package com.kivotos.armorer.armor;

import com.kivotos.armorer.armor.item.PlateArmorItem;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;

/** 穿戴状态同步：插板接管原版护甲/韧性，并按构型施加一次机动修正。 */
public final class PlateArmorEquipmentHandler {

    public static final UUID ARMOR_REPLACEMENT_ID = UUID.fromString("5f2234c1-4479-4fb8-a4ba-ef3199bf42a1");
    public static final UUID TOUGHNESS_REPLACEMENT_ID = UUID.fromString("77d8c2e6-9a9c-4da0-b57f-ef8ef1dbdd07");
    public static final UUID MOVEMENT_ID = UUID.fromString("a8df1880-5c7f-4c81-a424-398d22d8372f");

    private static final double FULL_REPLACEMENT = -1.0D;

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && !event.player.level().isClientSide) {
            synchronize(event.player);
        }
    }

    @SubscribeEvent
    public void onEquipmentChanged(LivingEquipmentChangeEvent event) {
        if (event.getEntity() instanceof Player player && !player.level().isClientSide) {
            synchronize(player);
        }
    }

    /** 当前一击的原版护甲阶段结束后清掉击碎护甲留下的瞬时属性；tick 仍作为异常流程兜底。 */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onLivingDamage(LivingDamageEvent event) {
        if (event.getEntity() instanceof Player player && !player.level().isClientSide) {
            synchronize(player);
        }
    }

    public static void synchronize(Player player) {
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() instanceof PlateArmorItem plate && !plate.isFunctional(chest)) {
            plate.breakExhausted(chest, player);
        }
        PlateArmorItem armor = PlateArmorItem.equippedBy(player);
        if (armor == null) {
            remove(player, Attributes.ARMOR, ARMOR_REPLACEMENT_ID);
            remove(player, Attributes.ARMOR_TOUGHNESS, TOUGHNESS_REPLACEMENT_ID);
            remove(player, Attributes.MOVEMENT_SPEED, MOVEMENT_ID);
            return;
        }

        ensure(player, Attributes.ARMOR, ARMOR_REPLACEMENT_ID,
                "plate armor replaces vanilla armor", FULL_REPLACEMENT,
                AttributeModifier.Operation.MULTIPLY_TOTAL);
        ensure(player, Attributes.ARMOR_TOUGHNESS, TOUGHNESS_REPLACEMENT_ID,
                "plate armor replaces vanilla toughness", FULL_REPLACEMENT,
                AttributeModifier.Operation.MULTIPLY_TOTAL);

        double movement = PlateArmorStats.resolve(armor.variant()).movementModifier();
        if (movement == 0.0D) {
            remove(player, Attributes.MOVEMENT_SPEED, MOVEMENT_ID);
        } else {
            ensure(player, Attributes.MOVEMENT_SPEED, MOVEMENT_ID,
                    "plate armor mobility", movement, AttributeModifier.Operation.MULTIPLY_TOTAL);
        }
    }

    private static void ensure(Player player, Attribute attribute, UUID id, String name,
                               double amount, AttributeModifier.Operation operation) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        AttributeModifier current = instance.getModifier(id);
        if (current != null && current.getAmount() == amount && current.getOperation() == operation) {
            return;
        }
        if (current != null) {
            instance.removeModifier(id);
        }
        instance.addTransientModifier(new AttributeModifier(id, name, amount, operation));
    }

    private static void remove(Player player, Attribute attribute, UUID id) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null && instance.getModifier(id) != null) {
            instance.removeModifier(id);
        }
    }
}

