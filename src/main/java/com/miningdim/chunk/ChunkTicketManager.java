package com.miningdim.chunk;

import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.world.ForgeChunkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 矿山实例区块强加载生命周期管理 (设计文档 19.1 / R5)。用 Forge {@link ForgeChunkManager} 维护
 * 以玩家所在区块为心的滑动 ticket 窗口, 区分 "需 tick 逻辑区" (ticking=true, 跑刷怪/坍塌/岩浆)
 * 与 "仅加载区" (ticking=false, 玩家可见但无主动逻辑), 避免为整 region force-tick 拖垮 TPS。
 *
 * ticket owner 用每实例稳定的 region 原点 {@link BlockPos} (forceChunk 的 BlockPos owner 重载),
 * 而非玩家 Entity: 玩家断线/换维度时 Entity 失效, 但 region 几何恒定, 用它做 owner 才能在
 * 空置 TTL / 实例销毁时精确按 owner 批量释放本实例全部 ticket, 不误伤相邻实例 (R5 防泄漏)。
 *
 * 线程契约 (D8): 全部 forceChunk 世界写操作只在服务端主线程调用 (由 ChunkSystem 的 ServerTickEvent
 * 与 EntryGateway/ResetSystem 的 server.execute 回调驱动)。本管理器自身状态 (ticket 窗口) 仅主线程读写,
 * 不需并发容器。
 *
 * 撤票必须带上加票时的 ticking 标志 (F031): Forge {@code ForgeChunkManager.forceChunk} 内部按
 * ticking 参数把票分别存入两张彼此独立的表 (TicketTracker 的 chunks / tickingChunks), 撤票同样按
 * ticking 参数选表 —— 以 ticking=true 加的票, 只能以 ticking=true 撤; 传 false 撤会打到另一张空表上,
 * 静默返回 false (连 removeRegionTicket 都不会调), 票本身原样留在 tickingChunks 里。这些票经
 * ForcedChunksSavedData 落盘, 开服 reinstatePersistentChunks 会把它们重新装回内存, 撤错表的泄漏因此
 * 跨重启累积。本类所有撤票调用都必须从 {@link InstanceTickets#forced} 里取出该区块当前实际持有的
 * ticking 标志作为撤票实参, 不能凭撤票发生的场景猜一个常量。
 *
 * 历史落盘票的开服清账 (F031 复核修正 #1/#3): 上面这条纪律只保证"以后不再撤错表", 但已经落盘的历史票
 * (含撤错表 no-op 期间残留的、以及已删除的 GenerationScheduler 生成期间加的整 region 票) 没有任何 Java
 * 侧代码能构造出对应的 owner 去撤 —— {@link #byInstance} 每次开服从空重建, {@code releaseAll}/{@code
 * syncGeometry} 只能撤本会话记过账的票。本管理器的 ticket 窗口在设计上完全由玩家在场驱动
 * (rebuildFromStorage 已把全部实例 playerSet 清空), 故开服瞬间不应存在任何合法持有的历史票; 唯一正确
 * 且时序安全的清理点是 Forge 的 {@link ForgeChunkManager.LoadingValidationCallback}, 在
 * reinstatePersistentChunks 把票重新装回内存 *之前* 拦截。回调注册在 {@code ChunkSystem} 的
 * FMLCommonSetupEvent (modBus 可用, 本类无 modBus 引用), 实际清账逻辑见 {@link #purgeStalePersistentTickets}。
 */
public final class ChunkTicketManager implements IChunkTicketService {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/chunk");

    /**
     * 单实例的 ticket 窗口快照: 记录当前已强加载的区块及其 ticking 标志, 供滑动差量维护与统一释放。
     * key = ChunkPos.toLong(); value = 该区块当前是否 ticking。
     */
    private static final class InstanceTickets {

        final long instanceId;
        /** regionBox/owner 随实例滑动重建时改写 (D3), 故非 final —— 见 {@link #syncGeometry}。 */
        RegionBox regionBox;
        /** owner = region 原点 BlockPos, 每实例唯一且稳定 (区块对齐区域不与他实例重叠, D1)。 */
        BlockPos owner;
        /** 当前持有的强加载区块 -> 是否 ticking。 */
        final Map<Long, Boolean> forced = new HashMap<>();

        InstanceTickets(InstanceState state) {
            this.instanceId = state.instanceId();
            this.regionBox = state.regionBox();
            this.owner = new BlockPos(regionBox.originX(), regionBox.originY(), regionBox.originZ());
        }
    }

    private final ServerLevel miningLevel;
    private final Map<Long, InstanceTickets> byInstance = new HashMap<>();

    public ChunkTicketManager(ServerLevel miningLevel) {
        if (miningLevel == null) {
            throw new IllegalArgumentException("miningLevel must not be null");
        }
        this.miningLevel = miningLevel;
    }

    /**
     * 按一组在场玩家位置刷新某实例的滑动 ticket 窗口 (设计文档 19.1 激活/滑动更新)。
     * 多玩家共享实例时取各玩家窗口并集; 半径外或落在他 region 的区块不纳入 (实例区块不重叠保证窗口
     * 不越界)。计算目标窗口后与当前持有集差量比对: 新进入 add、离开 remove、ticking 标志变更则
     * 先 remove 旧 ticket 再 add 新标志 (ForgeChunkManager 同 (owner, chunk) 的 ticking 不可原地翻转,
     * 必须移除再重加)。
     *
     * @param state           目标实例
     * @param presentPlayers  当前在该实例内的在线玩家 (用其 chunkPos 作窗口圆心)
     */
    @Override
    public void refreshWindow(InstanceState state, Iterable<ServerPlayer> presentPlayers) {
        InstanceTickets t = syncGeometry(state);

        int activeRadius = MiningServices.config().loadRadiusChunks();
        // tick 半径取 load 半径的内圈: 刷怪/坍塌作用半径必须落在 ticking 区块内 (19.1 DECIDED),
        // 故 tickRadius <= activeRadius。无独立配置项时取 activeRadius 与其半值的较大者, 保证 >=1。
        int tickRadius = Math.max(1, activeRadius / 2);

        // 目标窗口: chunk -> 期望 ticking 标志 (在任一玩家 tick 圈内为 true, 否则若在 load 圈内为 false)。
        Map<Long, Boolean> desired = new HashMap<>();
        for (ServerPlayer player : presentPlayers) {
            ChunkPos center = player.chunkPosition();
            for (int dz = -activeRadius; dz <= activeRadius; dz++) {
                for (int dx = -activeRadius; dx <= activeRadius; dx++) {
                    int cx = center.x + dx;
                    int cz = center.z + dz;
                    // 仅纳入落在本实例 region 内的区块, 杜绝窗口溢出到缓冲带或邻接实例 (R5)。
                    if (!chunkInRegion(t.regionBox, cx, cz)) {
                        continue;
                    }
                    boolean wantTicking = Math.abs(dx) <= tickRadius && Math.abs(dz) <= tickRadius;
                    long key = ChunkPos.asLong(cx, cz);
                    Boolean prev = desired.get(key);
                    // 并集取 ticking 较强者: 任一玩家要求 ticking 即 ticking。
                    desired.put(key, prev != null ? (prev || wantTicking) : wantTicking);
                }
            }
        }

        applyDesired(t, desired);
    }

    /**
     * 强制确保给定区块集合在某实例内被 ticking 强加载并立即就绪 (设计文档 14.3 入场前 force-load)。
     * 用于 EntryGateway 传送前对 spawn 周边区块加 ticking ticket; 返回后调用方还须用
     * {@link #areChunksLoaded} 轮询确认 FULL 状态再传送 (防虚空)。本方法只负责申请 ticket。
     *
     * @param state  目标实例
     * @param chunks 需 ticking 强加载的区块 (ChunkPos.asLong 编码)
     */
    @Override
    public void ensureTicking(InstanceState state, Set<Long> chunks) {
        InstanceTickets t = syncGeometry(state);
        for (long key : chunks) {
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            if (!chunkInRegion(t.regionBox, cx, cz)) {
                continue;
            }
            Boolean held = t.forced.get(key);
            if (held != null && held) {
                continue;
            }
            if (held != null) {
                // 已加载但非 ticking: 先撤旧 ticket 再以 ticking 重加。
                ForgeChunkManager.forceChunk(miningLevel, MiningConstants.MODID, t.owner, cx, cz, false, false);
            }
            ForgeChunkManager.forceChunk(miningLevel, MiningConstants.MODID, t.owner, cx, cz, true, true);
            t.forced.put(key, Boolean.TRUE);
        }
    }

    /** 校验给定区块是否已加载且达 FULL 状态 (设计文档 14.3 awaitChunksLoaded 防虚空)。 */
    @Override
    public boolean areChunksLoaded(Set<Long> chunks) {
        for (long key : chunks) {
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            if (!miningLevel.getChunkSource().hasChunk(cx, cz)) {
                return false;
            }
            // getChunkNow 非阻塞取已加载区块; FULL 是可安全落点的最高 ChunkStatus。
            if (miningLevel.getChunkSource().getChunkNow(cx, cz) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * 空置 TTL 到期或实例销毁: 释放某实例持有的全部 ticket (设计文档 19.1 卸载释放 / R5 销毁强制 add=false)。
     * 释放后区块走原版卸载流程。本管理器随之丢弃该实例窗口记录。
     */
    @Override
    public void releaseAll(long instanceId) {
        InstanceTickets t = byInstance.remove(instanceId);
        if (t == null) {
            return;
        }
        int released = t.forced.size();
        for (Map.Entry<Long, Boolean> e : t.forced.entrySet()) {
            int cx = ChunkPos.getX(e.getKey());
            int cz = ChunkPos.getZ(e.getKey());
            boolean wasTicking = Boolean.TRUE.equals(e.getValue());
            ForgeChunkManager.forceChunk(miningLevel, MiningConstants.MODID, t.owner, cx, cz, false, wasTicking);
        }
        LOGGER.debug("[miningdim] released {} ticket(s) for instance {}", released, instanceId);
        t.forced.clear();
    }

    /** 当前是否持有该实例的任何 ticket (供 ChunkSystem 判断空置降级是否已完成释放)。 */
    @Override
    public boolean hasTickets(long instanceId) {
        InstanceTickets t = byInstance.get(instanceId);
        return t != null && !t.forced.isEmpty();
    }

    /**
     * 空置实例降级: 把该实例所有 ticket 的 ticking 标志降为 false (仅加载不 tick),
     * 用于 playerSet 刚空但仍在 TTL 宽限期内 —— 暂停主动逻辑 tick 又不立刻卸载, 便于玩家短暂往返
     * 复用 (设计文档 19.1 空置 TTL 行 / 12.7)。TTL 真正到期后由 ChunkSystem 调 releaseAll 卸载。
     */
    @Override
    public void demoteToLoadOnly(long instanceId) {
        InstanceTickets t = byInstance.get(instanceId);
        if (t == null) {
            return;
        }
        for (Map.Entry<Long, Boolean> e : t.forced.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue())) {
                continue;
            }
            int cx = ChunkPos.getX(e.getKey());
            int cz = ChunkPos.getZ(e.getKey());
            // 持有标志为 true (本 for 已用 Boolean.TRUE.equals 过滤), 撤票必须同样传 ticking=true,
            // 否则打到非 ticking 表上是 no-op, 旧 ticking 票留存, 此方法反而变成净增票 (F031)。
            ForgeChunkManager.forceChunk(miningLevel, MiningConstants.MODID, t.owner, cx, cz, false, true);
            ForgeChunkManager.forceChunk(miningLevel, MiningConstants.MODID, t.owner, cx, cz, true, false);
            e.setValue(Boolean.FALSE);
        }
    }

    /**
     * 开服期一次性历史票清账 (F031 复核修正 #1/#3), 作为 {@link ForgeChunkManager.LoadingValidationCallback}
     * 注册给 Forge (见 ChunkSystem.register 的 FMLCommonSetupEvent)。本方法本身不持有任何 {@link ChunkTicketManager}
     * 实例状态 (静态), 只操作 Forge 传入的快照 {@code ticketHelper}, 与本类构造/{@link #byInstance} 重建的时序
     * 无关, 不存在竞态。无条件撤销该 modId 在本次 level 加载时持有的全部 BlockPos-owner 票 (ticking 与非
     * ticking 一并撤, {@code removeAllTickets} 内部两张表都清): 会话内窗口从空重建、由玩家在场驱动, 开服瞬间
     * 不存在任何"仍需保留"的历史票, 全部撤销后交由 refreshWindow/ensureTicking 在玩家实际进场时按需重新申请,
     * 不会误撤在场需求。本 mod 只以 BlockPos 作 owner (从未用 Entity/UUID 重载), 无需处理 entity 票表。
     */
    static void purgeStalePersistentTickets(ServerLevel level, ForgeChunkManager.TicketHelper ticketHelper) {
        int ownerCount = ticketHelper.getBlockTickets().size();
        if (ownerCount == 0) {
            return;
        }
        for (BlockPos owner : ticketHelper.getBlockTickets().keySet()) {
            ticketHelper.removeAllTickets(owner);
        }
        LOGGER.info("[miningdim] purged {} stale persistent forced-chunk ticket owner(s) on level load"
                + " (F031 leaked-before-restart cleanup)", ownerCount);
    }

    @Override
    public ServerLevel level() {
        return miningLevel;
    }

    @Override
    public Set<Long> chunksAround(BlockPos center, int radiusChunks) {
        return chunkKeysAround(center, radiusChunks);
    }

    // ---- 内部 ----

    /**
     * 取/建实例的 ticket 窗口记录, 并在几何 (regionBox) 随重置滑动改变时重建 (D3)。
     * 旧 owner/旧 regionBox 快照下发出的 ticket 只能由旧 owner 撤销, 几何切换前必须先用旧 owner
     * 逐个撤票并清空 forced, 否则新 region 的区块会被 chunkInRegion 全部过滤掉, 该实例永远拿不到强加载票。
     */
    private InstanceTickets syncGeometry(InstanceState state) {
        InstanceTickets t = byInstance.computeIfAbsent(state.instanceId(), k -> new InstanceTickets(state));
        if (t.regionBox.equals(state.regionBox())) {
            return t;
        }
        for (Map.Entry<Long, Boolean> e : t.forced.entrySet()) {
            int cx = ChunkPos.getX(e.getKey());
            int cz = ChunkPos.getZ(e.getKey());
            boolean wasTicking = Boolean.TRUE.equals(e.getValue());
            ForgeChunkManager.forceChunk(miningLevel, MiningConstants.MODID, t.owner, cx, cz, false, wasTicking);
        }
        t.forced.clear();
        t.regionBox = state.regionBox();
        t.owner = new BlockPos(t.regionBox.originX(), t.regionBox.originY(), t.regionBox.originZ());
        LOGGER.info("[miningdim] instance {} ticket window rebuilt for slid region", state.instanceId());
        return t;
    }

    /** 把当前持有窗口差量收敛到目标窗口。 */
    private void applyDesired(InstanceTickets t, Map<Long, Boolean> desired) {
        // 1) 移除目标窗口外的旧 ticket。
        Set<Long> toRemove = new HashSet<>(t.forced.keySet());
        toRemove.removeAll(desired.keySet());
        for (long key : toRemove) {
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            boolean wasTicking = Boolean.TRUE.equals(t.forced.get(key));
            ForgeChunkManager.forceChunk(miningLevel, MiningConstants.MODID, t.owner, cx, cz, false, wasTicking);
            t.forced.remove(key);
        }
        // 2) 新增或翻转 ticking 标志。
        for (Map.Entry<Long, Boolean> e : desired.entrySet()) {
            long key = e.getKey();
            boolean wantTicking = e.getValue();
            Boolean held = t.forced.get(key);
            if (held != null && held == wantTicking) {
                continue;
            }
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            if (held != null) {
                // 同 chunk ticking 标志变更: 先按旧标志撤票, 再以新标志重加。
                ForgeChunkManager.forceChunk(miningLevel, MiningConstants.MODID, t.owner, cx, cz, false, held);
            }
            ForgeChunkManager.forceChunk(miningLevel, MiningConstants.MODID, t.owner, cx, cz, true, wantTicking);
            t.forced.put(key, wantTicking);
        }
    }

    /** 区块坐标 (整块) 是否完全落在 region 的 XZ 范围内。 */
    private static boolean chunkInRegion(RegionBox box, int chunkX, int chunkZ) {
        int minBlockX = chunkX << 4;
        int minBlockZ = chunkZ << 4;
        int maxBlockX = minBlockX + 15;
        int maxBlockZ = minBlockZ + 15;
        return box.contains(minBlockX, minBlockZ) && box.contains(maxBlockX, maxBlockZ);
    }

    /**
     * 计算实例 spawn 周边需 force-load 的区块集合 (设计文档 14.3, spawn 周边 3x3 起步, 取 load 半径)。
     * 供 EntryGateway 在传送前调用。中心取 region 的 XZ 几何中心所在区块 (实际 spawn 解析后可再 ensureTicking
     * 精确点位; 这里给一个保底的中心窗口确保至少中心区块就绪)。
     */
    static Set<Long> chunkKeysAround(BlockPos center, int radiusChunks) {
        Set<Long> result = new HashSet<>();
        int ccx = center.getX() >> 4;
        int ccz = center.getZ() >> 4;
        for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
            for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
                result.add(ChunkPos.asLong(ccx + dx, ccz + dz));
            }
        }
        return result;
    }
}
