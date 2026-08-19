package com.miningdim.job.engineer.armor;

import com.miningdim.job.engineer.armor.item.PlateArmorItem;
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
        // isFinite 显式判: NaN 与任何值比较都为 false, 单靠 amount <= 0 会放行非有限伤害, 随后被插板
        // 公式算成一个成功减伤结果并写回事件, 等于佩戴者免疫且掩盖上游数据错误。
        if (!Float.isFinite(event.getAmount()) || event.getAmount() <= 0.0F
                || !(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack armorStack = player.getItemBySlot(EquipmentSlot.CHEST);
        if (!(armorStack.getItem() instanceof PlateArmorItem armor) || !armor.isFunctional(armorStack)) {
            return;
        }

        PlateArmorStats stats = PlateArmorStats.resolve(armor.variant());
        double input = event.getAmount();
        int fePerPoint = PlateArmorConfig.fePerAbsorbedDamage();
        // 没电的插板退化成普通护甲: 不减伤、不磨损、也不静默假装工作。
        if (fePerPoint > 0 && PlateArmorPowerCell.storedEnergy(armorStack) <= 0) {
            return;
        }
        PlateArmorDamageClassifier.Kind kind = PlateArmorDamageClassifier.classify(event.getSource());
        double output = switch (kind) {
            case BALLISTIC_NORMAL -> PlateArmorMath.reduceSegment(input, stats.ballisticProtection());
            case BALLISTIC_ARMOR_PIERCING -> PlateArmorMath.reduceSegment(input, stats.armorPiercingBuffer());
            case GENERAL_PHYSICAL -> PlateArmorMath.reduceWithPressureCapacity(
                    input, stats.generalProtection(), stats.pressureCapacity());
            case EXCLUDED -> input;
        };
        // 电力按实际吸收量计费: 挡得多扣得多, 不打架完全不掉电。电不够时按可支付的比例回退减伤,
        // 而不是让最后一次防护免费 —— 否则残电可以无限次挡满伤害。
        double absorbed = input - output;
        if (fePerPoint > 0 && absorbed > 0.0D) {
            int demand = (int) Math.ceil(absorbed * fePerPoint);
            int paid = PlateArmorPowerCell.consume(armorStack, demand);
            if (paid < demand) {
                double affordable = (double) paid / fePerPoint;
                output = input - Math.min(absorbed, affordable);
            }
        }
        event.setAmount((float) output);

        // TaCZ 一颗弹丸会产生两个 LivingHurtEvent，耐久统一留给 Post/Kill 集成只扣一次。
        if (kind == PlateArmorDamageClassifier.Kind.GENERAL_PHYSICAL) {
            armor.applyCombatWear(armorStack, input, player);
        }
    }
}
