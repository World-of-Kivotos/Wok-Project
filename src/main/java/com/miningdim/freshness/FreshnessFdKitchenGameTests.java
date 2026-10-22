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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import vectorwing.farmersdelight.common.block.entity.SkilletBlockEntity;
import vectorwing.farmersdelight.common.block.entity.AbstractStoveBlockEntity;
import vectorwing.farmersdelight.common.block.StoveBlock;
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
 *   <li><b>厨锅</b>: 真实 cookingTick 消耗原料后, 无容器与碗装两条路径均继承一次;</li>
 *   <li><b>炉灶</b>: 真实 cookingTick 逐格出品, 不同原料分别继承。</li>
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
        helper.setBlock(rel.below(), Blocks.CAMPFIRE);
        pot.getInventory().setStackInSlot(0, halfFreshBeef(nowMinute));
        pot.getInventory().setStackInSlot(1, new ItemStack(ModItems.CABBAGE.get()));
        timer(CookingPotBlockEntity.class, pot, "cookTime", 10_000);
        helper.assertTrue(pot.isHeated(), "夹具: 厨锅下方营火提供热源");
        CookingPotBlockEntity.cookingTick(level, helper.absolutePos(rel), helper.getBlockState(rel), pot);

        ItemStack moved = pot.getInventory().getStackInSlot(FreshnessFdTargets.POT_MEAL_SLOT + 2);
        helper.assertTrue(moved.is(ModItems.CABBAGE_ROLLS.get()), "真实烹饪应把卷心菜卷挪到输出格, 实得 " + moved);
        helper.assertTrue(pot.getInventory().getStackInSlot(0).isEmpty(), "原料必须由真实 processCooking 消耗");
        assertRatio(helper, moved, 0.75D, "厨锅: 原料一半 + 加热 25%");
        helper.assertTrue(FreshnessCompatStatus.woven(FreshnessCompatStatus.POT),
                "厨锅 mixin 没织进去: " + System.getProperty(
                        FreshnessCompatMixinPlugin.PROPERTY_PREFIX + FreshnessCompatStatus.POT));
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rottenIngredientsProduceNothing(GameTestHelper helper) throws Exception {
        FreshnessConfig.Mode previous = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.ON);
        try {
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

        // 炉灶逐格行为由 stoveInheritsEachSlotSeparately 覆盖, 此处同时核对可选注入。
        BlockPos stoveRel = new BlockPos(SLOT * 2 + 5, 2, 1);
        helper.setBlock(stoveRel, ModBlocks.STOVE.get());
        helper.assertTrue(helper.getBlockEntity(stoveRel) != null, "炉灶方块实体要放得出来");
        helper.assertTrue(FreshnessCompatStatus.woven(FreshnessCompatStatus.STOVE),
                "炉灶 mixin 没织进去: " + System.getProperty(
                        FreshnessCompatMixinPlugin.PROPERTY_PREFIX + FreshnessCompatStatus.STOVE));
        helper.succeed();
        } finally {
            FreshnessConfig.MODE.set(previous);
        }
    }

    /**
     * 厨锅: 输出格里已经有同一种菜时, 新做好的那份**并进去**而不是占第二格 (规格 #15 后半)。
     *
     * <p>机器合并取最老与最大停表额度; 不受玩家堆叠的窗口与同停表额度限制。</p>
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void potMergesTheNewMealIntoAnExistingOutput(GameTestHelper helper) throws Exception {
        FreshnessConfig.Mode previous = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.ON);
        try {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(SLOT * 3 + 1, 2, 1);
        helper.setBlock(rel, ModBlocks.COOKING_POT.get());
        CookingPotBlockEntity pot = (CookingPotBlockEntity) helper.getBlockEntity(rel);

        long nowMinute = FreshnessClockData.nowMinute(level);
        // 输出格先放两份"刚出锅"的熟牛肉。
        ItemStack existing = new ItemStack(Items.COOKED_BEEF, 2);
        FreshnessData.stamp(existing, nowMinute);
        pot.getInventory().setStackInSlot(FreshnessFdTargets.POT_OUTPUT_SLOT, existing);
        // 展示格已在 processCooking 中盖章, 后续移动不能再次按旁边原料重盖。
        ItemStack meal = new ItemStack(Items.COOKED_BEEF, 3);
        int shelfHours = FreshnessCategories.shelfLifeHours(meal);
        FreshnessData.stampAt(meal, nowMinute - shelfHours * 30L - 504L * 60L, 504);
        pot.getInventory().setStackInSlot(FreshnessFdTargets.POT_MEAL_SLOT, meal);

        Method move = CookingPotBlockEntity.class.getDeclaredMethod("moveMealToOutput");
        move.setAccessible(true);
        move.invoke(pot);

        ItemStack output = pot.getInventory().getStackInSlot(FreshnessFdTargets.POT_OUTPUT_SLOT);
        helper.assertTrue(output.getCount() == 5, "新菜应当并进输出格 (2 + 3), 实得 " + output.getCount());
        helper.assertTrue(pot.getInventory().getStackInSlot(FreshnessFdTargets.POT_MEAL_SLOT).isEmpty(),
                "并进去之后展示格要空, 不能留一份卡在那里");
        double ratio = FreshnessData.remainingMinutes(output, shelfHours,
                FreshnessClockData.nowMinute(level)) / (shelfHours * 60.0D);
        helper.assertTrue(Math.abs(ratio - 0.5D) < 0.02D, "机器合并须取最老, 实得 " + ratio);
        helper.assertTrue(FreshnessData.stoppedHours(output) == 504, "合并须保留最大停表额度");
        ItemStack incompatible = output.copyWithCount(1);
        incompatible.getOrCreateTag().putString("ChefQuality", "different");
        pot.getInventory().setStackInSlot(FreshnessFdTargets.POT_MEAL_SLOT, incompatible);
        move.invoke(pot);
        helper.assertTrue(output.getCount() == 5 && pot.getMeal().getCount() == 1,
                "NBT 不兼容时必须留在展示格, 不得落回原版 item-only 合并");
        helper.succeed();
        } finally {
            FreshnessConfig.MODE.set(previous);
        }
    }

    /**
     * 煎锅**手持烹饪** (#13): 成品直接进背包, 不走 ItemUtils —— 所以它要自己按原料盖章;
     * 锅里是烂的就什么都不出。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void handCookingInheritsAndRefusesRottenFood(GameTestHelper helper) throws Exception {
        FreshnessConfig.Mode previous = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.ON);
        try {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        long nowMinute = FreshnessClockData.nowMinute(level);
        ItemStack oldUnstamped = new ItemStack(Items.COOKED_BEEF, 2);
        player.getInventory().setItem(0, oldUnstamped);

        // 一半新鲜的生牛肉塞进煎锅物品的 NBT (FD 1.2.9: 子 compound "Cooking")。
        ItemStack skillet = skilletWith(Items.BEEF, nowMinute - 6L * 60L);
        ItemStack returned = skillet.getItem().finishUsingItem(skillet, level, player);
        helper.assertTrue(returned.is(ModItems.SKILLET.get()), "煎锅物品要原样还回来");

        ItemStack product = player.getInventory().items.stream().filter(stack -> stack.is(Items.COOKED_BEEF)
                && FreshnessData.isStamped(stack)).findFirst().orElse(null);
        helper.assertTrue(product != null, "手持烹饪应当把熟牛肉放进背包");
        helper.assertTrue(oldUnstamped.getCount() == 2 && !FreshnessData.isStamped(oldUnstamped),
                "旧的同类未盖章物品不得被当成这次产物");
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
        } finally {
            FreshnessConfig.MODE.set(previous);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void potBowlPathInheritsOnceAndDoesNotRefreshWaitingMeal(GameTestHelper helper) throws Exception {
        FreshnessConfig.Mode previous = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.ON);
        try {
        BlockPos rel = new BlockPos(1, 2, 5);
        helper.setBlock(rel.below(), Blocks.CAMPFIRE);
        helper.setBlock(rel, ModBlocks.COOKING_POT.get());
        CookingPotBlockEntity pot = (CookingPotBlockEntity) helper.getBlockEntity(rel);
        long now = FreshnessClockData.nowMinute(helper.getLevel());
        pot.getInventory().setStackInSlot(0, halfFreshBeef(now));
        pot.getInventory().setStackInSlot(1, new ItemStack(Items.CARROT));
        pot.getInventory().setStackInSlot(2, new ItemStack(Items.POTATO));
        timer(CookingPotBlockEntity.class, pot, "cookTime", 10_000);
        CookingPotBlockEntity.cookingTick(helper.getLevel(), helper.absolutePos(rel), helper.getBlockState(rel), pot);
        helper.assertTrue(pot.getMeal().is(ModItems.BEEF_STEW.get()), "没有碗时成品留在展示格");
        assertRatio(helper, pot.getMeal(), 0.75D, "碗装食物在原料消耗时继承");
        CompoundTag stamp = pot.getMeal().getTag().copy();
        pot.getInventory().setStackInSlot(0, new ItemStack(Items.BEEF));
        CookingPotBlockEntity.cookingTick(helper.getLevel(), helper.absolutePos(rel), helper.getBlockState(rel), pot);
        helper.assertTrue(stamp.equals(pot.getMeal().getTag()), "等待输出不能用新放入原料刷新旧菜");
        pot.getInventory().setStackInSlot(7, new ItemStack(Items.BOWL));
        CookingPotBlockEntity.cookingTick(helper.getLevel(), helper.absolutePos(rel), helper.getBlockState(rel), pot);
        ItemStack output = pot.getInventory().getStackInSlot(8);
        helper.assertTrue(output.is(ModItems.BEEF_STEW.get()) && pot.getMeal().isEmpty(), "加碗后真实 tick 走碗装输出");
        helper.assertTrue(stamp.equals(output.getTag()), "碗装输出须保留本次产物章");
        output.setCount(output.getMaxStackSize() - 1);
        ItemStack nextMeal = output.copyWithCount(2);
        int shelfHours = FreshnessCategories.shelfLifeHours(nextMeal);
        FreshnessData.stampAt(nextMeal, now - shelfHours * 30L - 504L * 60L, 504);
        pot.getInventory().setStackInSlot(6, nextMeal);
        pot.getInventory().setStackInSlot(7, new ItemStack(Items.BOWL, 2));
        CookingPotBlockEntity.cookingTick(helper.getLevel(), helper.absolutePos(rel), helper.getBlockState(rel), pot);
        helper.assertTrue(output.getCount() == output.getMaxStackSize() && pot.getMeal().getCount() == 1
                && pot.getInventory().getStackInSlot(7).getCount() == 1,
                "碗装部分合并只搬有空位的一件, 留下另一份菜与碗");
        helper.assertTrue(FreshnessData.stoppedHours(output) == 504, "碗装合并保留最大停表额度");
        assertRatio(helper, output, 0.5D, "碗装合并取较老一批");
        helper.succeed();
        } finally {
            FreshnessConfig.MODE.set(previous);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void stoveInheritsEachSlotSeparately(GameTestHelper helper) throws Exception {
        BlockPos rel = new BlockPos(1, 2, 9);
        helper.setBlock(rel.above(), Blocks.AIR);
        helper.setBlock(rel, ModBlocks.STOVE.get().defaultBlockState().setValue(StoveBlock.LIT, true));
        AbstractStoveBlockEntity stove = (AbstractStoveBlockEntity) helper.getBlockEntity(rel);
        long now = FreshnessClockData.nowMinute(helper.getLevel());
        ItemStack beef = halfFreshBeef(now);
        ItemStack pork = new ItemStack(Items.PORKCHOP);
        FreshnessData.stamp(pork, now);
        var beefRecipe = stove.getCookingRecipe(beef);
        var porkRecipe = stove.getCookingRecipe(pork);
        helper.assertTrue(beefRecipe.isPresent() && porkRecipe.isPresent(),
                "夹具: 炉灶须为牛肉和猪肉找到 FD1.3.2 CAMPFIRE_COOKING 配方");
        helper.assertTrue(!stove.shouldDropItems(), "夹具: 炉灶上方 grill 区域必须无遮挡");
        helper.assertTrue(stove.placeFood(null, beef, beefRecipe.orElseThrow().getCookingTime()),
                "夹具: 牛肉按真实配方时长放入炉灶");
        helper.assertTrue(stove.placeFood(null, pork, porkRecipe.orElseThrow().getCookingTime()),
                "夹具: 猪肉按真实配方时长放入炉灶");
        java.lang.reflect.Field times = AbstractStoveBlockEntity.class.getDeclaredField("cookingProgress");
        times.setAccessible(true);
        java.util.Arrays.fill((int[]) times.get(stove), 10_000);
        AbstractStoveBlockEntity.serverTick(helper.getLevel(), helper.absolutePos(rel), helper.getBlockState(rel), stove);
        ItemStack beefProduct = findDrop(helper.getLevel(), helper.absolutePos(rel), Items.COOKED_BEEF);
        ItemStack porkProduct = findDrop(helper.getLevel(), helper.absolutePos(rel), Items.COOKED_PORKCHOP);
        helper.assertTrue(beefProduct != null && porkProduct != null, "炉灶真实 tick 应逐格出两种菜");
        assertRatio(helper, beefProduct, 0.75D, "旧肉单格继承");
        assertRatio(helper, porkProduct, 1.0D, "新肉不能受另一格旧肉污染");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void offDoesNotStampAndDryRunDoesNotRefuseCooking(GameTestHelper helper) throws Exception {
        FreshnessConfig.Mode previous = FreshnessConfig.MODE.get();
        try {
            ServerLevel level = helper.getLevel();
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            BlockPos rel = new BlockPos(1, 2, 13);
            helper.setBlock(rel, Blocks.AIR);
            helper.setBlock(rel, ModBlocks.SKILLET.get());
            SkilletBlockEntity pan = (SkilletBlockEntity) helper.getBlockEntity(rel);
            FreshnessConfig.MODE.set(FreshnessConfig.Mode.OFF);
            helper.assertTrue(pan.addItemToCook(new ItemStack(Items.BEEF), player).isEmpty(),
                    "夹具: 未盖章生牛肉通过 FD 真实入口放入煎锅");
            SkilletBlockEntity.cookingTick(level, helper.absolutePos(rel), helper.getBlockState(rel), pan);
            helper.assertTrue(!FreshnessData.isStamped(pan.getStoredStack()), "OFF 机器 tick 不得盖章");
            ItemStack offPan = skilletWith(Items.BEEF, FreshnessClockData.nowMinute(level));
            offPan.getItem().finishUsingItem(offPan, level, player);
            ItemStack offProduct = findInInventory(player, Items.COOKED_BEEF);
            helper.assertTrue(offProduct != null && !FreshnessData.isStamped(offProduct), "OFF 手持出品不得盖章");
            player.getInventory().clearContent();
            FreshnessConfig.MODE.set(FreshnessConfig.Mode.DRY_RUN);
            ItemStack dryPan = skilletWith(Items.BEEF, FreshnessClockData.nowMinute(level) - 40L * 24L * 60L);
            dryPan.getItem().finishUsingItem(dryPan, level, player);
            ItemStack dryProduct = findInInventory(player, Items.COOKED_BEEF);
            helper.assertTrue(dryProduct != null && FreshnessData.isStamped(dryProduct), "DRY_RUN 烂原料照常做菜并继承");
            helper.setBlock(new BlockPos(5, 2, 13), ModBlocks.COOKING_POT.get());
            CookingPotBlockEntity pot = (CookingPotBlockEntity) helper.getBlockEntity(new BlockPos(5, 2, 13));
            ItemStack old = new ItemStack(Items.COOKED_BEEF);
            old.getOrCreateTag().putString("ChefQuality", "old");
            pot.getInventory().setStackInSlot(8, old);
            pot.getInventory().setStackInSlot(6, new ItemStack(Items.COOKED_BEEF));
            CookingPotBlockEntity.cookingTick(level, helper.absolutePos(new BlockPos(5, 2, 13)),
                    helper.getBlockState(new BlockPos(5, 2, 13)), pot);
            helper.assertTrue(old.getCount() == 1 && pot.getMeal().getCount() == 1,
                    "DRY_RUN 保持 FD1.3.2 的 NBT 敏感输出行为, 不合并不同 ChefQuality");
        } finally {
            FreshnessConfig.MODE.set(previous);
        }
        helper.succeed();
    }

    private static void timer(Class<?> type, Object machine, String name, int value) throws Exception {
        java.lang.reflect.Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(machine, value);
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
