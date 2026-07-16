package com.miningdim.job.engineer;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.engineer.effect.NanoShieldHandler;
import com.miningdim.job.engineer.shield.PlasmaShieldConfig;
import com.miningdim.job.engineer.shield.PlasmaShieldHandler;
import com.miningdim.job.engineer.shield.PlasmaShieldState;
import com.miningdim.job.engineer.shield.PlasmaShieldSoundCadence;
import com.miningdim.job.engineer.shield.PlasmaShieldType;
import com.miningdim.job.engineer.shield.PlasmaShieldVisualProfile;
import com.miningdim.job.engineer.shield.item.PlasmaShieldItem;
import com.miningdim.job.engineer.shield.network.PlasmaShieldHitS2C;
import com.miningdim.job.engineer.shield.network.PlasmaShieldSyncS2C;
import com.miningdim.testutil.MockGameTestPlayers;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Deterministic regression coverage for the plasma-shield state machine and its integration boundaries. */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PlasmaShieldGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "plasma_shield";
    private static final double EPS = 1.0E-5D;

    private PlasmaShieldGameTests() {
    }

    @BeforeBatch(batch = BATCH)
    public static void beforePlasmaShieldBatch(ServerLevel level) {
        EngineerConfig.ensureLoadedForTest();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void threeRegisteredItemsKeepTrustedTypeIdentity(GameTestHelper helper) {
        Set<Item> distinctItems = new HashSet<>();
        for (PlasmaShieldType type : PlasmaShieldType.values()) {
            Item registered = ModEngineerItems.plasmaShield(type).get();
            helper.assertTrue(registered instanceof PlasmaShieldItem,
                    type.itemId() + " must register as PlasmaShieldItem");
            PlasmaShieldItem shield = (PlasmaShieldItem) registered;
            helper.assertTrue(shield.shieldType() == type,
                    type.itemId() + " must retain its registry-authoritative chassis type");
            helper.assertTrue(ForgeRegistries.ITEMS.getKey(registered) != null
                            && type.itemId().equals(ForgeRegistries.ITEMS.getKey(registered).getPath()),
                    type.itemId() + " registry path mismatch");
            helper.assertTrue(distinctItems.add(registered), type.itemId() + " must be a distinct item");
        }
        helper.assertTrue(distinctItems.size() == PlasmaShieldType.values().length,
                "exactly three distinct plasma-shield items must be registered");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void newStacksStartFullAndInitializeVersionedState(GameTestHelper helper) {
        for (PlasmaShieldType type : PlasmaShieldType.values()) {
            PlasmaShieldConfig.Stats stats = stats(type);
            ItemStack stack = shieldStack(type);
            helper.assertTrue(stack.getTagElement(PlasmaShieldState.ROOT_KEY) == null,
                    type + " fresh stack must not need eager NBT");

            PlasmaShieldState read = PlasmaShieldState.read(stack, stats);
            assertState(helper, read, stats.capacity(), 0.0D, false,
                    type + " fresh read");
            PlasmaShieldState initialized = PlasmaShieldState.initialize(stack, stats);
            assertState(helper, initialized, stats.capacity(), 0.0D, false,
                    type + " initialized state");
            helper.assertTrue(stack.getTagElement(PlasmaShieldState.ROOT_KEY) != null,
                    type + " initialize must persist the versioned shield root");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fullAbsorptionConsumesEnergyAndGeneratesHeat(GameTestHelper helper) {
        PlasmaShieldConfig.Stats stats = stats(PlasmaShieldType.NANO);
        PlasmaShieldState.HitResult result = PlasmaShieldState.absorb(
                PlasmaShieldState.full(stats), stats, 20.0D);

        helper.assertTrue(close(result.absorbedDamage(), 20.0D), "20 damage must be fully absorbed");
        helper.assertTrue(close(result.remainingDamage(), 0.0D), "fully absorbed hit must pass no damage");
        helper.assertTrue(close(result.state().shield(), stats.capacity() - 20.0D),
                "absorbed damage must consume one energy per damage");
        helper.assertTrue(close(result.state().heat(), 20.0D * stats.heatPerDamage()),
                "absorbed damage must generate chassis heat");
        helper.assertTrue(!result.state().overheated(), "ordinary nano hit must not overheat");
        helper.assertTrue(result.state().rechargeDelayTicks() == stats.rechargeDelayTicks(),
                "eligible hit must reset recharge delay");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void depletedEnergyPartiallyAbsorbsAndPassesRemainder(GameTestHelper helper) {
        PlasmaShieldConfig.Stats stats = stats(PlasmaShieldType.LIGHT);
        PlasmaShieldState input = new PlasmaShieldState(3.0D, 0.0D, false, 0, 0);
        PlasmaShieldState.HitResult result = PlasmaShieldState.absorb(input, stats, 10.0D);

        helper.assertTrue(close(result.absorbedDamage(), 3.0D), "only remaining energy may be absorbed");
        helper.assertTrue(close(result.remainingDamage(), 7.0D), "unshielded remainder must reach health");
        helper.assertTrue(close(result.state().shield(), 0.0D), "partial absorption must drain battery exactly");
        helper.assertTrue(close(result.state().heat(), 3.0D * stats.heatPerDamage()),
                "only absorbed damage may generate heat");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void thermalBudgetPartiallyAbsorbsBoundaryHitAndShutsDown(GameTestHelper helper) {
        PlasmaShieldConfig.Stats stats = stats(PlasmaShieldType.LIGHT);
        double startingHeat = stats.maxHeat() - 1.0D;
        double thermalAllowance = 1.0D / stats.heatPerDamage();
        PlasmaShieldState input = new PlasmaShieldState(
                stats.capacity(), startingHeat, false, 0, 0);
        PlasmaShieldState.HitResult result = PlasmaShieldState.absorb(input, stats, 10.0D);

        helper.assertTrue(close(result.absorbedDamage(), thermalAllowance),
                "absorption must stop exactly at remaining thermal budget");
        helper.assertTrue(close(result.remainingDamage(), 10.0D - thermalAllowance),
                "damage beyond thermal budget must pass through");
        helper.assertTrue(close(result.state().heat(), stats.maxHeat()),
                "thermal boundary hit must clamp heat to maximum");
        helper.assertTrue(result.state().overheated(), "reaching maximum heat must shut the shield down");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void combinedAndSegmentedDamageHaveEquivalentSettlement(GameTestHelper helper) {
        PlasmaShieldConfig.Stats stats = stats(PlasmaShieldType.LIGHT);
        PlasmaShieldState initial = PlasmaShieldState.full(stats);
        PlasmaShieldState.HitResult combined = PlasmaShieldState.absorb(initial, stats, 100.0D);

        PlasmaShieldState segmentedState = initial;
        double segmentedAbsorbed = 0.0D;
        double segmentedRemaining = 0.0D;
        for (double segment : new double[]{25.0D, 25.0D, 50.0D}) {
            PlasmaShieldState.HitResult result = PlasmaShieldState.absorb(segmentedState, stats, segment);
            segmentedState = result.state();
            segmentedAbsorbed += result.absorbedDamage();
            segmentedRemaining += result.remainingDamage();
        }

        helper.assertTrue(close(segmentedAbsorbed, combined.absorbedDamage()),
                "split TaCZ segments must absorb the same total as one combined hit");
        helper.assertTrue(close(segmentedRemaining, combined.remainingDamage()),
                "split TaCZ segments must pass the same total as one combined hit");
        helper.assertTrue(close(segmentedState.shield(), combined.state().shield())
                        && close(segmentedState.heat(), combined.state().heat())
                        && segmentedState.overheated() == combined.state().overheated(),
                "combined and segmented hits must end in the same shield state");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void overheatBlocksAbsorptionCoolsImmediatelyAndRestartsAtThirty(GameTestHelper helper) {
        PlasmaShieldConfig.Stats stats = stats(PlasmaShieldType.NANO);
        PlasmaShieldState hot = new PlasmaShieldState(
                stats.capacity(), stats.maxHeat(), true, 0, 200);
        PlasmaShieldState.HitResult blocked = PlasmaShieldState.absorb(hot, stats, 12.0D);
        helper.assertTrue(close(blocked.absorbedDamage(), 0.0D)
                        && close(blocked.remainingDamage(), 12.0D),
                "overheated shield must absorb zero damage");

        PlasmaShieldState oneTickCooled = PlasmaShieldState.tick(blocked.state(), stats, 1);
        helper.assertTrue(oneTickCooled.heat() < blocked.state().heat(),
                "overheated shield must emergency-cool despite its cooling-delay counter");

        double heatRemovedPerTick = stats.coolingPerSecond() / 20.0D;
        PlasmaShieldState aboveRestart = new PlasmaShieldState(
                0.0D, stats.restartHeat() + heatRemovedPerTick + 0.01D, true, 200, 200);
        PlasmaShieldState stillHot = PlasmaShieldState.tick(aboveRestart, stats, 1);
        helper.assertTrue(stillHot.overheated() && stillHot.heat() > stats.restartHeat(),
                "hysteresis must keep shield disabled above restart heat");
        PlasmaShieldState restarted = PlasmaShieldState.tick(stillHot, stats, 1);
        helper.assertTrue(!restarted.overheated() && restarted.heat() <= stats.restartHeat(),
                "shield must restart when cooling reaches the configured 30 heat threshold");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chassisBalanceOrderingMatchesLightMediumHeavyRoles(GameTestHelper helper) {
        PlasmaShieldConfig.Stats nano = stats(PlasmaShieldType.NANO);
        PlasmaShieldConfig.Stats light = stats(PlasmaShieldType.LIGHT);
        PlasmaShieldConfig.Stats heavy = stats(PlasmaShieldType.HEAVY_ION);

        helper.assertTrue(nano.capacity() < light.capacity() && light.capacity() < heavy.capacity(),
                "energy capacity must order nano < light < heavy ion");
        helper.assertTrue(nano.heatPerDamage() < heavy.heatPerDamage()
                        && heavy.heatPerDamage() < light.heatPerDamage(),
                "nano must accumulate heat slowest and light fastest");
        helper.assertTrue(heavy.coolingPerSecond() < light.coolingPerSecond()
                        && light.coolingPerSecond() < nano.coolingPerSecond(),
                "heavy must cool slowest and nano fastest");
        helper.assertTrue(heavy.rechargePerSecond() < light.rechargePerSecond()
                        && light.rechargePerSecond() < nano.rechargePerSecond(),
                "heavy must recharge slowest and nano fastest");
        helper.assertTrue(close(nano.movementModifier(), 0.0D)
                        && close(light.movementModifier(), 0.0D)
                        && heavy.movementModifier() < 0.0D,
                "only heavy ion may impose a movement penalty");
        helper.assertTrue(close(nano.restartHeat(), 30.0D)
                        && close(light.restartHeat(), 30.0D)
                        && close(heavy.restartHeat(), 30.0D),
                "all chassis must retain the approved 30-heat restart hysteresis");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nbtRoundTripSanitizesCorruptionAndConfigShrink(GameTestHelper helper) {
        ItemStack stack = new ItemStack(Items.STICK);
        PlasmaShieldConfig.Stats originalStats = stats(PlasmaShieldType.NANO);
        PlasmaShieldState original = new PlasmaShieldState(17.25D, 42.5D, false, 37, 11);
        PlasmaShieldState.write(stack, original);
        PlasmaShieldState roundTrip = PlasmaShieldState.read(stack, originalStats);
        helper.assertTrue(roundTrip.equals(original), "valid versioned NBT must round-trip exactly");

        PlasmaShieldState.write(stack,
                new PlasmaShieldState(Double.NaN, Double.POSITIVE_INFINITY, false, -7, -9));
        PlasmaShieldState cleaned = PlasmaShieldState.read(stack, originalStats);
        assertState(helper, cleaned, originalStats.capacity(), 0.0D, false,
                "non-finite NBT cleaning");
        helper.assertTrue(cleaned.rechargeDelayTicks() == 0 && cleaned.heatCoolDelayTicks() == 0,
                "negative persisted delay counters must sanitize to zero");

        PlasmaShieldState.write(stack,
                new PlasmaShieldState(originalStats.capacity(), 80.0D, false, 7, 9));
        PlasmaShieldConfig.Stats shrunk = customStats(
                10.0D, 50.0D, 30.0D, originalStats.heatPerDamage(),
                originalStats.coolingPerSecond(), originalStats.rechargePerSecond(),
                originalStats.rechargeDelayTicks(), originalStats.heatCoolDelayTicks(),
                originalStats.movementModifier());
        PlasmaShieldState clamped = PlasmaShieldState.read(stack, shrunk);
        assertState(helper, clamped, 10.0D, 50.0D, true, "config-shrunk state");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void s2cCodecRoundTripsAndRejectsInvalidTypes(GameTestHelper helper) {
        PlasmaShieldSyncS2C message = new PlasmaShieldSyncS2C(
                true, PlasmaShieldType.HEAVY_ION.id(), 90.5F, 140.0F,
                73.25F, 100.0F, false, 21);
        FriendlyByteBuf validBuffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            PlasmaShieldSyncS2C.encode(message, validBuffer);
            PlasmaShieldSyncS2C decoded = PlasmaShieldSyncS2C.decode(validBuffer);
            helper.assertTrue(decoded.equals(message), "valid S2C snapshot must round-trip exactly");
        } finally {
            validBuffer.release();
        }

        PlasmaShieldSyncS2C invalid = new PlasmaShieldSyncS2C(
                true, "forged_unknown_type", Float.NaN, -4.0F,
                Float.POSITIVE_INFINITY, 0.0F, true, -20);
        helper.assertTrue(invalid.sanitized().equals(PlasmaShieldSyncS2C.inactive()),
                "unknown type must sanitize to inactive before reaching client state");
        FriendlyByteBuf invalidBuffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            PlasmaShieldSyncS2C.encode(invalid, invalidBuffer);
            helper.assertTrue(PlasmaShieldSyncS2C.decode(invalidBuffer).equals(PlasmaShieldSyncS2C.inactive()),
                    "invalid encoded snapshot must decode as inactive");
        } finally {
            invalidBuffer.release();
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void hitS2cCodecPreservesTypeStrengthAndOverload(GameTestHelper helper) {
        int entityId = 42;
        for (PlasmaShieldType type : PlasmaShieldType.values()) {
            PlasmaShieldHitS2C message = PlasmaShieldHitS2C.forHit(
                    entityId, type, 3.0D, type == PlasmaShieldType.HEAVY_ION);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                PlasmaShieldHitS2C.encode(message, buffer);
                PlasmaShieldHitS2C decoded = PlasmaShieldHitS2C.decode(buffer);
                helper.assertTrue(decoded.equals(message),
                        type + " hit feedback must round-trip exactly");
            } finally {
                buffer.release();
            }
        }

        PlasmaShieldHitS2C invalid = new PlasmaShieldHitS2C(
                -1, "forged_unknown_type", Float.NaN, true);
        helper.assertTrue(invalid.sanitized().equals(PlasmaShieldHitS2C.inactive()),
                "invalid entity, type, or strength must disable hit feedback at the network boundary");
        PlasmaShieldHitS2C oversized = new PlasmaShieldHitS2C(
                entityId, PlasmaShieldType.LIGHT.id(), 5.0F, false).sanitized();
        helper.assertTrue(Float.compare(oversized.strength(), 1.0F) == 0,
                "network strength above one must clamp before reaching client rendering");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void visualProfileKeepsThreeReadableColoursAndBoundedPulse(GameTestHelper helper) {
        Set<Integer> primaryColours = new HashSet<>();
        Set<Integer> highlightColours = new HashSet<>();
        for (PlasmaShieldType type : PlasmaShieldType.values()) {
            PlasmaShieldVisualProfile.Style style = PlasmaShieldVisualProfile.style(type);
            primaryColours.add(style.primaryRgb());
            highlightColours.add(style.highlightRgb());
        }
        helper.assertTrue(primaryColours.size() == PlasmaShieldType.values().length,
                "each shield chassis must have a distinct primary hit colour");
        helper.assertTrue(highlightColours.size() == PlasmaShieldType.values().length,
                "each shield chassis must have a distinct highlight colour");

        float weak = PlasmaShieldVisualProfile.strengthForAbsorbedDamage(0.25D);
        float strong = PlasmaShieldVisualProfile.strengthForAbsorbedDamage(12.0D);
        helper.assertTrue(weak >= PlasmaShieldVisualProfile.MIN_VISIBLE_STRENGTH && weak < strong,
                "small absorbed hits must remain visible but weaker than full-strength hits");
        helper.assertTrue(Float.compare(strong, 1.0F) == 0,
                "twelve absorbed damage must reach full visual strength");
        helper.assertTrue(PlasmaShieldVisualProfile.OVERLOAD_DURATION_TICKS
                        > PlasmaShieldVisualProfile.HIT_DURATION_TICKS,
                "overload collapse must outlast an ordinary hit flash");
        helper.assertTrue(PlasmaShieldVisualProfile.alpha(0.5F, strong, false)
                        > PlasmaShieldVisualProfile.alpha(8.0F, strong, false),
                "hit flash must decay after its immediate attack");
        helper.assertTrue(PlasmaShieldVisualProfile.alpha(
                        PlasmaShieldVisualProfile.HIT_DURATION_TICKS, strong, false) == 0.0F,
                "ordinary hit flash must be fully gone at its declared lifetime");
        helper.assertTrue(PlasmaShieldVisualProfile.scale(1.0F, strong, true) > 0.0F,
                "overload pulse must have a positive render scale while active");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void hitTextureIsTransparentTintableRgba(GameTestHelper helper) {
        String path = "/assets/miningdim/textures/entity/plasma_shield_hit.png";
        try (InputStream input = PlasmaShieldGameTests.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("shield hit texture missing from classpath: " + path);
            }
            BufferedImage image = ImageIO.read(input);
            helper.assertTrue(image != null && image.getWidth() == 256 && image.getHeight() == 256,
                    "shield hit texture must decode as 256 by 256 pixels");
            helper.assertTrue(image.getColorModel().hasAlpha(),
                    "shield hit texture must retain an alpha channel");
            int[] corners = {
                    image.getRGB(0, 0),
                    image.getRGB(image.getWidth() - 1, 0),
                    image.getRGB(0, image.getHeight() - 1),
                    image.getRGB(image.getWidth() - 1, image.getHeight() - 1)
            };
            for (int corner : corners) {
                helper.assertTrue((corner >>> 24) == 0,
                        "all shield hit texture corners must be fully transparent");
            }
            int visiblePixels = 0;
            int opaqueBlackPixels = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    int alpha = argb >>> 24;
                    if (alpha > 0) {
                        visiblePixels++;
                        int red = argb >> 16 & 0xFF;
                        int green = argb >> 8 & 0xFF;
                        int blue = argb & 0xFF;
                        if (alpha == 255 && red < 16 && green < 16 && blue < 16) {
                            opaqueBlackPixels++;
                        }
                    }
                }
            }
            helper.assertTrue(visiblePixels > 5_000,
                    "shield hit texture must contain a readable honeycomb silhouette");
            helper.assertTrue(opaqueBlackPixels == 0,
                    "shield hit texture must not contain an opaque black backdrop");
        } catch (IOException exception) {
            throw new IllegalStateException("failed reading shield hit texture: " + path, exception);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void heavyMovementModifierIsIdempotentAndNeverLeaks(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.setItemSlot(EquipmentSlot.CHEST, shieldStack(PlasmaShieldType.HEAVY_ION));
        for (int i = 0; i < 5; i++) {
            PlasmaShieldHandler.synchronizeMovement(player);
        }
        AttributeModifier heavy = player.getAttribute(Attributes.MOVEMENT_SPEED)
                .getModifier(PlasmaShieldHandler.MOVEMENT_ID);
        helper.assertTrue(heavy != null
                        && close(heavy.getAmount(), stats(PlasmaShieldType.HEAVY_ION).movementModifier())
                        && heavy.getOperation() == AttributeModifier.Operation.MULTIPLY_TOTAL,
                "repeated heavy sync must retain exactly one approved movement modifier");

        player.setItemSlot(EquipmentSlot.CHEST, shieldStack(PlasmaShieldType.LIGHT));
        PlasmaShieldHandler.synchronizeMovement(player);
        helper.assertTrue(player.getAttribute(Attributes.MOVEMENT_SPEED)
                        .getModifier(PlasmaShieldHandler.MOVEMENT_ID) == null,
                "switching to light shield must remove the heavy modifier");

        player.setItemSlot(EquipmentSlot.CHEST, shieldStack(PlasmaShieldType.HEAVY_ION));
        PlasmaShieldHandler.synchronizeMovement(player);
        player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        PlasmaShieldHandler.synchronizeMovement(player);
        PlasmaShieldHandler.synchronizeMovement(player);
        helper.assertTrue(player.getAttribute(Attributes.MOVEMENT_SPEED)
                        .getModifier(PlasmaShieldHandler.MOVEMENT_ID) == null,
                "repeated unequip sync must leave no stale movement modifier");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void legacyNanoShieldCannotStackWithPlasmaShield(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ItemStack helmet = new ItemStack(Items.DIAMOND_HELMET);
        NanoNbt.writeEffects(helmet, EnumSet.of(NanoEffect.SHIELD));
        NanoNbt.setShieldWindowTick(helmet, 20);
        player.setItemSlot(EquipmentSlot.HEAD, helmet);

        ItemStack plasma = shieldStack(PlasmaShieldType.NANO);
        player.setItemSlot(EquipmentSlot.CHEST, plasma);
        LivingHurtEvent withPlasma = new LivingHurtEvent(player,
                helper.getLevel().damageSources().playerAttack(player), 20.0F);
        new NanoShieldHandler().onLivingHurt(withPlasma);
        helper.assertTrue(close(withPlasma.getAmount(), 20.0D),
                "legacy nano immunity must not zero damage while plasma shield is equipped");

        new PlasmaShieldHandler().onLivingHurt(withPlasma);
        helper.assertTrue(close(withPlasma.getAmount(), 0.0D),
                "the equipped plasma shield remains the sole active absorber");
        PlasmaShieldState plasmaState = PlasmaShieldState.read(plasma, stats(PlasmaShieldType.NANO));
        helper.assertTrue(close(plasmaState.shield(), stats(PlasmaShieldType.NANO).capacity() - 20.0D),
                "plasma absorber must spend energy exactly once");

        player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        LivingHurtEvent withoutPlasma = new LivingHurtEvent(player,
                helper.getLevel().damageSources().playerAttack(player), 20.0F);
        new NanoShieldHandler().onLivingHurt(withoutPlasma);
        helper.assertTrue(close(withoutPlasma.getAmount(), 0.0D),
                "legacy nano shield must resume after plasma shield removal");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nanoRepairRejectsPlasmaShield(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ItemStack shield = shieldStack(PlasmaShieldType.HEAVY_ION);
        ItemStack repairPlate = new ItemStack(ModEngineerItems.plate(NanoTier.HIGH).get());

        NanoRepair.Result result = NanoRepair.repair(shield, repairPlate, player,
                new com.miningdim.job.engineer.testutil.FixedDoubleRandom(0.0D));
        helper.assertTrue(!result.success()
                        && "message.miningdim.engineer.repair.plasma_shield_incompatible".equals(result.failKey()),
                "nano repair must reject plasma shields with the dedicated incompatibility result");
        helper.assertTrue(shield.getDamageValue() == 0,
                "rejected nano repair must not mutate the plasma shield item");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bypassInvulnerabilityDamageLeavesInitializedShieldUntouched(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EmbeddedChannel channel = (EmbeddedChannel) player.connection.connection.channel();
        drainFeedbackPackets(channel);
        PlasmaShieldConfig.Stats stats = stats(PlasmaShieldType.LIGHT);
        ItemStack shield = shieldStack(PlasmaShieldType.LIGHT);
        PlasmaShieldState.initialize(shield, stats);
        PlasmaShieldState before = new PlasmaShieldState(17.25D, 44.5D, false, 37, 19);
        PlasmaShieldState.write(shield, before);
        player.setItemSlot(EquipmentSlot.CHEST, shield);

        helper.assertTrue(helper.getLevel().damageSources().genericKill().is(DamageTypeTags.BYPASSES_INVULNERABILITY),
                "generic_kill must retain the bypasses_invulnerability safety-boundary tag");
        helper.assertTrue(helper.getLevel().damageSources().starve().is(PlasmaShieldHandler.BYPASSES_PLASMA_SHIELD),
                "the plasma-shield bypass datapack tag must include starvation damage");
        LivingHurtEvent event = new LivingHurtEvent(
                player, helper.getLevel().damageSources().genericKill(), 23.75F);
        new PlasmaShieldHandler().onLivingHurt(event);

        PlasmaShieldState after = PlasmaShieldState.read(shield, stats);
        helper.assertTrue(Float.compare(event.getAmount(), 23.75F) == 0,
                "bypassing damage amount must pass through completely unchanged");
        helper.assertTrue(after.equals(before),
                "bypassing damage must not mutate shield, heat, overheat, recharge delay, or cooling delay");
        FeedbackPackets feedback = drainFeedbackPackets(channel);
        helper.assertTrue(feedback.sounds().isEmpty() && feedback.hits().isEmpty(),
                "bypassing damage must not emit plasma-shield sound or visual feedback");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void batchedTickMatchesPerTickAcrossDelaysAndOverheatRestart(GameTestHelper helper) {
        PlasmaShieldConfig.Stats nano = stats(PlasmaShieldType.NANO);
        PlasmaShieldState delayed = new PlasmaShieldState(10.0D, 10.0D, false, 2, 2);
        PlasmaShieldState delayedBatch = PlasmaShieldState.tick(delayed, nano, 5);
        PlasmaShieldState delayedSteps = tickOneAtATime(delayed, nano, 5);

        helper.assertTrue(delayedBatch.equals(delayedSteps),
                "five-tick settlement must exactly equal five one-tick settlements across both delay boundaries");
        helper.assertTrue(close(delayedBatch.shield(),
                        delayed.shield() + 3.0D * nano.rechargePerSecond() / 20.0D),
                "rechargeDelay=2 must permit recharge on exactly the final three of five ticks");
        helper.assertTrue(close(delayedBatch.heat(),
                        delayed.heat() - 3.0D * nano.coolingPerSecond() / 20.0D),
                "heatCoolDelay=2 must permit cooling on exactly the final three of five ticks");
        helper.assertTrue(delayedBatch.rechargeDelayTicks() == 0
                        && delayedBatch.heatCoolDelayTicks() == 0,
                "both two-tick delays must expire without going negative");

        PlasmaShieldConfig.Stats crossingStats = customStats(
                100.0D, 100.0D, 30.0D, 1.0D,
                20.0D, 20.0D, 0, 0, 0.0D);
        PlasmaShieldState crossing = new PlasmaShieldState(40.0D, 32.0D, true, 0, 0);
        PlasmaShieldState crossingBatch = PlasmaShieldState.tick(crossing, crossingStats, 5);
        PlasmaShieldState crossingSteps = tickOneAtATime(crossing, crossingStats, 5);

        helper.assertTrue(crossingBatch.equals(crossingSteps),
                "batched settlement must exactly match per-tick settlement while crossing restart heat");
        helper.assertTrue(!crossingBatch.overheated() && close(crossingBatch.heat(), 27.0D),
                "overheated shield must cool through 30 heat during the five-tick window");
        helper.assertTrue(close(crossingBatch.shield(), 43.0D),
                "restart at tick two must not recharge on that same tick; only ticks three through five may charge");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void boundaryHitDoesNotSpendFreshDelaysInSameTick(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        PlasmaShieldConfig.Stats stats = stats(PlasmaShieldType.LIGHT);
        ItemStack shield = shieldStack(PlasmaShieldType.LIGHT);
        player.setItemSlot(EquipmentSlot.CHEST, shield);

        PlasmaShieldHandler handler = new PlasmaShieldHandler();
        int interval = EngineerConfig.PLASMA_SHIELD.stateTickInterval();
        player.tickCount = interval;
        LivingHurtEvent hit = new LivingHurtEvent(
                player, helper.getLevel().damageSources().generic(), 1.0F);
        handler.onLivingHurt(hit);

        PlasmaShieldState afterHit = PlasmaShieldState.read(shield, stats);
        helper.assertTrue(afterHit.rechargeDelayTicks() == stats.rechargeDelayTicks()
                        && afterHit.heatCoolDelayTicks() == stats.heatCoolDelayTicks(),
                "a hit must start both configured delays at their full values");
        helper.assertTrue(close(afterHit.shield(), stats.capacity() - 1.0D)
                        && close(afterHit.heat(), stats.heatPerDamage()),
                "the boundary hit must be absorbed exactly once");

        handler.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
        PlasmaShieldState afterSameTickEnd = PlasmaShieldState.read(shield, stats);
        helper.assertTrue(afterSameTickEnd.equals(afterHit),
                "END settlement on the damage tick must not retroactively spend a fresh five-tick delay");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shieldNbtEquipmentEventDoesNotResetSettlementClock(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        PlasmaShieldConfig.Stats stats = stats(PlasmaShieldType.NANO);
        ItemStack shield = shieldStack(PlasmaShieldType.NANO);
        ItemStack previousTickCopy = shield.copy();
        player.setItemSlot(EquipmentSlot.CHEST, shield);
        PlasmaShieldHandler handler = new PlasmaShieldHandler();
        ServerLevel level = helper.getLevel();
        ServerLevelData levelData = (ServerLevelData) level.getLevelData();
        long originalGameTime = level.getGameTime();

        try {
            LivingHurtEvent hit = new LivingHurtEvent(
                    player, level.damageSources().generic(), 1.0F);
            handler.onLivingHurt(hit);
            PlasmaShieldState afterHit = PlasmaShieldState.read(shield, stats);

            levelData.setGameTime(originalGameTime + 1L);
            handler.onEquipmentChanged(new LivingEquipmentChangeEvent(
                    player, EquipmentSlot.CHEST, previousTickCopy, shield));

            levelData.setGameTime(originalGameTime + 5L);
            handler.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
            PlasmaShieldState afterFiveTicks = PlasmaShieldState.read(shield, stats);

            helper.assertTrue(afterFiveTicks.rechargeDelayTicks()
                            == afterHit.rechargeDelayTicks() - 5,
                    "same-stack NBT equipment detection must not lose one tick from the settlement window");
            helper.assertTrue(afterFiveTicks.heatCoolDelayTicks()
                            == afterHit.heatCoolDelayTicks() - 5,
                    "same-stack NBT equipment detection must not slow heat-delay expiry to five-sixths speed");
        } finally {
            levelData.setGameTime(originalGameTime);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void plasmaShieldSoundsAreRegistered(GameTestHelper helper) {
        helper.assertTrue(ModEngineerSounds.PLASMA_SHIELD_HIT.getId().equals(
                        new ResourceLocation(MiningConstants.MODID, "plasma_shield_hit")),
                "hit sound registry id mismatch");
        helper.assertTrue(ModEngineerSounds.PLASMA_SHIELD_OVERHEAT.getId().equals(
                        new ResourceLocation(MiningConstants.MODID, "plasma_shield_overheat")),
                "overheat sound registry id mismatch");
        helper.assertTrue(ModEngineerSounds.PLASMA_SHIELD_STEAM_VENT.getId().equals(
                        new ResourceLocation(MiningConstants.MODID, "plasma_shield_steam_vent")),
                "steam-vent sound registry id mismatch");
        helper.assertTrue(ForgeRegistries.SOUND_EVENTS.getValue(
                        ModEngineerSounds.PLASMA_SHIELD_HIT.getId())
                        == ModEngineerSounds.PLASMA_SHIELD_HIT.get(),
                "hit sound must be present in Forge's registry");
        helper.assertTrue(ForgeRegistries.SOUND_EVENTS.getValue(
                        ModEngineerSounds.PLASMA_SHIELD_OVERHEAT.getId())
                        == ModEngineerSounds.PLASMA_SHIELD_OVERHEAT.get(),
                "overheat sound must be present in Forge's registry");
        helper.assertTrue(ForgeRegistries.SOUND_EVENTS.getValue(
                        ModEngineerSounds.PLASMA_SHIELD_STEAM_VENT.getId())
                        == ModEngineerSounds.PLASMA_SHIELD_STEAM_VENT.get(),
                "steam-vent sound must be present in Forge's registry");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void hitCadenceCoalescesSegmentsAndResetsAtLifecycleBoundaries(GameTestHelper helper) {
        PlasmaShieldSoundCadence cadence = new PlasmaShieldSoundCadence();
        UUID playerId = UUID.randomUUID();
        ItemStack firstShield = new ItemStack(Items.STICK);

        helper.assertTrue(cadence.shouldEmitHit(playerId, firstShield, 100L),
                "the first absorbed hit must emit feedback immediately");
        helper.assertTrue(!cadence.shouldEmitHit(playerId, firstShield, 100L),
                "same-tick segmented damage must be coalesced");
        helper.assertTrue(!cadence.shouldEmitHit(playerId, firstShield, 101L),
                "feedback must remain paced until the two-tick boundary");
        helper.assertTrue(cadence.shouldEmitHit(playerId, firstShield, 102L),
                "the exact pacing boundary must permit the next response");

        ItemStack replacementShield = new ItemStack(Items.BLAZE_ROD);
        helper.assertTrue(cadence.shouldEmitHit(playerId, replacementShield, 102L),
                "replacement equipment must not inherit the previous shield's hit schedule");
        helper.assertTrue(cadence.shouldEmitHit(playerId, replacementShield, 90L),
                "world-time rollback must restart the hit schedule without suppressing feedback");
        cadence.clear(playerId);
        helper.assertTrue(cadence.shouldEmitHit(playerId, replacementShield, 91L),
                "lifecycle cleanup must permit the next absorbed hit immediately");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void steamVentCadenceRequiresOverheatAndPacesEmergencyCooling(GameTestHelper helper) {
        PlasmaShieldSoundCadence cadence = new PlasmaShieldSoundCadence();
        UUID playerId = UUID.randomUUID();
        ItemStack firstShield = new ItemStack(Items.STICK);

        helper.assertTrue(!cadence.shouldPlayVent(playerId, firstShield, 100L),
                "ordinary cooling must never start the full emergency steam sound");
        cadence.onOverheated(playerId, firstShield, 105L);
        helper.assertTrue(!cadence.shouldPlayVent(playerId, firstShield,
                        105L + PlasmaShieldSoundCadence.OVERHEAT_TO_FIRST_VENT_TICKS - 1L),
                "overheat warning must finish before the first pressure release");
        helper.assertTrue(cadence.shouldPlayVent(playerId, firstShield,
                        105L + PlasmaShieldSoundCadence.OVERHEAT_TO_FIRST_VENT_TICKS),
                "emergency vent must start at the exact warning-to-steam boundary");
        helper.assertTrue(!cadence.shouldPlayVent(playerId, firstShield,
                        105L + PlasmaShieldSoundCadence.OVERHEAT_TO_FIRST_VENT_TICKS
                                + PlasmaShieldSoundCadence.VENT_INTERVAL_TICKS - 1L),
                "continuous emergency cooling must not repeat steam before its interval");
        helper.assertTrue(cadence.shouldPlayVent(playerId, firstShield,
                        105L + PlasmaShieldSoundCadence.OVERHEAT_TO_FIRST_VENT_TICKS
                                + PlasmaShieldSoundCadence.VENT_INTERVAL_TICKS),
                "continuous emergency cooling may vent again at the exact interval boundary");

        ItemStack replacementShield = new ItemStack(Items.BLAZE_ROD);
        helper.assertTrue(!cadence.shouldPlayVent(playerId, replacementShield, 313L),
                "replacement equipment must require its own overheat transition before venting");
        cadence.onOverheated(playerId, replacementShield, 313L);
        helper.assertTrue(cadence.shouldPlayVent(playerId, replacementShield,
                        313L + PlasmaShieldSoundCadence.OVERHEAT_TO_FIRST_VENT_TICKS),
                "replacement equipment may vent after its own warning delay");
        cadence.clear(playerId);
        helper.assertTrue(!cadence.shouldPlayVent(playerId, replacementShield, 400L),
                "lifecycle cleanup must remove the emergency vent schedule");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void absorbedHitEmitsTypedSoundAndTrackingFeedback(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EmbeddedChannel channel = (EmbeddedChannel) player.connection.connection.channel();
        drainFeedbackPackets(channel);

        PlasmaShieldConfig.Stats stats = stats(PlasmaShieldType.LIGHT);
        ItemStack shield = shieldStack(PlasmaShieldType.LIGHT);
        PlasmaShieldState.write(shield, new PlasmaShieldState(3.0D, 0.0D, false, 0, 0));
        player.setItemSlot(EquipmentSlot.CHEST, shield);
        LivingHurtEvent hit = new LivingHurtEvent(
                player, helper.getLevel().damageSources().generic(), 10.0F);
        new PlasmaShieldHandler().onLivingHurt(hit);

        FeedbackPackets feedback = drainFeedbackPackets(channel);
        helper.assertTrue(feedback.sounds().size() == 1
                        && feedback.sounds().get(0) == ModEngineerSounds.PLASMA_SHIELD_HIT.get(),
                "an ordinary absorbed hit must emit exactly the plasma-shield hit sound");
        helper.assertTrue(feedback.hits().size() == 1,
                "an ordinary absorbed hit must emit exactly one tracking visual packet");
        PlasmaShieldHitS2C packet = feedback.hits().get(0);
        helper.assertTrue(packet.entityId() == player.getId()
                        && packet.typeId().equals(PlasmaShieldType.LIGHT.id())
                        && !packet.overloaded(),
                "tracking feedback must identify the hit player and equipped shield type");
        helper.assertTrue(close(packet.strength(),
                        PlasmaShieldVisualProfile.strengthForAbsorbedDamage(3.0D)),
                "feedback strength must use actual absorbed damage rather than incoming damage");
        helper.assertTrue(close(hit.getAmount(), 7.0D)
                        && close(PlasmaShieldState.read(shield, stats).shield(), 0.0D),
                "partial absorption must still preserve the underlying damage and energy result");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void inactiveShieldEmitsNoHitFeedback(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EmbeddedChannel channel = (EmbeddedChannel) player.connection.connection.channel();
        drainFeedbackPackets(channel);

        PlasmaShieldConfig.Stats stats = stats(PlasmaShieldType.NANO);
        ItemStack shield = shieldStack(PlasmaShieldType.NANO);
        PlasmaShieldState.write(shield, new PlasmaShieldState(
                stats.capacity(), stats.maxHeat(), true, 0, 0));
        player.setItemSlot(EquipmentSlot.CHEST, shield);
        LivingHurtEvent hit = new LivingHurtEvent(
                player, helper.getLevel().damageSources().generic(), 8.0F);
        new PlasmaShieldHandler().onLivingHurt(hit);

        FeedbackPackets feedback = drainFeedbackPackets(channel);
        helper.assertTrue(feedback.sounds().isEmpty() && feedback.hits().isEmpty(),
                "an already-overheated shield must not emit hit sound or visual feedback");
        helper.assertTrue(close(hit.getAmount(), 8.0D),
                "an inactive shield must let incoming damage pass through unchanged");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ordinaryCoolingDoesNotPlayEmergencySteam(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EmbeddedChannel channel = (EmbeddedChannel) player.connection.connection.channel();
        drainFeedbackPackets(channel);

        PlasmaShieldConfig.Stats stats = stats(PlasmaShieldType.NANO);
        ItemStack shield = shieldStack(PlasmaShieldType.NANO);
        PlasmaShieldState.write(shield, new PlasmaShieldState(
                stats.capacity() - 1.0D, 20.0D, false, 0, 0));
        player.setItemSlot(EquipmentSlot.CHEST, shield);
        PlasmaShieldHandler handler = new PlasmaShieldHandler();
        ServerLevel level = helper.getLevel();
        ServerLevelData levelData = (ServerLevelData) level.getLevelData();
        long originalGameTime = level.getGameTime();

        try {
            handler.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
            levelData.setGameTime(originalGameTime + EngineerConfig.PLASMA_SHIELD.stateTickInterval());
            handler.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));

            FeedbackPackets feedback = drainFeedbackPackets(channel);
            helper.assertTrue(feedback.sounds().isEmpty(),
                    "ordinary non-overheated cooling must not play the full emergency steam sound");
            helper.assertTrue(PlasmaShieldState.read(shield, stats).heat() < 20.0D,
                    "the negative sound assertion must still exercise a real cooling settlement");
        } finally {
            levelData.setGameTime(originalGameTime);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void overheatEdgeEmitsExactlyOnePositionalSound(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EmbeddedChannel channel = (EmbeddedChannel) player.connection.connection.channel();
        drainFeedbackPackets(channel);

        PlasmaShieldConfig.Stats stats = stats(PlasmaShieldType.LIGHT);
        ItemStack shield = shieldStack(PlasmaShieldType.LIGHT);
        PlasmaShieldState.write(shield, new PlasmaShieldState(
                stats.capacity(), stats.maxHeat() - 1.0D, false, 0, 0));
        player.setItemSlot(EquipmentSlot.CHEST, shield);
        PlasmaShieldHandler handler = new PlasmaShieldHandler();

        LivingHurtEvent boundaryHit = new LivingHurtEvent(
                player, helper.getLevel().damageSources().generic(), 10.0F);
        handler.onLivingHurt(boundaryHit);
        helper.assertTrue(PlasmaShieldState.read(shield, stats).overheated(),
                "boundary hit must enter overheat before the sound assertion");
        FeedbackPackets boundaryFeedback = drainFeedbackPackets(channel);
        helper.assertTrue(boundaryFeedback.sounds().size() == 1
                        && boundaryFeedback.sounds().get(0) == ModEngineerSounds.PLASMA_SHIELD_OVERHEAT.get(),
                "false-to-true overheat edge must emit only the dedicated shutdown sound");
        helper.assertTrue(boundaryFeedback.hits().size() == 1
                        && boundaryFeedback.hits().get(0).overloaded(),
                "the overheat-causing hit must still emit one overload visual packet");

        LivingHurtEvent repeatedHit = new LivingHurtEvent(
                player, helper.getLevel().damageSources().generic(), 10.0F);
        handler.onLivingHurt(repeatedHit);
        FeedbackPackets repeatedFeedback = drainFeedbackPackets(channel);
        helper.assertTrue(repeatedFeedback.sounds().isEmpty() && repeatedFeedback.hits().isEmpty(),
                "additional damage while already overheated must not replay sound or hit visuals");
        helper.succeed();
    }

    private static PlasmaShieldConfig.Stats stats(PlasmaShieldType type) {
        return EngineerConfig.PLASMA_SHIELD.stats(type);
    }

    private static ItemStack shieldStack(PlasmaShieldType type) {
        return new ItemStack(ModEngineerItems.plasmaShield(type).get());
    }

    private static PlasmaShieldConfig.Stats customStats(double capacity,
                                                         double maxHeat,
                                                         double restartHeat,
                                                         double heatPerDamage,
                                                         double coolingPerSecond,
                                                         double rechargePerSecond,
                                                         int rechargeDelayTicks,
                                                         int heatCoolDelayTicks,
                                                         double movementModifier) {
        return new PlasmaShieldConfig.Stats(
                capacity, maxHeat, restartHeat, heatPerDamage,
                coolingPerSecond, rechargePerSecond,
                rechargeDelayTicks, heatCoolDelayTicks, movementModifier);
    }

    private static PlasmaShieldState tickOneAtATime(PlasmaShieldState state,
                                                     PlasmaShieldConfig.Stats stats,
                                                     int ticks) {
        PlasmaShieldState result = state;
        for (int i = 0; i < ticks; i++) {
            result = PlasmaShieldState.tick(result, stats, 1);
        }
        return result;
    }

    private static void assertState(GameTestHelper helper,
                                    PlasmaShieldState state,
                                    double expectedShield,
                                    double expectedHeat,
                                    boolean expectedOverheated,
                                    String context) {
        helper.assertTrue(close(state.shield(), expectedShield), context + " shield mismatch: " + state.shield());
        helper.assertTrue(close(state.heat(), expectedHeat), context + " heat mismatch: " + state.heat());
        helper.assertTrue(state.overheated() == expectedOverheated,
                context + " overheated mismatch: " + state.overheated());
    }

    private static boolean close(double actual, double expected) {
        return Math.abs(actual - expected) < EPS;
    }

    private static FeedbackPackets drainFeedbackPackets(EmbeddedChannel channel) {
        ResourceLocation shieldChannel = new ResourceLocation(MiningConstants.MODID, "plasma_shield");
        List<SoundEvent> sounds = new ArrayList<>();
        List<PlasmaShieldHitS2C> hits = new ArrayList<>();
        Object message;
        while ((message = channel.readOutbound()) != null) {
            if (message instanceof ClientboundSoundPacket soundPacket) {
                sounds.add(soundPacket.getSound().value());
            } else if (message instanceof ClientboundCustomPayloadPacket payload
                    && payload.getIdentifier().equals(shieldChannel)) {
                FriendlyByteBuf copy = new FriendlyByteBuf(payload.getData().copy());
                try {
                    int discriminator = copy.readUnsignedByte();
                    if (discriminator == 1) {
                        hits.add(PlasmaShieldHitS2C.decode(copy));
                    }
                } finally {
                    copy.release();
                }
            }
            ReferenceCountUtil.release(message);
        }
        return new FeedbackPackets(List.copyOf(sounds), List.copyOf(hits));
    }

    private record FeedbackPackets(List<SoundEvent> sounds, List<PlasmaShieldHitS2C> hits) {
    }
}
