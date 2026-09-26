package com.miningdim.job.fisher.ore;

import com.miningdim.job.fisher.quality.FishQuality;

/**
 * 矿石鱼五档。鱼种品质与 {@code data/miningdim/tags/items/fish_quality/*.json} 里的归档一致 (由 GameTest 核对):
 * 物品注册时先按这里的品质给默认稀有度, 标签绑定后由品质标签统一覆盖名字颜色, 两者不得分叉。
 * 鱼种品质独立于厨师给成品菜盖出的加工品质。
 */
public enum OreFishType {
    IRON("iron", FishQuality.COMMON),
    GOLD("gold", FishQuality.FINE),
    DIAMOND("diamond", FishQuality.RARE),
    EMERALD("emerald", FishQuality.EPIC),
    DARK_GOLD("dark_gold", FishQuality.LEGENDARY);

    private final String id;
    private final FishQuality quality;

    OreFishType(String id, FishQuality quality) {
        this.id = id;
        this.quality = quality;
    }

    public String id() {
        return id;
    }

    public String fishId() {
        return id + "_ore_fish";
    }

    public String soupId() {
        return fishId() + "_soup";
    }

    public FishQuality quality() {
        return quality;
    }
}
