package com.miningdim.reset;

import com.miningdim.core.MiningConstants;
import com.miningdim.persistence.MiningSavedData;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;

/**
 * 退役 region 磁盘回收的回归 (滑动重置的收尾)。
 *
 * 第一条用例是本文件的地基, 也是整个 GC 唯一的物理前提: <b>{@code ChunkStorage.write(pos, null)} 真的会把
 * 区块从 .mca 里摘掉</b>。这条不能靠读原版源码推断 —— 推断错了整个 GC 就是每 100 tick 空转一次却报告"已回收",
 * 是最难发现的那种假绿。故直接写入、再读回、断言读到的是 empty。
 *
 * 其余用例锁账本推进的正确性: 游标必须落盘 (重启接着清)、清满即出队、被加载的区块必须跳过且<b>不推进游标</b>。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class RetiredRegionGcGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "region_gc";

    /**
     * 物理前提: 往区块存储写 null 之后, 该区块必须从存储里消失。
     *
     * 取一块远离测试结构的坐标自己造数据再删, 不碰任何真实区域。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void writingNullRemovesTheChunkFromStorage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos scratch = new ChunkPos(1_500_000 >> 4, 1_500_000 >> 4);

        // 先塞一份可辨识的最小 NBT, 确认存储里确实有东西 (否则下面的"删掉了"无从区分是删成了还是本来就空)。
        CompoundTag stub = new CompoundTag();
        stub.putInt("miningdimGcProbe", 1);
        level.getChunkSource().chunkMap.write(scratch, stub);
        level.getChunkSource().chunkMap.flushWorker();

        Optional<CompoundTag> beforeClear = level.getChunkSource().chunkMap.read(scratch).join();
        helper.assertTrue(beforeClear.isPresent(),
                "前置条件: 写入的探针 NBT 必须能读回来, 否则本用例无法区分'删掉了'与'本来就空'");

        level.getChunkSource().chunkMap.write(scratch, null);
        level.getChunkSource().chunkMap.flushWorker();

        Optional<CompoundTag> afterClear = level.getChunkSource().chunkMap.read(scratch).join();
        helper.assertTrue(afterClear.isEmpty(),
                "write(pos, null) 必须把区块从 .mca 摘掉 —— 这是整个 GC 的物理前提, 实测读回 " + afterClear);
        helper.succeed();
    }

    /**
     * <b>runPass 必须真的把区块从磁盘上清掉</b>, 而不只是推进账本。
     *
     * 这条是变异实测补出来的: 只有上面那条"写 null 能删"(直接调 chunkMap.write, 测的是平台能力) 加下面那些
     * 队列用例 (坐标上本来就没有区块, 只断言游标与出队) 时, 把 runPass 里那行 write 换成空操作能让 1275 条
     * <b>全绿</b> —— GC 每 100 tick 空转一次却照样报告"已回收", 是最难发现的那种假绿。
     *
     * 故这里在退役 region 内部真写一份探针 NBT, 跑一轮, 再读回断言它没了。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void runPassActuallyClearsChunksFromDisk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MiningSavedData scratch = new MiningSavedData();
        int originX = 9_000_000;
        scratch.retireRegion(originX, 0);
        MiningSavedData.RetiredRegion region = scratch.retiredRegions().get(0);
        RetiredRegionGc gc = new RetiredRegionGc(level, scratch);

        // 探针放在游标序号 0 那个区块 —— 它必定落在本轮 (前 CHUNKS_PER_PASS 个) 的清除范围内。
        ChunkPos probePos = gc.chunkAt(region, 0);
        CompoundTag probe = new CompoundTag();
        probe.putInt("miningdimGcProbe", 42);
        level.getChunkSource().chunkMap.write(probePos, probe);
        level.getChunkSource().chunkMap.flushWorker();
        helper.assertTrue(level.getChunkSource().chunkMap.read(probePos).join().isPresent(),
                "前置条件: 探针必须先真的在盘上, 否则本用例证明不了任何事");

        int cleared = gc.runPass();
        level.getChunkSource().chunkMap.flushWorker();

        helper.assertTrue(cleared == RetiredRegionGc.CHUNKS_PER_PASS,
                "本轮应清 " + RetiredRegionGc.CHUNKS_PER_PASS + " 个, 实得 " + cleared);
        Optional<CompoundTag> after = level.getChunkSource().chunkMap.read(probePos).join();
        helper.assertTrue(after.isEmpty(),
                "跑过一轮 GC 之后, 退役 region 内的探针区块必须已从盘上消失 —— 实测仍读得到 " + after
                        + " (GC 只动了账本没动磁盘)");
        helper.succeed();
    }

    /**
     * 一块 region 的区块总数必须与几何一致 (16x16=256)。
     *
     * 这个数是落盘游标的量纲: 它错了会让 GC 提前出队 (漏清) 或永远清不完 (队首卡死, 后面的永不回收)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chunksPerRegionMatchesGeometry(GameTestHelper helper) {
        int expected = (MiningConstants.REGION_SIZE_X / 16) * (MiningConstants.REGION_SIZE_Z / 16);
        helper.assertTrue(RetiredRegionGc.CHUNKS_PER_REGION == expected && expected == 256,
                "一块 region 应为 256 个区块 (256x256 格), 实得 " + RetiredRegionGc.CHUNKS_PER_REGION);
        helper.succeed();
    }

    /**
     * 游标序号到区块坐标的映射必须稳定且铺满整块 region, 不重不漏。
     *
     * 序号是落盘的, 换一种铺法就会让重启后接着清的位置对不上 (一部分漏清, 另一部分白清两遍)。这里逐个枚举
     * 全部 256 个序号, 断言得到 256 个互不相同的坐标, 且全部落在 region 盒内。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cursorMappingTilesTheRegionExactlyOnce(GameTestHelper helper) {
        MiningSavedData scratch = new MiningSavedData();
        scratch.retireRegion(4096, 0);
        MiningSavedData.RetiredRegion region = scratch.retiredRegions().get(0);
        RetiredRegionGc gc = new RetiredRegionGc(helper.getLevel(), scratch);

        java.util.Set<Long> seen = new java.util.HashSet<>();
        int baseChunkX = 4096 >> 4;
        int baseChunkZ = 0;
        int chunksPerRow = MiningConstants.REGION_SIZE_X / 16;
        for (int i = 0; i < RetiredRegionGc.CHUNKS_PER_REGION; i++) {
            ChunkPos pos = gc.chunkAt(region, i);
            helper.assertTrue(seen.add(pos.toLong()), "序号 " + i + " 映射到了重复坐标 " + pos);
            helper.assertTrue(pos.x >= baseChunkX && pos.x < baseChunkX + chunksPerRow
                            && pos.z >= baseChunkZ && pos.z < baseChunkZ + chunksPerRow,
                    "序号 " + i + " 映射出了 region 盒外的坐标 " + pos);
        }
        helper.assertTrue(seen.size() == 256, "256 个序号必须铺出 256 个不同区块, 实得 " + seen.size());
        helper.succeed();
    }

    /**
     * 队列账本: 登记 -> 分批清 -> 清满出队, 且游标单调推进。
     *
     * 用远离一切真实区域的坐标, 那里的区块本就不存在也未加载, 故每轮都能满额清 CHUNKS_PER_PASS 个
     * (write(pos, null) 对本就不存在的区块是无害空操作)。断言重点在账本, 不在磁盘。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void queueDrainsInBatchesThenPops(GameTestHelper helper) {
        MiningSavedData scratch = new MiningSavedData();
        scratch.retireRegion(2_000_000, 0);
        RetiredRegionGc gc = new RetiredRegionGc(helper.getLevel(), scratch);

        helper.assertTrue(scratch.retiredRegions().size() == 1, "登记后队列应有 1 块");

        int firstPass = gc.runPass();
        helper.assertTrue(firstPass == RetiredRegionGc.CHUNKS_PER_PASS,
                "首轮应清满 " + RetiredRegionGc.CHUNKS_PER_PASS + " 个, 实得 " + firstPass);
        helper.assertTrue(scratch.retiredRegions().get(0).clearedChunks() == RetiredRegionGc.CHUNKS_PER_PASS,
                "游标必须推进到 " + RetiredRegionGc.CHUNKS_PER_PASS
                        + ", 实得 " + scratch.retiredRegions().get(0).clearedChunks());

        // 再跑到清完为止 (总 256 个, 每轮 16 个 -> 还需 15 轮)。多跑一轮验证空队列时是无害的 0。
        int passes = 1;
        while (!scratch.retiredRegions().isEmpty() && passes < 64) {
            gc.runPass();
            passes++;
        }
        helper.assertTrue(scratch.retiredRegions().isEmpty(),
                "清满 256 个区块后该块必须出队, 实测仍在队列里 (跑了 " + passes + " 轮)");
        helper.assertTrue(passes == RetiredRegionGc.CHUNKS_PER_REGION / RetiredRegionGc.CHUNKS_PER_PASS,
                "256 个区块按每轮 16 个应恰好 16 轮清完, 实得 " + passes + " 轮");
        helper.assertTrue(gc.runPass() == 0, "空队列时 runPass 必须是无害的 0");
        helper.succeed();
    }

    /**
     * 被强加载票按住的区块必须跳过, 且<b>不推进游标</b> —— 下一轮要从同一个位置重试。
     *
     * 推进了游标就等于把这个区块永久跳过了: 它是退役区域, 之后再没有任何东西会回来清它, 磁盘就永久泄漏。
     * 删掉 isBusy 的票判据会让本用例挂在"游标不许推进"上。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ticketedChunkIsSkippedWithoutAdvancingCursor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int originX = 3_000_000;
        MiningSavedData scratch = new MiningSavedData();
        scratch.retireRegion(originX, 0);
        MiningSavedData.RetiredRegion region = scratch.retiredRegions().get(0);
        RetiredRegionGc gc = new RetiredRegionGc(level, scratch);

        // 给队首那块 region 的第 0 个区块加一张强加载票, 模拟"还有东西按着它"。
        ChunkPos head = gc.chunkAt(region, 0);
        level.setChunkForced(head.x, head.z, true);
        try {
            helper.assertTrue(level.getForcedChunks().contains(head.toLong()),
                    "前置条件: 强加载票必须真的挂上了");

            int cleared = gc.runPass();
            helper.assertTrue(cleared == 0,
                    "队首区块被票按住时本轮不该清任何区块, 实得清了 " + cleared + " 个");
            helper.assertTrue(region.clearedChunks() == 0,
                    "被跳过时游标必须留在原地 (否则该区块永久漏清), 实得游标 " + region.clearedChunks());
            helper.assertTrue(scratch.retiredRegions().size() == 1,
                    "没清完不许出队");

            /*
             * 同期对照, 证明上面那个 0 确实是"被按住"导致的而不是别的原因把整条路堵死了: 另起一块坐标完全
             * 不同、既未加载也无票的退役 region, 同一份 GC 逻辑必须满额清除。
             *
             * 这里刻意<b>不</b>写成"撤票后再跑一轮就该清" —— 撤票不等于立刻卸载, 区块还在内存里时 isBusy
             * 仍然为真, 跳过才是对的; 那种写法依赖原版的卸载时序, 是条会随时间飘的假断言 (本用例第一版就是
             * 这么写的, 实测挂在这里)。
             */
            MiningSavedData control = new MiningSavedData();
            control.retireRegion(originX + 4 * MiningConstants.REGION_SIZE_X, 0);
            int controlCleared = new RetiredRegionGc(level, control).runPass();
            helper.assertTrue(controlCleared == RetiredRegionGc.CHUNKS_PER_PASS,
                    "对照组 (无票未加载) 必须满额清除 " + RetiredRegionGc.CHUNKS_PER_PASS
                            + " 个, 实得 " + controlCleared + " —— 若这里也是 0, 说明卡住的不是票判据");
        } finally {
            level.setChunkForced(head.x, head.z, false);
        }
        helper.succeed();
    }

    /**
     * 同坐标重复登记必须幂等: 否则同一块区域会在队列里出现两次, 第二遍全是空操作但白发一轮 IO,
     * 且第二遍的游标从 0 起会让日志报告一次假的"已回收"。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void retiringSameOriginTwiceIsIdempotent(GameTestHelper helper) {
        MiningSavedData scratch = new MiningSavedData();
        scratch.retireRegion(5_000_000, 0);
        scratch.retireRegion(5_000_000, 0);
        helper.assertTrue(scratch.retiredRegions().size() == 1,
                "同坐标重复登记必须只留一条, 实得 " + scratch.retiredRegions().size());
        scratch.retireRegion(5_000_000 + MiningConstants.REGION_SIZE_X, 0);
        helper.assertTrue(scratch.retiredRegions().size() == 2,
                "不同坐标必须各占一条, 实得 " + scratch.retiredRegions().size());
        helper.succeed();
    }

    /**
     * 队列必须随存档往返 (重启后接着清)。
     *
     * 只在内存里跟踪的话, 重启就把整个待回收账丢了 —— 那些区块从此没有任何东西会回来清, 是永久泄漏。
     * 游标也必须一起往返, 否则重启后从 0 重清已清过的部分 (白发 IO) 。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void queueSurvivesSaveLoadRoundTripWithCursor(GameTestHelper helper) {
        MiningSavedData original = new MiningSavedData();
        original.retireRegion(7_000_000, 0);
        original.advanceRetiredCursor(original.retiredRegions().get(0), 48);

        MiningSavedData reloaded = MiningSavedData.load(original.save(new CompoundTag()));

        helper.assertTrue(reloaded.retiredRegions().size() == 1,
                "待回收队列必须随存档往返, 实得 " + reloaded.retiredRegions().size() + " 条");
        MiningSavedData.RetiredRegion region = reloaded.retiredRegions().get(0);
        helper.assertTrue(region.originX() == 7_000_000 && region.originZ() == 0,
                "坐标必须原样往返, 实得 (" + region.originX() + ", " + region.originZ() + ")");
        helper.assertTrue(region.clearedChunks() == 48,
                "游标必须一起往返 (否则重启后从 0 重清), 实得 " + region.clearedChunks());
        helper.succeed();
    }
}
