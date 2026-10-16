package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;

/**
 * 统计环、标签自检与 {@code /freshness} 命令的 GameTest
 * (docs/Spoilage_Granary_DesignSpec.md「物品数据与存档」「安全阀」「界面、提示与命令」)。
 *
 * 三件事分别对应: 试运行期要有可观测的计数 (盖章 / 本该腐烂 / 真换掉), 标签坏了要能自动退回试运行,
 * 以及 OP 得有命令能查状态、导清单、给奖励模板去章。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessStatsGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "freshness_stats";

    private static final long NOW = 500_000L * 60L;

    /** 跨轮基线: 本批会动进程级计数器, 先清零, 免得上一轮残留把断言带红。 */
    @BeforeBatch(batch = BATCH)
    public static void resetCounters(ServerLevel level) {
        FreshnessStats.resetForTest();
    }

    // ============================================================
    // 计数器与统计环
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void countersPeekThenDrain(GameTestHelper helper) {
        FreshnessStats.resetForTest();
        FreshnessStats.recordStamped();
        FreshnessStats.recordStamped();
        FreshnessStats.recordWouldRot();
        FreshnessStats.recordRotted();
        FreshnessStats.recordRotted();
        FreshnessStats.recordRotted();

        FreshnessStats.Snapshot peeked = FreshnessStats.peek();
        helper.assertTrue(peeked.stamped() == 2L && peeked.wouldRot() == 1L && peeked.rotted() == 3L,
                "看一眼不清零");
        FreshnessStats.Snapshot drained = FreshnessStats.drain();
        helper.assertTrue(drained.equals(peeked), "排空拿到的就是刚才那一份");
        helper.assertTrue(FreshnessStats.peek().isEmpty(), "排空之后归零");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ringTotalsRespectTheWindowAndWrapAround(GameTestHelper helper) {
        FreshnessStatsRing ring = new FreshnessStatsRing();
        ring.record(1_000L, 5L, 0L, 0L);
        ring.record(1_001L, 0L, 3L, 0L);
        ring.record(1_002L, 1L, 1L, 1L);

        FreshnessStats.Snapshot all = ring.totals(1_000L);
        helper.assertTrue(all.stamped() == 6L && all.wouldRot() == 4L && all.rotted() == 1L,
                "整段汇总");
        FreshnessStats.Snapshot recent = ring.totals(1_002L);
        helper.assertTrue(recent.stamped() == 1L && recent.wouldRot() == 1L && recent.rotted() == 1L,
                "只汇总窗口内的小时");
        helper.assertTrue(ring.newestHour() == 1_002L, "最新一格是 1002");
        helper.assertTrue(ring.filledHours() == 3, "用了 3 格");

        // 转一圈: 30 天后的同一位置覆盖掉最老的那一格。
        long wrapped = 1_000L + FreshnessStatsRing.HOURS_RETAINED;
        ring.record(wrapped, 9L, 0L, 0L);
        FreshnessStats.Snapshot afterWrap = ring.totals(1_000L);
        helper.assertTrue(afterWrap.stamped() == 10L,
                "老格被覆盖后不再计入 (5 变成 9, 合计 9+1)");
        helper.assertTrue(ring.filledHours() == 3, "覆盖不增加格数");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ringSurvivesSaveAndLoad(GameTestHelper helper) {
        FreshnessStatsRing ring = new FreshnessStatsRing();
        ring.record(2_000L, 7L, 2L, 1L);
        FreshnessStatsRing loaded = FreshnessStatsRing.load(ring.save());
        helper.assertTrue(loaded.totals(2_000L).equals(ring.totals(2_000L)), "存读一轮数据不变");

        FreshnessStatsRing fromOldSave = FreshnessStatsRing.load(new CompoundTag());
        helper.assertTrue(fromOldSave.filledHours() == 0, "旧存档没有统计环时从空环开始, 不报错");

        CompoundTag broken = new CompoundTag();
        broken.putLongArray("Hours", new long[]{1L, 2L});
        boolean threw = false;
        try {
            FreshnessStatsRing.load(broken);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw, "列长不对要直接报错, 不能悄悄吞掉");
        helper.succeed();
    }

    // ============================================================
    // 标签自检
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void selfCheckPassesWithTheShippedTags(GameTestHelper helper) {
        List<String> problems = FreshnessSelfCheck.verify(
                FreshnessCategories::isExempt, FreshnessCategories::categoryOf);
        helper.assertTrue(problems.isEmpty(),
                "出厂标签必须让八条自检全过, 现在的问题: " + String.join("; ", problems));
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void selfCheckReportsBrokenTags(GameTestHelper helper) {
        // 假的豁免判定: 只认蜂蜜瓶 —— 腐肉/蜘蛛眼/金苹果三条应当报出来。
        List<String> exemptProblems = FreshnessSelfCheck.verify(
                stack -> stack.is(Items.HONEY_BOTTLE),
                FreshnessCategories::categoryOf);
        helper.assertTrue(exemptProblems.stream().anyMatch(text -> text.contains("腐肉")),
                "腐肉不再豁免必须报出来");
        helper.assertTrue(exemptProblems.stream().anyMatch(text -> text.contains("蜘蛛眼")),
                "蜘蛛眼不再豁免必须报出来");
        helper.assertTrue(exemptProblems.stream().anyMatch(text -> text.contains("金苹果")),
                "金苹果不再豁免必须报出来");

        // 假的类别判定: 生牛肉取不到类别 —— 也该报出来 (这条对应「标签整张加载失败」)。
        List<String> categoryProblems = FreshnessSelfCheck.verify(
                FreshnessCategories::isExempt,
                stack -> stack.is(Items.BEEF) ? Optional.empty() : FreshnessCategories.categoryOf(stack));
        helper.assertTrue(categoryProblems.stream().anyMatch(text -> text.contains("生牛肉")),
                "生牛肉不腐烂必须报出来");
        helper.succeed();
    }

    // ============================================================
    // 结算计数 + 命令
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void settleFeedsTheStatsCounters(GameTestHelper helper) {
        FreshnessStats.resetForTest();

        FreshnessSettle.settleSlot(new ItemStack(Items.BEEF), NOW, false, null);
        FreshnessStats.Snapshot afterStamp = FreshnessStats.peek();
        helper.assertTrue(afterStamp.stamped() == 1L, "盖章计入 stamped");

        FreshnessSettle.settleSlot(ancientBeef(), NOW, false, null);
        FreshnessStats.Snapshot afterDryRun = FreshnessStats.peek();
        helper.assertTrue(afterDryRun.wouldRot() == 1L,
                "试运行只统计「本该腐烂多少件」, 不动东西");

        ItemStack live = ancientBeef();
        ItemStack replacement = FreshnessSettle.settleSlot(live, NOW, true, null);
        helper.assertTrue(!replacement.isEmpty(), "开启档真的换掉");
        helper.assertTrue(FreshnessStats.peek().rotted() == 1L, "真换掉计入 rotted");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void freshnessCommandsAreRegisteredAndRunnable(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var source = server.createCommandSourceStack();
        helper.assertTrue(server.getCommands().performPrefixedCommand(source, "freshness") == 1,
                "/freshness 应当可执行 (档位摘要)");
        helper.assertTrue(server.getCommands().performPrefixedCommand(source, "freshness stats") == 1,
                "/freshness stats 应当可执行");
        helper.assertTrue(server.getCommands().performPrefixedCommand(source, "freshness clock") == 1,
                "/freshness clock 应当可执行");
        helper.assertTrue(server.getCommands().performPrefixedCommand(source, "freshness dump") == 1,
                "/freshness dump 应当能把清单写出来");
        helper.succeed();
    }

    private static ItemStack ancientBeef() {
        ItemStack stack = new ItemStack(Items.BEEF);
        FreshnessData.stampAt(stack, NOW - 2L * 365L * 24L * 60L, 0);
        return stack;
    }
}
