package com.miningdim.chunk;

import com.miningdim.core.Difficulty;
import com.miningdim.core.GenState;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.RegionBox;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Set;

/**
 * 区块票生命周期回归 (设计文档 19.1 / R5)。覆盖:
 *  - F031 撤票必须带上加票时的 ticking 标志: {@link ChunkTicketManager#demoteToLoadOnly} 与
 *    {@link ChunkTicketManager#releaseAll} 若撤票时传错 ticking 常量, Forge {@code ForgeChunkManager.forceChunk}
 *    会打到另一张空表上静默 no-op, 旧票原样留在 {@code ForcedChunksSavedData} 里跨重启累积;
 *  - F021 无人在场时矿山维度不得有整块 region 规模的常驻强加载票: {@code ChunkSystem.tickInstance} 必须只在
 *    实例 active 且 playerSet 非空时才 refreshWindow, 不得对固定实例逐帧全 region force-tick。
 *
 * 两条用例分属不同 batch: 用例一会真的对矿山维度加/撤票, 与用例二读取的全局账本 {@code ForcedChunksSavedData}
 * 共享同一份存储, 挤在同一 batch 会互相污染计数 (Forge GameTest 同 batch 内多方法并发起跑)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChunkTicketLifecycleGameTests {

    private static final String EMPTY = "empty";

    /** 合成实例的哨兵 id, 只作本方法私有 {@link ChunkTicketManager} 的 map 键, 不进真实注册表, 不会与真实实例 id 撞。 */
    private static final long SYNTHETIC_INSTANCE_ID = -9999L;

    /** 一块从未被任何真实 region 使用过的区块对齐坐标 (真实 frontier 从 0 附近以 ~1280 步长递增, 远够不到这里)。 */
    private static final int SYNTHETIC_ORIGIN_X = 20_000_000;
    private static final int SYNTHETIC_ORIGIN_Z = 20_000_000;
    /** 2x2 区块 (32 = 2*16), 把这条用例真实产生的区块生成量压到最小。 */
    private static final int SYNTHETIC_SIZE = 32;

    // ============================================================
    // 用例一 (F031): demoteToLoadOnly / releaseAll 必须真的撤掉票, 不能撤错表变成净增票。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = "chunk_ticket_release")
    public static void demoteAndReleaseActuallyRevokeTicketsFromTheCorrectTable(GameTestHelper helper) {
        ServerLevel miningLevel = helper.getLevel().getServer().getLevel(MiningConstants.MINING_LEVEL);
        if (miningLevel == null) {
            helper.fail("矿山维度 miningdim:mining 缺失, 无法读取 ForcedChunksSavedData 账本");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }

        RegionBox syntheticBox = new RegionBox(SYNTHETIC_ORIGIN_X, MiningConstants.REGION_MIN_Y, SYNTHETIC_ORIGIN_Z,
                SYNTHETIC_SIZE, MiningConstants.REGION_HEIGHT, SYNTHETIC_SIZE);
        InstanceState syntheticInstance = new InstanceState(SYNTHETIC_INSTANCE_ID, 1L, Difficulty.HARD,
                syntheticBox, null, true, 0L, GenState.READY);

        int baseChunkX = SYNTHETIC_ORIGIN_X >> 4;
        int baseChunkZ = SYNTHETIC_ORIGIN_Z >> 4;
        Set<Long> chunks = new HashSet<>();
        chunks.add(ChunkPos.asLong(baseChunkX, baseChunkZ));
        chunks.add(ChunkPos.asLong(baseChunkX + 1, baseChunkZ));

        ChunkTicketManager mgr = new ChunkTicketManager(miningLevel);

        try {
            long[] baseline = readTicketCounts(miningLevel);
            long baselineTicking = baseline[0];
            long baselineLoadOnly = baseline[1];

            mgr.ensureTicking(syntheticInstance, chunks);
            long[] afterEnsure = readTicketCounts(miningLevel);
            helper.assertTrue(afterEnsure[0] == baselineTicking + 2,
                    "ensureTicking 必须以 ticking=true 加 2 张票, 基线=" + baselineTicking
                            + " 实测=" + afterEnsure[0]);

            mgr.demoteToLoadOnly(SYNTHETIC_INSTANCE_ID);
            long[] afterDemote = readTicketCounts(miningLevel);
            helper.assertTrue(afterDemote[0] == baselineTicking,
                    "F031: demoteToLoadOnly 必须真的把 ticking 票撤掉 (撤票须带 ticking=true 实参, 传错常量会打到"
                            + " 非 ticking 表上 no-op), 基线=" + baselineTicking + " 实测=" + afterDemote[0]);
            helper.assertTrue(afterDemote[1] == baselineLoadOnly + 2,
                    "demoteToLoadOnly 后 2 张票必须以非 ticking 形式持有, 基线=" + baselineLoadOnly
                            + " 实测=" + afterDemote[1]);

            mgr.releaseAll(SYNTHETIC_INSTANCE_ID);
            long[] afterRelease = readTicketCounts(miningLevel);
            helper.assertTrue(afterRelease[0] == baselineTicking && afterRelease[1] == baselineLoadOnly,
                    "releaseAll 必须把降级后的非 ticking 票也清零, 基线 ticking=" + baselineTicking
                            + "/loadOnly=" + baselineLoadOnly + " 实测 ticking=" + afterRelease[0]
                            + "/loadOnly=" + afterRelease[1]);

            // 短路径: ensureTicking 之后不经 demote 直接 releaseAll, 覆盖 releaseAll 自身按 forced 记录的
            // ticking 标志撤票这条路径 (与经 demote 后的非 ticking 撤票是两条不同代码路径)。
            mgr.ensureTicking(syntheticInstance, chunks);
            mgr.releaseAll(SYNTHETIC_INSTANCE_ID);
            long[] afterShortRelease = readTicketCounts(miningLevel);
            helper.assertTrue(afterShortRelease[0] == baselineTicking && afterShortRelease[1] == baselineLoadOnly,
                    "ensureTicking 后不经 demote 直接 releaseAll 也必须把 ticking 票清零, 基线 ticking="
                            + baselineTicking + "/loadOnly=" + baselineLoadOnly + " 实测 ticking="
                            + afterShortRelease[0] + "/loadOnly=" + afterShortRelease[1]);

            helper.succeed();
        } finally {
            // 兜底清理: 用例失败时也不给测试存档留下持久强加载票 (两个 ticking 标志各撤一遍, 无论实际持有哪种)。
            BlockPos owner = new BlockPos(SYNTHETIC_ORIGIN_X, MiningConstants.REGION_MIN_Y, SYNTHETIC_ORIGIN_Z);
            for (long key : chunks) {
                int cx = ChunkPos.getX(key);
                int cz = ChunkPos.getZ(key);
                ForgeChunkManager.forceChunk(miningLevel, MiningConstants.MODID, owner, cx, cz, false, true);
                ForgeChunkManager.forceChunk(miningLevel, MiningConstants.MODID, owner, cx, cz, false, false);
            }
        }
    }

    // ============================================================
    // 用例二 (F021): 无人在场时矿山维度不得有整块 region 规模的常驻强加载票。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = "chunk_no_region_preload")
    public static void miningDimensionHasNoResidentRegionScalePreloadWithoutPlayers(GameTestHelper helper) {
        ServerLevel miningLevel = helper.getLevel().getServer().getLevel(MiningConstants.MINING_LEVEL);
        if (miningLevel == null) {
            helper.fail("矿山维度 miningdim:mining 缺失, 无法读取 ForcedChunksSavedData 账本");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }

        long[] counts = readTicketCounts(miningLevel);
        long blockForcedTotal = counts[0] + counts[1];
        int regionChunkCount = (MiningConstants.REGION_SIZE_X / 16) * (MiningConstants.REGION_SIZE_Z / 16);

        // ChunkTicketManager 是全库唯一调用 ForgeChunkManager.forceChunk 的地方, 且只在
        // ChunkSystem.tickInstance 判定 inst.active() && !playerSet().isEmpty() 时才触发 refreshWindow;
        // 本 GameTest 全程未曾真的把玩家送进矿洞实例的 playerSet (entry 包里唯一命中真实
        // EntryGateway.requestEnter 接受路径的用例, 在其延迟一 tick 生效的传送回调触发前就把 mock 玩家从
        // PlayerList 摘除, 传送因此从未真正发生), 故实测应为 0。上界仍留 region 规模 (256) 而非硬编码
        // == 0, 是为了在全量 ~1000 用例的联合服务端会话里, 给尚未逐一审计到的其它批次留一点非零余量,
        // 同时仍能锁死 F021 描述的"整块 region 被逐帧强加载"这一类回归 (修复前三块固定实例共 768 个区块)。
        helper.assertTrue(blockForcedTotal < regionChunkCount,
                "F021 回归锁: 无人在场时矿山维度的强加载票总数必须远小于单个 region 的区块数; region 规模="
                        + regionChunkCount + " 实测 ticking+loadOnly=" + blockForcedTotal
                        + " (修复前三块固定实例逐帧全 region 强加载会得到 768)");

        helper.succeed();
    }

    // ============================================================
    // 共用取值助手
    // ============================================================

    /** 读取矿山维度 Forge 强加载账本, 返回 {ticking 票总数, 非 ticking(仅加载) 票总数}。 */
    private static long[] readTicketCounts(ServerLevel miningLevel) {
        ForcedChunksSavedData data = miningLevel.getDataStorage()
                .computeIfAbsent(ForcedChunksSavedData::load, ForcedChunksSavedData::new, "chunks");
        long ticking = 0L;
        for (LongSet chunkSet : data.getBlockForcedChunks().getTickingChunks().values()) {
            ticking += chunkSet.size();
        }
        long loadOnly = 0L;
        for (LongSet chunkSet : data.getBlockForcedChunks().getChunks().values()) {
            loadOnly += chunkSet.size();
        }
        return new long[] {ticking, loadOnly};
    }
}
