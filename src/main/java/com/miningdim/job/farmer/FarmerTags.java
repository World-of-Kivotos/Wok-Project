package com.miningdim.job.farmer;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * 农夫方块 tag 键持有者 (FarmingXP_Mod_DesignSpec 资源: tags/blocks/farmer_farmland)。
 *
 * {@link #FARMER_FARMLAND} 是 "mod 作物只能长在 mod 耕地" 强制约束的判定基底 (设计目标 2 反扩建):
 * {@link FarmerCropBlock#mayPlaceOn}/canSurvive 仅当下方方块在此 tag 内才允许; 原版耕地不在此 tag,
 * 故原版耕地上的 mod 作物无法存活/成长 (从而不产经验)。tag 成员由 data/miningdim/tags/blocks/farmer_farmland.json 填充。
 */
public final class FarmerTags {

    private FarmerTags() {
    }

    /** 全部五档 mod 耕地的方块 tag (作物存活基底判定)。 */
    public static final TagKey<Block> FARMER_FARMLAND =
            TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                    new ResourceLocation(MiningConstants.MODID, "farmer_farmland"));
}
