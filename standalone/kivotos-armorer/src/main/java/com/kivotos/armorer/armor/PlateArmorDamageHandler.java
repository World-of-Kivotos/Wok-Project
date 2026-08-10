package com.kivotos.armorer.armor;

import com.kivotos.armorer.armor.item.PlateArmorItem;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 插板受击结算点。LOW 保证先接收冠军 HIGH 重写与易伤 NORMAL 放大，再把结果交给 LOWEST 职业减伤。
 */
public final class PlateArmorDamageHandler {

    /** 先把同 tick 换装状态同步到属性，避免插板公式后又误叠一次原版护甲。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void beforeLivingHurt(LivingHurtEvent event) {
        if (event.getEntity() instanceof Player player && !player.level().isClientSide) {
            PlateArmorEquipmentHandler.synchronize(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onLivingHurt(LivingHurtEvent event) {
        if (event.getAmount() <= 0.0F || !(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack armorStack = player.getItemBySlot(EquipmentSlot.CHEST);
        if (!(armorStack.getItem() instanceof PlateArmorItem armor) || !armor.isFunctional(armorStack)) {
            return;
        }

        PlateArmorStats stats = PlateArmorStats.resolve(armor.variant());
        double input = event.getAmount();
        PlateArmorDamageClassifier.Kind kind = PlateArmorDamageClassifier.classify(event.getSource());
        double output = switch (kind) {
            case BALLISTIC_NORMAL -> PlateArmorMath.reduceSegment(input, stats.ballisticProtection());
            case BALLISTIC_ARMOR_PIERCING -> PlateArmorMath.reduceSegment(input, stats.armorPiercingBuffer());
            case GENERAL_PHYSICAL -> PlateArmorMath.reduceWithPressureCapacity(
                    input, stats.generalProtection(), stats.pressureCapacity());
            case EXCLUDED -> input;
        };
        event.setAmount((float) output);

        // TaCZ 一颗弹丸会产生两个 LivingHurtEvent，耐久统一留给 Post/Kill 集成只扣一次。
        if (kind == PlateArmorDamageClassifier.Kind.GENERAL_PHYSICAL) {
            armor.applyCombatWear(armorStack, input, player);
        }
    }
}

