package com.miningdim.job.farmer.granary.block;

import com.miningdim.job.farmer.granary.GranaryAccess;
import com.miningdim.job.farmer.granary.GranaryClock;
import com.miningdim.job.farmer.granary.GranaryPending;
import com.miningdim.job.farmer.granary.GranaryRecord;
import com.miningdim.job.farmer.granary.GranarySavedData;
import com.miningdim.job.farmer.granary.GranarySystem;
import com.miningdim.job.farmer.granary.GranaryTransfer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

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

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/granary");

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

    /**
     * 右键开这个囤自己的 27 格界面 (Spoilage_Granary_DesignSpec「界面」)。
     *
     * 开门三判: 假玩家不给开; 名单上没他也没权限就不给开 (房主一样, 反漏洞 #68); OP 能开但记一行日志
     * (规格权限表里 OP 那一栏写的是「能 (记日志)」)。开之前先把账结到这一刻 —— 打开界面看到的就是
     * 结算后的数, 与「菜单开着每到整点先结算」是同一条规矩。
     */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer) || player instanceof FakePlayer) {
            return InteractionResult.CONSUME;
        }
        if (!(level.getBlockEntity(pos) instanceof GranaryBinBlockEntity bin)) {
            return InteractionResult.CONSUME;
        }
        UUID owner = bin.owner();
        boolean allowed = GranaryAccess.mayStore(owner, bin.access(), serverPlayer.getUUID())
                || GranaryAccess.mayTake(owner, bin.access(), serverPlayer.getUUID());
        if (!allowed && !serverPlayer.hasPermissions(2)) {
            serverPlayer.displayClientMessage(Component.translatable(
                    "message.miningdim.granary.bin_not_allowed"), true);
            return InteractionResult.CONSUME;
        }
        if (!allowed) {
            // OP 打开别人的囤: 记下来 (运营审计要看得到谁动过谁的东西)。
            LOGGER.info("granary bin at {} in {} opened by operator {} (owner {})",
                    pos, level.dimension().location(), serverPlayer.getGameProfile().getName(), owner);
        }
        bin.settleAll(GranaryClock.nowHour(level.getServer().overworld()));
        NetworkHooks.openScreen(serverPlayer, bin, buffer -> buffer.writeBlockPos(pos));
        return InteractionResult.CONSUME;
    }

    /** 拆囤的三条前置: 囤主或 OP; 房主可以拆本房间里**未入册**的空囤; 必须先把东西取空。
     * 返回 false 即取消这次破坏。
     */
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
                                       boolean willHarvest, FluidState fluid) {
        if (level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof GranaryBinBlockEntity bin) {
            boolean owner = bin.owner() == null || bin.owner().equals(player.getUUID());
            boolean op = player.hasPermissions(2);
            if (!owner && !op && isUnenrolledBinOfMyGranary(serverLevel, pos, player)) {
                // 房主拆的是别人**未入册**的空囤: 囤进放置者的待领粮单, 房主拿不走 (规格「谁能拆」)。
                // 方块在这里自己拿掉 (搬运那一步连方块一起处理), 所以告诉原版别再走一遍。
                GranaryTransfer.takeDown(serverLevel, pos, GranaryPending.Reason.BROKEN,
                        GranaryClock.nowHour(serverLevel));
                player.displayClientMessage(Component.translatable("message.miningdim.granary.bin_sent_away"), true);
                return false;
            }
            if (!owner && !op) {
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

    /** 房主能拆本房间里未入册的囤 (规格「谁能拆」: 房主能拆本房间里未入册的空囤)。 */
    private static boolean isUnenrolledBinOfMyGranary(ServerLevel level, BlockPos pos, Player player) {
        GranarySavedData data = GranarySavedData.get(level);
        ResourceLocation dimension = level.dimension().location();
        long gid = data.granaryOfBin(dimension, pos);
        if (gid == 0L) {
            return false;
        }
        GranaryRecord record = data.granary(gid);
        if (record == null || !player.getUUID().equals(record.owner())) {
            return false;
        }
        return !record.isMember(data.ownerOf(dimension, pos));
    }

    /**
     * 方块移除的汇合点: 里面还有东西就搬进囤主的待领粮单, 然后归还额度。
     *
     * 判据与耕地一致 —— 只有方块真正被换成另一种方块时才回收, 同方块状态翻转不误扣。
     * **不要**跳过 {@code restoringBlockSnapshots}: 被后续监听器取消的放置本来就要在这里把额度还回去。
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            if (level.getBlockEntity(pos) instanceof GranaryBinBlockEntity bin && !bin.isEmpty()) {
                // 非玩家路径 (指令、爆炸、活塞) 拆掉一个有货的囤: 东西从不掉在地上, 只进囤主的待领粮单
                // (规格「有货的囤被移除」—— 拆的人拿不走)。没有归属记录时才退回原地掉落。
                UUID owner = GranaryTransfer.absorb(serverLevel, pos, GranaryPending.Reason.BROKEN,
                        GranaryClock.nowHour(serverLevel));
                if (owner == null) {
                    for (int slot = 0; slot < bin.getContainerSize(); slot++) {
                        ItemStack stack = bin.getItem(slot);
                        if (!stack.isEmpty()) {
                            Containers.dropItemStack(serverLevel, pos.getX() + 0.5D, pos.getY() + 0.5D,
                                    pos.getZ() + 0.5D, stack);
                        }
                    }
                    bin.clearContent();
                }
            }
            GranarySystem.releaseBin(serverLevel, pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
