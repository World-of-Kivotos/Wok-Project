package com.miningdim.job.engineer.armor.client;

import com.miningdim.job.engineer.armor.PlateArmorVariant;
import com.miningdim.job.engineer.armor.item.PlateArmorItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.NotNull;

/** 延迟烘焙 THOR 模型，避免在 ArmorItem 超类构造期间读取尚未赋值的 variant。 */
public final class ThorIntegratedArmorClient implements IClientItemExtensions {

    private final PlateArmorItem armorItem;
    private ThorIntegratedArmorModel model;

    private ThorIntegratedArmorClient(PlateArmorItem armorItem) {
        this.armorItem = armorItem;
    }

    public static IClientItemExtensions forItem(PlateArmorItem armorItem) {
        return new ThorIntegratedArmorClient(armorItem);
    }

    @Override
    @NotNull
    public HumanoidModel<?> getHumanoidArmorModel(LivingEntity livingEntity, ItemStack itemStack,
                                                   EquipmentSlot equipmentSlot, HumanoidModel<?> original) {
        if (equipmentSlot != EquipmentSlot.CHEST
                || itemStack.getItem() != armorItem
                || armorItem.variant() != PlateArmorVariant.THOR_INTEGRATED) {
            return original;
        }
        if (model == null) {
            ModelPart root = Minecraft.getInstance().getEntityModels()
                    .bakeLayer(ThorIntegratedArmorModel.LAYER);
            model = new ThorIntegratedArmorModel(root);
        }
        return model;
    }
}
