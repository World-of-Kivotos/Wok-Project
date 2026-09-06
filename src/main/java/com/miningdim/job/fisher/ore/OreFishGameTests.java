package com.miningdim.job.fisher.ore;

import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.store.MiningDb;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemFishedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class OreFishGameTests {
    private OreFishGameTests() {
    }

    @GameTest(template = "empty", batch = "ore_fish")
    public static void weightedRollKeepsTheOriginalCatchRemainder(GameTestHelper helper) {
        helper.assertTrue(OreFishCatchHandler.typeForRoll(0) == OreFishType.IRON,
                "首个万分位必须命中铁矿鱼");
        helper.assertTrue(OreFishCatchHandler.typeForRoll(OreFishingConfig.catchWeight(OreFishType.IRON)) == OreFishType.GOLD,
                "铁矿鱼权重后的首个万分位必须命中金矿鱼");
        int total = 0;
        for (OreFishType type : OreFishType.values()) {
            total += OreFishingConfig.catchWeight(type);
        }
        if (total < 10_000) {
            helper.assertTrue(OreFishCatchHandler.typeForRoll(total) == null,
                    "未分配的万分位必须保留原渔获池");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "ore_fish")
    public static void replacementClearsTheOriginalCatch(GameTestHelper helper) {
        NonNullList<ItemStack> drops = NonNullList.create();
        drops.add(new ItemStack(Items.COD));
        drops.add(new ItemStack(Items.SALMON));
        boolean replaced = OreFishCatchHandler.replaceDrops(drops, 0);
        helper.assertTrue(replaced && drops.size() == 1 && drops.get(0).is(OreFishingItems.FISH.get(OreFishType.IRON).get()),
                "命中矿石鱼时必须清掉原渔获并只留下单条矿石鱼");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "ore_fish")
    public static void vanillaRetrieveGuardsMiningWaterAndCancellation(GameTestHelper helper) {
        Map<OreFishType, Integer> originalWeights = forceIronOnlyCatchWeight();
        try {
            ServerLevel mining = miningLevel(helper);
            ServerPlayer miningPlayer = miningPlayer(helper, mining);
            BlockPos waterPos = new BlockPos(0, 80, 0);
            mining.setBlock(waterPos, Blocks.WATER.defaultBlockState(), 3);
            retrieveSuccessfulCatch(miningPlayer, mining, waterPos);
            helper.assertTrue(oreFishDrops(mining, waterPos) == 1,
                    "矿洞水域的真实 FishingHook.retrieve 必须生成一条铁矿鱼");

            BlockPos dryPos = new BlockPos(4, 80, 0);
            mining.setBlock(dryPos, Blocks.AIR.defaultBlockState(), 3);
            retrieveSuccessfulCatch(miningPlayer, mining, dryPos);
            helper.assertTrue(oreFishDrops(mining, dryPos) == 0,
                    "矿洞非水域的真实 FishingHook.retrieve 不得生成矿石鱼");

            ServerPlayer overworldPlayer = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            BlockPos overworldWater = helper.absolutePos(new BlockPos(4, 1, 1));
            helper.setBlock(new BlockPos(4, 1, 1), Blocks.WATER.defaultBlockState());
            retrieveSuccessfulCatch(overworldPlayer, helper.getLevel(), overworldWater);
            helper.assertTrue(oreFishDrops(helper.getLevel(), overworldWater) == 0,
                    "非矿洞维度的真实 FishingHook.retrieve 不得生成矿石鱼");

            boolean[] cancellationFired = {false};
            Object canceller = new Object() {
                @SubscribeEvent
                public void onItemFished(ItemFishedEvent event) {
                    cancellationFired[0] = true;
                    event.setCanceled(true);
                }
            };
            MinecraftForge.EVENT_BUS.register(canceller);
            BlockPos cancelledPos = new BlockPos(8, 80, 0);
            mining.setBlock(cancelledPos, Blocks.WATER.defaultBlockState(), 3);
            try {
                retrieveSuccessfulCatch(miningPlayer, mining, cancelledPos);
            } finally {
                MinecraftForge.EVENT_BUS.unregister(canceller);
            }
            helper.assertTrue(cancellationFired[0] && oreFishDrops(mining, cancelledPos) == 0,
                    "已取消的真实 ItemFishedEvent 不得生成矿石鱼");
            helper.succeed();
        } finally {
            restoreCatchWeights(originalWeights);
        }
    }

    @GameTest(template = "empty", batch = "ore_fish")
    public static void sellingMainHandStackCreditsExactlyAndRejectsSoup(GameTestHelper helper) {
        IEconomyService previous = currentEconomy();
        SqliteEconomyLedger ledger = registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            ItemStack fish = new ItemStack(OreFishingItems.FISH.get(OreFishType.GOLD).get(), 3);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, fish);
            long before = EconomyServices.economyService().creditBalance(player);
            OreFishSellService.SellResult sold = OreFishSellService.sellMainHand(player);
            long expected = OreFishingConfig.sellPrice(OreFishType.GOLD) * 3L;
            helper.assertTrue(sold.soldCount() == 3 && sold.creditsGranted() == expected && fish.isEmpty()
                            && EconomyServices.economyService().creditBalance(player) == before + expected,
                    "卖主手整组金矿鱼必须扣尽三条并按配置价入账");

            ItemStack soup = new ItemStack(OreFishingItems.SOUPS.get(OreFishType.GOLD).get(), 2);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, soup);
            OreFishSellService.SellResult rejected = OreFishSellService.sellMainHand(player);
            helper.assertTrue(rejected.invalidHeldItem() && soup.getCount() == 2,
                    "鱼羹不得出售且主手物品不得被吞掉");

            EconomyServices.economyService().grantDaily(player, 600_000L,
                    com.miningdim.economy.EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY,
                    com.miningdim.economy.EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER);
            ItemStack iron = new ItemStack(OreFishingItems.FISH.get(OreFishType.IRON).get());
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, iron);
            OreFishSellService.SellResult zero = OreFishSellService.sellMainHand(player);
            helper.assertTrue(zero.soldCount() == 0 && zero.creditsGranted() == 0L && iron.getCount() == 1,
                    "统一 faucet 深档实发归零时必须保留矿石鱼");
            helper.succeed();
        } finally {
            restoreEconomy(previous);
            MiningDb.close(ledger.connection());
        }
    }

    private static SqliteEconomyLedger registerFreshEconomy() {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        Function<UUID, PlayerAbuseState> resolver = id -> states.computeIfAbsent(id, ignored -> new PlayerAbuseState());
        EconomyServices.reset();
        EconomyServices.registerEconomyService(new EconomyService(ledger, new AbuseGuard(), resolver));
        return ledger;
    }

    private static IEconomyService currentEconomy() {
        return EconomyServices.isRegistered() ? EconomyServices.economyService() : null;
    }

    private static void restoreEconomy(IEconomyService previous) {
        EconomyServices.reset();
        if (previous != null) {
            EconomyServices.registerEconomyService(previous);
        }
    }

    private static NonNullList<ItemStack> drops() {
        NonNullList<ItemStack> drops = NonNullList.create();
        drops.add(new ItemStack(Items.COD));
        return drops;
    }

    private static FishingHook hook(ServerPlayer player, ServerLevel level, BlockPos pos) {
        player.setPos(Vec3.atCenterOf(pos));
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.FISHING_ROD));
        FishingHook hook = new FishingHook(player, level, 0, 0);
        hook.setPos(Vec3.atCenterOf(pos));
        player.fishing = hook;
        setNibble(hook, 1);
        return hook;
    }

    private static void retrieveSuccessfulCatch(ServerPlayer player, ServerLevel level, BlockPos pos) {
        level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(2.0D))
                .forEach(ItemEntity::discard);
        FishingHook hook = hook(player, level, pos);
        hook.retrieve(player.getMainHandItem());
    }

    private static int oreFishDrops(ServerLevel level, BlockPos pos) {
        return level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(2.0D)).stream()
                .map(ItemEntity::getItem)
                .filter(stack -> stack.is(OreFishingItems.FISH.get(OreFishType.IRON).get()))
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    private static void setNibble(FishingHook hook, int nibble) {
        try {
            java.lang.reflect.Field field = FishingHook.class.getDeclaredField("nibble");
            field.setAccessible(true);
            field.setInt(hook, nibble);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot prepare a successful vanilla fishing catch", exception);
        }
    }

    private static ServerLevel miningLevel(GameTestHelper helper) {
        ServerLevel mining = helper.getLevel().getServer().getLevel(MiningConstants.MINING_LEVEL);
        if (mining == null) {
            throw new IllegalStateException("Mining dimension is unavailable to ore fish GameTest");
        }
        return mining;
    }

    private static ServerPlayer miningPlayer(GameTestHelper helper, ServerLevel mining) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.teleportTo(mining, 0.5D, 80.0D, 0.5D, 0.0F, 0.0F);
        return player;
    }

    static Map<OreFishType, Integer> forceIronOnlyCatchWeight() {
        Map<OreFishType, Integer> original = new java.util.EnumMap<>(OreFishType.class);
        for (OreFishType type : OreFishType.values()) {
            original.put(type, OreFishingConfig.CATCH_WEIGHTS.get(type).get());
            OreFishingConfig.CATCH_WEIGHTS.get(type).set(type == OreFishType.IRON ? 10_000 : 0);
        }
        return original;
    }

    static void restoreCatchWeights(Map<OreFishType, Integer> original) {
        for (OreFishType type : OreFishType.values()) {
            OreFishingConfig.CATCH_WEIGHTS.get(type).set(original.get(type));
        }
    }
}
