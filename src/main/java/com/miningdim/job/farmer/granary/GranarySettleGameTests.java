package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.freshness.FreshnessData;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 粮仓停表结算的 GameTest (docs/Spoilage_Granary_DesignSpec.md「停表规则」)。
 *
 * 设计文档把四组数字写死了, 这里逐条钉住: 存 21 小时取出停 0、存 25 小时停 24、存 168 小时停 167、
 * 存 600 小时封顶 504; 另外锁两条容易被写错的性质 —— 中途结算不重置计时 (只更新 C, 不动 E),
 * 以及每件 21 天的额度用完之后即使还在仓里也不再记。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranarySettleGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_settle";

    /** 每条用例占一条槽位 (同一批次的用例并行跑且共用区域, 摆方块必须错开)。 */
    private static final int SLOT = 8;

    private static final long ENTRY = 1_000_000L;   // 随意一个入仓小时序号

    @BeforeBatch(batch = BATCH)
    public static void resetBaseline(ServerLevel level) {
        // 本批不碰配置, 只借这个钩子把「批次起点」钉住 (与其它批次同一体例)。
    }

    // ============================================================
    // 四组钉死的数字
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void storedTwentyOneHoursCreditsNothing(GameTestHelper helper) {
        GranarySettle.Result result = GranarySettle.settle(ENTRY, 0, ENTRY + 21L, 0);
        helper.assertTrue(result.grantedHours() == 0, "不满 24 小时取出, 这次停表记 0");
        helper.assertTrue(result.creditedHours() == 0, "已计停表还是 0");
        helper.assertTrue(!result.credited(), "这一趟没记上东西");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void exactlyOneDayCreditsTwentyThreeHours(GameTestHelper helper) {
        // 存满 24 小时才起算, 入仓那一小时不计 —— 所以正好 24 小时记 23。
        helper.assertTrue(GranarySettle.settle(ENTRY, 0, ENTRY + 24L, 0).grantedHours() == 23,
                "正好存满一天记 23 小时");
        helper.assertTrue(GranarySettle.settle(ENTRY, 0, ENTRY + 25L, 0).grantedHours() == 24,
                "存 25 小时记 24");
        helper.assertTrue(GranarySettle.settle(ENTRY, 0, ENTRY + 168L, 0).grantedHours() == 167,
                "存 168 小时记 167");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void twentyOneDaysIsTheHardCap(GameTestHelper helper) {
        GranarySettle.Result result = GranarySettle.settle(ENTRY, 0, ENTRY + 600L, 0);
        helper.assertTrue(result.grantedHours() == 504, "存 600 小时封顶 504 (21 天)");
        helper.assertTrue(result.capReached(), "到顶要报出来");
        helper.assertTrue(GranarySettle.settle(ENTRY + 600L, 0, ENTRY + 900L, 0).grantedHours() == 299,
                "另起一趟 (换一个入仓时刻) 又是新的一段, 但物品自己的总额度仍然是 504");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void midwaySettlementKeepsTheOriginalEntryHour(GameTestHelper helper) {
        // 中途打开账台看一眼: 先结算到现在, E 不动。
        GranarySettle.Result first = GranarySettle.settle(ENTRY, 0, ENTRY + 100L, 0);
        helper.assertTrue(first.creditedHours() == 99, "第一趟记 99");
        GranarySettle.Result second = GranarySettle.settle(ENTRY, first.creditedHours(), ENTRY + 200L, 99);
        helper.assertTrue(second.creditedHours() == 199, "接着记到 199");
        // 与「中间一次都不看」的结果必须一致 —— 这就是「中途打开不重置」的判据。
        GranarySettle.Result straight = GranarySettle.settle(ENTRY, 0, ENTRY + 200L, 0);
        helper.assertTrue(second.creditedHours() == straight.creditedHours(),
                "分两次结算与一次结算结果必须相同, 实际 " + second.creditedHours() + " vs " + straight.creditedHours());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void itemCapAndEdgeCasesAreRespected(GameTestHelper helper) {
        // 物品已经有 500 小时额度: 这一趟最多再记 4 小时。
        GranarySettle.Result nearlyFull = GranarySettle.settle(ENTRY, 0, ENTRY + 600L, 500);
        helper.assertTrue(nearlyFull.grantedHours() == 4, "只按剩下的 4 小时额度记, 实际 " + nearlyFull.grantedHours());
        helper.assertTrue(nearlyFull.capReached(), "记满要报出来");

        // 额度用光: 什么也不记。
        GranarySettle.Result exhausted = GranarySettle.settle(ENTRY, 0, ENTRY + 600L, 504);
        helper.assertTrue(exhausted.grantedHours() == 0 && exhausted.capReached(),
                "额度用完就不该再记 (照常腐烂)");

        // 截止时刻早于入仓时刻 (时钟回拨/异常数据): 不记, 也不报错。
        helper.assertTrue(GranarySettle.settle(ENTRY, 0, ENTRY - 10L, 0).grantedHours() == 0,
                "截止时刻早于入仓时刻不记");
        helper.succeed();
    }

    // ============================================================
    // 方块实体上的账目
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void binCreditsStopHoursOntoTheItem(GameTestHelper helper) {
        GranaryBinBlockEntity bin = placeBin(helper, 0);
        ItemStack wheat = new ItemStack(Items.WHEAT, 4);
        FreshnessData.stamp(wheat, ENTRY * 60L);
        bin.setItem(0, wheat);
        bin.markStored(0, ENTRY);

        helper.assertTrue(bin.settleSlot(0, ENTRY + 25L) == 0, "还没启用的囤不停表");
        helper.assertTrue(FreshnessData.stoppedHours(bin.getItem(0)) == 0, "没停表就不该改物品");

        bin.setStopping(true);
        helper.assertTrue(bin.settleSlot(0, ENTRY + 25L) == 24, "启用后存 25 小时记 24");
        helper.assertTrue(FreshnessData.stoppedHours(bin.getItem(0)) == 24, "额度写在物品上");
        helper.assertTrue(bin.creditedHours(0) == 24, "格账同步记下");

        // 再看一眼: 不重复记。
        helper.assertTrue(bin.settleSlot(0, ENTRY + 25L) == 0, "同一时刻重复结算不重复记");
        helper.assertTrue(bin.settleSlot(0, ENTRY + 26L) == 1, "再过一小时记 1");

        // 东西取走: 账目清掉, 下次重新计。
        bin.markTaken(0);
        helper.assertTrue(bin.entryHour(0) == 0L && bin.creditedHours(0) == 0,
                "取走之后格账清空 (存一夜就取不算停表)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void binAccountsSurviveSaveAndLoad(GameTestHelper helper) {
        GranaryBinBlockEntity bin = placeBin(helper, 1);
        ItemStack wheat = new ItemStack(Items.WHEAT, 2);
        FreshnessData.stamp(wheat, ENTRY * 60L);
        bin.setItem(3, wheat);
        bin.markStored(3, ENTRY);
        bin.setStopping(true);
        bin.settleAll(ENTRY + 50L);
        int credited = bin.creditedHours(3);
        int itemHours = FreshnessData.stoppedHours(bin.getItem(3));
        helper.assertTrue(credited == 49 && itemHours == 49, "前提: 这一趟记了 49 小时");

        CompoundTag saved = bin.saveWithoutMetadata();
        GranaryBinBlockEntity reloaded = new GranaryBinBlockEntity(bin.getBlockPos(), bin.getBlockState());
        reloaded.load(saved);
        helper.assertTrue(reloaded.stopping(), "停表状态随存档");
        helper.assertTrue(reloaded.entryHour(3) == ENTRY, "入仓时刻随存档");
        helper.assertTrue(reloaded.creditedHours(3) == credited, "已计停表随存档");
        helper.assertTrue(FreshnessData.stoppedHours(reloaded.getItem(3)) == itemHours, "物品上的额度随存档");
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    private static GranaryBinBlockEntity placeBin(GameTestHelper helper, int slot) {
        BlockPos relative = new BlockPos(slot * SLOT + 1, 2, 1);
        helper.setBlock(relative, GranaryBlocks.BIN.get());
        GranaryBinBlockEntity bin = (GranaryBinBlockEntity) helper.getBlockEntity(relative);
        if (bin == null) {
            throw new IllegalStateException("储粮囤方块实体没建起来");
        }
        return bin;
    }
}
