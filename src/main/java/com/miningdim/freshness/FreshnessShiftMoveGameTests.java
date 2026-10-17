package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.server.level.ServerPlayer;

/**
 * Shift 快移合并 (规格 mixin 清单 #9) 的契约 GameTest: 判据、白名单与"整堆装得下才并"的边界。
 *
 * 注入点本身是原版方法上的 {@code @Inject} (required 配置 + defaultRequire 1: 注入不上直接崩服), 所以
 * 这一片的风险不在"有没有接上", 而在三处语义:
 * <ol>
 *   <li><b>并错了地方</b>: 机器格子 (熔炉输入/燃料/输出) 有自己的批次与事务规矩, Shift 快移并进去就是绕开它们;</li>
 *   <li><b>装不下也并</b>: 那部分件数会跟着目标槽的老章走, 等于把新货拉老 (保守方向是"不并");</li>
 *   <li><b>差太远也并</b>: 窗口 (1/8 保质期, 至少 2 小时) 与停表额度两道门必须真的拦住。</li>
 * </ol>
 *
 * 用真实的 {@link ChestMenu} / {@link FurnaceMenu} 构造界面 (不摆方块: 判据只看槽与容器, 与方块无关),
 * 直接调注入点用的那个入口 —— 与 mixin 里那一行是同一个方法。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessShiftMoveGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "freshness_merge";

    private static final long NOW = 4_000_000L;

    // ============================================================
    // 1. 并得上: 玩家背包里的目标槽
    // ============================================================

    /**
     * 只差新鲜度、差距在窗口内、整堆装得下 -> 并进背包那一格: 件数相加、剩余按件数加权平均、源堆清空,
     * 且入口返回 TRUE (等价于"整堆搬完", 玩家按一次 Shift 就该看到东西进包了)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mergesIntoPlayerInventoryWhenOnlyFreshnessDiffers(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        ChestMenu menu = chestMenu(player, 9);

        // 目标槽 (背包第 0 格): 2 个面包, 剩 20 小时; 源堆: 1 个面包, 剩 10 小时 (差 10 小时, 窗口是 4.5 小时?
        // —— 熟食 48 小时保质期的窗口 = max(48/8, 2) = 6 小时, 所以这里刻意取 4 小时差, 落在窗口内)。
        ItemStack target = breadRemaining(2, 20L * 60L);
        ItemStack source = breadRemaining(1, 16L * 60L);
        helper.assertTrue(FreshMerge.canMerge(target, source), "前提: 这两堆应当满足合并的三条门槛");
        player.getInventory().setItem(0, target);
        int inventoryStart = menu.slots.size() - 36;

        Boolean merged = FreshnessMergeHooks.mergeShiftMove(menu, source, inventoryStart, menu.slots.size(), false);

        helper.assertTrue(Boolean.TRUE.equals(merged), "并上了就该返回 TRUE (等价于整堆搬完)");
        helper.assertTrue(source.isEmpty(), "源堆必须被搬空, 实得 " + source.getCount());
        ItemStack result = player.getInventory().getItem(0);
        helper.assertTrue(result.getCount() == 3, "件数要相加 (2+1), 实得 " + result.getCount());
        long remaining = FreshnessCheck.remainingMinutes(result, NOW);
        long expected = Math.floorDiv(20L * 60L * 2L + 16L * 60L * 1L, 3L);
        helper.assertTrue(Math.abs(remaining - expected) <= 60L,
                "剩余按件数加权平均 (期望约 " + expected + "), 实得 " + remaining);
        helper.succeed();
    }

    // ============================================================
    // 2. 并不得: 三道边界
    // ============================================================

    /** 差得太远 (超过窗口) / 停表额度不同 / 装不下 —— 三种情况都必须**不并**, 原样交给 vanilla。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void refusesWhenTheGapIsTooBigOrTheStackDoesNotFit(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        ChestMenu menu = chestMenu(player, 9);
        int inventoryStart = menu.slots.size() - 36;

        // 差 20 小时: 远超窗口 (6 小时), 不并。
        player.getInventory().setItem(0, breadRemaining(2, 40L * 60L));
        ItemStack far = breadRemaining(1, 20L * 60L);
        helper.assertTrue(FreshnessMergeHooks.mergeShiftMove(menu, far, inventoryStart, menu.slots.size(), false)
                        == null,
                "差得超过窗口的不许并");
        helper.assertTrue(far.getCount() == 1, "不并时源堆一件都不能少, 实得 " + far.getCount());

        // 停表额度不同: 不并 (额度不同的两堆并起来会互相白嫖额度)。
        player.getInventory().setItem(0, breadRemaining(2, 20L * 60L));
        ItemStack withStops = breadRemaining(1, 20L * 60L);
        FreshnessData.setStoppedHours(withStops, 5);
        helper.assertTrue(FreshnessMergeHooks.mergeShiftMove(menu, withStops, inventoryStart, menu.slots.size(), false)
                        == null,
                "停表额度不同的不许并");
        helper.assertTrue(withStops.getCount() == 1, "不并时源堆一件都不能少");

        // 装不下: 目标 63 个 + 源 3 个 (上限 64) -> 只装得下一部分, 一律不并 (那部分跟着老章走是保守方向)。
        player.getInventory().setItem(0, breadRemaining(63, 20L * 60L));
        ItemStack overflow = breadRemaining(3, 18L * 60L);
        helper.assertTrue(FreshnessMergeHooks.mergeShiftMove(menu, overflow, inventoryStart, menu.slots.size(), false)
                        == null,
                "整堆装不下时不许并 (装进去的那部分会跟着老章走)");
        helper.assertTrue(overflow.getCount() == 3, "不并时源堆一件都不能少, 实得 " + overflow.getCount());
        helper.succeed();
    }

    /**
     * 白名单: 箱子界面里的槽是合并目标; 熔炉这类机器界面的机器槽**不是** (机器格子有自己的批次与事务规矩),
     * 但同一个熔炉界面里的**玩家背包槽**照样是。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mergesInWhitelistedMenusButNeverIntoMachineSlots(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();

        // 箱子界面: 箱子里的槽也参与合并 (设计: 只对玩家背包和白名单容器生效)。
        ChestMenu chest = chestMenu(player, 9);
        Slot chestSlot = chest.slots.get(0);
        ItemStack inChest = breadRemaining(2, 20L * 60L);
        ((Container) chestSlot.container).setItem(0, inChest);
        ItemStack incoming = breadRemaining(1, 16L * 60L);
        helper.assertTrue(Boolean.TRUE.equals(
                        FreshnessMergeHooks.mergeShiftMove(chest, incoming, 0, 1, false)),
                "箱子 (白名单容器) 里的槽应当能并");

        // 熔炉界面: 机器槽不许并。
        SimpleContainer furnaceContainer = new SimpleContainer(3);
        FurnaceMenu furnace = new FurnaceMenu(0, player.getInventory(), furnaceContainer,
                new net.minecraft.world.inventory.SimpleContainerData(4));
        furnaceContainer.setItem(0, breadRemaining(2, 20L * 60L));
        ItemStack intoMachine = breadRemaining(1, 16L * 60L);
        helper.assertTrue(FreshnessMergeHooks.mergeShiftMove(furnace, intoMachine, 0, 1, false) == null,
                "熔炉输入槽不是合并目标 (机器有自己的批次与事务规矩)");
        helper.assertTrue(furnaceContainer.getItem(0).getCount() == 2, "机器槽一件都不许被并进去");

        // 同一个熔炉界面里, 玩家背包那一半照常并。
        int inventoryStart = furnace.slots.size() - 36;
        player.getInventory().setItem(0, breadRemaining(2, 20L * 60L));
        ItemStack intoPlayer = breadRemaining(1, 16L * 60L);
        helper.assertTrue(Boolean.TRUE.equals(FreshnessMergeHooks.mergeShiftMove(furnace, intoPlayer,
                        inventoryStart, furnace.slots.size(), false)),
                "机器界面里的玩家背包槽仍是合并目标");
        helper.succeed();
    }

    /** 反向 Shift (从背包倒着扫) 也要并得上: 顺序只影响"先看哪个槽", 不影响判据。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void reverseDirectionScansInReverseOrder(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        ChestMenu menu = chestMenu(player, 9);
        int inventoryStart = menu.slots.size() - 36;
        // 背包最后一格 (反向扫的第一个) 是可并的目标。
        player.getInventory().setItem(8, breadRemaining(2, 20L * 60L));
        ItemStack source = breadRemaining(1, 16L * 60L);
        helper.assertTrue(Boolean.TRUE.equals(
                        FreshnessMergeHooks.mergeShiftMove(menu, source, inventoryStart, menu.slots.size(), true)),
                "反向扫也要并得上");
        helper.assertTrue(player.getInventory().getItem(8).getCount() == 3,
                "并进的应当是反向扫到的第一格, 实得 " + player.getInventory().getItem(8).getCount());
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 一个箱子界面 (27 格 + 玩家背包): 不摆方块, 判据只看槽与容器。 */
    private static ChestMenu chestMenu(ServerPlayer player, int rows) {
        SimpleContainer container = new SimpleContainer(27);
        return new ChestMenu(net.minecraft.world.inventory.MenuType.GENERIC_9x3, 0, player.getInventory(),
                container, 3);
    }

    /** 一件"盖了章、剩余正好多少分钟"的面包 (熟食: 48 小时保质期, 合并窗口 = 6 小时)。 */
    private static ItemStack breadRemaining(int count, long remainingMinutes) {
        ItemStack stack = new ItemStack(Items.BREAD, count);
        int shelfLife = FreshnessCategories.shelfLifeHours(stack);
        FreshnessData.stampAt(stack, NOW - ((long) shelfLife * 60L - remainingMinutes), 0);
        return stack;
    }

}
