package com.miningdim.job.munitions.gunsmith;

import java.util.List;
import java.util.Objects;

public final class GunsmithFireModePolicy {

    private GunsmithFireModePolicy() {
    }

    public static <T> T preserveAndSelectFirst(List<T> sourceFireModes, List<T> assembledFireModes) {
        validateLists(sourceFireModes, assembledFireModes);
        if (!sourceFireModes.equals(assembledFireModes)) {
            throw new IllegalArgumentException("Assembled firearm fire modes must exactly match the source order");
        }
        return sourceFireModes.get(0);
    }

    public static <T> T forceThreeRoundBurst(List<T> sourceFireModes, List<T> assembledFireModes,
                                             T burstMode, int burstCount, boolean continuousBurst) {
        validateLists(sourceFireModes, assembledFireModes);
        Objects.requireNonNull(burstMode, "burstMode");
        if (!assembledFireModes.equals(List.of(burstMode))) {
            throw new IllegalArgumentException("Three-round-burst firearm must expose only burst mode");
        }
        if (burstCount != 3) {
            throw new IllegalArgumentException("Three-round-burst firearm must fire exactly three rounds");
        }
        if (continuousBurst) {
            throw new IllegalArgumentException("Three-round-burst firearm must require a new trigger pull");
        }
        return burstMode;
    }

    private static <T> void validateLists(List<T> sourceFireModes, List<T> assembledFireModes) {
        Objects.requireNonNull(sourceFireModes, "sourceFireModes");
        Objects.requireNonNull(assembledFireModes, "assembledFireModes");
        if (sourceFireModes.isEmpty()) {
            throw new IllegalArgumentException("Source firearm fire modes must not be empty");
        }
        if (assembledFireModes.isEmpty()) {
            throw new IllegalArgumentException("Assembled firearm fire modes must not be empty");
        }
        if (sourceFireModes.stream().anyMatch(Objects::isNull)
                || assembledFireModes.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Fire mode lists must not contain null values");
        }
    }
}
