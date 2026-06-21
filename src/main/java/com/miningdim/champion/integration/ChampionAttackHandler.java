package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionAffixState;
import com.miningdim.champion.ChampionAttackValues;
import com.miningdim.champion.ChampionEffectRegistries;
import com.miningdim.champion.ChampionStrikeGate;
import com.miningdim.champion.StarRank;
import com.miningdim.champion.aggregate.PlayerControlAggregator;
import com.miningdim.champion.aggregate.PlayerDotAccumulator;
import com.miningdim.champion.bloodpool.BloodPool;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.champion.integration.affix.MiningAffix;
import com.miningdim.effect.ModJobEffects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import top.theillusivec4.champions.api.IAffix;
import top.theillusivec4.champions.api.IChampion;
import top.theillusivec4.champions.common.capability.ChampionCapability;
import top.theillusivec4.champions.common.rank.Rank;

import java.util.EnumMap;
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
 *  - DoT (燃烧 / 寒霜冻伤): on-hit 刷层 (受 {@link ChampionStrikeGate} DoT 刷新内 CD ≥1s), 本秒名义伤害记入
 *    per-player {@link PlayerDotAccumulator}, 每秒由 {@link ChampionDotTickHandler} 经 DotAggregator 夹 ≤15% maxHP
 *    统一施加。本 handler 只刷层 + 记本秒名义量, 不在受击点直接扣 DoT 伤害 (DoT 单一权威在 tick handler)。
 *  - 减速 (寒霜): 进 per-player 控制聚合 (≤50% 总减速)。
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
 * 单点铁律守卫: 本 handler 是冠军→玩家攻击的唯一施加点, 词条本体 {@link MiningAffix} 不重写任何战斗钩子 (见其
 * 类注释)。compileOnly 隔离: 本类 import top.theillusivec4.champions.* —— 属 integration 隔离包, 仅
 * {@code ModList.isLoaded("champions")} 守卫下由 {@code ChampionIntegrationBootstrap} 挂 forgeBus, dev GameTest 不
 * 触达 (纯数值/红线在 champion 包纯逻辑类 GameTest 验)。
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
        if (!(event.getSource().getEntity() instanceof LivingEntity attacker)) {
            return; // 无直接生物攻击者 (环境/无主弹射物): 不施攻击词条。
        }
        IChampion champion = ChampionCapability.getCapability(attacker).resolve().orElse(null);
        if (champion == null || champion.getServer() == null) {
            return; // 攻击者非冠军。
        }
        IChampion.Server server = champion.getServer();
        Rank rank = server.getRank().orElse(null);
        if (rank == null || rank.getTier() <= 0) {
            return; // 无有效 rank。
        }
        // 星级 + 品质: 优先本工程盖章 NBT; 命令召唤冠军 (/champions summon 直接 setAffixes 未经我方 promoter, 无 NBT)
        // 用 rank tier 兜底星级 + 空品质表 (qualityOf 据星走默认品质), 使命令召冠军的攻击词条同样可测可用 (与减伤/地基兜底一致)。
        CompoundTag data = server.getData(ChampionPromoter.DATA_KEY);
        int star = (data != null && data.contains(ChampionPromoter.NBT_STAR))
                ? data.getInt(ChampionPromoter.NBT_STAR) : rank.getTier();
        if (star < StarRank.MIN_STAR || star > StarRank.MAX_STAR) {
            return; // 星级越界 (脏 NBT / 异常 tier): 不施, 不让脏星越红线。
        }
        StarRank starRank = StarRank.ofStar(star);
        CompoundTag affixQuality = (data != null && data.contains(ChampionAffixState.NBT_AFFIX_QUALITY))
                ? data.getCompound(ChampionAffixState.NBT_AFFIX_QUALITY) : new CompoundTag();

        // 该冠军装配的本工程词条 (def -> quality)。
        Map<AffixDef, AffixQuality> equipped = collectEquipped(server, affixQuality, starRank);
        if (equipped.isEmpty()) {
            return; // 无本工程攻击词条。
        }

        long nowTick = victim.level().getGameTime();
        double playerMaxHp = victim.getMaxHealth();
        PairState pair = stateByVictim
                .computeIfAbsent(victim.getUUID(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(attacker.getUUID(), k -> new PairState());

        applyInstantDamage(event, equipped, starRank, attacker, victim, playerMaxHp);
        applyDotsAndSlow(equipped, pair, attacker.getUUID(), nowTick, victim, playerMaxHp);
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
     * DoT (燃烧/寒霜冻伤) on-hit 刷层 + 记本秒名义伤害进 per-player 累加器 (≤15% 统一封顶由 tick handler 夹);
     * 寒霜减速进 per-player 控制聚合 (≤50%)。DoT 刷层受同一 {@link ChampionStrikeGate} 内 CD ≥1s 约束 (双倍/四倍
     * 分跳同帧不重复刷层)。
     */
    private void applyDotsAndSlow(Map<AffixDef, AffixQuality> equipped, PairState pair, UUID attackerId, long nowTick,
                                  Player victim, double playerMaxHp) {
        boolean hasBurning = equipped.containsKey(AffixDef.BURNING);
        boolean hasFrost = equipped.containsKey(AffixDef.FROST);
        if (!hasBurning && !hasFrost) {
            return;
        }

        boolean canRefresh = pair.gate.canRefreshDot(nowTick);
        PlayerDotAccumulator dotAcc = ChampionEffectRegistries.dotFor(victim.getUUID());
        boolean refreshedAny = false;

        if (hasBurning) {
            AffixQuality q = equipped.get(AffixDef.BURNING);
            DotStacks ds = pair.dotStacks.computeIfAbsent(AffixDef.BURNING, k -> new DotStacks());
            if (canRefresh) {
                ds.bump(nowTick);
                refreshedAny = true;
            }
            ds.decayIfStale(nowTick);
            double hp = ChampionAttackValues.burningTickHp(q, ds.stacks, playerMaxHp);
            if (hp > 0.0D) {
                // 源标识 = (冠军→玩家, 燃烧): 同源同秒刷新累加, 跨秒由 tick handler flush。
                dotAcc.record(dotSource(attackerId, AffixDef.BURNING), hp);
            }
        }

        if (hasFrost) {
            AffixQuality q = equipped.get(AffixDef.FROST);
            DotStacks ds = pair.dotStacks.computeIfAbsent(AffixDef.FROST, k -> new DotStacks());
            if (canRefresh) {
                ds.bump(nowTick);
                refreshedAny = true;
            }
            ds.decayIfStale(nowTick);
            double hp = ChampionAttackValues.frostFreezeTickHp(q, ds.stacks, playerMaxHp);
            if (hp > 0.0D) {
                dotAcc.record(dotSource(attackerId, AffixDef.FROST), hp);
            }
            applyFrostSlow(q, ds.stacks, nowTick, victim);
        }

        if (refreshedAny) {
            pair.gate.markDotRefreshed(nowTick);
        }
    }

    /**
     * 寒霜减速: 按层折减速量 (自夹 ≤50%), 申请进 per-player 控制聚合 (7s 窗 ≤50% 受控 + ≥2s 自由窗), 实际施加
     * 原版 MOVEMENT_SLOWDOWN 一段时长。控制聚合 admit 返回被夹后可施加 tick (超额作废返 0 = 不施)。
     */
    private void applyFrostSlow(AffixQuality frostQuality, int stacks, long nowTick, Player victim) {
        double slowPct = ChampionAttackValues.frostSlowPct(frostQuality, stacks);
        if (slowPct <= 0.0D) {
            return;
        }
        PlayerControlAggregator control = ChampionEffectRegistries.controlFor(victim.getUUID());
        long granted = control.admit(nowTick, RIDER_DURATION_TICKS);
        if (granted <= 0L) {
            return; // 控制额度耗尽 (7s 窗 ≤50%): 本次减速作废, 保留自由窗。
        }
        // 减速 amplifier: 原版 MOVEMENT_SLOWDOWN 每级 -15%, 按夹后减速量折最近不超档的级数 (floor)。
        int amp = (int) Math.floor(slowPct / 0.15D) - 1;
        if (amp < 0) {
            amp = 0;
        }
        victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, (int) granted, amp, false, true));
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

    /** 收集该冠军装配的本工程攻击/相关词条 (def -> 该词条品质); 非 MiningAffix 实例跳过。 */
    private Map<AffixDef, AffixQuality> collectEquipped(IChampion.Server server, CompoundTag affixQuality,
                                                        StarRank starRank) {
        Map<AffixDef, AffixQuality> equipped = new EnumMap<>(AffixDef.class);
        for (IAffix affix : server.getAffixes()) {
            if (!(affix instanceof MiningAffix mining)) {
                continue; // 非本工程词条 (Champions 内置或第三方): 不归本系统。
            }
            AffixDef def = mining.def();
            AffixQuality quality = ChampionAffixState.qualityOf(affixQuality, def, starRank);
            equipped.put(def, quality);
        }
        return equipped;
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

    /** DoT 源标识 (per-player 累加器去重键): (冠军 UUID, 词条) 复合串, 同源同秒累加 (区分多冠军同时上 DoT)。 */
    private static String dotSource(UUID attackerId, AffixDef def) {
        return attackerId + "/" + def.name();
    }

    /** per-(玩家→冠军) 攻击交互状态聚合: on-hit 内 CD/击飞闸 + 撕裂层 + 燃烧/寒霜层。 */
    private static final class PairState {
        private final ChampionStrikeGate gate = new ChampionStrikeGate();
        private final RendStacks rend = new RendStacks();
        private final EnumMap<AffixDef, DotStacks> dotStacks = new EnumMap<>(AffixDef.class);
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

    /** 燃烧/寒霜层数 + 刷新窗: 3s 刷新窗内最大 5 层, 窗外衰减清零 (spec 7.2: 最大 5 层 3s 刷新)。 */
    private static final class DotStacks {
        private int stacks = 0;
        private long lastTick = Long.MIN_VALUE;

        void bump(long nowTick) {
            decayIfStale(nowTick);
            if (stacks < ChampionAttackValues.DOT_MAX_STACKS) {
                stacks++;
            }
            lastTick = nowTick;
        }

        void decayIfStale(long nowTick) {
            if (lastTick != Long.MIN_VALUE && nowTick - lastTick > RIDER_DURATION_TICKS) {
                stacks = 0; // 刷新窗外: 层数清零。
            }
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
