package com.miningdim.job.farmer.granary.block;

import com.miningdim.job.farmer.granary.GranarySystem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * 粮仓账台 (Spoilage_Granary_DesignSpec「部件」): 粮仓的大脑, 本身不装东西。
 *
 * 可以放在屋里, 也可以嵌在墙上 (建议嵌在外墙, 来验收的人在屋外就能右键到) —— 结构判定是按「账台周围
 * 能走的格子」起洪泛的, 所以嵌墙不影响判定。每人最多两座, 上限在放置事件里查。
 */
public final class GranaryCenterBlock extends BaseEntityBlock {

    public GranaryCenterBlock(Properties properties) {
        super(properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GranaryCenterBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel serverLevel) || !(placer instanceof ServerPlayer player)) {
            return;
        }
        if (serverLevel.getBlockEntity(pos) instanceof GranaryCenterBlockEntity center) {
            GranarySystem.bindCenterOwner(serverLevel, pos, center, player);
        }
    }

    /** 只有房主或 OP 能拆账台 (成员还在时不能拆的规矩随成员功能分片加入)。 */
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
                                       boolean willHarvest, FluidState fluid) {
        if (level instanceof ServerLevel && level.getBlockEntity(pos) instanceof GranaryCenterBlockEntity center) {
            if (center.owner() != null && !center.owner().equals(player.getUUID()) && !player.hasPermissions(2)) {
                player.displayClientMessage(Component.translatable("message.miningdim.granary.not_owner"), true);
                return false;
            }
        }
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    /** 方块移除的汇合点: 归还账台额度。 */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            GranarySystem.releaseCenter(serverLevel, pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
