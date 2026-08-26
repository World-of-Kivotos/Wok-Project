package com.miningdim.job.munitions.gunsmith;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.ModMunitionsItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

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
