package com.miningdim.champion;

/**
 * 词条品质 (ChampionStarAffix spec 第四章双层平衡模型 + 9A.7 品质显示)。
 *
 * 品质同时决定词条强度档位 (5 档数值索引) 与点数成本系数 (成本 = 基础成本 c × 品质系数)。高品质更强更贵。
 * 品质随星解锁 (spec 第四章): 星级钳制实际可取的最高品质档, 越档 roll 须降级或重 roll (落在 spawn 期分配器)。
 *
 * 配色 (spec 9A.7 MMO 稀有度梯度, 与塔罗 R/SR/SSR/UR/闪耀 视觉一致): 普通=灰白 / 中级=绿 / 高级=蓝 /
 * 超凡=紫 / 闪耀=金。配色仅供名牌/探测列表着色, 不影响数值。
 *
 * 纯数据枚举, 无世界引用。ordinal() 即 5 档数值表 (普通/中级/高级/超凡/闪耀) 的索引, 供
 * {@link AffixPool} 各词条数值数组按品质取档。
 */
public enum AffixQuality {

    /** 普通 (1.0×, 灰白): 1★ 起可取。 */
    COMMON(1.0D, 0xC8C8C8),

    /** 中级 (1.6×, 绿): 3★ 起可取。 */
    UNCOMMON(1.6D, 0x55C040),

    /** 高级 (2.5×, 蓝): 5★ 起可取。 */
    RARE(2.5D, 0x3070E0),

    /** 超凡 (4.0×, 紫): 7★ 起可取。 */
    EPIC(4.0D, 0x9B30E0),

    /** 闪耀 (6.5×, 金, signature): 9★ 起可取。 */
    LEGENDARY(6.5D, 0xE0B020);

    private final double costMultiplier;
    private final int displayColor;

    AffixQuality(double costMultiplier, int displayColor) {
        this.costMultiplier = costMultiplier;
        this.displayColor = displayColor;
    }

    /** 品质成本系数 (spec 第四章: 普通 ×1.0 / 中级 ×1.6 / 高级 ×2.5 / 超凡 ×4.0 / 闪耀 ×6.5)。 */
    public double costMultiplier() {
        return costMultiplier;
    }

    /** 名牌/探测列表着色 RGB (spec 9A.7; 不影响数值)。 */
    public int displayColor() {
        return displayColor;
    }

    /** 5 档数值表索引 (= ordinal): 普通=0 / 中级=1 / 高级=2 / 超凡=3 / 闪耀=4。 */
    public int valueIndex() {
        return ordinal();
    }

    /**
     * 把"星级允许的最高品质 ordinal"夹断到本品质: 若本品质超过 cap 则降级到 cap, 否则原样返回 (spec 第四章
     * 品质随星解锁: 越档取值由机制层挡住, roll 到超档品质须降级)。
     *
     * @param maxQuality 该星级允许的最高品质 (来自 {@link StarRank#maxQuality()})
     * @return 夹断后的实际品质 (≤ maxQuality)
     */
    public AffixQuality clampTo(AffixQuality maxQuality) {
        if (this.ordinal() > maxQuality.ordinal()) {
            return maxQuality;
        }
        return this;
    }
}
