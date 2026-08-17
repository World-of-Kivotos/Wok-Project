package com.miningdim.reset;

import com.miningdim.core.MiningConstants;
import com.miningdim.persistence.MiningSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 退役 region 的磁盘回收 (滑动重置的已知代价, 本类是它的收尾)。
 *
 * <b>为什么需要它</b>: 滑动 region 方案每次重置把实例挪到一块从未生成过的新坐标, 旧坐标那 16x16 = 256 个
 * 区块原样留在 .mca 文件里。不回收有两笔账: 一是每次重置泄漏一整块 region 的磁盘 (256 个区块 x 192 高的
 * 石头/深板岩), 二是 {@link MiningSavedData#allocateRegionOriginX} 的游标只增不回绕 (它明确拒绝复用旧坐标,
 * 因为旧坐标的区块还在盘上), 每次重置推进 REGION_SIZE_X + SLIDE_SEPARATION_BLOCKS = 1280 格, 撞到
 * MAX_REGION_WORLD_X 就直接抛异常、重置从此彻底不能用。
 *
 * <b>为什么不能删 .mca 文件</b>: 一个 .mca 覆盖 32x32 区块 (512x512 格), 而一块矿洞 region 只有 256x256 格,
 * 且 region 之间只隔 SLIDE_SEPARATION_BLOCKS —— 多块 region 会落在同一个 .mca 里。按文件删会连带毁掉邻居
 * region 的数据。故只能逐区块清: {@code ChunkStorage.write(pos, null)} 会走到 RegionFile.clear(pos),
 * 把该区块从 .mca 的扇区表里摘掉 (这条路径由 RetiredRegionGcGameTests 实测锁死, 不靠推断)。
 *
 * <b>安全前提, 每个区块单独判</b>:
 *  1. 该区块<b>当前未加载</b> —— 对加载中的区块清盘毫无意义, 它会在下一次保存时被原样写回, 白发一次 IO;
 *  2. 该区块<b>不在任何强加载票下</b> —— 滑动时旧 owner 的票已在换几何前撤掉 (见 InstanceManager.slideRegion
 *     的类注释), 这里再判一次是防御: 万一还有别的来源 (别的 mod / 残留票) 按着它, 跳过等下一轮。
 * 两条都不满足就跳过该区块并把它留在队列里, 下一轮再试 —— GC 是纯磁盘回收, 慢一点没有任何代价, 抢着清才有。
 *
 * <b>节流</b>: 每 {@value #GC_INTERVAL_TICKS} tick 清最多 {@value #CHUNKS_PER_PASS} 个区块。一块 region 共
 * 256 个, 故最快约 16 轮 / 80 秒清完一块。{@code ChunkStorage.write} 本身是投给 IOWorker 的异步写, 不阻塞
 * 主线程, 但一次投 256 个会让 IO 队列瞬间堆高, 故分批。数值写成常量而非配置键: 没有任何调参需求能证明它需要
 * 暴露, 而 IMiningConfig 每加一个键都要同步改三处测试替身。
 *
 * <b>本轮不做 (据实报备)</b>: 只清地形区块 (world/region/)。实体区块 (world/entities/) 的存储句柄挂在
 * {@code ServerLevel.entityManager} 这个私有字段上, 不反射/不 mixin 拿不到; 而本维度里它的体量极小
 * (每实例怪物硬上限 30, 且大多在玩家离开后就 despawn 了)。POI (world/poi/) 在本维度恒空 —— 没有村民、床不
 * 可用、没有任何 POI 方块。两者合计远小于地形, 留待后续需要时再单独处理。
 */
public final class RetiredRegionGc {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/reset/gc");

    /** GC 评估周期 (tick)。 */
    static final int GC_INTERVAL_TICKS = 100;

    /** 单轮最多清除的区块数。 */
    static final int CHUNKS_PER_PASS = 16;

    /** 一块 region 的区块数 (16x16)。 */
    static final int CHUNKS_PER_REGION =
            (MiningConstants.REGION_SIZE_X / 16) * (MiningConstants.REGION_SIZE_Z / 16);

    private final ServerLevel miningLevel;
    private final MiningSavedData savedData;

    public RetiredRegionGc(ServerLevel miningLevel, MiningSavedData savedData) {
        if (miningLevel == null || savedData == null) {
            throw new IllegalArgumentException("RetiredRegionGc requires both mining level and saved data");
        }
        this.miningLevel = miningLevel;
        this.savedData = savedData;
    }

    /** 由 ResetSystem 每 tick 调用; 内部按周期降频。 */
    public void tick(long gameTime) {
        if (gameTime % GC_INTERVAL_TICKS != 0L) {
            return;
        }
        runPass();
    }

    /**
     * 跑一轮回收: 取队首那块退役 region, 从游标处往后清最多 CHUNKS_PER_PASS 个区块。
     *
     * 只处理队首一块而不是遍历全队: 队列本就是先进先出的回收账, 逐块清完再出队, 一轮只碰一块能让
     * "每轮的 IO 上限" 严格等于 CHUNKS_PER_PASS, 不随队列长度膨胀 (积压十块时也不会一轮清 160 个)。
     *
     * @return 本轮实际清除的区块数 (供测试断言与日志)
     */
    int runPass() {
        var pending = savedData.retiredRegions();
        if (pending.isEmpty()) {
            return 0;
        }
        MiningSavedData.RetiredRegion region = pending.get(0);

        int cursor = region.clearedChunks();
        int cleared = 0;
        int skipped = 0;
        while (cursor < CHUNKS_PER_REGION && cleared < CHUNKS_PER_PASS) {
            ChunkPos pos = chunkAt(region, cursor);
            if (isBusy(pos)) {
                // 加载中或仍被票持有: 跳过但<b>不推进游标</b>, 下一轮从这里重试。
                skipped++;
                break;
            }
            miningLevel.getChunkSource().chunkMap.write(pos, null);
            cursor++;
            cleared++;
        }

        if (cursor != region.clearedChunks()) {
            savedData.advanceRetiredCursor(region, cursor);
        }
        if (cursor >= CHUNKS_PER_REGION) {
            savedData.dropRetiredRegion(region);
            LOGGER.info("[miningdim] retired region ({}, {}) fully reclaimed: {} chunks cleared from disk",
                    region.originX(), region.originZ(), CHUNKS_PER_REGION);
        } else if (cleared > 0) {
            LOGGER.debug("[miningdim] retired region ({}, {}) reclaim progress {}/{}",
                    region.originX(), region.originZ(), cursor, CHUNKS_PER_REGION);
        } else if (skipped > 0) {
            LOGGER.debug("[miningdim] retired region ({}, {}) stalled at chunk {} (still loaded or ticketed)",
                    region.originX(), region.originZ(), cursor);
        }
        return cleared;
    }

    /**
     * 游标序号 -> 区块坐标 (行主序铺满 region)。序号语义必须稳定: 它落盘了, 换一种铺法会让重启后接着清的
     * 位置对不上, 表现是漏清一部分而另一部分白清两遍。
     */
    ChunkPos chunkAt(MiningSavedData.RetiredRegion region, int index) {
        int chunksPerRow = MiningConstants.REGION_SIZE_X / 16;
        int baseChunkX = region.originX() >> 4;
        int baseChunkZ = region.originZ() >> 4;
        return new ChunkPos(baseChunkX + (index % chunksPerRow), baseChunkZ + (index / chunksPerRow));
    }

    /** 该区块此刻是否不宜清盘 (已加载, 或仍被强加载票持有)。 */
    private boolean isBusy(ChunkPos pos) {
        if (miningLevel.getChunkSource().hasChunk(pos.x, pos.z)) {
            return true;
        }
        return miningLevel.getForcedChunks().contains(pos.toLong());
    }
}
