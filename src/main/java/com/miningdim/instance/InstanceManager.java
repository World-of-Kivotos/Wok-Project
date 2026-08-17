package com.miningdim.instance;

import com.miningdim.core.Difficulty;
import com.miningdim.core.GenState;
import com.miningdim.core.IInstanceManager;
import com.miningdim.core.InstanceLimitException;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import com.miningdim.core.RegionLayout;
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
 * 任何结构性变更后写回并 setDirty。region 几何分配委托 RegionGrid; 维度走 minecraft:noise 按需生成
 * (F021/F032: 离线体素管线已下线), 实例登记即置 GenState.READY, 不再有独立的生成调度阶段。
 *
 * R1 固定区域模型: 开服时 (rebuildFromStorage 末) 预建恰好三个固定难度实例 (每难度一个, shared=true,
 * 常驻不 GC)。allocate(player, difficulty) 路由到对应固定实例 (不再动态新建/复用/背压); regionAt 仍返回
 * 包含该点的固定实例。这三个实例稳定存在于 SavedData, 永不进入空实例 GC 或动态回收, 只能被 ResetService 重置。
 * 旧的动态分配/共享复用/容量背压机制 (findReusable* / createInstance / backpressureOrQueue / pollQueue /
 * 空实例 GC) 代码保留但本模式下不触发。
 *
 * D3 滑动重置: 三块固定 region 在每次重置时会经 slideRegion 整体滑到一块从未生成过的新坐标 (由
 * MiningSavedData 的世界 X 游标单向推进保证不复用), 旧坐标的区块数据留在磁盘上不再被访问 (旧 .mca
 * 的回收是已知遗留, 不在本类处理)。故固定实例只能靠持久 fixedInstanceId 认领, 不能再靠编译期几何比对。
 */
public final class InstanceManager implements IInstanceManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/InstanceManager");

    private final MinecraftServer server;
    private final ServerLevel miningLevel;
    private final MiningSavedData savedData;
    private final RegionGrid regionGrid;

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
    }

    // ---- 启动重建与孤儿清理 (12.8) ----

    /**
     * 服务端启动后 (矿山 ServerLevel 可用时) 从 SavedData 重建内存视图 (12.8)。主线程串行, 玩家未登录, 天然安全。
     * 步骤: 初始化 globalSeed -> 还原 region 位图 -> 逐实例重置在场态/重建索引/交叉校验 -> 清理孤儿。
     */
    public void rebuildFromStorage() {
        // F088 几何自检: dimension_type/mining.json 与 MiningConstants 已脱钩, 早年不对齐时新 region 的 Y
        // 会落到世界外。用 LevelHeightAccessor 的方法而非 dimensionType() 上的 record 访问器 (少一层版本风险)。
        if (miningLevel.getHeight() != MiningConstants.REGION_HEIGHT
                || miningLevel.getMinBuildHeight() != MiningConstants.REGION_MIN_Y) {
            throw new IllegalStateException("mining dimension geometry mismatch: level height="
                    + miningLevel.getHeight() + " minBuildHeight=" + miningLevel.getMinBuildHeight()
                    + " but MiningConstants.REGION_HEIGHT=" + MiningConstants.REGION_HEIGHT
                    + " REGION_MIN_Y=" + MiningConstants.REGION_MIN_Y
                    + " -- data/miningdim/dimension_type/mining.json 与 MiningConstants 已脱钩");
        }

        // globalSeed 首次确定: 取存档主 seed 与 mod 常量混合 (12.4), 全程不变。
        long worldSeed = miningLevel.getSeed();
        savedData.initGlobalSeedIfAbsent(SeedUtil.deriveSeed(worldSeed, 0L, 0));

        regionGrid.loadOccupancy(savedData.regionOccupancy());

        // 三固定实例的 id 先收集成集合: 占用位图交叉校验只对非固定实例执行 (固定实例的滑动坐标不进位图,
        // 见 RegionGrid 类注释 "滑动 region" 段)。
        java.util.Set<Long> fixedIds = new java.util.HashSet<>();
        for (Difficulty d : Difficulty.values()) {
            savedData.fixedInstanceId(d).ifPresent(fixedIds::add);
        }

        List<Long> orphansToDestroy = new ArrayList<>();
        for (InstanceState inst : instances.values()) {
            // 重启后玩家尚未登录: playerSet 视为空, refCount 归零, active=false (12.8 步骤 2)。
            inst.playerSet().clear();
            inst.setActive(false);
            inst.setLastEmptyTick(server.getTickCount());

            // region 位图与 instances 交叉校验: 以 instances 为准修正位图 (12.8)。固定/滑动实例不参与
            // (它们的滑动坐标从不写入位图, 见 RegionGrid 类注释 "滑动 region" 段)。用 isAligned 而非只
            // 靠 fixedIds 判断: 存档升级窗口内 (fixedInstance_* NBT 键尚未写入前) fixedIds 为空,
            // 若仅凭 fixedIds 会把老几何误当动态实例强行 markOccupied/isOccupied, 一旦老几何与当前
            // config 派生 stride 不对齐 (F063 config 默认值变化后的既有存档) 就直接抛 IAE 崩服
            // (分支复核 finding #1/#5/#8)。非网格成员一律跳过, 不强行对齐。
            RegionBox box = inst.regionBox();
            boolean gridMember = !fixedIds.contains(inst.instanceId()) && regionGrid.isAligned(box);
            if (gridMember && !regionGrid.isOccupied(box)) {
                LOGGER.warn("[miningdim] region for instance {} not marked occupied in bitmap; correcting (instances authoritative)",
                        inst.instanceId());
                regionGrid.markOccupied(box);
            }

            switch (inst.genState()) {
                case GENERATING, RESETTING -> {
                    // 离线生成已下线 (F021/F032): 维度走 minecraft:noise 按需生成, 关服时卡在
                    // GENERATING/RESETTING 的活实例直接归一到 READY, 不再有生成阶段可重新触发。
                    LOGGER.info("[miningdim] instance {} was {} at shutdown; offline generation retired,"
                            + " directly settling to READY", inst.instanceId(), inst.genState());
                    inst.setGenState(GenState.READY);
                    rebuildPrivateIndex(inst);
                }
                case FAILED -> {
                    LOGGER.warn("[miningdim] instance {} FAILED at shutdown; destroying and freeing region", inst.instanceId());
                    orphansToDestroy.add(inst.instanceId());
                }
                case RECYCLED -> orphansToDestroy.add(inst.instanceId());
                default -> {
                    // PENDING/READY/READY_FALLBACK: 离线生成已下线, 一律归一到 READY (noise 维度按需生成,
                    // 无需重建体素缓存)。
                    inst.setGenState(GenState.READY);
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
     * R1: 预建/复用三固定难度实例。每难度占一个常驻槽位, shared=true。
     *
     * 认领顺序 (F088/D3: region 会滑动, 原点不再是稳定锚点, 持久 id 才是):
     *   1) savedData.fixedInstanceId(d) 有值且该 id 在 instances 里且 isAlive -> 直接认领 (重启常规路径);
     *   2) 否则按旧规则做一次性迁移认领 (difficulty 相同 + isAlive + regionBox 的 XZ 原点等于历史硬编码
     *      几何 MiningConstants.REGION_STRIDE_X/Z 算出的原点, 不是当前 config 派生的 regionGrid.fixedRegionFor(d);
     *      只比 XZ, 不比 Y/size, 因为 F088 把 sizeY 归一到 192; 详见 claimFixedByLegacyGeometry 方法注释);
     *   3) 都不中才新建 —— 新存档 (!hasRegionFrontier) 用初始三格固定几何, 老存档 (hasRegionFrontier)
     *      必须从 frontier 游标取一块从未生成过的坐标, 绝不能落回可能已生成过地形的老坐标。
     * 三块都就位后把当前真实 regionBox 写入 RegionLayout, 供 MiningBiomeSource 判归属。主线程, 玩家未登录, 天然安全。
     */
    private void ensureFixedInstances() {
        java.util.EnumMap<Difficulty, RegionBox> resolvedBoxes = new java.util.EnumMap<>(Difficulty.class);

        for (Difficulty d : Difficulty.values()) {
            InstanceState claimed = claimFixedById(d);
            if (claimed == null) {
                claimed = claimFixedByLegacyGeometry(d);
            }
            if (claimed != null) {
                fixedInstances.put(d, claimed.instanceId());
                savedData.setFixedInstanceId(d, claimed.instanceId());
                resolvedBoxes.put(d, claimed.regionBox());
                LOGGER.info("[miningdim] claimed fixed instance {} for difficulty {} (region origin={},{})",
                        claimed.instanceId(), d, claimed.regionBox().originX(), claimed.regionBox().originZ());
                continue;
            }

            RegionBox box = savedData.hasRegionFrontier()
                    ? regionGrid.regionAtOrigin(
                            savedData.allocateRegionOriginX(regionGrid.sizeX(), MiningConstants.SLIDE_SEPARATION_BLOCKS),
                            MiningConstants.REGION_ORIGIN_Z)
                    : regionGrid.fixedRegionFor(d);
            InstanceState created = createFixedInstance(d, box);
            fixedInstances.put(d, created.instanceId());
            savedData.setFixedInstanceId(d, created.instanceId());
            resolvedBoxes.put(d, box);
        }

        if (!savedData.hasRegionFrontier()) {
            int frontierX = resolvedBoxes.values().stream()
                    .mapToInt(box -> box.originX() + box.sizeX())
                    .max().orElseThrow()
                    + MiningConstants.SLIDE_SEPARATION_BLOCKS;
            savedData.initRegionFrontierIfAbsent(frontierX);
        }

        RegionLayout.set(new RegionLayout.Snapshot(
                resolvedBoxes.get(Difficulty.EASY), resolvedBoxes.get(Difficulty.MEDIUM), resolvedBoxes.get(Difficulty.HARD)));
    }

    /** 按持久固定 id 认领 (重启常规路径; 唯一在 region 滑动后仍稳定的锚点)。 */
    private InstanceState claimFixedById(Difficulty d) {
        java.util.OptionalLong fixedId = savedData.fixedInstanceId(d);
        if (fixedId.isEmpty()) {
            return null;
        }
        InstanceState inst = instances.get(fixedId.getAsLong());
        return (inst != null && inst.genState().isAlive()) ? inst : null;
    }

    /**
     * 一次性迁移认领 (存量存档尚未写 fixedInstanceId 时的兜底; 只比 XZ 原点, 不比 Y/size)。
     *
     * 匹配几何必须用 MiningConstants 硬编码的历史值 (SIZE=256/STRIDE=288), 不能用
     * {@code regionGrid.fixedRegionFor(d)}: 后者现在由运行期 config 的 regionSizeChunks/bufferChunks
     * 派生 (F063), 而 F063 落地前的所有存档, 其固定实例一律是按写死几何建的, 与之后 config 里的值
     * 无关也不随其变化。用 config 派生几何去匹配, 在既有存档的 config 默认值与写死历史值不一致时
     * (例如本分支把 bufferChunks 默认值从 1 改成 2 之后) 会把老实例判成"未认领", 从而重复新建、
     * 与老实例已挖空的地形在世界里直接重叠 (分支复核 finding #1/#5/#8)。
     */
    private InstanceState claimFixedByLegacyGeometry(Difficulty d) {
        int legacyOriginX = MiningConstants.REGION_ORIGIN_X + d.regionCellX() * MiningConstants.REGION_STRIDE_X;
        int legacyOriginZ = MiningConstants.REGION_ORIGIN_Z
                + MiningConstants.FIXED_REGION_CELL_Z * MiningConstants.REGION_STRIDE_Z;
        for (InstanceState inst : instances.values()) {
            if (inst.difficulty() == d && inst.genState().isAlive()
                    && inst.regionBox().originX() == legacyOriginX
                    && inst.regionBox().originZ() == legacyOriginZ) {
                return inst;
            }
        }
        return null;
    }

    /**
     * 在给定 region 创建一个常驻共享固定实例 (R1)。与 createInstance 同流程, 但 region 取固定或 frontier
     * 几何而非螺旋 claim。不调用 regionGrid.markOccupied: 固定实例常驻且不参与动态螺旋分配, 滑动坐标进
     * 位图会把 BitSet 撑到 GB 级 (见 RegionGrid 类注释 "滑动 region" 段), 位图对固定实例毫无意义。
     */
    private InstanceState createFixedInstance(Difficulty difficulty, RegionBox box) {
        long instanceId = savedData.allocateInstanceId();
        long seed = SeedUtil.deriveSeed(savedData.globalSeed(), instanceId, 0);

        InstanceState inst = new InstanceState(instanceId, seed, difficulty, box,
                null, true, server.getTickCount(), GenState.READY);
        instances.put(instanceId, inst);
        savedData.setDirty();

        LOGGER.info("[miningdim] created fixed instance {} (difficulty={}, region origin={},{})",
                instanceId, difficulty, box.originX(), box.originZ());
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
                ownerKey, shared, server.getTickCount(), GenState.READY);
        instances.put(instanceId, inst);
        if (!shared && ownerKey != null) {
            privateIndex.put(new OwnerDifficultyKey(ownerKey, difficulty), instanceId);
        }
        persistRegionBitmap();
        savedData.setDirty();

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

    // ---- 生成终态回调 (主线程) ----

    /**
     * 实例进入 READY/READY_FALLBACK/FAILED 时兑现/异常完成挂起的 allocate future (7.9.4)。
     * 由 {@link #slideRegion} 在置 READY 后直接调用 (复核修正 finding #2/#4: 删除 GenerationScheduler 时
     * 一并删掉了其对本方法的注入回调, 导致 RESETTING 窗口内 attachAllocationFuture 落入 pendingAllocations
     * 的 future 再无兑现路径, 玩家静默永久挂起)。
     */
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

    // ---- 滑动重置 (D3/13.4) ----

    /**
     * 把实例整块搬到一块从未生成过的新坐标 (F003/D3)。主线程, 由 ResetJob 的 REGEN 阶段调用。
     * 区块票的释放责任不在本类: 调用方 ResetJob.doUnload 已在滑动前调
     * ChunkServices.ticketService().releaseAll(instanceId) 撤销旧几何下的强加载票; 即便有遗漏,
     * ChunkTicketManager.syncGeometry 在下次同步几何时也会按旧 owner 快照兜底撤票 (旧 owner/旧 regionBox
     * 下发出的 ticket 只能由旧 owner 撤销, 该逻辑已在几何切换前完成, 见 ChunkTicketManager 类注释)。
     * RegionLayout.set 仍须在 relocate 之后尽快调用: 否则新区块生成/查询期间 MiningBiomeSource 仍按旧
     * 快照判归属, 把新区判成 mining_wall 基岩墙。
     */
    @Override
    public RegionBox slideRegion(long instanceId, long newSeed) {
        InstanceState inst = instances.get(instanceId);
        if (inst == null) {
            throw new IllegalStateException("cannot slide unknown instance " + instanceId);
        }

        int newOriginX = savedData.allocateRegionOriginX(regionGrid.sizeX(), MiningConstants.SLIDE_SEPARATION_BLOCKS);
        RegionBox newBox = regionGrid.regionAtOrigin(newOriginX, MiningConstants.REGION_ORIGIN_Z);

        RegionBox oldBox = inst.regionBox();
        // 旧 region 登记待回收: 滑动方案的代价是旧坐标那 16x16 个区块原样留在 .mca 里, 不登记就是每次重置
        // 泄漏一整块 region 的磁盘。实际清除由 RetiredRegionGc 在区块卸载后分批做, 此处只入队。
        savedData.retireRegion(oldBox.originX(), oldBox.originZ());
        inst.relocate(newBox, newSeed);
        // 几何一改立刻广播失效 (分支复核 finding #11): 陷阱静态表/铺矿表/出生池/刷怪调度态四个子系统
        // 按 instanceId 缓存的都是旧几何/旧种子, 必须在 genState 变回 enterable 之前失效, 否则生成完成
        // 到 SETTLE 阶段广播之间存在 1-2 tick 窗口, 期间新进入的玩家会拿到旧几何算出的出生点坐标。
        // 原先这条广播放在 ResetJob 的 SETTLE 阶段首 tick (远晚于此处), 已移除, 不再重复调用。
        MiningServices.fireInstanceReset(inst.instanceId());
        RegionLayout.set(RegionLayout.current().with(inst.difficulty(), newBox));
        savedData.setDirty();

        inst.setGenState(GenState.READY);
        // ResetSystem.reset() 在建 ResetJob 前已同步把 genState 置 RESETTING (非 isEnterable), 期间任何
        // allocate 都会落入 attachAllocationFuture 的 else 分支挂进 pendingAllocations; 上面这行是该窗口
        // 内 genState 唯一的终态迁移点, 必须原地兑现, 否则挂起的 future 再无任何回调路径 (复核修正 #2/#4)。
        onInstanceTerminalState(inst);

        LOGGER.info("[miningdim] instance {} (difficulty={}) slid region ({},{}) -> ({},{}), newSeed={}",
                instanceId, inst.difficulty(), oldBox.originX(), oldBox.originZ(),
                newBox.originX(), newBox.originZ(), newSeed);
        return newBox;
    }

    /**
     * 派生下一次重置的种子 (F089)。重置代数经 savedData.incrementResetGeneration() 随存档落盘,
     * 重启后不会从 0 重来 (代数在内存里跟踪会导致重启后复用同一批种子)。主线程。
     */
    @Override
    public long deriveNextResetSeed(long instanceId) {
        int generation = savedData.incrementResetGeneration();
        return SeedUtil.deriveSeed(savedData.globalSeed(), instanceId, generation);
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
            // 不立即销毁: 进入 emptyTtl 宽限期 (12.6), 由 gcScan 到期处理。区块票生命周期完全交给
            // chunk 子系统的滑动窗口 + 空置 TTL, 本类不再自己撤票。
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

        // 固定/滑动实例从未 markOccupied (见 RegionGrid 类注释 "滑动 region" 段), free 对它们本就是
        // 无操作; 而它们的滑动坐标原点不保证对齐当前网格 stride, 无条件 free 会在孤儿清理路径上直接
        // 抛 IAE 崩服 (分支复核 finding #2/#4/#9)。只对真正网格成员 (对齐的动态分配实例) 调用 free。
        if (regionGrid.isAligned(inst.regionBox())) {
            regionGrid.free(inst.regionBox());
        }
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

    // ---- 服务端生命周期 (InstanceSystem 调用) ----

    /** 服务端停止: 落盘最终态 (ServerStoppingEvent)。离线生成线程池已随 GenerationScheduler 下线 (F090)。 */
    public void shutdown() {
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
