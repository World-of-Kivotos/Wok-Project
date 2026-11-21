package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 同种食物合并的 GameTest (docs/Spoilage_Granary_DesignSpec.md「同种食物合并」、反漏洞 #50/#52/#53)。
 *
 * 合并核心不依赖时钟 (只在「有效批次」上算), 所以这里用一个固定时刻构造夹具、再用同一个时刻读回,
 * 断言的都是相对量与守恒量:
 * <ul>
 *   <li>加权平均: 合并后的剩余正好是两边按件数加权的结果, 且「件数 × 剩余」的总和不增加;</li>
 *   <li>三条门槛: 只差新鲜度、落在 1/8 保质期 (至少 2 小时) 的窗口内、停表时间相同;</li>
 *   <li>两个原版注入用的口径: 入包追加前先对齐章 (件数仍由原版搬)、配方书追加取最老的那批。</li>
 * </ul>
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessMergeGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "freshness_merge";

    private static final long NOW = 500_000L * 60L;

    // ============================================================
    // 加权平均与守恒
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mergeAveragesRemainingByCountAndConservesTheTotal(GameTestHelper helper) {
        int shelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.FRESH);
        long gap = Math.max(1L, FreshMerge.windowMinutes(shelf) / 2L);

        ItemStack target = beef(shelf, 600L, 10, 0);
        ItemStack incoming = beef(shelf, 600L + gap, 30, 0);
        long totalBefore = remaining(shelf, target) * target.getCount()
                + remaining(shelf, incoming) * incoming.getCount();

        int moved = FreshMerge.mergeInto(target, incoming);

        helper.assertTrue(moved == 30, "窗口内的同种食物应当整堆并进来");
        helper.assertTrue(target.getCount() == 40, "件数相加");
        helper.assertTrue(incoming.isEmpty(), "来源堆被搬空");
        long expected = Math.floorDiv(600L * 10L + (600L + gap) * 30L, 40L);
        helper.assertTrue(remaining(shelf, target) == expected,
                "剩余按件数加权平均 (期望 " + expected + " 分钟, 实际 " + remaining(shelf, target) + ")");
        helper.assertTrue(remaining(shelf, target) * target.getCount() <= totalBefore,
                "合并不得凭空多出新鲜度: 件数 × 剩余 的总和只能持平或变小");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mergeRefusesWhenTheGapExceedsTheWindow(GameTestHelper helper) {
        int shelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.FRESH);
        long window = FreshMerge.windowMinutes(shelf);

        ItemStack justInside = beef(shelf, 600L, 5, 0);
        ItemStack inside = beef(shelf, 600L + window, 5, 0);
        helper.assertTrue(FreshMerge.canMerge(justInside, inside),
                "差正好一个窗口应当还算能合并 (窗口是闭区间)");

        ItemStack justOutside = beef(shelf, 600L, 5, 0);
        ItemStack outside = beef(shelf, 600L + window + 1L, 5, 0);
        helper.assertTrue(!FreshMerge.canMerge(justOutside, outside),
                "差一个窗口再加一分钟就不合并: 新货不能把快烂的货救活");
        helper.assertTrue(FreshMerge.mergeInto(justOutside, outside) == 0,
                "不允许合并时一件都不搬");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mergeRefusesDifferentDataOrDifferentStopHours(GameTestHelper helper) {
        int shelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.FRESH);

        ItemStack plain = beef(shelf, 600L, 5, 0);
        ItemStack renamed = beef(shelf, 600L, 5, 0);
        renamed.setHoverName(Component.literal("test"));
        helper.assertTrue(!FreshMerge.canMerge(plain, renamed), "其它数据不同就不合并");

        ItemStack stopped = beef(shelf, 600L, 5, 7);
        helper.assertTrue(!FreshMerge.canMerge(plain, stopped),
                "停表时间不同不自动叠 (免得新货被悄悄扣掉停表额度)");

        ItemStack migrated = beef(shelf, 600L, 5, 0);
        FreshnessData.markMigrated(migrated);
        helper.assertTrue(!FreshMerge.canMerge(plain, migrated), "开服迁移货永不和新货合并");

        ItemStack other = new ItemStack(Items.COOKED_BEEF, 5);
        FreshnessData.stampAt(other, FreshnessData.batchMinute(plain), 0);
        helper.assertTrue(!FreshMerge.canMerge(plain, other), "不同物品不合并");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mergeRefusesUnstampedAndNonSpoilingStacks(GameTestHelper helper) {
        int shelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.FRESH);

        ItemStack stamped = beef(shelf, 600L, 5, 0);
        helper.assertTrue(!FreshMerge.canMerge(stamped, new ItemStack(Items.BEEF, 5)),
                "还没盖章的堆先盖章, 本轮不参与合并");
        helper.assertTrue(!FreshMerge.canMerge(new ItemStack(Items.BEEF, 5), stamped),
                "顺序反过来同样不合并");

        ItemStack diamond = new ItemStack(Items.DIAMOND, 5);
        FreshnessData.stampAt(diamond, 0L, 0);
        helper.assertTrue(!FreshMerge.canMerge(stamped, diamond), "不腐烂的东西不参与合并");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mergeKeepsStoppedHoursAndRewritesBatch(GameTestHelper helper) {
        int shelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.FRESH);

        ItemStack target = beef(shelf, 600L, 8, 5);
        ItemStack incoming = beef(shelf, 660L, 8, 5);
        helper.assertTrue(FreshMerge.mergeInto(target, incoming) == 8, "停表时间相同就可以合并");
        helper.assertTrue(FreshnessData.stoppedHours(target) == 5, "停表额度不变");
        long expected = Math.floorDiv(600L * 8L + 660L * 8L, 16L);
        helper.assertTrue(remaining(shelf, target) == expected,
                "合并后的剩余是两边的平均 (期望 " + expected + ", 实际 " + remaining(shelf, target) + ")");
        helper.succeed();
    }

    // ============================================================
    // 两个原版注入用的口径
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void prepareAppendAlignsStampsWithoutMovingItems(GameTestHelper helper) {
        int shelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.FRESH);

        ItemStack target = beef(shelf, 600L, 10, 0);
        ItemStack incoming = beef(shelf, 660L, 30, 0);
        int moved = FreshMerge.prepareAppend(target, incoming);

        helper.assertTrue(moved == 30, "整堆都塞得下时, 预计搬 30 件");
        helper.assertTrue(target.getCount() == 10 && incoming.getCount() == 30,
                "这一趟只改章不搬件数 (件数由原版随后自己搬)");
        helper.assertTrue(FreshnessData.batchMinute(target) == FreshnessData.batchMinute(incoming),
                "两堆的章要对齐, 原版随后 grow 时账才对");

        target.grow(moved);
        incoming.shrink(moved);
        long expected = Math.floorDiv(600L * 10L + 660L * 30L, 40L);
        helper.assertTrue(remaining(shelf, target) == expected,
                "原版搬完之后就是加权平均的结果 (期望 " + expected + ")");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void prepareAppendSkipsWhenTheStackDoesNotFit(GameTestHelper helper) {
        int shelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.FRESH);

        ItemStack target = beef(shelf, 600L, 60, 0);
        ItemStack incoming = beef(shelf, 660L, 10, 0);
        helper.assertTrue(FreshMerge.prepareAppend(target, incoming) == 0,
                "装不下时不插手: 原版只搬一部分, 那部分跟老章走才是保守方向");
        helper.assertTrue(FreshnessData.batchMinute(target) != FreshnessData.batchMinute(incoming),
                "不插手时两堆的章保持原样");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void recipeBookAppendTakesTheOlderBatch(GameTestHelper helper) {
        int shelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.FRESH);

        ItemStack grid = beef(shelf, 600L, 1, 0);
        ItemStack fresh = beef(shelf, 660L, 6, 0);
        FreshMerge.preferOlderBatch(grid, fresh);

        helper.assertTrue(remaining(shelf, grid) == remaining(shelf, fresh),
                "追加后两边的年龄一致");
        helper.assertTrue(remaining(shelf, fresh) == 600L,
                "取的是较老的那一批 (剩余较少), 不是平均");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mergeWindowScalesWithShelfLife(GameTestHelper helper) {
        // 1/8 保质期与「至少 2 小时」取大者: 生鲜 12 小时 -> 2 小时; 果蔬 36 -> 4.5 小时;
        // 48 小时类 -> 6 小时; 腌制 168 -> 21 小时 (规格「同种合并窗口」那行)。
        helper.assertTrue(FreshMerge.windowMinutes(12) == 120L, "生鲜窗口 2 小时");
        helper.assertTrue(FreshMerge.windowMinutes(36) == 270L, "果蔬窗口 4.5 小时");
        helper.assertTrue(FreshMerge.windowMinutes(48) == 360L, "48 小时类窗口 6 小时");
        helper.assertTrue(FreshMerge.windowMinutes(168) == 1_260L, "腌制窗口 21 小时");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void containerSearchFindsTheMergeableSlotOnly(GameTestHelper helper) {
        int shelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.FRESH);

        SimpleContainer container = new SimpleContainer(3);
        container.setItem(0, new ItemStack(Items.DIAMOND, 5));
        container.setItem(1, beef(shelf, 600L, 10, 0));
        container.setItem(2, beef(shelf, 600L + FreshMerge.windowMinutes(shelf) + 60L, 10, 0));

        ItemStack incoming = beef(shelf, 640L, 5, 0);
        int slot = FreshnessMergeHooks.findMergeableSlot(container, incoming);
        helper.assertTrue(slot == 1, "只认同种、只差新鲜度、落在窗口内的那一格 (实际 " + slot + ")");

        FreshnessMergeHooks.prepareAppend(container, slot, incoming);
        helper.assertTrue(FreshnessData.batchMinute(container.getItem(1))
                        == FreshnessData.batchMinute(incoming),
                "入包追加前先把两边的章对齐");
        helper.assertTrue(container.getItem(2).getCount() == 10, "窗口外的那一格一动不动");
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 一堆生牛肉: 在 NOW 时正好剩 remainingMinutes, 并带 stoppedHours 的停表额度。 */
    private static ItemStack beef(int shelfLifeHours, long remainingMinutes, int count, int stoppedHours) {
        ItemStack stack = new ItemStack(Items.BEEF, count);
        FreshnessData.stampAt(stack, NOW + remainingMinutes
                - (long) shelfLifeHours * 60L - (long) stoppedHours * 60L, stoppedHours);
        return stack;
    }

    private static long remaining(int shelfLifeHours, ItemStack stack) {
        return FreshnessData.remainingMinutes(stack, shelfLifeHours, NOW);
    }
}
