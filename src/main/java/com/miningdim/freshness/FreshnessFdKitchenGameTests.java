package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import com.miningdim.freshness.item.FreshnessItems;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
                + level.getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(rel)).inflate(1.5D))
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

        // "不出品"的判据看锅里: 被拦下的那一趟不该把原料消耗掉 (同批别的用例就在隔壁摆着同样的机器做菜,
        // 所以这里不扫世界里的掉落物 —— 那会扫到邻居的锅)。
        ItemStack leftInPan = pan.getStoredStack();
        helper.assertTrue(!leftInPan.isEmpty(), "烂原料不能做成新菜 (反漏洞): 原料应当还在锅里");
        helper.assertTrue(leftInPan.is(FreshnessItems.ROT.get()) || leftInPan.is(Items.BEEF),
                "锅里应当是原来那块 (或它烂成的腐烂物), 实得 " + leftInPan);
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

    /**
     * 厨锅: 输出格里已经有同一种菜时, 新做好的那份**并进去**而不是占第二格 (规格 #15 后半)。
     *
     * <p>两份菜只差新鲜度, 合并规则就是"同种食物合并"那一套 (加权平均 + 取较老的停表额度),
     * 不另写一套。</p>
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void potMergesTheNewMealIntoAnExistingOutput(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(SLOT * 3 + 1, 2, 1);
        helper.setBlock(rel, ModBlocks.COOKING_POT.get());
        CookingPotBlockEntity pot = (CookingPotBlockEntity) helper.getBlockEntity(rel);

        long nowMinute = FreshnessClockData.nowMinute(level);
        pot.getInventory().setStackInSlot(0, halfFreshBeef(nowMinute));
        // 输出格先放两份"刚出锅"的熟牛肉。
        ItemStack existing = new ItemStack(Items.COOKED_BEEF, 2);
        FreshnessData.stamp(existing, nowMinute);
        pot.getInventory().setStackInSlot(FreshnessFdTargets.POT_OUTPUT_SLOT, existing);
        // 展示格里是这一锅新做好的 (3 份, 还没盖章 —— 由注入点按原料盖)。
        ItemStack meal = new ItemStack(Items.COOKED_BEEF, 3);
        pot.getInventory().setStackInSlot(FreshnessFdTargets.POT_MEAL_SLOT, meal);

        Method move = CookingPotBlockEntity.class.getDeclaredMethod("moveMealToOutput");
        move.setAccessible(true);
        move.invoke(pot);

        ItemStack output = pot.getInventory().getStackInSlot(FreshnessFdTargets.POT_OUTPUT_SLOT);
        helper.assertTrue(output.getCount() == 5, "新菜应当并进输出格 (2 + 3), 实得 " + output.getCount());
        helper.assertTrue(pot.getInventory().getStackInSlot(FreshnessFdTargets.POT_MEAL_SLOT).isEmpty(),
                "并进去之后展示格要空, 不能留一份卡在那里");
        // 合并后的新鲜度介于两者之间 (权重按件数), 且比"刚出锅"那份老。
        int shelfHours = FreshnessCategories.shelfLifeHours(output);
        double ratio = FreshnessData.remainingMinutes(output, shelfHours,
                FreshnessClockData.nowMinute(level)) / (shelfHours * 60.0D);
        helper.assertTrue(ratio > 0.7D && ratio < 1.0D, "合并后应当是加权平均, 实得 " + ratio);
        helper.succeed();
    }

    /**
     * 煎锅**手持烹饪** (#13): 成品直接进背包, 不走 ItemUtils —— 所以它要自己按原料盖章;
     * 锅里是烂的就什么都不出。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void handCookingInheritsAndRefusesRottenFood(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        long nowMinute = FreshnessClockData.nowMinute(level);

        // 一半新鲜的生牛肉塞进煎锅物品的 NBT (FD 1.2.9: 子 compound "Cooking")。
        ItemStack skillet = skilletWith(Items.BEEF, nowMinute - 6L * 60L);
        ItemStack returned = skillet.getItem().finishUsingItem(skillet, level, player);
        helper.assertTrue(returned.is(ModItems.SKILLET.get()), "煎锅物品要原样还回来");

        ItemStack product = findInInventory(player, Items.COOKED_BEEF);
        helper.assertTrue(product != null, "手持烹饪应当把熟牛肉放进背包");
        assertRatio(helper, product, 0.75D, "手持烹饪: 原料一半 + 加热 25%");

        // 锅里的肉烂了: 这一趟不出品。
        player.getInventory().clearContent();
        ItemStack rottenPan = skilletWith(Items.BEEF, nowMinute - 40L * 24L * 60L);
        rottenPan.getItem().finishUsingItem(rottenPan, level, player);
        helper.assertTrue(findInInventory(player, Items.COOKED_BEEF) == null, "烂原料不能做出新菜");

        helper.assertTrue(FreshnessCompatStatus.woven(FreshnessCompatStatus.SKILLET_ITEM),
                "煎锅物品 mixin 没织进去: " + System.getProperty(
                        FreshnessCompatMixinPlugin.PROPERTY_PREFIX + FreshnessCompatStatus.SKILLET_ITEM));
        helper.succeed();
    }

    /** 一个装了指定生肉 (按给定批次) 的煎锅物品。 */
    private static ItemStack skilletWith(net.minecraft.world.item.Item food, long batchMinute) {
        ItemStack skillet = new ItemStack(ModItems.SKILLET.get());
        ItemStack stored = new ItemStack(food, 1);
        FreshnessData.stampAt(stored, batchMinute, 0);
        CompoundTag foodTag = new CompoundTag();
        stored.save(foodTag);
        skillet.getOrCreateTag().put(FreshnessFdTargets.SKILLET_ITEM_COOKING, foodTag);
        return skillet;
    }

    private static ItemStack findInInventory(ServerPlayer player, net.minecraft.world.item.Item item) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                return stack;
            }
        }
        return null;
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
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1.0D));
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