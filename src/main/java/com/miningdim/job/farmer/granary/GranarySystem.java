package com.miningdim.job.farmer.granary;

import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.farmer.granary.block.GranaryBinBlock;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.level.BlockEvent;

import java.util.UUID;

/** 粮仓方块放置时登记归属和额度，移除时释放登记。 */
public final class GranarySystem {
    private GranarySystem() {
    }

    /** 放置裁决 + 归属登记 (LOWEST: 别的监听器都放过之后才轮到这里)。 */
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        boolean isBin = event.getPlacedBlock().getBlock() instanceof GranaryBinBlock;
        boolean isCenter = event.getPlacedBlock().getBlock()
                instanceof com.miningdim.job.farmer.granary.block.GranaryCenterBlock;
        if (!isBin && !isCenter) {
            return;
        }
        // 假玩家 (机器、Deployer) 一律放不了: 额度不能记在假玩家名下。
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer
                || player.isRemoved()) {
            event.setCanceled(true);
            return;
        }
        GranarySavedData data = GranarySavedData.get(level);
        if (isCenter) {
            int centers = data.centerCount(player.getUUID());
            if (centers >= GranaryConfig.maxCentersPerPlayer()) {
                event.setCanceled(true);
                player.displayClientMessage(Component.translatable(
                        "message.miningdim.granary.center_cap_reached",
                        GranaryConfig.maxCentersPerPlayer()), true);
                return;
            }
            // 放下账台就是「开一座粮仓」: 先开号并落一条未验收的登记项, 档位等验收那一刻再锁。
            ResourceLocation dimension = level.dimension().location();
            long gid = data.nextGranaryId();
            if (data.claimCenter(dimension, event.getPos(), player.getUUID(), gid)) {
                data.putGranary(GranaryRecord.unaccepted(gid, player.getUUID()));
            }
            return;
        }
        int farmerLevel = JobServices.jobService().level(player, JobId.FARMER);
        int alreadyPlaced = data.binCount(player.getUUID());
        GranaryBinGuard.PlaceResult result =
                GranaryBinGuard.checkPlacement(farmerLevel, alreadyPlaced, pendingClaimBins(player));
        if (result == GranaryBinGuard.PlaceResult.REJECT_CAP_REACHED) {
            event.setCanceled(true);
            player.displayClientMessage(Component.translatable(
                    "message.miningdim.granary.bin_cap_reached",
                    GranaryBinGuard.capForLevel(farmerLevel), farmerLevel), true);
            return;
        }
        // 入册序号是**全服单调递增**的: 启用顺序按它排, 合建时不同人的囤才有同一条先后线。
        data.claimBin(level.dimension().location(), event.getPos(), player.getUUID(), data.nextSeq());
    }

    /**
     * 把归属写给方块实体。{@code setPlacedBy} 与放置事件的先后由原版与 Forge 决定, 所以这里做成幂等的:
     * 已经登记过就不再改主人, 免得给后来者覆盖。
     */
    public static void bindOwner(ServerLevel level, net.minecraft.core.BlockPos pos,
                                 GranaryBinBlockEntity bin, ServerPlayer player) {
        if (bin.owner() != null) {
            return;
        }
        GranarySavedData data = GranarySavedData.get(level);
        bin.assignOwner(player.getUUID(), data.binCount(player.getUUID()));
        // 新放的囤套用玩家的默认开放名单 (规格「用 /granary trust 设一份默认名单, 新放的囤自动套用」)。
        bin.setAccess(data.trustDefault(player.getUUID()));
    }

    /**
     * 账台归属 (谁放的账台谁就是房主)。
     *
     * 粮仓编号在放置事件里已经开好 (账本是权威), 这里只把它**镜像**到方块实体上给界面用。
     */
    public static void bindCenterOwner(ServerLevel level, net.minecraft.core.BlockPos pos,
                                       com.miningdim.job.farmer.granary.block.GranaryCenterBlockEntity center,
                                       ServerPlayer player) {
        if (center.owner() != null) {
            return;
        }
        long gid = GranarySavedData.get(level).centerGranary(level.dimension().location(), pos);
        center.assignOwner(player.getUUID(), gid, level.getGameTime());
    }

    /** 方块被移除时归还额度 (唯一回收点由方块的 onRemove 调)。 */
    public static void releaseBin(ServerLevel level, net.minecraft.core.BlockPos pos) {
        GranarySavedData.get(level).releaseBin(level.dimension().location(), pos);
    }

    /**
     * 账台被移除时归还账台额度, 并**解散这座粮仓** (登记项丢掉、里面囤的连线与启用状态一并清掉)。
     *
     * 解散走账本而不是方块实体: 爆炸、{@code /setblock} 这些路径上不保证还读得到方块实体,
     * 而账本总是知道这座账台开的是哪座粮仓。
     */
    public static void releaseCenter(ServerLevel level, net.minecraft.core.BlockPos pos) {
        GranarySavedData data = GranarySavedData.get(level);
        long gid = data.releaseCenter(level.dimension().location(), pos);
        if (gid != 0L) {
            data.removeGranary(gid);
        }
    }

    /** 某人当前占用的储粮囤数 (界面与提示用)。 */
    public static int binCount(ServerLevel level, java.util.UUID owner) {
        return GranarySavedData.get(level).binCount(owner);
    }

    /**
     * 这座粮仓里还有**别人**的囤吗 (拆账台的前置)。
     *
     * 两处都要看: 账本上连着的囤, 以及**屋里实际摆着的囤** —— 刚放下、还没等到下一次复查的囤
     * 还没连线, 只看账本会让「还有别人的囤」这条门在那一小段时间里失效 (拆账台是不可逆动作, 不能靠运气)。
     */
    public static boolean otherMembersKeepBins(ServerLevel level, BlockPos centerPos, UUID requester) {
        GranarySavedData data = GranarySavedData.get(level);
        ResourceLocation dimension = level.dimension().location();
        long gid = data.centerGranary(dimension, centerPos);
        if (gid == 0L) {
            return false;
        }
        for (GranarySavedData.PosKey key : data.binsOf(gid)) {
            if (isOtherOwner(data, key, requester)) {
                return true;
            }
        }
        for (BlockPos bin : GranaryStructureScanner.scan(level, centerPos).binPositions()) {
            UUID owner = data.ownerOf(dimension, bin);
            if (owner != null && !owner.equals(requester)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOtherOwner(GranarySavedData data, GranarySavedData.PosKey key, UUID requester) {
        UUID owner = data.ownerOf(key.dimension(), key.pos());
        return owner != null && !owner.equals(requester);
    }

    /** 这座粮仓的编号 (0 = 这座账台还没开号)。 */
    public static long granaryAt(ServerLevel level, BlockPos centerPos) {
        return GranarySavedData.get(level).centerGranary(level.dimension().location(), centerPos);
    }

    private static int pendingClaimBins(ServerPlayer player) {
        // 待领粮单占的额度: 每 27 格 (一个囤) 占 1 个, 领完才归还 —— 反漏洞 #65 的关键一条。
        return GranarySavedData.get(player.getServer().overworld()).pendingBins(player.getUUID());
    }
}
