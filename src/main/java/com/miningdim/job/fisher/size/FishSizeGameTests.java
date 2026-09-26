package com.miningdim.job.fisher.size;

import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.fisher.journal.FishingJournalCatalog;
import com.miningdim.job.fisher.journal.FishingJournalEntry;
import com.miningdim.job.fisher.ore.OreFishType;
import com.miningdim.job.fisher.ore.OreFishingItems;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemFishedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FishSizeGameTests {
    private static final ResourceLocation COD = new ResourceLocation("minecraft", "cod");
    /** 与 data/miningdim/fishing/sizes/vanilla.json 的鳕鱼档案逐字一致 (设计文档体型表同一行)。 */
    private static final FishSizeProfile DOCUMENTED_COD = new FishSizeProfile(COD, 30.0D, 60.0D, 130.0D, 0.0077D, 3.07D);

    private FishSizeGameTests() {
    }

    @GameTest(template = "empty", batch = "fish_size")
    public static void measurementSpansTheProfileAndClassesFollowQuantiles(GameTestHelper helper) {
        FishMeasurement smallest = FishSizeRoller.measure(DOCUMENTED_COD, -9.0D, 0.0D);
        FishMeasurement median = FishSizeRoller.measure(DOCUMENTED_COD, 0.0D, 0.0D);
        FishMeasurement largest = FishSizeRoller.measure(DOCUMENTED_COD, 9.0D, 0.0D);
        helper.assertTrue(smallest.lengthMm() == 300 && smallest.sizeClass() == FishSizeClass.SMALL,
                "z 截断到 -3 时恰好是档案最小体长 30 cm, 实得 " + smallest);
        helper.assertTrue(median.lengthMm() == 600 && median.sizeClass() == FishSizeClass.STANDARD,
                "z=0 落在中位体长 60 cm, 实得 " + median);
        helper.assertTrue(largest.lengthMm() == 1300 && largest.sizeClass() == FishSizeClass.TROPHY,
                "z 截断到 +3 时恰好是奖杯上限 130 cm, 实得 " + largest);
        long expectedMedianMg = Math.round(0.0077D * Math.pow(60.0D, 3.07D) * 1000.0D);
        helper.assertTrue(median.weightMg() == expectedMedianMg,
                "条件因子为 1 时体重必须等于 a*L^b, 期望 " + expectedMedianMg + " mg, 实得 " + median.weightMg());
        long heavy = FishSizeRoller.measure(DOCUMENTED_COD, 0.0D, 50.0D).weightMg();
        long light = FishSizeRoller.measure(DOCUMENTED_COD, 0.0D, -50.0D).weightMg();
        helper.assertTrue(Math.abs(heavy - Math.round(expectedMedianMg * 1.2D)) <= 1
                        && Math.abs(light - Math.round(expectedMedianMg * 0.8D)) <= 1,
                "同体长个体的肥瘦差必须钳在 +-20%");
        helper.assertTrue(FishSizeClass.forZ(FishSizeClass.SMALL_BELOW_Z) == FishSizeClass.STANDARD
                        && FishSizeClass.forZ(Math.nextDown(FishSizeClass.SMALL_BELOW_Z)) == FishSizeClass.SMALL
                        && FishSizeClass.forZ(FishSizeClass.LARGE_FROM_Z) == FishSizeClass.LARGE
                        && FishSizeClass.forZ(Math.nextDown(FishSizeClass.LARGE_FROM_Z)) == FishSizeClass.STANDARD
                        && FishSizeClass.forZ(FishSizeClass.TROPHY_FROM_Z) == FishSizeClass.TROPHY
                        && FishSizeClass.forZ(Math.nextDown(FishSizeClass.TROPHY_FROM_Z)) == FishSizeClass.LARGE,
                "档位边界: 小于小档阈值为小, 达到大档/奖杯阈值即升档");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "fish_size")
    public static void classOddsMatchTheDocumentedSplit(GameTestHelper helper) {
        // 设计文档: 小 20% / 标准 65% / 大 14% / 奖杯 1%。固定种子, 结果确定; 容差按 20 万样本的抽样误差留足。
        RandomSource random = RandomSource.create(20260924L);
        int samples = 200_000;
        int[] counts = new int[FishSizeClass.values().length];
        for (int i = 0; i < samples; i++) {
            counts[FishSizeRoller.roll(DOCUMENTED_COD, random).sizeClass().ordinal()]++;
        }
        double[] documented = {0.20D, 0.65D, 0.14D, 0.01D};
        for (FishSizeClass sizeClass : FishSizeClass.values()) {
            double share = counts[sizeClass.ordinal()] / (double) samples;
            helper.assertTrue(Math.abs(share - documented[sizeClass.ordinal()]) < 0.005D,
                    sizeClass.id() + " 档占比 " + share + " 偏离文档 " + documented[sizeClass.ordinal()]);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "fish_size")
    public static void everyJournalFishHasASizeProfile(GameTestHelper helper) {
        for (FishingJournalEntry entry : FishingJournalCatalog.INSTANCE.entries()) {
            helper.assertTrue(FishSizeCatalog.INSTANCE.profile(entry.itemId()) != null,
                    "图鉴鱼种缺少体型档案: " + entry.itemId());
        }
        boolean supportedTide = ModList.get().getModContainerById("tide")
                .map(mod -> mod.getModInfo().getVersion().toString().equals("1.6.5")).orElse(false);
        int expected = supportedTide ? 75 : 9;
        helper.assertTrue(FishSizeCatalog.INSTANCE.profiles().size() == expected,
                "体型档案应为原版 4 + 矿石鱼 5" + (supportedTide ? " + Tide 66" : "") + " = " + expected
                        + " 条, 实得 " + FishSizeCatalog.INSTANCE.profiles().size());
        FishSizeProfile cod = FishSizeCatalog.INSTANCE.profile(COD);
        helper.assertTrue(DOCUMENTED_COD.equals(cod), "鳕鱼档案与设计文档不一致: " + cod);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "fish_size")
    public static void invalidReloadPreservesPreviousProfiles(GameTestHelper helper) {
        String valid = """
                {"entries":[{"item":"minecraft:cod","min_cm":30,"common_cm":60,"max_cm":130,"a":0.0077,"b":3.07}]}
                """;
        String inverted = """
                {"entries":[{"item":"minecraft:salmon","min_cm":90,"common_cm":60,"max_cm":130,"a":0.0107,"b":3.0}]}
                """;
        FishSizeCatalog catalog = new FishSizeCatalog();
        var manager = helper.getLevel().getServer().getResourceManager();
        catalog.apply(Map.of(new ResourceLocation("miningdim", "first"), JsonParser.parseString(valid)),
                manager, InactiveProfiler.INSTANCE);
        boolean rejected = false;
        try {
            catalog.apply(Map.of(new ResourceLocation("miningdim", "first"), JsonParser.parseString(valid),
                    new ResourceLocation("miningdim", "broken"), JsonParser.parseString(inverted)),
                    manager, InactiveProfiler.INSTANCE);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected && catalog.profiles().size() == 1 && catalog.profile(COD) != null,
                "非法体长顺序必须整次拒绝, 保留此前完整档案");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "fish_size")
    public static void onlyNonStandardClassesTagTheStack(GameTestHelper helper) {
        UUID catcher = UUID.randomUUID();
        ItemStack standard = new ItemStack(Items.COD);
        FishSizeNbt.stamp(standard, new FishMeasurement(600, 2_000_000L, FishSizeClass.STANDARD), catcher, "Angler", 1L);
        helper.assertTrue(!standard.hasTag() && ItemStack.isSameItemSameTags(standard, new ItemStack(Items.COD)),
                "标准档不写标签, 与未测量的鱼照常堆叠");

        ItemStack smallA = new ItemStack(Items.COD);
        ItemStack smallB = new ItemStack(Items.COD);
        FishSizeNbt.stamp(smallA, new FishMeasurement(350, 400_000L, FishSizeClass.SMALL), catcher, "Angler", 1L);
        FishSizeNbt.stamp(smallB, new FishMeasurement(410, 700_000L, FishSizeClass.SMALL), UUID.randomUUID(), "Other", 9L);
        helper.assertTrue(ItemStack.isSameItemSameTags(smallA, smallB),
                "小/大只存档位: 同档不同尺寸、不同钓获者的鱼必须能堆叠");
        helper.assertTrue(FishSizeNbt.read(smallA).map(FishSizeNbt.Info::sizeClass).orElse(null) == FishSizeClass.SMALL,
                "小档标签必须能读回");

        ItemStack trophyA = new ItemStack(Items.COD);
        ItemStack trophyB = new ItemStack(Items.COD);
        FishSizeNbt.stamp(trophyA, new FishMeasurement(1250, 21_000_000L, FishSizeClass.TROPHY), catcher, "Angler", 5L);
        FishSizeNbt.stamp(trophyB, new FishMeasurement(1260, 21_500_000L, FishSizeClass.TROPHY), catcher, "Angler", 6L);
        FishSizeNbt.Info info = FishSizeNbt.read(trophyA).orElseThrow();
        helper.assertTrue(info.lengthMm() == 1250 && info.weightMg() == 21_000_000L && info.catcherName().equals("Angler"),
                "奖杯必须完整保存体长、体重与钓获者");
        helper.assertTrue(!ItemStack.isSameItemSameTags(trophyA, trophyB), "奖杯个体刻意独一份, 不与其它奖杯堆叠");

        ItemStack corrupt = new ItemStack(Items.COD);
        corrupt.getOrCreateTag().put(FishSizeNbt.ROOT, new net.minecraft.nbt.CompoundTag());
        helper.assertTrue(FishSizeNbt.read(corrupt).isEmpty(), "读不出档位的标签按无标签处理, 不抛异常");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "fish_size")
    public static void catchServiceRecordsCountsAndPersonalBest(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        int[] lengths = {500, 700, 600};
        int[] next = {0};
        java.util.function.Function<FishSizeProfile, FishMeasurement> fixed = profile ->
                new FishMeasurement(lengths[next[0]++], 1_000_000L + next[0], FishSizeClass.STANDARD);
        for (int i = 0; i < lengths.length; i++) {
            NonNullList<ItemStack> catches = NonNullList.create();
            catches.add(new ItemStack(Items.COD));
            catches.add(new ItemStack(Items.STICK));
            helper.assertTrue(FishCatchService.onSuccessfulCatch(player, catches, fixed) == 1,
                    "只有带体型档案的渔获被测量, 木棍这类杂物跳过");
        }
        FishRecord record = FishingRecords.get(player, COD);
        helper.assertTrue(record != null && record.count() == 3 && record.bestLengthMm() == 700
                        && record.bestWeightMg() == 1_000_002L,
                "三次钓获计 3 次, 最大个体是 70 cm 那一条及其体重, 实得 " + record);
        helper.assertTrue(player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).contains(FishingRecords.ROOT),
                "记录必须挂在 PlayerPersisted 下, Forge 重建玩家实体时只复制这个子标签");

        ItemStack alreadyMeasured = new ItemStack(Items.COD);
        FishSizeNbt.stamp(alreadyMeasured, new FishMeasurement(400, 500_000L, FishSizeClass.SMALL), player.getUUID(), "x", 1L);
        helper.assertTrue(FishCatchService.onSuccessfulCatch(player, List.of(alreadyMeasured), fixed) == 0
                        && FishingRecords.get(player, COD).count() == 3,
                "已有体型标签的栈不得重复结算");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "fish_size")
    public static void vanillaRetrieveMeasuresOnlyLandedCatches(GameTestHelper helper) {
        var originalWeights = com.miningdim.job.fisher.ore.OreFishGameTests.forceIronOnlyCatchWeight();
        try {
            ServerLevel mining = helper.getLevel().getServer().getLevel(MiningConstants.MINING_LEVEL);
            if (mining == null) {
                throw new IllegalStateException("Mining dimension is unavailable to fish size GameTest");
            }
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            player.teleportTo(mining, 64.5D, 80.0D, 0.5D, 0.0F, 0.0F);
            ResourceLocation iron = OreFishingItems.FISH.get(OreFishType.IRON).getId();

            BlockPos water = new BlockPos(64, 80, 0);
            mining.setBlock(water, Blocks.WATER.defaultBlockState(), 3);
            // 体型走玩家自己的随机源, 单竿有 65% 是不写标签的标准档, 无法据此判断"标签打在了真正落地的那一栈上"。
            // 于是连续出竿直到落地的鱼读出非标准档: 若标签打在副本或别的列表上, 落地的鱼永远没有标签, 这里必红。
            // 60 竿全是标准档的概率是 0.65^60, 约 6e-12。
            int casts = 0;
            FishSizeClass landedClass = FishSizeClass.STANDARD;
            while (landedClass == FishSizeClass.STANDARD && casts < 60) {
                retrieve(player, mining, water);
                casts++;
                List<ItemStack> spawned = mining.getEntitiesOfClass(ItemEntity.class,
                                new net.minecraft.world.phys.AABB(water).inflate(2.0D),
                                entity -> entity.getItem().is(OreFishingItems.FISH.get(OreFishType.IRON).get()))
                        .stream().map(ItemEntity::getItem).toList();
                helper.assertTrue(spawned.size() == 1, "每一竿原版 retrieve 都必须落地一条铁矿鱼, 第 " + casts + " 竿实得 " + spawned.size());
                landedClass = FishSizeNbt.read(spawned.get(0)).map(FishSizeNbt.Info::sizeClass).orElse(FishSizeClass.STANDARD);
            }
            FishRecord landed = FishingRecords.get(player, iron);
            helper.assertTrue(landedClass != FishSizeClass.STANDARD,
                    "连续 60 竿落地的鱼都没有体型标签: 标签没有打在实际生成的掉落物上");
            helper.assertTrue(landed != null && landed.count() == casts,
                    "每一竿落地的矿石鱼都必须被测量并记入钓获次数, 期望 " + casts + ", 实得 " + landed);

            Object canceller = new Object() {
                @SubscribeEvent
                public void onItemFished(ItemFishedEvent event) {
                    event.setCanceled(true);
                }
            };
            MinecraftForge.EVENT_BUS.register(canceller);
            BlockPos cancelled = new BlockPos(68, 80, 0);
            mining.setBlock(cancelled, Blocks.WATER.defaultBlockState(), 3);
            try {
                retrieve(player, mining, cancelled);
            } finally {
                MinecraftForge.EVENT_BUS.unregister(canceller);
            }
            helper.assertTrue(FishingRecords.get(player, iron).count() == casts,
                    "被取消的钓获不得留下个人记录");
            helper.succeed();
        } finally {
            com.miningdim.job.fisher.ore.OreFishGameTests.restoreCatchWeights(originalWeights);
        }
    }

    private static void retrieve(ServerPlayer player, ServerLevel level, BlockPos pos) {
        level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(2.0D))
                .forEach(ItemEntity::discard);
        player.setPos(Vec3.atCenterOf(pos));
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.FISHING_ROD));
        FishingHook hook = new FishingHook(player, level, 0, 0);
        hook.setPos(Vec3.atCenterOf(pos));
        player.fishing = hook;
        try {
            java.lang.reflect.Field nibble = FishingHook.class.getDeclaredField("nibble");
            nibble.setAccessible(true);
            nibble.setInt(hook, 1);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot prepare a successful vanilla fishing catch", exception);
        }
        hook.retrieve(player.getMainHandItem());
    }
}
