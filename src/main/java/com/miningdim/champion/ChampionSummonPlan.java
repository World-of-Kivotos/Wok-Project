package com.miningdim.champion;

import java.util.ArrayList;
import java.util.List;

/**
 * 支援召唤 SUMMON_SUPPORT 技能纯逻辑 (ChampionStarAffix spec 7.4 支援 + 红线 8 三重封顶)。把
 * {@link AffixDef#SUMMON_SUPPORT} 的档位数值折算成: 召唤冷却 (新表) / 每次召唤数 / 同时存活上限 / 实际可召数
 * (按存活扣减) / 召唤星级 (主人星降档钳制) / 召唤物词条过滤 (剥离技能 + 支援本身防递归嵌套)。
 *
 * 三重封顶 (spec 红线 8): (1) 每次召唤数 valueFor 1/2/2/3/3; (2) 同时存活上限 secondaryValueFor 2/3/4/5/6;
 * (3) 召唤冷却 30/26/22/18/14s。实际召唤数 = min(每次数, 上限-当前存活), 既受"每次数"节流又受"存活上限"封顶,
 * 存活满则本次为 0 (不刷屏怪海)。经济闸 (召唤物排除货币/经验/掉落/贡献/BOSS 条) 在 integration 层
 * {@code ChampionSummonHandler} 施加, 本类只算数值与门槛。
 *
 * 纯函数集合, 不碰世界/实体/Champions (只用 {@link AffixDef}/{@link AffixQuality}/{@link StarRank}/
 * {@link AffixSelection} 纯枚举与值对象), GameTest 直接断言 (删被测折算/钳制/过滤必挂)。异常必痛: 参数非法
 * 抛 {@link IllegalArgumentException}, 不静默兜底。
 */
public final class ChampionSummonPlan {

    private ChampionSummonPlan() {
    }

    /**
     * 召唤冷却表 (tick, 按品质 {@link AffixQuality#valueIndex()} 索引): 30/26/22/18/14s = 600/520/440/360/280 tick
     * (spec 7.4; 品质越高召唤越频)。这是支援召唤独有的第三重封顶表, 未进 {@link AffixDef} 主/副数值 (那两组已被
     * "每次召唤数"与"同时存活上限"占用), 故在本纯逻辑类落新表。
     */
    private static final long[] COOLDOWN_TICKS = {600L, 520L, 440L, 360L, 280L};

    /** 召唤星级下限 (clamp 下界; 主人星-2 低于此按此 —— 助战至少 1★, 不召出非冠军)。 */
    public static final int MIN_SUMMON_STAR = 1;

    /** 召唤星级上限 (clamp 上界; 助战不越 4★, 防高星主人召出成群高星链堆料破平衡)。 */
    public static final int MAX_SUMMON_STAR = 4;

    /** 召唤星级相对主人的降档 (spec 7.4: 召低星同型助战 -> 主人星 - 2)。 */
    public static final int SUMMON_STAR_OFFSET = 2;

    /**
     * 某品质的召唤冷却 (tick)。
     *
     * @param quality 支援召唤品质
     * @return 冷却 tick (∈ {600,520,440,360,280})
     */
    public static long cooldownTicks(AffixQuality quality) {
        requireQuality(quality);
        return COOLDOWN_TICKS[quality.valueIndex()];
    }

    /**
     * 召唤冷却是否已过 (距上次召唤 ≥ 该品质冷却)。lastCastTick = {@link Long#MIN_VALUE} (从未召唤) 视为就绪。
     *
     * @param nowTick      当前 gameTime tick
     * @param lastCastTick 上次成功召唤 tick (Long.MIN_VALUE = 从未召唤)
     * @param quality      支援召唤品质
     * @return 冷却是否就绪
     */
    public static boolean cooldownElapsed(long nowTick, long lastCastTick, AffixQuality quality) {
        long cd = cooldownTicks(quality);
        if (lastCastTick == Long.MIN_VALUE) {
            return true;
        }
        return nowTick - lastCastTick >= cd;
    }

    /**
     * 每次召唤数 (spec 7.4 主数值 1/2/2/3/3): 冷却就绪时本次尝试召出的数量 (再受存活上限夹断)。
     *
     * @param quality 支援召唤品质
     * @return 每次召唤数 (&gt;=1)
     */
    public static int summonsPerCast(AffixQuality quality) {
        requireQuality(quality);
        return (int) AffixDef.SUMMON_SUPPORT.valueFor(quality);
    }

    /**
     * 同时存活上限 (spec 7.4 副数值 2/3/4/5/6): 该主人名下召唤物同时存活的硬上限。
     *
     * @param quality 支援召唤品质
     * @return 同时存活上限 (&gt;=2)
     */
    public static int simultaneousCap(AffixQuality quality) {
        requireQuality(quality);
        return (int) AffixDef.SUMMON_SUPPORT.secondaryValueFor(quality);
    }

    /**
     * 本次实际召唤数 = min(每次召唤数, 同时存活上限 - 当前存活) (spec 红线 8 三重封顶交汇)。存活已满 (剩余空位
     * ≤0) 返 0 —— 本次不召, 待召唤物阵亡腾位再补。
     *
     * @param quality      支援召唤品质
     * @param currentAlive 当前该主人名下存活召唤物数 (须 &gt;=0)
     * @return 实际可召数 (∈ [0, 每次召唤数])
     */
    public static int actualSummonCount(AffixQuality quality, int currentAlive) {
        requireQuality(quality);
        if (currentAlive < 0) {
            throw new IllegalArgumentException("currentAlive must be >= 0, got " + currentAlive);
        }
        int room = simultaneousCap(quality) - currentAlive;
        if (room <= 0) {
            return 0;
        }
        return Math.min(summonsPerCast(quality), room);
    }

    /**
     * 召唤星级 = clamp(主人星 - 2, 1, 4) (spec 7.4: 召低星同型助战)。主人星 3->1 / 6->4 / 10->4 (钳到 4★ 顶)。
     *
     * @param ownerStar 主人 (冠军) 星级 (须 ∈ [1,10])
     * @return 召唤物星级 (∈ [1,4])
     */
    public static int summonStar(int ownerStar) {
        if (ownerStar < StarRank.MIN_STAR || ownerStar > StarRank.MAX_STAR) {
            throw new IllegalArgumentException("ownerStar out of [1,10]: " + ownerStar);
        }
        int raw = ownerStar - SUMMON_STAR_OFFSET;
        if (raw < MIN_SUMMON_STAR) {
            return MIN_SUMMON_STAR;
        }
        if (raw > MAX_SUMMON_STAR) {
            return MAX_SUMMON_STAR;
        }
        return raw;
    }

    /**
     * 某词条是否可赋予召唤物: 排除全部技能词条 (isSkill, 占技能上限的主动技能) 与支援召唤本身。业务理由: 召唤物
     * 若带技能 = 嵌套读条核弹刷屏; 若带支援召唤 = 召唤物再召唤递归增殖 (指数刷怪)。SUMMON_SUPPORT 本就是技能
     * (isSkill=true), 显式再排一次是防御性冗余 —— 即便将来它被移出技能池, 递归闸依然成立。
     *
     * @param def 词条
     * @return 是否可赋予召唤物
     */
    public static boolean isSummonable(AffixDef def) {
        if (def == null) {
            throw new IllegalArgumentException("def must not be null");
        }
        return !def.isSkill() && def != AffixDef.SUMMON_SUPPORT;
    }

    /**
     * 过滤召唤物词条选择: 保留可赋予召唤物的词条 (见 {@link #isSummonable}), 丢弃技能/支援召唤【不补偿】(直接扣掉,
     * 不重 roll 顶替) —— 防递归召唤/技能嵌套优先于"召唤物词条数达标"。保序返回新表, 不改入参。
     *
     * @param rolled 原始 roll 结果 (须非 null)
     * @return 过滤后的词条选择 (可能为空)
     */
    public static List<AffixSelection> retainSummonableAffixes(List<AffixSelection> rolled) {
        if (rolled == null) {
            throw new IllegalArgumentException("rolled must not be null");
        }
        List<AffixSelection> kept = new ArrayList<>(rolled.size());
        for (AffixSelection sel : rolled) {
            if (isSummonable(sel.affix())) {
                kept.add(sel);
            }
        }
        return kept;
    }

    private static void requireQuality(AffixQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
    }
}
