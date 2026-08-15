package com.miningdim.reset;

import com.miningdim.core.Difficulty;
import com.miningdim.core.IResetService;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * F003 端到端回归锁 (D3 滑动 region): 证明一次真实重置之后, 实例真的换到了一块新的、与旧区
 * 不相交的世界坐标, 而不是只把 genState 翻一圈就地返回。
 *
 * 会真的把测试服的 Hard region 滑走并触发整块 region 的分帧强加载
 * ({@link com.miningdim.instance.GenerationScheduler} 每 tick 4 个区块), 故两条用例都把
 * timeoutTicks 放宽到 1200。
 *
 * 两条用例刻意分属两个不同 batch (reset_slide_new_seed / reset_slide_same_seed) 而非同一个 batch:
 * 本包同批次的其它 GameTest (如 MiningAdminWebUiGameTests 里同批次五个方法故意各挑一个不同难度实例)
 * 已证明同一 batch 内的多个 @GameTest 方法会在同一世界时刻并发起跑, 若两条用例挤在同一 batch 里
 * 争用同一个真实单例 Hard 实例, 用例二在用例一刚把 genState 翻成 RESETTING 但尚未真正滑动时就会
 * 同步调用 reset() 拿到 IllegalStateException 的 failedFuture, 必然误报。跨 batch 之间由 Forge
 * GameTest 框架顺序执行 (BeforeBatch/AfterBatch 语义), 借此保证用例二起跑时用例一已经跑完
 * (成功或超时收尾), 从而让"用例一已经搬过一次"这个用例二的前提成立。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ResetSlideGameTests {

    private static final String EMPTY = "empty";
    private static final int TIMEOUT_TICKS = 1200;

    // ============================================================
    // 用例一 (F003): NEW_SEED 重置必须真的搬家到新坐标, 且遗弃旧坐标上的玩家改动。
    // 把 ResetJob REGEN 阶段的 slideRegion 调用删掉 (退回空转) 后, regionBox 不变, 本用例必挂。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY,
            batch = "reset_slide_new_seed", timeoutTicks = TIMEOUT_TICKS)
    public static void newSeedResetRelocatesHardInstanceToNonIntersectingRegion(GameTestHelper helper) {
        InstanceState inst = requireSoleHardInstance(helper);
        ServerLevel miningLevel = requireMiningLevel(helper);

        RegionBox oldBox = inst.regionBox();
        long oldSeed = inst.seed();
        long id = inst.instanceId();

        // 在旧区中心落一个"玩家改动"的替身方块, 用来证明旧坐标被整体遗弃而非就地复用。
        BlockPos markerPos = new BlockPos(
                oldBox.originX() + oldBox.sizeX() / 2,
                miningLevel.getMinBuildHeight() + 8,
                oldBox.originZ() + oldBox.sizeZ() / 2);
        miningLevel.setBlockAndUpdate(markerPos, Blocks.DIAMOND_BLOCK.defaultBlockState());

        // 真放一个"活" mob 引用进 liveMobs (真实生产代码从不空转出这个 Set), 让下面的 isEmpty 断言
        // 具备判据: 删掉 ResetJob 的 instance.liveMobs().clear() 调用, 本用例必挂 (分支复核 finding #7,
        // 原断言在从未写入过 liveMobs 的情况下永远为真, 属弱校验)。
        UUID sentinelMob = UUID.randomUUID();
        inst.liveMobs().add(sentinelMob);

        // 直接挂一个重置监听器, 证明 fireInstanceReset 真的把这次滑动广播了出去 (分支复核 finding #7:
        // 原用例对此零覆盖, 删掉 MiningServices.fireInstanceReset 调用本用例原先照样全绿)。
        AtomicInteger resetBroadcastCount = new AtomicInteger(0);
        MiningServices.registerInstanceResetListener(broadcastId -> {
            if (broadcastId == id) {
                resetBroadcastCount.incrementAndGet();
            }
        });

        CompletableFuture<Void> resetFuture = MiningServices.resetService().reset(id, IResetService.ResetMode.NEW_SEED);

        helper.succeedWhen(() -> {
            helper.assertTrue(resetFuture.isDone(),
                    "NEW_SEED reset future must complete within " + TIMEOUT_TICKS + " ticks");
            helper.assertFalse(resetFuture.isCompletedExceptionally(),
                    "NEW_SEED reset future must not fail: " + describeFailure(resetFuture));

            RegionBox newBox = inst.regionBox();
            helper.assertTrue(
                    newBox.originX() >= oldBox.originX() + oldBox.sizeX() + MiningConstants.SLIDE_SEPARATION_BLOCKS,
                    "new region originX must clear old region + SLIDE_SEPARATION_BLOCKS ("
                            + MiningConstants.SLIDE_SEPARATION_BLOCKS + "); old=" + oldBox + " new=" + newBox);
            helper.assertTrue(!newBox.intersects(oldBox),
                    "new region must not intersect the abandoned old region; old=" + oldBox + " new=" + newBox);
            helper.assertTrue(!newBox.contains(markerPos.getX(), markerPos.getZ()),
                    "the player-made marker block's world coordinates must be left behind in the abandoned"
                            + " old region, not reachable inside the new region; marker=" + markerPos
                            + " newBox=" + newBox);
            helper.assertTrue(inst.seed() != oldSeed,
                    "NEW_SEED reset must derive a different seed; old=" + oldSeed + " new=" + inst.seed());
            helper.assertTrue(inst.genState().isEnterable(),
                    "instance must be enterable (READY/READY_FALLBACK) after a completed reset, got "
                            + inst.genState());
            helper.assertTrue(!inst.liveMobs().contains(sentinelMob) && inst.liveMobs().isEmpty(),
                    "SETTLE phase must clear liveMobs after reset (sentinel " + sentinelMob
                            + " must be gone), got " + inst.liveMobs().size() + " entr(y/ies)");
            helper.assertTrue(resetBroadcastCount.get() >= 1,
                    "slideRegion must broadcast MiningServices.fireInstanceReset for instance " + id
                            + " so trap/ore/spawn/pressure caches invalidate; observed broadcast count="
                            + resetBroadcastCount.get());
        });
    }

    // ============================================================
    // 用例二 (F003): D3 下 SAME_SEED 的重定义 —— 种子逐位不变, 但仍必须搬到新世界坐标 (地形因坐标
    // 变化必然不同)。这条断言把"SAME_SEED 也搬家"这个设计决策钉死: 谁把 SAME_SEED 悄悄改回原地
    // 不动 (originX 不再前进), 本用例必挂。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY,
            batch = "reset_slide_same_seed", timeoutTicks = TIMEOUT_TICKS)
    public static void sameSeedResetKeepsSeedButStillRelocatesToNewRegion(GameTestHelper helper) {
        // 以本用例起跑那一刻的实况为基准取 oldBox/oldSeed —— 前一个 batch (reset_slide_new_seed)
        // 已经顺序跑完并把这个 Hard 单例实例搬过一次。
        InstanceState inst = requireSoleHardInstance(helper);

        RegionBox oldBox = inst.regionBox();
        long oldSeed = inst.seed();
        long id = inst.instanceId();

        CompletableFuture<Void> resetFuture = MiningServices.resetService().reset(id, IResetService.ResetMode.SAME_SEED);

        helper.succeedWhen(() -> {
            helper.assertTrue(resetFuture.isDone(),
                    "SAME_SEED reset future must complete within " + TIMEOUT_TICKS + " ticks");
            helper.assertFalse(resetFuture.isCompletedExceptionally(),
                    "SAME_SEED reset future must not fail: " + describeFailure(resetFuture));

            helper.assertTrue(inst.seed() == oldSeed,
                    "SAME_SEED reset must keep the derived seed bit-for-bit identical; old=" + oldSeed
                            + " new=" + inst.seed());
            RegionBox newBox = inst.regionBox();
            helper.assertTrue(newBox.originX() > oldBox.originX(),
                    "SAME_SEED reset must still relocate to a strictly larger originX (D3: SAME_SEED only"
                            + " freezes mod-side layout params, not world coordinates); old=" + oldBox
                            + " new=" + newBox);
            helper.assertTrue(!newBox.intersects(oldBox),
                    "SAME_SEED reset must not reuse/overlap the abandoned old region; old=" + oldBox
                            + " new=" + newBox);
            helper.assertTrue(inst.genState().isEnterable(),
                    "instance must be enterable (READY/READY_FALLBACK) after a completed reset, got "
                            + inst.genState());
        });
    }

    // ============================================================
    // 共用取值助手
    // ============================================================

    /** 恰好一个 shared 的 HARD 实例; 数量不为 1 直接 helper.fail 报出真实数量, 不静默跳过。 */
    private static InstanceState requireSoleHardInstance(GameTestHelper helper) {
        List<InstanceState> hardInstances = new ArrayList<>();
        for (InstanceState candidate : MiningServices.instanceManager().snapshot()) {
            if (candidate.difficulty() == Difficulty.HARD && candidate.shared()) {
                hardInstances.add(candidate);
            }
        }
        if (hardInstances.size() != 1) {
            helper.fail("expected exactly one shared HARD instance, found " + hardInstances.size());
        }
        return hardInstances.get(0);
    }

    /** 矿山维度未加载直接 helper.fail 报出来, 不静默跳过。 */
    private static ServerLevel requireMiningLevel(GameTestHelper helper) {
        ServerLevel miningLevel = helper.getLevel().getServer().getLevel(MiningConstants.MINING_LEVEL);
        if (miningLevel == null) {
            helper.fail("mining dimension " + MiningConstants.MINING_LEVEL.location() + " is not loaded");
        }
        return miningLevel;
    }

    /** 把 reset future 的失败原因拼进断言消息, 而非只报 "completed exceptionally" 这种无诊断价值的文本。 */
    private static String describeFailure(CompletableFuture<Void> future) {
        if (!future.isCompletedExceptionally()) {
            return "(future has not failed)";
        }
        try {
            future.join();
            return "(unreachable: join() did not throw on an exceptionally-completed future)";
        } catch (CompletionException | CancellationException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            return cause.toString();
        }
    }
}
