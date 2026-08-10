package com.miningdim.job.farmer;

import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.EconomyWalletData;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.job.JobXpCurve;
import com.miningdim.job.farmer.block.FarmerBlocks;
import com.miningdim.job.farmer.block.FarmerCropBlock;
import com.miningdim.job.farmer.item.FarmerItems;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.common.PlantType;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 农夫职业核心逻辑 GameTest (FarmingXP_Mod_DesignSpec 第六节测试断言 + 实现手册 GameTest 范式)。
 *
 * 断言具体业务结果 (删被测核心逻辑测试必挂, 禁 is-not-null 弱校验):
 *  - 放置上限/档位门控 (表A/表B): L1 第10块拒、L2 第13块拒、L4 拒高级地/L5 通;
 *  - 收获经验入账经框架 2000 系衰减 (表B 吞吐 + JobXpCurve): L5 满 25 块高级地 6h 入账精确值;
 *  - 经验硬顶边界 (9500 原始那一档): 跨 3800 有效经验前后单株有效经验差异;
 *  - 收购价递减 (第八节方案4): softCap 前后单株单价、卖菜跨边界总价连续;
 *  - 五档耕地参数表 (表B): 解锁等级/产量/成长间隔自洽。
 *
 * 纯逻辑断言不依赖结构, 用 template = "empty"。涉及 capability 挂载/世界写的端到端 (作物成长/破坏掉落)
 * 在 FarmerSystem 接入 MiningDim 后才生效 (本任务不接线), 故此处验证驱动这些事件的纯裁决/纯函数逻辑
 * (与挂载后玩家身上运行的同一份逻辑)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FarmerGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "farmer";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void farmersDelightTomatoRightClickUsesSupremeYield(GameTestHelper helper) {
        if (!ModList.get().isLoaded("farmersdelight")) {
            helper.succeed();
            return;
        }

        Block tomatoBlock = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("farmersdelight", "tomatoes"));
        helper.assertTrue(tomatoBlock instanceof net.minecraft.world.level.block.CropBlock,
                "Farmer's Delight tomato crop must be registered");
        net.minecraft.world.level.block.CropBlock crop =
                (net.minecraft.world.level.block.CropBlock) tomatoBlock;
        BlockPos relativeSoil = new BlockPos(1, 1, 1);
        BlockPos relativeCrop = relativeSoil.above();
        helper.setBlock(relativeSoil,
                FarmerBlocks.farmland(FarmerTier.SUPREME).get().defaultBlockState());
        helper.setBlock(relativeCrop, crop.getStateForAge(crop.getMaxAge()));

        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        BlockPos cropPos = helper.absolutePos(relativeCrop);
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(cropPos), Direction.UP, cropPos, false);
        player.gameMode.useItemOn(player, helper.getLevel(), ItemStack.EMPTY,
                InteractionHand.MAIN_HAND, hit);

        net.minecraft.world.item.Item tomato = ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("farmersdelight", "tomato"));
        int count = helper.getLevel().getEntitiesOfClass(
                        ItemEntity.class, new AABB(cropPos).inflate(3.0D)).stream()
                .map(ItemEntity::getItem)
                .filter(stack -> stack.is(tomato))
                .mapToInt(ItemStack::getCount)
                .sum();
        helper.assertTrue(count >= 6 && count <= 12,
                "SUPREME right-click tomato harvest must drop 6 or 12, got " + count);
        helper.assertTrue(crop.getAge(helper.getLevel().getBlockState(cropPos)) == 0,
                "right-click tomato harvest must reset the crop to age 0");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void farmersDelightHangingTomatoRightClickUsesSupremeYield(GameTestHelper helper) {
        if (!ModList.get().isLoaded("farmersdelight")) {
            helper.succeed();
            return;
        }

        BlockState cropState = matureFarmersDelightCrop("tomatoes_on_rope");
        net.minecraft.world.level.block.CropBlock crop =
                (net.minecraft.world.level.block.CropBlock) cropState.getBlock();
        BlockPos relativeSoil = new BlockPos(1, 1, 1);
        BlockPos relativeCrop = relativeSoil.above();
        helper.setBlock(relativeSoil,
                FarmerBlocks.farmland(FarmerTier.SUPREME).get().defaultBlockState());
        helper.setBlock(relativeCrop, cropState);

        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        BlockPos cropPos = helper.absolutePos(relativeCrop);
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(cropPos), Direction.UP, cropPos, false);
        player.gameMode.useItemOn(player, helper.getLevel(), ItemStack.EMPTY,
                InteractionHand.MAIN_HAND, hit);

        net.minecraft.world.item.Item tomato = ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("farmersdelight", "tomato"));
        int count = helper.getLevel().getEntitiesOfClass(
                        ItemEntity.class, new AABB(cropPos).inflate(3.0D)).stream()
                .map(ItemEntity::getItem)
                .filter(stack -> stack.is(tomato))
                .mapToInt(ItemStack::getCount)
                .sum();
        helper.assertTrue(count == 6 || count == 12,
                "SUPREME right-click hanging tomato harvest must drop 6 or 12, got " + count);
        helper.assertTrue(crop.getAge(helper.getLevel().getBlockState(cropPos)) == 0,
                "right-click hanging tomato harvest must reset the crop to age 0");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void farmersDelightAllCropsCanUseFarmerFarmland(GameTestHelper helper) {
        if (!ModList.get().isLoaded("farmersdelight")) {
            helper.succeed();
            return;
        }

        BlockState farmland = FarmerBlocks.farmland(FarmerTier.SUPREME).get().defaultBlockState();
        BlockPos soilPos = helper.absolutePos(new BlockPos(1, 1, 1));
        for (String cropId : List.of("cabbages", "onions", "tomatoes", "rice")) {
            Block crop = requireFarmersDelightBlock(cropId);
            helper.assertTrue(crop instanceof IPlantable,
                    "Farmer's Delight crop must be plantable: " + cropId);
            helper.assertTrue(farmland.canSustainPlant(helper.getLevel(), soilPos, Direction.UP,
                            (IPlantable) crop),
                    "farmer farmland must sustain Farmer's Delight crop: " + cropId);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void farmersDelightAllMatureCropsUseSupremeYield(GameTestHelper helper) {
        if (!ModList.get().isLoaded("farmersdelight")) {
            helper.succeed();
            return;
        }

        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        BlockPos relativeSoil = new BlockPos(1, 1, 1);
        BlockPos relativeCrop = relativeSoil.above();
        helper.setBlock(relativeSoil,
                FarmerBlocks.farmland(FarmerTier.SUPREME).get().defaultBlockState());

        BlockState cabbage = matureFarmersDelightCrop("cabbages");
        helper.setBlock(relativeCrop, cabbage);
        List<ItemStack> cabbageLoot = cropDrops(helper, player, relativeCrop, cabbage);
        helper.assertTrue(itemCount(cabbageLoot, "cabbage") == 6,
                "SUPREME cabbage produce must be 1 x 6");
        int cabbageSeedCount = itemCount(cabbageLoot, "cabbage_seeds");
        helper.assertTrue(cabbageSeedCount >= 1 && cabbageSeedCount <= 4,
                "cabbage seeds must remain in Farmer's Delight's native 1-4 range, got "
                        + cabbageSeedCount);

        BlockState onion = matureFarmersDelightCrop("onions");
        helper.setBlock(relativeCrop, onion);
        int onionCount = itemCount(cropDrops(helper, player, relativeCrop, onion), "onion");
        helper.assertTrue(onionCount >= 6 && onionCount % 6 == 0,
                "SUPREME onion produce must be a whole native yield times 6, got " + onionCount);

        BlockState tomato = matureFarmersDelightCrop("tomatoes");
        helper.setBlock(relativeCrop, tomato);
        List<ItemStack> tomatoLoot = cropDrops(helper, player, relativeCrop, tomato);
        int tomatoCount = itemCount(tomatoLoot, "tomato");
        helper.assertTrue(tomatoCount == 6 || tomatoCount == 12,
                "SUPREME tomato produce must be 6 or 12, got " + tomatoCount);
        helper.assertTrue(itemCount(tomatoLoot, "tomato_seeds") <= 1,
                "tomato seeds must not be multiplied");
        helper.assertTrue(itemCount(tomatoLoot, "rotten_tomato") <= 1,
                "rare rotten tomato byproduct must not be multiplied");

        BlockState hangingTomato = matureFarmersDelightCrop("tomatoes_on_rope");
        helper.setBlock(relativeCrop, hangingTomato);
        int hangingTomatoCount = itemCount(
                cropDrops(helper, player, relativeCrop, hangingTomato), "tomato");
        helper.assertTrue(hangingTomatoCount == 6 || hangingTomatoCount == 12,
                "SUPREME hanging tomato produce must be 6 or 12, got " + hangingTomatoCount);

        BlockState rice = requireFarmersDelightBlock("rice").defaultBlockState();
        BooleanProperty supporting = rice.getProperties().stream()
                .filter(BooleanProperty.class::isInstance)
                .map(BooleanProperty.class::cast)
                .filter(property -> property.getName().equals("supporting"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Farmer's Delight rice is missing supporting property"));
        rice = rice.setValue(supporting, true);
        // Replacing tomatoes_on_rope makes Farmer's Delight restore its rope in onRemove.
        // Clear that restored rope before building the independent mature-rice fixture.
        helper.setBlock(relativeCrop, Blocks.AIR);
        BlockState ricePanicles = matureFarmersDelightCrop("rice_panicles");
        helper.setBlock(relativeCrop, rice);
        BlockPos relativePanicles = relativeCrop.above();
        helper.setBlock(relativePanicles, ricePanicles);
        helper.assertTrue(FarmerHarvests.isSupportedMatureCrop(ricePanicles),
                "mature rice panicles must be recognized as supported produce");
        BlockPos absolutePanicles = helper.absolutePos(relativePanicles);
        FarmerTier riceTier = FarmerHarvests.tierFor(
                helper.getLevel(), absolutePanicles, ricePanicles);
        ResourceLocation belowRiceId = ForgeRegistries.BLOCKS.getKey(
                helper.getLevel().getBlockState(absolutePanicles.below()).getBlock());
        helper.assertTrue(riceTier == FarmerTier.SUPREME,
                "rice panicles must trace through the lower rice block to SUPREME farmland; below="
                        + belowRiceId + ", tier=" + riceTier);
        List<ItemStack> riceLoot = cropDrops(helper, player, relativePanicles, ricePanicles);
        int ricePanicleCount = itemCount(riceLoot, "rice_panicle");
        helper.assertTrue(ricePanicleCount == 6,
                "SUPREME rice panicle produce must be 1 x 6, got " + ricePanicleCount);
        helper.assertTrue(itemCount(riceLoot, "rice") == 0,
                "rice seed/grain is not added when harvesting without a knife");

        net.minecraft.world.item.Item ironKnife = ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("farmersdelight", "iron_knife"));
        helper.assertTrue(ironKnife != null,
                "Farmer's Delight iron knife must be registered for knife-harvest coverage");
        List<ItemStack> knifeRiceLoot = Block.getDrops(
                ricePanicles, helper.getLevel(), absolutePanicles,
                null, player, new ItemStack(ironKnife));
        helper.assertTrue(itemCount(knifeRiceLoot, "rice") == 6,
                "SUPREME knife-harvested rice must be 1 x 6");
        helper.assertTrue(itemCount(knifeRiceLoot, "rice_panicle") == 0,
                "knife-harvested rice must replace the panicle output, not duplicate it");
        helper.succeed();
    }

    // ============================================================
    // 放置上限 + 档位门控 (表A 方块上限 / 表B 解锁等级)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void placementCapPerLevel(GameTestHelper helper) {
        // 表A: L1 上限 9, L2 上限 12。capForLevel 精确查表。
        helper.assertTrue(FarmlandPlacementGuard.capForLevel(1) == 9, "L1 farmland cap must be 9");
        helper.assertTrue(FarmlandPlacementGuard.capForLevel(2) == 12, "L2 farmland cap must be 12");
        helper.assertTrue(FarmlandPlacementGuard.capForLevel(5) == 25, "L5 farmland cap must be 25");
        helper.assertTrue(FarmlandPlacementGuard.capForLevel(10) == 64, "L10 farmland cap must be 64");

        // L1 放第 10 块低级地被拒 (已放 9 = 上限, 第 10 块越界)。spec 第244行 "第13块在L1被拒" 是笔误,
        // 以表A L1=9 为准: 第 10 块拒。
        helper.assertTrue(
                FarmlandPlacementGuard.checkPlacement(FarmerTier.LOW, 1, 9)
                        == FarmlandPlacementGuard.PlaceResult.REJECT_CAP_REACHED,
                "L1: placing 10th low farmland (already 9) is rejected by cap");
        helper.assertTrue(
                FarmlandPlacementGuard.checkPlacement(FarmerTier.LOW, 1, 8)
                        == FarmlandPlacementGuard.PlaceResult.ALLOW,
                "L1: placing 9th low farmland (already 8) is allowed (cap 9)");

        // L2 放第 13 块低级地被拒 (上限 12, 已放 12, 第 13 越界)。
        helper.assertTrue(
                FarmlandPlacementGuard.checkPlacement(FarmerTier.LOW, 2, 12)
                        == FarmlandPlacementGuard.PlaceResult.REJECT_CAP_REACHED,
                "L2: placing 13th low farmland (already 12) is rejected by cap");
        helper.assertTrue(
                FarmlandPlacementGuard.checkPlacement(FarmerTier.LOW, 2, 11)
                        == FarmlandPlacementGuard.PlaceResult.ALLOW,
                "L2: placing 12th low farmland (already 11) is allowed (cap 12)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tierUnlockGate(GameTestHelper helper) {
        // 高级地解锁等级 L5: L4 玩家放高级地被拒, L5 玩家通 (表B 解锁等级边界)。
        helper.assertTrue(
                FarmlandPlacementGuard.checkPlacement(FarmerTier.HIGH, 4, 0)
                        == FarmlandPlacementGuard.PlaceResult.REJECT_TIER_LOCKED,
                "L4 player cannot place HIGH farmland (unlocks at L5)");
        helper.assertTrue(
                FarmlandPlacementGuard.checkPlacement(FarmerTier.HIGH, 5, 0)
                        == FarmlandPlacementGuard.PlaceResult.ALLOW,
                "L5 player can place HIGH farmland (just unlocked)");
        // 超凡地解锁 L9: L8 拒 / L9 通。
        helper.assertTrue(
                FarmlandPlacementGuard.checkPlacement(FarmerTier.SUPREME, 8, 0)
                        == FarmlandPlacementGuard.PlaceResult.REJECT_TIER_LOCKED,
                "L8 player cannot place SUPREME farmland (unlocks at L9)");
        helper.assertTrue(
                FarmlandPlacementGuard.checkPlacement(FarmerTier.SUPREME, 9, 0)
                        == FarmlandPlacementGuard.PlaceResult.ALLOW,
                "L9 player can place SUPREME farmland");
        // 门控优先于上限: 即便已放 0 块, 档位未解锁仍先拒 (顺序: 档位 -> 上限)。
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void farmerFarmlandSustainsForgeCropPlants(GameTestHelper helper) {
        BlockState state = FarmerBlocks.farmland(FarmerTier.LOW).get().defaultBlockState();
        BlockPos pos = BlockPos.ZERO;

        helper.assertTrue(state.canSustainPlant(helper.getLevel(), pos, Direction.UP,
                        new TestPlantable(PlantType.CROP)),
                "farmer farmland supports Forge CROP plants such as most Farmer's Delight seeds");
        helper.assertFalse(state.canSustainPlant(helper.getLevel(), pos, Direction.UP,
                        new TestPlantable(PlantType.WATER)),
                "farmer farmland must not pretend to be water soil for special aquatic crops");
        helper.assertFalse(state.canSustainPlant(helper.getLevel(), pos, Direction.UP,
                        new TestPlantable(PlantType.DESERT)),
                "farmer farmland must not pretend to be sand for cactus/desert plants");
        helper.assertFalse(state.canSustainPlant(helper.getLevel(), pos, Direction.NORTH,
                        new TestPlantable(PlantType.CROP)),
                "farmer farmland only supports crops planted above it");
        helper.succeed();
    }

    // ============================================================
    // 收获经验入账经框架 2000 系衰减 (表B 吞吐 + JobXpCurve.applyDailyDecay)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void singleHarvestRawXp(GameTestHelper helper) {
        // 表B: 单作物经验固定 2; 一次成熟破坏的原始经验 = 2 × 该档产量。
        helper.assertTrue(rawXpForTier(FarmerTier.LOW) == 4L, "LOW harvest raw xp = 2 * yield 2 = 4");
        helper.assertTrue(rawXpForTier(FarmerTier.MEDIUM) == 6L, "MEDIUM harvest raw xp = 2 * yield 3 = 6");
        helper.assertTrue(rawXpForTier(FarmerTier.HIGH) == 8L, "HIGH harvest raw xp = 2 * yield 4 = 8");
        helper.assertTrue(rawXpForTier(FarmerTier.PREMIUM) == 10L, "PREMIUM harvest raw xp = 2 * yield 5 = 10");
        helper.assertTrue(rawXpForTier(FarmerTier.SUPREME) == 12L, "SUPREME harvest raw xp = 2 * yield 6 = 12");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void l5HighFarmSixHourEffectiveXp(GameTestHelper helper) {
        // 表B: 高级地原始吞吐 = 收获/时(10) × 产量(4) × 单作物经验(2) = 80 经验/块/时。
        // L5 满 25 块: 25 × 80 = 2000 原始/时; 6h = 12,000 原始。
        long rawPerBlockHour = (long) harvestsPerHourHigh() * FarmerTier.HIGH.yieldPerHarvest() * FarmerConstants.SINGLE_CROP_XP;
        helper.assertTrue(rawPerBlockHour == 80L, "HIGH throughput = 10*4*2 = 80 raw xp/block/hour (table B)");
        long sixHourRaw = 25L * rawPerBlockHour * 6L;
        helper.assertTrue(sixHourRaw == 12_000L, "L5 25 HIGH blocks 6h = 12000 raw xp");

        // 经框架 2000 系衰减 (单源真值 JobXpCurve, 取代 spec 表C T=1500)。权威模型为 "有效经验容量模型" (B 解释):
        // 分段边界划分有效经验轴, 每段按系数折算原始去填满该段有效容量。当日 0 起入 12000 原始 ->
        // [0,2000) 2000 原始填满 2000 有效 + [2000,2800) 2000 原始填满 800 有效 + [2800,3400) 3000 原始填满 600 有效
        // + [3400,3800) 5000 原始填满 400 有效 (累计原始 12000 恰好填到 3800) = 2000+800+600+400 = 3800 有效经验。
        // 说明: 旧实现误把分段边界当原始经验轴 (A 解释) 得 2636, 与工程师 spec 第八章 "12000 原始 -> 3800" 定值
        // 及农夫 spec FarmingXP_Mod_DesignSpec.md:79-85 "该段累计需要的原始经验" 同源模型背离, 已修正为 3800。
        long effective = JobXpCurve.applyDailyDecay(0L, sixHourRaw);
        helper.assertTrue(effective == 3_800L,
                "L5 25 HIGH blocks 6h decays to 3800 effective xp under the effective-capacity model "
                        + "(matches engineer spec ch.8 12000 raw -> 3800; old 2636 used the wrong axis)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dailyHardCapBoundary(GameTestHelper helper) {
        // 经验硬顶档 (框架末档 3800 有效经验之后 x0.02): 跨 3800 边界单株有效经验差异。
        // 一株超凡作物原始 12: 当日 3799 有效起入 12 -> [3799,3800)1*0.08 + [3800,3811)11*0.02 = floor(0.08+0.22)=0;
        // 当日 3800 有效起入 12 -> 全在末档 12*0.02 = floor(0.24)=0。两者都 floor 到 0 (末档近乎归零)。
        // 用更大的批量凸显边界: 当日 3000 起入 12 (全在 [2800,3400) x0.2) = floor(2.4)=2; 当日 3800 起入 12 = 0。
        long midSegment = JobXpCurve.applyDailyDecay(3_000L, 12L);
        long lastSegment = JobXpCurve.applyDailyDecay(3_800L, 12L);
        helper.assertTrue(midSegment == 2L, "12 raw at dailyXp 3000 (x0.2) -> 2 effective");
        helper.assertTrue(lastSegment == 0L, "12 raw at dailyXp 3800 (x0.02) -> floor(0.24) = 0 effective (hard cap)");
        helper.assertTrue(midSegment > lastSegment,
                "crossing into the last decay segment strictly reduces effective xp per harvest");
        helper.succeed();
    }

    // ============================================================
    // NPC 小麦动态收购价 (第八节方案4 — 与经验衰减独立)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wheatBuybackDecay(GameTestHelper helper) {
        long base = FarmerConstants.WHEAT_BASE_PRICE; // 1
        int cap = FarmerConstants.WHEAT_DAILY_SOFTCAP; // 2160
        // softCap 内全价: 第 1 株与第 cap 株均 = basePrice。
        helper.assertTrue(FarmerWheatBuyback.wheatBuyPrice(1, base) == base, "first wheat at full base price");
        helper.assertTrue(FarmerWheatBuyback.wheatBuyPrice(cap, base) == base, "wheat at softCap still full price");
        // 超 softCap 衰减: 第 cap+1 株价 = floor(base * 0.97^1)。base=1 时 floor(0.97)=0 -> 触地板比例。
        // 用更大 basePrice 校验衰减形状 (与曲线无关于 base 的比例一致): base=1000。
        long b = 1000L;
        helper.assertTrue(FarmerWheatBuyback.wheatBuyPrice(cap, b) == 1000L, "at softCap full price (base 1000)");
        helper.assertTrue(FarmerWheatBuyback.wheatBuyPrice(cap + 1, b) == (long) Math.floor(1000 * 0.97D),
                "at softCap+1 price = floor(base * 0.97^1) = 970");
        helper.assertTrue(FarmerWheatBuyback.wheatBuyPrice(cap + 2, b) == (long) Math.floor(1000 * Math.pow(0.97D, 2)),
                "at softCap+2 price = floor(base * 0.97^2)");
        // 地板: 大量超出后价不低于 base * floorRatio (0.25)。
        long deepPrice = FarmerWheatBuyback.wheatBuyPrice(cap + 100000, b);
        helper.assertTrue(deepPrice == (long) Math.floor(1000 * FarmerConstants.WHEAT_PRICE_FLOOR_RATIO),
                "deep over-cap price floors at base * 0.25 = 250");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wheatBuybackTotalIsContinuous(GameTestHelper helper) {
        long b = 1000L;
        int cap = FarmerConstants.WHEAT_DAILY_SOFTCAP;
        // 跨 softCap 边界逐株求和 = 各株单价之和 (连续, 无跳变)。卖 3 株, 起点 cap-1:
        // 第 cap 株 full(1000) + 第 cap+1 株 floor(970) + 第 cap+2 株 floor(940.9)=940 = 1000+970+940 = 2910。
        long total = FarmerWheatBuyback.totalBuyPrice(cap - 1, 3, b);
        long expected = FarmerWheatBuyback.wheatBuyPrice(cap, b)
                + FarmerWheatBuyback.wheatBuyPrice(cap + 1, b)
                + FarmerWheatBuyback.wheatBuyPrice(cap + 2, b);
        helper.assertTrue(total == expected, "batch total equals per-wheat sum across softCap boundary (continuous)");
        helper.assertTrue(total == 2910L, "3 wheat from cap-1: 1000 + 970 + 940 = 2910");
        helper.succeed();
    }

    // ============================================================
    // 五档耕地参数表自洽 (表B)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tierParamsTableB(GameTestHelper helper) {
        // 解锁等级: 每两级一档 (表B / 表A)。
        helper.assertTrue(FarmerTier.LOW.unlockLevel() == 1, "LOW unlocks L1");
        helper.assertTrue(FarmerTier.MEDIUM.unlockLevel() == 3, "MEDIUM unlocks L3");
        helper.assertTrue(FarmerTier.HIGH.unlockLevel() == 5, "HIGH unlocks L5");
        helper.assertTrue(FarmerTier.PREMIUM.unlockLevel() == 7, "PREMIUM unlocks L7");
        helper.assertTrue(FarmerTier.SUPREME.unlockLevel() == 9, "SUPREME unlocks L9");
        // 产量: 2/3/4/5/6 (表B 每次产量)。
        helper.assertTrue(FarmerTier.LOW.yieldPerHarvest() == 2, "LOW yield 2");
        helper.assertTrue(FarmerTier.SUPREME.yieldPerHarvest() == 6, "SUPREME yield 6");
        // 成长间隔 tick: 低 10min = 12000 tick; 超凡 4min = 4800 tick。
        helper.assertTrue(FarmerTier.LOW.growthIntervalTicks() == 10L * 60L * 20L, "LOW grow interval = 12000 ticks");
        helper.assertTrue(FarmerTier.SUPREME.growthIntervalTicks() == 4L * 60L * 20L, "SUPREME grow interval = 4800 ticks");
        // 单作物经验固定 2 (表B 主方案)。
        helper.assertTrue(FarmerConstants.SINGLE_CROP_XP == 2, "single crop xp fixed at 2 (table B main plan)");
        helper.succeed();
    }

    // ============================================================
    // 档位化成长速率: 期望成熟 tick 命中表B 间隔 (farmer-01 多阶推进回归锚)
    // ============================================================

    /** 作物最大成长阶 (= 原版 CropBlock.getMaxAge() = 7; 表B 折算每阶段期望 tick 的分母)。 */
    private static final int CROP_MAX_AGE = 7;

    /** 默认 randomTickSpeed (原版 gamerule 默认 3; 表B 成长间隔即按此默认折算)。 */
    private static final int DEFAULT_RANDOM_TICK_SPEED = 3;

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void growthRateHitsTableBInterval(GameTestHelper helper) {
        // 守恒律: 期望成熟 tick = maxAge / E × (4096 / randomTickSpeed) 必须恰等于该档 growthIntervalTicks (表B)。
        // 五档逐一核对 (低 12000 / 中 9600 / 高 7200 / 极品 6000 / 超凡 4800 tick)。
        for (FarmerTier tier : FarmerTier.values()) {
            double e = FarmerCropBlock.expectedStagesPerRandomTick(tier, DEFAULT_RANDOM_TICK_SPEED, CROP_MAX_AGE);
            double maturityTicks = maturityTicksFromExpectedStages(e);
            long target = tier.growthIntervalTicks();
            // 偏差容差 1 tick (守恒律为精确恒等, 仅吸收 double 末位误差; 删多阶推进逻辑则塌缩到 ~9557 必越界)。
            helper.assertTrue(Math.abs(maturityTicks - target) <= 1.0D,
                    tier.id() + " expected maturity " + maturityTicks + " ticks must hit table B "
                            + target + " ticks (within 1 tick)");
        }

        // 三高档 (6/5/4min) 的每刻期望推进阶数必须 > 1 (多阶推进区), 且彼此严格不同 ——
        // 这正是旧"钳到 1.0 单阶推进"会塌缩掉的差异 (三档同退化为每刻恰一阶 -> 同 ~9557 tick)。
        double eHigh = FarmerCropBlock.expectedStagesPerRandomTick(FarmerTier.HIGH, DEFAULT_RANDOM_TICK_SPEED, CROP_MAX_AGE);
        double ePremium = FarmerCropBlock.expectedStagesPerRandomTick(FarmerTier.PREMIUM, DEFAULT_RANDOM_TICK_SPEED, CROP_MAX_AGE);
        double eSupreme = FarmerCropBlock.expectedStagesPerRandomTick(FarmerTier.SUPREME, DEFAULT_RANDOM_TICK_SPEED, CROP_MAX_AGE);
        helper.assertTrue(eHigh > 1.0D, "HIGH advances >1 stage per random tick (got " + eHigh + ")");
        helper.assertTrue(ePremium > 1.0D, "PREMIUM advances >1 stage per random tick (got " + ePremium + ")");
        helper.assertTrue(eSupreme > 1.0D, "SUPREME advances >1 stage per random tick (got " + eSupreme + ")");
        // 严格单调递增且间距显著 (>0.2 阶/刻), 证明三档未塌缩成同值。
        helper.assertTrue(ePremium - eHigh > 0.2D,
                "PREMIUM stages/tick (" + ePremium + ") strictly exceeds HIGH (" + eHigh + ") by >0.2");
        helper.assertTrue(eSupreme - ePremium > 0.2D,
                "SUPREME stages/tick (" + eSupreme + ") strictly exceeds PREMIUM (" + ePremium + ") by >0.2");

        // 对应成熟 tick 必须显著不同 (高 7200 / 极品 6000 / 超凡 4800, 两两差 >= 1000 tick), 不再塌缩同 ~7.96min。
        double matHigh = maturityTicksFromExpectedStages(eHigh);
        double matPremium = maturityTicksFromExpectedStages(ePremium);
        double matSupreme = maturityTicksFromExpectedStages(eSupreme);
        helper.assertTrue(matHigh - matPremium > 1000.0D,
                "HIGH maturity " + matHigh + " strictly slower than PREMIUM " + matPremium + " by >1000 ticks");
        helper.assertTrue(matPremium - matSupreme > 1000.0D,
                "PREMIUM maturity " + matPremium + " strictly slower than SUPREME " + matSupreme + " by >1000 ticks");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void multiStageSamplerMeanEqualsExpectedStages(GameTestHelper helper) {
        // 采样器实现 E 的证明 (drive randomTick 的真函数): 超凡 E ~ 1.99, 对 sampleStageAdvance 重复采样,
        // 经验均值必须收敛到 E (容差 0.05)。旧"单刻最多推一阶"实现均值 <= 1.0, 距 1.99 远超容差 -> 此断言挂。
        double eSupreme = FarmerCropBlock.expectedStagesPerRandomTick(FarmerTier.SUPREME, DEFAULT_RANDOM_TICK_SPEED, CROP_MAX_AGE);
        RandomSource rng = RandomSource.create(0xFA12E501L);
        int samples = 200_000;
        long totalStages = 0L;
        int sawTwoStages = 0; // 必须出现"单刻推进 2 阶"的事件 (整数部分 1 + 概率多推一阶), 证明确实多阶。
        for (int i = 0; i < samples; i++) {
            int adv = FarmerCropBlock.sampleStageAdvance(eSupreme, rng);
            helper.assertTrue(adv >= 1 && adv <= 2,
                    "SUPREME per-tick advance in {1,2} (E~1.99), got " + adv);
            totalStages += adv;
            if (adv == 2) {
                sawTwoStages++;
            }
        }
        double mean = (double) totalStages / samples;
        helper.assertTrue(Math.abs(mean - eSupreme) < 0.05D,
                "sampler empirical mean " + mean + " converges to E=" + eSupreme + " (within 0.05)");
        helper.assertTrue(sawTwoStages > 0,
                "SUPREME multi-stage path actually advances 2 stages in a single tick at least once");

        // 低档 (E < 1) 退化为零或一阶, 均值仍收敛到该档 E (< 1)。
        double eLow = FarmerCropBlock.expectedStagesPerRandomTick(FarmerTier.LOW, DEFAULT_RANDOM_TICK_SPEED, CROP_MAX_AGE);
        helper.assertTrue(eLow < 1.0D, "LOW advances <1 stage per random tick (got " + eLow + ")");
        RandomSource rngLow = RandomSource.create(0x10E12L);
        long totalLow = 0L;
        for (int i = 0; i < samples; i++) {
            int adv = FarmerCropBlock.sampleStageAdvance(eLow, rngLow);
            helper.assertTrue(adv == 0 || adv == 1, "LOW per-tick advance in {0,1} (E<1), got " + adv);
            totalLow += adv;
        }
        double meanLow = (double) totalLow / samples;
        helper.assertTrue(Math.abs(meanLow - eLow) < 0.05D,
                "LOW sampler mean " + meanLow + " converges to E=" + eLow + " (within 0.05)");
        helper.succeed();
    }

    /**
     * 由每刻期望推进阶数 E 反推期望成熟 tick: maxAge 阶 / (E 阶/刻) × (4096/randomTickSpeed) tick/刻。
     * 与 {@link FarmerCropBlock#expectedStagesPerRandomTick} 同源参数, 仅用于测试核对表B 间隔。
     */
    private static double maturityTicksFromExpectedStages(double expectedStagesPerRandomTick) {
        double avgTicksBetweenRandomTicks = 4096.0D / DEFAULT_RANDOM_TICK_SPEED;
        return CROP_MAX_AGE / expectedStagesPerRandomTick * avgTicksBetweenRandomTicks;
    }

    // ============================================================
    // 当日卖菜株数持久层 (FarmerSavedData: 仅株数 + 日戳, UTC 翻日整条清; 每日信用点 cap 已收敛进货币层)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wheatSaleRecordAccumulatesWithinDay(GameTestHelper helper) {
        FarmerSavedData data = new FarmerSavedData();
        java.util.UUID p = new java.util.UUID(0xF1L, 0xA1L);
        long day = 100L;
        // 同一日内多次记账累加株数 (供收购曲线定位边际单价档)。
        data.recordWheatSale(p, 50, day);
        data.recordWheatSale(p, 30, day);
        helper.assertTrue(data.wheatSoldToday(p, day) == 80, "same-day sold count accumulates to 50+30=80");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wheatSaleRolloverClearsCount(GameTestHelper helper) {
        FarmerSavedData data = new FarmerSavedData();
        java.util.UUID p = new java.util.UUID(0xF2L, 0xB2L);
        data.recordWheatSale(p, 100, 200L);
        helper.assertTrue(data.wheatSoldToday(p, 200L) == 100, "day 200 sold = 100");
        // 翻到下一日: 读取即整条清零 (株数归 0, 不留孤儿残值)。
        helper.assertTrue(data.wheatSoldToday(p, 201L) == 0, "day 201 sold rolls over to 0");
        // 翻日后再记账从新一日 0 起累加 (不继承旧日)。
        data.recordWheatSale(p, 5, 201L);
        helper.assertTrue(data.wheatSoldToday(p, 201L) == 5, "day 201 fresh accumulation = 5");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wheatSaleRolloverRemovesOrphanEntry(GameTestHelper helper) {
        FarmerSavedData data = new FarmerSavedData();
        java.util.UUID p = new java.util.UUID(0xF3L, 0xC3L);
        data.recordWheatSale(p, 10, 300L);
        // 翻日读取触发整条丢弃 (无孤儿日戳滞留); 落盘后重载该玩家应无任何卖菜记录。
        helper.assertTrue(data.wheatSoldToday(p, 301L) == 0, "rolled-over day reads 0 sold");
        net.minecraft.nbt.CompoundTag saved = data.save(new net.minecraft.nbt.CompoundTag());
        FarmerSavedData reloaded = FarmerSavedData.load(saved);
        helper.assertTrue(reloaded.wheatSoldToday(p, 301L) == 0,
                "reloaded data has no orphan stamp: rolled-over entry was removed before save");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wheatSaleRecordRoundTrips(GameTestHelper helper) {
        FarmerSavedData data = new FarmerSavedData();
        java.util.UUID p = new java.util.UUID(0xF4L, 0xD4L);
        data.recordWheatSale(p, 2200, 400L); // 株数超 cap。
        net.minecraft.nbt.CompoundTag saved = data.save(new net.minecraft.nbt.CompoundTag());
        FarmerSavedData reloaded = FarmerSavedData.load(saved);
        helper.assertTrue(reloaded.wheatSoldToday(p, 400L) == 2200, "sold count survives save/load round-trip");
        helper.succeed();
    }

    // ============================================================
    // 卖菜端到端: 触发点可达 (Critical 1) + 经 EconomyServices 定位器真发币 (Critical 2) + 并入全服每日
    // 信用点 faucet 软上限 (Major)。经济门面注册进 EconomyServices 定位器后, FarmerWheatSellService.sell
    // 必须真扣库存小麦、真增当日卖出株数、经 grantDaily 真入账。删任一修复测试必挂 (见各断言注释)。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sellGrantsCreditsAndDecrementsInventory(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        EconomyWalletData ledger = registerFreshEconomy();
        try {
            // 给 100 株 mod 小麦 (远低于收购 softCap 2160, 故全价 base=1 -> 毛收 100)。
            int amount = 100;
            long today = FarmerClock.currentUtcDayStamp();
            FarmerSavedData data = FarmerSavedData.get(player.server.overworld());
            int soldBefore = data.wheatSoldToday(player.getUUID(), today); // 基线 (共享持久层, 取增量防跨测试串扰)。
            player.getInventory().add(new ItemStack(FarmerItems.FARMER_WHEAT.get(), amount));

            FarmerWheatSellService.SellResult result = FarmerWheatSellService.sell(player, amount);

            // 触发点可达且经济已注册 -> 非 offline (删 /farmer sell 触发点或回退 isBound 死 seam 即 offline, 此断言挂)。
            helper.assertFalse(result.economyOffline(),
                    "sell with registered economy must not be offline (trigger point + locator wired)");
            helper.assertTrue(result.soldCount() == amount,
                    "sell removes all " + amount + " wheat, got soldCount=" + result.soldCount());

            // 库存小麦清零 (先扣后发, 真扣物品)。
            int leftover = player.getInventory().clearOrCountMatchingItems(
                    s -> s.is(FarmerItems.FARMER_WHEAT.get()), 0, new net.minecraft.world.SimpleContainer(0));
            helper.assertTrue(leftover == 0, "inventory mod wheat decremented to 0 after sale, got " + leftover);

            // 当日卖出株数 += amount (收购曲线计数真增; 取增量, 不依赖共享持久层的绝对值)。
            int soldAfter = data.wheatSoldToday(player.getUUID(), today);
            helper.assertTrue(soldAfter - soldBefore == amount,
                    "wheatSoldToday increased by exactly " + amount + " after sale, delta="
                            + (soldAfter - soldBefore));

            // 经 EconomyServices 定位器真入账 (删定位器调用或回退死 seam -> grant 不发生, 余额 0, 此断言挂)。
            // base=1, 全在收购 softCap(2160 株) 内 -> 毛收 100; 主闸首档 [0,60000) 系数 1.0 -> 全额入账 100
            // (第十一章: 统一 60000 档 / 0.6 衰减; 100 远在首档内不触衰减, carry 池 0.0+100.0=100.0 落整 100 留 0.0)。
            helper.assertTrue(result.creditsGranted() == 100L,
                    "100 wheat at base 1, faucet band 0 (cumulative raw 0..100 < 60000 tier) grants full 100 credits, got "
                            + result.creditsGranted());
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 100L,
                    "wallet credit balance reflects the granted 100 via the economy locator");
            helper.succeed();
        } finally {
            EconomyServices.reset();
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sellWhenEconomyUnregisteredIsOffline(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        EconomyServices.reset(); // 确保未注册 (定位器空 -> sell 应判 offline, 不扣不发)。
        try {
            player.getInventory().add(new ItemStack(FarmerItems.FARMER_WHEAT.get(), 10));

            FarmerWheatSellService.SellResult result = FarmerWheatSellService.sell(player, 10);

            // 未注册经济 -> offline, 不扣物品 (回退为 "未注册仍发币" 或抛 IllegalStateException 此断言挂)。
            helper.assertTrue(result.economyOffline(),
                    "sell with no registered economy must return offline (isRegistered gate)");
            helper.assertTrue(result.soldCount() == 0, "offline sale removes nothing");
            helper.assertTrue(result.creditsGranted() == 0L, "offline sale grants nothing");
            int kept = player.getInventory().clearOrCountMatchingItems(
                    s -> s.is(FarmerItems.FARMER_WHEAT.get()), 0, new net.minecraft.world.SimpleContainer(0));
            helper.assertTrue(kept == 10, "offline sale keeps all 10 wheat in inventory, got " + kept);
            helper.succeed();
        } finally {
            EconomyServices.reset();
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sellSharesPerPlayerDailyFaucetCapWithOtherFaucets(GameTestHelper helper) {
        // Major (第十一章决策 3/4): 卖菜与卖矿并入全服每人每日统一信用点衰减主闸 —— 同一 faucetKey (credit_faucet)、
        // 同一 60000 档值。主闸把衰减档划在"当日累计原始毛收入"轴上, 第 k 档系数 = max(0.01, 0.6^k)。
        // 本测先用同一 key 把累计原始毛收入推进满两整档 (模拟矿工卖矿先撞两档), 再卖菜: 卖菜落进第 2 档 (x0.36),
        // 证明它读的是共享 (player,key) 累计计数器而非农夫私有上限, 且系数是主闸的 0.6 几何衰减 (非逐矿 0.97)。
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        EconomyWalletData ledger = registerFreshEconomy();
        try {
            long tier = FarmerConstants.DAILY_CREDIT_FAUCET_CAP;            // 60000 (= 全服统一主闸档值)
            String sharedKey = FarmerConstants.WHEAT_SELL_FAUCET_KEY;       // credit_faucet
            // 卖菜键/档值就是全服唯一真源 (回退到农夫私有字面量/旧 2160 株档此处即挂)。
            helper.assertTrue(tier == EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER && tier == 60000L,
                    "wheat faucet tier IS the global 60000 tier, got " + tier);
            helper.assertTrue(sharedKey.equals(EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY),
                    "wheat faucet key IS the shared credit_faucet key, got " + sharedKey);

            // 另一 faucet (如矿工卖矿) 先用同一 key 发两笔, 各 60000 原始毛收入, 把累计原始毛收入推进到 120000 (= 2 整档)。
            // 第 0 档系数 1.0 全额 60000; 第 1 档系数 0.6 整档 -> 60000*0.6 = 36000 (无小数, carry 始终 0)。
            long band0 = EconomyServices.economyService().grantDaily(player, tier, sharedKey, tier);
            long band1 = EconomyServices.economyService().grantDaily(player, tier, sharedKey, tier);
            helper.assertTrue(band0 == 60000L, "band 0 full ratio 1.0: 60000 raw -> 60000 (got " + band0 + ")");
            helper.assertTrue(band1 == 36000L,
                    "band 1 ratio 0.6 (cumulative raw 60000..120000): 60000 raw -> 36000 (got " + band1 + ")");

            // 卖 100 株小麦: 共享累计原始毛收入 = 120000, 本批毛收 100 全落第 2 档 (raw 120000..120100, tier=120000/60000=2),
            // 系数 0.6^2 = 0.36 -> 精确实发 100*0.36 = 36.0, carry 池 0.0+36.0 -> 落整 36 留 0.0。
            // 株档 softCap 独立: 100 株远在 WHEAT_DAILY_SOFTCAP(2160 株) 内, 收购曲线全价 -> 毛收 gross = 100,
            // 不受 CP 主闸已深入第 2 档影响 (两条曲线不同量纲, 互不串扰)。
            helper.assertTrue(FarmerConstants.WHEAT_DAILY_SOFTCAP == 2160,
                    "buyback softcap stays 2160 株, decoupled from the 60000 CP faucet tier");
            helper.assertTrue(FarmerWheatBuyback.totalBuyPrice(0, 100, FarmerConstants.WHEAT_BASE_PRICE) == 100L,
                    "100 wheat within 2160-株 softcap is full base price, gross=100 (株 softcap not yet triggered)");

            int amount = 100;
            player.getInventory().add(new ItemStack(FarmerItems.FARMER_WHEAT.get(), amount));
            FarmerWheatSellService.SellResult result = FarmerWheatSellService.sell(player, amount);

            helper.assertTrue(result.soldCount() == amount, "all 100 wheat sold");
            // 主闸第 2 档 x0.36 -> floor(100*0.36)=36。若卖菜走农夫私有/独立上限 (回退), 它读不到矿工已推进的累计原始毛收入,
            // 会落第 0 档全额 100, 此断言挂 (区分共享主闸 vs 私有上限的判定锚)。同时 36 != floor(100*0.97)=97 区分 0.6 主闸 vs 0.97 逐矿。
            helper.assertTrue(result.creditsGranted() == 36L,
                    "wheat sale shares the cumulative faucet axis at band 2 (0.6^2=0.36): floor(100*0.36)=36, got "
                            + result.creditsGranted());

            // 账本余额 = 三笔实发之和 (共享同一玩家钱包同一主闸): 60000 + 36000 + 36 = 96036。
            long expectedBalance = band0 + band1 + 36L; // 96036
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 96036L
                            && ledger.balance(player.getUUID(), Currency.CREDIT) == expectedBalance,
                    "wallet = sum of all post-decay grants on the shared per-player faucet band = 96036, got "
                            + ledger.balance(player.getUUID(), Currency.CREDIT));
            helper.succeed();
        } finally {
            EconomyServices.reset();
        }
    }

    // ---- 测试辅助 (与 FarmerSystem.onCropHarvested 的原始经验公式同源) ----

    /**
     * 新建一套内存经济门面 (账本 + AbuseGuard + 惰性 PlayerAbuseState 解析器) 注册进 {@link EconomyServices} 定位器,
     * 供卖菜端到端测试经定位器真发币。返回账本以便断言余额。调用方 finally 务必 {@link EconomyServices#reset()}。
     */
    private static EconomyWalletData registerFreshEconomy() {
        EconomyWalletData ledger = new EconomyWalletData();
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        Function<UUID, PlayerAbuseState> resolver = id -> states.computeIfAbsent(id, k -> new PlayerAbuseState());
        EconomyServices.reset();
        EconomyServices.registerEconomyService(new EconomyService(ledger, new AbuseGuard(), resolver));
        return ledger;
    }

    private static Block requireFarmersDelightBlock(String path) {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("farmersdelight", path));
        if (block == null || block == Blocks.AIR) {
            throw new IllegalStateException("Missing Farmer's Delight block: " + path);
        }
        return block;
    }

    private static BlockState matureFarmersDelightCrop(String path) {
        Block block = requireFarmersDelightBlock(path);
        if (!(block instanceof net.minecraft.world.level.block.CropBlock crop)) {
            throw new IllegalStateException("Farmer's Delight block is not a CropBlock: " + path);
        }
        return crop.getStateForAge(crop.getMaxAge());
    }

    private static List<ItemStack> cropDrops(GameTestHelper helper, ServerPlayer player,
                                              BlockPos relativePos, BlockState state) {
        return Block.getDrops(state, helper.getLevel(), helper.absolutePos(relativePos),
                null, player, ItemStack.EMPTY);
    }

    private static int itemCount(List<ItemStack> loot, String farmersDelightItemPath) {
        net.minecraft.world.item.Item item = ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("farmersdelight", farmersDelightItemPath));
        return loot.stream()
                .filter(stack -> stack.is(item))
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    private static long rawXpForTier(FarmerTier tier) {
        return (long) FarmerConstants.SINGLE_CROP_XP * tier.yieldPerHarvest();
    }

    /** 高级地每小时收获次数 = 60min / 6min间隔 = 10 (表B; 仅吞吐校验用)。 */
    private static int harvestsPerHourHigh() {
        return 60 / FarmerTier.HIGH.growthIntervalMinutes();
    }

    private record TestPlantable(PlantType type) implements IPlantable {
        @Override
        public PlantType getPlantType(BlockGetter level, BlockPos pos) {
            return type;
        }

        @Override
        public BlockState getPlant(BlockGetter level, BlockPos pos) {
            return Blocks.WHEAT.defaultBlockState();
        }
    }

    // ============================================================
    // 收获增产的触发者校验 (FarmerHarvestLootModifier)
    //
    // 刻意使用原版小麦而非 Farmer's Delight 作物: 上方那批 FD 兼容用例在依赖缺席时走 helper.succeed(),
    // 在 dev GameTest 环境里是不执行的假绿; 而原版小麦同样登记在 FarmerHarvests.PRODUCE_BY_CROP 中,
    // 受同一个全局掉落修改器影响, 因此下列用例在任何环境下都真实执行。
    // ============================================================

    /** 成熟原版小麦的基准产出恒为 1 个小麦 (种子数随机, 不参与断言)。 */
    private static final int VANILLA_WHEAT_BASE = 1;

    private static BlockPos prepareSupremeWheat(GameTestHelper helper) {
        BlockPos relativeSoil = new BlockPos(1, 1, 1);
        helper.setBlock(relativeSoil, FarmerBlocks.farmland(FarmerTier.SUPREME).get().defaultBlockState());
        BlockPos relativeCrop = relativeSoil.above();
        helper.setBlock(relativeCrop, matureVanillaWheat());
        return relativeCrop;
    }

    private static BlockState matureVanillaWheat() {
        return Blocks.WHEAT.defaultBlockState()
                .setValue(net.minecraft.world.level.block.CropBlock.AGE, 7);
    }

    private static int wheatCount(List<ItemStack> loot) {
        int total = 0;
        for (ItemStack stack : loot) {
            if (stack.is(net.minecraft.world.item.Items.WHEAT)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void setFarmerLevel(ServerPlayer player, int level) {
        com.miningdim.entry.MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家未挂载 capability, 无法摆放职业等级"))
                .jobProgress(com.miningdim.job.JobId.FARMER)
                .setLevel(level);
    }

    /** 无实体来源 (活塞推毁/爆炸/自动化通用 API) 不得增产。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void vanillaCropYieldRejectsSourceWithoutEntity(GameTestHelper helper) {
        BlockPos relativeCrop = prepareSupremeWheat(helper);
        // 4 参重载不写入 THIS_ENTITY, 等价于活塞与爆炸走的掉落路径。
        List<ItemStack> loot = Block.getDrops(matureVanillaWheat(), helper.getLevel(),
                helper.absolutePos(relativeCrop), null);
        helper.assertTrue(wheatCount(loot) == VANILLA_WHEAT_BASE,
                "无实体来源必须零增产, 期望 " + VANILLA_WHEAT_BASE + " 实得 " + wheatCount(loot));
        helper.succeed();
    }

    /** FakePlayer 驱动的自动化收割不得增产 (它是 ServerPlayer 子类且实体类型同为 minecraft:player)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void vanillaCropYieldRejectsFakePlayer(GameTestHelper helper) {
        BlockPos relativeCrop = prepareSupremeWheat(helper);
        net.minecraftforge.common.util.FakePlayer fake = new net.minecraftforge.common.util.FakePlayer(
                helper.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "test-fake-harvester"));
        setFarmerLevel(fake, JobXpCurve.MAX_LEVEL);
        List<ItemStack> loot = Block.getDrops(matureVanillaWheat(), helper.getLevel(),
                helper.absolutePos(relativeCrop), null, fake, ItemStack.EMPTY);
        helper.assertTrue(wheatCount(loot) == VANILLA_WHEAT_BASE,
                "FakePlayer 必须零增产 (即使等级已满), 期望 " + VANILLA_WHEAT_BASE
                        + " 实得 " + wheatCount(loot));
        helper.succeed();
    }

    /** 等级未解锁该耕地档位的玩家不得享受其倍率。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void vanillaCropYieldRequiresTierUnlock(GameTestHelper helper) {
        BlockPos relativeCrop = prepareSupremeWheat(helper);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setFarmerLevel(player, FarmerTier.SUPREME.unlockLevel() - 1);
        List<ItemStack> loot = Block.getDrops(matureVanillaWheat(), helper.getLevel(),
                helper.absolutePos(relativeCrop), null, player, ItemStack.EMPTY);
        helper.assertTrue(wheatCount(loot) == VANILLA_WHEAT_BASE,
                "等级不足 SUPREME 解锁线时必须零增产, 期望 " + VANILLA_WHEAT_BASE
                        + " 实得 " + wheatCount(loot));
        helper.succeed();
    }

    /** 等级达标的农夫本人收割时, 倍率必须照常生效 (防止修复过度杀伤合法收益)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void vanillaCropYieldAppliesForUnlockedFarmer(GameTestHelper helper) {
        BlockPos relativeCrop = prepareSupremeWheat(helper);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setFarmerLevel(player, FarmerTier.SUPREME.unlockLevel());
        List<ItemStack> loot = Block.getDrops(matureVanillaWheat(), helper.getLevel(),
                helper.absolutePos(relativeCrop), null, player, ItemStack.EMPTY);
        int expected = VANILLA_WHEAT_BASE * FarmerTier.SUPREME.yieldPerHarvest();
        helper.assertTrue(wheatCount(loot) == expected,
                "等级达标农夫应得 SUPREME 全额倍率, 期望 " + expected + " 实得 " + wheatCount(loot));
        helper.succeed();
    }

    /** 低等级玩家在已解锁的低档耕地上仍应拿到该档倍率, 等级门只挡越级。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void vanillaCropYieldStillAppliesOnUnlockedLowTier(GameTestHelper helper) {
        BlockPos relativeSoil = new BlockPos(1, 1, 1);
        helper.setBlock(relativeSoil, FarmerBlocks.farmland(FarmerTier.LOW).get().defaultBlockState());
        BlockPos relativeCrop = relativeSoil.above();
        helper.setBlock(relativeCrop, matureVanillaWheat());
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setFarmerLevel(player, JobXpCurve.MIN_LEVEL);
        List<ItemStack> loot = Block.getDrops(matureVanillaWheat(), helper.getLevel(),
                helper.absolutePos(relativeCrop), null, player, ItemStack.EMPTY);
        int expected = VANILLA_WHEAT_BASE * FarmerTier.LOW.yieldPerHarvest();
        helper.assertTrue(wheatCount(loot) == expected,
                "LOW 档对 1 级玩家已解锁, 应得其倍率, 期望 " + expected + " 实得 " + wheatCount(loot));
        helper.succeed();
    }
}
