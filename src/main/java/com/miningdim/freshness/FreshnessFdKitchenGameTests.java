package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import vectorwing.farmersdelight.common.block.entity.SkilletBlockEntity;
import vectorwing.farmersdelight.common.registry.ModBlocks;
import vectorwing.farmersdelight.common.registry.ModItems;

import java.lang.reflect.Method;
import java.util.List;

/**
 * FD 煎锅 / 炉灶 / 厨锅的继承与"烂原料不出品" (docs/Spoilage_Granary_DesignSpec.md「可选配置」#12、#14、#15)。
 *
 * <p>三台机器出菜的方式不同, 所以断言也分三种:</p>
 * <ul>
 *   <li><b>煎锅</b>: 产物从 {@code ItemUtils.spawnItemEntity} 喷出来 → 在掉落物上断言 (原料一半 + 25% = 75%);</li>
 *   <li><b>厨锅</b>: 菜留在输出格里 → 在槽位上断言 ({@code moveMealToOutput} 之前就要盖好章);</li>
 *   <li><b>炉灶</b>: 出菜方法无参、只从自己的物品栏取料, 行为断言要真跑烹饪 tick —— 这里只核对注入点真的织进去了,
 *       行为列进测试服实机清单。</li>
 * </ul>
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessFdKitchenGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "freshness_fd";

    private static final int SLOT = 12;

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void skilletProductsInheritWithHeatBonus(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        BlockPos rel = new BlockPos(1, 2, 1);
        helper.setBlock(rel, ModBlocks.SKILLET.get());
        SkilletBlockEntity pan = (SkilletBlockEntity) helper.getBlockEntity(rel);
        helper.assertTrue(pan != null, "煎锅方块实体要放得出来");

        long nowMinute = FreshnessClockData.nowMinute(level);
        ItemStack beef = halfFreshBeef(nowMinute);
        helper.assertTrue(pan.addItemToCook(beef, player).isEmpty(), "生牛肉要放得进煎锅");

        cookNow(pan, level);

        ItemStack product = findDrop(level, helper.absolutePos(rel), Items.COOKED_BEEF);
        helper.assertTrue(product != null, "煎锅应当把生牛肉煎成熟牛肉 (掉落物 "
                + level.getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(rel)).inflate(3.0D))
                .stream().map(entity -> entity.getItem().toString()).toList() + ")");
        assertRatio(helper, product, 0.75D, "煎锅: 原料一半 + 加热 25%");
        helper.assertTrue(FreshnessSourceContext.current().isEmpty(), "出菜之后要清掉来源上下文");
        helper.assertTrue(FreshnessCompatStatus.woven(FreshnessCompatStatus.SKILLET),
                "煎锅 mixin 没织进去: " + System.getProperty(
                        FreshnessCompatMixinPlugin.PROPERTY_PREFIX + FreshnessCompatStatus.SKILLET));
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cookingPotMealIsStampedBeforeItMoves(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(SLOT + 1, 2, 1);
        helper.setBlock(rel, ModBlocks.COOKING_POT.get());
        CookingPotBlockEntity pot = (CookingPotBlockEntity) helper.getBlockEntity(rel);
        helper.assertTrue(pot != null, "厨锅方块实体要放得出来");

        long nowMinute = FreshnessClockData.nowMinute(level);
        pot.getInventory().setStackInSlot(0, halfFreshBeef(nowMinute));
        ItemStack meal = new ItemStack(Items.COOKED_BEEF, 1);
        pot.getInventory().setStackInSlot(FreshnessFdTargets.POT_MEAL_SLOT, meal);

        Method move = CookingPotBlockEntity.class.getDeclaredMethod("moveMealToOutput");
        move.setAccessible(true);
        move.invoke(pot);

        ItemStack moved = pot.getInventory().getStackInSlot(FreshnessFdTargets.POT_MEAL_SLOT + 2);
        helper.assertTrue(moved.is(Items.COOKED_BEEF), "菜应当被挪到输出格, 实得 " + moved);
        assertRatio(helper, moved, 0.75D, "厨锅: 原料一半 + 加热 25%");
        helper.assertTrue(FreshnessCompatStatus.woven(FreshnessCompatStatus.POT),
                "厨锅 mixin 没织进去: " + System.getProperty(
                        FreshnessCompatMixinPlugin.PROPERTY_PREFIX + FreshnessCompatStatus.POT));
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rottenIngredientsProduceNothing(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        BlockPos rel = new BlockPos(SLOT * 2 + 1, 2, 1);
        helper.setBlock(rel, ModBlocks.SKILLET.get());
        SkilletBlockEntity pan = (SkilletBlockEntity) helper.getBlockEntity(rel);

        long nowMinute = FreshnessClockData.nowMinute(level);
        ItemStack rotten = new ItemStack(Items.BEEF, 1);
        FreshnessData.stampAt(rotten, nowMinute - 40L * 24L * 60L, 0);
        helper.assertTrue(FreshnessProcessing.anyRotten(List.of(rotten), nowMinute),
                "夹具: 这块肉要真的烂了");
        pan.addItemToCook(rotten, player);

        cookNow(pan, level);

        ItemStack product = findDrop(level, helper.absolutePos(rel), Items.COOKED_BEEF);
        helper.assertTrue(product == null, "烂原料不能做成新菜 (反漏洞)");
        helper.assertTrue(FreshnessSourceContext.current().isEmpty(), "被拦下也要清掉来源上下文");

        // 炉灶: 只核对注入点 (行为断言要真跑烹饪 tick, 列进测试服实机清单)。
        BlockPos stoveRel = new BlockPos(SLOT * 2 + 5, 2, 1);
        helper.setBlock(stoveRel, ModBlocks.STOVE.get());
        helper.assertTrue(helper.getBlockEntity(stoveRel) != null, "炉灶方块实体要放得出来");
        helper.assertTrue(FreshnessCompatStatus.woven(FreshnessCompatStatus.STOVE),
                "炉灶 mixin 没织进去: " + System.getProperty(
                        FreshnessCompatMixinPlugin.PROPERTY_PREFIX + FreshnessCompatStatus.STOVE));
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 剩一半的生牛肉 (生鲜 12 小时 → 批次 = 现在 − 6 小时)。 */
    private static ItemStack halfFreshBeef(long nowMinute) {
        ItemStack beef = new ItemStack(Items.BEEF, 1);
        int shelfHours = FreshnessCategories.shelfLifeHours(beef);
        FreshnessData.stampAt(beef, nowMinute - (long) shelfHours * 30L, 0);
        return beef;
    }

    /**
     * 让煎锅"这一锅正好熟"。
     *
     * {@code cookAndOutputItems} 第一件事是 {@code cookingTime++} 再与 {@code cookingTimeTotal} 比,
     * 而 {@code addItemToCook} 已经把总时长按配方设好了 (几百 tick) —— 不把计时器顶到终点, 这一趟只会
     * 让计时器加一, 什么都出不来。
     */
    private static void cookNow(SkilletBlockEntity pan, ServerLevel level) throws Exception {
        java.lang.reflect.Field timer = SkilletBlockEntity.class.getDeclaredField("cookingTime");
        timer.setAccessible(true);
        timer.setInt(pan, 10_000);
        Method cook = SkilletBlockEntity.class.getDeclaredMethod("cookAndOutputItems", ItemStack.class,
                net.minecraft.world.level.Level.class);
        cook.setAccessible(true);
        cook.invoke(pan, pan.getStoredStack(), level);
    }

    /** 产物落在地上: 在方块附近找指定物品的掉落物。 */
    private static ItemStack findDrop(ServerLevel level, BlockPos pos, net.minecraft.world.item.Item item) {
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3.0D));
        return drops.stream().map(ItemEntity::getItem).filter(stack -> stack.is(item)).findFirst().orElse(null);
    }

    private static void assertRatio(GameTestHelper helper, ItemStack product, double expected, String what) {
        int shelfHours = FreshnessCategories.shelfLifeHours(product);
        long remaining = FreshnessData.remainingMinutes(product,
                shelfHours, FreshnessClockData.nowMinute(helper.getLevel()));
        double ratio = remaining / (shelfHours * 60.0D);
        helper.assertTrue(Math.abs(ratio - expected) < 0.06D,
                what + " → 期望比例约 " + expected + ", 实得 " + ratio);
    }
}
