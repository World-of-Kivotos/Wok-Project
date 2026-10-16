package com.miningdim.job.farmer.granary;

import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.farmer.granary.block.GranaryBinBlock;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import net.minecraft.network.chat.Component;
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
        if (!(event.getPlacedBlock().getBlock() instanceof GranaryBinBlock)) {
            return;
        }
        // 假玩家 (机器、Deployer) 一律放不了: 额度不能记在假玩家名下。
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer
                || player.isRemoved()) {
            event.setCanceled(true);
            return;
        }
        GranarySavedData data = GranarySavedData.get(level);
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
        data.claimBin(level.dimension().location(), event.getPos(), player.getUUID(),
                alreadyPlaced + 1);
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

    /** 方块被移除时归还额度 (唯一回收点由 {@link GranaryBinBlock#onRemove} 调)。 */
    public static void releaseBin(ServerLevel level, net.minecraft.core.BlockPos pos) {
        GranarySavedData.get(level).releaseBin(level.dimension().location(), pos);
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
