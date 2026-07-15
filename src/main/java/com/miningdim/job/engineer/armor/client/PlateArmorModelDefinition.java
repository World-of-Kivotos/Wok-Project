package com.miningdim.job.engineer.armor.client;

import com.miningdim.job.engineer.armor.PlateArmorVariant;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.client.event.EntityRenderersEvent;

import java.util.function.Function;
import java.util.function.Supplier;

/** 把护甲物品外观映射到原生人形模型层；同款不同配色共享几何。 */
enum PlateArmorModelDefinition {
    JAYPC(JaypcArmorModel.LAYER, JaypcArmorModel::new, JaypcArmorModel::createLayer),
    PACA(PacaArmorModel.LAYER, PacaArmorModel::new, PacaArmorModel::createLayer),
    MBSS(MbssArmorModel.LAYER, MbssArmorModel::new, MbssArmorModel::createLayer),
    TV115(Tv115ArmorModel.LAYER, Tv115ArmorModel::new, Tv115ArmorModel::createLayer),
    B6B23_DIGITAL_FLORA(B6B23DigitalFloraArmorModel.LAYER,
            B6B23DigitalFloraArmorModel::new, B6B23DigitalFloraArmorModel::createLayer),
    B6B5(B6B5ArmorModel.LAYER, B6B5ArmorModel::new, B6B5ArmorModel::createLayer),
    KIRASA_N(KirasaNArmorModel.LAYER, KirasaNArmorModel::new, KirasaNArmorModel::createLayer),
    MF_UNTAR(MfUntarArmorModel.LAYER, MfUntarArmorModel::new, MfUntarArmorModel::createLayer),
    KORA_KULON(KoraKulonArmorModel.LAYER, KoraKulonArmorModel::new, KoraKulonArmorModel::createLayer),
    THOR_INTEGRATED(ThorIntegratedArmorModel.LAYER,
            ThorIntegratedArmorModel::new, ThorIntegratedArmorModel::createLayer);

    private final ModelLayerLocation layer;
    private final Function<ModelPart, HumanoidModel<LivingEntity>> factory;
    private final Supplier<LayerDefinition> layerFactory;

    PlateArmorModelDefinition(ModelLayerLocation layer,
                              Function<ModelPart, HumanoidModel<LivingEntity>> factory,
                              Supplier<LayerDefinition> layerFactory) {
        this.layer = layer;
        this.factory = factory;
        this.layerFactory = layerFactory;
    }

    ModelLayerLocation layer() {
        return layer;
    }

    HumanoidModel<LivingEntity> create(ModelPart root) {
        return factory.apply(root);
    }

    static void registerLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        for (PlateArmorModelDefinition definition : values()) {
            event.registerLayerDefinition(definition.layer, definition.layerFactory);
        }
    }

    static PlateArmorModelDefinition find(PlateArmorVariant variant) {
        return switch (variant) {
            case JAYPC_OLIVE, JAYPC_BLACK -> JAYPC;
            case PACA -> PACA;
            case MBSS -> MBSS;
            case TV115 -> TV115;
            case B6B23_1_DIGITAL_FLORA -> B6B23_DIGITAL_FLORA;
            case B6B5_16 -> B6B5;
            case KIRASA_N_GREEN -> KIRASA_N;
            case MF_UNTAR -> MF_UNTAR;
            case KORA_KULON, KORA_KULON_DIGITAL -> KORA_KULON;
            case THOR_INTEGRATED -> THOR_INTEGRATED;
            default -> null;
        };
    }
}
