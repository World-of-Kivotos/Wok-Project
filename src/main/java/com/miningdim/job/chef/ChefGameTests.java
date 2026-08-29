package com.miningdim.job.chef;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyServices;
import com.miningdim.effect.ModJobEffects;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.JobId;
import com.miningdim.progression.ExperienceServices;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** 厨师模块闭环 GameTest：事务、经验、食用快照、真实窗口效果及可选模组数据契约。 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChefGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "chef";
    private static final BlockPos TABLE_RELATIVE = new BlockPos(1, 1, 1);
    /**
     * 单菜原始经验逐档硬钉值 (下标 = 品质 tier)。刻意写成常量而不是回调 {@link ChefConfig#xpForQuality}:
     * 发经验的生产代码调的正是那个函数, 拿它当期望值等于用被测实现验证被测实现, 整张经验表会变成无人看守。
     */
    private static final int[] RAW_XP_BY_TIER = {50, 80, 130, 220, 400};

    static {
        ChefConfig.ensureLoadedForTest();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void qualityCapsAndDeterministicPools(GameTestHelper helper) {
        ChefQteTiming lowLevelOneTiming = ChefQteTiming.forChallenge(
                ChefQuality.LOW, 1, ChefQuality.LOW);
        ChefQteTiming mediumLevelThreeTiming = ChefQteTiming.forChallenge(
                ChefQuality.MEDIUM, 3, ChefQuality.MEDIUM);
        ChefQteTiming highLevelFiveTiming = ChefQteTiming.forChallenge(
                ChefQuality.HIGH, 5, ChefQuality.HIGH);
        ChefQteTiming extraordinaryLevelSevenTiming = ChefQteTiming.forChallenge(
                ChefQuality.EXTRAORDINARY, 7, ChefQuality.EXTRAORDINARY);
        ChefQteTiming radiantLevelNineTiming = ChefQteTiming.forChallenge(
                ChefQuality.RADIANT, 9, ChefQuality.RADIANT);
        ChefQteTiming radiantMaxLevelTiming = ChefQteTiming.forChallenge(
                ChefQuality.RADIANT, 10, ChefQuality.RADIANT);
        ChefQteTiming radiantLevelOneTiming = ChefQteTiming.forChallenge(
                ChefQuality.RADIANT, 1, ChefQuality.RADIANT);
        ChefQteTiming radiantOnLowTableTiming = ChefQteTiming.forChallenge(
                ChefQuality.RADIANT, 1, ChefQuality.LOW);
        ChefQteTiming highOnLowTableTiming = ChefQteTiming.forChallenge(
                ChefQuality.HIGH, 1, ChefQuality.LOW);
        helper.assertTrue(lowLevelOneTiming.equals(new ChefQteTiming(4, 20, 15, 35, 10))
                        && mediumLevelThreeTiming.equals(new ChefQteTiming(5, 18, 13, 33, 9))
                        && highLevelFiveTiming.equals(new ChefQteTiming(5, 16, 11, 31, 8)),
                "matching higher tables reduce cues while higher targets retain faster timing");
        helper.assertTrue(extraordinaryLevelSevenTiming.equals(new ChefQteTiming(6, 14, 9, 29, 7))
                        && radiantLevelNineTiming.equals(new ChefQteTiming(6, 12, 7, 27, 6)),
                "extraordinary and radiant matching tables remove one and two cues respectively");
        helper.assertTrue(radiantMaxLevelTiming.equals(new ChefQteTiming(6, 13, 8, 28, 7))
                        && radiantLevelOneTiming.equals(new ChefQteTiming(10, 4, 3, 11, 4)),
                "a level 1 chef receives four extra cues and tighter pacing on radiant quality");
        helper.assertTrue(radiantOnLowTableTiming.equals(new ChefQteTiming(16, 4, 3, 7, 4))
                        && highOnLowTableTiming.equals(new ChefQteTiming(10, 6, 3, 21, 4)),
                "low tables compound missing-tier and novice penalties on advanced targets");
        helper.assertTrue(ChefQteTiming.cueCountFor(
                        ChefQuality.HIGH, 10, ChefQuality.RADIANT) == 4,
                "a radiant table removes two cues from a high-quality target");
        int cues = radiantLevelNineTiming.cueCount();
        int radiantBaseAtLevel9 = ChefQualityResolver.successChancePerMille(
                ChefQuality.RADIANT, 0.0D, 0, cues, 9, ChefQuality.RADIANT);
        int radiantOneHitAtLevel9 = ChefQualityResolver.successChancePerMille(
                ChefQuality.RADIANT, 0.0D, 1, cues, 9, ChefQuality.RADIANT);
        int radiantPerfectAtLevel9 = ChefQualityResolver.successChancePerMille(
                ChefQuality.RADIANT, 1.0D, cues, cues, 9, ChefQuality.RADIANT);
        int radiantPerfectAtLevel10 = ChefQualityResolver.successChancePerMille(
                ChefQuality.RADIANT, 1.0D, cues, cues, 10, ChefQuality.RADIANT);
        int radiantPerfectAtLevel1 = ChefQualityResolver.successChancePerMille(
                ChefQuality.RADIANT, 1.0D, cues, cues, 1, ChefQuality.RADIANT);
        int highPerfectAtLevel10 = ChefQualityResolver.successChancePerMille(
                ChefQuality.HIGH, 1.0D, highLevelFiveTiming.cueCount(),
                highLevelFiveTiming.cueCount(), 10, ChefQuality.HIGH);
        int extraordinaryPerfectAtLevel10 = ChefQualityResolver.successChancePerMille(
                ChefQuality.EXTRAORDINARY, 1.0D, extraordinaryLevelSevenTiming.cueCount(),
                extraordinaryLevelSevenTiming.cueCount(), 10, ChefQuality.EXTRAORDINARY);
        int highBaseOnHighTable = ChefQualityResolver.successChancePerMille(
                ChefQuality.HIGH, 0.0D, 0, highLevelFiveTiming.cueCount(), 10, ChefQuality.HIGH);
        int highBaseOnRadiantTable = ChefQualityResolver.successChancePerMille(
                ChefQuality.HIGH, 0.0D, 0, 4, 10, ChefQuality.RADIANT);
        // 89 而不是旧的 97: QTE 加成已改成按命中率折算, 六个 cue 里中一个只值 round(1/6 x 500) = 83,
        // 不再是"每命中一次固定 +100"。手算链 100+83=183 -> x450 难度 = 82 -> x900 等级 = 74 -> x1200 台档 = 89。
        helper.assertTrue(radiantBaseAtLevel9 == 49 && radiantOneHitAtLevel9 == 89,
                "one hit out of six cues lifts the radiant chance from 49 to 89 per-mille, got "
                        + radiantBaseAtLevel9 + "/" + radiantOneHitAtLevel9);
        helper.assertTrue(radiantPerfectAtLevel1 == 54 && radiantPerfectAtLevel9 == 486
                        && radiantPerfectAtLevel10 == 540,
                "open radiant selection stays rare for novices and scales strongly with chef level");
        helper.assertTrue(highPerfectAtLevel10 == 935 && extraordinaryPerfectAtLevel10 == 748,
                "matching high-tier tables improve but do not erase target difficulty");
        helper.assertTrue(highBaseOnHighTable == 421 && highBaseOnRadiantTable == 460,
                "a better table visibly improves the same high-quality challenge");
        helper.assertTrue(ChefQualityResolver.successChancePerMille(
                        ChefQuality.LOW, 0.0D, 0, lowLevelOneTiming.cueCount(), 1,
                        ChefQuality.LOW) == 1000,
                "low quality remains the novice fallback and is not reduced by chef level");
        int[] qualityThresholds = {1000, 100, 85, 65, 29};
        helper.assertTrue(ChefQualityResolver.resolveTargetRoll(
                        ChefQuality.RADIANT, 28, quality -> qualityThresholds[quality.tier()])
                        == ChefQuality.RADIANT,
                "a roll immediately below the radiant threshold reaches the selected target");
        helper.assertTrue(ChefQualityResolver.resolveTargetRoll(
                        ChefQuality.RADIANT, 29, quality -> qualityThresholds[quality.tier()])
                        == ChefQuality.EXTRAORDINARY
                        && ChefQualityResolver.resolveTargetRoll(
                        ChefQuality.RADIANT, 65, quality -> qualityThresholds[quality.tier()])
                        == ChefQuality.HIGH,
                "a missed target continues down the configured quality thresholds");
        helper.assertTrue(ChefQualityResolver.resolveTargetRoll(
                        ChefQuality.RADIANT, 100, quality -> qualityThresholds[quality.tier()])
                        == ChefQuality.LOW,
                "a roll missing every higher threshold falls back to low quality");

        List<ChefEffectType> lowPool = SeasoningEffectRoller.unlockedPool(10, ChefQuality.LOW);
        helper.assertTrue(lowPool.stream().noneMatch(ChefEffectType::isCombat),
                "low quality pool contains no combat effects");
        helper.assertTrue(lowPool.stream().anyMatch(ChefEffectType::isNegative),
                "low quality pool retains failure outcomes");
        List<ChefEffectType> radiantPool = SeasoningEffectRoller.unlockedPool(10, ChefQuality.RADIANT);
        helper.assertTrue(radiantPool.stream().noneMatch(ChefEffectType::isNegative),
                "radiant quality pool has zero failure outcomes");
        helper.assertTrue(radiantPool.stream().anyMatch(ChefEffectType::isCombat),
                "radiant high-level pool contains combat support");

        RandomSource fixed = RandomSource.create(0xC0FFEE12L);
        for (int i = 0; i < 16; i++) {
            List<ChefEffectInstance> rolled = SeasoningEffectRoller.rollAll(
                    fixed, 10, ChefQuality.RADIANT, SeasoningBias.OILY, cues);
            helper.assertTrue(rolled.size() <= ChefQuality.RADIANT.maxEffects(),
                    "radiant dish exceeds configured effect cap");
            helper.assertTrue(rolled.stream().filter(effect -> effect.type().isCombat()).count() <= 1,
                    "one dish contains more than one combat effect");
            helper.assertTrue(rolled.stream().noneMatch(effect -> effect.type().isNegative()),
                    "radiant deterministic roll contains a negative effect");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void qualitySchemaAndSelectedDefaults(GameTestHelper helper) {
        ItemStack stamped = new ItemStack(Items.BREAD);
        ChefQualityNbt.stamp(stamped, ChefQuality.HIGH,
                List.of(new ChefEffectInstance(ChefEffectType.NOURISH_HEAL,
                        ChefConfig.healPerMille(ChefQuality.HIGH))));
        helper.assertTrue(ChefQualityNbt.readSchemaVersion(stamped) == 2,
                "new dishes write MiningChef schema v2");

        ItemStack legacy = new ItemStack(Items.BREAD);
        CompoundTag legacyRoot = new CompoundTag();
        legacyRoot.putString("quality", ChefQuality.MEDIUM.id());
        legacyRoot.put("effects", new net.minecraft.nbt.ListTag());
        legacy.getOrCreateTag().put(ChefQualityNbt.ROOT_TAG, legacyRoot);
        helper.assertTrue(ChefQualityNbt.readSchemaVersion(legacy) == 1,
                "dishes without schemaVersion are read as v1");
        helper.assertTrue(ChefQualityNbt.readQuality(legacy) == ChefQuality.MEDIUM,
                "legacy MiningChef quality id remains readable");

        helper.assertTrue(ChefConfig.healPerMille(ChefQuality.HIGH) == 75,
                "high nourish heal is 7.5 percent max health");
        helper.assertTrue(ChefConfig.healPerMille(ChefQuality.EXTRAORDINARY) == 100,
                "extraordinary nourish heal is 10 percent max health");
        helper.assertTrue(ChefConfig.healPerMille(ChefQuality.RADIANT) == 1000,
                "radiant nourish heal is 100 percent max health");
        helper.assertTrue(ChefConfig.rawXp(ChefQuality.LOW) == RAW_XP_BY_TIER[0]
                        && ChefConfig.rawXp(ChefQuality.MEDIUM) == RAW_XP_BY_TIER[1]
                        && ChefConfig.rawXp(ChefQuality.HIGH) == RAW_XP_BY_TIER[2]
                        && ChefConfig.rawXp(ChefQuality.EXTRAORDINARY) == RAW_XP_BY_TIER[3]
                        && ChefConfig.rawXp(ChefQuality.RADIANT) == RAW_XP_BY_TIER[4],
                "single-dish raw XP stays 50/80/130/220/400 across the five quality tiers");
        helper.assertTrue(ChefConfig.seasoningBiasedWeight() == 3
                        && ChefConfig.seasoningNeutralWeight() == 1
                        && ChefConfig.complexVirtualHits() == 1,
                "seasoning defaults keep 3:1 bias and one complex virtual hit");
        helper.succeed();
    }

    /**
     * 两批翻译键都必须齐: 十个真实注册的窗口 MobEffect 的原版描述键, 以及<b>全部</b> {@link ChefEffectType}
     * 的 chef.effect.&lt;id&gt; 键 (tooltip 与平板厨师页共用的那一批)。
     *
     * 第二批刻意写成遍历枚举而不是手抄名单: 新加一个效果种类却忘了补两份 lang 时, 玩家看到的是一行生键名,
     * 而手抄名单不会有任何反应。遍历 values() 才能让漏译当场挂测试。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void registeredWindowEffectsHaveClientTranslations(GameTestHelper helper) {
        JsonObject zh = loadJsonResource("/assets/miningdim/lang/zh_cn.json");
        JsonObject en = loadJsonResource("/assets/miningdim/lang/en_us.json");
        List<MobEffect> effects = List.of(
                ModJobEffects.CHEF_ENDURANCE.get(), ModJobEffects.CHEF_SATIATION.get(),
                ModJobEffects.CHEF_SHIELD.get(), ModJobEffects.CHEF_GREASE.get(),
                ModJobEffects.CHEF_AFTERTASTE_REGEN.get(), ModJobEffects.CHEF_STABLE_AIM.get(),
                ModJobEffects.CHEF_FIRE_QUELL.get(), ModJobEffects.CHEF_GILLS.get(),
                ModJobEffects.CHEF_FEATHER.get(), ModJobEffects.CHEF_FIREFLY.get());
        for (MobEffect effect : effects) {
            assertTranslated(helper, zh, en, effect.getDescriptionId(), "registered chef window effect");
        }
        // "chef.effect." 前缀是 ChefTooltipHandler 与 ChefWebUiActions.labelKey 共用的契约, 此处照写字面量。
        for (ChefEffectType type : ChefEffectType.values()) {
            assertTranslated(helper, zh, en, "chef.effect." + type.id(), "chef effect type");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH,
            timeoutTicks = 400)
    public static void seasoningTransactionAwardsOnceAfterAtomicSuccess(GameTestHelper helper) {
        TableFixture fixture = tableFixture(helper);
        fixture.table.inputSlots().setStackInSlot(SeasoningMenu.SLOT_INPUT, new ItemStack(Items.BREAD, 2));
        fixture.table.inputSlots().setStackInSlot(SeasoningMenu.SLOT_SEASONING, new ItemStack(Items.SUGAR, 2));

        long fee = ChefConfig.seasoningCreditCost();
        long initialCredits = EconomyServices.economyService().creditBalance(fixture.player);
        EconomyServices.economyService().grant(fixture.player, Currency.CREDIT, fee);
        long xpBefore = ExperienceServices.experienceService()
                .snapshot(fixture.player, ChefExperience.TRACK_ID).totalXp();

        helper.assertTrue(fixture.table.startCooking(fixture.player, ChefQuality.LOW.tier()),
                "valid bread starts the selected low-quality challenge");
        helper.assertTrue(fixture.menu.qteCount() == 2 && fixture.menu.qteSwayPeriodTicks() == 10,
                "radiant table sync removes two cues from the active low-quality challenge");
        BlockPos secondaryPos = fixture.absolute.relative(Direction.EAST);
        helper.assertTrue(helper.getLevel().getBlockState(fixture.absolute).getValue(SeasoningTableBlock.ACTIVE)
                        && helper.getLevel().getBlockState(secondaryPos).getValue(SeasoningTableBlock.ACTIVE),
                "starting a cook synchronizes the GeckoLib cooking state across both halves");
        drivePerfectHeat(helper, fixture);
        driveAllQteHits(helper, fixture);
        helper.assertTrue(!helper.getLevel().getBlockState(fixture.absolute).getValue(SeasoningTableBlock.ACTIVE)
                        && !helper.getLevel().getBlockState(secondaryPos).getValue(SeasoningTableBlock.ACTIVE),
                "successful settlement returns both halves to the idle animation state");

        ItemStack output = findStampedDish(fixture.player);
        helper.assertFalse(output.isEmpty(), "successful transaction gives one stamped dish");
        ChefQuality achieved = ChefQualityNbt.readQuality(output);
        helper.assertTrue(output.getCount() == 1, "one seasoning transaction produces one dish");
        helper.assertTrue(fixture.table.inputSlots().getStackInSlot(SeasoningMenu.SLOT_INPUT).getCount() == 1,
                "one unseasoned input remains");
        helper.assertTrue(fixture.table.inputSlots().getStackInSlot(SeasoningMenu.SLOT_SEASONING).getCount() == 1,
                "one seasoning item is consumed");
        helper.assertTrue(EconomyServices.economyService().creditBalance(fixture.player) == initialCredits,
                "exactly the configured five-credit fee is consumed");
        long xpAfter = ExperienceServices.experienceService()
                .snapshot(fixture.player, ChefExperience.TRACK_ID).totalXp();
        // 低品质目标是下探链的保底档, 不掷阈值, 所以完美操作必定恰好落在低品质 -> 入账额是硬钉的 50。
        helper.assertTrue(achieved == ChefQuality.LOW,
                "a low target always settles exactly on low quality, got " + achieved.id());
        helper.assertTrue(xpAfter - xpBefore == RAW_XP_BY_TIER[achieved.tier()],
                "successful low-quality cook awards exactly 50 raw XP once, got " + (xpAfter - xpBefore));
        helper.assertTrue(ExperienceServices.experienceService().hasSource(
                        ChefExperience.SEASONING_COMPLETE_SOURCE),
                "stable seasoning_complete XP source is registered");

        for (int i = 0; i < 20; i++) {
            fixture.table.serverTick();
        }
        long xpAfterIdleTicks = ExperienceServices.experienceService()
                .snapshot(fixture.player, ChefExperience.TRACK_ID).totalXp();
        helper.assertTrue(xpAfterIdleTicks == xpAfter, "completed table cannot duplicate XP on later ticks");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH,
            timeoutTicks = 400)
    public static void seasoningInsufficientFundsHasNoSideEffects(GameTestHelper helper) {
        TableFixture fixture = tableFixture(helper);
        fixture.table.inputSlots().setStackInSlot(SeasoningMenu.SLOT_INPUT, new ItemStack(Items.BREAD));
        fixture.table.inputSlots().setStackInSlot(SeasoningMenu.SLOT_SEASONING, new ItemStack(Items.SUGAR));
        long xpBefore = ExperienceServices.experienceService()
                .snapshot(fixture.player, ChefExperience.TRACK_ID).totalXp();

        helper.assertTrue(fixture.table.startCooking(fixture.player, ChefQuality.LOW.tier()),
                "valid transaction starts before fee check");
        drivePerfectHeat(helper, fixture);
        driveAllQteHits(helper, fixture);

        helper.assertTrue(findStampedDish(fixture.player).isEmpty(),
                "insufficient balance produces no stamped dish");
        helper.assertTrue(fixture.table.inputSlots().getStackInSlot(SeasoningMenu.SLOT_INPUT).getCount() == 1,
                "insufficient balance keeps the original dish");
        helper.assertTrue(fixture.table.inputSlots().getStackInSlot(SeasoningMenu.SLOT_SEASONING).getCount() == 1,
                "insufficient balance keeps seasoning");
        helper.assertTrue(ExperienceServices.experienceService()
                        .snapshot(fixture.player, ChefExperience.TRACK_ID).totalXp() == xpBefore,
                "insufficient balance awards no XP");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void seasoningValidationConcurrencyAndCloseCancel(GameTestHelper helper) {
        TableFixture fixture = tableFixture(
                helper, (SeasoningTableBlock) ChefBlocks.SEASONING_TABLE_LOW.get());
        fixture.table.inputSlots().setStackInSlot(SeasoningMenu.SLOT_INPUT, new ItemStack(Items.DIAMOND));
        helper.assertFalse(fixture.table.startCooking(fixture.player, ChefQuality.LOW.tier()),
                "non-food input is rejected");

        ItemStack alreadySeasoned = new ItemStack(Items.BREAD);
        ChefQualityNbt.stamp(alreadySeasoned, ChefQuality.LOW, List.of());
        fixture.table.inputSlots().setStackInSlot(SeasoningMenu.SLOT_INPUT, alreadySeasoned);
        helper.assertFalse(fixture.table.startCooking(fixture.player, ChefQuality.LOW.tier()),
                "already seasoned dish is rejected");

        fixture.table.inputSlots().setStackInSlot(SeasoningMenu.SLOT_INPUT, new ItemStack(Items.BREAD));
        fixture.table.inputSlots().setStackInSlot(SeasoningMenu.SLOT_SEASONING, new ItemStack(Items.SUGAR));
        helper.assertTrue(fixture.menu.tierCap() == ChefQuality.LOW,
                "fixture is the low tier seasoning table");
        // 台档不再对可选目标封顶, 所以逐档真开工再取消: 判据是服务端受理且 targetQuality 原样落库 ——
        // 若哪天又把目标 clamp 回台档, startCooking 仍会返回 true, 只有这条同步值断言会变红。
        for (ChefQuality probe : ChefQuality.values()) {
            helper.assertTrue(fixture.table.startCooking(fixture.player, probe.tier()),
                    "low table accepts target tier " + probe.tier() + " from a level 1 chef");
            helper.assertTrue(fixture.menu.targetQualityTier() == probe.tier(),
                    "accepted target tier " + probe.tier() + " is not clamped down to the table tier");
            fixture.table.cancelCooking(fixture.player, "test resets an accepted target probe");
        }
        helper.assertTrue(fixture.table.startCooking(fixture.player, ChefQuality.RADIANT.tier()),
                "level 1 chef can request a radiant target on a low table");
        helper.assertTrue(fixture.menu.qteCount() == 16 && fixture.menu.successChancePerMille() == 5,
                "low-table novice radiant challenge compounds sixteen cues with a very low initial chance");
        fixture.table.cancelCooking(fixture.player, "test resets the accepted radiant challenge");
        helper.assertTrue(fixture.table.startCooking(fixture.player, ChefQuality.LOW.tier()),
                "first operator locks the table");
        var second = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        second.setPos(fixture.absolute.getX() + 0.5D, fixture.absolute.getY() + 0.5D,
                fixture.absolute.getZ() + 0.5D);
        SeasoningMenu secondMenu = new SeasoningMenu(2, second.getInventory(), fixture.table);
        second.containerMenu = secondMenu;
        helper.assertFalse(fixture.table.startCooking(second, ChefQuality.LOW.tier()),
                "second player cannot replace the operator");
        helper.assertFalse(fixture.menu.getSlot(SeasoningMenu.SLOT_INPUT).mayPickup(fixture.player),
                "active input slot is pickup-locked");
        helper.assertFalse(fixture.menu.getSlot(SeasoningMenu.SLOT_SEASONING).mayPlace(new ItemStack(Items.SUGAR)),
                "active seasoning slot is placement-locked");
        helper.assertTrue(fixture.table.pressHeat(fixture.player), "operator can press heat once");
        helper.assertFalse(fixture.table.pressHeat(fixture.player), "duplicate heat packet is rejected");

        long xpBefore = ExperienceServices.experienceService()
                .snapshot(fixture.player, ChefExperience.TRACK_ID).totalXp();
        fixture.menu.removed(fixture.player);
        helper.assertFalse(fixture.table.isActive(), "closing menu cancels the active transaction");
        BlockPos secondaryPos = fixture.absolute.relative(Direction.EAST);
        helper.assertTrue(!helper.getLevel().getBlockState(fixture.absolute).getValue(SeasoningTableBlock.ACTIVE)
                        && !helper.getLevel().getBlockState(secondaryPos).getValue(SeasoningTableBlock.ACTIVE),
                "cancellation clears the GeckoLib cooking state on both halves");
        helper.assertTrue(fixture.table.inputSlots().getStackInSlot(SeasoningMenu.SLOT_INPUT).is(Items.BREAD),
                "close cancellation preserves the original dish");
        helper.assertTrue(fixture.table.inputSlots().getStackInSlot(SeasoningMenu.SLOT_SEASONING).is(Items.SUGAR),
                "close cancellation preserves seasoning");
        helper.assertTrue(ExperienceServices.experienceService()
                        .snapshot(fixture.player, ChefExperience.TRACK_ID).totalXp() == xpBefore,
                "close cancellation awards no XP");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void consumeAmplifiesOnlyThisMealsNetGains(GameTestHelper helper) {
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ChefConsumeHandler handler = new ChefConsumeHandler();
        ItemStack dish = new ItemStack(Items.BREAD);
        ChefQualityNbt.stamp(dish, ChefQuality.RADIANT, List.of(
                new ChefEffectInstance(ChefEffectType.AFTERTASTE_SAT,
                        ChefConfig.aftertasteSatMul(ChefQuality.RADIANT)),
                new ChefEffectInstance(ChefEffectType.NOURISH_FOOD,
                        ChefConfig.nourishFoodMul(ChefQuality.RADIANT))));

        player.getFoodData().setFoodLevel(5);
        player.getFoodData().setSaturation(2.0F);
        start(handler, player, dish);
        player.getFoodData().setFoodLevel(10);
        player.getFoodData().setSaturation(8.0F);
        finish(handler, player, dish);
        helper.assertTrue(player.getFoodData().getFoodLevel() == 20,
                "nourish multiplies only the meal's five-point food gain and caps at 20");
        helper.assertTrue(Math.abs(player.getFoodData().getSaturationLevel() - 20.0F) < 0.001F,
                "aftertaste multiplies only the six-point saturation gain after food expansion");

        player.getFoodData().setFoodLevel(18);
        player.getFoodData().setSaturation(17.0F);
        start(handler, player, dish);
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(20.0F);
        finish(handler, player, dish);
        helper.assertTrue(player.getFoodData().getFoodLevel() == 20
                        && Math.abs(player.getFoodData().getSaturationLevel() - 20.0F) < 0.001F,
                "near-cap consumption never exceeds vanilla food and saturation limits");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void spoiledDishRestoresExactPreMealSnapshot(GameTestHelper helper) {
        ItemStack dish = new ItemStack(Items.GOLDEN_APPLE);
        ChefQualityNbt.stamp(dish, ChefQuality.LOW,
                List.of(new ChefEffectInstance(ChefEffectType.SPOILED, 0)));
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ChefConsumeHandler handler = new ChefConsumeHandler();
        player.getFoodData().setFoodLevel(18);
        player.getFoodData().setSaturation(17.0F);
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 0));
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 300, 0));

        start(handler, player, dish);
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(20.0F);
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 400, 1));
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 1200, 0));
        finish(handler, player, dish);

        MobEffectInstance regeneration = player.getEffect(MobEffects.REGENERATION);
        MobEffectInstance speed = player.getEffect(MobEffects.MOVEMENT_SPEED);
        helper.assertTrue(player.getFoodData().getFoodLevel() == 18
                        && Math.abs(player.getFoodData().getSaturationLevel() - 17.0F) < 0.001F,
                "spoiled dish restores food and saturation without subtracting old resources");
        helper.assertTrue(regeneration != null && regeneration.getDuration() == 200
                        && regeneration.getAmplifier() == 0,
                "spoiled dish restores the meal-declared pre-existing effect");
        helper.assertFalse(player.hasEffect(MobEffects.ABSORPTION),
                "spoiled dish removes a meal-declared effect that was absent before eating");
        helper.assertTrue(speed != null && speed.getDuration() == 300,
                "spoiled dish does not alter unrelated pre-existing effects");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void amplifyUsesFreshEffectDeltaAndHonorsBlacklists(GameTestHelper helper) {
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ChefConsumeHandler handler = new ChefConsumeHandler();
        player.addEffect(new MobEffectInstance(MobEffects.JUMP, 100, 0));
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 300, 0));
        Set<MobEffect> declared = Set.of(MobEffects.JUMP);
        var before = ChefConsumeHandler.declaredEffectsBefore(player, declared);
        player.addEffect(new MobEffectInstance(MobEffects.JUMP, 400, 0));
        handler.amplifyDeclaredBuffs(player, declared, ChefConfig.amplifyMul(ChefQuality.HIGH), before);
        helper.assertTrue(player.getEffect(MobEffects.JUMP).getDuration() == 700,
                "x2 amplify doubles only the fresh 300-tick food contribution");
        helper.assertTrue(player.getEffect(MobEffects.MOVEMENT_SPEED).getDuration() == 300,
                "amplify never touches an unrelated potion or previous meal effect");

        var noProcPlayer = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        noProcPlayer.addEffect(new MobEffectInstance(MobEffects.JUMP, 250, 0));
        var noProcBefore = ChefConsumeHandler.declaredEffectsBefore(noProcPlayer, declared);
        handler.amplifyDeclaredBuffs(noProcPlayer, declared,
                ChefConfig.amplifyMul(ChefQuality.HIGH), noProcBefore);
        helper.assertTrue(noProcPlayer.getEffect(MobEffects.JUMP).getDuration() == 250,
                "a food effect that did not trigger cannot amplify an old effect of the same type");

        ItemStack apple = new ItemStack(Items.ENCHANTED_GOLDEN_APPLE);
        ChefQualityNbt.stamp(apple, ChefQuality.RADIANT,
                List.of(new ChefEffectInstance(ChefEffectType.AMPLIFY,
                        ChefConfig.amplifyMul(ChefQuality.RADIANT))));
        var appleEater = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        start(handler, appleEater, apple);
        appleEater.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 1200, 3));
        finish(handler, appleEater, apple);
        helper.assertTrue(appleEater.getEffect(MobEffects.ABSORPTION).getDuration() == 1200,
                "enchanted golden apple effects are never duration-amplified");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void refreshStableAimAndWindowEffectsUseRealMobEffects(GameTestHelper helper) {
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ChefConsumeHandler handler = new ChefConsumeHandler();
        player.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 200, 0));
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 200, 0));
        ItemStack refresh = new ItemStack(Items.BREAD);
        ChefQualityNbt.stamp(refresh, ChefQuality.HIGH,
                List.of(new ChefEffectInstance(ChefEffectType.REFRESH, 3)));
        start(handler, player, refresh);
        finish(handler, player, refresh);
        helper.assertFalse(player.hasEffect(MobEffects.DIG_SLOWDOWN), "refresh clears mining fatigue");
        helper.assertTrue(player.hasEffect(MobEffects.MOVEMENT_SLOWDOWN),
                "refresh does not clear movement slowdown reserved for stable aim");

        ItemStack stable = new ItemStack(Items.BREAD);
        int stableMagnitude = ChefConfig.stableAimPerMille(ChefQuality.RADIANT);
        ChefQualityNbt.stamp(stable, ChefQuality.RADIANT,
                List.of(new ChefEffectInstance(ChefEffectType.STABLE_AIM, stableMagnitude)));
        start(handler, player, stable);
        finish(handler, player, stable);
        helper.assertFalse(player.hasEffect(MobEffects.MOVEMENT_SLOWDOWN),
                "stable aim clears movement slowdown");
        helper.assertTrue(ChefWindowEffectState.active(player, ChefEffectType.STABLE_AIM)
                        && ChefWindowEffectState.magnitudeOf(player, ChefEffectType.STABLE_AIM) == stableMagnitude,
                "stable aim duration and magnitude are stored in the real MobEffect instance");
        helper.assertTrue(ChefConfig.stableAimPerMille(ChefQuality.MEDIUM) == 500
                        && ChefConfig.stableAimPerMille(ChefQuality.RADIANT) == 1000,
                "stable aim knockback resistance stays 500 per-mille at medium and 1000 at radiant");
        LivingKnockBackEvent knockback = new LivingKnockBackEvent(player, 1.0F, 0.0D, 0.0D);
        new ChefAimHandler().onKnockback(knockback);
        helper.assertTrue(Math.abs(knockback.getStrength()) < 0.001F,
                "radiant stable aim cancels knockback, got " + knockback.getStrength());

        // 只验闪耀那一档时, "有稳膛就把击退归零" 的错实现与正确的比例公式给出同一个 0, 全绿而漏掉三档。
        // 中级 500 千分比必须精确减半, 才能证明结算走的是 strength * (1 - 千分比/1000) 而不是一刀切免疫。
        ServerPlayer mediumChef = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ChefWindowEffectState.stamp(mediumChef, ChefEffectType.STABLE_AIM, ChefQuality.MEDIUM,
                ChefConfig.stableAimWindowSeconds());
        LivingKnockBackEvent mediumKnockback = new LivingKnockBackEvent(mediumChef, 1.0F, 0.0D, 0.0D);
        new ChefAimHandler().onKnockback(mediumKnockback);
        helper.assertTrue(Math.abs(mediumKnockback.getStrength() - 0.5F) < 0.001F,
                "medium stable aim halves knockback instead of cancelling it, got "
                        + mediumKnockback.getStrength());

        // 设计规格红线: 抗击退只许在击退事件里缩放。挂 AttributeModifier 的实现登出/死亡不移除会永久泄漏,
        // 而它同样能让上面两条断言通过, 所以属性零修饰符必须单独钉死。
        for (ServerPlayer chef : List.of(player, mediumChef)) {
            AttributeInstance resistance = chef.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
            helper.assertTrue(resistance == null || resistance.getModifiers().isEmpty(),
                    "stable aim must never install a KNOCKBACK_RESISTANCE attribute modifier");
        }
        helper.succeed();
    }

    /**
     * 倒胃中毒时长、提神急速时长、膳香回血三条按品质分档的进食分支, 逐档端到端锁定。
     *
     * 期望值全部是手算常量 (中毒 8/6/4 秒, 急速 90/150/240/360/600 秒), 不回调 ChefConfig ——
     * 退回硬编码单一时长的实现会让除该档外的所有断言变红。
     *
     * 回血这一段刻意把最大血量抬到公服的 80: 原版 20 血下 "千分比 x 最大血量" 与 "千分比 / 10 的定值"
     * 只差一个系数, 用 20 血跑很容易把违反 %最大血量铁律的实现放过去。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dishPerQualityDurationsAndPercentHeal(GameTestHelper helper) {
        helper.assertTrue(ChefEffectMagnitude.nauseaSeconds(ChefQuality.LOW) == 8
                        && ChefEffectMagnitude.nauseaSeconds(ChefQuality.MEDIUM) == 6
                        && ChefEffectMagnitude.nauseaSeconds(ChefQuality.HIGH) == 4,
                "nausea poison lasts 8/6/4 seconds on low/medium/high");
        helper.assertTrue(ChefConfig.refreshSeconds(ChefQuality.LOW) == 90
                        && ChefConfig.refreshSeconds(ChefQuality.MEDIUM) == 150
                        && ChefConfig.refreshSeconds(ChefQuality.HIGH) == 240
                        && ChefConfig.refreshSeconds(ChefQuality.EXTRAORDINARY) == 360
                        && ChefConfig.refreshSeconds(ChefQuality.RADIANT) == 600,
                "refresh haste lasts 90/150/240/360/600 seconds across the five tiers");

        // 倒胃: 低=毒II, 中/高=毒I (magnitude 存 amplifier+1); 时长按档分别是 160/120/80 tick。
        assertNauseaDish(helper, ChefQuality.LOW, 2, 160);
        assertNauseaDish(helper, ChefQuality.MEDIUM, 1, 120);
        assertNauseaDish(helper, ChefQuality.HIGH, 1, 80);

        // 提神: 急速等级 = tier+1, 时长按档分别是 1800/3000/4800/7200/12000 tick。
        assertRefreshDish(helper, ChefQuality.LOW, 1, 1800);
        assertRefreshDish(helper, ChefQuality.MEDIUM, 2, 3000);
        assertRefreshDish(helper, ChefQuality.HIGH, 3, 4800);
        assertRefreshDish(helper, ChefQuality.EXTRAORDINARY, 4, 7200);
        assertRefreshDish(helper, ChefQuality.RADIANT, 5, 12000);

        // 膳香: 80 血玩家半血 (40) 吃一道高品质膳香菜, 回 7.5% 最大血量 = 6.0, 结果 46.0。
        ServerPlayer eater = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setMaxHealth(eater, 80.0D);
        eater.setHealth(40.0F);
        ChefConsumeHandler handler = new ChefConsumeHandler();
        ItemStack nourish = new ItemStack(Items.BREAD);
        ChefQualityNbt.stamp(nourish, ChefQuality.HIGH,
                List.of(new ChefEffectInstance(ChefEffectType.NOURISH_HEAL, 75)));
        start(handler, eater, nourish);
        finish(handler, eater, nourish);
        helper.assertTrue(Math.abs(eater.getHealth() - 46.0F) < 0.01F,
                "high nourish heals 7.5 percent of an 80 HP pool (40 -> 46), got " + eater.getHealth());

        eater.setHealth(80.0F);
        start(handler, eater, nourish);
        finish(handler, eater, nourish);
        helper.assertTrue(Math.abs(eater.getHealth() - 80.0F) < 0.01F,
                "nourish never heals past max health, got " + eater.getHealth());
        helper.succeed();
    }

    /**
     * 披甲的记账、伤害核销、真存档往返与到期回收。
     *
     * 最大血量刻意抬到公服的 80: 闪耀披甲 80 千分比 = 6.4 点黄心, 全部期望值由此手算, 不回调 ChefConfig。
     * 「往返」是真往返 —— 走一次 {@code saveWithoutId} / {@code load} 的 CompoundTag, 中途把活对象上的
     * 效果、吸收值和 ForgeData 全部抹掉。只连着调两个登录/登出回调不算持久化验证: 前后是同一个活着的
     * ServerPlayer, MobEffectInstance 与 getPersistentData() 根本没经过序列化。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shieldTracksOnlyChefOwnedAbsorptionAcrossSaveLoadAndExpiry(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ChefWindowEffectState state = new ChefWindowEffectState();
        setMaxHealth(player, 80.0D);
        helper.assertTrue(ChefConfig.shieldPerMille(ChefQuality.RADIANT) == 80,
                "radiant shield stays 80 per-mille of max health (6.4 hearts on an 80 HP pool)");

        player.setAbsorptionAmount(1.0F);
        ChefWindowEffectState.stampShield(player, 80, ChefConfig.shieldWindowSeconds());
        helper.assertTrue(Math.abs(player.getAbsorptionAmount() - 6.4F) < 0.01F
                        && Math.abs(ChefWindowEffectState.shieldRemaining(player) - 5.4F) < 0.01F,
                "shield tops absorption up to its 6.4 target and owns only the 5.4 it actually added, got "
                        + player.getAbsorptionAmount() + "/" + ChefWindowEffectState.shieldRemaining(player));

        // 挨打两点: 只有伤害路径才核销份额 (5.4 - 2.0 = 3.4)。
        player.setAbsorptionAmount(4.4F);
        state.onAbsorptionDamage(new LivingDamageEvent(player, player.damageSources().generic(), 2.0F));
        helper.assertTrue(Math.abs(ChefWindowEffectState.shieldRemaining(player) - 3.4F) < 0.01F,
                "absorption damage burns the chef-owned share first, got "
                        + ChefWindowEffectState.shieldRemaining(player));

        CompoundTag saved = player.saveWithoutId(new CompoundTag());
        player.removeAllEffects();
        player.setAbsorptionAmount(0.0F);
        clearPersistentData(player);
        helper.assertFalse(ChefWindowEffectState.active(player, ChefEffectType.SHIELD),
                "precondition: the live object must hold no shield state before the reload");
        helper.assertTrue(ChefWindowEffectState.shieldRemaining(player) == 0.0F,
                "precondition: the ownership ledger must be wiped before the reload");
        player.load(saved);
        helper.assertTrue(ChefWindowEffectState.active(player, ChefEffectType.SHIELD)
                        && Math.abs(ChefWindowEffectState.shieldRemaining(player) - 3.4F) < 0.01F
                        && Math.abs(player.getAbsorptionAmount() - 4.4F) < 0.01F,
                "effect, absorption and shield ownership all survive a real CompoundTag round trip, got "
                        + player.getAbsorptionAmount() + "/" + ChefWindowEffectState.shieldRemaining(player));

        // 再收一份外来黄心 (4.0), 到期只许回收厨师自己那 3.4, 剩 1.0 + 4.0 = 5.0 外来份额。
        player.setAbsorptionAmount(player.getAbsorptionAmount() + 4.0F);
        state.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
        player.removeEffect(ChefWindowEffectState.effectFor(ChefEffectType.SHIELD));
        state.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
        helper.assertTrue(Math.abs(player.getAbsorptionAmount() - 5.0F) < 0.01F,
                "expiry reclaims only the remaining 3.4 chef share and preserves 5.0 foreign absorption, got "
                        + player.getAbsorptionAmount());
        helper.assertTrue(ChefWindowEffectState.shieldRemaining(player) == 0.0F,
                "expired shield ownership is removed from persistent player data");
        helper.succeed();
    }

    /** 同一档在窗口内连盖两次是刷新不是叠加: 授予额取 max 而不是累加, 否则两道闪耀披甲菜白嫖双份黄心。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shieldRefreshSameTierDoesNotDoubleGrant(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ChefWindowEffectState state = new ChefWindowEffectState();
        setMaxHealth(player, 80.0D);
        player.setAbsorptionAmount(0.0F);

        ChefWindowEffectState.stampShield(player, 80, ChefConfig.shieldWindowSeconds());
        ChefWindowEffectState.stampShield(player, 80, ChefConfig.shieldWindowSeconds());
        helper.assertTrue(Math.abs(player.getAbsorptionAmount() - 6.4F) < 0.01F,
                "a same-tier refresh keeps the single 6.4 share instead of stacking to 12.8, got "
                        + player.getAbsorptionAmount());
        helper.assertTrue(Math.abs(ChefWindowEffectState.shieldRemaining(player) - 6.4F) < 0.01F,
                "the ledger also records a single share, got " + ChefWindowEffectState.shieldRemaining(player));

        player.removeEffect(ChefWindowEffectState.effectFor(ChefEffectType.SHIELD));
        state.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
        helper.assertTrue(Math.abs(player.getAbsorptionAmount()) < 0.01F,
                "the double-stamped share is still fully reclaimed on expiry, got "
                        + player.getAbsorptionAmount());
        helper.succeed();
    }

    /** 外来黄心已高于本档目标时厨师这份不得再抬高, 到期也不许回收别人的黄心 (附魔金苹果 ABSORPTION IV 场景)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shieldDoesNotRaiseWhenForeignAbsorptionAlreadyExceedsOwnTarget(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ChefWindowEffectState state = new ChefWindowEffectState();
        setMaxHealth(player, 80.0D);
        // 16.4 远高于闪耀披甲的 6.4 目标。
        player.setAbsorptionAmount(16.4F);

        ChefWindowEffectState.stampShield(player, 80, ChefConfig.shieldWindowSeconds());
        helper.assertTrue(Math.abs(player.getAbsorptionAmount() - 16.4F) < 0.01F,
                "chef shield never adds on top of higher foreign absorption, got "
                        + player.getAbsorptionAmount());
        helper.assertTrue(ChefWindowEffectState.shieldRemaining(player) == 0.0F,
                "nothing was added, so the chef owns nothing, got "
                        + ChefWindowEffectState.shieldRemaining(player));

        player.removeEffect(ChefWindowEffectState.effectFor(ChefEffectType.SHIELD));
        state.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
        helper.assertTrue(Math.abs(player.getAbsorptionAmount() - 16.4F) < 0.01F,
                "expiry reclaims nothing and leaves the foreign 16.4 untouched, got "
                        + player.getAbsorptionAmount());
        helper.succeed();
    }

    /** 换维度语义已改为只对齐账本、不回收: 黄心与所有权都必须原封不动地跟着玩家过去。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shieldSurvivesChangedDimensionWithoutReclaim(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ChefWindowEffectState state = new ChefWindowEffectState();
        setMaxHealth(player, 80.0D);
        player.setAbsorptionAmount(0.0F);
        ChefWindowEffectState.stampShield(player, 80, ChefConfig.shieldWindowSeconds());

        state.onChangedDimension(new PlayerEvent.PlayerChangedDimensionEvent(
                player, Level.OVERWORLD, Level.NETHER));
        helper.assertTrue(ChefWindowEffectState.active(player, ChefEffectType.SHIELD)
                        && Math.abs(player.getAbsorptionAmount() - 6.4F) < 0.01F
                        && Math.abs(ChefWindowEffectState.shieldRemaining(player) - 6.4F) < 0.01F,
                "changing dimension keeps both the yellow hearts and their ownership, got "
                        + player.getAbsorptionAmount() + "/" + ChefWindowEffectState.shieldRemaining(player));
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void twoBlockTableKeepsSingleAuthoritativeBlockEntity(GameTestHelper helper) {
        BlockPos primaryPos = helper.absolutePos(TABLE_RELATIVE);
        BlockState primary = ChefBlocks.SEASONING_TABLE_RADIANT.get().defaultBlockState()
                .setValue(SeasoningTableBlock.FACING, Direction.NORTH)
                .setValue(SeasoningTableBlock.SECONDARY, false);
        BlockPos secondaryPos = primaryPos.relative(Direction.EAST);
        helper.getLevel().setBlock(primaryPos, primary, Block.UPDATE_CLIENTS);
        ((SeasoningTableBlock) primary.getBlock()).setPlacedBy(
                helper.getLevel(), primaryPos, primary, null, ItemStack.EMPTY);

        helper.assertTrue(helper.getLevel().getBlockState(primaryPos).is(primary.getBlock())
                        && !helper.getLevel().getBlockState(primaryPos).getValue(SeasoningTableBlock.SECONDARY),
                "two-block table keeps the left half as its authoritative primary");
        helper.assertTrue(helper.getLevel().getBlockState(secondaryPos).is(primary.getBlock())
                        && helper.getLevel().getBlockState(secondaryPos).getValue(SeasoningTableBlock.SECONDARY),
                "two-block table creates a matching right half");
        helper.assertTrue(helper.getLevel().getBlockEntity(primaryPos) instanceof SeasoningTableBlockEntity
                        && helper.getLevel().getBlockEntity(secondaryPos) == null,
                "only the primary half owns inventory and cooking state");

        helper.getLevel().setBlock(secondaryPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(helper.getLevel().getBlockState(primaryPos).isAir(),
                "removing either half removes the whole table without leaving an orphan");

        BlockState migratedPrimary = ((SeasoningTableBlock) primary.getBlock()).updateShape(
                primary, Direction.EAST, Blocks.AIR.defaultBlockState(),
                helper.getLevel(), primaryPos, secondaryPos);
        helper.assertTrue(migratedPrimary.is(primary.getBlock())
                        && !migratedPrimary.getValue(SeasoningTableBlock.SECONDARY),
                "legacy one-block tables survive neighbor updates until their second half can be restored");
        helper.succeed();
    }

    /**
     * 双格台的战利品路径。上一条用例用 setBlock(AIR) 拆台, 那条路根本不经过 dropResources, 于是"到底掉了
     * 几个调味台"和"库存里的菜掉没掉出来"两件最容易出刷方块/吞物资的事一直零覆盖。这里走真正的
     * {@code destroyBlock(pos, true)}。
     *
     * 掉落数一律取本用例内的增量: GameTest 复用 run/world 存档, 上一轮的掉落物会跨轮堆在同一片区域。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 400)
    public static void breakingEitherHalfDropsOneTableAndSpillsInventory(GameTestHelper helper) {
        Item tableItem = ChefItems.SEASONING_TABLE_RADIANT.get();

        // 第一段: 玩家敲掉右半 (副格)。副格自己掉一个台, 被静默移除的主格不得再掉第二个。
        TableFixture idle = tableFixture(helper);
        idle.table.inputSlots().setStackInSlot(SeasoningMenu.SLOT_INPUT, new ItemStack(Items.BREAD, 3));
        idle.table.inputSlots().setStackInSlot(SeasoningMenu.SLOT_SEASONING, new ItemStack(Items.SUGAR, 2));
        BlockPos idleSecondary = idle.absolute.relative(Direction.EAST);
        AABB idleArea = dropArea(idle.absolute, idleSecondary);
        int tablesBefore = countDroppedItems(helper, idleArea, tableItem);
        int breadBefore = countDroppedItems(helper, idleArea, Items.BREAD);
        int sugarBefore = countDroppedItems(helper, idleArea, Items.SUGAR);

        helper.assertTrue(helper.getLevel().destroyBlock(idleSecondary, true),
                "breaking the right half of the table succeeds");
        helper.assertTrue(helper.getLevel().getBlockState(idle.absolute).isAir()
                        && helper.getLevel().getBlockState(idleSecondary).isAir(),
                "breaking one half leaves neither half nor an ACTIVE blockstate behind");
        helper.assertTrue(countDroppedItems(helper, idleArea, tableItem) - tablesBefore == 1,
                "a two-block table drops exactly one seasoning table item, got "
                        + (countDroppedItems(helper, idleArea, tableItem) - tablesBefore));
        helper.assertTrue(countDroppedItems(helper, idleArea, Items.BREAD) - breadBefore == 3
                        && countDroppedItems(helper, idleArea, Items.SUGAR) - sugarBefore == 2,
                "the primary half's stocked input and seasoning slots spill out in full");
        helper.assertTrue(idle.table.inputSlots().getStackInSlot(SeasoningMenu.SLOT_INPUT).isEmpty()
                        && idle.table.inputSlots().getStackInSlot(SeasoningMenu.SLOT_SEASONING).isEmpty(),
                "spilled slots are cleared so a re-placed table cannot duplicate them");

        // 第二段: 破坏正在调味的主格。事务必须先取消, 材料才掉。
        TableFixture cooking = tableFixture(helper);
        cooking.table.inputSlots().setStackInSlot(SeasoningMenu.SLOT_INPUT, new ItemStack(Items.BREAD));
        cooking.table.inputSlots().setStackInSlot(SeasoningMenu.SLOT_SEASONING, new ItemStack(Items.SUGAR));
        helper.assertTrue(cooking.table.startCooking(cooking.player, ChefQuality.LOW.tier()),
                "a stocked table starts its low-quality challenge");
        helper.assertTrue(cooking.table.isActive(), "precondition: the table is mid-transaction");
        BlockPos cookingSecondary = cooking.absolute.relative(Direction.EAST);
        AABB cookingArea = dropArea(cooking.absolute, cookingSecondary);
        int cookingTablesBefore = countDroppedItems(helper, cookingArea, tableItem);
        int cookingBreadBefore = countDroppedItems(helper, cookingArea, Items.BREAD);

        helper.assertTrue(helper.getLevel().destroyBlock(cooking.absolute, true),
                "breaking the left half of an active table succeeds");
        helper.assertFalse(cooking.table.isActive(),
                "breaking the table cancels the transaction in progress");
        helper.assertTrue(helper.getLevel().getBlockState(cooking.absolute).isAir()
                        && helper.getLevel().getBlockState(cookingSecondary).isAir(),
                "no half of the broken active table survives");
        helper.assertTrue(countDroppedItems(helper, cookingArea, tableItem) - cookingTablesBefore == 1,
                "breaking the primary half also drops exactly one table item, got "
                        + (countDroppedItems(helper, cookingArea, tableItem) - cookingTablesBefore));
        helper.assertTrue(countDroppedItems(helper, cookingArea, Items.BREAD) - cookingBreadBefore == 1,
                "the locked input of a cancelled transaction is returned to the world, not swallowed");
        helper.succeed();
    }

    /**
     * 两条目标品质的平衡不变量, 期望值全是手算常量。
     *
     * 一、瞄高必须付出代价。同一次操作下, 「瞄闪耀最后拿到超凡及以上」的概率必须严格低于「直接瞄超凡」。
     * 没有下探衰减时 P(成品 &gt;= Q) 只由 Q 决定、与所选目标无关, 于是永远该点最高目标, 中/高/超凡三个
     * 目标退化成死选项。
     *
     * 二、台档必须严格有用。固定控火精度与「全部命中」, 台档 tier 0..4 的达成率必须逐级上升。QTE 加成改按
     * 命中率折算之前, 低档台补派的 cue 是净收益, 这条会倒挂 (低档台反而更容易出闪耀)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void aimingHigherCostsAndBetterTablesHelpMonotonically(GameTestHelper helper) {
        // 满控火 + 六个 cue 全中 + 满级 + 闪耀台下的逐档达成率: 低/中/高 都被 1000 封顶, 超凡 780, 闪耀 540。
        // 目标 = 闪耀时, 超凡那一档要再乘一次 0.9 衰减 -> round(780 x 0.9) = 702, 于是 "超凡及以上" 只剩 702 点。
        int extraordinaryOrBetterAimingRadiant = countRollsAtLeast(
                ChefQuality.RADIANT, ChefQuality.EXTRAORDINARY);
        int extraordinaryOrBetterAimingExtraordinary = countRollsAtLeast(
                ChefQuality.EXTRAORDINARY, ChefQuality.EXTRAORDINARY);
        helper.assertTrue(extraordinaryOrBetterAimingRadiant == 702
                        && extraordinaryOrBetterAimingExtraordinary == 780,
                "P(成品>=超凡) 应为 702/1000 (瞄闪耀) 与 780/1000 (瞄超凡), 实得 "
                        + extraordinaryOrBetterAimingRadiant + "/"
                        + extraordinaryOrBetterAimingExtraordinary);
        helper.assertTrue(extraordinaryOrBetterAimingRadiant < extraordinaryOrBetterAimingExtraordinary,
                "瞄更高必须让同一档更难拿到, 否则中间三档目标是永远不该点的死选项");

        // 台档单调: 目标闪耀, 控火精度固定 0.2 (= +100), 每档台子按自己那份 cue 数全部命中 (= +500), 满级。
        // 表现分 700 -> x450 难度 = 315 -> x1000 等级 = 315 -> 台档倍率 1000/1050/1100/1150/1200。
        int[] expectedByTableTier = {315, 331, 347, 362, 378};
        int[] actualByTableTier = new int[ChefQuality.values().length];
        for (ChefQuality table : ChefQuality.values()) {
            int cues = ChefQteTiming.cueCountFor(ChefQuality.RADIANT, 10, table);
            actualByTableTier[table.tier()] = ChefQualityResolver.successChancePerMille(
                    ChefQuality.RADIANT, 0.2D, cues, cues, 10, table);
        }
        for (int tier = 0; tier < expectedByTableTier.length; tier++) {
            helper.assertTrue(actualByTableTier[tier] == expectedByTableTier[tier],
                    "tier " + tier + " 的调味台应给出 " + expectedByTableTier[tier] + " 千分比, 实得 "
                            + actualByTableTier[tier]);
            helper.assertTrue(tier == 0 || actualByTableTier[tier] > actualByTableTier[tier - 1],
                    "台档必须严格有用: tier " + tier + " 的达成率不比 tier " + (tier - 1) + " 高");
        }

        // 低档台多派的 cue 不许变成净收益: 12 个 cue 全中与 6 个 cue 全中的表现分相同 (都是命中率 1.0),
        // 于是低档台仍旧只拿到 450, 输给闪耀台的 540。
        helper.assertTrue(ChefQualityResolver.successChancePerMille(
                        ChefQuality.RADIANT, 1.0D, 12, 12, 10, ChefQuality.LOW) == 450
                        && ChefQualityResolver.successChancePerMille(
                        ChefQuality.RADIANT, 1.0D, 6, 6, 10, ChefQuality.RADIANT) == 540,
                "QTE 加成按命中率封顶, 低档台补派的 cue 换不来更高达成率");
        helper.succeed();
    }

    /**
     * 披甲的另一半所有权语义: 外来黄心自然到期不是伤害。
     *
     * 附魔金苹果那类 ABSORPTION 到期时, 原版在 removeAttributeModifiers 里直接扣 4x(等级+1) 点吸收。把这次
     * 下降也记成"厨师份额被打掉", 窗口到期时就会回收不足, 差额变成没有任何 MobEffect 支撑、只能被伤害打掉的
     * 永久黄心 —— 反复吃披甲菜即白嫖有效血量。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shieldExpiryLeavesNoOrphanAbsorptionAfterForeignHeartsRunOut(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ChefWindowEffectState state = new ChefWindowEffectState();
        setMaxHealth(player, 80.0D);
        player.setAbsorptionAmount(0.0F);

        ChefWindowEffectState.stampShield(player, 80, ChefConfig.shieldWindowSeconds());
        helper.assertTrue(Math.abs(player.getAbsorptionAmount() - 6.4F) < 0.01F,
                "precondition: 闪耀披甲在 80 血玩家身上是 6.4 点黄心, 实得 " + player.getAbsorptionAmount());

        // 外来吸收 I: 原版 addAttributeModifiers 直接 +4 点 -> 10.4。
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 0));
        helper.assertTrue(Math.abs(player.getAbsorptionAmount() - 10.4F) < 0.01F,
                "precondition: 外来吸收 I 叠上来应是 10.4, 实得 " + player.getAbsorptionAmount());
        state.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));

        // 外来吸收到期 (原版 -4)。全程没有任何伤害事件, 所以厨师那 6.4 份额一点都不许被核销。
        player.removeEffect(MobEffects.ABSORPTION);
        state.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
        helper.assertTrue(Math.abs(ChefWindowEffectState.shieldRemaining(player) - 6.4F) < 0.01F,
                "外来黄心自然到期不是伤害, 厨师份额必须原样留着 6.4, 实得 "
                        + ChefWindowEffectState.shieldRemaining(player));

        // 厨师窗口到期: 6.4 全额回收, 不许剩下孤儿黄心。
        player.removeEffect(ChefWindowEffectState.effectFor(ChefEffectType.SHIELD));
        state.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
        helper.assertTrue(player.getAbsorptionAmount() < 0.01F,
                "披甲到期后黄心必须回到 0, 实得 " + player.getAbsorptionAmount());
        helper.assertTrue(ChefWindowEffectState.shieldRemaining(player) == 0.0F,
                "回收后所有权账本也必须清空, 实得 " + ChefWindowEffectState.shieldRemaining(player));
        helper.succeed();
    }

    /**
     * 失败品回滚只许撤销本菜写下的效果, 不许顺手删掉进食途中由第三方新加的同名增益。
     *
     * 金苹果的 FoodProperties 声明 REGENERATION 100 tick; 队友的喷溅再生药水在这一口的 32 tick 里落下
     * 600 tick。两者只能靠"剩余时长是否超过本菜声明值"区分 —— 拿掉那道闸门, 失败品就成了偷队友增益的工具。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void spoiledDishKeepsAThirdPartyBuffGrantedWhileEating(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ChefConsumeHandler handler = new ChefConsumeHandler();
        ItemStack dish = new ItemStack(Items.GOLDEN_APPLE);
        ChefQualityNbt.stamp(dish, ChefQuality.LOW,
                List.of(new ChefEffectInstance(ChefEffectType.SPOILED, 0)));
        player.getFoodData().setFoodLevel(12);
        player.getFoodData().setSaturation(3.0F);
        helper.assertFalse(player.hasEffect(MobEffects.REGENERATION),
                "precondition: 进食前身上没有再生");

        start(handler, player, dish);
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 600, 0));
        player.getFoodData().setFoodLevel(16);
        player.getFoodData().setSaturation(9.0F);
        finish(handler, player, dish);

        MobEffectInstance regeneration = player.getEffect(MobEffects.REGENERATION);
        helper.assertTrue(regeneration != null && regeneration.getDuration() == 600
                        && regeneration.getAmplifier() == 0,
                "第三方在进食途中给的再生 (600 tick, 长于金苹果自己声明的 100) 必须原样留下, 实得 "
                        + (regeneration == null ? "已被删除"
                        : regeneration.getDuration() + "/" + regeneration.getAmplifier()));
        // 同一次回滚确实执行了 (否则上面那条只是"什么都没发生"的空断言)。
        helper.assertTrue(player.getFoodData().getFoodLevel() == 12
                        && Math.abs(player.getFoodData().getSaturationLevel() - 3.0F) < 0.001F,
                "失败品仍必须把饱食/饱和回滚到 12/3.0, 实得 " + player.getFoodData().getFoodLevel()
                        + "/" + player.getFoodData().getSaturationLevel());
        helper.succeed();
    }

    /**
     * 一口菜的事务生命周期: 只有活着的事务才允许结算, 中途放弃的必须被清干净。
     *
     * 两条放弃路径都要覆盖: 松手 (发 Stop 事件) 与换快捷栏槽位 (1.20.1 走 stopUsingItem, <b>不发</b> Stop
     * 事件, 只能靠 LivingTickEvent 兜底)。第三段是反面对照 —— 同一条 Finish 在事务还活着时必定回滚, 证明
     * 前两段的"什么都没发生"确实来自清理而不是 Finish 本身失能。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void abandonedEatingTransactionIsClearedByStopAndByTheTickFallback(GameTestHelper helper) {
        ChefConsumeHandler handler = new ChefConsumeHandler();

        ServerPlayer released = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ItemStack releasedDish = spoiledGoldenApple();
        released.getFoodData().setFoodLevel(6);
        released.getFoodData().setSaturation(1.0F);
        start(handler, released, releasedDish);
        handler.onStopEating(new LivingEntityUseItemEvent.Stop(released, releasedDish, 12));
        released.getFoodData().setFoodLevel(20);
        released.getFoodData().setSaturation(20.0F);
        finish(handler, released, releasedDish);
        helper.assertTrue(released.getFoodData().getFoodLevel() == 20
                        && Math.abs(released.getFoodData().getSaturationLevel() - 20.0F) < 0.001F,
                "松手放弃后的 Finish 不得再结算 (饱食应停在 20/20), 实得 "
                        + released.getFoodData().getFoodLevel() + "/"
                        + released.getFoodData().getSaturationLevel());

        ServerPlayer switched = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ItemStack switchedDish = spoiledGoldenApple();
        switched.getFoodData().setFoodLevel(6);
        switched.getFoodData().setSaturation(1.0F);
        start(handler, switched, switchedDish);
        // 换槽位不发 Stop, 玩家只是不再 isUsingItem(); 兜底清理必须在下一 tick 就把残留事务扔掉。
        handler.onLivingTick(new LivingEvent.LivingTickEvent(switched));
        switched.getFoodData().setFoodLevel(20);
        switched.getFoodData().setSaturation(20.0F);
        finish(handler, switched, switchedDish);
        helper.assertTrue(switched.getFoodData().getFoodLevel() == 20
                        && Math.abs(switched.getFoodData().getSaturationLevel() - 20.0F) < 0.001F,
                "tick 兜底清掉的残留事务不得再结算 (饱食应停在 20/20), 实得 "
                        + switched.getFoodData().getFoodLevel() + "/"
                        + switched.getFoodData().getSaturationLevel());

        ServerPlayer control = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ItemStack controlDish = spoiledGoldenApple();
        control.getFoodData().setFoodLevel(6);
        control.getFoodData().setSaturation(1.0F);
        start(handler, control, controlDish);
        control.getFoodData().setFoodLevel(20);
        control.getFoodData().setSaturation(20.0F);
        finish(handler, control, controlDish);
        helper.assertTrue(control.getFoodData().getFoodLevel() == 6
                        && Math.abs(control.getFoodData().getSaturationLevel() - 1.0F) < 0.001F,
                "对照组: 事务还活着时同一条 Finish 必定把饱食回滚到 6/1.0, 实得 "
                        + control.getFoodData().getFoodLevel() + "/"
                        + control.getFoodData().getSaturationLevel());
        helper.succeed();
    }

    /**
     * 调味台的三条状态机红线, 一次跑完两轮。
     *
     * 1. 结算态不是终点: 第一轮做完后台子停在 PHASE_DONE, 第二次 startCooking 必须照常受理并再出一份盖章菜
     *    (曾经的实现只能靠结算态自动超时复位, 中间那段时间台子等于报废);
     * 2. 火候阶段两个容器槽都必须 pickup 锁死 (锁在 HEAT 就锁, 不是等到 SEASON);
     * 3. 旁观者关自己的界面不得取消他人事务 —— 第二个人右键看一眼再按 Esc 就打掉别人的火候与命中, 是最容易
     *    被当成"随机丢进度"投诉的一类 bug。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH,
            timeoutTicks = 600)
    public static void seasoningTableRunsASecondRoundAndIgnoresBystanders(GameTestHelper helper) {
        TableFixture fixture = tableFixture(helper);
        fixture.table.inputSlots().setStackInSlot(SeasoningMenu.SLOT_INPUT, new ItemStack(Items.BREAD, 2));
        fixture.table.inputSlots().setStackInSlot(SeasoningMenu.SLOT_SEASONING, new ItemStack(Items.SUGAR, 2));
        EconomyServices.economyService().grant(fixture.player, Currency.CREDIT,
                ChefConfig.seasoningCreditCost() * 2L);

        helper.assertTrue(fixture.table.startCooking(fixture.player, ChefQuality.LOW.tier()),
                "第一轮低品质挑战开工");
        helper.assertTrue(fixture.menu.phase() == SeasoningMenu.PHASE_HEAT,
                "开工后立刻进入火候阶段, 实得 phase=" + fixture.menu.phase());
        helper.assertFalse(fixture.menu.getSlot(SeasoningMenu.SLOT_INPUT).mayPickup(fixture.player),
                "火候阶段输入槽必须 pickup 锁死");
        helper.assertFalse(fixture.menu.getSlot(SeasoningMenu.SLOT_SEASONING).mayPickup(fixture.player),
                "火候阶段调料槽必须 pickup 锁死");

        drivePerfectHeat(helper, fixture);
        int guard = 0;
        while (fixture.menu.hits() == 0 && guard++ < 2000) {
            if (fixture.menu.cueActive()) {
                helper.assertTrue(fixture.table.hitSeason(fixture.player, fixture.menu.targetIndex()),
                        "第一个时机点接受操作者的输入");
            } else {
                fixture.table.serverTick();
            }
        }
        helper.assertTrue(fixture.menu.hits() == 1 && fixture.table.isActive(),
                "precondition: 事务应停在调味阶段且已有 1 次命中, 实得 hits=" + fixture.menu.hits());

        ServerPlayer bystander = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        bystander.setPos(fixture.absolute.getX() + 0.5D, fixture.absolute.getY() + 0.5D,
                fixture.absolute.getZ() + 0.5D);
        SeasoningMenu bystanderMenu = new SeasoningMenu(7, bystander.getInventory(), fixture.table);
        bystander.containerMenu = bystanderMenu;
        bystanderMenu.removed(bystander);
        helper.assertTrue(fixture.table.isActive() && fixture.menu.hits() == 1,
                "旁观者关界面不得取消他人事务, 实得 active=" + fixture.table.isActive()
                        + " hits=" + fixture.menu.hits());

        driveAllQteHits(helper, fixture);
        helper.assertTrue(countStampedDishes(fixture.player) == 1,
                "第一轮产出恰好一份盖章菜, 实得 " + countStampedDishes(fixture.player));
        helper.assertTrue(fixture.menu.phase() == SeasoningMenu.PHASE_DONE,
                "第一轮结束后台子停在结算态, 实得 phase=" + fixture.menu.phase());

        helper.assertTrue(fixture.table.startCooking(fixture.player, ChefQuality.LOW.tier()),
                "结算态必须能被下一次 startCooking 直接复位, 否则调味台做完第一道菜就报废");
        drivePerfectHeat(helper, fixture);
        driveAllQteHits(helper, fixture);
        helper.assertTrue(countStampedDishes(fixture.player) == 2,
                "第二轮同样产出一份盖章菜 (累计 2), 实得 " + countStampedDishes(fixture.player));
        helper.assertTrue(fixture.table.inputSlots().getStackInSlot(SeasoningMenu.SLOT_INPUT).isEmpty()
                        && fixture.table.inputSlots().getStackInSlot(SeasoningMenu.SLOT_SEASONING).isEmpty(),
                "两轮各消耗一份原料与一份调料, 两个槽应被吃空");
        helper.succeed();
    }

    /**
     * 目标品质预览必须跟着厨师等级实时重算, 而不是开界面时算死。
     *
     * 不关面板连做几道菜就能升级 (ChefXpHandler.award 是即时结算), 服务端从下一次开工起已按新等级出题;
     * 预览若停在开界面那一刻, 玩家看到的达成率与 cue 数会与真正跑的挑战对不上。
     *
     * 期望值手算 (闪耀台, 完美操作口径): L1 闪耀 = 4 基础 + 4 品质 + 4 生手 - 2 台档减免 = 10 个 cue,
     * 达成率 1000 表现分 x450 难度 x100 等级 x1200 台档 = 54; L10 闪耀 = 6 个 cue / 540。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void targetPreviewFollowsChefLevelWhileTheMenuStaysOpen(GameTestHelper helper) {
        TableFixture fixture = tableFixture(helper);
        helper.assertTrue(fixture.menu.targetPreviewQteCount(ChefQuality.RADIANT) == 10
                        && fixture.menu.targetPreviewChancePerMille(ChefQuality.RADIANT) == 54,
                "L1 厨师的闪耀预览应是 10 个 cue / 千分之 54, 实得 "
                        + fixture.menu.targetPreviewQteCount(ChefQuality.RADIANT) + "/"
                        + fixture.menu.targetPreviewChancePerMille(ChefQuality.RADIANT));

        setChefLevel(fixture.player, 10);
        fixture.menu.broadcastChanges();
        helper.assertTrue(fixture.menu.targetPreviewQteCount(ChefQuality.RADIANT) == 6
                        && fixture.menu.targetPreviewChancePerMille(ChefQuality.RADIANT) == 540,
                "升到 10 级后闪耀预览必须刷新成 6 个 cue / 千分之 540, 实得 "
                        + fixture.menu.targetPreviewQteCount(ChefQuality.RADIANT) + "/"
                        + fixture.menu.targetPreviewChancePerMille(ChefQuality.RADIANT));
        // 低品质在闪耀台上恒为 2 个 cue / 千分之 1000, 等级动它不动 —— 证明刷新不是把整排拍成同一个数。
        helper.assertTrue(fixture.menu.targetPreviewQteCount(ChefQuality.LOW) == 2
                        && fixture.menu.targetPreviewChancePerMille(ChefQuality.LOW) == 1000,
                "低品质预览不随等级变化, 应恒为 2 个 cue / 千分之 1000, 实得 "
                        + fixture.menu.targetPreviewQteCount(ChefQuality.LOW) + "/"
                        + fixture.menu.targetPreviewChancePerMille(ChefQuality.LOW));
        helper.succeed();
    }

    /**
     * 耐饥与饱腹这两个窗口的真实结算 (ChefHungerHandler)。
     *
     * 回补基准 1.0 饱和是 ChefHungerHandler 的私有常量, 期望值只能照抄成字面量 —— 改动那个常量必须同步改
     * 本用例, 这正是"数值有人看守"的意思。周期 40 tick 同理: 少了周期闸门就变成每 tick 补一次饱和,
     * 净效果是饥饿条永不下降。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void hungerWindowsRefillSaturationOnTheirOwnCadence(GameTestHelper helper) {
        ChefHungerHandler hunger = new ChefHungerHandler();

        ServerPlayer sated = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ChefWindowEffectState.stamp(sated, ChefEffectType.SATIATION, ChefQuality.RADIANT,
                ChefConfig.satiationSeconds(ChefQuality.RADIANT));
        sated.getFoodData().setFoodLevel(20);
        sated.getFoodData().setSaturation(0.0F);
        sated.addEffect(new MobEffectInstance(MobEffects.HUNGER, 200, 0));
        sated.tickCount = 40;
        hunger.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, sated));
        helper.assertTrue(Math.abs(sated.getFoodData().getSaturationLevel() - 1.0F) < 0.001F,
                "饱腹每周期整份回补 1.0 饱和, 实得 " + sated.getFoodData().getSaturationLevel());
        helper.assertFalse(sated.hasEffect(MobEffects.HUNGER),
                "饱腹窗口内必须清掉原版饥饿 debuff");

        sated.tickCount = 41;
        hunger.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, sated));
        helper.assertTrue(Math.abs(sated.getFoodData().getSaturationLevel() - 1.0F) < 0.001F,
                "周期外的 tick 不得回补 (否则饥饿条永不下降), 实得 "
                        + sated.getFoodData().getSaturationLevel());

        ServerPlayer enduring = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        helper.assertTrue(ChefConfig.endurancePctPerMille(ChefQuality.RADIANT) == 900,
                "闪耀耐饥的减衰减比例是千分之 900, 实得 "
                        + ChefConfig.endurancePctPerMille(ChefQuality.RADIANT));
        ChefWindowEffectState.stamp(enduring, ChefEffectType.ENDURANCE, ChefQuality.RADIANT,
                ChefConfig.enduranceSeconds(ChefQuality.RADIANT));
        enduring.getFoodData().setFoodLevel(20);
        enduring.getFoodData().setSaturation(0.0F);
        enduring.tickCount = 40;
        hunger.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, enduring));
        helper.assertTrue(Math.abs(enduring.getFoodData().getSaturationLevel() - 0.9F) < 0.001F,
                "闪耀耐饥每周期回补 1.0 x 900/1000 = 0.9 饱和, 实得 "
                        + enduring.getFoodData().getSaturationLevel());
        helper.succeed();
    }

    /**
     * 三个靠原版效果落地的窗口: 潜鳃 / 流萤 / 镇火。
     *
     * 镇火这条同时是 ChefDamageHandler 整类删除后的替代覆盖 —— 免火伤已交还原版 FIRE_RESISTANCE
     * (原版 hurt 在最前面就对火伤直接 return false), 所以必须钉死"吃完真有那个原版效果且时长对"。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void vanillaBackedWindowsApplyTheirRealMobEffects(GameTestHelper helper) {
        ChefConsumeHandler handler = new ChefConsumeHandler();

        helper.assertTrue(ChefConfig.gillsSeconds(ChefQuality.RADIANT) == 480,
                "闪耀潜鳃 480 秒, 实得 " + ChefConfig.gillsSeconds(ChefQuality.RADIANT));
        ServerPlayer diver = eatWindowDish(helper, handler, ChefEffectType.GILLS, 480);
        helper.assertTrue(ChefWindowEffectState.active(diver, ChefEffectType.GILLS),
                "潜鳃窗口本身必须盖上");
        MobEffectInstance waterBreathing = diver.getEffect(MobEffects.WATER_BREATHING);
        MobEffectInstance dolphinsGrace = diver.getEffect(MobEffects.DOLPHINS_GRACE);
        helper.assertTrue(waterBreathing != null && waterBreathing.getDuration() == 9600
                        && dolphinsGrace != null && dolphinsGrace.getDuration() == 9600,
                "潜鳃必须给 480 秒 (9600 tick) 的水下呼吸与海豚之赐, 实得 "
                        + (waterBreathing == null ? "无水下呼吸" : waterBreathing.getDuration()) + "/"
                        + (dolphinsGrace == null ? "无海豚之赐" : dolphinsGrace.getDuration()));

        helper.assertTrue(ChefConfig.fireflySeconds(ChefQuality.RADIANT) == 600,
                "闪耀流萤 600 秒, 实得 " + ChefConfig.fireflySeconds(ChefQuality.RADIANT));
        ServerPlayer glower = eatWindowDish(helper, handler, ChefEffectType.FIREFLY, 600);
        MobEffectInstance glowing = glower.getEffect(MobEffects.GLOWING);
        helper.assertTrue(ChefWindowEffectState.active(glower, ChefEffectType.FIREFLY)
                        && glowing != null && glowing.getDuration() == 12000,
                "流萤必须给 600 秒 (12000 tick) 的发光, 实得 "
                        + (glowing == null ? "无发光" : String.valueOf(glowing.getDuration())));

        helper.assertTrue(ChefConfig.fireQuellSeconds(ChefQuality.RADIANT) == 18,
                "闪耀镇火 18 秒, 实得 " + ChefConfig.fireQuellSeconds(ChefQuality.RADIANT));
        ServerPlayer quencher = eatWindowDish(helper, handler, ChefEffectType.FIRE_QUELL, 18);
        MobEffectInstance fireResistance = quencher.getEffect(MobEffects.FIRE_RESISTANCE);
        helper.assertTrue(ChefWindowEffectState.active(quencher, ChefEffectType.FIRE_QUELL)
                        && fireResistance != null && fireResistance.getDuration() == 360,
                "镇火的免火伤由原版 FIRE_RESISTANCE 承担, 应为 18 秒 (360 tick), 实得 "
                        + (fireResistance == null ? "无火抗" : String.valueOf(fireResistance.getDuration())));
        helper.succeed();
    }

    /**
     * 增香效果黑名单的数据包文件必须落在<b>单数</b>的 tags/mob_effect/ 下。
     *
     * 1.20.1 的 tag 目录名取自注册表 key 的 path (Registries.MOB_EFFECT = "mob_effect", 单数, 不像
     * blocks/items 那样在 CUSTOM_REGISTRY_DIRECTORIES 里被改成复数)。写成 tags/mob_effects/ 的文件永远不会
     * 被 TagLoader 扫到, 而 {@code getTag()} 对未知 tag 返回一个空 tag 并不报错 —— 整份黑名单静默失效,
     * 没有任何日志。这里既按原版自己的规则算出目录名再去取文件, 也确认运行期这个 tag 真的被绑定过。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void amplifyEffectBlacklistTagLoadsFromTheSingularDirectory(GameTestHelper helper) {
        String tagDir = TagManager.getTagDir(ForgeRegistries.MOB_EFFECTS.getRegistryKey());
        helper.assertTrue("tags/mob_effect".equals(tagDir),
                "MobEffect 的数据包 tag 目录是单数 tags/mob_effect, 实得 " + tagDir);
        JsonObject tagFile = loadJsonResource(
                "/data/miningdim/" + tagDir + "/chef_amplify_effect_blacklist.json");
        helper.assertTrue(tagFile.getAsJsonArray("values").size() >= 32,
                "增香效果黑名单至少登记 32 条 FID 战斗向效果, 实得 "
                        + tagFile.getAsJsonArray("values").size());
        helper.assertTrue(ForgeRegistries.MOB_EFFECTS.tags()
                        .isKnownTagName(SeasoningBlacklist.AMPLIFY_EFFECT_BLACKLIST),
                "服务端必须真的加载到 " + SeasoningBlacklist.AMPLIFY_EFFECT_BLACKLIST.location()
                        + "; 未绑定说明文件放错了目录, 黑名单已静默失效");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void farmersDelightAndFidCompatibilityUsesActualRegistries(GameTestHelper helper) {
        // 两个 mod 都是 build.gradle 里 "jar 在 libs/ 才 runtimeOnly" 的可选开发依赖, 而 libs/ 不进版本库。
        // 硬断言它们已加载, 会让干净克隆上的整批 chef 挂在 "beef stew is loaded" 这种与调味逻辑无关的行上,
        // 更糟的是让 "All N required tests passed" 这条合 main 的唯一判据在不同机器上给出不同结论。
        // 与农夫模块既有范式一致 (FarmerGameTests): 缺依赖时跳过该段, 不缺时照常跑真注册表。
        if (ModList.get().isLoaded("farmersdelight")) {
            ResourceLocation stewId = new ResourceLocation("farmersdelight", "beef_stew");
            ResourceLocation feastId = new ResourceLocation("farmersdelight", "roast_chicken_block");
            helper.assertTrue(ForgeRegistries.ITEMS.containsKey(stewId),
                    "Farmers Delight is loaded but its beef stew is missing from the item registry");
            ItemStack stew = new ItemStack(ForgeRegistries.ITEMS.getValue(stewId));
            helper.assertTrue(stew.getFoodProperties(null) != null && !SeasoningEligibility.isUnseasonable(stew),
                    "ordinary Farmers Delight portions remain seasonable through FoodProperties");
            helper.assertTrue(ForgeRegistries.ITEMS.containsKey(feastId),
                    "Farmers Delight is loaded but its roast chicken feast is missing from the item registry");
            helper.assertTrue(SeasoningEligibility.isUnseasonable(
                            new ItemStack(ForgeRegistries.ITEMS.getValue(feastId))),
                    "whole Farmers Delight feasts are rejected by the optional feast tag");
        }

        if (ModList.get().isLoaded("flavor_immersed_daily")) {
            ResourceLocation groundPowderId = new ResourceLocation("flavor_immersed_daily", "groundpowder");
            helper.assertTrue(ForgeRegistries.ITEMS.containsKey(groundPowderId),
                    "FID is loaded but its groundpowder is missing from the item registry");
            ItemStack groundPowder = new ItemStack(ForgeRegistries.ITEMS.getValue(groundPowderId));
            helper.assertTrue(SeasoningTag.isSeasoning(groundPowder)
                            && SeasoningTag.biasOf(groundPowder) == SeasoningBias.AROMATIC,
                    "groundpowder belongs to the aromatic seasoning data tag");

            ResourceLocation blockedEffectId = new ResourceLocation("flavor_immersed_daily", "acidicpenetration");
            helper.assertTrue(ForgeRegistries.MOB_EFFECTS.containsKey(blockedEffectId),
                    "FID is loaded but its audited combat effect is missing from the effect registry");
            MobEffect blocked = ForgeRegistries.MOB_EFFECTS.getValue(blockedEffectId);
            helper.assertTrue(SeasoningBlacklist.isEffectBlacklisted(new MobEffectInstance(blocked, 200)),
                    "audited FID damage/penetration effect is blocked from amplify");
        }

        // 与可选 mod 无关的红线: 没有显式 id 条目的 HARMFUL 效果也必须被增香拒绝, 缺依赖时本条照跑。
        helper.assertTrue(SeasoningBlacklist.isEffectBlacklisted(
                        new MobEffectInstance(MobEffects.POISON, 200)),
                "all harmful effects are blocked even without an explicit id entry");
        helper.succeed();
    }

    private static TableFixture tableFixture(GameTestHelper helper) {
        return tableFixture(helper, (SeasoningTableBlock) ChefBlocks.SEASONING_TABLE_RADIANT.get());
    }

    private static TableFixture tableFixture(GameTestHelper helper, SeasoningTableBlock tableBlock) {
        BlockPos absolute = helper.absolutePos(TABLE_RELATIVE);
        BlockState primary = tableBlock.defaultBlockState()
                .setValue(SeasoningTableBlock.FACING, Direction.NORTH)
                .setValue(SeasoningTableBlock.SECONDARY, false);
        helper.getLevel().setBlock(absolute, primary, Block.UPDATE_CLIENTS);
        ((SeasoningTableBlock) primary.getBlock()).setPlacedBy(
                helper.getLevel(), absolute, primary, null, ItemStack.EMPTY);
        BlockEntity raw = helper.getLevel().getBlockEntity(absolute);
        helper.assertTrue(raw instanceof SeasoningTableBlockEntity,
                "seasoning table creates its preserved block entity id");
        SeasoningTableBlockEntity table = (SeasoningTableBlockEntity) raw;
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        player.setPos(absolute.getX() + 0.5D, absolute.getY() + 0.5D, absolute.getZ() + 0.5D);
        SeasoningMenu menu = new SeasoningMenu(1, player.getInventory(), table);
        player.containerMenu = menu;
        return new TableFixture(table, player, menu, absolute);
    }

    private static void drivePerfectHeat(GameTestHelper helper, TableFixture fixture) {
        helper.assertTrue(fixture.table.pressHeat(fixture.player), "initial heat press is accepted");
        tick(fixture.table, 70);
        helper.assertTrue(fixture.table.releaseHeat(fixture.player), "first heat release is accepted");
        tick(fixture.table, 20);
        helper.assertTrue(fixture.table.pressHeat(fixture.player), "second heat press is accepted");
        tick(fixture.table, 20);
        helper.assertTrue(fixture.table.releaseHeat(fixture.player), "second heat release is accepted");
        tick(fixture.table, 20);
        helper.assertTrue(fixture.table.pressHeat(fixture.player), "third heat press is accepted");
        tick(fixture.table, 10);
        helper.assertTrue(fixture.table.releaseHeat(fixture.player), "third heat release is accepted");
        tick(fixture.table, 20);
        helper.assertTrue(fixture.menu.phase() == 2, "160 authoritative ticks enter seasoning phase");
    }

    private static void driveAllQteHits(GameTestHelper helper, TableFixture fixture) {
        int guard = 0;
        while (fixture.menu.phase() == 2 && guard++ < 2000) {
            if (fixture.menu.cueActive()) {
                helper.assertTrue(fixture.table.hitSeason(fixture.player, fixture.menu.targetIndex()),
                        "active server target accepts its matching QTE input");
            } else {
                fixture.table.serverTick();
            }
        }
        helper.assertTrue(guard < 2000 && fixture.menu.phase() == 3,
                "all configured QTE targets settle the transaction");
        helper.assertTrue(fixture.menu.hits() == fixture.menu.qteCount(),
                "all configured QTE windows were hit");
    }

    private static void tick(SeasoningTableBlockEntity table, int count) {
        for (int i = 0; i < count; i++) {
            table.serverTick();
        }
    }

    /**
     * 固定 (满控火, 六个 cue 全中, 满级, 闪耀台) 下, 目标为 {@code target} 时成品不低于 {@code floor} 的点数
     * (千分之几)。逐一枚举 0-999 的掷点走生产结算函数, 不另写一份概率公式。
     */
    private static int countRollsAtLeast(ChefQuality target, ChefQuality floor) {
        int count = 0;
        for (int roll = 0; roll < 1000; roll++) {
            ChefQuality settled = ChefQualityResolver.resolveTargetRoll(target, roll,
                    candidate -> ChefQualityResolver.successChancePerMille(
                            candidate, 1.0D, 6, 6, 10, ChefQuality.RADIANT));
            if (settled.tier() >= floor.tier()) {
                count++;
            }
        }
        return count;
    }

    private static void assertTranslated(GameTestHelper helper, JsonObject zh, JsonObject en,
                                         String key, String what) {
        helper.assertTrue(zh.has(key) && !zh.get(key).getAsString().isBlank(),
                "missing zh_cn translation for " + what + " " + key);
        helper.assertTrue(en.has(key) && !en.get(key).getAsString().isBlank(),
                "missing en_us translation for " + what + " " + key);
    }

    /** 一个"什么都不给、只负责被回滚"的失败品: 金苹果的声明效果集合非空, 正好用来验回滚的边界。 */
    private static ItemStack spoiledGoldenApple() {
        ItemStack dish = new ItemStack(Items.GOLDEN_APPLE);
        ChefQualityNbt.stamp(dish, ChefQuality.LOW,
                List.of(new ChefEffectInstance(ChefEffectType.SPOILED, 0)));
        return dish;
    }

    /** 新造一个玩家吃下一道只带该窗口效果的闪耀菜, 返回吃完的玩家供断言。 */
    private static ServerPlayer eatWindowDish(GameTestHelper helper, ChefConsumeHandler handler,
                                              ChefEffectType type, int magnitude) {
        ServerPlayer eater = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ItemStack dish = new ItemStack(Items.BREAD);
        ChefQualityNbt.stamp(dish, ChefQuality.RADIANT,
                List.of(new ChefEffectInstance(type, magnitude)));
        start(handler, eater, dish);
        finish(handler, eater, dish);
        return eater;
    }

    private static void setChefLevel(ServerPlayer player, int level) {
        MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"))
                .jobProgress(JobId.CHEF).setLevel(level);
    }

    /** 背包里盖了品质章的菜总份数 (跨槽累加; 两轮做菜的产出可能因随机效果不同而无法堆叠)。 */
    private static int countStampedDishes(ServerPlayer player) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (ChefQualityNbt.hasQuality(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static ItemStack findStampedDish(net.minecraft.server.level.ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (ChefQualityNbt.hasQuality(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static void start(ChefConsumeHandler handler, net.minecraft.server.level.ServerPlayer player,
                              ItemStack stack) {
        handler.onStartEating(new LivingEntityUseItemEvent.Start(player, stack, 32));
    }

    private static void finish(ChefConsumeHandler handler, net.minecraft.server.level.ServerPlayer player,
                               ItemStack stack) {
        handler.onFinishEating(new LivingEntityUseItemEvent.Finish(player, stack, 0, ItemStack.EMPTY));
    }

    private static void assertNauseaDish(GameTestHelper helper, ChefQuality quality, int poisonLevel,
                                         int expectedTicks) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ChefConsumeHandler handler = new ChefConsumeHandler();
        ItemStack dish = new ItemStack(Items.BREAD);
        ChefQualityNbt.stamp(dish, quality, List.of(new ChefEffectInstance(ChefEffectType.NAUSEA, poisonLevel)));
        start(handler, player, dish);
        finish(handler, player, dish);
        MobEffectInstance poison = player.getEffect(MobEffects.POISON);
        helper.assertTrue(poison != null && poison.getDuration() == expectedTicks
                        && poison.getAmplifier() == poisonLevel - 1,
                quality.id() + " nausea must apply POISON for " + expectedTicks + " ticks at amplifier "
                        + (poisonLevel - 1) + ", got "
                        + (poison == null ? "no poison" : poison.getDuration() + "/" + poison.getAmplifier()));
    }

    private static void assertRefreshDish(GameTestHelper helper, ChefQuality quality, int hasteLevel,
                                          int expectedTicks) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ChefConsumeHandler handler = new ChefConsumeHandler();
        ItemStack dish = new ItemStack(Items.BREAD);
        ChefQualityNbt.stamp(dish, quality, List.of(new ChefEffectInstance(ChefEffectType.REFRESH, hasteLevel)));
        start(handler, player, dish);
        finish(handler, player, dish);
        MobEffectInstance haste = player.getEffect(MobEffects.DIG_SPEED);
        helper.assertTrue(haste != null && haste.getDuration() == expectedTicks
                        && haste.getAmplifier() == hasteLevel - 1,
                quality.id() + " refresh must apply DIG_SPEED for " + expectedTicks + " ticks at amplifier "
                        + (hasteLevel - 1) + ", got "
                        + (haste == null ? "no haste" : haste.getDuration() + "/" + haste.getAmplifier()));
    }

    /** 公服初始血量是 80, 与原版 20 差一个量级; 百分比公式必须在非 20 血下验证才测得出退化成定值的实现。 */
    private static void setMaxHealth(ServerPlayer player, double maxHealth) {
        AttributeInstance attribute = player.getAttribute(Attributes.MAX_HEALTH);
        if (attribute == null) {
            throw new IllegalStateException("mock player is missing its MAX_HEALTH attribute");
        }
        attribute.setBaseValue(maxHealth);
    }

    /** 抹掉活对象上的全部 ForgeData, 使后续断言只可能来自 NBT 反序列化 (mock 玩家上没有别的模块的数据)。 */
    private static void clearPersistentData(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        for (String key : List.copyOf(data.getAllKeys())) {
            data.remove(key);
        }
    }

    private static AABB dropArea(BlockPos primaryPos, BlockPos secondaryPos) {
        return new AABB(primaryPos).minmax(new AABB(secondaryPos)).inflate(2.0D);
    }

    private static int countDroppedItems(GameTestHelper helper, AABB area, Item item) {
        int total = 0;
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, area)) {
            if (entity.getItem().is(item)) {
                total += entity.getItem().getCount();
            }
        }
        return total;
    }

    private static JsonObject loadJsonResource(String path) {
        try (InputStream in = ChefGameTests.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("JSON resource not found on classpath: " + path);
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("failed reading JSON resource: " + path, exception);
        }
    }

    private record TableFixture(SeasoningTableBlockEntity table,
                                net.minecraft.server.level.ServerPlayer player,
                                SeasoningMenu menu,
                                BlockPos absolute) {
    }
}
