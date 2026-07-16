package com.miningdim.job.engineer.shield;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.crafting.Ingredient;

/** Zero-vanilla-defense carrier material; all protection comes from shield energy. */
public enum PlasmaShieldEquipmentMaterial implements ArmorMaterial {
    NANO("plasma_shield_nano", SoundEvents.ARMOR_EQUIP_CHAIN),
    LIGHT("plasma_shield_light", SoundEvents.ARMOR_EQUIP_IRON),
    HEAVY_ION("plasma_shield_heavy_ion", SoundEvents.ARMOR_EQUIP_NETHERITE);

    private final String name;
    private final SoundEvent equipSound;

    PlasmaShieldEquipmentMaterial(String name, SoundEvent equipSound) {
        this.name = name;
        this.equipSound = equipSound;
    }

    public static PlasmaShieldEquipmentMaterial forType(PlasmaShieldType type) {
        return switch (type) {
            case NANO -> NANO;
            case LIGHT -> LIGHT;
            case HEAVY_ION -> HEAVY_ION;
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
        return name;
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
