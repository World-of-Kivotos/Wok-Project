package com.miningdim.job.chef;

/** Server-authoritative hold-to-heat state and post-warmup heat score. */
public final class ChefHeatGame {
    public static final int HEAT_MAX = 200;
    public static final int GREEN_LOW = 120;
    public static final int GREEN_HIGH = 160;

    private int heat;
    private int scoredTicks;
    private int greenTicks;
    private boolean heating;
    private boolean validControlInput;

    public int heat() {
        return heat;
    }

    public boolean heating() {
        return heating;
    }

    public boolean hasValidControlInput() {
        return validControlInput;
    }

    /** Advances one authoritative tick. Only ticks after the warmup contribute to the score. */
    public void tick(boolean scoring) {
        if (heating) {
            heat = Math.min(ChefConfig.heatMax(), heat + ChefConfig.heatRisePerTick());
        } else {
            heat = Math.max(0, heat - ChefConfig.heatFallPerTick());
        }
        if (scoring) {
            scoredTicks++;
            if (heat >= ChefConfig.heatGreenStart() && heat <= ChefConfig.heatGreenEnd()) {
                greenTicks++;
            }
        }
    }

    public boolean press() {
        if (heating) {
            return false;
        }
        heating = true;
        validControlInput = true;
        return true;
    }

    public boolean release() {
        if (!heating) {
            return false;
        }
        heating = false;
        validControlInput = true;
        return true;
    }

    public double accuracyScore() {
        return scoredTicks == 0 ? 0.0D : (double) greenTicks / scoredTicks;
    }

    public void reset() {
        heat = 0;
        scoredTicks = 0;
        greenTicks = 0;
        heating = false;
        validControlInput = false;
    }
}
