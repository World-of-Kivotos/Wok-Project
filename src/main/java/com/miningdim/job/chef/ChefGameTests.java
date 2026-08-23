package com.miningdim.job.chef;

import com.miningdim.core.MiningConstants;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyServices;
import com.miningdim.progression.ExperienceServices;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Set;

/** 厨师模块闭环 GameTest：事务、经验、食用快照、真实窗口效果及可选模组数据契约。 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChefGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "chef";
    private static final BlockPos TABLE_RELATIVE = new BlockPos(1, 1, 1);

    static {
        ChefConfig.ensureLoadedForTest();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void qualityCapsAndDeterministicPools(GameTestHelper helper) {
        int cues = ChefConfig.qteCount();
        helper.assertTrue(ChefQualityResolver.resolve(1.0D, cues, cues, ChefQuality.LOW, 10)
                        == ChefQuality.LOW,
                "radiant-capable chef is still capped by a low table");
        helper.assertTrue(ChefQualityResolver.resolve(1.0D, cues, cues, ChefQuality.RADIANT, 1)
                        == ChefQuality.LOW,
                "level 1 chef is still capped to low quality");
        helper.assertTrue(ChefQualityResolver.resolve(1.0D, cues, cues, ChefQuality.RADIANT, 9)
                        == ChefQuality.RADIANT,
                "level 9 chef can reach radiant quality at a perfect score");

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
        helper.assertTrue(ChefConfig.seasoningBiasedWeight() == 3
                        && ChefConfig.seasoningNeutralWeight() == 1
                        && ChefConfig.complexVirtualHits() == 1,
                "seasoning defaults keep 3:1 bias and one complex virtual hit");
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

        helper.assertTrue(fixture.table.startCooking(fixture.player), "valid bread starts seasoning");
        drivePerfectHeat(helper, fixture);
        driveAllQteHits(helper, fixture);

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
        helper.assertTrue(xpAfter - xpBefore == ChefConfig.xpForQuality(achieved),
                "successful cook awards its quality XP exactly once");
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

        helper.assertTrue(fixture.table.startCooking(fixture.player), "valid transaction starts before fee check");
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
        TableFixture fixture = tableFixture(helper);
        fixture.table.inputSlots().setStackInSlot(SeasoningMenu.SLOT_INPUT, new ItemStack(Items.DIAMOND));
        helper.assertFalse(fixture.table.startCooking(fixture.player), "non-food input is rejected");

        ItemStack alreadySeasoned = new ItemStack(Items.BREAD);
        ChefQualityNbt.stamp(alreadySeasoned, ChefQuality.LOW, List.of());
        fixture.table.inputSlots().setStackInSlot(SeasoningMenu.SLOT_INPUT, alreadySeasoned);
        helper.assertFalse(fixture.table.startCooking(fixture.player), "already seasoned dish is rejected");

        fixture.table.inputSlots().setStackInSlot(SeasoningMenu.SLOT_INPUT, new ItemStack(Items.BREAD));
        fixture.table.inputSlots().setStackInSlot(SeasoningMenu.SLOT_SEASONING, new ItemStack(Items.SUGAR));
        helper.assertTrue(fixture.table.startCooking(fixture.player), "first operator locks the table");
        var second = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        second.setPos(fixture.absolute.getX() + 0.5D, fixture.absolute.getY() + 0.5D,
                fixture.absolute.getZ() + 0.5D);
        SeasoningMenu secondMenu = new SeasoningMenu(2, second.getInventory(), fixture.table);
        second.containerMenu = secondMenu;
        helper.assertFalse(fixture.table.startCooking(second), "second player cannot replace the operator");
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
        LivingKnockBackEvent knockback = new LivingKnockBackEvent(player, 1.0F, 0.0D, 0.0D);
        new ChefAimHandler().onKnockback(knockback);
        helper.assertTrue(Math.abs(knockback.getStrength()) < 0.001F,
                "radiant stable aim cancels knockback without an attribute modifier");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shieldTracksOnlyChefOwnedAbsorptionAcrossLoginAndExpiry(GameTestHelper helper) {
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ChefWindowEffectState state = new ChefWindowEffectState();
        player.setAbsorptionAmount(1.0F);
        int perMille = ChefConfig.shieldPerMille(ChefQuality.RADIANT);
        ChefWindowEffectState.stampShield(player, perMille, ChefConfig.shieldWindowSeconds());
        float owned = ChefWindowEffectState.shieldRemaining(player);
        helper.assertTrue(Math.abs(player.getAbsorptionAmount() - 1.6F) < 0.01F
                        && Math.abs(owned - 0.6F) < 0.01F,
                "shield records only the yellow hearts actually added above foreign absorption");

        player.setAbsorptionAmount(1.3F);
        state.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
        helper.assertTrue(Math.abs(ChefWindowEffectState.shieldRemaining(player) - 0.3F) < 0.01F,
                "absorption damage consumes the chef-owned share first");
        state.onLoggedOut(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
        state.onLoggedIn(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(player));
        helper.assertTrue(ChefWindowEffectState.active(player, ChefEffectType.SHIELD)
                        && Math.abs(ChefWindowEffectState.shieldRemaining(player) - 0.3F) < 0.01F,
                "real effect and persistent shield ownership survive logout/login recovery");

        player.setAbsorptionAmount(player.getAbsorptionAmount() + 4.0F);
        state.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
        player.removeEffect(ChefWindowEffectState.effectFor(ChefEffectType.SHIELD));
        state.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
        helper.assertTrue(Math.abs(player.getAbsorptionAmount() - 5.0F) < 0.01F,
                "expiry reclaims only the remaining chef share and preserves foreign absorption");
        helper.assertTrue(ChefWindowEffectState.shieldRemaining(player) == 0.0F,
                "expired shield ownership is removed from persistent player data");
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

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void farmersDelightAndFidCompatibilityUsesActualRegistries(GameTestHelper helper) {
        ResourceLocation stewId = new ResourceLocation("farmersdelight", "beef_stew");
        ResourceLocation feastId = new ResourceLocation("farmersdelight", "roast_chicken_block");
        helper.assertTrue(ForgeRegistries.ITEMS.containsKey(stewId), "Farmers Delight beef stew is loaded in dev");
        ItemStack stew = new ItemStack(ForgeRegistries.ITEMS.getValue(stewId));
        helper.assertTrue(stew.getFoodProperties(null) != null && !SeasoningEligibility.isUnseasonable(stew),
                "ordinary Farmers Delight portions remain seasonable through FoodProperties");
        helper.assertTrue(ForgeRegistries.ITEMS.containsKey(feastId), "Farmers Delight feast item is loaded in dev");
        helper.assertTrue(SeasoningEligibility.isUnseasonable(
                        new ItemStack(ForgeRegistries.ITEMS.getValue(feastId))),
                "whole Farmers Delight feasts are rejected by the optional feast tag");

        ResourceLocation groundPowderId = new ResourceLocation("flavor_immersed_daily", "groundpowder");
        helper.assertTrue(ForgeRegistries.ITEMS.containsKey(groundPowderId), "FID groundpowder is loaded");
        ItemStack groundPowder = new ItemStack(ForgeRegistries.ITEMS.getValue(groundPowderId));
        helper.assertTrue(SeasoningTag.isSeasoning(groundPowder)
                        && SeasoningTag.biasOf(groundPowder) == SeasoningBias.AROMATIC,
                "groundpowder belongs to the aromatic seasoning data tag");

        ResourceLocation blockedEffectId = new ResourceLocation("flavor_immersed_daily", "acidicpenetration");
        helper.assertTrue(ForgeRegistries.MOB_EFFECTS.containsKey(blockedEffectId),
                "audited FID combat effect is loaded");
        MobEffect blocked = ForgeRegistries.MOB_EFFECTS.getValue(blockedEffectId);
        helper.assertTrue(SeasoningBlacklist.isEffectBlacklisted(new MobEffectInstance(blocked, 200)),
                "audited FID damage/penetration effect is blocked from amplify");
        helper.assertTrue(SeasoningBlacklist.isEffectBlacklisted(
                        new MobEffectInstance(MobEffects.POISON, 200)),
                "all harmful effects are blocked even without an explicit id entry");
        helper.succeed();
    }

    private static TableFixture tableFixture(GameTestHelper helper) {
        BlockPos absolute = helper.absolutePos(TABLE_RELATIVE);
        BlockState primary = ChefBlocks.SEASONING_TABLE_RADIANT.get().defaultBlockState()
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

    private record TableFixture(SeasoningTableBlockEntity table,
                                net.minecraft.server.level.ServerPlayer player,
                                SeasoningMenu menu,
                                BlockPos absolute) {
    }
}
