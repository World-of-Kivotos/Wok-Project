package com.miningdim.job.agent.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionAttackValues;
import com.miningdim.champion.ChampionBladeWaltzPlan;
import com.miningdim.champion.ChampionCaesarSwapPlan;
import com.miningdim.champion.ChampionCounterUnitWindow;
import com.miningdim.champion.ChampionDamageReduction;
import com.miningdim.champion.ChampionDeathMarkMath;
import com.miningdim.champion.ChampionEffectRegistries;
import com.miningdim.champion.ChampionElectroChargePlan;
import com.miningdim.champion.ChampionLittleBoyPlan;
import com.miningdim.champion.ChampionRedlines;
import com.miningdim.champion.ChampionSelfRepairCycle;
import com.miningdim.champion.ChampionSummonPlan;
import com.miningdim.champion.ChampionThunderPlan;
import com.miningdim.champion.ChampionVisualDisruptionValues;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.StarRank;
import com.miningdim.champion.aggregate.PlayerDotSources;
import com.miningdim.champion.bloodpool.BloodPool;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.job.agent.panel.AgentScanIntel;
import com.miningdim.job.agent.panel.AgentScanSnapshotBuilder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 战术扫描数值情报的真值读取器 (SpecialAgent_Job_DesignSpec 第四章探测列 L3-L10 + 10.1"全是我们自己存在 capability
 * 里的数据")。只读、不改任何精英状态, 产出 champions-free 的 {@link AgentScanSnapshotBuilder.RawStats} /
 * {@link AgentScanSnapshotBuilder.RawLive}; 哪一格发给哪一级干员由构建器裁决, 本类不做任何等级判断。
 *
 * 口径纪律: 每个数都必须能在精英受击/攻击/技能的真实结算链上找到出处, 并与结算链<b>用同一个纯函数</b>折算
 * (减伤走 {@link ChampionDamageReduction} + {@link ChampionRedlines#clampNetKeepFactor}, 单击走
 * {@link ChampionAttackValues#singleHitTotalPct}, 技能时序直接取各技能 Plan 类的常量/查表)。找不到出处的概念
 * (如某技能没有打断阈值) 一律不发, 绝不为了表格整齐编一个数 —— 面板上一个编出来的数, 玩家会拿它去算打法。
 *
 * 数值源分两类, 刻意区别对待:
 *  - 词条贡献 (减伤 / 子弹抗性 / 单击) 只读<b>当前装配</b>的词条 ({@link MiningChampionData#affixes}): 被封印中的
 *    词条已从 capability 摘掉, 它的效果此刻真的不在, 把它算进去就是在报一个不存在的数;
 *  - 技能时序读<b>面板候选表</b> (装配 + 封印中): 封印只是临时窗口, 窗口一到技能就回来, 玩家要看的正是它回来后
 *    多久放一次, 且词条行本身也把封印中的那条列了出来 (sealed=true), 两边口径对齐。
 */
final class AgentScanStatsReader {

    /** 秒 -> tick (原版 20 tick/s); 技能 Plan 类的常量一律以 tick 记, 面板按秒显示。 */
    private static final double TICKS_PER_SECOND = 20.0D;

    private AgentScanStatsReader() {
    }

    /**
     * 读某精英此刻的全部数值原料。
     *
     * @param target     精英实体 (提供原版属性与血量)
     * @param champ      该实体的自研冠军数据 (调用方已判 isChampion)
     * @param candidates 面板候选词条 -> 品质 (装配 + 封印中; 只用于技能时序, 见类注释)
     */
    static AgentScanSnapshotBuilder.RawStats readStats(LivingEntity target, MiningChampionData champ,
                                                       Map<AffixDef, AffixQuality> candidates) {
        Map<AffixDef, AffixQuality> equipped = champ.affixes();
        List<AgentScanIntel.Mechanic> mechanics = new ArrayList<>();
        for (Map.Entry<AffixDef, AffixQuality> entry : candidates.entrySet()) {
            AgentScanIntel.Mechanic mechanic = mechanicOf(entry.getKey(), entry.getValue());
            if (mechanic != null) {
                mechanics.add(mechanic);
            }
        }
        return new AgentScanSnapshotBuilder.RawStats(
                champ.effectiveHp(),
                attributeValue(target, Attributes.ARMOR),
                generalReductionPct(equipped),
                bulletResistancePct(equipped),
                attributeValue(target, Attributes.ATTACK_DAMAGE),
                singleHitPct(target, StarRank.ofStar(champ.star()), equipped),
                attributeValue(target, Attributes.MOVEMENT_SPEED),
                mechanics);
    }

    /**
     * 重读一个仍在快照内的目标的实时原料 (L9/L10)。目标已死 / 已不是精英时返 null (构建器据此报 tracked=false)。
     * 实体是否已加载、是否仍是快照里那一只 (网络 id 可能被复用) 由调用方按 UUID 核对后才调本法。
     *
     * @param agent  扫描干员 (读"本精英叠在你身上的 DoT 层数")
     * @param target 目标实体
     */
    static AgentScanSnapshotBuilder.RawLive readLive(ServerPlayer agent, LivingEntity target) {
        if (!target.isAlive()) {
            return null;
        }
        MiningChampionData champ = MiningChampions.get(target).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return null;
        }
        // 血量权威与贡献/拦死同源: 6★+ 读影子血池, 原版血只是渲染镜像 (ChampionStarAffix 6.2 #2 严禁业务读它)。
        BloodPool pool = BloodPoolRegistry.get(target.getUUID());
        double currentHp = pool != null ? pool.currentHp() : target.getHealth();
        double maxHp = pool != null ? pool.maxHp() : target.getMaxHealth();

        int frost = 0;
        int burning = 0;
        // 先问 hasDot 再取源表: dotSourcesFor 是 computeIfAbsent, 对没中过 DoT 的干员直接调会凭空建一条空账,
        // 让 DoT tick handler 以为有人在挨烧而去空转。只读路径不许留下写痕。
        if (ChampionEffectRegistries.hasDot(agent.getUUID())) {
            PlayerDotSources dots = ChampionEffectRegistries.dotSourcesFor(agent.getUUID());
            frost = dots.stacksOf(target.getUUID(), AffixDef.FROST);
            burning = dots.stacksOf(target.getUUID(), AffixDef.BURNING);
        }
        // 技能时序是按品质查表的定值, 实时属性里不需要, 传空候选表省一轮查表。
        AgentScanSnapshotBuilder.RawStats attributes = readStats(target, champ, Map.of());
        return new AgentScanSnapshotBuilder.RawLive(currentHp, maxHp, target.getAbsorptionAmount(),
                frost, burning, attributes);
    }

    /**
     * 对全部伤害类型生效的比例减伤 (L4)。复合装甲按满层上限 ({@link ChampionDamageReduction#COMPOSITE_RAMP_STEPS}
     * 次同类受击后的值) 计 —— 满层之前的实时层数存在受击 handler 的私有计数器里, 且换伤害类别即清零, 扫描能诚实
     * 给出的只有它的上限; 缩小化体型折算恒定生效。两源与结算链一样经 75% 帽连乘。
     */
    private static double generalReductionPct(Map<AffixDef, AffixQuality> equipped) {
        List<Double> rates = new ArrayList<>(2);
        AffixQuality composite = equipped.get(AffixDef.COMPOSITE_ARMOR);
        if (composite != null) {
            rates.add(ChampionDamageReduction.compositeRampRate(composite, ChampionDamageReduction.COMPOSITE_RAMP_STEPS));
        }
        AffixQuality mini = equipped.get(AffixDef.MINIATURIZATION);
        if (mini != null) {
            rates.add(ChampionDamageReduction.miniaturizationReductionRate(mini));
        }
        double[] array = rates.stream().mapToDouble(Double::doubleValue).toArray();
        return 1.0D - ChampionRedlines.clampNetKeepFactor(array);
    }

    /**
     * 只对子弹生效的附加抗性 (L5)。超高分子 + 重型护甲子弹抗 + 偏斜期望闪避, 与受击单点同样按 1-∏(1-rᵢ) 合成;
     * 这里<b>不</b>施 75% 帽 —— 帽作用在"通用减伤 x 子弹抗性"的总乘积上, 单独给子弹这一项封帽会把两项都已接近
     * 上限的精英报得比真实更软。
     */
    private static double bulletResistancePct(Map<AffixDef, AffixQuality> equipped) {
        double keep = 1.0D;
        AffixQuality uhmwpe = equipped.get(AffixDef.UHMWPE_ARMOR);
        if (uhmwpe != null) {
            keep *= 1.0D - ChampionDamageReduction.uhmwpeBulletRate(uhmwpe);
        }
        AffixQuality heavy = equipped.get(AffixDef.HEAVY_ARMOR);
        if (heavy != null) {
            keep *= 1.0D - ChampionDamageReduction.heavyArmorBulletRate(heavy);
        }
        AffixQuality deflector = equipped.get(AffixDef.DEFLECTOR_SHIELD);
        if (deflector != null) {
            keep *= 1.0D - ChampionDamageReduction.deflectorBulletEvRate(deflector);
        }
        return 1.0D - keep;
    }

    /**
     * 近战单击被补足到的玩家最大血量比例 (L6)。逐字复刻 {@code ChampionAttackHandler.applyInstantDamage} 的口径:
     * 重炮 / 嗜血 (仅当前已低血激活) / 穿甲三者全无时那条管线直接早退、不做任何补足, 此处同样报 0; 有任一项时
     * 走同一个 {@link ChampionAttackValues#singleHitTotalPct} 红线钳制。嗜血按此刻血量占比判激活, 故这一格在
     * L10 实时属性里会随精英掉血跳变 —— 那正是它的真实行为。
     */
    private static double singleHitPct(LivingEntity target, StarRank rank, Map<AffixDef, AffixQuality> equipped) {
        AffixQuality cannon = equipped.get(AffixDef.HEAVY_CANNON);
        double heavyCannonAmp = cannon == null ? 0.0D : AffixDef.HEAVY_CANNON.valueFor(cannon);
        AffixQuality bloodlust = equipped.get(AffixDef.BLOODLUST);
        double bloodlustAmp = bloodlust == null ? 0.0D
                : ChampionAttackValues.bloodlustDamageAmp(bloodlust, hpFraction(target));
        AffixQuality piercing = equipped.get(AffixDef.ARMOR_PIERCING);
        double piercingPct = piercing == null ? 0.0D : AffixDef.ARMOR_PIERCING.valueFor(piercing);
        if (heavyCannonAmp == 0.0D && bloodlustAmp == 0.0D && piercingPct == 0.0D) {
            return 0.0D;
        }
        return ChampionAttackValues.singleHitTotalPct(rank, rank.baseSingleHitPct(), heavyCannonAmp, bloodlustAmp,
                piercingPct);
    }

    /** 精英当前血量占比 (嗜血激活判据; 与攻击 handler 同口径: 血池优先, 无池读原版比), 夹到 [0,1]。 */
    private static double hpFraction(LivingEntity target) {
        BloodPool pool = BloodPoolRegistry.get(target.getUUID());
        double fraction;
        if (pool != null) {
            fraction = pool.fraction();
        } else {
            float max = target.getMaxHealth();
            fraction = max <= 0.0F ? 1.0D : target.getHealth() / max;
        }
        return Math.max(0.0D, Math.min(1.0D, fraction));
    }

    /**
     * 原版属性当前值。实体没注册该属性时 (如部分飞行怪没有攻击伤害属性) 返 0: 没有这条属性就是它在原版结算里
     * 真实贡献 0, 不是加密。
     */
    private static double attributeValue(LivingEntity target, Attribute attribute) {
        AttributeInstance instance = target.getAttribute(attribute);
        return instance == null ? 0.0D : instance.getValue();
    }

    /**
     * 机制类 (技能池) 词条的技能时序 (L7)。每一格都直接取该技能 Plan 类里真实驱动 handler 的常量/查表, 没有对应
     * 概念的格留 null (JSON 里缺键):
     *  - 打断阈值只有小男孩有 (蓄力期累计伤害 >= 到场人数 x 120); 自我修复是"近战命中即打断", 没有伤害阈值;
     *    命定之死的"解除"阈值按被标记玩家近 10s 实测 DPS 动态算出, 没有一个可以提前告诉玩家的定值;
     *  - 冷却: 小男孩是一次性核弹 (起手即摘词条), 没有冷却;
     *  - 起手窗口: 视觉干扰 / 反击单元 / 支援召唤起手即生效, 没有蓄力或预兆。反击单元的 5 秒反击窗是"生效期"
     *    而非起手窗, 不塞进这一格。
     * 非技能池词条返 null (不进时序表)。
     */
    static AgentScanIntel.Mechanic mechanicOf(AffixDef def, AffixQuality quality) {
        String id = AgentAffixClassifier.affixId(def);
        return switch (def) {
            case ELECTRO_CHARGE -> new AgentScanIntel.Mechanic(id,
                    seconds(ChampionElectroChargePlan.CHARGE_TICKS), null,
                    seconds(ChampionElectroChargePlan.cycleTicks(quality)));
            case THUNDER -> new AgentScanIntel.Mechanic(id,
                    seconds(ChampionThunderPlan.WARNING_TICKS), null,
                    seconds(ChampionThunderPlan.cycleTicks(quality)));
            case LITTLE_BOY -> new AgentScanIntel.Mechanic(id,
                    seconds(ChampionLittleBoyPlan.CHARGE_TICKS), ChampionLittleBoyPlan.INTERRUPT_DAMAGE_PER_PLAYER,
                    null);
            case DEATH_MARK -> new AgentScanIntel.Mechanic(id,
                    seconds(ChampionDeathMarkMath.WINDOW_TICKS), null,
                    seconds(ChampionDeathMarkMath.COOLDOWN_TICKS));
            case VISUAL_DISRUPTION -> new AgentScanIntel.Mechanic(id, null, null,
                    seconds(ChampionVisualDisruptionValues.cyclePeriodTicks(quality)));
            case SELF_REPAIR -> new AgentScanIntel.Mechanic(id,
                    seconds(ChampionSelfRepairCycle.CHANNEL_TICKS), null,
                    seconds(ChampionSelfRepairCycle.COOLDOWN_TICKS));
            case COUNTER_UNIT -> new AgentScanIntel.Mechanic(id, null, null,
                    seconds(ChampionCounterUnitWindow.LOCK_CYCLE_TICKS));
            case CAESAR_SWAP -> new AgentScanIntel.Mechanic(id,
                    seconds(ChampionCaesarSwapPlan.TELEGRAPH_TICKS), null,
                    seconds(ChampionCaesarSwapPlan.cdTicks(quality)));
            case BLADE_WALTZ -> new AgentScanIntel.Mechanic(id,
                    seconds(ChampionBladeWaltzPlan.TELEGRAPH_TICKS), null,
                    seconds(ChampionBladeWaltzPlan.COOLDOWN_TICKS));
            case SUMMON_SUPPORT -> new AgentScanIntel.Mechanic(id, null, null,
                    seconds(ChampionSummonPlan.cooldownTicks(quality)));
            default -> null; // 非技能池词条: 没有技能时序可报。
        };
    }

    private static double seconds(long ticks) {
        return ticks / TICKS_PER_SECOND;
    }
}
