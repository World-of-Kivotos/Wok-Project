package com.miningdim.job.tarot;

import com.miningdim.job.tarot.card.TarotCardData;
import com.miningdim.job.tarot.card.TarotEffectOp;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
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

    /**
     * 死神闪耀 "回血/叠层仅对玩家/精英" 的精英判定阈值 (maxHealth)。80 血公服下普通杂兵 (僵尸 20 / 骷髅 20 等)
     * 远低于此, 6 星精英/Boss/玩家 (>=80) 高于此; 玩家另在 {@link #isEliteForReward} 恒判精英。本阈值不进 config:
     * 它只决定死神闪耀单张牌的回血/叠层资格, 不是系统级闸门 (复杂度匹配问题; YAGNI)。
     */
    private static final float ELITE_HP_THRESHOLD = 80.0F;

    /** 死神闪耀力量叠层的续期时长 (ticks); 15s, 与签名窗同量级。 */
    private static final int STRENGTH_STACK_DURATION = 300;

    /**
     * 周期 AoE DoT (太阳每秒灼敌) 单跳对每个目标的伤害上限 = 目标最大生命的此占比 (平衡红线: 参照精英怪 DoT 的
     * 15% maxHP/s, 防 datapack 的扁平每跳值在低血杂兵上离谱; spec 给扁平值, 此处只钳上界不抬下界, 满血厚血目标
     * 按 spec 扁平值生效)。
     */
    private static final double MAX_DOT_PCT_PER_TICK = 0.15D;

    /**
     * 周期 AoE 友方回血 (太阳闪耀每秒为友回血) 单跳对每个目标的治疗上限 = 目标最大生命的此占比 (与 DoT 同口径上界,
     * 防扁平回血值在低血上限玩家身上离谱; 80 血公服按 spec 扁平 12/s 远低于此, clamp 仅作红线兜底)。
     */
    private static final double MAX_HEAL_PCT_PER_TICK = 0.15D;

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
            case SELF_REFLECT_ACCUM -> openReflectAccum(caster, op);
            case SELF_DELAYED_LEDGER -> openDelayedLedger(caster, op);
            case SELF_INVULNERABLE ->
                    TarotCombatState.openInvulnerable(caster, op.durationTicks());
            case SHINY_BIND_SHARE_LIFE -> bindShareLife(level, caster, op);
            case ENEMY_TARGET_DAMAGE -> targetDamage(level, caster, op);
            case ENEMY_TARGET_AVERAGE_HEALTH -> targetAverageHealth(level, caster, op);
            case AOE_ENEMY_RANDOM_DAMAGE -> aoeEnemyRandomDamage(level, caster, op);
            case AOE_ENEMY_POTION -> aoeEnemyPotion(level, caster, op);
            case AOE_ENEMY_DAMAGE -> aoeEnemyDamage(level, caster, op);
            case AOE_EXECUTE_BELOW_PCT -> aoeExecuteBelowPct(level, caster, op);
            case AOE_ALLY_POTION -> aoeAllyPotion(level, caster, op);
            case AOE_ALLY_HEAL -> aoeAllyHeal(level, caster, op);
            case AOE_ALLY_ABSORPTION -> aoeAllyAbsorption(level, caster, op);
            case AOE_ENEMY_DAMAGE_OVER_TIME -> scheduleAoeEnemyDamageOverTime(caster, op);
            case AOE_ALLY_HEAL_OVER_TIME -> scheduleAoeAllyHealOverTime(caster, op);
            case IMMUNITY -> applyImmunity(caster, op);
            default -> throw new IllegalStateException("Unhandled tarot effect kind: " + op.kind());
        }
    }

    /**
     * 以命相赌 (倒吊人逆位): chance 概率当场死亡 (服务端 RNG). 赌输直接 setHealth(0) 触发死亡 (走死亡管线,
     * 死亡不掉落环境下不丢物); 赌赢牺牲最大生命 amount (durationTicks 后归还, 下限 floorDown), 返回 false 让
     * 剩余收益 op 继续施加。
     *
     * 与死神逆位复活契约的边界 (review Minor): 若赌输但玩家身上有未过期复活契约, 直接 setHealth(0) 会被
     * {@link TarotCombatHandlers#onLivingDeath} 的契约拦截复活, 而本法已返回 true 中止后续收益 op —— 玩家既没
     * 真死也没拿到赌赢收益 (一次空过)。修正: 赌输前先判定有效契约, 有则视为 "契约救命未真死", 显式消费契约并按
     * 存活路径继续给收益 (契约的一次性拦截在此被这次自杀消耗, 与拦截外部致死同口径)。
     *
     * @return true 赌输且无契约救命 (当场死亡, 中止剩余 op); false 存活 (赌赢, 或赌输但被契约救; 继续后续 op)
     */
    private boolean applyDeathGamble(ServerPlayer caster, TarotEffectOp op) {
        if (rollDeath(caster.getRandom(), op.chance())) {
            MinecraftServer server = caster.getServer();
            long now = server == null ? 0L : server.getTickCount();
            double revive = server == null ? -1.0D
                    : TarotCombatState.consumeDeathContract(caster.getUUID(), now);
            if (revive < 0.0D) {
                caster.setHealth(0.0F);
                return true; // 无契约: 真死, 中止收益。
            }
            // 有契约救命: 视为未真死。按契约复活血量保命 (与 onLivingDeath 拦截外部致死同口径), 落入存活收益路径。
            caster.setHealth((float) Math.min(revive, caster.getMaxHealth()));
        }
        // 牺牲当前最大生命 amount (负向修饰), durationTicks 后归还; 下限 floorDown。
        applyMaxHealthDelta(caster, -op.amount(), 0.0D, op.floorDown(), op.durationTicks());
        return false;
    }

    /** 赌死判定 (倒吊人逆位): rng < chance 即当场死亡。抽出供 TDD 大样本统计 R 20%/UR 2% 区间。 */
    public static boolean rollDeath(net.minecraft.util.RandomSource rng, double chance) {
        return rng.nextDouble() < chance;
    }

    /**
     * 测试钩子 (同包可见): 直接驱动 {@link #applyDeathGamble} 验证赌死 x 契约边界 (review Minor)。
     * 返回值同 {@link #applyDeathGamble} (true 真死中止; false 存活继续收益)。
     */
    boolean applyDeathGambleForTest(ServerPlayer caster, TarotEffectOp op) {
        return applyDeathGamble(caster, op);
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

    /**
     * 累计反击窗 (正义闪耀结算尾): 开窗 durationTicks 内逐攻击者累计伤害 (handler 记账), 排一个窗口结束的结算
     * 任务: 对 radius 格内仍在场的每个攻击者各回击其累计伤害的 percent (单次封顶 capUp; spec 40% 封顶 60)。
     */
    private void openReflectAccum(ServerPlayer caster, TarotEffectOp op) {
        double radius = op.radius();
        TarotCombatState.openReflectAccum(caster, op.durationTicks(), op.percent(), op.capUp(), radius);
        // 半径在闭包内捕获: drain 会移除窗口, 结算时已无法再从窗口读半径。
        scheduler.scheduleOnce(caster, op.durationTicks(), p -> settleReflectAccum(p, radius));
    }

    /** 窗口结束结算: 抽干账本, 对半径内仍在场的攻击者各回击累计伤害的 percent (封顶), 用 magic 源 (不触吸血/反伤递归)。 */
    private void settleReflectAccum(ServerPlayer caster, double radius) {
        java.util.Map<UUID, Double> retaliations = TarotCombatState.drainReflectAccum(caster.getUUID());
        if (retaliations.isEmpty() || !(caster.level() instanceof ServerLevel level)) {
            return;
        }
        AABB box = caster.getBoundingBox().inflate(radius);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
            Double dmg = retaliations.get(entity.getUUID());
            if (dmg != null && dmg > 0.0D && entity.distanceTo(caster) <= radius) {
                entity.hurt(level.damageSources().magic(), dmg.floatValue());
            }
        }
    }

    /**
     * 延迟记账冻死窗 (倒吊人闪耀): 开窗 durationTicks 内伤害挂账且致命伤被冻结 (handler 拦致死), 排窗口结束结算:
     * 扣 pendingDamage 的 percent (绕护甲/抗性 setHealth), 若仍存活则额外回 amount 血 (spec 结束 50% + 存活 +40)。
     */
    private void openDelayedLedger(ServerPlayer caster, TarotEffectOp op) {
        TarotCombatState.openLedger(caster, op.durationTicks(), op.percent(), op.amount());
        scheduler.scheduleOnce(caster, op.durationTicks(), this::settleLedger);
    }

    /** 倒吊人闪耀窗结束: 结算挂起伤害 50% (真扣血), 扣后存活则 +40 血。 */
    private void settleLedger(ServerPlayer caster) {
        double[] result = TarotCombatState.drainLedger(caster.getUUID());
        if (result == null) {
            return;
        }
        double settleDamage = result[0];
        double surviveHeal = result[1];
        // 结算扣血绕护甲/抗性 (与延迟期的冻结记账口径一致); 下限 0 (扣到 0 即真死, 不再回血)。
        float newHealth = (float) Math.max(0.0D, caster.getHealth() - settleDamage);
        caster.setHealth(newHealth);
        if (newHealth > 0.0F) {
            caster.heal((float) surviveHeal);
        }
    }

    /**
     * 绑定共享生死 (恋人闪耀): 准星锁定一个已 {@code /tarot consent} 同意的玩家 (reach=radius), 双向绑定
     * durationTicks; 无目标或目标未同意则空过 (签名打空不报错)。一方死则另一方延迟 count ticks 同死,
     * 距离 > radius 解绑 (距离/死亡判定在 handler 与 TarotSystem tick)。
     */
    private void bindShareLife(ServerLevel level, ServerPlayer caster, TarotEffectOp op) {
        LivingEntity target = crosshairAlly(level, caster, op.radius());
        if (!(target instanceof ServerPlayer partner) || partner == caster) {
            return;
        }
        if (!TarotConsentRegistry.consume(partner.getUUID(), caster.getServer().getTickCount())) {
            // 对方未在同意窗内 (需 /tarot consent): 不绑定 (spec "需同意"), 提示发起方。
            caster.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("message.miningdim.tarot.lovers.no_consent"), true);
            return;
        }
        TarotCombatState.openLifeBond(caster, partner, op.durationTicks(), op.radius(), op.count());
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

    /**
     * 处决斩杀 AoE (死神闪耀): radius 格内当前血占比 &lt; percent 的敌处决 (setHealth 0); 每处决一个玩家/精英给
     * caster 回 threshold 血并把力量提升一级 (上限 amplifier=4=V)。若无任何处决目标则对全体敌各 amount 穿刺。
     *
     * "回血/叠层仅对玩家/精英" (spec): 精英判定无原版/本工程现成标签, 用 maxHealth >= ELITE_HP_THRESHOLD 作确定性
     * 代理 (80 血公服下杂兵远低于此, 6 星精英/Boss 高于此); 玩家恒视为精英 (PvP)。普通杂兵被处决不给回血/叠层。
     */
    private void aoeExecuteBelowPct(ServerLevel level, ServerPlayer caster, TarotEffectOp op) {
        List<LivingEntity> enemies = enemiesInRadius(level, caster, op.radius());
        int executed = 0;
        int killedEliteCount = 0;
        for (LivingEntity enemy : enemies) {
            float maxHp = enemy.getMaxHealth();
            if (maxHp > 0.0F && enemy.getHealth() / maxHp < op.percent()) {
                boolean elite = isEliteForReward(enemy);
                enemy.hurt(level.damageSources().magic(), enemy.getHealth()); // 处决: 一击至 0。
                executed++;
                if (elite) {
                    killedEliteCount++;
                }
            }
        }
        if (executed == 0) {
            // 无处决目标: 全体敌各 amount 穿刺 (spec "无目标则全体 50 穿刺")。
            float dmg = (float) op.amount();
            for (LivingEntity enemy : enemies) {
                enemy.hurt(level.damageSources().magic(), dmg);
            }
            return;
        }
        // 每处决一个玩家/精英: 回 threshold 血 + 力量叠一级 (上限 amplifier)。
        if (killedEliteCount > 0) {
            caster.heal((float) (op.threshold() * killedEliteCount));
            stackStrengthUpTo(caster, op.amplifier(), killedEliteCount);
        }
    }

    /** 精英判定 (死神闪耀回血/叠层资格): 玩家恒精英; 非玩家生物按 maxHealth 阈值代理 (见 ELITE_HP_THRESHOLD)。 */
    private static boolean isEliteForReward(LivingEntity entity) {
        return entity instanceof Player || entity.getMaxHealth() >= ELITE_HP_THRESHOLD;
    }

    /**
     * 力量叠层至上限 (死神闪耀每杀一精英叠一级, 不超 maxAmplifier=4=V)。按已有力量等级累加 killCount 级, 钳到上限,
     * 续期等长 STRENGTH_STACK_DURATION (不可续期约束仅针对抗性/隐身等防御性强增益, 力量按击杀叠层是设计内特例)。
     */
    private static void stackStrengthUpTo(ServerPlayer caster, int maxAmplifier, int killCount) {
        MobEffectInstance current = caster.getEffect(MobEffects.DAMAGE_BOOST);
        int currentAmp = current == null ? -1 : current.getAmplifier();
        int newAmp = Math.min(maxAmplifier, currentAmp + killCount);
        if (newAmp < 0) {
            return;
        }
        caster.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, STRENGTH_STACK_DURATION, newAmp));
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

    // ---- 周期 AoE DoT / 友方回血 (太阳每秒灼敌 / 闪耀每秒为友回血) ----

    /**
     * 太阳每秒灼敌 (AOE_ENEMY_DAMAGE_OVER_TIME): 经调度器每 periodTicks 对 owner 当前半径内敌各 amount 伤害, 共
     * durationTicks/periodTicks 跳。每跳按 owner 实时坐标重取敌 (周期内移动/新进入半径的敌也吃灼烧)。单跳每目标伤害
     * 经 {@link #clampDotPerTick} 钳到目标最大生命的上限 (红线)。owner 离线/死亡由调度器按 UUID 自动跳过并取消队列。
     */
    private void scheduleAoeEnemyDamageOverTime(ServerPlayer caster, TarotEffectOp op) {
        int period = op.periodTicks();
        int count = periodicCount(op.durationTicks(), period);
        if (count <= 0) {
            return;
        }
        double amount = op.amount();
        double radius = op.radius();
        scheduler.schedule(caster, period, period, count, p -> tickAoeEnemyDamage(p, amount, radius));
    }

    /**
     * 测试钩子 (同包可见): 直接驱动一跳灼敌 (绕过调度器的 server 时钟依赖, GameTest 单帧内无法推进 server tick)。
     * 周期跳数由 {@link #periodicCount} 单测; 本钩子让 TDD 端到端断言"半径内累计掉血/半径外不掉/clamp 红线"。
     */
    void tickAoeEnemyDamageForTest(ServerPlayer caster, double amount, double radius) {
        tickAoeEnemyDamage(caster, amount, radius);
    }

    /** 测试钩子 (同包可见): 直接驱动一跳为友回血 (绕过调度器 server 时钟依赖)。 */
    void tickAoeAllyHealForTest(ServerPlayer caster, double amount, double radius) {
        tickAoeAllyHeal(caster, amount, radius);
    }

    /** 单跳灼敌: 对 caster 当前半径内每个敌按 clamp 后伤害施加 (magic 源, 与一次性 aoeEnemyDamage 同口径)。 */
    private void tickAoeEnemyDamage(ServerPlayer caster, double amount, double radius) {
        if (!(caster.level() instanceof ServerLevel level)) {
            return;
        }
        for (LivingEntity enemy : enemiesInRadius(level, caster, radius)) {
            float dmg = (float) clampDotPerTick(amount, enemy.getMaxHealth());
            if (dmg > 0.0F) {
                enemy.hurt(level.damageSources().magic(), dmg);
            }
        }
    }

    /**
     * 太阳闪耀每秒为友回血 (AOE_ALLY_HEAL_OVER_TIME): 经调度器每 periodTicks 对 owner 当前半径内友方各瞬治 amount,
     * 共 durationTicks/periodTicks 跳。单跳每目标治疗经 {@link #clampHealPerTick} 钳到目标最大生命的上限。
     */
    private void scheduleAoeAllyHealOverTime(ServerPlayer caster, TarotEffectOp op) {
        int period = op.periodTicks();
        int count = periodicCount(op.durationTicks(), period);
        if (count <= 0) {
            return;
        }
        double amount = op.amount();
        double radius = op.radius();
        scheduler.schedule(caster, period, period, count, p -> tickAoeAllyHeal(p, amount, radius));
    }

    /** 单跳为友回血: 对 caster 当前半径内每个友方按 clamp 后治疗量 heal。 */
    private void tickAoeAllyHeal(ServerPlayer caster, double amount, double radius) {
        if (!(caster.level() instanceof ServerLevel level)) {
            return;
        }
        for (Player ally : alliesInRadius(level, caster, radius)) {
            float heal = (float) clampHealPerTick(amount, ally.getMaxHealth());
            if (heal > 0.0F) {
                ally.heal(heal);
            }
        }
    }

    /**
     * 周期跳数 = 总时长 / 周期 (向下取整; 太阳 "20 秒每秒灼" = 400/20 = 20 跳)。周期或时长非正则返回 0 (无跳, 空过)。
     * 抽出供 TDD 直接断言跳数计算 (周期 op 累计伤害 == 单跳 x 跳数)。
     */
    public static int periodicCount(int durationTicks, int periodTicks) {
        if (periodTicks <= 0 || durationTicks <= 0) {
            return 0;
        }
        return durationTicks / periodTicks;
    }

    /**
     * DoT 单跳每目标伤害钳制: 取 min(spec 扁平值, 目标最大生命 x {@link #MAX_DOT_PCT_PER_TICK})。抽出供 TDD 断言
     * 红线 (删 clamp 则扁平值在低血杂兵上击穿 15% 上限, 断言挂)。
     */
    public static double clampDotPerTick(double flatAmount, double targetMaxHealth) {
        if (targetMaxHealth <= 0.0D) {
            return 0.0D;
        }
        return Math.min(flatAmount, targetMaxHealth * MAX_DOT_PCT_PER_TICK);
    }

    /** 回血单跳每目标治疗钳制: 取 min(spec 扁平值, 目标最大生命 x {@link #MAX_HEAL_PCT_PER_TICK})。供 TDD 断言。 */
    public static double clampHealPerTick(double flatAmount, double targetMaxHealth) {
        if (targetMaxHealth <= 0.0D) {
            return 0.0D;
        }
        return Math.min(flatAmount, targetMaxHealth * MAX_HEAL_PCT_PER_TICK);
    }

    // ---- 免疫窗 (太阳/世界/力量/恶魔闪耀的 IMMUNITY op) ----

    /**
     * 开免疫窗 (IMMUNITY): 把 op.effects() 的注册名解析校验后 (走 {@link #resolveEffect}, 未知名抛出冒泡, 与其它
     * effect 引用同口径不静默) 存进 {@link TarotCombatState} 免疫窗, 拒绝施加期由 {@link TarotCombatHandlers} 读窗。
     * 解析仅做校验 (确认 datapack 写的是真 effect), 窗口存注册名字符串 (handler 端按 effect 的注册名比对, 无须持
     * MobEffect 实例)。
     */
    private void applyImmunity(ServerPlayer caster, TarotEffectOp op) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (String effectId : op.effects()) {
            resolveEffect(effectId); // 校验: 未知 effect 名抛 IllegalArgumentException 冒泡 (C9 不静默)。
            ids.add(effectId);
        }
        TarotCombatState.openImmunity(caster, op.durationTicks(), ids, op.immuneVulnerability());
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

    /**
     * 准星指向的友方玩家 (恋人闪耀 "锁定 1 玩家")。沿视线 reach 格射线命中的玩家 (排除自身)。无命中返回 null。
     * 与 {@link #crosshairEnemy} 对称, 但锁的是玩家 (绑定对象必须是玩家, 共享生死无意义于杂兵)。
     */
    private static LivingEntity crosshairAlly(ServerLevel level, ServerPlayer caster, double reach) {
        Vec3 eye = caster.getEyePosition();
        Vec3 end = eye.add(caster.getViewVector(1.0F).scale(reach));
        AABB searchBox = caster.getBoundingBox().expandTowards(caster.getViewVector(1.0F).scale(reach)).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                level, caster, eye, end, searchBox,
                e -> e instanceof Player other && other != caster);
        if (hit == null || !(hit.getEntity() instanceof Player target)) {
            return null;
        }
        return target;
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
