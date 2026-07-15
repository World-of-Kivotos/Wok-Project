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
    MMAC(MmacArmorModel.LAYER, MmacArmorModel::new, MmacArmorModel::createLayer),
    RBAV_AF(RbavAfArmorModel.LAYER, RbavAfArmorModel::new, RbavAfArmorModel::createLayer),
    STRANDHOGG(StrandhoggArmorModel.LAYER, StrandhoggArmorModel::new, StrandhoggArmorModel::createLayer),
    TROOPER_TFO(TrooperTfoArmorModel.LAYER, TrooperTfoArmorModel::new, TrooperTfoArmorModel::createLayer),
    BANSHEE(BansheeArmorModel.LAYER, BansheeArmorModel::new, BansheeArmorModel::createLayer),
    B6B13(B6B13ArmorModel.LAYER, B6B13ArmorModel::new, B6B13ArmorModel::createLayer),
    B6B3TM_01M(B6B3Tm01MArmorModel.LAYER, B6B3Tm01MArmorModel::new, B6B3Tm01MArmorModel::createLayer),
    ANA_M1(AnaM1ArmorModel.LAYER, AnaM1ArmorModel::new, AnaM1ArmorModel::createLayer),
    A18_SKANDA(A18SkandaArmorModel.LAYER, A18SkandaArmorModel::new, A18SkandaArmorModel::createLayer),
    AVS(AvsArmorModel.LAYER, AvsArmorModel::new, AvsArmorModel::createLayer),
    THOR_CONCEALABLE(ThorConcealableArmorModel.LAYER,
            ThorConcealableArmorModel::new, ThorConcealableArmorModel::createLayer),
    STICH_PROFI_V2(StichProfiV2ArmorModel.LAYER,
            StichProfiV2ArmorModel::new, StichProfiV2ArmorModel::createLayer),
    TV110(Tv110ArmorModel.LAYER, Tv110ArmorModel::new, Tv110ArmorModel::createLayer),
    B6B23_MOUNTAIN_FLORA(B6B23MountainFloraArmorModel.LAYER,
            B6B23MountainFloraArmorModel::new, B6B23MountainFloraArmorModel::createLayer),
    B6B5_FLORA(B6B5FloraArmorModel.LAYER, B6B5FloraArmorModel::new, B6B5FloraArmorModel::createLayer),
    OSPREY_MK4A_ASSAULT(OspreyMk4AAssaultArmorModel.LAYER,
            OspreyMk4AAssaultArmorModel::new, OspreyMk4AAssaultArmorModel::createLayer),
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
            case MMAC_RANGER_GREEN -> MMAC;
            case RBAV_AF_RANGER_GREEN -> RBAV_AF;
            case STRANDHOGG_RANGER_GREEN, STRANDHOGG_BLACK_MULTICAM -> STRANDHOGG;
            case TROOPER_TFO_MULTICAM -> TROOPER_TFO;
            case BANSHEE_ATACS_AU -> BANSHEE;
            case B6B13_FLORA -> B6B13;
            case B6B3TM_01M_KHAKI -> B6B3TM_01M;
            case ANA_M1_OLIVE -> ANA_M1;
            case A18_SKANDA_MULTICAM -> A18_SKANDA;
            case AVS_RANGER_GREEN, AVS_MULTICAM -> AVS;
            case THOR_CONCEALABLE -> THOR_CONCEALABLE;
            case STICH_PROFI_V2_BLACK -> STICH_PROFI_V2;
            case TV110_COYOTE -> TV110;
            case B6B23_2_MOUNTAIN_FLORA -> B6B23_MOUNTAIN_FLORA;
            case B6B5_15_FLORA -> B6B5_FLORA;
            case OSPREY_MK4A_ASSAULT -> OSPREY_MK4A_ASSAULT;
            case THOR_INTEGRATED -> THOR_INTEGRATED;
            default -> null;
        };
    }
}
