package com.miningdim.job.engineer.armor;

/**
 * 已分类的 54 件塔科夫护甲外观。每个外观拥有稳定物品 ID，同一等级与构型下只共享数值，不共享物品身份。
 */
public enum PlateArmorVariant {
    JAYPC_OLIVE("jaypc_olive", PlateArmorTier.I, PlateArmorWeight.LIGHT, PlateArmorConstructionMaterial.UHMWPE),
    JAYPC_BLACK("jaypc_black", PlateArmorTier.I, PlateArmorWeight.LIGHT, PlateArmorConstructionMaterial.UHMWPE),

    PACA("paca", PlateArmorTier.II, PlateArmorWeight.LIGHT, PlateArmorConstructionMaterial.ARAMID),

    MBSS("mbss", PlateArmorTier.III, PlateArmorWeight.LIGHT, PlateArmorConstructionMaterial.UHMWPE),
    TV115("tv115", PlateArmorTier.III, PlateArmorWeight.LIGHT, PlateArmorConstructionMaterial.UHMWPE),
    B6B23_1_DIGITAL_FLORA("6b23_1_digital_flora", PlateArmorTier.III, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.ARMOR_STEEL),
    B6B5_16("6b5_16", PlateArmorTier.III, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.TITANIUM_ARAMID),
    KIRASA_N_GREEN("kirasa_n_green", PlateArmorTier.III, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.COMBINED),
    MF_UNTAR("mf_untar", PlateArmorTier.III, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.ALUMINUM),
    KORA_KULON("kora_kulon", PlateArmorTier.III, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.ARMOR_STEEL),
    KORA_KULON_DIGITAL("kora_kulon_digital", PlateArmorTier.III, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.ARMOR_STEEL),

    MMAC_RANGER_GREEN("mmac_ranger_green", PlateArmorTier.IV, PlateArmorWeight.LIGHT, PlateArmorConstructionMaterial.UHMWPE),
    RBAV_AF_RANGER_GREEN("rbav_af_ranger_green", PlateArmorTier.IV, PlateArmorWeight.LIGHT, PlateArmorConstructionMaterial.TITANIUM),
    STRANDHOGG_RANGER_GREEN("strandhogg_ranger_green", PlateArmorTier.IV, PlateArmorWeight.LIGHT, PlateArmorConstructionMaterial.ALUMINUM),
    STRANDHOGG_BLACK_MULTICAM("strandhogg_black_multicam", PlateArmorTier.IV, PlateArmorWeight.LIGHT, PlateArmorConstructionMaterial.ALUMINUM),
    TROOPER_TFO_MULTICAM("trooper_tfo_multicam", PlateArmorTier.IV, PlateArmorWeight.LIGHT, PlateArmorConstructionMaterial.UHMWPE),
    BANSHEE_ATACS_AU("banshee_atacs_au", PlateArmorTier.IV, PlateArmorWeight.LIGHT, PlateArmorConstructionMaterial.UHMWPE),
    B6B13_FLORA("6b13_flora", PlateArmorTier.IV, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.ARMOR_STEEL),
    B6B3TM_01M_KHAKI("6b3tm_01m_khaki", PlateArmorTier.IV, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.TITANIUM),
    ANA_M1_OLIVE("ana_m1_olive", PlateArmorTier.IV, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.ARMOR_STEEL),
    A18_SKANDA_MULTICAM("a18_skanda_multicam", PlateArmorTier.IV, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.COMBINED),
    AVS_RANGER_GREEN("avs_ranger_green", PlateArmorTier.IV, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.COMBINED),
    AVS_MULTICAM("avs_multicam", PlateArmorTier.IV, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.COMBINED),
    THOR_CONCEALABLE("thor_concealable", PlateArmorTier.IV, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.COMBINED),
    STICH_PROFI_V2_BLACK("stich_profi_v2_black", PlateArmorTier.IV, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.ARMOR_STEEL),
    TV110_COYOTE("tv110_coyote", PlateArmorTier.IV, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.ARMOR_STEEL),
    B6B23_2_MOUNTAIN_FLORA("6b23_2_mountain_flora", PlateArmorTier.IV, PlateArmorWeight.HEAVY, PlateArmorConstructionMaterial.ARMOR_STEEL),
    B6B5_15_FLORA("6b5_15_flora", PlateArmorTier.IV, PlateArmorWeight.HEAVY, PlateArmorConstructionMaterial.CERAMIC_ARAMID),
    OSPREY_MK4A_ASSAULT("osprey_mk4a_assault", PlateArmorTier.IV, PlateArmorWeight.HEAVY, PlateArmorConstructionMaterial.ALUMINUM),

    TACTEC_RANGER_GREEN("tactec_ranger_green", PlateArmorTier.V, PlateArmorWeight.LIGHT, PlateArmorConstructionMaterial.UHMWPE),
    CPC_MOD1_ATACS_FG("cpc_mod1_atacs_fg", PlateArmorTier.V, PlateArmorWeight.LIGHT, PlateArmorConstructionMaterial.UHMWPE),
    FCPC_V5("fcpc_v5", PlateArmorTier.V, PlateArmorWeight.LIGHT, PlateArmorConstructionMaterial.UHMWPE),
    GLADIATOR_S_LIGHT_MULTICAM("gladiator_s_light_multicam", PlateArmorTier.V, PlateArmorWeight.LIGHT, PlateArmorConstructionMaterial.CERAMIC),
    HEXATAC_HPC_BLACK_MULTICAM("hexatac_hpc_black_multicam", PlateArmorTier.V, PlateArmorWeight.LIGHT, PlateArmorConstructionMaterial.UHMWPE),
    B6B45_GENERAL("6b45_general", PlateArmorTier.V, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.CERAMIC),
    B6B45_MEDIC("6b45_medic", PlateArmorTier.V, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.CERAMIC),
    GZHEL_K("gzhel_k", PlateArmorTier.V, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.CERAMIC),
    GLADIATOR_S_GRAY("gladiator_s_gray", PlateArmorTier.V, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.CERAMIC),
    GLADIATOR_S_VIKING("gladiator_s_viking", PlateArmorTier.V, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.CERAMIC),
    TT_MKIII_COYOTE("tt_mkiii_coyote", PlateArmorTier.V, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.COMBINED),
    OSPREY_MK4A_PROTECTION("osprey_mk4a_protection", PlateArmorTier.V, PlateArmorWeight.HEAVY, PlateArmorConstructionMaterial.COMBINED),
    DEFENDER_2_SPOT_CAMO("defender_2_spot_camo", PlateArmorTier.V, PlateArmorWeight.HEAVY, PlateArmorConstructionMaterial.CERAMIC),
    DEFENDER_2("defender_2", PlateArmorTier.V, PlateArmorWeight.HEAVY, PlateArmorConstructionMaterial.CERAMIC),
    GLADIATOR_S_DEATHLESS("gladiator_s_deathless", PlateArmorTier.V, PlateArmorWeight.HEAVY, PlateArmorConstructionMaterial.CERAMIC),
    REDUT_M("redut_m", PlateArmorTier.V, PlateArmorWeight.HEAVY, PlateArmorConstructionMaterial.CERAMIC),
    IOTV_GEN4_HIGH_MOBILITY("iotv_gen4_high_mobility", PlateArmorTier.V, PlateArmorWeight.HEAVY, PlateArmorConstructionMaterial.TITANIUM),
    IOTV_GEN4_FULL_PROTECTION("iotv_gen4_full_protection", PlateArmorTier.V, PlateArmorWeight.HEAVY, PlateArmorConstructionMaterial.TITANIUM),
    IOTV_GEN4_ASSAULT("iotv_gen4_assault", PlateArmorTier.V, PlateArmorWeight.HEAVY, PlateArmorConstructionMaterial.TITANIUM),
    KORUND_VM_BLACK("korund_vm_black", PlateArmorTier.V, PlateArmorWeight.HEAVY, PlateArmorConstructionMaterial.ARMOR_STEEL),

    HEXGRID("hexgrid", PlateArmorTier.VI, PlateArmorWeight.LIGHT, PlateArmorConstructionMaterial.UHMWPE),
    SLICK("slick", PlateArmorTier.VI, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.ARMOR_STEEL),
    STICH_DEFENSE_MOD2("stich_defense_mod2", PlateArmorTier.VI, PlateArmorWeight.MEDIUM, PlateArmorConstructionMaterial.UHMWPE),
    B6B43_ZABRALO_SH("6b43_zabralo_sh", PlateArmorTier.VI, PlateArmorWeight.HEAVY, PlateArmorConstructionMaterial.CERAMIC),
    THOR_INTEGRATED("thor_integrated", PlateArmorTier.VI, PlateArmorWeight.HEAVY, PlateArmorConstructionMaterial.COMBINED);

    private final String id;
    private final PlateArmorTier tier;
    private final PlateArmorWeight weight;
    private final PlateArmorConstructionMaterial material;

    PlateArmorVariant(String id, PlateArmorTier tier, PlateArmorWeight weight,
                      PlateArmorConstructionMaterial material) {
        this.id = id;
        this.tier = tier;
        this.weight = weight;
        this.material = material;
    }

    public String id() {
        return id;
    }

    public String itemId() {
        return "plate_armor_" + id;
    }

    public PlateArmorTier tier() {
        return tier;
    }

    public PlateArmorWeight weight() {
        return weight;
    }

    public PlateArmorConstructionMaterial material() {
        return material;
    }
}
