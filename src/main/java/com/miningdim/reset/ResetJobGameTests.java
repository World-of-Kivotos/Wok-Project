package com.miningdim.reset;

import com.miningdim.core.Difficulty;
import com.miningdim.core.GenState;
import com.miningdim.core.IResetService;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.RegionBox;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@link ResetJob} 物理重生成状态机 GameTest (设计文档第十三章; TDD: 删被测核心逻辑对应用例必挂)。
 *
 * 文件级区块删除本体 (真 ChunkMap / RegionFileStorage / EntityStorage) 只能真服验, 但状态机必须锁死:
 * 用记录型 {@link ResetChunkOps} 替身注入 ResetJob, 逐 tick 驱动并断言具体业务结果 ——
 *  - 阶段推进顺序 (UNLOAD 先释放票/清陷阱, 再 AWAIT, 卸载确认后才 flush+删除, 删除后再 flush);
 *  - AWAIT_UNLOAD 卸载不完在超时后转 FAILED, 且 PURGE 一步不做 (flush/delete 零调用);
 *  - PURGE 对恰好 256 个 region chunk 逐一调删除 (坐标集合精确断言);
 *  - 陷阱注册表清理仍发生 (clearTrapRegistry 恰调一次);
 *  - 完成后 genState=READY 且 completion 正常兑现。
 *
 * 全程内存构造 (InstanceState / ResetJob / 替身), 不依赖真服务端; helper 仅用于 succeed/assert。template = "empty"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ResetJobGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "reset";

    /** 默认 region (256x256 -> 16x16=256 chunk); origin (0,0)。 */
    private static InstanceState newResettingInstance() {
        return new InstanceState(1L, 42L, Difficulty.EASY, RegionBox.ofDefault(0, 0),
                UUID.randomUUID(), true, 0L, GenState.RESETTING);
    }

    /** region (0,0) 覆盖的全部 chunk 打包键 (16x16=256); 与 ResetJob.doPurge 的枚举必须一致。 */
    private static Set<Long> expectedRegionChunks() {
        Set<Long> expected = new HashSet<>();
        for (int cx = 0; cx <= 15; cx++) {
            for (int cz = 0; cz <= 15; cz++) {
                expected.add(ChunkPos.asLong(cx, cz));
            }
        }
        return expected;
    }

    /** 记录型 ResetChunkOps 替身: 记录调用序列/次数/删除坐标, 并可控 allChunksUnloaded 的返回。 */
    private static final class RecordingOps implements ResetChunkOps {
        final List<String> log = new ArrayList<>();
        final Set<Long> deletedChunks = new HashSet<>();
        int releaseCalls = 0;
        int clearTrapCalls = 0;
        int flushCalls = 0;
        int deleteCalls = 0;
        /** allChunksUnloaded 前若干次返回 false, 之后返回 true; Integer.MAX_VALUE 表示永不卸载 (超时用例)。 */
        private final int falsePollsBeforeUnloaded;
        private int polls = 0;

        RecordingOps(int falsePollsBeforeUnloaded) {
            this.falsePollsBeforeUnloaded = falsePollsBeforeUnloaded;
        }

        @Override
        public void releaseTickets() {
            releaseCalls++;
            log.add("release");
        }

        @Override
        public int clearTrapRegistry() {
            clearTrapCalls++;
            log.add("clearTrap");
            return 7; // 非零回值, 供上层日志/未来断言; 此处 ResetJob 只做日志
        }

        @Override
        public boolean allChunksUnloaded() {
            return polls++ >= falsePollsBeforeUnloaded;
        }

        @Override
        public void flushPendingWrites() {
            flushCalls++;
            log.add("flush");
        }

        @Override
        public void deleteChunk(int chunkX, int chunkZ) {
            deleteCalls++;
            deletedChunks.add(ChunkPos.asLong(chunkX, chunkZ));
            log.add("delete");
        }
    }

    /** tick 到终态 (返回 true) 或超守卫上限; 返回实际推进的 tick 数。 */
    private static int driveToEnd(GameTestHelper helper, ResetJob job) {
        int ticks = 0;
        while (!job.tick()) {
            if (++ticks > 1000) {
                helper.fail("reset job did not terminate within 1000 ticks");
                return ticks;
            }
        }
        return ticks;
    }

    // ============================================================
    // 正常路径: UNLOAD -> AWAIT(先 false 一次再 true) -> PURGE(删 256) -> SETTLE -> READY
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void happyPathPurges256ChunksAndReachesReady(GameTestHelper helper) {
        InstanceState inst = newResettingInstance();
        RecordingOps ops = new RecordingOps(1); // AWAIT 先返回一次 false, 第二次 true (真走一遍轮询循环)
        ResetJob job = new ResetJob(inst, ops, IResetService.ResetMode.NEW_SEED, 3);

        driveToEnd(helper, job);

        // 终态: READY + completion 正常兑现 (非异常)。
        helper.assertTrue(inst.genState() == GenState.READY,
                "instance must be READY after reset (was " + inst.genState() + ")");
        helper.assertTrue(job.completion().isDone() && !job.completion().isCompletedExceptionally(),
                "completion must complete normally on success");

        // UNLOAD 阶段各调一次。
        helper.assertTrue(ops.releaseCalls == 1, "releaseTickets must be called exactly once (was " + ops.releaseCalls + ")");
        helper.assertTrue(ops.clearTrapCalls == 1, "clearTrapRegistry must be called exactly once (was " + ops.clearTrapCalls + ")");

        // PURGE: 删除恰好 region 的 256 个 chunk, 坐标集合精确匹配 (删空 doPurge 循环 -> 此断言必挂)。
        helper.assertTrue(ops.deleteCalls == 256, "deleteChunk must be called exactly 256 times (was " + ops.deleteCalls + ")");
        helper.assertTrue(ops.deletedChunks.equals(expectedRegionChunks()),
                "deleted chunk coordinate set must equal the region's 16x16 chunks");

        // PURGE flush 前后各一次 (共 2)。
        helper.assertTrue(ops.flushCalls == 2, "flushPendingWrites must be called twice (before+after delete), was " + ops.flushCalls);

        // 阶段顺序: release/clearTrap 在删除前; flush 在首个删除前; 末次 flush 在末次删除后。
        int firstDelete = ops.log.indexOf("delete");
        int lastDelete = ops.log.lastIndexOf("delete");
        int firstFlush = ops.log.indexOf("flush");
        int lastFlush = ops.log.lastIndexOf("flush");
        int release = ops.log.indexOf("release");
        int clearTrap = ops.log.indexOf("clearTrap");
        helper.assertTrue(release == 0, "releaseTickets must be the very first op");
        helper.assertTrue(release < clearTrap && clearTrap < firstDelete, "UNLOAD (release then clearTrap) must precede any delete");
        helper.assertTrue(firstFlush >= 0 && firstFlush < firstDelete, "a flush must precede the first delete");
        helper.assertTrue(lastFlush > lastDelete, "a flush must follow the last delete (persist the purge)");

        helper.succeed();
    }

    // ============================================================
    // 超时路径: 区块永不卸载 -> AWAIT_UNLOAD 超时 -> FAILED, PURGE 一步不做
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void awaitUnloadTimeoutFailsWithoutPurge(GameTestHelper helper) {
        InstanceState inst = newResettingInstance();
        RecordingOps ops = new RecordingOps(Integer.MAX_VALUE); // 永不卸载完
        ResetJob job = new ResetJob(inst, ops, IResetService.ResetMode.NEW_SEED, 1);

        int ticks = driveToEnd(helper, job);

        // 终态: FAILED + completion 异常兑现 (删超时守卫 -> 死循环撞守卫 helper.fail -> 本用例必挂)。
        helper.assertTrue(inst.genState() == GenState.FAILED,
                "instance must be FAILED on unload timeout (was " + inst.genState() + ")");
        helper.assertTrue(job.completion().isCompletedExceptionally(),
                "completion must complete exceptionally on timeout");

        // UNLOAD 仍发生 (释放票/清陷阱), 但 PURGE 一步不做: 无 flush、无 delete。
        helper.assertTrue(ops.releaseCalls == 1, "releaseTickets still runs in UNLOAD before await");
        helper.assertTrue(ops.clearTrapCalls == 1, "clearTrapRegistry still runs in UNLOAD before await");
        helper.assertTrue(ops.flushCalls == 0, "PURGE must not run on timeout (no flush)");
        helper.assertTrue(ops.deleteCalls == 0, "PURGE must not run on timeout (no deleteChunk)");

        // 超时应在约 300 tick 卸载窗口 + UNLOAD 一帧后触发 (证明确有卸载等待而非立即失败)。
        helper.assertTrue(ticks >= 300, "timeout must occur only after the ~300-tick await window, not immediately (ticks=" + ticks + ")");

        helper.succeed();
    }
}
