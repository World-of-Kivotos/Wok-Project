package com.miningdim.job.fisher.ore;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.fisher.size.FishRecord;
import com.miningdim.job.fisher.size.FishSizeClass;
import com.miningdim.job.fisher.size.FishSizeNbt;
import com.miningdim.job.fisher.size.FishingRecords;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The optional Tide bridge is tested only when its exact runtime dependency is present. */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class TideOreFishCompatibilityGameTests {
    private static final String TIDE_HOOK = "com.li64.tide.registries.entities.misc.fishing.TideFishingHook";
    private static final String TIDE_CATCH_TYPE = TIDE_HOOK + "$CatchType";

    private TideOreFishCompatibilityGameTests() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @GameTest(template = "empty", batch = "ore_fish")
    public static void successfulTideRetrieveReplacesOnlyTheSuccessfulCatch(GameTestHelper helper) {
        if (!ModList.get().isLoaded("tide")) {
            helper.succeed();
            return;
        }
        Map<OreFishType, Integer> originalWeights = OreFishGameTests.forceIronOnlyCatchWeight();
        try {
            ServerLevel mining = helper.getLevel().getServer().getLevel(MiningConstants.MINING_LEVEL);
            if (mining == null) {
                throw new IllegalStateException("Mining dimension is unavailable to Tide ore fish GameTest");
            }
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            player.teleportTo(mining, 48.5D, 80.0D, 0.5D, 0.0F, 0.0F);
            BlockPos water = new BlockPos(48, 80, 0);
            mining.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(water).inflate(2.0D))
                    .forEach(ItemEntity::discard);
            mining.setBlock(water, Blocks.WATER.defaultBlockState(), 3);
            ItemStack rod = new ItemStack(Items.FISHING_ROD);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, rod);
            player.fishing = new FishingHook(player, mining, 0, 0);

            Class<?> hookClass = Class.forName(TIDE_HOOK);
            Object hook = newHook(hookClass, mining);
            ((Entity) hook).setPos(48.5D, 80.5D, 0.5D);
            setField(hookClass, hook, "rod", rod);
            setField(hookClass, hook, "nibble", 1);
            setField(hookClass, hook, "fluid", mining.getFluidState(water));
            setField(hookClass, hook, "hookedItems", new ArrayList<>(List.of(new ItemStack(Items.COD))));
            Class<?> catchTypeClass = Class.forName(TIDE_CATCH_TYPE);
            setField(hookClass, hook, "catchType", Enum.valueOf((Class) catchTypeClass, "FISH"));

            Method retrieve = hookClass.getMethod("retrieve", ItemStack.class, ServerLevel.class, Player.class);
            retrieve.invoke(hook, rod, mining, player);

            List<ItemStack> resolved = (List<ItemStack>) getField(hookClass, hook, "hookedItems");
            int spawnedOreFish = mining.getEntitiesOfClass(ItemEntity.class,
                            new net.minecraft.world.phys.AABB(water).inflate(2.0D),
                            entity -> entity.getItem().is(OreFishingItems.FISH.get(OreFishType.IRON).get()))
                    .stream()
                    .map(ItemEntity::getItem)
                    .mapToInt(ItemStack::getCount)
                    .sum();
            helper.assertTrue(resolved.size() == 1 && resolved.get(0).is(OreFishingItems.FISH.get(OreFishType.IRON).get())
                            && spawnedOreFish == 1,
                    "Tide 成功 retrieve 必须在实际掉落循环前把 COD 替换为一条铁矿鱼");
            FishRecord record = FishingRecords.get(player, OreFishingItems.FISH.get(OreFishType.IRON).getId());
            helper.assertTrue(record != null && record.count() == 1,
                    "Tide 路径不发 ItemFishedEvent, 替换后的矿石鱼必须在同一注入点完成体型结算");

            // 标签必须打在真正落地的那一栈上: 单竿 65% 是不写标签的标准档, 连续出竿直到落地的鱼读出非标准档。
            // 若结算作用在副本上, 落地的鱼永远没有标签, 这里必红; 60 竿全是标准档的概率约 6e-12。
            int casts = 0;
            FishSizeClass landedClass = FishSizeClass.STANDARD;
            while (landedClass == FishSizeClass.STANDARD && casts < 60) {
                List<ItemStack> landed = castTide(mining, player, water, rod,
                        new ArrayList<>(List.of(new ItemStack(Items.COD))));
                casts++;
                helper.assertTrue(landed.size() == 1, "每一竿 Tide retrieve 都必须落地一条铁矿鱼, 第 " + casts + " 竿实得 " + landed.size());
                landedClass = FishSizeNbt.read(landed.get(0)).map(FishSizeNbt.Info::sizeClass).orElse(FishSizeClass.STANDARD);
            }
            helper.assertTrue(landedClass != FishSizeClass.STANDARD
                            && FishingRecords.get(player, OreFishingItems.FISH.get(OreFishType.IRON).getId()).count() == 1 + casts,
                    "Tide 落地的矿石鱼必须带着体型标签, 且每一竿都记入钓获次数");
            helper.succeed();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Tide 1.6.5 compatibility test could not invoke its verified retrieve path", exception);
        } finally {
            OreFishGameTests.restoreCatchWeights(originalWeights);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @GameTest(template = "empty", batch = "ore_fish")
    public static void immutableFallbackCatchIsReplacedAndMeasured(GameTestHelper helper) {
        if (!ModList.get().isLoaded("tide")) {
            helper.succeed();
            return;
        }
        // Tide 空表兜底给的是不可变 List.of(鲑鱼): 原先原地 clear() 会抛 UnsupportedOperationException 把整竿吞掉。
        Map<OreFishType, Integer> originalWeights = OreFishGameTests.forceIronOnlyCatchWeight();
        try {
            ServerLevel mining = helper.getLevel().getServer().getLevel(MiningConstants.MINING_LEVEL);
            if (mining == null) {
                throw new IllegalStateException("Mining dimension is unavailable to Tide ore fish GameTest");
            }
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            player.teleportTo(mining, 56.5D, 80.0D, 0.5D, 0.0F, 0.0F);
            BlockPos water = new BlockPos(56, 80, 0);
            mining.setBlock(water, Blocks.WATER.defaultBlockState(), 3);
            ItemStack rod = new ItemStack(Items.FISHING_ROD);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, rod);
            player.fishing = new FishingHook(player, mining, 0, 0);

            Class<?> hookClass = Class.forName(TIDE_HOOK);
            Object hook = newHook(hookClass, mining);
            ((Entity) hook).setPos(56.5D, 80.5D, 0.5D);
            setField(hookClass, hook, "rod", rod);
            setField(hookClass, hook, "nibble", 1);
            setField(hookClass, hook, "fluid", mining.getFluidState(water));
            setField(hookClass, hook, "hookedItems", List.of(new ItemStack(Items.SALMON)));
            setField(hookClass, hook, "catchType", Enum.valueOf((Class) Class.forName(TIDE_CATCH_TYPE), "FISH"));

            Method retrieve = hookClass.getMethod("retrieve", ItemStack.class, ServerLevel.class, Player.class);
            retrieve.invoke(hook, rod, mining, player);

            List<ItemStack> resolved = (List<ItemStack>) getField(hookClass, hook, "hookedItems");
            FishRecord record = FishingRecords.get(player, OreFishingItems.FISH.get(OreFishType.IRON).getId());
            helper.assertTrue(resolved.size() == 1 && resolved.get(0).is(OreFishingItems.FISH.get(OreFishType.IRON).get())
                            && record != null && record.count() == 1,
                    "不可变兜底列表必须换成新的可变列表再替换, 且替换后的鱼照常结算体型");
            helper.succeed();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Tide 1.6.5 compatibility test could not invoke its verified retrieve path", exception);
        } finally {
            OreFishGameTests.restoreCatchWeights(originalWeights);
        }
    }

    /** 用新的 Tide 鱼钩在指定水格上走一次成功的 retrieve, 返回落地的铁矿鱼栈。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<ItemStack> castTide(ServerLevel level, ServerPlayer player, BlockPos water, ItemStack rod,
                                            List<ItemStack> hookedItems) throws ReflectiveOperationException {
        net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(water).inflate(2.0D);
        level.getEntitiesOfClass(ItemEntity.class, area).forEach(ItemEntity::discard);
        player.fishing = new FishingHook(player, level, 0, 0);
        Class<?> hookClass = Class.forName(TIDE_HOOK);
        Object hook = newHook(hookClass, level);
        ((Entity) hook).setPos(water.getX() + 0.5D, water.getY() + 0.5D, water.getZ() + 0.5D);
        setField(hookClass, hook, "rod", rod);
        setField(hookClass, hook, "nibble", 1);
        setField(hookClass, hook, "fluid", level.getFluidState(water));
        setField(hookClass, hook, "hookedItems", hookedItems);
        setField(hookClass, hook, "catchType", Enum.valueOf((Class) Class.forName(TIDE_CATCH_TYPE), "FISH"));
        hookClass.getMethod("retrieve", ItemStack.class, ServerLevel.class, Player.class).invoke(hook, rod, level, player);
        return level.getEntitiesOfClass(ItemEntity.class, area,
                        entity -> entity.getItem().is(OreFishingItems.FISH.get(OreFishType.IRON).get()))
                .stream().map(ItemEntity::getItem).toList();
    }

    private static Object newHook(Class<?> hookClass, ServerLevel level) throws ReflectiveOperationException {
        Constructor<?> constructor = hookClass.getConstructor(EntityType.class, net.minecraft.world.level.Level.class);
        return constructor.newInstance(EntityType.FISHING_BOBBER, level);
    }

    private static void setField(Class<?> type, Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Class<?> type, Object target, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
