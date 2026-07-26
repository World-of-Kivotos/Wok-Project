package com.miningdim.job.engineer.armor.client;

import com.miningdim.job.engineer.armor.item.PlateArmorItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.NotNull;

/** 按物品绑定的外观延迟烘焙原生护甲模型，未建模外观继续使用原版胸甲模型。 */
public final class PlateArmorClient implements IClientItemExtensions {

    private final PlateArmorItem armorItem;
    private HumanoidModel<?> model;

    private PlateArmorClient(PlateArmorItem armorItem) {
        this.armorItem = armorItem;
    }

    public static IClientItemExtensions forItem(PlateArmorItem armorItem) {
        return new PlateArmorClient(armorItem);
    }

    @Override
    @NotNull
    public HumanoidModel<?> getHumanoidArmorModel(LivingEntity livingEntity, ItemStack itemStack,
                                                   EquipmentSlot equipmentSlot, HumanoidModel<?> original) {
        if (equipmentSlot != EquipmentSlot.CHEST || itemStack.getItem() != armorItem) {
            return original;
        }
        PlateArmorModelDefinition definition = PlateArmorModelDefinition.find(armorItem.variant());
        if (definition == null) {
            return original;
        }
        if (model == null) {
            ModelPart root = Minecraft.getInstance().getEntityModels().bakeLayer(definition.layer());
            model = definition.create(root);
        }
        return model;
    }
}
