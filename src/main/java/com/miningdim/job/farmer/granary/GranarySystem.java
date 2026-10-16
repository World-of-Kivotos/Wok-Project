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
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.PistonEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.List;
import java.util.UUID;

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

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("miningdim/granary");

    private GranarySystem() {
    }

    /** 农夫模块装配时调用。 */
    public static void register(IEventBus modBus, IEventBus forgeBus) {
        GranaryBlocks.register(modBus);
        GranaryCommands.register(forgeBus);
        forgeBus.addListener(EventPriority.LOWEST, GranarySystem::onBlockPlaced);
        forgeBus.addListener(GranarySystem::onPlayerLoggedIn);
        // 损坏复查: 方块一变就打脏标记, 由每个 tick 的调度器排队真扫 (规格「发现损坏要快」)。
        forgeBus.addListener(GranarySystem::onBlockBroken);
        forgeBus.addListener(GranarySystem::onExplosion);
        forgeBus.addListener(GranarySystem::onPiston);
        forgeBus.addListener(GranarySystem::onChunkLoad);
        forgeBus.addListener(GranarySystem::onServerStarted);
        forgeBus.addListener(GranarySystem::onServerTick);
    }

    /** 开服清空调度队列 (调度状态不跨存档)。 */
    public static void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        GranaryRecheck.reset();
    }

    /** 每 tick 排队复查一座 (END 阶段: 这一 tick 的方块变动都落定了)。 */
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        GranaryRecheck.tick(event.getServer(), event.getServer().getTickCount());
    }

    /** 方块被挖 (事件在方块真正消失之前发, 同一 tick 的 END 阶段才复查, 所以看到的是挖完之后的样子)。 */
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            GranaryRecheck.markDirty(level, event.getPos());
        }
    }

    /** 被炸: 一次动一片, 按受影响方块的包围盒过一遍账本。 */
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        List<BlockPos> affected = event.getAffectedBlocks();
        if (affected.isEmpty()) {
            double[] center = {event.getExplosion().getPosition().x,
                    event.getExplosion().getPosition().y, event.getExplosion().getPosition().z};
            GranaryRecheck.markDirty(level, BlockPos.containing(center[0], center[1], center[2]));
            return;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : affected) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        GranaryRecheck.markDirtyArea(level, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** 活塞推动: 推的那一串方块在活塞正前方最多 12 格, 按这条线打标记。 */
    public static void onPiston(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos piston = event.getPos();
        BlockPos far = piston.relative(event.getDirection(), 12);
        GranaryRecheck.markDirtyArea(level,
                Math.min(piston.getX(), far.getX()), Math.min(piston.getY(), far.getY()),
                Math.min(piston.getZ(), far.getZ()),
                Math.max(piston.getX(), far.getX()), Math.max(piston.getY(), far.getY()),
                Math.max(piston.getZ(), far.getZ()));
    }

    /** 区块加载: 之前因为「跨到未加载区块」没判的粮仓, 这时候补一次。 */
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            GranaryRecheck.markChunkLoaded(level, event.getChunk().getPos());
        }
    }

    /**
     * 上线提醒 + 宽限锚点 (Spoilage_Granary_DesignSpec「损坏与宽限」: 房主和受影响的囤主上线都会收到提醒)。
     *
     * 两个作用各自都要紧:
     * <ul>
     *   <li><b>锚点</b>: 「出事后第一次上线起 3 天」这条宽限从这一下开始算, 只记第一次 ——
     *       每天上线都刷新的话宽限就成了无限;</li>
     *   <li><b>提醒</b>: 写明坏在哪、宽限还剩多久 (已经过期的照实说「里面的东西在照常腐烂」)。</li>
     * </ul>
     */
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer
                || player.isRemoved() || player.getServer() == null) {
            return;
        }
        ServerLevel overworld = player.getServer().overworld();
        GranarySavedData data = GranarySavedData.get(overworld);
        long nowHour = GranaryClock.nowHour(overworld);
        // 待领粮单: 先把还在停表的那几条结到这一刻, 再清掉烂满 30 天的, 最后提醒还剩几个囤的东西没领。
        long nowMinute = GranaryClock.nowMinute(overworld);
        GranaryPending.settle(data, player.getUUID(), nowHour);
        GranaryPending.PurgeResult purged = GranaryPending.purge(data, player.getUUID(), nowMinute);
        if (purged.changed()) {
            LOGGER.info("granary pending auto-cleared {} rotten item(s) for {} ({} bin(s) freed)",
                    purged.destroyed(), player.getGameProfile().getName(), purged.clearedBins());
        }
        int pendingBins = data.pendingBins(player.getUUID());
        if (pendingBins > 0) {
            List<GranaryPending.Entry> entries = data.pendingOf(player.getUUID());
            GranaryPending.Entry newest = entries.get(entries.size() - 1);
            player.displayClientMessage(Component.translatable("message.miningdim.granary.pending_notice",
                    pendingBins, Component.translatable(newest.reason().translationKey())), false);
        }
        ResourceLocation dimension = overworld.dimension().location();
        for (GranaryRecord record : data.granaries()) {
            boolean damaged = record.status() == GranaryRecord.Status.DAMAGED;
            // 这位玩家在这座粮仓里被停用/受影响的囤: 宽限锚点与提醒都按他本人算。
            int suspended = 0;
            boolean binOwner = false;
            for (GranarySavedData.PosKey key : data.binsOf(record.id())) {
                if (!player.getUUID().equals(data.ownerOf(key.dimension(), key.pos()))) {
                    continue;
                }
                binOwner = true;
                if (data.binState(key.dimension(), key.pos()) == GranaryBinState.STOPPED) {
                    suspended++;
                }
            }
            if (!damaged && suspended == 0) {
                continue;
            }
            if (!binOwner && !player.getUUID().equals(record.owner())) {
                continue;
            }
            if (binOwner) {
                data.recordGraceLogin(record.id(), player.getUUID(), nowHour);
            }
            long anchor = data.graceLogin(record.id(), player.getUUID());
            if (damaged) {
                long remaining = record.damagedAtHour() <= 0L ? 0L
                        : GranaryGrace.remainingHours(nowHour, record.damagedAtHour(), anchor);
                player.displayClientMessage(remaining > 0L
                        ? Component.translatable("message.miningdim.granary.damage_notice", remaining)
                        : Component.translatable("message.miningdim.granary.damage_notice_expired"), false);
                continue;
            }
            // 只是部件撑不住被停用 (粮仓本身没坏): 各自按自己的停用时刻算宽限。
            long suspendedAt = 0L;
            for (GranarySavedData.PosKey key : data.binsOf(record.id())) {
                if (player.getUUID().equals(data.ownerOf(key.dimension(), key.pos()))
                        && data.binState(key.dimension(), key.pos()) == GranaryBinState.STOPPED) {
                    long at = data.binSuspendedAt(key.dimension(), key.pos());
                    suspendedAt = suspendedAt == 0L ? at : Math.min(suspendedAt, at);
                }
            }
            long remaining = suspendedAt <= 0L ? 0L
                    : GranaryGrace.remainingHours(nowHour, suspendedAt, anchor);
            player.displayClientMessage(remaining > 0L
                    ? Component.translatable("message.miningdim.granary.suspend_notice", suspended, remaining)
                    : Component.translatable("message.miningdim.granary.suspend_notice_expired", suspended), false);
        }
    }

    /** 放置裁决 + 归属登记 (LOWEST: 别的监听器都放过之后才轮到这里)。 */
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // 放过来了就算屋子范围变了: 先打脏标记 (粮仓本身之外的一砖一瓦同样要复查)。
        GranaryRecheck.markDirty(level, event.getPos());
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
