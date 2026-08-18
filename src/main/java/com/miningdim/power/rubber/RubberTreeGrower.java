package com.miningdim.power.rubber;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.grower.AbstractTreeGrower;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import org.jetbrains.annotations.Nullable;

/** 橡胶树苗固定指向数据包中的 miningdim:rubber_tree 配置特征。 */
public final class RubberTreeGrower extends AbstractTreeGrower {

    private static final ResourceKey<ConfiguredFeature<?, ?>> RUBBER_TREE = ResourceKey.create(
            Registries.CONFIGURED_FEATURE, new ResourceLocation(MiningConstants.MODID, "rubber_tree"));

    @Nullable
    @Override
    protected ResourceKey<ConfiguredFeature<?, ?>> getConfiguredFeature(RandomSource random, boolean hasFlowers) {
        return RUBBER_TREE;
    }
}
