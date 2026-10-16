package com.miningdim.job.farmer.granary.block;

import com.miningdim.job.farmer.granary.GranarySystem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
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
 * 储粮囤方块 (Spoilage_Granary_DesignSpec「部件」「保护与防复制」「拆除与归还」)。
 *
 * 本片落的规矩:
 * <ul>
 *   <li>归属登记与额度归还的唯一权威点是 {@code onRemove} (照耕地那套, 覆盖玩家破坏、爆炸、活塞、指令
 *       /setblock 等全部路径), 判据是 {@code !state.is(newState.getBlock())};</li>
 *   <li>拆囤必须先取空, 且只有囤主或 OP 能拆 (非主人的破坏请求直接取消);</li>
 *   <li>里面还有东西时被移除 (指令/爆炸这类非玩家路径): 先原地掉出来, 免得存档里的东西凭空消失 ——
 *       设计文档的正式口径是进囤主的「待领粮单」, 那张表随待领功能分片加入后再改成不掉落。</li>
 * </ul>
 */
public final class GranaryBinBlock extends BaseEntityBlock {

    public GranaryBinBlock(Properties properties) {
        super(properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GranaryBinBlockEntity(pos, state);
    }

    /** 放置时把归属写给方块实体 (登记在放置事件里做, 这里只负责让囤知道自己的主人)。 */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel serverLevel) || !(placer instanceof ServerPlayer player)) {
            return;
        }
        if (serverLevel.getBlockEntity(pos) instanceof GranaryBinBlockEntity bin) {
            GranarySystem.bindOwner(serverLevel, pos, bin, player);
        }
    }

    /** 拆囤的两条前置: 必须是囤主或 OP, 而且必须先取空。返回 false 即取消这次破坏。 */
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
                                       boolean willHarvest, FluidState fluid) {
        if (level instanceof ServerLevel && level.getBlockEntity(pos) instanceof GranaryBinBlockEntity bin) {
            if (bin.owner() != null && !bin.owner().equals(player.getUUID()) && !player.hasPermissions(2)) {
                player.displayClientMessage(Component.translatable("message.miningdim.granary.not_owner"), true);
                return false;
            }
            if (!bin.isEmpty()) {
                player.displayClientMessage(Component.translatable("message.miningdim.granary.bin_not_empty"), true);
                return false;
            }
        }
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    /**
     * 方块移除的汇合点: 归还额度。
     *
     * 判据与耕地一致 —— 只有方块真正被换成另一种方块时才回收, 同方块状态翻转不误扣。
     * **不要**跳过 {@code restoringBlockSnapshots}: 被后续监听器取消的放置本来就要在这里把额度还回去。
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            if (level.getBlockEntity(pos) instanceof GranaryBinBlockEntity bin && !bin.isEmpty()) {
                // 非玩家路径 (指令、爆炸) 拆掉一个有货的囤: 先掉出来, 别让存档里的东西凭空消失。
                for (int slot = 0; slot < bin.getContainerSize(); slot++) {
                    ItemStack stack = bin.getItem(slot);
                    if (!stack.isEmpty()) {
                        Containers.dropItemStack(serverLevel, pos.getX() + 0.5D, pos.getY() + 0.5D,
                                pos.getZ() + 0.5D, stack);
                    }
                }
                bin.clearContent();
            }
            GranarySystem.releaseBin(serverLevel, pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
