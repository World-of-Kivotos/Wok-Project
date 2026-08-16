package com.miningdim.job.munitions.gunsmith;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.ModMunitionsItems;
import com.miningdim.job.munitions.MunitionsSystem;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
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
import java.util.function.Function;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GunsmithStatGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "gunsmith_stats";

    private GunsmithStatGameTests() {
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
    public static void marksmanUsesFivePartsAndNeverResolvesHandling(GameTestHelper helper) {
        Function<GunsmithPressPart, Double> coefficients = part -> switch (part) {
            case HANDGUARD -> 1.05D;
            case CORE -> 1.10D;
            case STOCK -> 1.15D;
            case BOLT -> 1.20D;
            case BARREL -> 1.25D;
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
        assertClose(helper, GunsmithStat.HANDLING.coefficient(GunsmithPlatform.MARKSMAN,
                        part -> { throw new IllegalStateException("marksman handling must not resolve a part"); }), 1.0D,
                "marksman without a grip must use fixed handling");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sniperUsesFourPartsAndNeverResolvesRangeOrHandling(GameTestHelper helper) {
        Function<GunsmithPressPart, Double> coefficients = part -> switch (part) {
            case RECEIVER -> 1.25D;
            case STOCK -> 1.15D;
            case BARREL -> 1.10D;
            case HANDGUARD -> 1.30D;
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
        assertClose(helper, GunsmithStat.HANDLING.coefficient(GunsmithPlatform.SNIPER,
                        part -> { throw new IllegalStateException("sniper handling must not resolve a part"); }), 1.0D,
                "sniper without a grip must use fixed handling");
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
    public static void gehennaQualityCurvePinsFireRateVerticalRecoilAndFixedInaccuracy(GameTestHelper helper) {
        GunsmithPartVariant variant = GunsmithPartVariant.GEHENNA_HIGH_SPEED_GAS;
        double minimum = GunsmithPartQuality.COMMON.minCoefficient();
        double midpoint = (minimum + GunsmithPartQuality.LEGENDARY.maxCoefficient()) / 2.0D;
        double maximum = GunsmithPartQuality.LEGENDARY.maxCoefficient();

        assertClose(helper, variant.fireRateMultiplier(minimum), 1.0D,
                "minimum global quality coefficient must not increase fire rate");
        assertClose(helper, variant.verticalRecoilMultiplier(minimum), 1.0D,
                "minimum global quality coefficient must not increase vertical recoil");
        assertClose(helper, variant.inaccuracyMultiplier(minimum), 1.30D,
                "minimum global quality coefficient must retain the fixed +30% inaccuracy penalty");
        assertClose(helper, variant.fireRateMultiplier(midpoint), 1.125D,
                "midpoint global quality coefficient must increase fire rate by 12.5%");
        assertClose(helper, variant.verticalRecoilMultiplier(midpoint), 2.50D,
                "midpoint global quality coefficient must increase vertical recoil by 150%");
        assertClose(helper, variant.inaccuracyMultiplier(midpoint), 1.30D,
                "midpoint global quality coefficient must retain the fixed +30% inaccuracy penalty");
        assertClose(helper, variant.fireRateMultiplier(maximum), 1.25D,
                "maximum global quality coefficient must cap fire rate at +25%");
        assertClose(helper, variant.verticalRecoilMultiplier(maximum), 4.00D,
                "maximum global quality coefficient must cap vertical recoil at +300%");
        assertClose(helper, variant.inaccuracyMultiplier(maximum), 1.30D,
                "maximum global quality coefficient must retain the fixed +30% inaccuracy penalty");
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
        assertClose(helper, m.adsTime(), 1.0D / 1.25D, "ADS time must apply the inverse of handling");
        assertClose(helper, m.inaccuracy(), 1.0D / 1.10D, "inaccuracy must apply the inverse of spread");
        assertClose(helper, m.aimInaccuracy(), 1.0D / 1.25D, "aim inaccuracy must apply the inverse of handling");
        assertClose(helper, m.recoil(), 1.0D / 1.50D, "recoil must apply the inverse of recoil");
        assertClose(helper, m.verticalRecoil(), 1.0D / 1.50D,
                "legacy recoil coefficient must affect vertical recoil");
        assertClose(helper, m.horizontalRecoil(), 1.0D / 1.50D,
                "legacy recoil coefficient must affect horizontal recoil equally");
        assertClose(helper, m.fireRate(), 1.0D,
                "basic components must leave fire rate unchanged");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void roundsPerMinuteUsesTaCzCompatibleRounding(GameTestHelper helper) {
        GunsmithStatMultipliers multipliers = new GunsmithStatMultipliers(
                1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D,
                1.0D, 1.0D, 1.25D);

        helper.assertTrue(multipliers.roundsPerMinute(943) == 1179,
                "943 RPM at +25% must round 1178.75 to 1179");
        helper.assertTrue(multipliers.roundsPerMinute(750) == 938,
                "750 RPM at +25% must round the half value up to 938");
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
        helper.succeed();
    }

    private static void assertClose(GameTestHelper helper, double actual, double expected, String label) {
        helper.assertTrue(Math.abs(actual - expected) < 0.0000001D,
                label + " expected " + expected + " but was " + actual);
    }

    // ============================================================
    // F011 tooltip 不再崩客户端: 缓存 stats 与当前平衡表算不出一致时, from() 仍必须硬抛 (装配/伤害结算路径的铁律),
    // 但 onItemTooltip 只读展示要降级成一条提示而不是让异常冒到渲染线程。
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
                "a stats cache inconsistent with its installed parts must still make from() throw");

        helper.assertTrue(GunsmithGunStats.tryFrom(corrupted) == null,
                "tryFrom must degrade an unreadable gunsmith gun to null instead of throwing");
        helper.assertTrue(GunsmithGunStats.hasGunsmithData(corrupted),
                "a corrupted gunsmith gun must still be recognized as carrying gunsmith data");

        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        List<Component> tooltip = new ArrayList<>();
        ItemTooltipEvent event = new ItemTooltipEvent(corrupted, player, tooltip, TooltipFlag.Default.NORMAL);

        boolean tooltipThrew = false;
        try {
            new MunitionsSystem().onItemTooltip(event);
        } catch (RuntimeException unexpected) {
            tooltipThrew = true;
        }
        helper.assertFalse(tooltipThrew, "the tooltip handler must not crash the client on unreadable gunsmith stats");
        helper.assertTrue(event.getToolTip().size() == 1,
                "an unreadable gunsmith gun must append exactly one degraded tooltip row, got "
                        + event.getToolTip().size());
        helper.succeed();
    }

    private static ItemStack assembledM4Gun() {
        EnumMap<GunsmithPressPart, ItemStack> parts = new EnumMap<>(GunsmithPressPart.class);
        parts.put(GunsmithPressPart.CORE, part(GunsmithPressPart.CORE, GunsmithPartQuality.COMMON, 1.04D));
        parts.put(GunsmithPressPart.BARREL, part(GunsmithPressPart.BARREL, GunsmithPartQuality.IMPROVED, 1.10D));
        parts.put(GunsmithPressPart.BOLT, part(GunsmithPressPart.BOLT, GunsmithPartQuality.MILSPEC, 1.20D));
        parts.put(GunsmithPressPart.HANDGUARD, part(GunsmithPressPart.HANDGUARD, GunsmithPartQuality.PRECISION, 1.30D));
        parts.put(GunsmithPressPart.GRIP, part(GunsmithPressPart.GRIP, GunsmithPartQuality.LEGENDARY, 1.40D));
        parts.put(GunsmithPressPart.STOCK, part(GunsmithPressPart.STOCK, GunsmithPartQuality.IMPROVED, 1.08D));
        return GunsmithAssemblyRecipe.assemble(
                new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.M4A1),
                parts);
    }

    private static ItemStack part(GunsmithPressPart part, GunsmithPartQuality quality, double coefficient) {
        return GunsmithPartItem.createStack(
                ModMunitionsItems.GUNSMITH_PART.get(), GunsmithPlatform.AR, part,
                GunsmithPartVariant.BASIC, quality, coefficient);
    }
}
