package com.kivotos.armorer.armor;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * 胸部装备壳。轻、中、重明确复用原版三种人形胸甲层和装备音，不制作自定义穿戴/3D 模型；
 * 实际防护完全由 R/Q/G/T 结算，不提供原版护甲值。
 */
public enum PlateArmorEquipmentMaterial implements ArmorMaterial {
    LIGHT("leather", SoundEvents.ARMOR_EQUIP_LEATHER),
    MEDIUM("iron", SoundEvents.ARMOR_EQUIP_IRON),
    HEAVY("netherite", SoundEvents.ARMOR_EQUIP_NETHERITE);

    private final String textureName;
    private final SoundEvent equipSound;

    PlateArmorEquipmentMaterial(String textureName, SoundEvent equipSound) {
        this.textureName = textureName;
        this.equipSound = equipSound;
    }

    public static PlateArmorEquipmentMaterial forWeight(PlateArmorWeight weight) {
        return switch (weight) {
            case LIGHT -> LIGHT;
            case MEDIUM -> MEDIUM;
            case HEAVY -> HEAVY;
        };
    }

    @Override
    public int getDurabilityForType(ArmorItem.Type type) {
        return 1;
    }

    @Override
    public int getDefenseForType(ArmorItem.Type type) {
        return 0;
    }

    @Override
    public int getEnchantmentValue() {
        return 0;
    }

    @Override
    public SoundEvent getEquipSound() {
        return equipSound;
    }

    @Override
    public Ingredient getRepairIngredient() {
        return Ingredient.EMPTY;
    }

    @Override
    public String getName() {
        return textureName;
    }

    @Override
    public float getToughness() {
        return 0.0F;
    }

    @Override
    public float getKnockbackResistance() {
        return 0.0F;
    }
}

