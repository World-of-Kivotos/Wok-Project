package com.kivotos.armorer.shield.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.NotNull;

/** Client-only empty wearable model used until a dedicated field-emitter geometry is approved. */
public final class PlasmaShieldClientArmor implements IClientItemExtensions {

    private static final PlasmaShieldClientArmor INSTANCE = new PlasmaShieldClientArmor();
    private HumanoidModel<?> model;

    private PlasmaShieldClientArmor() {
    }

    public static IClientItemExtensions extension() {
        return INSTANCE;
    }

    @Override
    @NotNull
    public HumanoidModel<?> getHumanoidArmorModel(LivingEntity livingEntity, ItemStack itemStack,
                                                   EquipmentSlot equipmentSlot, HumanoidModel<?> original) {
        if (equipmentSlot != EquipmentSlot.CHEST) {
            return original;
        }
        if (model == null) {
            ModelPart root = Minecraft.getInstance().getEntityModels().bakeLayer(PlasmaShieldArmorModel.LAYER);
            model = new PlasmaShieldArmorModel(root);
        }
        return model;
    }
}

