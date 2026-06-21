package com.miningdim.champion.aggregate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单玩家 DoT 秒窗累加缓冲 (ChampionStarAffix spec 红线 4 / 9.5 持续伤害聚合)。{@link DotAggregator} 是无状态纯
 * 函数 (一次性接收"本秒各源名义伤害"varargs 返回衰减结果); 而效果应用层是逐 tick 把多个 DoT 源 (燃烧/寒霜冻伤/
 * 强酸若按 dot/穿甲若按 dot) 的本秒名义伤害陆续记入, 再每秒 (20tick) 统一 flush 一次。本类即承载这段"逐源累加 ->
 * 每秒 flush 经 DotAggregator 衰减"的 per-player 有状态缓冲, 供 {@link com.miningdim.champion.ChampionEffectRegistries}
 * 按 UUID 管理。
 *
 * 设计 (与 DotAggregator 分工): 本类只缓冲"本秒名义伤害"(按源标识聚合, 同源刷新累加), flush 时把缓冲交给
 * {@link DotAggregator#aggregate} 做红线 4 的 15% maxHP 合计封顶 + 按贡献比例衰减, 返回逐源实际伤害, 然后清空
 * 缓冲进入下一秒窗。封顶/衰减数学全在 DotAggregator (单一权威, 不在本类复制), 本类只管缓冲与秒窗节奏。
 *
 * 线程纪律: 服务端主线程 tick 串行写, 非线程安全 (与其它聚合器一致)。不碰世界/实体 (只持 source 标识 + double),
 * GameTest 直接断言。
 */
public final class PlayerDotAccumulator {

    private static final long TICKS_PER_SECOND = 20L;

    /** 本秒按源累加的名义伤害 (源标识 -> 累计名义 HP); LinkedHashMap 保插入序 = flush 逐源保序。 */
    private final Map<Object, Double> pendingBySource = new LinkedHashMap<>();

    /** 当前秒窗起点 tick (Long.MIN_VALUE = 未开窗)。 */
    private long currentSecondStartTick = Long.MIN_VALUE;

    /**
     * 记一笔本秒 DoT 名义伤害 (同源刷新累加, 跨秒先 flush 由调用方按 {@link #shouldFlush} 驱动)。
     *
     * @param source       DoT 源标识 (如 affix 实例 / 词条枚举 / 源 UUID; 仅作 map 键去重/保序, 不解释)
     * @param nominalDamage 本次名义伤害 (HP, 已把 %maxHP·maxHp 与 FLAT 折算成统一 HP; 必须 &gt;=0)
     */
    public void record(Object source, double nominalDamage) {
        if (source == null) {
            throw new IllegalArgumentException("dot source must not be null");
        }
        if (nominalDamage < 0.0D || Double.isNaN(nominalDamage)) {
            throw new IllegalArgumentException("dot nominal damage must be >= 0, got " + nominalDamage);
        }
        pendingBySource.merge(source, nominalDamage, Double::sum);
    }

    /**
     * 是否已跨入新秒窗 (距上次 flush ≥20tick), 该 flush。首次 (未开窗) 返 false (开窗后才计秒)。
     *
     * @param nowTick 当前 gameTime tick
     * @return 是否到 flush 边界
     */
    public boolean shouldFlush(long nowTick) {
        if (currentSecondStartTick == Long.MIN_VALUE) {
            currentSecondStartTick = nowTick;
            return false;
        }
        return nowTick - currentSecondStartTick >= TICKS_PER_SECOND;
    }

    /**
     * flush 本秒缓冲: 把累加的逐源名义伤害交给 {@link DotAggregator#aggregate} 做 15% maxHP 合计封顶 + 按贡献比例
     * 衰减, 返回逐源实际伤害 (与记入顺序同序), 然后清空缓冲并把秒窗推进到 nowTick (下一秒重新累加)。
     * 缓冲为空时返回空结果 (合计 0)。
     *
     * @param maxHp   玩家有效最大血量 (&gt;0; 15% 封顶基数)
     * @param nowTick 当前 gameTime tick (推进秒窗)
     * @return 逐源衰减后实际伤害 (按 source 插入序) + 合计
     */
    public FlushResult flush(double maxHp, long nowTick) {
        currentSecondStartTick = nowTick;
        if (pendingBySource.isEmpty()) {
            return new FlushResult(new ArrayList<>(), new double[0], 0.0D);
        }

        List<Object> sources = new ArrayList<>(pendingBySource.keySet());
        double[] nominal = new double[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            nominal[i] = pendingBySource.get(sources.get(i));
        }
        pendingBySource.clear();

        DotAggregator.Result r = DotAggregator.aggregate(maxHp, nominal);
        return new FlushResult(sources, r.perSource(), r.total());
    }

    /** 当前秒窗内已记入的源数 (诊断/测试用)。 */
    public int pendingSourceCount() {
        return pendingBySource.size();
    }

    /** flush 结果: 逐源 (与记入序对齐) 衰减后实际伤害 + 合计 (≤15% maxHP)。不可变。 */
    public static final class FlushResult {

        private final List<Object> sources;
        private final double[] perSource;
        private final double total;

        FlushResult(List<Object> sources, double[] perSource, double total) {
            this.sources = sources;
            this.perSource = perSource;
            this.total = total;
        }

        /** 逐源标识 (与 {@link #perSource()} 同序)。 */
        public List<Object> sources() {
            return List.copyOf(sources);
        }

        /** 逐源衰减后实际伤害 (与 {@link #sources()} 同序)。 */
        public double[] perSource() {
            return perSource.clone();
        }

        /** 本秒合计 DoT 实际伤害 (≤15% maxHP)。 */
        public double total() {
            return total;
        }
    }
}
