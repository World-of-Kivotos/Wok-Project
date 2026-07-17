package com.miningdim.job.engineer.shield;

/** Common-side timing, colour and intensity contract for plasma-shield hit feedback. */
public final class PlasmaShieldVisualProfile {

    public static final int HIT_DURATION_TICKS = 10;
    public static final int OVERLOAD_DURATION_TICKS = 14;
    public static final float MIN_VISIBLE_STRENGTH = 0.38F;

    private static final double FULL_STRENGTH_DAMAGE = 12.0D;

    private PlasmaShieldVisualProfile() {
    }

    public static float strengthForAbsorbedDamage(double absorbedDamage) {
        if (!Double.isFinite(absorbedDamage) || absorbedDamage <= 0.0D) {
            throw new IllegalArgumentException("absorbedDamage must be finite and positive");
        }
        double normalized = Math.sqrt(Math.min(absorbedDamage, FULL_STRENGTH_DAMAGE)
                / FULL_STRENGTH_DAMAGE);
        return (float) (MIN_VISIBLE_STRENGTH + (1.0D - MIN_VISIBLE_STRENGTH) * normalized);
    }

    public static int durationTicks(boolean overloaded) {
        return overloaded ? OVERLOAD_DURATION_TICKS : HIT_DURATION_TICKS;
    }

    public static float alpha(float ageTicks, float strength, boolean overloaded) {
        int duration = durationTicks(overloaded);
        if (ageTicks < 0.0F || ageTicks >= duration) {
            return 0.0F;
        }
        float attack = Math.min(1.0F, (ageTicks + 0.35F) / 0.85F);
        float remaining = 1.0F - ageTicks / duration;
        float overloadGain = overloaded ? 0.12F : 0.0F;
        return Math.min(1.0F,
                attack * remaining * remaining * (0.58F + 0.30F * strength + overloadGain));
    }

    public static float scale(float ageTicks, float strength, boolean overloaded) {
        int duration = durationTicks(overloaded);
        if (ageTicks < 0.0F || ageTicks >= duration) {
            return 0.0F;
        }
        float progress = ageTicks / duration;
        float expansion = 1.0F - (float) Math.exp(-8.0F * progress);
        float overloadScale = overloaded ? 1.06F : 1.0F;
        return (0.90F + 0.10F * expansion) * (0.96F + 0.08F * strength) * overloadScale;
    }

    public static Style style(PlasmaShieldVariant variant) {
        return switch (variant.tier()) {
            case I -> new Style(0xD8E8EE, 0xFFFFFF);
            case II -> new Style(0x298DDE, 0x81CBFF);
            case III -> new Style(0x35B85E, 0xA2FFB8);
            case IV -> new Style(0xB348D2, 0xE59CFF);
            case V -> new Style(0xD9A928, 0xFFE68A);
            case VI -> new Style(0xD93B35, 0xFF8B7A);
        };
    }

    public record Style(int primaryRgb, int highlightRgb) {
    }
}
