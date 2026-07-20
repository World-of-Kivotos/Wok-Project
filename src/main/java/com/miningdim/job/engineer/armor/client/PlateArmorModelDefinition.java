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
    TACTEC(TactecArmorModel.LAYER, TactecArmorModel::new, TactecArmorModel::createLayer),
    CPC_MOD1(CpcMod1ArmorModel.LAYER, CpcMod1ArmorModel::new, CpcMod1ArmorModel::createLayer),
    FCPC_V5(FcpcV5ArmorModel.LAYER, FcpcV5ArmorModel::new, FcpcV5ArmorModel::createLayer),
    GLADIATOR_S_LIGHT(GladiatorSLightArmorModel.LAYER,
            GladiatorSLightArmorModel::new, GladiatorSLightArmorModel::createLayer),
    HEXATAC_HPC(HexatacHpcArmorModel.LAYER,
            HexatacHpcArmorModel::new, HexatacHpcArmorModel::createLayer),
    B6B45_GENERAL(B6B45GeneralArmorModel.LAYER,
            B6B45GeneralArmorModel::new, B6B45GeneralArmorModel::createLayer),
    B6B45_MEDIC(B6B45MedicArmorModel.LAYER,
            B6B45MedicArmorModel::new, B6B45MedicArmorModel::createLayer),
    GZHEL_K(GzhelKArmorModel.LAYER, GzhelKArmorModel::new, GzhelKArmorModel::createLayer),
    GLADIATOR_S_GRAY(GladiatorSGrayArmorModel.LAYER,
            GladiatorSGrayArmorModel::new, GladiatorSGrayArmorModel::createLayer),
    GLADIATOR_S_VIKING(GladiatorSVikingArmorModel.LAYER,
            GladiatorSVikingArmorModel::new, GladiatorSVikingArmorModel::createLayer),
    TT_MKIII(TtMkiiiArmorModel.LAYER, TtMkiiiArmorModel::new, TtMkiiiArmorModel::createLayer),
    OSPREY_MK4A_PROTECTION(OspreyMk4AProtectionArmorModel.LAYER,
            OspreyMk4AProtectionArmorModel::new, OspreyMk4AProtectionArmorModel::createLayer),
    DEFENDER_2(Defender2ArmorModel.LAYER,
            Defender2ArmorModel::new, Defender2ArmorModel::createLayer),
    GLADIATOR_S_DEATHLESS(GladiatorSDeathlessArmorModel.LAYER,
            GladiatorSDeathlessArmorModel::new, GladiatorSDeathlessArmorModel::createLayer),
    REDUT_M(RedutMArmorModel.LAYER, RedutMArmorModel::new, RedutMArmorModel::createLayer),
    IOTV_GEN4_HIGH_MOBILITY(IotvGen4HighMobilityArmorModel.LAYER,
            IotvGen4HighMobilityArmorModel::new, IotvGen4HighMobilityArmorModel::createLayer),
    IOTV_GEN4_FULL_PROTECTION(IotvGen4FullProtectionArmorModel.LAYER,
            IotvGen4FullProtectionArmorModel::new, IotvGen4FullProtectionArmorModel::createLayer),
    IOTV_GEN4_ASSAULT(IotvGen4AssaultArmorModel.LAYER,
            IotvGen4AssaultArmorModel::new, IotvGen4AssaultArmorModel::createLayer),
    KORUND_VM(KorundVmArmorModel.LAYER,
            KorundVmArmorModel::new, KorundVmArmorModel::createLayer),
    HEXGRID(HexgridArmorModel.LAYER,
            HexgridArmorModel::new, HexgridArmorModel::createLayer),
    SLICK(SlickArmorModel.LAYER,
            SlickArmorModel::new, SlickArmorModel::createLayer),
    STICH_DEFENSE_MOD2(StichDefenseMod2ArmorModel.LAYER,
            StichDefenseMod2ArmorModel::new, StichDefenseMod2ArmorModel::createLayer),
    B6B43_ZABRALO_SH(B6B43ZabraloShArmorModel.LAYER,
            B6B43ZabraloShArmorModel::new, B6B43ZabraloShArmorModel::createLayer),
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
            case TACTEC_RANGER_GREEN -> TACTEC;
            case CPC_MOD1_ATACS_FG -> CPC_MOD1;
            case FCPC_V5 -> FCPC_V5;
            case GLADIATOR_S_LIGHT_MULTICAM -> GLADIATOR_S_LIGHT;
            case HEXATAC_HPC_BLACK_MULTICAM -> HEXATAC_HPC;
            case B6B45_GENERAL -> B6B45_GENERAL;
            case B6B45_MEDIC -> B6B45_MEDIC;
            case GZHEL_K -> GZHEL_K;
            case GLADIATOR_S_GRAY -> GLADIATOR_S_GRAY;
            case GLADIATOR_S_VIKING -> GLADIATOR_S_VIKING;
            case TT_MKIII_COYOTE -> TT_MKIII;
            case OSPREY_MK4A_PROTECTION -> OSPREY_MK4A_PROTECTION;
            case DEFENDER_2_SPOT_CAMO, DEFENDER_2 -> DEFENDER_2;
            case GLADIATOR_S_DEATHLESS -> GLADIATOR_S_DEATHLESS;
            case REDUT_M -> REDUT_M;
            case IOTV_GEN4_HIGH_MOBILITY -> IOTV_GEN4_HIGH_MOBILITY;
            case IOTV_GEN4_FULL_PROTECTION -> IOTV_GEN4_FULL_PROTECTION;
            case IOTV_GEN4_ASSAULT -> IOTV_GEN4_ASSAULT;
            case KORUND_VM_BLACK -> KORUND_VM;
            case HEXGRID -> HEXGRID;
            case SLICK -> SLICK;
            case STICH_DEFENSE_MOD2 -> STICH_DEFENSE_MOD2;
            case B6B43_ZABRALO_SH -> B6B43_ZABRALO_SH;
            case THOR_INTEGRATED -> THOR_INTEGRATED;
            default -> null;
        };
    }
}
