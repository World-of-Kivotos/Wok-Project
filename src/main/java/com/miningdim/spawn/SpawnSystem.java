package com.miningdim.spawn;

import com.miningdim.core.IMiningConfig;
import com.miningdim.core.ISpawnService;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import com.miningdim.core.SeedUtil;
import com.miningdim.core.Subsystem;
import com.miningdim.core.VoxelOccupancy;
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
 *   1. 预生成 spawn pool: ConnectivityFix 后从主连通分量枚举合法站立点 (11.2/11.3), 按 instanceId 缓存。
 *   2. 并发取点原子占用 (11.4): 取点经主线程串行 + SpawnPool TTL 占用避免叠人。
 *   3. 安全谓词权威 (11.2): isSafe 在真实 ServerLevel 上复核头顶净空/脚下固体/无岩浆。
 *   4. 兜底安全平台 (11.5): 找不到点强制建 3x3 平台保证可落地。
 *   5. 出生后保护 (11.7): 安全半径 + danger 冻结由压力子系统在玩家进入实例时设置冻结窗口落实
 *      (单点控制, 不在本出生服务里重复设置)。本服务只负责解析并返回安全落点。
 *
 * 线程纪律 (D8): pool 预扫描 (枚举体素) 为纯计算; 取点/整地/兜底平台的世界写须主线程, 调用方
 * (入口流程 14.2) 已在 server.execute 内调用本服务。本类自身不订阅世界写事件, 仅作服务被调用。
 */
public final class SpawnSystem implements Subsystem, ISpawnService {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/spawn");

    /** 出生点池容量下限 (11.3 MIN_SPAWN_POOL); 低于此记 WARN 并允许兜底平台。 */
    private static final int MIN_SPAWN_POOL = 8;

    /** 占用 TTL (11.4 占用 TTL 如 60 tick)。 */
    private static final int OCCUPY_TTL_TICKS = 60;

    /** pool 预扫描时每个难度子盒采样的 Y 层步长 (避免逐层枚举上百万体素, 取代表层抽样)。 */
    private static final int POOL_SCAN_Y_STEP = 4;

    /** 兜底平台边长 (11.5: 3x3)。 */
    private static final int FALLBACK_PLATFORM_HALF = 1;

    /** 各实例的 spawn pool 缓存 (instanceId -> pool)。运行期瞬态, 实例释放时移除。 */
    private final Map<Long, SpawnPool> pools = new ConcurrentHashMap<>();

    /** 各实例取点计数器 (11.3 pickCounter, 用于确定性取点偏移)。 */
    private final Map<Long, AtomicInteger> pickCounters = new ConcurrentHashMap<>();

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
    public BlockPos findSpawn(InstanceState instance, VoxelOccupancy voxels) {
        SpawnPool pool = pools.computeIfAbsent(instance.instanceId(),
                id -> buildPool(instance, voxels));

        long now = serverGameTimeOrZero();
        int pick = pickCounters.computeIfAbsent(instance.instanceId(), id -> new AtomicInteger())
                .getAndIncrement();

        // 取点偏移: 确定性可复现 (11.3 deriveInt(seed,"spawnPick",pickCounter))。
        int startIndex = deterministicPick(instance.seed(), pick, Math.max(1, pool.size()));
        BlockPos claimed = pool.claim(startIndex, now, OCCUPY_TTL_TICKS);
        if (claimed != null) {
            return claimed;
        }

        // 池耗尽 (11.4): 退回 anchor 附近微扰; anchor 为空则用 region 几何中心作为兜底目标点。
        BlockPos anchor = pool.anchor();
        if (anchor != null) {
            pool.reserve(anchor, now, OCCUPY_TTL_TICKS);
            LOGGER.warn("spawn pool exhausted for instance {}, fell back to anchor {}",
                    instance.instanceId(), anchor);
            return anchor;
        }

        // pool 完全为空 (连通性缺陷或体素全实心): 返回 region 中心列的目标点, 由调用方建兜底平台 (11.5)。
        BlockPos center = regionCenterTarget(instance.regionBox());
        LOGGER.warn("spawn pool empty for instance {}, returning region center target {} for fallback platform",
                instance.instanceId(), center);
        return center;
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
     * @param level    矿山维度
     * @param instance 所属实例
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

        SpawnPool pool = pools.get(instance.instanceId());
        if (pool != null) {
            pool.reserve(center, serverGameTimeOrZero(), OCCUPY_TTL_TICKS);
        }
        LOGGER.warn("built fallback platform for instance {} at {}", instance.instanceId(), center);
        return center;
    }

    // ---- pool 预生成 (11.3, 纯计算) ----

    /**
     * 从体素视图枚举合法站立点构成 spawn pool (11.3)。OfflineGenerator 已保证体素主分量全连通 (D4),
     * 故无需在此再跑连通分量 BFS, 全体合法站立点天然 ∈ 主分量; mainComponent 约束传 null。
     * anchor 取靠近 region 几何中心的合法点 (11.1 优先几何中心), 置于 pool 首位。
     */
    SpawnPool buildPool(InstanceState instance, VoxelOccupancy voxels) {
        IMiningConfig config = MiningServices.config();
        int headroom = config.spawnHeadroomBlocks();
        boolean requireFloor = config.spawnRequireSolidFloor();
        boolean avoidTraps = config.spawnAvoidTrapZones();
        int targetSize = Math.max(MIN_SPAWN_POOL, config.spawnPoolSize());

        RegionBox box = instance.regionBox();
        int w = voxels.width();
        int h = voxels.height();
        int d = voxels.depth();

        // 几何中心本地坐标 (用于 anchor 优选与排序)。
        int cx = w / 2;
        int cz = d / 2;

        // R2: 整块 region 单难度, 出生点可落在全高任意安全层 (不再有 Y 子盒)。
        int loLocalY = 0;
        int hiLocalY = h - 1;

        List<BlockPos> candidates = new ArrayList<>();
        BlockPos anchor = null;
        long bestAnchorDist = Long.MAX_VALUE;

        // 自中心向外按 Y 抽样层枚举, 命中即收集; anchor 取离中心最近者。
        for (int ly = loLocalY; ly <= hiLocalY; ly += POOL_SCAN_Y_STEP) {
            for (int lz = 0; lz < d; lz++) {
                for (int lx = 0; lx < w; lx++) {
                    if (!SpawnPredicate.isSafeSpawn(voxels, lx, ly, lz, headroom, requireFloor,
                            null, null, avoidTraps)) {
                        continue;
                    }
                    BlockPos world = box.localToWorldPos(lx, ly, lz);
                    long dist = (long) (lx - cx) * (lx - cx) + (long) (lz - cz) * (lz - cz);
                    if (dist < bestAnchorDist) {
                        bestAnchorDist = dist;
                        anchor = world;
                    }
                    candidates.add(world);
                    // 收集到足够候选即停 (含 anchor 优选裕量), 避免全盒枚举开销。
                    if (candidates.size() >= targetSize * 4) {
                        return assemblePool(instance, candidates, anchor, targetSize);
                    }
                }
            }
        }
        return assemblePool(instance, candidates, anchor, targetSize);
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

    /** region 几何中心列的脚部目标点 (兜底平台选址, 11.5 step1)。Y 取 region 盒几何中点。 */
    private static BlockPos regionCenterTarget(RegionBox box) {
        int wx = box.originX() + box.sizeX() / 2;
        int wz = box.originZ() + box.sizeZ() / 2;
        int wy = box.originY() + box.sizeY() / 2;
        return new BlockPos(wx, wy, wz);
    }

    /** 确定性取点起始下标 (11.3): 用 SeedUtil.hash 把 (seed, pickCounter) 派生为 [0,size) 的偏移。 */
    private static int deterministicPick(long seed, int pickCounter, int size) {
        long h = SeedUtil.hash(seed, pickCounter, 0, 0x5A_FE);
        return (int) Math.floorMod(h, size);
    }

    /**
     * 取当前 server game time。findSpawn 由入口流程在主线程调用, 此时 server 必已运行;
     * 若极端早期被调 (server 未就绪) 退化为 0 (仅影响 TTL 起点, 不影响正确性)。
     */
    private static long serverGameTimeOrZero() {
        net.minecraft.server.MinecraftServer server =
                net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld().getGameTime() : 0L;
    }
}
