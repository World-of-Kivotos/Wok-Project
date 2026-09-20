package com.miningdim.job.fisher.ore;

import com.miningdim.core.MiningConstants;
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
            helper.succeed();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Tide 1.6.5 compatibility test could not invoke its verified retrieve path", exception);
        } finally {
            OreFishGameTests.restoreCatchWeights(originalWeights);
        }
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
