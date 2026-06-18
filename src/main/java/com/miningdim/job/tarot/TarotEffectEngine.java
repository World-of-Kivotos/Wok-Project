package com.miningdim.job.tarot;

import com.miningdim.effect.ModJobEffects;
import com.miningdim.job.tarot.card.TarotCardData;
import com.miningdim.job.tarot.card.TarotEffectOp;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * 牌效执行引擎 (TarotReader spec 第六章)。把一张牌某品质某朝向的 {@link TarotEffectOp} 列表逐条施加到世界:
 * 给自己/友方加药水、瞬治、加黄心、改最大生命、净化, 给敌方 AoE 药水/伤害。
 *
 * 关键平衡红线 (spec 第五/十二章):
 *  - 抗性药水 amplifier 封顶 III (=2): 解析期与施加期双重钳制, 绝不施加 Resistance IV+。
 *  - 自伤绕过护甲/抗性/吸收: 用 setHealth 直接扣血 (不走 hurt, 否则被自身抗性/黄心吞掉)。
 *  - 强增益 (抗性/隐身) 同类 MobEffect 不可续期: 已有同类效果时拒绝刷新 (spec 第五章 4)。
 *  - 黄心 (吸收) 用 max(现有, 目标) 施加: 不被无关黄心来源整张掐死, 也不削减更高现值; 不可续期由每卡 CD 约束。
 *  - 最大生命增减统一经 {@link MaxHealthModifierManager} (按来源聚合 transient 修饰, 防泄漏/防交叉覆盖)。
 *  - 战斗窗口机制 (免疫击退/吸血/反伤/无敌/复活契约) 经 {@link TarotCombatState} + {@link TarotCombatHandlers} 事件结算。
 *  - 周期/延迟效果经 {@link ScheduledEffectManager} (登出/死亡清队列)。
 *
 * 全部服务端执行 (调用方保证 !level.isClientSide); 数值已是 datapack 按品质档填好的绝对值。
 */
public final class TarotEffectEngine {

    /** 抗性 (Resistance) amplifier 封顶 III = 2 (spec 第五章 1: 严禁 IV/V/VI)。 */
    private static final int RESISTANCE_MAX_AMPLIFIER = 2;

    private final MaxHealthModifierManager maxHealth;
    private final ScheduledEffectManager scheduler;

    public TarotEffectEngine(MaxHealthModifierManager maxHealth, ScheduledEffectManager scheduler) {
        this.maxHealth = maxHealth;
        this.scheduler = scheduler;
    }

    /**
     * 施加一整张牌效 (某品质某朝向的全部操作)。按列表顺序逐条施加。SELF_DEATH_GAMBLE 命中 (玩家当场死亡) 时
     * 立即中止剩余 op (人都死了, 后续力量/吸收无意义; spec 倒吊人逆位 "成功则..." 仅在存活时给收益)。
     */
    public void applyCard(ServerLevel level, ServerPlayer caster, TarotCardData data,
                          TarotQuality quality, boolean upright) {
        List<TarotEffectOp> ops = data.opsFor(quality, upright);
        for (TarotEffectOp op : ops) {
            if (op.kind() == com.miningdim.job.tarot.card.TarotEffectKind.SELF_DEATH_GAMBLE) {
                boolean died = applyDeathGamble(caster, op);
                if (died) {
                    return; // 赌输当场死亡: 后续收益 op 全部不再施加。
                }
                continue;
            }
            applyOp(level, caster, op);
        }
    }

    private void applyOp(ServerLevel level, ServerPlayer caster, TarotEffectOp op) {
        switch (op.kind()) {
            case SELF_POTION -> applySelfPotion(caster, op);
            case SELF_HEAL -> caster.heal((float) op.amount());
            case SELF_HEAL_OVER_TIME -> scheduleHealOverTime(caster, op);
            case SELF_PERIODIC_ABSORPTION -> schedulePeriodicAbsorption(caster, op);
            case SELF_FULL_HEAL -> caster.setHealth(caster.getMaxHealth());
            case SELF_TRUE_DAMAGE -> dealTrueDamage(caster, op.amount());
            case SELF_ABSORPTION -> addAbsorption(caster, (float) op.amount());
            case SELF_MAX_HEALTH -> applyMaxHealth(caster, op);
            case SELF_CLEANSE -> cleanse(caster);
            case SELF_DEATH_CONTRACT ->
                    TarotCombatState.openDeathContract(caster, op.durationTicks(), op.amount());
            case SELF_KNOCKBACK_IMMUNITY ->
                    TarotCombatState.openKnockbackImmunity(caster, op.durationTicks());
            case SELF_LIFESTEAL ->
                    TarotCombatState.openLifesteal(caster, op.durationTicks(), op.percent());
            case SELF_REFLECT ->
                    TarotCombatState.openReflect(caster, op.durationTicks(), op.percent(), op.capUp());
            case SELF_INVULNERABLE ->
                    TarotCombatState.openInvulnerable(caster, op.durationTicks());
            case ENEMY_TARGET_DAMAGE -> targetDamage(level, caster, op);
            case ENEMY_TARGET_AVERAGE_HEALTH -> targetAverageHealth(level, caster, op);
            case AOE_ENEMY_RANDOM_DAMAGE -> aoeEnemyRandomDamage(level, caster, op);
            case AOE_ENEMY_POTION -> aoeEnemyPotion(level, caster, op);
            case AOE_ENEMY_DAMAGE -> aoeEnemyDamage(level, caster, op);
            case AOE_ALLY_POTION -> aoeAllyPotion(level, caster, op);
            case AOE_ALLY_HEAL -> aoeAllyHeal(level, caster, op);
            case AOE_ALLY_ABSORPTION -> aoeAllyAbsorption(level, caster, op);
            default -> throw new IllegalStateException("Unhandled tarot effect kind: " + op.kind());
        }
    }

    /**
     * 以命相赌 (倒吊人逆位): chance 概率当场死亡 (服务端 RNG). 赌输直接 setHealth(0) 触发死亡 (走死亡管线,
     * 死亡不掉落环境下不丢物); 赌赢牺牲最大生命 amount (durationTicks 后归还, 下限 floorDown), 返回 false 让
     * 剩余收益 op 继续施加。
     *
     * @return true 赌输死亡 (中止剩余 op); false 存活 (已牺牲最大生命, 继续后续 op)
     */
    private boolean applyDeathGamble(ServerPlayer caster, TarotEffectOp op) {
        if (rollDeath(caster.getRandom(), op.chance())) {
            caster.setHealth(0.0F);
            return true;
        }
        // 牺牲当前最大生命 amount (负向修饰), durationTicks 后归还; 下限 floorDown。
        applyMaxHealthDelta(caster, -op.amount(), 0.0D, op.floorDown(), op.durationTicks());
        return false;
    }

    /** 赌死判定 (倒吊人逆位): rng < chance 即当场死亡。抽出供 TDD 大样本统计 R 20%/UR 2% 区间。 */
    public static boolean rollDeath(net.minecraft.util.RandomSource rng, double chance) {
        return rng.nextDouble() < chance;
    }

    // ---- self ----

    private void applySelfPotion(LivingEntity target, TarotEffectOp op) {
        MobEffect effect = resolveEffect(op.effectId());
        int amplifier = clampAmplifierForEffect(effect, op.amplifier());
        // 强增益同类不可续期 (spec 第五章 4): 已有同类则拒绝刷新。
        if (isNonRefreshable(effect) && target.hasEffect(effect)) {
            return;
        }
        target.addEffect(new MobEffectInstance(effect, op.durationTicks(), amplifier));
    }

    private void scheduleHealOverTime(ServerPlayer caster, TarotEffectOp op) {
        float heal = (float) op.amount();
        // 首次延迟一个周期 (愚者 "每 30s 治疗 x3" = 30s 后首次, 共 3 次)。
        scheduler.schedule(caster, op.durationTicks(), op.durationTicks(), op.count(),
                p -> p.heal(heal));
    }

    /** 自伤: 直接 setHealth 扣血, 绕过护甲/抗性/吸收 (spec 红线: 否则被自身 buff 吞)。下限 0 (不致死由牌设计保证)。 */
    private void dealTrueDamage(ServerPlayer caster, double amount) {
        float newHealth = (float) Math.max(0.0D, caster.getHealth() - amount);
        caster.setHealth(newHealth);
    }

    /**
     * 加黄心 (吸收): 取 max(现有, 目标), 不把更高的现有黄心改小, 也不被无关黄心来源整张掐死 (spec C 修正:
     * 续期判定应基于本系统标记而非全局 getAbsorptionAmount>0)。塔罗强增益 "不可续期" 由每卡 CD (20-60s) 限制,
     * 不再用 "已有任意黄心则拒绝" 这种会被食物/图腾黄心连带哑火的判定。
     */
    private void addAbsorption(LivingEntity target, float amount) {
        target.setAbsorptionAmount(Math.max(target.getAbsorptionAmount(), amount));
    }

    private void applyMaxHealth(ServerPlayer caster, TarotEffectOp op) {
        applyMaxHealthDelta(caster, op.amount(), op.capUp(), op.floorDown(), op.durationTicks());
    }

    /** 经 {@link MaxHealthModifierManager} 按来源累加施加最大生命增减, durationTicks 后只回退本来源那一份。 */
    private void applyMaxHealthDelta(ServerPlayer caster, double delta, double capUp,
                                     double floorDown, int durationTicks) {
        // 每张牌一次施加分配一个唯一来源 token, 同一玩家两张最大生命牌互不覆盖, 各自到期独立回退 (spec C 修正)。
        UUID source = UUID.randomUUID();
        maxHealth.apply(caster, source, delta, capUp, floorDown);
        if (durationTicks > 0) {
            scheduler.scheduleOnce(caster, durationTicks, p -> maxHealth.remove(p, source));
        }
    }

    private void schedulePeriodicAbsorption(ServerPlayer caster, TarotEffectOp op) {
        // 世界闪耀 "每 5s 补 25 黄心": 周期把吸收补至至少 amount (max 语义不削减更高现值)。
        float floor = (float) op.amount();
        scheduler.schedule(caster, op.durationTicks(), op.durationTicks(), op.count(),
                p -> p.setAbsorptionAmount(Math.max(p.getAbsorptionAmount(), floor)));
    }

    /** 清除全部负面效果 (spec 教皇/节制/星星)。 */
    private void cleanse(LivingEntity target) {
        target.getActiveEffects().stream()
                .map(MobEffectInstance::getEffect)
                .filter(e -> e.getCategory() == MobEffectCategory.HARMFUL)
                .toList()
                .forEach(target::removeEffect);
    }

    // ---- AoE ----

    private void aoeEnemyPotion(ServerLevel level, ServerPlayer caster, TarotEffectOp op) {
        MobEffect effect = resolveEffect(op.effectId());
        int amplifier = clampAmplifierForEffect(effect, op.amplifier());
        for (LivingEntity enemy : enemiesInRadius(level, caster, op.radius())) {
            enemy.addEffect(new MobEffectInstance(effect, op.durationTicks(), amplifier));
        }
    }

    private void aoeEnemyDamage(ServerLevel level, ServerPlayer caster, TarotEffectOp op) {
        float dmg = (float) op.amount();
        for (LivingEntity enemy : enemiesInRadius(level, caster, op.radius())) {
            enemy.hurt(level.damageSources().magic(), dmg);
        }
    }

    /** 半径内随机抽 1 敌打 amount; 无敌则使用者受双倍真伤 (恋人逆位 "无敌人则自己双倍")。 */
    private void aoeEnemyRandomDamage(ServerLevel level, ServerPlayer caster, TarotEffectOp op) {
        List<LivingEntity> enemies = enemiesInRadius(level, caster, op.radius());
        if (enemies.isEmpty()) {
            dealTrueDamage(caster, op.amount() * 2.0D);
            return;
        }
        LivingEntity target = enemies.get(caster.getRandom().nextInt(enemies.size()));
        target.hurt(level.damageSources().magic(), (float) op.amount());
    }

    /**
     * 准星单体 (死神正位): 取准星指向的敌方目标, 当前血 < threshold 直接处决 (setHealth 0), 否则 amount 穿刺。
     * 无准星目标则空过 (单体牌打空不报错, 与 AoE 打空一致)。
     */
    private void targetDamage(ServerLevel level, ServerPlayer caster, TarotEffectOp op) {
        LivingEntity target = crosshairEnemy(level, caster, op.radius());
        if (target == null) {
            return;
        }
        if (target.getHealth() < op.threshold()) {
            target.hurt(level.damageSources().magic(), target.getHealth()); // 处决: 一击至 0。
        } else {
            target.hurt(level.damageSources().magic(), (float) op.amount());
        }
    }

    /**
     * 均值化 (正义逆位): 准星目标与使用者当前血各设为两者均值, 单次最多 ±capUp (绝对 HP)。无准星目标则空过。
     * 用 setHealth/heal 直接改血 (绕过抗性/黄心; 均值化是改血量不是造成伤害)。
     */
    private void targetAverageHealth(ServerLevel level, ServerPlayer caster, TarotEffectOp op) {
        LivingEntity target = crosshairEnemy(level, caster, op.radius());
        if (target == null) {
            return;
        }
        float selfHp = caster.getHealth();
        float enemyHp = target.getHealth();
        float mean = (selfHp + enemyHp) / 2.0F;
        float cap = (float) op.capUp();
        caster.setHealth(clampToward(selfHp, mean, cap));
        target.setHealth(clampToward(enemyHp, mean, cap));
    }

    /** 把 from 朝 target 移动, 单步最多 maxDelta (单次最多 ±maxDelta 的均值化钳制)。 */
    private static float clampToward(float from, float target, float maxDelta) {
        float delta = target - from;
        if (delta > maxDelta) {
            delta = maxDelta;
        } else if (delta < -maxDelta) {
            delta = -maxDelta;
        }
        return Math.max(0.0F, from + delta);
    }

    private void aoeAllyPotion(ServerLevel level, ServerPlayer caster, TarotEffectOp op) {
        MobEffect effect = resolveEffect(op.effectId());
        int amplifier = clampAmplifierForEffect(effect, op.amplifier());
        for (Player ally : alliesInRadius(level, caster, op.radius())) {
            if (isNonRefreshable(effect) && ally.hasEffect(effect)) {
                continue;
            }
            ally.addEffect(new MobEffectInstance(effect, op.durationTicks(), amplifier));
        }
    }

    private void aoeAllyHeal(ServerLevel level, ServerPlayer caster, TarotEffectOp op) {
        float heal = (float) op.amount();
        for (Player ally : alliesInRadius(level, caster, op.radius())) {
            ally.heal(heal);
        }
    }

    private void aoeAllyAbsorption(ServerLevel level, ServerPlayer caster, TarotEffectOp op) {
        float amount = (float) op.amount();
        for (Player ally : alliesInRadius(level, caster, op.radius())) {
            ally.setAbsorptionAmount(Math.max(ally.getAbsorptionAmount(), amount));
        }
    }

    // ---- helpers ----

    private List<LivingEntity> enemiesInRadius(ServerLevel level, ServerPlayer caster, double radius) {
        AABB box = caster.getBoundingBox().inflate(radius);
        // 敌方 = 半径内非友方 LivingEntity (含敌对玩家, PvP+PvE; spec 部署环境)。友方/自身排除见 isEnemy。
        return level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && e.distanceTo(caster) <= radius && isEnemy(caster, e));
    }

    /**
     * 敌我判定 (PvP+PvE; spec 第六章多张战斗牌的 "敌" 在 PvP 中应能命中敌对玩家, 不可用 Mob.class 一刀切排除玩家)。
     *  - caster 本人: 永远不是敌人;
     *  - 玩家: 仅当 caster.canHarmPlayer(other) (尊重计分板队伍 + 友伤开关) 才算敌人;
     *  - 非玩家生物: 排除 caster 的友方 (被驯服/同队), 其余 (怪物/中立) 算敌人。
     */
    private static boolean isEnemy(ServerPlayer caster, LivingEntity entity) {
        if (entity == caster) {
            return false;
        }
        if (entity instanceof Player other) {
            return caster.canHarmPlayer(other);
        }
        return !entity.isAlliedTo(caster);
    }

    /**
     * 准星指向的敌方 LivingEntity (死神正位/正义逆位 "准星目标")。沿使用者视线 reach 格做实体射线检测,
     * 命中的实体须通过 {@link #isEnemy} (友方/自身不可被锁定)。无命中返回 null。
     */
    private static LivingEntity crosshairEnemy(ServerLevel level, ServerPlayer caster, double reach) {
        Vec3 eye = caster.getEyePosition();
        Vec3 end = eye.add(caster.getViewVector(1.0F).scale(reach));
        AABB searchBox = caster.getBoundingBox().expandTowards(caster.getViewVector(1.0F).scale(reach)).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                level, caster, eye, end, searchBox,
                e -> e instanceof LivingEntity living && isEnemy(caster, living));
        if (hit == null || !(hit.getEntity() instanceof LivingEntity target)) {
            return null;
        }
        return target;
    }

    private List<Player> alliesInRadius(ServerLevel level, ServerPlayer caster, double radius) {
        AABB box = caster.getBoundingBox().inflate(radius);
        // 友方 = 半径内玩家 (含 caster 本人, 多数 "友方恢复" 含自己)。
        return level.getEntitiesOfClass(Player.class, box,
                p -> p.isAlive() && p.distanceTo(caster) <= radius);
    }

    private static MobEffect resolveEffect(String effectId) {
        ResourceLocation id = ResourceLocation.tryParse(effectId);
        if (id == null) {
            throw new IllegalArgumentException("invalid effect id in tarot datapack: " + effectId);
        }
        // 易伤是本 mod 自定义效果, 不在原版注册表常量里, 走 ForgeRegistries 统一解析。
        MobEffect effect = BuiltInRegistries.MOB_EFFECT.get(id);
        if (effect == null) {
            throw new IllegalArgumentException("unknown MobEffect in tarot datapack: " + effectId);
        }
        return effect;
    }

    /** 抗性封顶 III (spec 第五章 1); 其余效果不限。在施加期再钳一次 (datapack 写错也不越界)。 */
    private static int clampAmplifierForEffect(MobEffect effect, int amplifier) {
        if (effect == MobEffects.DAMAGE_RESISTANCE) {
            return Math.min(amplifier, RESISTANCE_MAX_AMPLIFIER);
        }
        return amplifier;
    }

    /** 强增益判定 (spec 第五章 4: 抗性/隐身/吸收/复活同类不可续期; 吸收单独走 setAbsorption 判定)。 */
    private static boolean isNonRefreshable(MobEffect effect) {
        return effect == MobEffects.DAMAGE_RESISTANCE
                || effect == MobEffects.INVISIBILITY;
    }
}
