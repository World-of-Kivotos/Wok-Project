package com.miningdim.job.fisher.ore;

import net.minecraft.world.item.Rarity;

/** 鱼种档次独立于厨师给成品菜盖出的加工品质。 */
public enum OreFishType {
    IRON("iron", Rarity.COMMON),
    GOLD("gold", Rarity.UNCOMMON),
    DIAMOND("diamond", Rarity.RARE),
    EMERALD("emerald", Rarity.RARE),
    DARK_GOLD("dark_gold", Rarity.EPIC);

    private final String id;
    private final Rarity rarity;

    OreFishType(String id, Rarity rarity) {
        this.id = id;
        this.rarity = rarity;
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

    public Rarity rarity() {
        return rarity;
    }
}
