package com.miningdim.job.munitions.gunsmith;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 枪匠组件平衡规则的运行期快照。每次 datapack 重载都以完整不可变快照原子替换。
 */
public final class GunsmithComponentRules {

    private static volatile Map<GunsmithPartVariant, GunsmithComponentRule> current = defaults();

    private GunsmithComponentRules() {
    }

    public static GunsmithComponentRule get(GunsmithPartVariant variant) {
        GunsmithComponentRule rule = current.get(Objects.requireNonNull(variant, "variant"));
        if (rule == null) {
            throw new IllegalStateException("Gunsmith component rule is not loaded: " + variant.id());
        }
        return rule;
    }

    public static Map<GunsmithPartVariant, GunsmithComponentRule> snapshot() {
        return current;
    }

    public static void install(Map<GunsmithPartVariant, GunsmithComponentRule> rules) {
        Objects.requireNonNull(rules, "rules");
        EnumMap<GunsmithPartVariant, GunsmithComponentRule> checked = new EnumMap<>(GunsmithPartVariant.class);
        for (GunsmithPartVariant variant : GunsmithPartVariant.values()) {
            GunsmithComponentRule rule = rules.get(variant);
            if (rule == null) {
                throw new IllegalArgumentException("Missing gunsmith component rule: " + variant.id());
            }
            checked.put(variant, rule);
        }
        if (rules.size() != checked.size()) {
            throw new IllegalArgumentException("Gunsmith component rules contain unknown entries");
        }
        current = Map.copyOf(checked);
    }

    public static Map<GunsmithPartVariant, GunsmithComponentRule> defaults() {
        EnumMap<GunsmithPartVariant, GunsmithComponentRule> defaults = new EnumMap<>(GunsmithPartVariant.class);
        defaults.put(GunsmithPartVariant.BASE, GunsmithComponentRule.identity());
        defaults.put(GunsmithPartVariant.GEHENNA_GAS, GunsmithComponentRule.gehennaDefaults());
        defaults.put(GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS,
                GunsmithComponentRule.redWinterDefaults());
        defaults.put(GunsmithPartVariant.MK_AX_A_BOLT, GunsmithComponentRule.mkAxADefaults());
        defaults.put(GunsmithPartVariant.TRINITY_PRECISION_GRADUATED_BARREL,
                GunsmithComponentRule.trinityPrecisionGraduatedBarrelDefaults());
        defaults.put(GunsmithPartVariant.AR_THREE_ROUND_BURST_BOLT,
                GunsmithComponentRule.arThreeRoundBurstBoltDefaults());
        defaults.put(GunsmithPartVariant.TRINITY_PRECISION_GRADUATED_SNIPER_BARREL,
                GunsmithComponentRule.trinityPrecisionGraduatedSniperBarrelDefaults());
        defaults.put(GunsmithPartVariant.RED_WINTER_CHIXUE_A_BOLT,
                GunsmithComponentRule.redWinterChixueABoltDefaults());
        return Map.copyOf(defaults);
    }
}
