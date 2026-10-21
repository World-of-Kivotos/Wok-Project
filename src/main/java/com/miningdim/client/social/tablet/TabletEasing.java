package com.miningdim.client.social.tablet;

/**
 * Easing curves of the tablet animation, one to one with the preview's {@code TA.ease}
 * ({@code ui-preview/tablet-anim/page/anim-spec.js} 534–545). Every curve clamps its input to
 * [0, 1] first.
 */
public enum TabletEasing {
    LINEAR("linear"),
    EASE_OUT_CUBIC("easeOutCubic"),
    EASE_IN_OUT_CUBIC("easeInOutCubic"),
    EASE_IN_OUT_SINE("easeInOutSine"),
    EASE_OUT_QUAD("easeOutQuad"),
    EASE_IN_QUAD("easeInQuad"),
    /** Minimum jerk (smootherstep 10x³ − 15x⁴ + 6x⁵): the speed profile of a reaching hand. */
    MIN_JERK("minJerk");

    private final String previewName;

    TabletEasing(String previewName) {
        this.previewName = previewName;
    }

    /** The preview's name of this curve ({@code easeOutCubic} …). */
    public String previewName() {
        return previewName;
    }

    /** The curve of the preview name, or {@code null}. */
    public static TabletEasing byPreviewName(String name) {
        for (TabletEasing easing : values()) {
            if (easing.previewName.equals(name)) {
                return easing;
            }
        }
        return null;
    }

    public static double clamp01(double x) {
        return x < 0.0D ? 0.0D : x > 1.0D ? 1.0D : x;
    }

    public double apply(double input) {
        double x = clamp01(input);
        return switch (this) {
            case LINEAR -> x;
            case EASE_OUT_CUBIC -> 1.0D - Math.pow(1.0D - x, 3);
            case EASE_IN_OUT_CUBIC -> x < 0.5D ? 4.0D * x * x * x
                    : 1.0D - Math.pow(-2.0D * x + 2.0D, 3) / 2.0D;
            case EASE_IN_OUT_SINE -> -(Math.cos(Math.PI * x) - 1.0D) / 2.0D;
            case EASE_OUT_QUAD -> 1.0D - (1.0D - x) * (1.0D - x);
            case EASE_IN_QUAD -> x * x;
            case MIN_JERK -> x * x * x * (10.0D + x * (-15.0D + 6.0D * x));
        };
    }
}
