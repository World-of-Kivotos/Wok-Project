package com.miningdim.instance;

import com.miningdim.core.Difficulty;
import com.miningdim.core.GenState;
import com.miningdim.core.IInstanceManager;
import com.miningdim.core.InstanceLimitException;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import com.miningdim.core.SeedUtil;
import com.miningdim.persistence.MiningSavedData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 实例生命周期权威实现 (设计文档第十二章 / IInstanceManager)。服务端单例, 生命周期绑定矿山 ServerLevel。
 *
 * 线程纪律 (D8/12.4): 所有分配/回收/计数/GC 都只在主线程执行; 网络/工作线程的请求必须经 server.execute
 * 串行回主线程。本类内部不加锁 —— 单线程串行即正确性边界。allocate 是唯一对外可能从任意线程调用的入口,
 * 故其内部立即 server.execute 跳回主线程再操作。
 *
 * 持久化 (12.5): 实例注册表/计数器/region 位图的权威副本在 MiningSavedData; 本类内存视图与之同步,
 * 任何结构性变更后写回并 setDirty。region 几何分配委托 RegionGrid, 离线生成委托 GenerationScheduler。
 *
 * R1 固定区域模型: 开服时 (rebuildFromStorage 末) 预建恰好三个固定难度实例 (每难度一个, 占
 * RegionGrid.fixedRegionFor 的固定槽位, shared=true, 常驻不 GC)。allocate(player, difficulty) 路由到对应
 * 固定实例 (不再动态新建/复用/背压); regionAt 仍返回包含该点的固定实例。这三个实例稳定存在于 SavedData,
 * 永不进入空实例 GC 或动态回收, 只能被 ResetService 重置。旧的动态分配/共享复用/容量背压机制 (findReusable* /
 * createInstance / backpressureOrQueue / pollQueue / 空实例 GC) 代码保留但本模式下不触发。
 */
public final class InstanceManager implements IInstanceManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/InstanceManager");

    private final MinecraftServer server;
    private final ServerLevel miningLevel;
    private final MiningSavedData savedData;
    private final RegionGrid regionGrid;
    private final GenerationScheduler scheduler;

    /** 内存实例注册表 (= savedData.instances() 的同一引用, 主线程独占)。 */
    private final Map<Long, InstanceState> instances;

    /**
     * R1 三固定难度实例: Difficulty -> instanceId。开服时预建恰好三个 (每难度一个, 占固定槽位 RegionBox,
     * shared=true, 常驻不 GC)。allocate / regionAt 路由到此; 这三个 id 永不进入空实例 GC 或动态回收。
     */
    private final java.util.EnumMap<Difficulty, Long> fixedInstances = new java.util.EnumMap<>(Difficulty.class);

    /**
     * 私有实例归属索引: (ownerKey, difficulty) -> instanceId。组队私有用 teamId 的 UUID 形式作 ownerKey
     * (本期单人私有用 player.uuid; 组队 id 解析待组队子系统接入, 见 resolveOwnerKey 说明)。
     */
    private final Map<OwnerDifficultyKey, Long> privateIndex = new HashMap<>();

    /** 挂起的 allocate future: instanceId -> 等待该实例就绪的请求者 future 列表 (主线程独占)。 */
    private final Map<Long, List<CompletableFuture<InstanceState>>> pendingAllocations = new HashMap<>();

    /**
     * 超 globalCap 且 overflowPolicy=QUEUE 时的等待队列 (12.3, FIFO)。元素含请求者、难度、入队 tick、
     * 待兑现 future。每当一个实例被 GC 回收腾出名额, pollQueue 取队首重试。
     */
    private final Deque<QueuedRequest> allocationQueue = new ArrayDeque<>();

    /** 排队等待上限 (tick); 12.3 queueTtlTicks 默认 1200 (60s)。本期取固定值, 待配置项扩展后改读 config。 */
    private static final long QUEUE_TTL_TICKS = 1200L;

    public InstanceManager(MinecraftServer server, ServerLevel miningLevel) {
        this.server = server;
        this.miningLevel = miningLevel;
        this.savedData = MiningSavedData.get(miningLevel);
        this.instances = savedData.instances();
        this.regionGrid = new RegionGrid();
        this.scheduler = new GenerationScheduler(server, MiningServices.config().maxGenWorkers(), this::onInstanceTerminalState);
    }

    // ---- 启动重建与孤儿清理 (12.8) ----

    /**
     * 服务端启动后 (矿山 ServerLevel 可用时) 从 SavedData 重建内存视图 (12.8)。主线程串行, 玩家未登录, 天然安全。
     * 步骤: 初始化 globalSeed -> 还原 region 位图 -> 逐实例重置在场态/重建索引/交叉校验 -> 清理孤儿。
     */
    public void rebuildFromStorage() {
        // globalSeed 首次确定: 取存档主 seed 与 mod 常量混合 (12.4), 全程不变。
        long worldSeed = miningLevel.getSeed();
        savedData.initGlobalSeedIfAbsent(SeedUtil.deriveSeed(worldSeed, 0L, 0));

        regionGrid.loadOccupancy(savedData.regionOccupancy());

        List<Long> orphansToDestroy = new ArrayList<>();
        for (InstanceState inst : instances.values()) {
            // 重启后玩家尚未登录: playerSet 视为空, refCount 归零, active=false (12.8 步骤 2)。
            inst.playerSet().clear();
            inst.setActive(false);
            inst.setLastEmptyTick(server.getTickCount());

            // region 位图与 instances 交叉校验: 以 instances 为准修正位图 (12.8)。
            RegionBox box = inst.regionBox();
            if (!regionGrid.isOccupied(box)) {
                LOGGER.warn("[miningdim] region for instance {} not marked occupied in bitmap; correcting (instances authoritative)",
                        inst.instanceId());
                regionGrid.markOccupied(box);
            }

            switch (inst.genState()) {
                case GENERATING, RESETTING -> {
                    // 关服时正在生成, 内存态丢失: 空实例直接回收, 否则置 PENDING 重新触发 (12.8)。
                    LOGGER.info("[miningdim] instance {} was {} at shutdown; re-queueing generation",
                            inst.instanceId(), inst.genState());
                    inst.setGenState(GenState.PENDING);
                    scheduler.submit(inst);
                    rebuildPrivateIndex(inst);
                }
                case FAILED -> {
                    LOGGER.warn("[miningdim] instance {} FAILED at shutdown; destroying and freeing region", inst.instanceId());
                    orphansToDestroy.add(inst.instanceId());
                }
                case RECYCLED -> orphansToDestroy.add(inst.instanceId());
                default -> {
                    // PENDING: 重新提交生成; READY/READY_FALLBACK: 体素需重算, 重新提交以重建缓存。
                    inst.setGenState(GenState.PENDING);
                    scheduler.submit(inst);
                    rebuildPrivateIndex(inst);
                }
            }
        }

        for (long id : orphansToDestroy) {
            destroyInstance(id);
        }

        // R1: 保证三固定难度实例存在 (复用既有同区域实例, 缺失则新建)。
        ensureFixedInstances();

        persistRegionBitmap();
        savedData.setDirty();
        LOGGER.info("[miningdim] instance manager rebuilt: {} live instance(s), {} region slot(s) occupied, {} fixed",
                instances.size(), regionGrid.occupiedCount(), fixedInstances.size());
    }

    /**
     * R1: 预建/复用三固定难度实例。每难度占 RegionGrid.fixedRegionFor(d) 的固定槽位, shared=true。
     * 复用规则: 若已有持久实例的 difficulty 与 regionBox 都等于该难度的固定几何, 则认作该固定实例 (重启后复用,
     * 不重复创建); 否则新建。建立 fixedInstances 索引供 allocate 路由。主线程, 玩家未登录, 天然安全。
     */
    private void ensureFixedInstances() {
        for (Difficulty d : Difficulty.values()) {
            RegionBox fixedBox = regionGrid.fixedRegionFor(d);
            InstanceState existing = null;
            for (InstanceState inst : instances.values()) {
                if (inst.difficulty() == d && inst.regionBox().equals(fixedBox) && inst.genState().isAlive()) {
                    existing = inst;
                    break;
                }
            }
            if (existing != null) {
                fixedInstances.put(d, existing.instanceId());
                regionGrid.markOccupied(fixedBox);
                LOGGER.info("[miningdim] reusing fixed instance {} for difficulty {} (region origin={},{})",
                        existing.instanceId(), d, fixedBox.originX(), fixedBox.originZ());
            } else {
                InstanceState created = createFixedInstance(d, fixedBox);
                fixedInstances.put(d, created.instanceId());
            }
        }
    }

    /** 在固定槽位创建一个常驻共享实例 (R1)。与 createInstance 同流程, 但 region 取固定几何而非螺旋 claim。 */
    private InstanceState createFixedInstance(Difficulty difficulty, RegionBox fixedBox) {
        long instanceId = savedData.allocateInstanceId();
        long seed = SeedUtil.deriveSeed(savedData.globalSeed(), instanceId, 0);
        regionGrid.markOccupied(fixedBox);

        InstanceState inst = new InstanceState(instanceId, seed, difficulty, fixedBox,
                null, true, server.getTickCount(), GenState.PENDING);
        instances.put(instanceId, inst);
        persistRegionBitmap();
        savedData.setDirty();

        scheduler.submit(inst);
        LOGGER.info("[miningdim] created fixed instance {} (difficulty={}, region origin={},{})",
                instanceId, difficulty, fixedBox.originX(), fixedBox.originZ());
        return inst;
    }

    /** 某 instanceId 是否为三固定难度实例之一 (常驻不 GC / 不动态回收)。 */
    private boolean isFixedInstance(long instanceId) {
        return fixedInstances.containsValue(instanceId);
    }

    /** 某难度的固定实例 (R1; allocate 路由)。重建后必非空; 早于重建调用返回 null 由调用方处理。 */
    public InstanceState fixedInstanceFor(Difficulty difficulty) {
        Long id = fixedInstances.get(difficulty);
        return id == null ? null : instances.get(id);
    }

    private void rebuildPrivateIndex(InstanceState inst) {
        if (!inst.shared() && inst.ownerKey() != null) {
            privateIndex.put(new OwnerDifficultyKey(inst.ownerKey(), inst.difficulty()), inst.instanceId());
        }
    }

    // ---- 分配 (12.2) ----

    @Override
    public CompletableFuture<InstanceState> allocate(ServerPlayer requester, Difficulty difficulty) {
        CompletableFuture<InstanceState> result = new CompletableFuture<>();
        UUID requesterId = requester.getUUID();
        // 任意线程调用安全: 立即串行回主线程 (12.4)。
        server.execute(() -> {
            try {
                allocateOnMainThread(requesterId, difficulty, result);
            } catch (InstanceLimitException backpressure) {
                // 背压是预期业务结果, 以异常完成 future 交入口层处理 (C9), 不在此吞。
                result.completeExceptionally(backpressure);
            }
        });
        return result;
    }

    /**
     * 分配主逻辑 (主线程)。R1 新模型: 入场只路由到该难度的固定常驻实例, 不再动态新建/复用私有/背压排队。
     * 固定实例若仍在 PENDING/GENERATING, future 由 attachAllocationFuture 登记等生成就绪兑现。
     * 旧的动态分配/共享复用/容量背压机制 (findReusable* / createInstance / backpressureOrQueue / pollQueue)
     * 保留但本模式下不触发 (见类注释)。
     */
    private void allocateOnMainThread(UUID requesterId, Difficulty difficulty,
                                      CompletableFuture<InstanceState> result) {
        InstanceState target = fixedInstanceFor(difficulty);
        if (target == null) {
            // 重建尚未完成时被调用 (不应发生: allocate 经服务端启动后才暴露): 暴露缺陷不掩盖。
            result.completeExceptionally(new IllegalStateException(
                    "fixed instance for difficulty " + difficulty + " not initialized"));
            return;
        }
        attachAllocationFuture(target, result);
    }

    /** 把请求 future 挂到目标实例: 已就绪立即兑现, 否则登记等生成回调 (7.9.4 门控)。 */
    private void attachAllocationFuture(InstanceState target, CompletableFuture<InstanceState> result) {
        if (target.genState().isEnterable()) {
            result.complete(target);
        } else if (target.genState() == GenState.FAILED) {
            result.completeExceptionally(new IllegalStateException(
                    "instance " + target.instanceId() + " generation failed"));
        } else {
            pendingAllocations.computeIfAbsent(target.instanceId(), k -> new ArrayList<>()).add(result);
        }
    }

    /** 私有归属复用: 存在同 owner+difficulty 且 isAlive 的实例则复用 (12.2 allocatePrivate)。 */
    private InstanceState findReusablePrivate(UUID ownerKey, Difficulty difficulty) {
        Long id = privateIndex.get(new OwnerDifficultyKey(ownerKey, difficulty));
        if (id == null) {
            return null;
        }
        InstanceState inst = instances.get(id);
        if (inst == null || !inst.genState().isAlive()) {
            // 索引指向已销毁/死亡实例: 清陈旧索引项, 视为无复用。
            privateIndex.remove(new OwnerDifficultyKey(ownerKey, difficulty));
            return null;
        }
        return inst;
    }

    /** 共享复用: 同 difficulty 共享池中最早创建且未满 (size < shareCap) 且 isAlive 者 (12.2 allocateShared)。 */
    private InstanceState findReusableShared(Difficulty difficulty) {
        int shareCap = MiningServices.config().shareCap();
        InstanceState best = null;
        for (InstanceState inst : instances.values()) {
            if (inst.shared() && inst.difficulty() == difficulty
                    && inst.genState().isAlive() && inst.playerSet().size() < shareCap) {
                if (best == null || inst.createdTick() < best.createdTick()) {
                    best = inst;
                }
            }
        }
        return best;
    }

    /** createInstance 流程 (12.2): 派生 id/seed、claim region、登记 PENDING、提交生成、持久化。 */
    private InstanceState createInstance(UUID ownerKey, Difficulty difficulty, boolean shared) {
        long instanceId = savedData.allocateInstanceId();
        long seed = SeedUtil.deriveSeed(savedData.globalSeed(), instanceId, 0);
        RegionBox box = regionGrid.claimNextFreeRegion();

        InstanceState inst = new InstanceState(instanceId, seed, difficulty, box,
                ownerKey, shared, server.getTickCount(), GenState.PENDING);
        instances.put(instanceId, inst);
        if (!shared && ownerKey != null) {
            privateIndex.put(new OwnerDifficultyKey(ownerKey, difficulty), instanceId);
        }
        persistRegionBitmap();
        savedData.setDirty();

        scheduler.submit(inst);
        LOGGER.info("[miningdim] created instance {} (difficulty={}, shared={}, region origin={},{})",
                instanceId, difficulty, shared, box.originX(), box.originZ());
        return inst;
    }

    /**
     * 解析归属键 (12.2 resolveOwnerKey)。组队 teamId 解析需组队子系统 (14.5) 提供, 本期未接入,
     * 故单人私有直接用 player.uuid。当组队子系统就绪后, 此处改为先查队伍再回退 uuid; 现在不编造队伍 API。
     */
    private UUID resolveOwnerKey(UUID playerId) {
        return playerId;
    }

    /** 超 globalCap: 按 overflowPolicy 入队或立即背压拒绝 (12.3)。 */
    private void backpressureOrQueue(UUID requesterId, Difficulty difficulty,
                                     CompletableFuture<InstanceState> result) {
        if (MiningServices.config().queueOnOverflow()) {
            allocationQueue.addLast(new QueuedRequest(requesterId, difficulty, server.getTickCount(), result));
            LOGGER.info("[miningdim] global cap reached; queued allocation for {} (queue depth {})",
                    requesterId, allocationQueue.size());
        } else {
            throw new InstanceLimitException(InstanceLimitException.Reason.GLOBAL_CAP,
                    "global instance cap (" + MiningServices.config().globalCap() + ") reached");
        }
    }

    /** 名额腾出后唤醒排队请求 (12.3 pollQueue): 清过期请求, 取队首重试一次分配。主线程。 */
    private void pollQueue() {
        long now = server.getTickCount();
        // 先清过期请求 (queueTtlTicks), 以 InstanceLimitException(QUEUE_TIMEOUT) 异常完成。
        while (!allocationQueue.isEmpty()) {
            QueuedRequest head = allocationQueue.peekFirst();
            if (now - head.enqueueTick() >= QUEUE_TTL_TICKS) {
                allocationQueue.pollFirst();
                head.result().completeExceptionally(new InstanceLimitException(
                        InstanceLimitException.Reason.QUEUE_TIMEOUT, "allocation queue wait timed out"));
            } else {
                break;
            }
        }
        if (allocationQueue.isEmpty() || instances.size() >= MiningServices.config().globalCap()) {
            return;
        }
        QueuedRequest req = allocationQueue.pollFirst();
        try {
            allocateOnMainThread(req.requesterId(), req.difficulty(), req.result());
        } catch (InstanceLimitException backpressure) {
            req.result().completeExceptionally(backpressure);
        }
    }

    // ---- 生成终态回调 (GenerationScheduler -> 此处, 主线程) ----

    /** 实例进入 READY/READY_FALLBACK/FAILED 时兑现/异常完成挂起的 allocate future (7.9.4)。 */
    private void onInstanceTerminalState(InstanceState inst) {
        List<CompletableFuture<InstanceState>> waiters = pendingAllocations.remove(inst.instanceId());
        if (waiters == null) {
            return;
        }
        if (inst.genState().isEnterable()) {
            for (CompletableFuture<InstanceState> w : waiters) {
                w.complete(inst);
            }
        } else {
            IllegalStateException ex = new IllegalStateException(
                    "instance " + inst.instanceId() + " generation failed (" + inst.genState() + ")");
            for (CompletableFuture<InstanceState> w : waiters) {
                w.completeExceptionally(ex);
            }
        }
    }

    // ---- 查询 ----

    @Override
    public Optional<InstanceState> byId(long instanceId) {
        return Optional.ofNullable(instances.get(instanceId));
    }

    @Override
    public InstanceState regionAt(int worldX, int worldZ) {
        // 热路径: 线性扫描实例数 <= globalCap (默认 32), 远小于按坐标反算网格槽再查表的复杂度;
        // 缓冲带/region 外返回 null, 调用方据此填实心 (4.2)。
        for (InstanceState inst : instances.values()) {
            if (inst.regionBox().contains(worldX, worldZ)) {
                return inst;
            }
        }
        return null;
    }

    @Override
    public int activeInstanceCount() {
        return instances.size();
    }

    @Override
    public Collection<InstanceState> snapshot() {
        return Collections.unmodifiableCollection(new ArrayList<>(instances.values()));
    }

    @Override
    public void forEach(Consumer<InstanceState> action) {
        for (InstanceState inst : new ArrayList<>(instances.values())) {
            action.accept(inst);
        }
    }

    @Override
    public long nextSeedFor(long instanceId) {
        return SeedUtil.deriveSeed(savedData.globalSeed(), instanceId, 0);
    }

    // ---- 引用计数与离开路径 (12.6) ----

    @Override
    public void onPlayerEnter(long instanceId, ServerPlayer player) {
        InstanceState inst = instances.get(instanceId);
        if (inst == null) {
            LOGGER.warn("[miningdim] onPlayerEnter for unknown instance {} by {}", instanceId, player.getGameProfile().getName());
            return;
        }
        inst.playerSet().add(player.getUUID());
        inst.setActive(true);
        inst.setLastEmptyTick(-1L);
        savedData.setDirty();
    }

    @Override
    public void onPlayerLeave(long instanceId, ServerPlayer player) {
        InstanceState inst = instances.get(instanceId);
        if (inst == null) {
            return;
        }
        inst.playerSet().remove(player.getUUID());
        savedData.setDirty();
        if (inst.playerSet().isEmpty()) {
            inst.setActive(false);                      // 暂停 tick (12.7)
            // R1: 固定实例常驻不 GC, 不打 lastEmptyTick (保持 -1 使 gcScan 永不命中), 但仍释放强加载省内存。
            if (!isFixedInstance(instanceId)) {
                inst.setLastEmptyTick(server.getTickCount());
            }
            scheduler.release(inst);                     // 取消强加载票, 允许区块卸载 (12.7)
            // 不立即销毁: 进入 emptyTtl 宽限期 (12.6), 由 gcScan 到期处理。
        }
        pollQueue();                                     // 腾出名额, 唤醒排队 (12.3)
    }

    @Override
    public void release(long instanceId) {
        // R1: 固定难度实例常驻, 永不销毁/释放 region (只能被重置)。误调用记警告并忽略, 不静默静默销毁。
        if (isFixedInstance(instanceId)) {
            LOGGER.warn("[miningdim] release() called on fixed instance {}; ignored (fixed regions are resident)", instanceId);
            return;
        }
        destroyInstance(instanceId);
        pollQueue();
    }

    @Override
    public int cancelQueuedChunkLoads(long instanceId) {
        return scheduler.cancelQueuedLoads(instanceId);
    }

    // ---- 空实例 GC (12.6) ----

    /**
     * GC 扫描 (12.6): 对 refCount==0 且超 emptyTtl 的实例销毁回收。由 InstanceSystem 每 gcScanInterval tick
     * 在维度 tick 末驱动。主线程。
     */
    public void gcScan() {
        long now = server.getTickCount();
        long emptyTtl = secondsToTicks(MiningServices.config().emptyInstanceTtlSeconds());
        List<Long> toDestroy = new ArrayList<>();
        for (InstanceState inst : instances.values()) {
            // R1: 固定难度实例常驻, 跳过 GC (lastEmptyTick 恒 -1 已使其不命中, 此处再加显式守卫双保险)。
            if (isFixedInstance(inst.instanceId())) {
                continue;
            }
            if (inst.playerSet().isEmpty()
                    && inst.lastEmptyTick() >= 0
                    && now - inst.lastEmptyTick() >= emptyTtl) {
                toDestroy.add(inst.instanceId());
            }
        }
        for (long id : toDestroy) {
            InstanceState inst = instances.get(id);
            // 二次确认仍空 (防 TTL 内有人重新进入的边界态, 12.6 步骤 1)。
            if (inst != null && inst.playerSet().isEmpty()) {
                destroyInstance(id);
            }
        }
        if (!toDestroy.isEmpty()) {
            pollQueue();
        }
    }

    /**
     * 销毁实例 (12.6 步骤 2-4): 取消强加载/清体素缓存 -> free region -> 移注册表与索引 -> 标 RECYCLED -> 持久化。
     * 文件级区块删除走重置子系统的删除路径 (本期未接入 ResetService 时仅释放强加载与逻辑回收;
     * 物理区块文件清理由 ResetService/GC 重置接入后补, 见 12.6 步骤 2 与第十三章)。instanceId 不回收复用。
     */
    private void destroyInstance(long instanceId) {
        InstanceState inst = instances.get(instanceId);
        if (inst == null) {
            return;
        }
        inst.setGenState(GenState.RECYCLED);
        scheduler.release(inst);

        regionGrid.free(inst.regionBox());
        instances.remove(instanceId);
        if (!inst.shared() && inst.ownerKey() != null) {
            privateIndex.remove(new OwnerDifficultyKey(inst.ownerKey(), inst.difficulty()));
        }
        // 任何仍挂在此实例上的 allocate future 以失败完成, 不静默丢弃 (C9)。
        List<CompletableFuture<InstanceState>> waiters = pendingAllocations.remove(instanceId);
        if (waiters != null) {
            IllegalStateException ex = new IllegalStateException("instance " + instanceId + " destroyed before ready");
            for (CompletableFuture<InstanceState> w : waiters) {
                w.completeExceptionally(ex);
            }
        }
        persistRegionBitmap();
        savedData.setDirty();
        LOGGER.info("[miningdim] destroyed instance {} (region freed, id not reused)", instanceId);
    }

    // ---- tick 驱动入口 (InstanceSystem 调用) ----

    /** 维度 tick 末: 分帧消费区块强加载队列 (7.9)。 */
    public void tickGeneration() {
        scheduler.tickChunkLoads();
    }

    /** 服务端停止: 关闭线程池, 落盘最终态 (ServerStoppingEvent)。 */
    public void shutdown() {
        scheduler.shutdown();
        savedData.setDirty();
    }

    // ---- 内部工具 ----

    private void persistRegionBitmap() {
        savedData.setRegionOccupancy(regionGrid.saveOccupancy());
    }

    private static long secondsToTicks(int seconds) {
        return (long) seconds * 20L;
    }

    /** 私有归属索引键 (ownerKey + difficulty)。record 提供值语义 equals/hashCode。 */
    private record OwnerDifficultyKey(UUID ownerKey, Difficulty difficulty) {
    }

    /** 排队中的分配请求 (12.3)。 */
    private record QueuedRequest(UUID requesterId, Difficulty difficulty, long enqueueTick,
                                 CompletableFuture<InstanceState> result) {
    }
}
