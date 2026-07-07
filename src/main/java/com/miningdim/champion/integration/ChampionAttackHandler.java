package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionAttackValues;
import com.miningdim.champion.ChampionEffectRegistries;
import com.miningdim.champion.ChampionStrikeGate;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.StarRank;
import com.miningdim.champion.aggregate.PlayerDotSources;
import com.miningdim.champion.bloodpool.BloodPool;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.effect.ModJobEffects;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 冠军攻击玩家时攻击类词条效果的【集中单点施加】(Champions 集成层; ChampionStarAffix spec 7.2 战斗 + 9A.2 单点
 * 铁律 + 9A.3 #5/#6 on-hit)。冠军近战命中玩家时, 在【一个】 {@link LivingHurtEvent} 受击点统一施加全部攻击类
 * 词条 rider, 严禁逐词条 onAttack/onHurt 串行 (那会绕过红线 3 单击 ≤40% / 红线 4 DoT ≤15% / 红线 5 控制聚合)。
 *
 * 施加哪些 (spec 7.2):
 *  - 即时伤害 (重炮 +伤害放大 / 嗜血低血 +伤害放大 / 穿甲无视护甲真伤): 经 {@link ChampionAttackValues#singleHitTotalPct}
 *    合并 + 红线 3 单击/穿甲合计钳制, 折成额外 HP 叠到 event 伤害 (单点, 不逐词条 setAmount)。
 *  - DoT (燃烧 / 寒霜冻伤): on-hit 只【刷层 + 续 3s 刷新窗】进 per-player {@link PlayerDotSources} (受
 *    {@link ChampionStrikeGate} DoT 刷新内 CD ≥1s)。本秒及窗内每秒名义伤害补记 + ≤15% maxHP 聚合 + 寒霜减速持续
 *    施加全部由 {@link ChampionDotTickHandler} 每秒读在册层数完成 (spec 7.2 持续 DoT: 命中后 3s 窗内每秒持续扣血,
 *    不要求每秒新命中, 窗口过期自然停伤)。本 handler 不在受击点 record 名义伤害/扣 DoT 伤 (DoT 单一权威在 tick handler)。
 *  - 减速 (寒霜): 不在受击点施加, 由 tick handler 按窗内活跃寒霜源每秒持续进 per-player 控制聚合 (≤50% 总减速)。
 *  - 易伤 (撕裂): 复用全局 {@link com.miningdim.effect.VulnerabilityEffect}, 按撕裂层折 amplifier 续期, 乘伤由
 *    {@code VulnerabilityHurtHandler} 单点结算 (严禁另挂第二个易伤)。
 *  - 护甲磨损 (强酸): 损玩家护甲耐久 (纯经济磨损, 不入伤害口径)。
 *  - 击飞 (混沌重击): 受 {@link ChampionStrikeGate} 击飞内 CD + 落地恢复窗限频 (击飞落点安全守卫
 *    KnockbackSafetyGuard 未落地, 见 sharedFileEditsNeeded; 当前仅做限频闸 + 内 CD, 不直接 push)。
 *
 * 事件优先级 (EventPriority.HIGH): 须在玩家侧减伤单点 {@code PlayerDamageReduction} (LOWEST) 与易伤放大
 * {@code VulnerabilityHurtHandler} (默认) 之前把即时额外伤害叠入 event.getAmount(), 使后续减伤/易伤对完整冠军近战
 * 伤害结算 (先叠攻击词条额外伤 → 再易伤放大 → 再玩家减伤)。撕裂续期的是【下次】受击的易伤, 不放大本次。
 *
 * 单点铁律守卫: 本 handler 是冠军→玩家攻击的唯一施加点。冠军星级 + 词条→品质经自研
 * {@link com.miningdim.champion.MiningChampions} capability 读, 不触任何 top.theillusivec4.champions.*。由
 * {@code ChampionSystem#register} 无条件挂 forgeBus (自研后脱离 Champions 依赖, 不再 ModList 守卫, dev GameTest
 * 可加载可验; 纯数值/红线仍在 champion 包纯逻辑类 GameTest 验)。
 */
public final class ChampionAttackHandler {

    /** 撕裂/DoT 效果挂载时长 (tick): DoT 3s 刷新窗 + 易伤续期窗均取 3s = 60tick (spec 7.2: 3s 刷新)。 */
    private static final int RIDER_DURATION_TICKS = 60;

    /**
     * per-(玩家→冠军) 攻击交互状态: 外层键 = 受击玩家 UUID, 内层键 = 冠军 UUID。如此结构使玩家死亡/登出/换维度时
     * O(1) 清掉该玩家全部冠军交互状态 (反泄漏), 不必反解 XOR 配对键。服务端 tick 串行写, ConcurrentHashMap 仅防
     * 跨线程读可见性。
     */
    private final Map<UUID, Map<UUID, PairState>> stateByVictim = new ConcurrentHashMap<>();

    /**
     * 冠军近战命中玩家: 集中施加全部攻击类词条 rider (单点)。非冠军攻击者 / 非玩家受击者 / 本工程未盖章冠军
     * 直接放行 (vanilla 自理)。
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return; // 受击者非玩家: 攻击类词条只施于玩家。
        }
        // 反震反弹伤 (THORNS) 是冠军对攻击者的【反伤】(ChampionSelfEffectHandler 施加), 其 source.getEntity() 恰是
        // 冠军, 会误判为"冠军攻击玩家"从而额外触发一套 on-hit 攻击词条 (燃烧/寒霜/穿甲…)。反伤非攻击, 直接放行。
        if (event.getSource().is(DamageTypes.THORNS)) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof LivingEntity attacker)) {
            return; // 无直接生物攻击者 (环境/无主弹射物): 不施攻击词条。
        }
        MiningChampionData champ = MiningChampions.get(attacker).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 攻击者非本工程盖章冠军。
        }
        // 自研 cap 恒有 star ∈ [1,10] + 词条→品质 (promote 期一次写全), 无需旧命令召唤"无 NBT 走 tier 兜底"分支。
        int star = champ.star();
        StarRank starRank = StarRank.ofStar(star);
        Map<AffixDef, AffixQuality> equipped = champ.affixes();
        if (equipped.isEmpty()) {
            return; // 无本工程攻击词条。
        }

        long nowTick = victim.level().getGameTime();
        double playerMaxHp = victim.getMaxHealth();
        PairState pair = stateByVictim
                .computeIfAbsent(victim.getUUID(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(attacker.getUUID(), k -> new PairState());

        applyInstantDamage(event, equipped, starRank, attacker, victim, playerMaxHp);
        applyDotsAndSlow(equipped, pair, attacker.getUUID(), nowTick, victim);
        applyRend(equipped, pair, nowTick, victim);
        applyCorrosive(equipped, victim);
        applyChaosKnockback(equipped, pair, nowTick, attacker, victim);
    }

    /**
     * 即时伤害单点 (重炮 + 嗜血 + 穿甲): 合并放大 + 红线钳后折额外 HP 叠到 event。普通分量被重炮/嗜血放大夹到
     * 该星普通单击上限, 与穿甲真伤合计夹 ≤40% 单击。bonusOverVanilla 只补足到 %maxHP 名义值, 不削原版近战。
     */
    private void applyInstantDamage(LivingHurtEvent event, Map<AffixDef, AffixQuality> equipped,
                                    StarRank starRank, LivingEntity attacker, Player victim, double playerMaxHp) {
        double heavyCannonAmp = 0.0D;
        AffixQuality hc = equipped.get(AffixDef.HEAVY_CANNON);
        if (hc != null) {
            heavyCannonAmp = AffixDef.HEAVY_CANNON.valueFor(hc);
        }

        double bloodlustAmp = 0.0D;
        AffixQuality bl = equipped.get(AffixDef.BLOODLUST);
        if (bl != null) {
            bloodlustAmp = ChampionAttackValues.bloodlustDamageAmp(bl, championHpFraction(attacker));
        }

        double piercingPct = 0.0D;
        AffixQuality ap = equipped.get(AffixDef.ARMOR_PIERCING);
        if (ap != null) {
            piercingPct = AffixDef.ARMOR_PIERCING.valueFor(ap);
        }

        if (heavyCannonAmp == 0.0D && bloodlustAmp == 0.0D && piercingPct == 0.0D) {
            return; // 无即时伤害词条。
        }

        double totalPct = ChampionAttackValues.singleHitTotalPct(
                starRank, starRank.baseSingleHitPct(), heavyCannonAmp, bloodlustAmp, piercingPct);
        double bonusHp = ChampionAttackValues.bonusOverVanilla(totalPct, playerMaxHp, event.getAmount());
        if (bonusHp > 0.0D) {
            event.setAmount((float) (event.getAmount() + bonusHp));
        }
    }

    /**
     * DoT (燃烧/寒霜冻伤) on-hit 只【刷层 + 续 3s 刷新窗】进 per-player {@link PlayerDotSources} (受
     * {@link ChampionStrikeGate} 内 CD ≥1s 约束, 双倍/四倍分跳同帧不重复刷层)。本秒及窗内每秒名义伤害补记 +
     * 寒霜减速持续施加全部下放到 {@link ChampionDotTickHandler}: 命中后即便玩家风筝/冠军够不到, 只要仍在 3s 窗内
     * tick handler 都按当前在册层数每秒扣血 (spec 7.2: 持续 DoT, 不要求每秒新命中), 窗口过期自然停伤。受击点不再
     * 直接 record 名义伤害 (避免命中秒走受击点、非命中秒走 tick 的双路径; DoT 扣血单一权威收敛到 tick handler)。
     */
    private void applyDotsAndSlow(Map<AffixDef, AffixQuality> equipped, PairState pair, UUID attackerId, long nowTick,
                                  Player victim) {
        boolean hasBurning = equipped.containsKey(AffixDef.BURNING);
        boolean hasFrost = equipped.containsKey(AffixDef.FROST);
        if (!hasBurning && !hasFrost) {
            return;
        }

        // 命中即时火/雪粒子 (cosmetic 显示层, 与 DoT 扣血结算分离): 让被冠军点燃/冰冻在受击瞬间看得见。
        if (victim.level() instanceof ServerLevel serverLevel) {
            double cx = victim.getX();
            double cy = victim.getY() + victim.getBbHeight() * 0.5D;
            double cz = victim.getZ();
            double sx = victim.getBbWidth() * 0.4D;
            double sy = victim.getBbHeight() * 0.4D;
            if (hasBurning) {
                serverLevel.sendParticles(ParticleTypes.FLAME, cx, cy, cz, 8, sx, sy, sx, 0.02D);
            }
            if (hasFrost) {
                serverLevel.sendParticles(ParticleTypes.SNOWFLAKE, cx, cy, cz, 8, sx, sy, sx, 0.02D);
            }
        }

        if (!pair.gate.canRefreshDot(nowTick)) {
            return; // DoT 刷新内 CD 内 (双倍/四倍分跳同帧第二跳): 不刷层, 不续窗 (不破 ≤15% 聚合封顶)。
        }
        PlayerDotSources dotSources = ChampionEffectRegistries.dotSourcesFor(victim.getUUID());
        if (hasBurning) {
            dotSources.refresh(attackerId, AffixDef.BURNING, equipped.get(AffixDef.BURNING), nowTick);
        }
        if (hasFrost) {
            dotSources.refresh(attackerId, AffixDef.FROST, equipped.get(AffixDef.FROST), nowTick);
        }
        pair.gate.markDotRefreshed(nowTick);
    }

    /**
     * 撕裂: 续期撕裂层 (3s 窗内叠层, 窗外重置), 折易伤 amplifier 复用全局易伤效果。amplifier &lt;0 (撕裂 %不足
     * 易伤 I 档) 时不挂。乘伤由 VulnerabilityHurtHandler 单点结算; 多源 (撕裂+塔罗) 取最高 amplifier 由原版保证。
     */
    private void applyRend(Map<AffixDef, AffixQuality> equipped, PairState pair, long nowTick, Player victim) {
        AffixQuality q = equipped.get(AffixDef.REND);
        if (q == null) {
            return;
        }
        RendStacks rs = pair.rend;
        rs.bump(nowTick);

        int amp = ChampionAttackValues.rendAmplifier(q, rs.layers);
        if (amp < 0) {
            return; // 撕裂 %不足最低易伤档: 不挂效果。
        }
        // 复用全局易伤 (9A.2 铁律): 挂同一 VULNERABILITY 效果, 原版取最高 amplifier, 乘伤单点结算。
        victim.addEffect(new MobEffectInstance(
                ModJobEffects.VULNERABILITY.get(), RIDER_DURATION_TICKS, amp, false, true));
    }

    /** 强酸: 损玩家全护甲槽耐久 valueFor 点 (纯经济磨损, 不入伤害口径)。 */
    private void applyCorrosive(Map<AffixDef, AffixQuality> equipped, Player victim) {
        AffixQuality q = equipped.get(AffixDef.CORROSIVE);
        if (q == null) {
            return;
        }
        int durabilityLoss = ChampionAttackValues.corrosiveArmorDamage(q);
        if (durabilityLoss <= 0) {
            return;
        }
        for (ItemStack armor : victim.getArmorSlots()) {
            if (armor.isEmpty() || !armor.isDamageableItem()) {
                continue;
            }
            int newDamage = Math.min(armor.getDamageValue() + durabilityLoss, armor.getMaxDamage());
            armor.setDamageValue(newDamage);
        }
    }

    /**
     * 混沌重击击飞: 受 {@link ChampionStrikeGate} 击飞内 CD ≥2s + 落地恢复窗 ≥1s 限频 (逐跳/连续击飞被挡)。落点
     * 安全守卫 (KnockbackSafetyGuard) 尚未落地 (见类注释 sharedFileEditsNeeded), 当前仅做限频闸 + 内 CD 落账,
     * 不直接 setDeltaMovement/push (避免无守卫把玩家击飞进岩浆/虚空)。守卫落地后在此把安全击飞向量接上。
     */
    private void applyChaosKnockback(Map<AffixDef, AffixQuality> equipped, PairState pair, long nowTick,
                                     LivingEntity attacker, Player victim) {
        if (!equipped.containsKey(AffixDef.CHAOS_STRIKE)) {
            return;
        }
        if (!pair.gate.canKnockback(nowTick)) {
            return; // 内 CD / 落地恢复窗内: 本次不击飞。
        }
        // 落地预计 tick: 击飞后玩家滞空粗估 1s (20tick) 落地 (守卫落地后按真实向量末端估算替换)。
        long estimatedLanding = nowTick + ChampionStrikeGate.CHAOS_LANDING_RECOVERY_TICKS;
        pair.gate.markKnockback(nowTick, estimatedLanding);
        // KnockbackSafetyGuard 未落地: 不直接 push (sharedFileEditsNeeded)。闸已落账保限频不破红线 5。
    }

    /**
     * 冠军当前血量占比 (嗜血低血判定; spec 6.2 #1 血池权威): 6★+ 读影子血池 fraction, 1-5★ 读 vanilla
     * getHealth/getMaxHealth 之比。血池缺失 (异常) 回退 vanilla 比, 不抛 (受击点不应因诊断数据缺失中断)。
     */
    private static double championHpFraction(LivingEntity champion) {
        BloodPool pool = BloodPoolRegistry.get(champion.getUUID());
        if (pool != null) {
            return pool.fraction();
        }
        float max = champion.getMaxHealth();
        if (max <= 0.0F) {
            return 1.0D;
        }
        return champion.getHealth() / max;
    }

    /** per-(玩家→冠军) 攻击交互状态聚合: on-hit 内 CD/击飞闸 + 撕裂层。燃烧/寒霜层数+刷新窗已上提到 per-player
     * {@link PlayerDotSources} (tick handler 每秒读), 不再藏于本 per-pair 状态。 */
    private static final class PairState {
        private final ChampionStrikeGate gate = new ChampionStrikeGate();
        private final RendStacks rend = new RendStacks();
    }

    /** 撕裂层数 + 刷新窗: 3s 窗内续期叠层 (上限 = 易伤封顶 +100%, 由 amplifier 映射自然钳), 窗外重置。 */
    private static final class RendStacks {
        private int layers = 0;
        private long lastTick = Long.MIN_VALUE;

        void bump(long nowTick) {
            if (lastTick != Long.MIN_VALUE && nowTick - lastTick > RIDER_DURATION_TICKS) {
                layers = 0; // 刷新窗外: 撕裂层衰减清零, 重新累叠。
            }
            layers++;
            lastTick = nowTick;
        }
    }

    /**
     * 反泄漏: 玩家死亡/登出/换维度时清该玩家全部 per-pair 攻击交互状态 (撕裂层/DoT 层/on-hit 闸)。状态外层键 =
     * 受击玩家 UUID, 故 O(1) 整桶移除。与 {@link ChampionEffectRegistries.CleanupHandler} 清聚合器 (DoT/控制/反伤)
     * 同生命周期, 但本 handler 的 per-pair 状态独立持有, 须本 handler 自行清 (二者键域不同: 此处按受击玩家整桶清)。
     */
    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Player player) {
            stateByVictim.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        stateByVictim.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        stateByVictim.remove(event.getEntity().getUUID());
    }
}
