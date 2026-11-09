package com.miningdim.job.farmer.granary;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 损坏复查的调度 (Spoilage_Granary_DesignSpec「损坏与宽限」: 发现损坏要快)。
 *
 * 房间判定本身不便宜 (洪泛整间屋), 所以不能一方块一变就扫全场。这里的规矩照规格写死:
 * <ol>
 *   <li><b>方块一变就查</b>: 屋子范围内有方块被挖、被放、被炸、被活塞推动 → 给那座粮仓打脏标记;</li>
 *   <li><b>同一座最快每 20 tick 一次</b>: 冷却没到就留在队里等下一 tick;</li>
 *   <li><b>全服排队</b>: 每 tick 最多真扫一座 (超过 {@value #BIG_ROOM_CELLS} 格的大屋子自然也就
 *       每 tick 一座, 不会出现一 tick 扫好几间大厅的情况);</li>
 *   <li><b>每 5 分钟错峰自查一次</b>: 按 {@code floorMod(gameTime + 打包坐标, 6000) == 0} 轮, 于是
 *       全服的粮仓散在 6000 tick 里, 不会整点一起扫;</li>
 *   <li><b>跨到未加载区块就不判</b>: 保持原状态, 等那个区块加载时由区块加载事件重新排队 ——
 *       绝不为了复查去把区块读出来 (那等于把卡服搬进主线程)。</li>
 * </ol>
 *
 * 这里是**调度状态**, 不进存档: 重启之后队列为空, 但「每 5 分钟错峰自查」会在一个周期内把所有
 * 粮仓过一遍, 不需要把脏标记写进存档。
 */
public final class GranaryRecheck {

    /** 同一座粮仓两次真扫之间的最短间隔 (tick)。 */
    public static final int SCAN_COOLDOWN_TICKS = 20;

    /** 定期错峰自查的周期 (tick, 5 分钟)。 */
    public static final int SELF_CHECK_PERIOD_TICKS = 6_000;

    /** 超过这个格数的屋子算「大厅」: 一 tick 只处理一座 (与「全服排队」同一条预算)。 */
    public static final int BIG_ROOM_CELLS = 1_500;

    /** 一处待查的粮仓 (维度 + 账台坐标)。 */
    private record Key(ResourceLocation dimension, long packedPos) {
    }

    private record Entry(Key key, long markedTick) {
    }

    /** 已经在队列里的键 (同一个坐标反复变动只排一次)。 */
    private static final Set<Key> queued = new HashSet<>();
    private static final Deque<Entry> queue = new ArrayDeque<>();
    /** 上一次真扫的 tick (冷却与诊断都读它)。 */
    private static final Map<Key, Long> lastScanTick = new HashMap<>();
    /** 上一次扫出来的室内格数 (大屋子的口径, 也给 /granary 一类的诊断用)。 */
    private static final Map<Key, Integer> lastInteriorCells = new HashMap<>();

    private GranaryRecheck() {
    }

    /** 开服清空 (调度状态不跨存档)。 */
    public static void reset() {
        queued.clear();
        queue.clear();
        lastScanTick.clear();
        lastInteriorCells.clear();
    }

    // ============================================================
    // 打脏标记
    // ============================================================

    /** 单处方块变了。 */
    public static void markDirty(ServerLevel level, BlockPos pos) {
        markDirtyArea(level, pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
    }

    /** 一片区域变了 (爆炸、活塞推动这类一次动一片的走这条, 只过一遍账本)。 */
    public static void markDirtyArea(ServerLevel level, int minX, int minY, int minZ,
                                     int maxX, int maxY, int maxZ) {
        ResourceLocation dimension = level.dimension().location();
        long now = level.getGameTime();
        for (GranarySavedData.PosKey center : centerList(level)) {
            if (!center.dimension().equals(dimension)) {
                continue;
            }
            if (inReach(center.pos(), minX, minY, minZ, maxX, maxY, maxZ)) {
                enqueue(new Key(dimension, center.packedPos()), now);
            }
        }
    }

    /** 一个区块加载了: 屋子范围可能盖到它的粮仓都要复查 (跨区块的粮仓之前被判「未加载」)。 */
    public static void markChunkLoaded(ServerLevel level, ChunkPos chunk) {
        markDirtyArea(level, chunk.getMinBlockX(), level.getMinBuildHeight(), chunk.getMinBlockZ(),
                chunk.getMaxBlockX(), level.getMaxBuildHeight(), chunk.getMaxBlockZ());
    }

    // ============================================================
    // 每 tick 调度
    // ============================================================

    /**
     * 服务器每 tick 调一次 (END 阶段)。
     *
     * @return 这一 tick 真扫了几座 (0 或 1)
     */
    public static int tick(MinecraftServer server, long gameTime) {
        ServerLevel overworld = server.overworld();
        List<GranarySavedData.PosKey> centers = centerList(overworld);
        if (centers.isEmpty()) {
            queue.clear();
            queued.clear();
            return 0;
        }
        GranarySavedData data = GranarySavedData.get(overworld);

        // 1) 脏队列: 先看队首那一座 —— 玩家刚动过手, 损坏要尽快被发现。
        while (!queue.isEmpty()) {
            Entry entry = queue.peekFirst();
            Long last = lastScanTick.get(entry.key());
            if (last != null && gameTime - last < SCAN_COOLDOWN_TICKS) {
                break;
            }
            queue.pollFirst();
            queued.remove(entry.key());
            if (scan(server, data, entry.key(), gameTime)) {
                return 1;
            }
            // 粮仓没了、或者区块没加载: 丢掉这一条, 继续看下一条。
        }

        // 2) 定期错峰自查: 本 tick 轮到的第一座。
        for (GranarySavedData.PosKey center : centers) {
            if (Math.floorMod(gameTime + center.packedPos(), SELF_CHECK_PERIOD_TICKS) != 0) {
                continue;
            }
            if (scan(server, data, new Key(center.dimension(), center.packedPos()), gameTime)) {
                return 1;
            }
        }
        return 0;
    }

    /** 真扫一座; 扫不了 (粮仓没了 / 区块没加载) 返回 false。 */
    private static boolean scan(MinecraftServer server, GranarySavedData data, Key key, long gameTime) {
        BlockPos pos = BlockPos.of(key.packedPos());
        if (data.centerGranary(key.dimension(), pos) == 0L) {
            return false;
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, key.dimension()));
        if (level == null) {
            return false;
        }
        if (!chunksLoaded(level, pos)) {
            // 规格: 房子跨到未加载区块时不判, 保持原状态。区块加载时会重新排队。
            return false;
        }
        GranaryInspection.Report report = GranaryInspection.inspect(level, pos,
                GranaryClock.nowHour(level));
        lastScanTick.put(key, gameTime);
        lastInteriorCells.put(key, report.scan().interiorCells());
        return true;
    }

    /** 屋子范围内的区块列都加载了吗 (只查区块, 不把它们读出来)。 */
    private static boolean chunksLoaded(ServerLevel level, BlockPos center) {
        int minChunkX = (center.getX() - GranaryStructureScanner.HORIZONTAL_RADIUS) >> 4;
        int maxChunkX = (center.getX() + GranaryStructureScanner.HORIZONTAL_RADIUS) >> 4;
        int minChunkZ = (center.getZ() - GranaryStructureScanner.HORIZONTAL_RADIUS) >> 4;
        int maxChunkZ = (center.getZ() + GranaryStructureScanner.HORIZONTAL_RADIUS) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ============================================================
    // 诊断 (用例与命令看这些)
    // ============================================================

    /** 队列里还有几座待查。 */
    public static int queuedCount() {
        return queue.size();
    }

    /** 这座粮仓排在队里吗。 */
    public static boolean isQueued(ResourceLocation dimension, BlockPos pos) {
        return queued.contains(new Key(dimension, pos.asLong()));
    }

    /** 上一次真扫是哪个 tick (0 = 这一轮还没扫过)。 */
    public static long lastScanTick(ResourceLocation dimension, BlockPos pos) {
        return lastScanTick.getOrDefault(new Key(dimension, pos.asLong()), 0L);
    }

    /** 上一次扫出来的室内格数 (-1 = 还没扫过)。 */
    public static int lastInteriorCells(ResourceLocation dimension, BlockPos pos) {
        return lastInteriorCells.getOrDefault(new Key(dimension, pos.asLong()), -1);
    }

    // ============================================================
    // 内部
    // ============================================================

    private static void enqueue(Key key, long now) {
        if (queued.add(key)) {
            queue.addLast(new Entry(key, now));
        }
    }

    /** 账本里的全部账台 (账本挂在 overworld 上, 是全局那一份)。 */
    private static List<GranarySavedData.PosKey> centerList(ServerLevel level) {
        return GranarySavedData.get(level.getServer().overworld()).centers();
    }

    /** 屋子范围 (±24 / ±12) 与这片区域有没有交集。 */
    private static boolean inReach(BlockPos center, int minX, int minY, int minZ,
                                   int maxX, int maxY, int maxZ) {
        return center.getX() + GranaryStructureScanner.HORIZONTAL_RADIUS >= minX
                && center.getX() - GranaryStructureScanner.HORIZONTAL_RADIUS <= maxX
                && center.getZ() + GranaryStructureScanner.HORIZONTAL_RADIUS >= minZ
                && center.getZ() - GranaryStructureScanner.HORIZONTAL_RADIUS <= maxZ
                && center.getY() + GranaryStructureScanner.VERTICAL_RADIUS >= minY
                && center.getY() - GranaryStructureScanner.VERTICAL_RADIUS <= maxY;
    }
}
