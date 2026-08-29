package com.miningdim.job.munitions.gunsmith;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.ModMunitionsItems;
import com.miningdim.job.munitions.MunitionsSystem;
import com.miningdim.testutil.MockGameTestPlayers;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GunsmithStatGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "gunsmith_stats";

    private GunsmithStatGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void spreadsheetBaseProfilesMatchFirstWaveWeaponRows(GameTestHelper helper) {
        assertProfile(helper, GunsmithBlueprint.M1911, 0.10D, 1.50D,
                12.0D, 19.0F, 9.0D, 40.0F, 6.0D);
        assertProfile(helper, GunsmithBlueprint.UZI, 0.00D, 1.50D,
                12.0D, 15.0F, 9.0D, 30.0F, 6.0D);
        assertProfile(helper, GunsmithBlueprint.UMP45, 0.10D, 1.50D,
                12.0D, 25.0F, 9.0D, 35.0F, 6.0D);
        assertProfile(helper, GunsmithBlueprint.M870, 0.00D, 1.10D,
                40.0D, 18.0F, 25.0D, 32.0F, 15.0D);
        assertProfile(helper, GunsmithBlueprint.M4A1, 0.30D, 1.50D,
                8.0D, 35.0F, 7.0D, 60.0F, 6.0D);
        assertProfile(helper, GunsmithBlueprint.HK416D, 0.30D, 1.50D,
                8.0D, 25.0F, 7.0D, 60.0F, 6.0D);
        assertProfile(helper, GunsmithBlueprint.HK_MP5A5, 0.00D, 1.50D,
                12.0D, 25.0F, 9.0D, 40.0F, 6.0D);
        assertProfile(helper, GunsmithBlueprint.KAR98K, 0.40D, 1.50D,
                25.0D, 80.0F, 20.0D, GunsmithWeaponBaseProfile.INFINITE_DISTANCE, 20.0D);
        assertProfile(helper, GunsmithBlueprint.STERLING, 0.00D, 1.50D,
                12.0D, 15.0F, 9.0D, 30.0F, 6.0D);
        assertProfile(helper, GunsmithBlueprint.M1887_LONG, 0.00D, 1.10D,
                40.0D, 35.0F, 25.0D, 60.0F, 15.0D);
        assertProfile(helper, GunsmithBlueprint.SMLE_III, 0.40D, 1.50D,
                25.0D, 80.0F, 20.0D, GunsmithWeaponBaseProfile.INFINITE_DISTANCE, 20.0D);
        assertProfile(helper, GunsmithBlueprint.MPX, 0.00D, 1.50D,
                12.0D, 25.0F, 9.0D, 40.0F, 6.0D);
        assertProfile(helper, GunsmithBlueprint.KSG, 0.00D, 1.10D,
                40.0D, 35.0F, 25.0D, 60.0F, 15.0D);
        assertProfile(helper, GunsmithBlueprint.M700, 0.40D, 1.50D,
                25.0D, 120.0F, 20.0D, GunsmithWeaponBaseProfile.INFINITE_DISTANCE, 20.0D);
        assertProfile(helper, GunsmithBlueprint.M1014, 0.00D, 1.10D,
                40.0D, 25.0F, 25.0D, 30.0F, 15.0D);

        Set<GunsmithBlueprint> synchronizedBlueprints = Set.of(
                GunsmithBlueprint.M1911, GunsmithBlueprint.UZI, GunsmithBlueprint.UMP45,
                GunsmithBlueprint.M870, GunsmithBlueprint.M4A1, GunsmithBlueprint.HK416D,
                GunsmithBlueprint.HK_MP5A5, GunsmithBlueprint.STERLING,
                GunsmithBlueprint.M1887_LONG, GunsmithBlueprint.MPX,
                GunsmithBlueprint.KSG, GunsmithBlueprint.M1014,
                GunsmithBlueprint.KAR98K, GunsmithBlueprint.SMLE_III, GunsmithBlueprint.M700);
        helper.assertTrue(GunsmithWeaponBaseProfile.all().keySet().equals(synchronizedBlueprints),
                "only the 15 blueprints uniquely present in the workbook may receive synchronized values");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void spreadsheetProfilesResolveAllGunsmithIdsWithoutClaimingUnlistedGuns(GameTestHelper helper) {
        for (GunsmithBlueprint blueprint : GunsmithWeaponBaseProfile.all().keySet()) {
            helper.assertTrue(GunsmithWeaponBaseProfile.findByGunId(blueprint.gunId()).isPresent(),
                    "spreadsheet profile must resolve original gun id " + blueprint.gunId());
        }
        helper.assertTrue(GunsmithWeaponBaseProfile.findByGunId(
                        new ResourceLocation(MiningConstants.MODID, "m4a1_gunsmith")).isPresent(),
                "legacy M4 gunsmith id must use the M4 spreadsheet row");
        helper.assertTrue(GunsmithWeaponBaseProfile.findByGunId(
                        new ResourceLocation(MiningConstants.MODID, "m4a1_gunsmith_burst")).isPresent(),
                "M4 burst id must use the M4 spreadsheet row");
        helper.assertTrue(GunsmithWeaponBaseProfile.findByGunId(
                        new ResourceLocation(MiningConstants.MODID, "hk416d_gunsmith_burst")).isPresent(),
                "HK416D burst id must use the HK416D spreadsheet row");
        helper.assertTrue(GunsmithWeaponBaseProfile.findByGunId(GunsmithBlueprint.AK47.gunId()).isEmpty(),
                "a blueprint absent from the workbook must keep its gun-pack base data");
        helper.assertTrue(GunsmithWeaponBaseProfile.findByGunId(
                        new ResourceLocation(MiningConstants.MODID, "m16a4_gunsmith_burst")).isEmpty(),
                "an unlisted burst gun must keep its gun-pack base data");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bullpupUsesReceiverForDamageAndNeverResolvesRecoil(GameTestHelper helper) {
        Function<GunsmithPressPart, Double> coefficients = part -> switch (part) {
            case CORE -> 1.04D;
            case BARREL -> 1.10D;
            case HANDGUARD -> 1.30D;
            case GRIP -> 1.40D;
            case RECEIVER -> 1.25D;
            default -> throw new IllegalArgumentException("unexpected bullpup part: " + part);
        };

        assertClose(helper, GunsmithStat.DAMAGE.coefficient(GunsmithPlatform.BULLPUP, coefficients), 1.25D,
                "bullpup damage must come from receiver");
        assertClose(helper, GunsmithStat.HEADSHOT.coefficient(GunsmithPlatform.BULLPUP, coefficients), 1.10D,
                "bullpup headshot must come from barrel");
        assertClose(helper, GunsmithStat.RANGE.coefficient(GunsmithPlatform.BULLPUP, coefficients), 1.04D,
                "bullpup range must come from core");
        assertClose(helper, GunsmithStat.RECOIL.coefficient(GunsmithPlatform.BULLPUP,
                        part -> { throw new IllegalStateException("bullpup recoil must not resolve a part"); }), 1.0D,
                "bullpup receiver must not provide a recoil bonus");
        assertClose(helper, GunsmithStat.SPREAD.coefficient(GunsmithPlatform.BULLPUP, coefficients), 1.30D,
                "bullpup spread must come from handguard");
        assertClose(helper, GunsmithStat.HANDLING.coefficient(GunsmithPlatform.BULLPUP, coefficients), 1.40D,
                "bullpup handling must come from grip");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void marksmanUsesSixPartsIncludingGripHandling(GameTestHelper helper) {
        Function<GunsmithPressPart, Double> coefficients = part -> switch (part) {
            case HANDGUARD -> 1.05D;
            case CORE -> 1.10D;
            case STOCK -> 1.15D;
            case BOLT -> 1.20D;
            case BARREL -> 1.25D;
            case GRIP -> 1.40D;
            default -> throw new IllegalArgumentException("unexpected marksman part: " + part);
        };

        assertClose(helper, GunsmithStat.DAMAGE.coefficient(GunsmithPlatform.MARKSMAN, coefficients), 1.20D,
                "marksman damage must come from bolt");
        assertClose(helper, GunsmithStat.HEADSHOT.coefficient(GunsmithPlatform.MARKSMAN, coefficients), 1.25D,
                "marksman headshot must come from barrel");
        assertClose(helper, GunsmithStat.RANGE.coefficient(GunsmithPlatform.MARKSMAN, coefficients), 1.10D,
                "marksman range must come from core");
        assertClose(helper, GunsmithStat.RECOIL.coefficient(GunsmithPlatform.MARKSMAN, coefficients), 1.15D,
                "marksman recoil must come from stock");
        assertClose(helper, GunsmithStat.SPREAD.coefficient(GunsmithPlatform.MARKSMAN, coefficients), 1.05D,
                "marksman spread must come from handguard");
        assertClose(helper, GunsmithStat.HANDLING.coefficient(GunsmithPlatform.MARKSMAN, coefficients), 1.40D,
                "marksman handling must come from grip");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sniperUsesFivePartsAndFiringPinHandling(GameTestHelper helper) {
        Function<GunsmithPressPart, Double> coefficients = part -> switch (part) {
            case RECEIVER -> 1.25D;
            case STOCK -> 1.15D;
            case BARREL -> 1.10D;
            case HANDGUARD -> 1.30D;
            case FIRING_PIN -> 1.40D;
            default -> throw new IllegalArgumentException("unexpected sniper part: " + part);
        };

        assertClose(helper, GunsmithStat.DAMAGE.coefficient(GunsmithPlatform.SNIPER, coefficients), 1.25D,
                "sniper damage must come from receiver");
        assertClose(helper, GunsmithStat.HEADSHOT.coefficient(GunsmithPlatform.SNIPER, coefficients), 1.10D,
                "sniper headshot must come from barrel");
        assertClose(helper, GunsmithStat.RECOIL.coefficient(GunsmithPlatform.SNIPER, coefficients), 1.15D,
                "sniper recoil must come from stock");
        assertClose(helper, GunsmithStat.SPREAD.coefficient(GunsmithPlatform.SNIPER, coefficients), 1.30D,
                "sniper spread must come from handguard");
        assertClose(helper, GunsmithStat.RANGE.coefficient(GunsmithPlatform.SNIPER,
                        part -> { throw new IllegalStateException("sniper range must not resolve a part"); }), 1.0D,
                "sniper without a core must use fixed range");
        assertClose(helper, GunsmithStat.HANDLING.coefficient(GunsmithPlatform.SNIPER, coefficients), 1.40D,
                "sniper handling must come from firing pin");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void machineGunUsesBipodHandlingAndNeverResolvesRange(GameTestHelper helper) {
        Function<GunsmithPressPart, Double> coefficients = part -> switch (part) {
            case HANDGUARD -> 1.05D;
            case BOLT -> 1.10D;
            case BARREL -> 1.15D;
            case STOCK -> 1.20D;
            case BIPOD -> 1.25D;
            default -> throw new IllegalArgumentException("unexpected machine gun part: " + part);
        };

        assertClose(helper, GunsmithStat.DAMAGE.coefficient(GunsmithPlatform.MACHINE_GUN, coefficients), 1.10D,
                "machine gun damage must come from bolt");
        assertClose(helper, GunsmithStat.HEADSHOT.coefficient(GunsmithPlatform.MACHINE_GUN, coefficients), 1.15D,
                "machine gun headshot must come from barrel");
        assertClose(helper, GunsmithStat.RECOIL.coefficient(GunsmithPlatform.MACHINE_GUN, coefficients), 1.20D,
                "machine gun recoil must come from stock");
        assertClose(helper, GunsmithStat.SPREAD.coefficient(GunsmithPlatform.MACHINE_GUN, coefficients), 1.05D,
                "machine gun spread must come from handguard");
        assertClose(helper, GunsmithStat.HANDLING.coefficient(GunsmithPlatform.MACHINE_GUN, coefficients), 1.25D,
                "machine gun handling must come from bipod");
        assertClose(helper, GunsmithStat.RANGE.coefficient(GunsmithPlatform.MACHINE_GUN,
                        part -> { throw new IllegalStateException("machine gun range must not resolve a part"); }), 1.0D,
                "machine gun range must remain fixed");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shotgunUsesFourPartsAndNeverResolvesRangeOrHandling(GameTestHelper helper) {
        Function<GunsmithPressPart, Double> coefficients = part -> switch (part) {
            case STOCK -> 1.20D;
            case BARREL -> 1.15D;
            case BOLT -> 1.10D;
            case HANDGUARD -> 1.05D;
            default -> throw new IllegalArgumentException("unexpected shotgun part: " + part);
        };

        assertClose(helper, GunsmithStat.DAMAGE.coefficient(GunsmithPlatform.SHOTGUN, coefficients), 1.10D,
                "shotgun damage must come from bolt");
        assertClose(helper, GunsmithStat.HEADSHOT.coefficient(GunsmithPlatform.SHOTGUN, coefficients), 1.15D,
                "shotgun headshot must come from barrel");
        assertClose(helper, GunsmithStat.RECOIL.coefficient(GunsmithPlatform.SHOTGUN, coefficients), 1.20D,
                "shotgun recoil must come from stock");
        assertClose(helper, GunsmithStat.SPREAD.coefficient(GunsmithPlatform.SHOTGUN, coefficients), 1.05D,
                "shotgun spread must come from handguard");
        assertClose(helper, GunsmithStat.RANGE.coefficient(GunsmithPlatform.SHOTGUN,
                        part -> { throw new IllegalStateException("shotgun range must not resolve a part"); }), 1.0D,
                "shotgun without a gas system must use fixed range");
        assertClose(helper, GunsmithStat.HANDLING.coefficient(GunsmithPlatform.SHOTGUN,
                        part -> { throw new IllegalStateException("shotgun handling must not resolve a part"); }), 1.0D,
                "shotgun without a grip must use fixed handling");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void smgUsesFivePartsAndNeverResolvesRange(GameTestHelper helper) {
        Function<GunsmithPressPart, Double> coefficients = part -> switch (part) {
            case BARREL -> 1.10D;
            case STOCK -> 1.15D;
            case RECEIVER -> 1.25D;
            case HANDGUARD -> 1.30D;
            case GRIP -> 1.40D;
            default -> throw new IllegalArgumentException("unexpected SMG part: " + part);
        };

        assertClose(helper, GunsmithStat.DAMAGE.coefficient(GunsmithPlatform.SMG, coefficients), 1.25D,
                "SMG damage must come from receiver");
        assertClose(helper, GunsmithStat.HEADSHOT.coefficient(GunsmithPlatform.SMG, coefficients), 1.10D,
                "SMG headshot must come from barrel");
        assertClose(helper, GunsmithStat.RECOIL.coefficient(GunsmithPlatform.SMG, coefficients), 1.15D,
                "SMG recoil must come from stock");
        assertClose(helper, GunsmithStat.SPREAD.coefficient(GunsmithPlatform.SMG, coefficients), 1.30D,
                "SMG spread must come from handguard");
        assertClose(helper, GunsmithStat.HANDLING.coefficient(GunsmithPlatform.SMG, coefficients), 1.40D,
                "SMG handling must come from grip");
        assertClose(helper, GunsmithStat.RANGE.coefficient(GunsmithPlatform.SMG,
                        part -> { throw new IllegalStateException("SMG range must not resolve a part"); }), 1.0D,
                "SMG without a gas system must use fixed range");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void statMultipliersPinMultiplyVersusInverseDirection(GameTestHelper helper) {
        // 高 cap 关闭封顶, 只验方向 (审查 TQ-1): damage/headshot/range 直乘; ads/aim 走 inverse(handling);
        // inaccuracy 走 inverse(spread); recoil 走 inverse(recoil)。方向写反则对应断言必挂。
        GunsmithStatMultipliers m = GunsmithStatMultipliers.of(
                1.40D, 1.30D, 1.20D, 1.25D, 1.10D, 1.50D, 10.0D);
        assertClose(helper, m.damage(), 1.40D, "damage must apply directly");
        assertClose(helper, m.headshot(), 1.30D, "uncapped headshot must apply directly");
        assertClose(helper, m.effectiveRange(), 1.20D, "range must apply directly");
        assertClose(helper, m.ammoSpeed(), 1.0D, "ordinary components must not change projectile speed");
        assertClose(helper, m.armorIgnore(), 1.0D, "ordinary components must not change armor penetration");
        assertClose(helper, m.adsTime(), 1.0D / 1.25D, "ADS time must apply the inverse of handling");
        assertClose(helper, m.inaccuracy(), 1.0D / 1.10D, "inaccuracy must apply the inverse of spread");
        assertClose(helper, m.aimInaccuracy(), 1.0D / 1.25D, "aim inaccuracy must apply the inverse of handling");
        assertClose(helper, m.recoil(), 1.0D / 1.50D, "recoil must apply the inverse of recoil");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void headshotEquivalentMultiplierIsCappedAgainstCompounding(GameTestHelper helper) {
        // 双 LEGENDARY 1.5 x 1.5 = 2.25 的复利被 1.8 帽钳住: headshot 反解为 1.8/1.5 = 1.2, 躯干 damage 不动。(审查 TACZ-BAL-1)
        GunsmithStatMultipliers capped = GunsmithStatMultipliers.of(
                1.50D, 1.50D, 1.0D, 1.0D, 1.0D, 1.0D, 1.80D);
        assertClose(helper, capped.damage(), 1.50D, "body damage must be untouched by the headshot cap");
        assertClose(helper, capped.headshot(), 1.80D / 1.50D, "headshot must be reduced so damage x headshot equals the cap");
        assertClose(helper, capped.damage() * capped.headshot(), 1.80D,
                "compounded headshot-equivalent multiplier must equal the cap");

        // 复利未越帽时 headshot 原样施加 (1.20 x 1.30 = 1.56 <= 1.80)。
        GunsmithStatMultipliers underCap = GunsmithStatMultipliers.of(
                1.20D, 1.30D, 1.0D, 1.0D, 1.0D, 1.0D, 1.80D);
        assertClose(helper, underCap.headshot(), 1.30D, "headshot below the cap must apply directly");

        // 红冬传奇总伤害 3.0 = 枪机 1.5 x 导气 2.0；爆头帽只钳枪机 1.5 x 枪管 1.5，导气不得把爆头压到躯干以下。
        GunsmithStatMultipliers redWinter = GunsmithStatMultipliers.of(
                3.00D, 1.50D, 1.50D, 1.0D, 1.0D, 1.0D, 1.0D, 1.80D);
        assertClose(helper, redWinter.damage(), 3.00D, "special component damage must remain on body damage");
        assertClose(helper, redWinter.headshot(), 1.20D,
                "headshot cap must use the bolt coefficient rather than total special-component damage");
        helper.assertTrue(redWinter.headshot() >= 1.0D,
                "special component damage must never invert headshots below body damage");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void redEastHighPressureGasUsesQualityTableAndMultiplicativeRangePenalty(GameTestHelper helper) {
        double[] damage = {1.20D, 1.40D, 1.60D, 1.80D, 2.00D};
        double[] spread = {1.20D, 1.35D, 1.50D, 1.65D, 1.80D};
        double[] recoil = {2.00D, 2.00D, 2.00D, 2.00D, 2.00D};
        GunsmithPartVariant variant = GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS;
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            assertClose(helper, variant.damageMultiplier(quality), damage[quality.index()],
                    "red east damage table " + quality.id());
            assertClose(helper, variant.fireRateMultiplier(quality), 0.75D,
                    "red east fire rate must be fixed across qualities");
            assertClose(helper, variant.spreadMultiplier(quality), spread[quality.index()],
                    "red east spread table " + quality.id());
            assertClose(helper, variant.recoilMultiplier(quality), recoil[quality.index()],
                    "red east all-axis recoil table " + quality.id());
            assertClose(helper, variant.verticalRecoilMultiplier(quality), 3.00D,
                    "red east extra vertical recoil must be fixed across qualities");
            assertClose(helper, variant.rangeMultiplier(quality), 0.60D,
                    "red east gas must apply a 40 percent effective-range penalty");
        }
        assertClose(helper, variant.applyRangeMultiplier(1.43D, GunsmithPartQuality.LEGENDARY), 0.858D,
                "red east range penalty must preserve the core quality coefficient");
        helper.assertTrue(variant.supports(GunsmithPlatform.AK, GunsmithPressPart.CORE),
                "red east gas must support AK core");
        helper.assertTrue(!variant.supports(GunsmithPlatform.AR, GunsmithPressPart.CORE),
                "red east gas must remain AK-exclusive");
        helper.assertTrue(!variant.supports(GunsmithPlatform.AK, GunsmithPressPart.BARREL),
                "red east gas must remain a gas/core component");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void factionRecoilExtraCannotBeRemovedByPioneerA3(GameTestHelper helper) {
        double stockControl = 1.0D / 1.08D;
        double redEastYaw = stockControl * 2.00D;
        double redEastPitch = stockControl * 2.00D * 3.00D;

        assertClose(helper, GunsmithStatMultipliers.recoilAfterAttachment(
                        1.33D, stockControl, redEastYaw), stockControl * (1.33D + 1.00D),
                "Pioneer A3 yaw increase must preserve the red east horizontal extra");
        assertClose(helper, GunsmithStatMultipliers.recoilAfterAttachment(
                        0.33D, stockControl, redEastPitch), stockControl * (0.33D + 5.00D),
                "Pioneer A3 pitch reduction must not reduce the red east vertical extra");
        assertClose(helper, GunsmithStatMultipliers.recoilAfterAttachment(
                        0.33D, stockControl, stockControl * 0.75D), stockControl * 0.33D * 0.75D,
                "a faction recoil bonus must compound with the attachment instead of becoming a negative extra");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gehennaHighSpeedGasUsesFireRateTradeoffTable(GameTestHelper helper) {
        double[] fireRate = {1.05D, 1.10D, 1.15D, 1.20D, 1.25D};
        double[] spread = {1.03D, 1.06D, 1.09D, 1.12D, 1.15D};
        GunsmithPartVariant variant = GunsmithPartVariant.GEHENNA_GAS;
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            assertClose(helper, variant.damageMultiplier(quality), 1.0D,
                    "gehenna gas must not change damage");
            assertClose(helper, variant.fireRateMultiplier(quality), fireRate[quality.index()],
                    "gehenna fire-rate table " + quality.id());
            assertClose(helper, variant.rangeMultiplier(quality), 1.0D,
                    "gehenna gas must not add a range multiplier");
            assertClose(helper, variant.spreadMultiplier(quality), spread[quality.index()],
                    "gehenna spread table " + quality.id());
            assertClose(helper, variant.recoilMultiplier(quality), 2.0D,
                    "gehenna all-axis recoil must be fixed across qualities");
            assertClose(helper, variant.verticalRecoilMultiplier(quality), 1.0D,
                    "gehenna gas must not add a second vertical-only multiplier");
        }
        helper.assertTrue(variant.supports(GunsmithPlatform.AR, GunsmithPressPart.CORE),
                "gehenna gas must support AR core");
        helper.assertTrue(!variant.supports(GunsmithPlatform.AK, GunsmithPressPart.CORE),
                "gehenna gas must remain AR-exclusive");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mkAxABoltUsesAdoptedQualityTableAndExistingArBoltSlot(GameTestHelper helper) {
        double[] improvements = {1.05D, 1.10D, 1.15D, 1.20D, 1.25D};
        double[] fireRates = {1.05D, 1.05D, 1.05D, 1.05D, 1.05D};
        double[] control = {0.95D, 0.90D, 0.85D, 0.80D, 0.75D};
        GunsmithPartVariant variant = GunsmithPartVariant.MK_AX_A_BOLT;
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            assertClose(helper, variant.damageMultiplier(quality), improvements[quality.index()],
                    "MK-AX-A damage table " + quality.id());
            assertClose(helper, variant.fireRateMultiplier(quality), fireRates[quality.index()],
                    "MK-AX-A fire-rate table " + quality.id());
            assertClose(helper, variant.rangeMultiplier(quality), improvements[quality.index()],
                    "MK-AX-A effective-range table " + quality.id());
            assertClose(helper, variant.spreadMultiplier(quality), control[quality.index()],
                    "MK-AX-A spread table " + quality.id());
            assertClose(helper, variant.recoilMultiplier(quality), control[quality.index()],
                    "MK-AX-A all-axis recoil table " + quality.id());
            assertClose(helper, variant.verticalRecoilMultiplier(quality), 1.0D,
                    "MK-AX-A must not double-apply its vertical recoil reduction");
            assertClose(helper, variant.adsSpeedMultiplier(quality), 0.70D,
                    "MK-AX-A ADS speed penalty must remain fixed across qualities");
        }
        helper.assertTrue(variant.supports(GunsmithPlatform.AR, GunsmithPressPart.BOLT),
                "MK-AX-A must support the existing AR bolt slot");
        helper.assertTrue(!variant.supports(GunsmithPlatform.AR, GunsmithPressPart.CORE),
                "MK-AX-A must not enter the AR gas-system slot");
        helper.assertTrue(!variant.supports(GunsmithPlatform.AK, GunsmithPressPart.BOLT),
                "MK-AX-A must remain AR-exclusive");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chixueABoltUsesFixedAkTradeoffsAndExistingBoltSlot(GameTestHelper helper) {
        GunsmithPartVariant variant = GunsmithPartVariant.RED_WINTER_CHIXUE_A_BOLT;
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            assertClose(helper, variant.damageMultiplier(quality), 1.25D,
                    "Chixue-A damage bonus must remain fixed across qualities");
            assertClose(helper, variant.recoilMultiplier(quality), 1.35D,
                    "Chixue-A recoil penalty must remain fixed across qualities");
            assertClose(helper, variant.armorIgnoreMultiplier(quality), 0.75D,
                    "Chixue-A armor penetration penalty must remain fixed across qualities");
            helper.assertTrue(variant.customModelData(GunsmithPlatform.AK, GunsmithPressPart.BOLT, quality)
                            == 10701 + quality.index(),
                    "Chixue-A quality must use its reserved model code");
        }
        helper.assertTrue(variant.supports(GunsmithPlatform.AK, GunsmithPressPart.BOLT),
                "Chixue-A must use the existing AK bolt slot");
        helper.assertTrue(!variant.supports(GunsmithPlatform.AK, GunsmithPressPart.RECEIVER),
                "Chixue-A must not create a seventh AK receiver slot");
        helper.assertTrue(!variant.supports(GunsmithPlatform.AR, GunsmithPressPart.BOLT),
                "Chixue-A must remain AK-exclusive");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void threeRoundBurstBoltUsesFixedControlAndExistingArBoltSlot(GameTestHelper helper) {
        GunsmithPartVariant variant = GunsmithPartVariant.AR_THREE_ROUND_BURST_BOLT;
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            assertClose(helper, variant.spreadMultiplier(quality), 0.75D,
                    "three-round-burst bolt spread reduction must remain fixed across qualities");
            assertClose(helper, variant.recoilMultiplier(quality), 0.65D,
                    "three-round-burst bolt recoil reduction must remain fixed across qualities");
        }
        helper.assertTrue(variant.forcesBurstFireMode(),
                "three-round-burst bolt must force the dedicated fire mode");
        helper.assertTrue(variant.supports(GunsmithPlatform.AR, GunsmithPressPart.BOLT),
                "three-round-burst bolt must use the existing AR bolt slot");
        helper.assertTrue(!variant.supports(GunsmithPlatform.AR, GunsmithPressPart.RECEIVER),
                "three-round-burst bolt must not create a seventh AR receiver slot");
        helper.assertTrue(!variant.supports(GunsmithPlatform.AK, GunsmithPressPart.BOLT),
                "three-round-burst bolt must remain AR-exclusive");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void trinityPrecisionBarrelUsesFixedArBarrelTradeoffs(GameTestHelper helper) {
        GunsmithPartVariant variant = GunsmithPartVariant.TRINITY_PRECISION_GRADUATED_BARREL;
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            assertClose(helper, variant.damageMultiplier(quality), 1.0D,
                    "Trinity barrel must not change body damage");
            assertClose(helper, variant.headshotMultiplier(quality), 1.50D,
                    "Trinity headshot bonus must be fixed across qualities");
            assertClose(helper, variant.fireRateMultiplier(quality), 1.0D,
                    "Trinity barrel must not change fire rate");
            assertClose(helper, variant.rangeMultiplier(quality), 1.50D,
                    "Trinity range bonus must be fixed across qualities");
            assertClose(helper, variant.spreadMultiplier(quality), 0.70D,
                    "Trinity spread control must be fixed across qualities");
            assertClose(helper, variant.recoilMultiplier(quality), 1.0D,
                    "Trinity barrel must not change recoil");
            assertClose(helper, variant.adsSpeedMultiplier(quality), 0.50D,
                    "Trinity ADS penalty must be fixed across qualities");
        }
        helper.assertTrue(variant.supports(GunsmithPlatform.AR, GunsmithPressPart.BARREL),
                "Trinity barrel must support the AR barrel slot");
        helper.assertTrue(!variant.supports(GunsmithPlatform.AK, GunsmithPressPart.BARREL),
                "Trinity barrel must remain AR-exclusive");
        helper.assertTrue(!variant.supports(GunsmithPlatform.AR, GunsmithPressPart.BOLT),
                "Trinity barrel must not enter another AR slot");
        assertClose(helper, variant.maximumDurabilityMultiplier(), 0.70D,
                "Trinity barrel must reduce maximum durability by a fixed 30 percent");
        assertClose(helper, GunsmithPartVariant.BASE.maximumDurabilityMultiplier(), 1.0D,
                "base components must not alter maximum durability");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void trinityPrecisionSniperBarrelUsesFixedBallisticTradeoffs(GameTestHelper helper) {
        GunsmithPartVariant variant = GunsmithPartVariant.TRINITY_PRECISION_GRADUATED_SNIPER_BARREL;
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            assertClose(helper, variant.damageMultiplier(quality), 1.0D,
                    "Trinity sniper barrel must not change body damage");
            assertClose(helper, variant.headshotMultiplier(quality), 2.0D,
                    "Trinity sniper barrel headshot bonus must remain fixed across qualities");
            assertClose(helper, variant.fireRateMultiplier(quality), 1.0D,
                    "Trinity sniper barrel must not change fire rate");
            assertClose(helper, variant.rangeMultiplier(quality), 1.0D,
                    "Trinity sniper barrel must not substitute effective range for projectile speed");
            assertClose(helper, variant.ammoSpeedMultiplier(quality), 1.50D,
                    "Trinity sniper barrel projectile-speed bonus must remain fixed across qualities");
            assertClose(helper, variant.spreadMultiplier(quality), 0.67D,
                    "Trinity sniper barrel spread reduction must remain fixed across qualities");
            assertClose(helper, variant.recoilMultiplier(quality), 1.0D,
                    "Trinity sniper barrel must not change recoil");
            assertClose(helper, variant.verticalRecoilMultiplier(quality), 1.0D,
                    "Trinity sniper barrel must not change vertical recoil");
            assertClose(helper, variant.adsSpeedMultiplier(quality), 0.70D,
                    "Trinity sniper barrel ADS penalty must remain fixed across qualities");
        }
        helper.assertTrue(variant.supports(GunsmithPlatform.SNIPER, GunsmithPressPart.BARREL),
                "Trinity sniper barrel must use the sniper barrel slot");
        helper.assertTrue(!variant.supports(GunsmithPlatform.MARKSMAN, GunsmithPressPart.BARREL),
                "Trinity sniper barrel must remain exclusive to bolt-action rifles");
        helper.assertTrue(!variant.supports(GunsmithPlatform.SNIPER, GunsmithPressPart.FIRING_PIN),
                "Trinity sniper barrel must not enter the firing-pin slot");
        assertClose(helper, variant.maximumDurabilityMultiplier(), 1.0D,
                "Trinity sniper barrel must not inherit the AR barrel durability penalty");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void componentVariantsDeclareRarityIndependentFromCraftQuality(GameTestHelper helper) {
        helper.assertTrue(GunsmithPartVariant.BASE.rarity() == GunsmithPartRarity.STANDARD,
                "base components must use the standard rarity nameplate");
        helper.assertTrue(GunsmithPartVariant.AR_THREE_ROUND_BURST_BOLT.rarity()
                        == GunsmithPartRarity.MODIFIED,
                "three-round-burst bolt must remain below high-speed gas systems");
        helper.assertTrue(GunsmithPartVariant.GEHENNA_GAS.rarity() == GunsmithPartRarity.SPECIAL,
                "Gehenna high-speed gas must use the special rarity nameplate");
        helper.assertTrue(GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS.rarity()
                        == GunsmithPartRarity.SPECIAL,
                "Red Winter high-pressure gas must use the special rarity nameplate");
        helper.assertTrue(GunsmithPartVariant.RED_WINTER_CHIXUE_A_BOLT.rarity()
                        == GunsmithPartRarity.SPECIAL,
                "Chixue-A bolt must use the special rarity nameplate");
        helper.assertTrue(GunsmithPartVariant.TRINITY_PRECISION_GRADUATED_BARREL.rarity()
                        == GunsmithPartRarity.ADVANCED,
                "Trinity AR barrel must use the advanced rarity nameplate");
        helper.assertTrue(GunsmithPartVariant.TRINITY_PRECISION_GRADUATED_SNIPER_BARREL.rarity()
                        == GunsmithPartRarity.ADVANCED,
                "Trinity sniper barrel must use the advanced rarity nameplate");
        helper.assertTrue(GunsmithPartVariant.MK_AX_A_BOLT.rarity() == GunsmithPartRarity.PROTOTYPE,
                "MK-AX-A bolt must expose the prototype rarity nameplate");

        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            var stack = GunsmithPartItem.createStack(ModMunitionsItems.GUNSMITH_PART.get(),
                            GunsmithPlatform.AR, GunsmithPressPart.BOLT, quality,
                            GunsmithPartVariant.AR_THREE_ROUND_BURST_BOLT);
            helper.assertTrue(GunsmithPartItem.qualityOf(stack) == quality,
                    "craft quality must survive beside rarity " + quality.id());
            helper.assertTrue(GunsmithPartItem.rarityOf(stack) == GunsmithPartRarity.MODIFIED,
                    "component rarity must remain variant-derived for quality " + quality.id());
        }
        helper.succeed();
    }

    // ============================================================
    // 审查 68: 组件热重载规则的同步通道 (encode -> decode) 至今零测试。这张表是服务端权威、登录时整份下发给
    // 客户端的; 编解码顺序错一位不会有任何报错, 症状是"客户端 tooltip 上的组件数值与服务端实际结算的不是同一张表"。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void componentRuleSurvivesEncodeDecodeRoundTripBitForBit(GameTestHelper helper) {
        // 十个字段逐档取互不相同的值: 全填同一张表的话, 字段顺序写错也照样绿。
        double[] damage = {1.11D, 1.12D, 1.13D, 1.14D, 1.15D};
        double[] headshot = {1.21D, 1.22D, 1.23D, 1.24D, 1.25D};
        double[] fireRate = {1.31D, 1.32D, 1.33D, 1.34D, 1.35D};
        double[] effectiveRange = {1.41D, 1.42D, 1.43D, 1.44D, 1.45D};
        double[] ammoSpeed = {1.51D, 1.52D, 1.53D, 1.54D, 1.55D};
        double[] armorIgnore = {0.61D, 0.62D, 0.63D, 0.64D, 0.65D};
        double[] spread = {0.71D, 0.72D, 0.73D, 0.74D, 0.75D};
        double[] recoil = {0.81D, 0.82D, 0.83D, 0.84D, 0.85D};
        double[] verticalRecoil = {0.91D, 0.92D, 0.93D, 0.94D, 0.95D};
        double[] adsSpeed = {1.01D, 1.02D, 1.03D, 1.04D, 1.05D};

        GunsmithComponentRule original = new GunsmithComponentRule(
                qualityRow(damage), qualityRow(headshot), qualityRow(fireRate),
                GunsmithComponentRule.RangeOperation.REPLACE,
                qualityRow(effectiveRange), qualityRow(ammoSpeed), qualityRow(armorIgnore),
                qualityRow(spread), qualityRow(recoil), qualityRow(verticalRecoil), qualityRow(adsSpeed));

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.encode(buf);
        GunsmithComponentRule decoded = GunsmithComponentRule.decode(buf);
        helper.assertTrue(buf.readableBytes() == 0,
                "decode 必须把 encode 写进去的字节读干净, 剩 " + buf.readableBytes() + " 字节说明两侧长度对不上");
        // replace 语义会把其余槽位累积的射程整体顶掉; 掉回 multiply 是"数值没错但语义反了"的静默事故。
        helper.assertTrue(decoded.rangeOperation() == GunsmithComponentRule.RangeOperation.REPLACE,
                "射程运算语义必须往返无损, 实得 " + decoded.rangeOperation());

        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            int tier = quality.index();
            assertExact(helper, decoded.damage(quality), damage[tier], "damage " + quality.id());
            assertExact(helper, decoded.headshot(quality), headshot[tier], "headshot " + quality.id());
            assertExact(helper, decoded.fireRate(quality), fireRate[tier], "fireRate " + quality.id());
            assertExact(helper, decoded.effectiveRange(quality), effectiveRange[tier],
                    "effectiveRange " + quality.id());
            assertExact(helper, decoded.ammoSpeed(quality), ammoSpeed[tier], "ammoSpeed " + quality.id());
            assertExact(helper, decoded.armorIgnore(quality), armorIgnore[tier], "armorIgnore " + quality.id());
            assertExact(helper, decoded.spread(quality), spread[tier], "spread " + quality.id());
            assertExact(helper, decoded.recoil(quality), recoil[tier], "recoil " + quality.id());
            assertExact(helper, decoded.verticalRecoil(quality), verticalRecoil[tier],
                    "verticalRecoil " + quality.id());
            assertExact(helper, decoded.adsSpeed(quality), adsSpeed[tier], "adsSpeed " + quality.id());
        }
        helper.succeed();
    }

    /**
     * 越界值必须在 decode 那一刻就被拒。规则表来自 datapack 又走网络下发, 放行一个 0 或 NaN 乘子等于让
     * 一整类组件的伤害/散布静默变成 0 或 NaN, 而且是在客户端与服务端各自解码之后才发作。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void componentRuleDecodeRejectsOutOfRangeMultipliers(GameTestHelper helper) {
        // 闭区间两端必须放行, 否则"全都拒"也能让下面四条通过。
        assertExact(helper, decodeWithProbedDamage(0.05D).damage(GunsmithPartQuality.COMMON), 0.05D,
                "下界 0.05 必须原样放行");
        assertExact(helper, decodeWithProbedDamage(10.0D).damage(GunsmithPartQuality.COMMON), 10.0D,
                "上界 10.0 必须原样放行");

        assertDecodeRejects(helper, 0.04D, "低于下界 0.05 的乘子必须被 decode 拒掉");
        assertDecodeRejects(helper, 10.1D, "高于上界 10.0 的乘子必须被 decode 拒掉");
        assertDecodeRejects(helper, Double.NaN, "NaN 乘子必须被 decode 拒掉");
        assertDecodeRejects(helper, Double.POSITIVE_INFINITY, "无穷大乘子必须被 decode 拒掉");
        helper.succeed();
    }

    // ============================================================
    // 审查 25: 护木(spread) 与握把(handling) 是散布的两个方向分量, TACZ 1.1.8 的 INACCURACY 与 AIM_INACCURACY
    // 共用同一份缓存, 必须先合成再一次性写入 —— 分两次写会把势力组件的 spread 乘数平方。
    // 本条不 import 任何 com.tacz.* 类型: dev GameTest 不加载 TACZ, 一 import 就 NoClassDefFoundError。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void combinedInaccuracyMultipliesBothAxesWithTheFactionSpreadPenalty(GameTestHelper helper) {
        GunsmithGunStats stats = GunsmithGunStats.from(legendaryGehennaM4Gun());
        helper.assertTrue(stats != null, "带传奇格赫娜导气核心的 M4 必须是合法数据");
        // 前提校验: 三个输入分量各自来自不同零件, 值也互不相同, 合成表达式写错任一处都会偏离期望值。
        assertClose(helper, stats.spread(), 1.30D, "护木 (spread) 品质系数");
        assertClose(helper, stats.handling(), 1.40D, "握把 (handling) 品质系数");
        assertClose(helper, stats.specialSpread(), 1.15D, "传奇格赫娜导气的散布惩罚");

        GunsmithStatMultipliers multipliers = GunsmithStatMultipliers.of(stats, 10.0D);
        assertClose(helper, multipliers.inaccuracy(), 1.0D / 1.30D * 1.15D,
                "散布分量 = 护木控制的逆 x 势力组件散布惩罚");
        assertClose(helper, multipliers.aimInaccuracy(), 1.0D / 1.40D,
                "瞄准散布分量 = 握把控制的逆");
        assertClose(helper, multipliers.combinedInaccuracy(), 1.0D / 1.30D * 1.15D * (1.0D / 1.40D),
                "写进 TACZ 散布缓存的唯一乘子 = 两个方向分量的乘积");
        helper.assertTrue(Math.abs(multipliers.combinedInaccuracy() - multipliers.inaccuracy()) > 0.0000001D
                        && Math.abs(multipliers.combinedInaccuracy() - multipliers.aimInaccuracy()) > 0.0000001D,
                "合成值必须真的是两者相乘: 只发其中一个分量本条即挂");
        helper.succeed();
    }

    private static EnumMap<GunsmithPartQuality, Double> qualityRow(double[] values) {
        EnumMap<GunsmithPartQuality, Double> row = new EnumMap<>(GunsmithPartQuality.class);
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            row.put(quality, values[quality.index()]);
        }
        return row;
    }

    /**
     * 编出一份合法规则后, 直接覆盖它的头 8 个字节 —— encode 写的第一项就是 damage 的 common 档。
     * 这样探针既不必在测试里再抄一份字段顺序, 又能精确打在单个乘子上。
     */
    private static GunsmithComponentRule decodeWithProbedDamage(double damageCommon) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        GunsmithComponentRule.identity().encode(buf);
        buf.setDouble(0, damageCommon);
        return GunsmithComponentRule.decode(buf);
    }

    private static void assertDecodeRejects(GameTestHelper helper, double damageCommon, String message) {
        boolean threw = false;
        try {
            decodeWithProbedDamage(damageCommon);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw, message);
    }

    private static ItemStack legendaryGehennaM4Gun() {
        EnumMap<GunsmithPressPart, ItemStack> parts = new EnumMap<>(GunsmithPressPart.class);
        parts.put(GunsmithPressPart.CORE, GunsmithPartItem.createStack(
                ModMunitionsItems.GUNSMITH_PART.get(), GunsmithPlatform.AR, GunsmithPressPart.CORE,
                GunsmithPartQuality.LEGENDARY, GunsmithPartVariant.GEHENNA_GAS));
        parts.put(GunsmithPressPart.BARREL, arPart(GunsmithPressPart.BARREL, GunsmithPartQuality.IMPROVED, 1.10D));
        parts.put(GunsmithPressPart.BOLT, arPart(GunsmithPressPart.BOLT, GunsmithPartQuality.MILSPEC, 1.20D));
        parts.put(GunsmithPressPart.HANDGUARD,
                arPart(GunsmithPressPart.HANDGUARD, GunsmithPartQuality.PRECISION, 1.30D));
        parts.put(GunsmithPressPart.GRIP, arPart(GunsmithPressPart.GRIP, GunsmithPartQuality.LEGENDARY, 1.40D));
        parts.put(GunsmithPressPart.STOCK, arPart(GunsmithPressPart.STOCK, GunsmithPartQuality.IMPROVED, 1.08D));
        return GunsmithAssemblyRecipe.assemble(
                new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.M4A1),
                parts);
    }

    /** 线上通道的往返必须逐位无损, 故这里用 Double.compare 而不是容差比较。 */
    private static void assertExact(GameTestHelper helper, double actual, double expected, String label) {
        helper.assertTrue(Double.compare(actual, expected) == 0,
                label + " 必须逐位等于 " + expected + ", 实得 " + actual);
    }

    // ============================================================
    // F011 tooltip 不得因老枪读不出来而崩客户端: 缓存 stats 与当前平衡表算不出一致时 from() 仍必须硬抛
    // (装配 / 冲压 / 伤害结算路径需要这份硬校验炸出畸形数据), 但 onItemTooltip 跑在客户端渲染线程、外层没有
    // 任何 Controller 兜底, 只读展示必须降级成一条提示。反面同样要守: 主线真实写出的 v5 势力枪 (range 缓存
    // 1.0 与 1.43 的核心对不上) 是合法存量数据, 绝不能被当成"读不出来"降级成一行红字。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tooltipDegradesInsteadOfCrashingOnUnreadableStats(GameTestHelper helper) {
        ItemStack legit = assembledM4Gun();
        helper.assertTrue(GunsmithGunStats.from(legit) != null,
                "a freshly assembled gun must be readable before corruption");

        ItemStack corrupted = legit.copy();
        CompoundTag corruptedStats = corrupted.getOrCreateTag()
                .getCompound(GunsmithGunStats.ROOT_KEY)
                .getCompound(GunsmithGunStats.STATS_KEY);
        corruptedStats.putDouble("damage", corruptedStats.getDouble("damage") + 0.01D);

        boolean fromThrew = false;
        try {
            GunsmithGunStats.from(corrupted);
        } catch (IllegalArgumentException expected) {
            fromThrew = true;
        }
        helper.assertTrue(fromThrew,
                "a current-version stats cache inconsistent with its installed parts must still make from() throw");
        helper.assertTrue(GunsmithGunStats.tryFrom(corrupted) == null,
                "tryFrom must degrade an unreadable gunsmith gun to null instead of throwing");
        helper.assertTrue(GunsmithGunStats.hasGunsmithData(corrupted),
                "a corrupted gunsmith gun must still be recognized as carrying gunsmith data");

        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        List<Component> tooltip = new ArrayList<>();
        ItemTooltipEvent event = new ItemTooltipEvent(corrupted, player, tooltip, TooltipFlag.Default.NORMAL);
        // 这里刻意不裹 try/catch: 处理器一旦抛异常, GameTest 直接判红, 这就是"不得崩渲染线程"的断言本身。
        new MunitionsSystem().onItemTooltip(event);
        helper.assertTrue(event.getToolTip().size() == 1,
                "an unreadable gunsmith gun must append exactly one degraded tooltip row, got "
                        + event.getToolTip().size());
        helper.assertTrue(hasTooltipRow(event.getToolTip(), "tooltip.miningdim.gunsmith.stats_unreadable"),
                "the single degraded row must be the unreadable-stats notice");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tooltipKeepsRenderingLegacyGehennaGunsInsteadOfDegradingThem(GameTestHelper helper) {
        ItemStack legacyGehenna = legacyV5GehennaGun();
        helper.assertTrue(GunsmithGunStats.tryFrom(legacyGehenna) != null,
                "a real v5 Gehenna gun (range cache 1.0 against a 1.43 core) must stay readable");

        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        List<Component> tooltip = new ArrayList<>();
        ItemTooltipEvent event = new ItemTooltipEvent(legacyGehenna, player, tooltip, TooltipFlag.Default.NORMAL);
        new MunitionsSystem().onItemTooltip(event);
        helper.assertFalse(event.getToolTip().isEmpty(),
                "a readable gunsmith gun must still receive its tooltip rows");
        helper.assertFalse(hasTooltipRow(event.getToolTip(), "tooltip.miningdim.gunsmith.stats_unreadable"),
                "a legitimate legacy faction gun must never be shown as unreadable");
        helper.succeed();
    }

    /** 按翻译键判定, 不比字面文本: 服务端不加载 mod 语言表, 文本会退化成键本身。 */
    private static boolean hasTooltipRow(List<Component> tooltip, String translationKey) {
        for (Component row : tooltip) {
            if (row.getContents() instanceof TranslatableContents contents
                    && translationKey.equals(contents.getKey())) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack assembledM4Gun() {
        EnumMap<GunsmithPressPart, ItemStack> parts = new EnumMap<>(GunsmithPressPart.class);
        parts.put(GunsmithPressPart.CORE, arPart(GunsmithPressPart.CORE, GunsmithPartQuality.COMMON, 1.04D));
        parts.put(GunsmithPressPart.BARREL, arPart(GunsmithPressPart.BARREL, GunsmithPartQuality.IMPROVED, 1.10D));
        parts.put(GunsmithPressPart.BOLT, arPart(GunsmithPressPart.BOLT, GunsmithPartQuality.MILSPEC, 1.20D));
        parts.put(GunsmithPressPart.HANDGUARD,
                arPart(GunsmithPressPart.HANDGUARD, GunsmithPartQuality.PRECISION, 1.30D));
        parts.put(GunsmithPressPart.GRIP, arPart(GunsmithPressPart.GRIP, GunsmithPartQuality.LEGENDARY, 1.40D));
        parts.put(GunsmithPressPart.STOCK, arPart(GunsmithPressPart.STOCK, GunsmithPartQuality.IMPROVED, 1.08D));
        return GunsmithAssemblyRecipe.assemble(
                new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.M4A1),
                parts);
    }

    private static ItemStack arPart(GunsmithPressPart part, GunsmithPartQuality quality, double coefficient) {
        return GunsmithPartItem.createStack(ModMunitionsItems.GUNSMITH_PART.get(),
                GunsmithPlatform.AR, part, quality, GunsmithPartVariant.BASE, coefficient);
    }

    /**
     * 手写一把主线 v5 格赫娜 M4 的 NBT: 型号 id 是当时发布的 "gehenna_high_speed_gas", 核心系数 1.43, 而
     * Stats 里的 range 被主线的写入公式强制成 1.0。fixture 必须这样手写而不是拿当前写入器造再改版本号 ——
     * 后者写不出这份"缓存与部件对不上"的形态, 而这正是本用例要守的那一类存量数据。
     */
    private static ItemStack legacyV5GehennaGun() {
        CompoundTag parts = new CompoundTag();
        parts.put("core", legacyPartTag("gehenna_high_speed_gas", "legendary", 1.43D));
        parts.put("barrel", legacyPartTag("basic", "improved", 1.10D));
        parts.put("bolt", legacyPartTag("basic", "milspec", 1.20D));
        parts.put("handguard", legacyPartTag("basic", "precision", 1.30D));
        parts.put("grip", legacyPartTag("basic", "legendary", 1.40D));
        parts.put("stock", legacyPartTag("basic", "improved", 1.08D));

        CompoundTag stats = new CompoundTag();
        stats.putDouble("damage", 1.20D);
        stats.putDouble("headshot", 1.10D);
        stats.putDouble("range", 1.00D);
        stats.putDouble("recoil", 1.08D);
        stats.putDouble("spread", 1.30D);
        stats.putDouble("handling", 1.40D);
        stats.putDouble("average", 7.51D / 6.0D);
        stats.putDouble("fireRate", 1.25D);
        stats.putDouble("verticalRecoil", 1.0D / 1.08D);
        stats.putDouble("inaccuracy", 1.0D / 1.30D);

        CompoundTag root = new CompoundTag();
        root.putInt(GunsmithGunStats.VERSION_KEY, 5);
        root.putString("template", "m4a1");
        root.putString("platform", "ar");
        root.putString("gunId", "tacz:m4a1");
        root.put(GunsmithGunStats.PARTS_KEY, parts);
        root.put(GunsmithGunStats.STATS_KEY, stats);

        ItemStack stack = new ItemStack(Items.IRON_HOE);
        stack.getOrCreateTag().put(GunsmithGunStats.ROOT_KEY, root);
        return stack;
    }

    private static CompoundTag legacyPartTag(String variantId, String qualityId, double coefficient) {
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", variantId);
        tag.putString("quality", qualityId);
        tag.putDouble("coefficient", coefficient);
        return tag;
    }

    private static void assertClose(GameTestHelper helper, double actual, double expected, String label) {
        helper.assertTrue(Math.abs(actual - expected) < 0.0000001D,
                label + " expected " + expected + " but was " + actual);
    }

    private static void assertProfile(GameTestHelper helper, GunsmithBlueprint blueprint,
                                      double armorIgnore, double headshot,
                                      double firstDamage, float firstRange,
                                      double secondDamage, float secondRange, double thirdDamage) {
        GunsmithWeaponBaseProfile profile = GunsmithWeaponBaseProfile.find(blueprint)
                .orElseThrow(() -> new IllegalStateException("missing profile for " + blueprint.gunId()));
        assertClose(helper, profile.armorIgnore(), armorIgnore, blueprint + " armor ignore");
        assertClose(helper, profile.headshotMultiplier(), headshot, blueprint + " headshot");
        assertClose(helper, profile.damage(), firstDamage, blueprint + " base damage");
        assertClose(helper, profile.effectiveRange(), firstRange, blueprint + " effective range");
        int expectedPointCount = secondRange == GunsmithWeaponBaseProfile.INFINITE_DISTANCE
                && Double.compare(secondDamage, thirdDamage) == 0 ? 2 : 3;
        helper.assertTrue(profile.damageCurve().size() == expectedPointCount,
                blueprint + " damage curve size");
        assertDamagePoint(helper, blueprint, profile.damageCurve().get(0), firstRange, firstDamage, 1);
        assertDamagePoint(helper, blueprint, profile.damageCurve().get(1), secondRange, secondDamage, 2);
        if (expectedPointCount == 3) {
            assertDamagePoint(helper, blueprint, profile.damageCurve().get(2),
                    GunsmithWeaponBaseProfile.INFINITE_DISTANCE, thirdDamage, 3);
        }
    }

    private static void assertDamagePoint(GameTestHelper helper, GunsmithBlueprint blueprint,
                                          GunsmithWeaponBaseProfile.DamagePoint point,
                                          float distance, double damage, int index) {
        assertClose(helper, point.distance(), distance, blueprint + " distance point " + index);
        assertClose(helper, point.damage(), damage, blueprint + " damage point " + index);
    }
}
