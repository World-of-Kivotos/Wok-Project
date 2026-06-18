package com.miningdim.job.chef;

import net.minecraft.ChatFormatting;

/**
 * 厨师品质 5 档 (Chef_Job_DesignSpec 第三章; 沿用塔罗/工程师词汇 低/中/高/超凡/闪耀)。
 *
 * 每档携带 (Chef spec 第三章 "品质决定两件事"):
 *  - {@link #maxEffects()}: 一道菜带几个效果 (低 1 -> 闪耀最多 3);
 *  - {@link #noFailure()}: 是否零翻车 (超凡/闪耀 = true, 不出夹生/烧焦/倒胃/多盐/失败品, 黑暗 100% 大吉);
 *  - {@link #tier()}: 0-based 索引, 供逐级数值表 (倍率/数值/经验) 按档查。
 *
 * combatUnlocked (第六章红线): 战斗向效果只在 高/超凡/闪耀 解锁; 低/中级永不带战斗向。
 *
 * NBT 持久化: 以稳定小写 id ({@link #id()}) 写入 ItemStack NBT, 跨版本不随枚举重排漂移
 * ({@link #fromId(String)} 反查; 未知 id 返回 null 由调用方短路, 不静默掩盖)。
 */
public enum ChefQuality {

    LOW("low", 0, 1, false, false, ChatFormatting.GRAY, "chef.quality.prefix.low"),
    MEDIUM("medium", 1, 1, false, false, ChatFormatting.GREEN, "chef.quality.prefix.medium"),
    HIGH("high", 2, 2, false, true, ChatFormatting.AQUA, "chef.quality.prefix.high"),
    EXTRAORDINARY("extraordinary", 3, 2, true, true, ChatFormatting.LIGHT_PURPLE, "chef.quality.prefix.extraordinary"),
    RADIANT("radiant", 4, 3, true, true, ChatFormatting.GOLD, "chef.quality.prefix.radiant");

    private final String id;
    private final int tier;
    private final int maxEffects;
    private final boolean noFailure;
    private final boolean combatUnlocked;
    private final ChatFormatting color;
    private final String prefixKey;

    ChefQuality(String id, int tier, int maxEffects, boolean noFailure, boolean combatUnlocked,
                ChatFormatting color, String prefixKey) {
        this.id = id;
        this.tier = tier;
        this.maxEffects = maxEffects;
        this.noFailure = noFailure;
        this.combatUnlocked = combatUnlocked;
        this.color = color;
        this.prefixKey = prefixKey;
    }

    /** 稳定小写 id (NBT 键 / lang / 命令)。 */
    public String id() {
        return id;
    }

    /** 0-based 档位索引 (逐级数值表按此查列, LOW=0 .. RADIANT=4)。 */
    public int tier() {
        return tier;
    }

    /** 一道菜最多带几个效果 (第三章: 低 1 -> 闪耀 3)。 */
    public int maxEffects() {
        return maxEffects;
    }

    /** 是否零翻车 (超凡/闪耀=true; 第三章: 高品质不出任何负面)。 */
    public boolean noFailure() {
        return noFailure;
    }

    /** 战斗向效果是否在本档解锁 (第六章红线: 仅 高/超凡/闪耀)。 */
    public boolean combatUnlocked() {
        return combatUnlocked;
    }

    /** 显示名颜色 (HUD 着色)。 */
    public ChatFormatting color() {
        return color;
    }

    /** 品质前缀 lang key ("超凡面包" 的 "超凡")。 */
    public String prefixKey() {
        return prefixKey;
    }

    /** 本档是否高于/等于另一档 (台档 + 厨师等级双重封顶用)。 */
    public boolean atLeast(ChefQuality other) {
        return this.tier >= other.tier;
    }

    /** 取两档中较低者 (封顶: min(台档, 厨师等级上限, 综合分档))。 */
    public static ChefQuality min(ChefQuality a, ChefQuality b) {
        return a.tier <= b.tier ? a : b;
    }

    /** 按 0-based 档位索引取档 (越界向最近端钳制, 防小游戏综合分越界击穿)。 */
    public static ChefQuality byTier(int tier) {
        ChefQuality[] all = values();
        if (tier < 0) {
            return all[0];
        }
        if (tier >= all.length) {
            return all[all.length - 1];
        }
        return all[tier];
    }

    /** 按稳定 id 反查; 未知返回 null (调用方短路, 不静默回退默认档掩盖坏数据)。 */
    public static ChefQuality fromId(String id) {
        for (ChefQuality q : values()) {
            if (q.id.equals(id)) {
                return q;
            }
        }
        return null;
    }
}
