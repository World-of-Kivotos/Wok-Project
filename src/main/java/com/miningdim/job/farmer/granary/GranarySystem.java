package com.miningdim.job.farmer.granary;

import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.farmer.granary.block.GranaryBinBlock;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * 粮仓子系统的运行期装配 (Spoilage_Granary_DesignSpec「模块落位」: 粮仓放在 wok-job-farmer 的
 * {@code job/farmer/granary} 下)。
 *
 * 由 {@link com.miningdim.job.farmer.FarmerModule} 在 register 内调用 —— 一个模块只有一个公开装配入口,
 * 粮仓不再单独占一个子系统。
 *
 * 本片落的规矩 (额度与归属):
 * <ul>
 *   <li>放置监听用 {@link EventPriority#LOWEST}: 等别的监听器都不取消了再登记, 否则被领地插件取消的放置
 *       会白占一个额度 (反漏洞 #58);</li>
 *   <li>显式拒绝假玩家: Forge 的假玩家也是 {@code ServerPlayer}, 光靠 {@code instanceof} 拦不住
 *       (反漏洞 #69);</li>
 *   <li>等级用职业门面读 (农夫经验是包级私有, 卖麦服务走的就是这条路); 用仓与结算不看任何人的等级;</li>
 *   <li>额度只减不增口径: 读等级前先判断玩家没有被移除 (反漏洞 #64)。</li>
 * </ul>
 */
public final class GranarySystem {

    private GranarySystem() {
    }

    /** 农夫模块装配时调用。 */
    public static void register(IEventBus modBus, IEventBus forgeBus) {
        GranaryBlocks.register(modBus);
        forgeBus.addListener(EventPriority.LOWEST, GranarySystem::onBlockPlaced);
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

    private static int pendingClaimBins(ServerPlayer player) {
        // 待领粮单占用的额度随「退出 / 打包 / 被移出」那一批加入; 现在没有这条路径, 一律 0。
        return 0;
    }
}
