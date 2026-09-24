package com.miningdim.job.fisher.ore;

import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.job.fisher.size.FishMeasurement;
import com.miningdim.job.fisher.size.FishSizeClass;
import com.miningdim.job.fisher.size.FishSizeNbt;
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
            helper.assertTrue(rejected.nothingToSell() && soup.getCount() == 2,
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

    @GameTest(template = "empty", batch = "ore_fish")
    public static void sellSweepsSizeClassesButLeavesTrophiesToTheMainHand(GameTestHelper helper) {
        IEconomyService previous = currentEconomy();
        SqliteEconomyLedger ledger = registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            player.getInventory().clearContent();
            player.getInventory().selected = 0;
            var inventory = player.getInventory();
            inventory.setItem(0, sized(OreFishType.GOLD, 2, FishSizeClass.SMALL));
            inventory.setItem(5, sized(OreFishType.GOLD, 3, FishSizeClass.STANDARD));
            inventory.setItem(6, sized(OreFishType.GOLD, 1, FishSizeClass.LARGE));
            inventory.setItem(7, sized(OreFishType.GOLD, 1, FishSizeClass.TROPHY));
            inventory.setItem(8, sized(OreFishType.IRON, 4, FishSizeClass.STANDARD));
            inventory.offhand.set(0, sized(OreFishType.GOLD, 5, FishSizeClass.STANDARD));

            OreFishSellService.SellResult held = OreFishSellService.sellMainHand(player);
            long goldPrice = DOCUMENTED_SELL_PRICES.get(OreFishType.GOLD);
            helper.assertTrue(held.soldCount() == 6 && held.creditsGranted() == goldPrice * 6,
                    "/fishing sell 卖主手鱼种的小/标准/大三档共 6 条, 实得 " + held.soldCount());
            helper.assertTrue(inventory.getItem(0).isEmpty() && inventory.getItem(5).isEmpty() && inventory.getItem(6).isEmpty()
                            && inventory.getItem(7).getCount() == 1 && inventory.getItem(8).getCount() == 4
                            && inventory.offhand.get(0).getCount() == 5,
                    "奖杯、其它鱼种与副手都不得被连带卖掉");

            OreFishSellService.SellResult all = OreFishSellService.sellAll(player);
            helper.assertTrue(all.soldCount() == 4 && inventory.getItem(8).isEmpty() && inventory.getItem(7).getCount() == 1,
                    "/fishing sell all 卖全部非奖杯矿石鱼, 奖杯留在背包");
            helper.assertTrue(OreFishSellService.sellAll(player).nothingToSell(),
                    "只剩奖杯时批量出售无鱼可卖");

            inventory.selected = 7;
            OreFishSellService.SellResult trophy = OreFishSellService.sellMainHand(player);
            helper.assertTrue(trophy.soldCount() == 1 && inventory.getItem(7).isEmpty(),
                    "奖杯拿在主手上执行 /fishing sell 才会卖掉");
            helper.succeed();
        } finally {
            restoreEconomy(previous);
            MiningDb.close(ledger.connection());
        }
    }

    @GameTest(template = "empty", batch = "ore_fish")
    public static void failedPayoutRefundsTheExactSizedStacks(GameTestHelper helper) {
        IEconomyService previous = currentEconomy();
        EconomyServices.reset();
        EconomyServices.registerEconomyService(new PayoutFailingEconomy());
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            player.getInventory().clearContent();
            player.getInventory().selected = 0;
            ItemStack large = sized(OreFishType.DIAMOND, 2, FishSizeClass.LARGE);
            ItemStack standard = sized(OreFishType.DIAMOND, 3, FishSizeClass.STANDARD);
            player.getInventory().setItem(0, large.copy());
            player.getInventory().setItem(1, standard.copy());
            boolean thrown = false;
            try {
                OreFishSellService.sellMainHand(player);
            } catch (IllegalStateException expected) {
                thrown = true;
            }
            int largeBack = 0;
            int standardBack = 0;
            for (ItemStack stack : player.getInventory().items) {
                if (ItemStack.isSameItemSameTags(stack, large)) {
                    largeBack += stack.getCount();
                } else if (ItemStack.isSameItemSameTags(stack, standard)) {
                    standardBack += stack.getCount();
                }
            }
            helper.assertTrue(thrown && largeBack == 2 && standardBack == 3,
                    "入账失败必须重抛, 并把扣下的栈连同体型标签原样退回");
            helper.succeed();
        } finally {
            restoreEconomy(previous);
        }
    }

    private static ItemStack sized(OreFishType type, int count, FishSizeClass sizeClass) {
        ItemStack stack = new ItemStack(OreFishingItems.FISH.get(type).get(), count);
        FishSizeNbt.stamp(stack, new FishMeasurement(400 + count, 900_000L + count, sizeClass),
                UUID.randomUUID(), "Angler", count);
        return stack;
    }

    /** 只在 grantDaily 上失败的替身, 用来走退款分支; 其余方法本用例不会调用。 */
    private static final class PayoutFailingEconomy implements IEconomyService {
        @Override
        public long creditBalance(ServerPlayer player) {
            return 0L;
        }

        @Override
        public long heartstoneBalance(ServerPlayer player) {
            return 0L;
        }

        @Override
        public boolean tryCharge(ServerPlayer player, com.miningdim.economy.Currency currency, long amount) {
            return false;
        }

        @Override
        public void grant(ServerPlayer player, com.miningdim.economy.Currency currency, long amount) {
            throw new IllegalStateException("grant is not expected in this test");
        }

        @Override
        public boolean tryChargeDaily(ServerPlayer player, com.miningdim.economy.Currency currency, long amount,
                                      String dailyKey, long dailyCap) {
            return false;
        }

        @Override
        public long settleOreSale(ServerPlayer player, com.miningdim.economy.EconomyConstants.HighValueOre ore,
                                  int countSoFar, double basePrice) {
            return 0L;
        }

        @Override
        public int recordMinedOreDrops(ServerPlayer player, net.minecraft.world.level.block.Block block, int producedCount) {
            return -1;
        }

        @Override
        public long grantDaily(ServerPlayer player, long rawCredit, String faucetKey, long dailyCap) {
            throw new IllegalStateException("simulated payout failure");
        }

        @Override
        public long grantAzureDaily(ServerPlayer player, long amount, long dailyCap) {
            return 0L;
        }

        @Override
        public boolean isAfkFrozen(ServerPlayer player) {
            return false;
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

    public static Map<OreFishType, Integer> forceIronOnlyCatchWeight() {
        Map<OreFishType, Integer> original = new java.util.EnumMap<>(OreFishType.class);
        for (OreFishType type : OreFishType.values()) {
            original.put(type, OreFishingConfig.CATCH_WEIGHTS.get(type).get());
            OreFishingConfig.CATCH_WEIGHTS.get(type).set(type == OreFishType.IRON ? 10_000 : 0);
        }
        return original;
    }

    public static void restoreCatchWeights(Map<OreFishType, Integer> original) {
        for (OreFishType type : OreFishType.values()) {
            OreFishingConfig.CATCH_WEIGHTS.get(type).set(original.get(type));
        }
    }
}
