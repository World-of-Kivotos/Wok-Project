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

    /**
     * 首测权重与售价的期望值, 逐字转抄 {@code docs/Ore_Fish_And_Soup.md} 的两张表 (20%/8%/2%/1%/0.2%,
     * 20/80/400/600/2000), 不从 {@link OreFishingConfig} 反取。
     *
     * 理由: 从被测配置反取期望值等于把实现复述一遍 —— 整张权重表或售价表被改错时断言会跟着一起改, 永远为绿。
     * 数值是首测方案, 调平衡时应当同时改配置默认值与设计文档, 这张表跟着文档走, 改单边就会红。
     */
    private static final Map<OreFishType, Integer> DOCUMENTED_CATCH_WEIGHTS = Map.of(
            OreFishType.IRON, 2000,
            OreFishType.GOLD, 800,
            OreFishType.DIAMOND, 200,
            OreFishType.EMERALD, 100,
            OreFishType.DARK_GOLD, 20);
    private static final Map<OreFishType, Long> DOCUMENTED_SELL_PRICES = Map.of(
            OreFishType.IRON, 20L,
            OreFishType.GOLD, 80L,
            OreFishType.DIAMOND, 400L,
            OreFishType.EMERALD, 600L,
            OreFishType.DARK_GOLD, 2000L);

    private OreFishGameTests() {
    }

    @GameTest(template = "empty", batch = "ore_fish")
    public static void weightedRollKeepsTheOriginalCatchRemainder(GameTestHelper helper) {
        int cumulative = 0;
        for (OreFishType type : OreFishType.values()) {
            int weight = DOCUMENTED_CATCH_WEIGHTS.get(type);
            helper.assertTrue(OreFishingConfig.catchWeight(type) == weight,
                    type.id() + " 的默认钓获权重是 " + OreFishingConfig.catchWeight(type)
                            + ", 设计文档写的是 " + weight + "; 配置与文档必须同改");
            helper.assertTrue(OreFishCatchHandler.typeForRoll(cumulative) == type,
                    "第 " + cumulative + " 个万分位必须落在 " + type.id() + " 区间的首位");
            helper.assertTrue(OreFishCatchHandler.typeForRoll(cumulative + weight - 1) == type,
                    "第 " + (cumulative + weight - 1) + " 个万分位必须落在 " + type.id() + " 区间的末位");
            cumulative += weight;
        }
        helper.assertTrue(cumulative == 3120,
                "五种矿石鱼的总权重必须是文档写的 3120/10000, 实得 " + cumulative);
        helper.assertTrue(OreFishCatchHandler.typeForRoll(cumulative) == null
                        && OreFishCatchHandler.typeForRoll(9_999) == null,
                "未分配的万分位必须保留原渔获池");
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
            // 期望值取设计文档的售价表 (金矿鱼 80/条), 不取被测配置; 售价表改错时这一条必须红。
            long expected = DOCUMENTED_SELL_PRICES.get(OreFishType.GOLD) * 3L;
            helper.assertTrue(OreFishingConfig.sellPrice(OreFishType.GOLD)
                            == DOCUMENTED_SELL_PRICES.get(OreFishType.GOLD),
                    "金矿鱼默认收购价与设计文档不一致: 配置 " + OreFishingConfig.sellPrice(OreFishType.GOLD)
                            + ", 文档 " + DOCUMENTED_SELL_PRICES.get(OreFishType.GOLD));
            helper.assertTrue(sold.soldCount() == 3 && sold.creditsGranted() == expected && fish.isEmpty()
                            && EconomyServices.economyService().creditBalance(player) == before + expected,
                    "卖主手整组金矿鱼必须扣尽三条并按文档售价入账");

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
            long beforeZero = EconomyServices.economyService().creditBalance(player);
            OreFishSellService.SellResult zero = OreFishSellService.sellMainHand(player);
            // 深档实发归零照样成交 (与卖菜"收购曲线到底仍算卖出"同口径): 鱼必须真离手, 余额一分不动。
            // 若退回"保留鱼"的老行为, grantDaily 已经把毛收入记进当日 faucet 计数器, 计数器会记下一笔
            // 从未发生的销售 —— 这一条断言正是拦那个回退的。
            helper.assertTrue(zero.soldCount() == 1 && zero.creditsGranted() == 0L && iron.isEmpty()
                            && EconomyServices.economyService().creditBalance(player) == beforeZero,
                    "统一 faucet 深档实发归零时必须照常扣鱼且余额不变");
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
