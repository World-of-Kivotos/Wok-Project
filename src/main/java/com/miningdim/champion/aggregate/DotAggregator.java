package com.miningdim.champion.aggregate;

import com.miningdim.champion.ChampionRedlines;

import java.util.ArrayList;
import java.util.List;

/**
 * 持续伤害聚合封顶纯逻辑 (ChampionStarAffix spec 红线 4 / 9.5 / 第十四章实现拆分 3-4)。
 *
 * 同一玩家身上所有 DoT (燃烧 + 寒霜冻伤 + 强酸若按 dot + 穿甲若按 dot) 每秒合计硬封顶 ≤15% maxHP/s, 多 DoT
 * 共存按贡献比例衰减入上限 (spec 红线 4)。本类是纯函数: 输入"本秒各 DoT 源的名义伤害 (%maxHP 或 FLAT 折算
 * 成 HP)", 输出"按贡献比例衰减夹到 15% maxHP 上限后的逐源实际伤害 + 合计"。不碰世界/实体, GameTest 直接断言。
 *
 * 删 clamp → 单玩家多 DoT 合计可破 15% maxHP, test 必挂。
 */
public final class DotAggregator {

    private DotAggregator() {
    }

    /**
     * 把本秒同一玩家身上的多个 DoT 名义伤害按红线 4 夹到 15% maxHP 合计上限 (超额按贡献比例衰减)。
     *
     * @param maxHp           玩家有效最大血量 (必须 &gt;0; %maxHP 上限的基数)
     * @param nominalDamages  本秒各 DoT 源的名义伤害 (HP, 已把 %maxHP·maxHp 与 FLAT 折算成统一 HP; 每项 &gt;=0)
     * @return 聚合结果 (逐源实际伤害 + 合计; 合计 ≤ 15% maxHp)
     */
    public static Result aggregate(double maxHp, double... nominalDamages) {
        if (!(maxHp > 0.0D) || Double.isNaN(maxHp)) {
            throw new IllegalArgumentException("maxHp must be > 0, got " + maxHp);
        }
        double cap = maxHp * ChampionRedlines.DOT_PER_SECOND_CAP_PCT;

        double nominalSum = 0.0D;
        for (double d : nominalDamages) {
            if (d < 0.0D || Double.isNaN(d)) {
                throw new IllegalArgumentException("dot nominal damage must be >= 0, got " + d);
            }
            nominalSum += d;
        }

        List<Double> scaled = new ArrayList<>(nominalDamages.length);
        double total;
        if (nominalSum <= cap || nominalSum == 0.0D) {
            // 未超顶: 原样下发。
            for (double d : nominalDamages) {
                scaled.add(d);
            }
            total = nominalSum;
        } else {
            // 超顶: 按贡献比例衰减, 逐源 × (cap / nominalSum), 合计精确等于 cap。
            double factor = cap / nominalSum;
            double acc = 0.0D;
            for (int i = 0; i < nominalDamages.length; i++) {
                double v;
                if (i == nominalDamages.length - 1) {
                    v = cap - acc; // 末源吸收浮点余数, 保合计 = cap。
                } else {
                    v = nominalDamages[i] * factor;
                    acc += v;
                }
                scaled.add(v);
            }
            total = cap;
        }

        double[] out = new double[scaled.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = scaled.get(i);
        }
        return new Result(out, total, cap);
    }

    /** DoT 聚合结果 (不可变): 逐源衰减后伤害 + 合计 + 本玩家上限。 */
    public static final class Result {

        private final double[] perSource;
        private final double total;
        private final double cap;

        Result(double[] perSource, double total, double cap) {
            this.perSource = perSource;
            this.total = total;
            this.cap = cap;
        }

        /** 逐源衰减后实际伤害 (与输入同序)。 */
        public double[] perSource() {
            return perSource.clone();
        }

        /** 本秒合计 DoT 伤害 (≤ {@link #cap()})。 */
        public double total() {
            return total;
        }

        /** 本玩家 DoT 每秒上限 (= 15% maxHP)。 */
        public double cap() {
            return cap;
        }

        /** 本秒是否撞顶 (合计被夹到 cap)。 */
        public boolean wasCapped() {
            return total >= cap;
        }
    }
}
