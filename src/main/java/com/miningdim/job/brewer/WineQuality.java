package com.miningdim.job.brewer;

import net.minecraft.ChatFormatting;

/**
 * 酒品质档 (酿酒师 第一节): 五档与其它职业一致, 品质决定"品质系数"。
 *
 * 品质系数用途: 喝酒增益强度 S = 年份 × 品质系数 ({@link WineNbt#strength})。年份在酒窖箱里随时间长
 * ({@link VintageClock}), 品质系数把同样年份的酒按档拉开差距。闪耀 (BRILLIANT) 为最高档, 触发各酒的
 * "永久 (一条命)" 特殊增益。
 *
 * 系数曲线由策划定 (1.0 / 1.5 / 2.0 / 3.0 / 5.0): 温和递增、闪耀=低级 5 倍, 适配长线职业、数值膨胀可控。
 */
public enum WineQuality {

    LOW("low", 1.0D, ChatFormatting.GRAY),
    MID("mid", 1.5D, ChatFormatting.WHITE),
    HIGH("high", 2.0D, ChatFormatting.AQUA),
    SUPERB("superb", 3.0D, ChatFormatting.LIGHT_PURPLE),
    BRILLIANT("brilliant", 5.0D, ChatFormatting.GOLD);

    private final String id;
    private final double coefficient;
    private final ChatFormatting color;

    WineQuality(String id, double coefficient, ChatFormatting color) {
        this.id = id;
        this.coefficient = coefficient;
        this.color = color;
    }

    /** 小写稳定 id (NBT 键 / lang key 命名空间用)。 */
    public String id() {
        return id;
    }

    /** 品质系数 (强度公式 S = 年份 × 系数 的乘子)。 */
    public double coefficient() {
        return coefficient;
    }

    /** 显示色 (品质名前缀着色; 与厨师 ChefQuality.color 同范式)。 */
    public ChatFormatting color() {
        return color;
    }

    /** 品质前缀 lang key (显示名 "闪耀威士忌" 的前缀部分)。 */
    public String prefixKey() {
        return "miningdim.brewer.quality." + id;
    }

    /** 是否闪耀档 (触发各酒的永久特殊增益)。 */
    public boolean isBrilliant() {
        return this == BRILLIANT;
    }

    /** 按小写 id 反查 (NBT 读取用); 未知返回 null (调用方据此短路, 不静默默认)。 */
    public static WineQuality fromId(String id) {
        for (WineQuality q : values()) {
            if (q.id.equals(id)) {
                return q;
            }
        }
        return null;
    }
}
