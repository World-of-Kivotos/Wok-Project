package com.miningdim.job.munitions.gunsmith;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

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

    private static void assertClose(GameTestHelper helper, double actual, double expected, String label) {
        helper.assertTrue(Math.abs(actual - expected) < 0.0000001D,
                label + " expected " + expected + " but was " + actual);
    }
}
