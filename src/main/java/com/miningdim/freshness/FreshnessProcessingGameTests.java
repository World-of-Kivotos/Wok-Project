package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import com.miningdim.freshness.item.FreshnessItems;
import com.miningdim.testutil.ConfigBaseline;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 加工继承策略与三个 mixin 观察点的 GameTest
 * (docs/Spoilage_Granary_DesignSpec.md「继承规则」「一期接入的加工点」、反漏洞 #5/#7/#10/#26)。
 *
 * 分两层锁:
 * <ul>
 *   <li><b>策略层</b> —— {@link FreshnessProcessing} 的算术 (取最差比例、加热加成、封顶、取整、下限、停表额度取大),
 *       现在由用例显式传进去, 与真实时钟无关;</li>
 *   <li><b>适配层</b> —— {@link FreshnessHooks} 对真实方块实体 (漏斗、熔炉) 的行为, 以及漏斗那条真正走 mixin 的
 *       tick 路径。这一层用的是**服务器时钟**, 所以夹具一律按 {@link FreshnessClockData#nowMinute} 现算,
 *       不能拿一个常量当「现在」(否则批次会被当成未来时间被 clamp 回去)。</li>
 * </ul>
 * 合成格那条注入点在本批只锁策略 (配方书与模拟玩家合成夹具随「同种合并」那批一起补)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessProcessingGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "freshness_processing";

    /** 策略层用例自带的「现在」: 这些用例把时刻显式传进纯函数, 不依赖服务器时钟。 */
    private static final long FIXED_NOW = 500_000L * 60L;

    @BeforeBatch(batch = BATCH)
    public static void resetConfigBaseline(ServerLevel level) {
        ConfigBaseline.resetToDefaults(FreshnessConfig.MODE);
    }

    // ============================================================
    // 策略层: 继承规则的四条算术
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void inheritTakesWorstRatioAndIgnoresBetterIngredients(GameTestHelper helper) {
        int wheatShelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.GRAIN);
        int breadShelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.COOKED);

        ItemStack wheat = ratio(wheatShelf, 0.5D, Items.WHEAT, 3);
        ItemStack apple = ratio(FreshnessConfig.shelfLifeHours(FreshnessCategory.PRODUCE), 1.0D, Items.APPLE, 1);
        ItemStack bread = new ItemStack(Items.BREAD);
        FreshnessProcessing.inherit(bread, List.of(wheat, apple), FreshnessProcessing.NO_BONUS, FIXED_NOW);

        long expected = (long) Math.floor(breadShelf * 60.0D * 0.5D);
        long actual = FreshnessData.remainingMinutes(bread, breadShelf, FIXED_NOW);
        helper.assertTrue(actual == expected,
                "合成产物按原料里最差的比例继承 (期望 " + expected + " 分钟, 实际 " + actual + ")");

        ItemStack onlyGood = new ItemStack(Items.BREAD);
        FreshnessProcessing.inherit(onlyGood, List.of(apple), FreshnessProcessing.NO_BONUS, FIXED_NOW);
        helper.assertTrue(FreshnessData.remainingMinutes(onlyGood, breadShelf, FIXED_NOW) == breadShelf * 60L,
                "全新原料做出的产物应当是满保质期 (说明取的不是平均值)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void heatBonusTopsUpAndCapsAtFullShelfLife(GameTestHelper helper) {
        int meatShelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.FRESH);
        int cookedShelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.COOKED);

        ItemStack quarter = ratio(meatShelf, 0.25D, Items.BEEF, 1);
        ItemStack halfSteak = new ItemStack(Items.COOKED_BEEF);
        FreshnessProcessing.inherit(halfSteak, List.of(quarter), FreshnessProcessing.HEAT_BONUS, FIXED_NOW);
        long halfExpected = (long) Math.floor(cookedShelf * 60.0D * 0.5D);
        helper.assertTrue(FreshnessData.remainingMinutes(halfSteak, cookedShelf, FIXED_NOW) == halfExpected,
                "加热加成把 0.25 顶到 0.5");

        ItemStack threeQuarters = ratio(meatShelf, 0.75D, Items.BEEF, 1);
        ItemStack fullSteak = new ItemStack(Items.COOKED_BEEF);
        FreshnessProcessing.inherit(fullSteak, List.of(threeQuarters), FreshnessProcessing.HEAT_BONUS, FIXED_NOW);
        helper.assertTrue(FreshnessData.remainingMinutes(fullSteak, cookedShelf, FIXED_NOW) == cookedShelf * 60L,
                "加成后超过 1 一律封顶为满保质期");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void productRemainingIsFlooredAndNeverBelowOneHour(GameTestHelper helper) {
        int meatShelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.FRESH);
        int cookedShelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.COOKED);

        ItemStack nearlyGone = ratio(meatShelf, 1.0D / 12.0D, Items.BEEF, 1);
        ItemStack product = new ItemStack(Items.COOKED_BEEF);
        FreshnessProcessing.inherit(product, List.of(nearlyGone), FreshnessProcessing.NO_BONUS, FIXED_NOW);
        long expected = (long) Math.floor(cookedShelf * 60.0D / 12.0D);
        helper.assertTrue(FreshnessData.remainingMinutes(product, cookedShelf, FIXED_NOW) == expected,
                "产物剩余按保质期 × 比例向下取整");

        ItemStack dust = ratio(meatShelf, 0.0001D, Items.BEEF, 1);
        ItemStack floored = new ItemStack(Items.COOKED_BEEF);
        FreshnessProcessing.inherit(floored, List.of(dust), FreshnessProcessing.NO_BONUS, FIXED_NOW);
        helper.assertTrue(FreshnessData.remainingMinutes(floored, cookedShelf, FIXED_NOW) == 60L,
                "产物最少给 1 小时, 不会做出立刻就烂的东西");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void stoppedHoursFollowTheMostStoppedIngredient(GameTestHelper helper) {
        int meatShelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.FRESH);
        int cookedShelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.COOKED);

        // 停表额度会加长剩余, 所以「剩 50% 且停过 100 小时」的批次要按 (剩余 − 保质期 − 停表) 反算。
        ItemStack frozen = ratioWithStopped(meatShelf, 0.5D, 100, Items.BEEF, 1);
        ItemStack plain = ratioWithStopped(meatShelf, 1.0D, 5, Items.BEEF, 1);

        ItemStack product = new ItemStack(Items.COOKED_BEEF);
        FreshnessProcessing.inherit(product, List.of(frozen, plain), FreshnessProcessing.NO_BONUS, FIXED_NOW);
        helper.assertTrue(FreshnessData.stoppedHours(product) == 100,
                "停表额度取原料里最大的 (堵「冻满额度 -> 做熟 -> 再冻满」)");
        long expected = (long) Math.floor(cookedShelf * 60.0D * 0.5D);
        helper.assertTrue(FreshnessData.remainingMinutes(product, cookedShelf, FIXED_NOW) == expected,
                "剩余比例照样取最差的那件");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void anyRottenOnlyCountsSpoilingStampedIngredients(GameTestHelper helper) {
        helper.assertTrue(!FreshnessProcessing.anyRotten(List.of(new ItemStack(Items.BEEF)), FIXED_NOW),
                "没盖章的原料按「刚到手的」算, 不算过期");
        helper.assertTrue(FreshnessProcessing.anyRotten(List.of(ancient(Items.BEEF, 1)), FIXED_NOW),
                "过了保质期的原料要判出来");
        helper.assertTrue(!FreshnessProcessing.anyRotten(List.of(ancient(Items.DIAMOND, 1)), FIXED_NOW),
                "不腐烂的东西不参与判定");
        helper.assertTrue(FreshnessProcessing.anyRotten(
                        List.of(new ItemStack(Items.BEEF), ancient(Items.COOKED_BEEF, 1)), FIXED_NOW),
                "一堆原料里有任何一件过期就不出产物");
        helper.succeed();
    }

    // ============================================================
    // 适配层: 漏斗与熔炉 (真实方块实体, 用服务器时钟)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void hopperHookSettlesItsOwnSlots(GameTestHelper helper) {
        long now = FreshnessClockData.nowMinute(helper.getLevel());
        BlockPos relative = new BlockPos(1, 2, 1);
        helper.setBlock(relative, Blocks.HOPPER);
        HopperBlockEntity hopper = (HopperBlockEntity) helper.getBlockEntity(relative);
        helper.assertTrue(hopper != null, "漏斗方块实体应当已创建");
        hopper.setItem(0, ancientAt(Items.BEEF, 3, now));

        withReplacementOn(() -> FreshnessHooks.settleHopper(hopper, helper.getLevel(), helper.absolutePos(relative)));

        ItemStack settled = hopper.getItem(0);
        helper.assertTrue(settled.is(FreshnessItems.ROT.get()) && settled.getCount() == 3,
                "漏斗里过期的食物要换成腐烂物, 件数不变");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void furnaceHookRefusesRottenInputAndInheritsWithHeatBonus(GameTestHelper helper) {
        int meatShelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.FRESH);
        int cookedShelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.COOKED);
        long now = FreshnessClockData.nowMinute(helper.getLevel());

        BlockPos relative = new BlockPos(3, 2, 3);
        helper.setBlock(relative, Blocks.FURNACE);
        AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) helper.getBlockEntity(relative);
        helper.assertTrue(furnace != null, "熔炉方块实体应当已创建");

        NonNullList<ItemStack> rottenItems = NonNullList.withSize(3, ItemStack.EMPTY);
        rottenItems.set(0, ancientAt(Items.BEEF, 2, now));
        NonNullList<ItemStack> freshItems = NonNullList.withSize(3, ItemStack.EMPTY);
        freshItems.set(0, ratioAt(meatShelf, 0.25D, Items.BEEF, 1, now));
        freshItems.set(2, new ItemStack(Items.COOKED_BEEF));

        withReplacementOn(() -> {
            helper.assertTrue(FreshnessHooks.refuseRottenFurnaceInput(furnace, rottenItems),
                    "过期原料必须让熔炉停工");
            helper.assertTrue(rottenItems.get(0).is(FreshnessItems.ROT.get())
                            && rottenItems.get(0).getCount() == 2,
                    "过期原料换成腐烂物, 件数不变");

            helper.assertTrue(!FreshnessHooks.refuseRottenFurnaceInput(furnace, freshItems),
                    "没过期的原料不该被拦");
            FreshnessHooks.inheritFurnaceProduct(furnace, freshItems);
            long expected = (long) Math.floor(cookedShelf * 60.0D * 0.5D);
            long actual = FreshnessData.remainingMinutes(freshItems.get(2), cookedShelf, now);
            helper.assertTrue(actual == expected,
                    "熔炉出品按「最差比例 + 加热 0.25」继承 (期望 " + expected + " 分钟, 实际 " + actual + ")");
        });
        helper.succeed();
    }

    /** 真正走 mixin 的那条路径: 漏斗在自己的 tick 里搬运前结算, 不需要任何外部调用。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 200)
    public static void hopperSettlesSlotsOnItsOwnTick(GameTestHelper helper) {
        long now = FreshnessClockData.nowMinute(helper.getLevel());
        BlockPos relative = new BlockPos(5, 2, 1);
        helper.setBlock(relative, Blocks.HOPPER);
        HopperBlockEntity hopper = (HopperBlockEntity) helper.getBlockEntity(relative);
        helper.assertTrue(hopper != null, "漏斗方块实体应当已创建");
        hopper.setItem(1, ancientAt(Items.COOKED_BEEF, 1, now));

        // 终态替换只在开启档发生, 而断言在延迟回调里跑, 所以这里手工开关而不是用 withReplacementOn
        // (那个会在回调之前就把开关还原回去)。
        FreshnessConfig.Mode previous = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.ON);
        helper.runAfterDelay(10, () -> {
            try {
                ItemStack settled = hopper.getItem(1);
                helper.assertTrue(settled.is(FreshnessItems.ROT.get()),
                        "漏斗 tick 时应当自己把过期食物换成腐烂物 (mixin 注入生效)");
            } finally {
                FreshnessConfig.MODE.set(previous);
            }
            helper.succeed();
        });
    }

    // ============================================================
    // 夹具
    // ============================================================
    // 机器: 输出合并取最老 + 营火掉落之前改产物 (规格「机器输出合并取最老」/ mixin #5)
    // ============================================================

    /**
     * 输出合并取最老 (规格「机器输出合并取最老」): 输出格里已经放着 f=0 的老牛排, 新烤出来的一件按 +25% 继承
     * 原料 —— 合并之后**整堆**必须是较老的那一批 (剩余取少、停表取大), 否则反复烤就能把积压的老货一次次刷新。
     *
     * 走真实的钩子入口 (HEAD 拍输出格快照 -> RETURN 改写), 与 mixin 注入的那两行是同一个方法:
     * burn 本身是私有的, 用例按本仓既有范式直接驱动钩子 (注入本身由 required 配置 + defaultRequire 1 兜底)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void furnaceOutputMergesIntoTheOlderBatch(GameTestHelper helper) {
        long now = FreshnessClockData.nowMinute(helper.getLevel());
        BlockPos relative = new BlockPos(6, 2, 2);
        helper.setBlock(relative, Blocks.FURNACE);
        AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) helper.getBlockEntity(relative);
        helper.assertTrue(furnace != null, "熔炉方块实体应当已创建");

        int cookedShelf = FreshnessCategories.shelfLifeHours(new ItemStack(Items.COOKED_BEEF));
        ItemStack oldOutput = new ItemStack(Items.COOKED_BEEF, 3);
        // 老牛排: 停表额度 200 小时、剩余 5 小时。
        FreshnessData.stampAt(oldOutput, now - ((long) cookedShelf * 60L - 5L * 60L) - 200L * 60L, 200);

        NonNullList<ItemStack> items = NonNullList.withSize(3, ItemStack.EMPTY);
        items.set(0, ratioAt(FreshnessConfig.shelfLifeHours(FreshnessCategory.FRESH), 0.6D, Items.BEEF, 1, now));
        items.set(2, oldOutput);

        // HEAD 那一步: 拍输出格快照 (顺手把没章的原料盖上章)。
        helper.assertTrue(!FreshnessHooks.refuseRottenFurnaceInput(furnace, items), "没过期的原料不该被拦");
        // 原版随后把这件产物 grow 进输出格 (件数 +1, 章仍是老的 —— 合并规则在下一步改写)。
        items.get(2).grow(1);
        // RETURN 那一步: 按"取最老"改写整堆的章。
        FreshnessHooks.inheritFurnaceProduct(furnace, items);

        ItemStack merged = items.get(2);
        helper.assertTrue(merged.getCount() == 4, "件数相加 (3 + 1), 实得 " + merged.getCount());
        helper.assertTrue(FreshnessData.stoppedHours(merged) == 200,
                "停表取大 (老牛排的 200 小时), 实得 " + FreshnessData.stoppedHours(merged));
        long remaining = FreshnessData.remainingMinutes(merged, cookedShelf, now);
        helper.assertTrue(remaining >= 4L * 60L && remaining <= 5L * 60L,
                "合并后的剩余必须落在老牛排那一档 (约 5 小时), 实得 " + remaining
                        + " —— 被新产物拉年轻就是这条规则的失败模式");
        helper.succeed();
    }

    /**
     * 营火: 掉落之前按原料继承 (+25%); 原料在火上放烂了就不出品、改掉一个腐烂物。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void campfireProductInheritsFromTheInputBeforeDropping(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long now = FreshnessClockData.nowMinute(level);
        BlockPos relative = new BlockPos(7, 2, 7);
        helper.setBlock(relative, Blocks.CAMPFIRE);
        var campfire = (net.minecraft.world.level.block.entity.CampfireBlockEntity) helper.getBlockEntity(relative);
        helper.assertTrue(campfire != null, "营火方块实体应当已创建");

        int beefShelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.FRESH);
        ItemStack raw = ratioAt(beefShelf, 0.625D, Items.BEEF, 1, now);
        helper.assertTrue(campfire.placeFood(null, raw, 100), "前提: 营火收下这块生牛肉");

        ItemStack stamped = FreshnessHooks.stampCampfireProduct(level, campfire,
                new ItemStack(Items.COOKED_BEEF, 1));
        helper.assertTrue(FreshnessData.isStamped(stamped), "掉落之前必须已经盖好章");
        long remaining = FreshnessCheck.remainingMinutes(stamped, now);
        // 继承口径: 产物剩余比例 = 原料剩余比例 + 加热加成 (加的是**比例**, 不是把剩余乘 1.25), 再封顶。
        long expected = (long) Math.floor(
                FreshnessConfig.shelfLifeHours(FreshnessCategory.COOKED) * 60.0D * (0.625D + 0.25D));
        helper.assertTrue(remaining == expected,
                "产物剩余比例应为 原料比例 + 0.25 (0.625 + 0.25 = 0.875), 期望 " + expected + " 实得 " + remaining);

        // 原料烂了: 不出品, 掉一个腐烂物。
        campfire.clearContent();
        ItemStack rotten = ancientAt(Items.BEEF, 1, now);
        helper.assertTrue(campfire.placeFood(null, rotten, 100), "前提: 营火收下这块已经烂了的生牛肉");
        ItemStack dropped = FreshnessHooks.stampCampfireProduct(level, campfire, new ItemStack(Items.COOKED_BEEF, 1));
        helper.assertTrue(FreshnessItems.ROT.get() == dropped.getItem(),
                "原料过期就换腐烂物、不出品, 实得 " + dropped.getItem());
        helper.succeed();
    }
    // ============================================================

    /** 一件「在 FIXED_NOW 时剩 ratio 比例」的东西 (策略层用例用)。 */
    private static ItemStack ratio(int shelfLifeHours, double ratio, Item item, int count) {
        return ratioAt(shelfLifeHours, ratio, item, count, FIXED_NOW);
    }

    /** 同 {@link #ratio}, 但时刻由调用方给 (适配层用例一律用服务器时钟)。 */
    private static ItemStack ratioAt(int shelfLifeHours, double ratio, Item item, int count, long nowMinute) {
        ItemStack stack = new ItemStack(item, count);
        long remaining = (long) Math.floor(shelfLifeHours * 60.0D * ratio);
        FreshnessData.stampAt(stack, nowMinute + remaining - (long) shelfLifeHours * 60L, 0);
        return stack;
    }

    /** 一件「剩 ratio 比例、并且已经停过 stoppedHours 小时」的东西。 */
    private static ItemStack ratioWithStopped(int shelfLifeHours, double ratio, int stoppedHours,
                                              Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        long remaining = (long) Math.floor(shelfLifeHours * 60.0D * ratio);
        FreshnessData.stampAt(stack, FIXED_NOW + remaining
                - (long) shelfLifeHours * 60L - (long) stoppedHours * 60L, stoppedHours);
        return stack;
    }

    private static ItemStack ancient(Item item, int count) {
        return ancientAt(item, count, FIXED_NOW);
    }

    /** 一件远在过去的批次 (一定过期) 的东西。 */
    private static ItemStack ancientAt(Item item, int count, long nowMinute) {
        ItemStack stack = new ItemStack(item, count);
        FreshnessData.stampAt(stack, nowMinute - 2L * 365L * 24L * 60L, 0);
        return stack;
    }

    /** 临时把总开关拨到「开启」(终态替换只在开启档发生), 用完立刻还原。 */
    private static void withReplacementOn(Runnable body) {
        FreshnessConfig.Mode previous = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.ON);
        try {
            body.run();
        } finally {
            FreshnessConfig.MODE.set(previous);
        }
    }
}
