package com.miningdim.registry;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * 维度相关 ResourceKey 的集中引用 (设计文档第四章)。维度本体走数据包 JSON 注册 (4.4),
 * 运行时不增删维度 (C1); 本类仅转引 MiningConstants 的键, 给 registry 包内/下游一个统一取键点,
 * 避免各处重复 ResourceKey.create。
 */
public final class ModDimensions {

    private ModDimensions() {
    }

    /** 唯一矿山维度 Level 键 (= miningdim:mining)。 */
    public static final ResourceKey<Level> MINING_LEVEL = MiningConstants.MINING_LEVEL;

    /** 矿山维度类型键。 */
    public static final ResourceKey<DimensionType> MINING_DIM_TYPE = MiningConstants.MINING_DIM_TYPE;
}
