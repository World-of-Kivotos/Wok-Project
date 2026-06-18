package com.miningdim.job.farmer.block;

import com.miningdim.job.farmer.FarmerTier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * mod 耕地方块, 五档各一个实例 (低/中/高/极品/超凡), 档位在构造时绑定 (FarmingXP_Mod_DesignSpec 表B)。
 *
 * 与原版 FarmBlock 的区别 (刻意不继承 FarmBlock):
 *  - 不带 moisture 状态、不会被踩踏退化为泥土 (耕地是职业产出基建, 退化会破坏体验且让方块上限计数漂移);
 *  - 不是 "作物只是长在普通耕地上", 而是经 {@link com.miningdim.job.farmer.FarmerTags#FARMER_FARMLAND}
 *    tag 作为 {@link FarmerCropBlock} 存活基底 —— 原版耕地不在此 tag, 故 mod 作物只能长在 mod 耕地上
 *    (设计目标 2 反扩建)。
 *
 * 本档成长速率不写在耕地上, 而由作物 {@link FarmerCropBlock} 在 randomTick 时读取下方耕地的 tier 决定
 * (耕地是 "数值来源", 作物是 "成长执行者")。耕地本体只承载档位标识。
 *
 * 放置数量受玩家等级硬封顶: 实际拒放逻辑在 {@link com.miningdim.job.farmer.FarmlandPlacementGuard} +
 * {@link com.miningdim.job.farmer.FarmerSystem#onFarmlandPlace} 事件层裁决 (方块本体不持有计数, 保持无状态)。
 */
public final class FarmerFarmlandBlock extends Block {

    private final FarmerTier tier;

    public FarmerFarmlandBlock(BlockBehaviour.Properties properties, FarmerTier tier) {
        super(properties);
        if (tier == null) {
            throw new IllegalArgumentException("FarmerFarmlandBlock tier must not be null");
        }
        this.tier = tier;
    }

    /** 本耕地档位 (作物成长速率/产量来源)。 */
    public FarmerTier tier() {
        return tier;
    }

    /**
     * 不退化: 覆写 fallOn 关掉原版 FarmBlock 的踩踏退化路径 —— 本类继承普通 Block 本就无退化逻辑,
     * 此处显式留注释说明该差异, 不额外改行为 (普通 Block.fallOn 仅做落地伤害, 与耕地无关, 不覆写)。
     */
    @Override
    public void fallOn(net.minecraft.world.level.Level level, BlockState state, BlockPos pos, Entity entity, float fallDistance) {
        // 普通方块默认落地行为 (摔落伤害正常), 不触发耕地退化 (本类无 moisture/退化状态)。
        super.fallOn(level, state, pos, entity, fallDistance);
    }
}
