package com.miningdim.power.cable;

import com.miningdim.power.grid.EnergyNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 有线 FE 线缆方块 (每级导体各一, 由 {@link ConductorMaterial} 参数化)。实现 {@link EntityBlock} 挂线缆 BE,
 * 但刻意不提供 ticker: 搬电由 {@link EnergyNetworkManager} 集中做 (线缆零 per-tick 成本)。
 * neighborChanged 时只标脏端点集, 让相邻机器/发电机的增减在下次 settlement 被重扫到, 不在此做任何遍历。
 */
public final class EnergyCableBlock extends Block implements EntityBlock {

    private final ConductorMaterial material;

    public EnergyCableBlock(ConductorMaterial material, Properties properties) {
        super(properties);
        this.material = material;
    }

    public ConductorMaterial material() {
        return material;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EnergyCableBlockEntity(pos, state);
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                BlockPos neighborPos, boolean movedByPiston) {
        if (level instanceof ServerLevel serverLevel) {
            EnergyNetworkManager.get(serverLevel).markEndpointsDirty(pos);
        }
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
    }
}
