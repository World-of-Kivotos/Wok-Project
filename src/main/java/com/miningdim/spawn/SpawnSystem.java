package com.miningdim.spawn;

import com.miningdim.core.IMiningConfig;
import com.miningdim.core.ISpawnService;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import com.miningdim.core.SeedUtil;
import com.miningdim.core.Subsystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 安全出生子系统 (设计文档 3.3 SpawnSystem / 第十一章, 实现 core.ISpawnService)。
 *
 * 职责:
 *   1. 预生成 spawn pool: 在真实世界 (原版 noise 生成后) 环形扫描出安全站立点 (11.2/11.3),
 *      按 instanceId 缓存; 无离线体素视图, 全部读真实 ServerLevel 方块状态。
 *   2. 并发取点原子占用 (11.4): 取点经主线程串行 + SpawnPool TTL 占用避免叠人。
 *   3. 安全谓词权威 (11.2): isSafe 在真实 ServerLevel 上复核头顶净空/脚下固体/无岩浆。
 *   4. 兜底安全平台 (11.5): 找不到点强制建 3x3 平台保证可落地。
 *   5. 出生后保护 (11.7): 安全半径 + danger 冻结由压力子系统在玩家进入实例时设置冻结窗口落实
 *      (单点控制, 不在本出生服务里重复设置)。本服务只负责解析并返回安全落点。
 *
 * 线程纪律 (D8): pool 预扫描本身即读真实世界方块状态, 须在已 force-load 就绪的区块范围内、
 * 主线程调用 (调用方入口流程 14.2 已在区块 FULL 确认后于主线程调用本服务)。
 */
public final class SpawnSystem implements Subsystem, ISpawnService {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/spawn");

    /** 出生点池容量下限 (11.3 MIN_SPAWN_POOL); 低于此记 WARN 并允许兜底平台。 */
    private static final int MIN_SPAWN_POOL = 8;

    /**
     * 占用 TTL (11.4 占用 TTL)。F034 复核修正: 原值 60 tick 远短于玩家实际受保护的出生冻结窗口
     * (EntryGateway.SPAWN_FREEZE_TICKS / MobPressureSystem.SPAWN_FREEZE_TICKS 均为 200) —— 玩家还站在
     * 冻结保护里没挪窝, 脚下候选点却已在第 60 tick 提前释放, 后续入场者有概率被判定为"该点空闲"而叠加
     * claim 到同一格。取 220 (略大于 200 的冻结窗口留一点缓冲), 保证一个点在玩家整段冻结期内不会被复用。
     */
    private static final int OCCUPY_TTL_TICKS = 220;

    /**
     * pool 预扫描的水平环形半径。16 是 EntryGateway 入场前 force-load 的 3x3 区块窗口
     * (SPAWN_FORCE_RADIUS_CHUNKS=1, 即中心区块 + 周边一圈共 3x3 区块 = 48 格边长) 在最坏对齐下
     * 的保证半径 (中心落在区块边界时, 一圈区块只能保证向外 16 格已强加载)。扫描半径必须与之对齐,
     * 否则会在主线程同步加载/生成邻近区块 —— PR35 删掉 GenerationScheduler 的全 region 预加载后,
     * 旧的 24 格半径 (EntryGateway 原 SPAWN_SCAN_HORIZONTAL_RADIUS) 已确定越界。
     */
    private static final int SPAWN_POOL_SCAN_RADIUS = 16;

    /**
     * 候选点最小水平间距 (Chebyshev 距离)。F034 的实害是多人叠在同一格被一颗苦力怕一锅端,
     * 池里的点必须真的分散开, 不是换个位置的同一格。
     */
    private static final int MIN_SPAWN_SEPARATION = 4;

    /**
     * pool 预扫描硬预算 (已探查的 (列, Y) 次数上限)。全实心 region 的最坏情况会在单 tick 内
     * 跑上百万次方块读, 必须有上界; 预算耗尽时退化成候选不足 -> findSpawn 走兜底平台, 行为安全。
     */
    private static final int SPAWN_SCAN_BUDGET = 40000;

    /** 兜底平台边长 (11.5: 3x3)。 */
    private static final int FALLBACK_PLATFORM_HALF = 1;

    /**
     * 兜底平台候选点的水平抖动步长 (F034 复核修正)。>= 3x3 平台的边长, 保证相邻抖动格的平台互不重叠。
     */
    private static final int FALLBACK_JITTER_STEP = 4;

    /** 兜底候选点向外扩的环数上限 (F034 复核修正: 找不到空位时的搜索上界, 避免无界扫描)。 */
    private static final int FALLBACK_JITTER_RINGS = 4;

    /** 各实例的 spawn pool 缓存 (instanceId -> pool)。运行期瞬态, 实例释放时移除。 */
    private final Map<Long, SpawnPool> pools = new ConcurrentHashMap<>();

    /** 各实例取点计数器 (11.3 pickCounter, 用于确定性取点偏移)。 */
    private final Map<Long, AtomicInteger> pickCounters = new ConcurrentHashMap<>();

    /**
     * 各实例的兜底平台占用登记 (F034 复核修正): 与预扫描 {@link #pools} 分开维护, 因为兜底候选点本就
     * 不在预扫描池的候选列表里 (SpawnPool.claim 只遍历 pool 列表, 不认非列表成员的 reserve)。用一个
     * 候选列表为空的 SpawnPool 单纯复用其 occupiedUntil TTL 记账 (reserve/isOccupied), 保证连续兜底调用
     * 之间真的互斥, 不会重复摞到同一格。
     */
    private final Map<Long, SpawnPool> fallbackTrackers = new ConcurrentHashMap<>();

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 本子系统不需要自有 DeferredRegister / 事件订阅: 仅作为 ISpawnService 被入口流程调用。
        // 注入门面供其他子系统按接口取用 (铁律 3)。
        MiningServices.registerSpawnService(this);
        // 滑动重置后按 instanceId 缓存的旧 spawn pool (旧几何/旧种子) 与取点计数器必须失效重算 (D3)。
        MiningServices.registerInstanceResetListener(this::onInstanceReleased);
    }

    @Override
    public String name() {
        return "SpawnSystem";
    }

    // ---- ISpawnService ----

    @Override
    public BlockPos findSpawn(ServerLevel level, InstanceState instance) {
        SpawnPool pool = pools.get(instance.instanceId());
        if (pool == null) {
            pool = buildPool(level, instance);
            // F034 复核修正: 只缓存非空池。空池 (预算耗尽/地形全实心) 若被永久缓存, 该实例此后每个
            // findSpawn 调用都会直接落到 pool.size()==0 的兜底分支, 永不重试 —— 即使玩家已把地形挖开、
            // 真实候选本可以存在了也一样。findSpawn 单线程串行 (D8), 不缓存空池的代价只是失败时重跑一次
            // 扫描 (预算已封顶, 开销可控), 换来的是"地形一旦可用就能自愈"而不是死锁到下次实例重置。
            if (!pool.isEmpty()) {
                pools.put(instance.instanceId(), pool);
            } else {
                LOGGER.warn("instance {} spawn pool scan produced 0 candidates; will retry on next findSpawn call",
                        instance.instanceId());
            }
        }

        long now = level.getGameTime();
        int pick = pickCounters.computeIfAbsent(instance.instanceId(), id -> new AtomicInteger())
                .getAndIncrement();

        // 取点偏移: 确定性可复现 (11.3 deriveInt(seed,"spawnPick",pickCounter))。
        int start = deterministicPick(instance.seed(), pick, Math.max(1, pool.size()));

        // 池是建好后缓存的, 玩家会把地形挖塌, 缓存点可能已不安全。claim 到的点若 isSafe 复核不过,
        // 【不 release】继续 claim 下一个 —— 已 claim 的点带着 TTL 继续占着, OCCUPY_TTL_TICKS 后自然过期重试,
        // 既避免同一次循环里反复取到同一个坏点造成死循环, 也不会永久踢出该点。
        for (int i = 0; i < pool.size(); i++) {
            BlockPos claimed = pool.claim(start, now, OCCUPY_TTL_TICKS);
            if (claimed == null) {
                // 池已全被占用 (含本次循环内已 claim 但 isSafe 未过的点)。
                break;
            }
            if (isSafe(level, claimed, instance)) {
                return claimed;
            }
        }

        // 池耗尽或全部候选都已不安全: 建兜底安全平台 (11.5), 保证任何地形都能落地。
        // F034 复核修正: 落点不再是恒定同一格, claimFallbackCell 在若干抖动候选里找一个当前未占用的
        // (占用登记 TTL = OCCUPY_TTL_TICKS, 覆盖整段出生冻结窗口), 避免多个入场者叠在同一格。
        BlockPos fallbackPos = claimFallbackCell(level, instance, now);
        return buildFallbackPlatform(level, instance, fallbackPos);
    }

    /**
     * 为兜底平台找一个当前未占用的候选点 (F034 复核修正): 以几何中心为环心, 按
     * {@link #FALLBACK_JITTER_STEP} 步长向外环形扩散, 找第一个 (在 region 内 且 未被占用) 的候选并登记占用。
     * 环内全占用 (极端并发) 时退回几何中心本身并强制登记 —— 仍然安全 (buildFallbackPlatform 每次都重新
     * 现造 3x3 安全平台), 只是失去去重, 记 WARN 便于观测。
     */
    private BlockPos claimFallbackCell(ServerLevel level, InstanceState instance, long now) {
        SpawnPool tracker = fallbackTrackers.computeIfAbsent(instance.instanceId(), id -> new SpawnPool(List.of()));
        RegionBox box = instance.regionBox();
        BlockPos base = fallbackCenter(level, instance);

        for (int ring = 0; ring <= FALLBACK_JITTER_RINGS; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue; // 只扫当前环, 由内向外 (与 buildPool 环形扫描同范式)。
                    }
                    int wx = base.getX() + dx * FALLBACK_JITTER_STEP;
                    int wz = base.getZ() + dz * FALLBACK_JITTER_STEP;
                    if (!box.contains(wx, wz)) {
                        continue;
                    }
                    BlockPos candidate = new BlockPos(wx, base.getY(), wz);
                    if (!tracker.isOccupied(candidate, now)) {
                        tracker.reserve(candidate, now, OCCUPY_TTL_TICKS);
                        return candidate;
                    }
                }
            }
        }
        LOGGER.warn("instance {} fallback jitter exhausted ({} rings), all candidates occupied; reusing base cell {}",
                instance.instanceId(), FALLBACK_JITTER_RINGS, base);
        tracker.reserve(base, now, OCCUPY_TTL_TICKS);
        return base;
    }

    @Override
    public boolean isSafe(ServerLevel level, BlockPos pos, InstanceState instance) {
        IMiningConfig config = MiningServices.config();
        int headroom = config.spawnHeadroomBlocks();
        boolean requireFloor = config.spawnRequireSolidFloor();
        int lavaRadius = config.spawnLavaAvoidRadius();

        // 头顶 headroom 格须为非阻挡空气 (11.2 头顶净空)。
        for (int dy = 0; dy < headroom; dy++) {
            BlockPos p = pos.above(dy);
            if (!level.getBlockState(p).isAir()) {
                return false;
            }
        }

        // 脚下须为可站立顶面 (11.2 脚下固体): 用 isFaceSturdy(UP) 判定稳固承载面。
        if (requireFloor) {
            BlockPos below = pos.below();
            BlockState floor = level.getBlockState(below);
            if (!floor.isFaceSturdy(level, below, Direction.UP)) {
                return false;
            }
        }

        // lavaAvoidRadius 邻域无岩浆流体 (11.2 无岩浆邻接, 真实 FluidState 复核)。
        return !hasLavaNearby(level, pos, lavaRadius);
    }

    // ---- 兜底平台 (11.5, 世界写: 须由调用方在主线程调用) ----

    /**
     * 在指定点强制建 3x3 安全平台 (11.5): 脚下铺实心石、清出 3x3x3 空气净空、清除半径内岩浆。
     * 必须在服务端主线程调用 (世界写, D8)。返回平台中心 (玩家落点 = center, 脚踏其下方石面)。
     *
     * 占用登记不在本方法内做 (F034 复核修正): 原实现在此对 {@link #pools} 的预扫描池 reserve(center,...),
     * 但 center 从不是 pool 列表成员, {@link SpawnPool#claim} 只遍历列表, 那次 reserve 对后续取点毫无
     * 拦截作用, 纯属误导性死代码。占用去重现由调用方 (findSpawn -> claimFallbackCell) 在选定 center 前经
     * {@link #fallbackTrackers} 完成, 本方法只管把方块造出来。
     *
     * @param level    矿山维度
     * @param instance 所属实例 (仅用于日志)
     * @param center   平台中心目标点 (玩家脚部所在体素)
     * @return 实际落点 (= center)
     */
    public BlockPos buildFallbackPlatform(ServerLevel level, InstanceState instance, BlockPos center) {
        IMiningConfig config = MiningServices.config();
        int headroom = Math.max(2, config.spawnHeadroomBlocks());
        BlockState floor = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();

        // 脚下 3x3 铺实心石。
        for (int dx = -FALLBACK_PLATFORM_HALF; dx <= FALLBACK_PLATFORM_HALF; dx++) {
            for (int dz = -FALLBACK_PLATFORM_HALF; dz <= FALLBACK_PLATFORM_HALF; dz++) {
                level.setBlockAndUpdate(center.offset(dx, -1, dz), floor);
            }
        }

        // 清出 3x3 x headroom 空气净空, 并替换该范围内岩浆 (清除半径内岩浆, 11.5 step2)。
        for (int dx = -FALLBACK_PLATFORM_HALF; dx <= FALLBACK_PLATFORM_HALF; dx++) {
            for (int dz = -FALLBACK_PLATFORM_HALF; dz <= FALLBACK_PLATFORM_HALF; dz++) {
                for (int dy = 0; dy < headroom; dy++) {
                    level.setBlockAndUpdate(center.offset(dx, dy, dz), air);
                }
            }
        }

        LOGGER.warn("built fallback platform for instance {} at {}", instance.instanceId(), center);
        return center;
    }

    // ---- pool 预生成 (11.3, 世界读: 须在已 force-load 就绪的区块范围内、主线程调用) ----

    /**
     * 在真实世界环形扫描出安全站立点构成 spawn pool (11.3)。以 region 几何中心为心、半径
     * {@link #SPAWN_POOL_SCAN_RADIUS} 由内向外环形列扫描, 每列自顶向下只取第一个命中点
     * (保证候选在水平面上铺开, 而非全堆在同一列)。anchor 取离中心最近的合法点 (11.1 优先几何中心),
     * 置于 pool 首位。
     */
    SpawnPool buildPool(ServerLevel level, InstanceState instance) {
        IMiningConfig config = MiningServices.config();
        int targetSize = Math.max(MIN_SPAWN_POOL, config.spawnPoolSize());

        RegionBox box = instance.regionBox();
        int centerX = box.originX() + box.sizeX() / 2;
        int centerZ = box.originZ() + box.sizeZ() / 2;
        // 与 EntryGateway.resolveSpawn 现有口径逐字一致: 扫描范围限制在维度实际可建高度内
        // (caves 维度仅 min..maxBuild, 非 region 全高)。
        int yTop = Math.min(MiningConstants.REGION_MAX_Y_EXCLUSIVE - 2, level.getMaxBuildHeight() - 2);
        int yBottom = Math.max(MiningConstants.REGION_FULL_MIN_WORLD_Y, level.getMinBuildHeight() + 1);

        List<BlockPos> candidates = new ArrayList<>();
        BlockPos anchor = null;
        int budgetUsed = 0;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        scan:
        for (int r = 0; r <= SPAWN_POOL_SCAN_RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    // 只扫当前环 (|dx|==r 或 |dz|==r), 由内向外。
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    int wx = centerX + dx;
                    int wz = centerZ + dz;
                    if (!box.contains(wx, wz)) {
                        continue;
                    }
                    // 每列自顶向下, 只取第一个命中点。
                    for (int wy = yTop; wy >= yBottom; wy--) {
                        budgetUsed++;
                        if (budgetUsed > SPAWN_SCAN_BUDGET) {
                            LOGGER.warn("instance {} spawn pool scan budget ({}) exhausted, collected {} candidates",
                                    instance.instanceId(), SPAWN_SCAN_BUDGET, candidates.size());
                            break scan;
                        }
                        cursor.set(wx, wy, wz);
                        // 两级判据防炸开销: 先做廉价预筛, 通过后再调 isSafe (含 lavaAvoidRadius 邻域扫描,
                        // 默认半径 3 约 343 次流体读), 绝不能对每一格都调。
                        if (!isCheapCandidate(level, cursor)) {
                            continue;
                        }
                        BlockPos hit = cursor.immutable();
                        if (!isSafe(level, hit, instance)) {
                            continue;
                        }
                        // 找到本列的命中点: 无论是否通过散布检查, 本列扫描到此为止。
                        if (isSeparated(candidates, hit)) {
                            if (anchor == null) {
                                // 由内向外环形扫描, 首个被接受的候选即离中心最近者。
                                anchor = hit;
                            }
                            candidates.add(hit);
                            if (candidates.size() >= targetSize) {
                                break scan;
                            }
                        }
                        break;
                    }
                }
            }
        }
        return assemblePool(instance, candidates, anchor, targetSize);
    }

    /** 廉价预筛 (先于 isSafe 调用): 本体与头顶为空气, 脚下非空气。 */
    private static boolean isCheapCandidate(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }
        if (!level.getBlockState(pos.above()).isAir()) {
            return false;
        }
        return !level.getBlockState(pos.below()).isAir();
    }

    /**
     * 候选去重与散布: 新候选须与已收候选的水平 Chebyshev 距离 >= {@link #MIN_SPAWN_SEPARATION} 才收。
     * F034 的实害是多人叠在同一格被一颗苦力怕一锅端, 池里的点必须真的分散, 不是换个位置的同一格。
     */
    private static boolean isSeparated(List<BlockPos> candidates, BlockPos point) {
        for (BlockPos existing : candidates) {
            int dx = Math.abs(existing.getX() - point.getX());
            int dz = Math.abs(existing.getZ() - point.getZ());
            if (Math.max(dx, dz) < MIN_SPAWN_SEPARATION) {
                return false;
            }
        }
        return true;
    }

    /** 组装 pool: anchor 置首位, 截断到 targetSize; 池过小记 WARN (11.3 池容量下限)。 */
    private SpawnPool assemblePool(InstanceState instance, List<BlockPos> candidates,
                                   BlockPos anchor, int targetSize) {
        List<BlockPos> ordered = new ArrayList<>();
        if (anchor != null) {
            ordered.add(anchor);
        }
        for (BlockPos pos : candidates) {
            if (pos.equals(anchor)) {
                continue;
            }
            ordered.add(pos);
            if (ordered.size() >= targetSize) {
                break;
            }
        }
        if (ordered.size() < MIN_SPAWN_POOL) {
            LOGGER.warn("instance {} spawn pool size {} below minimum {}; fallback platform may trigger",
                    instance.instanceId(), ordered.size(), MIN_SPAWN_POOL);
        }
        return new SpawnPool(ordered);
    }

    /** 实例释放/重置时清缓存, 防 pool/计数器泄漏 (与 12.6 GC 呼应)。 */
    public void onInstanceReleased(long instanceId) {
        pools.remove(instanceId);
        pickCounters.remove(instanceId);
        fallbackTrackers.remove(instanceId);
    }

    // ---- 内部工具 ----

    private static boolean hasLavaNearby(ServerLevel level, BlockPos pos, int radius) {
        if (radius <= 0) {
            return level.getFluidState(pos).is(FluidTags.LAVA);
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (level.getFluidState(cursor).is(FluidTags.LAVA)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 兜底平台选址 (11.5 step1): region 几何中心列的脚部目标点。刻意不用 RegionBox 全高中点算 Y
     * 的旧口径 —— region 几何常量按 384 高工作而维度实际只有 192 高 (F088), 按它取 Y 会落到世界外;
     * 本分支不修 F088, 故这里只取世界实际可建高度内的固定层, 与旧 EntryGateway.buildFallbackPlatform
     * 的选址口径逐字一致。
     */
    private static BlockPos fallbackCenter(ServerLevel level, InstanceState instance) {
        RegionBox box = instance.regionBox();
        int wx = box.originX() + box.sizeX() / 2;
        int wz = box.originZ() + box.sizeZ() / 2;
        int wy = Math.min(48, level.getMaxBuildHeight() - 5) + 1;
        return new BlockPos(wx, wy, wz);
    }

    /** 确定性取点起始下标 (11.3): 用 SeedUtil.hash 把 (seed, pickCounter) 派生为 [0,size) 的偏移。 */
    private static int deterministicPick(long seed, int pickCounter, int size) {
        long h = SeedUtil.hash(seed, pickCounter, 0, 0x5A_FE);
        return (int) Math.floorMod(h, size);
    }
}
