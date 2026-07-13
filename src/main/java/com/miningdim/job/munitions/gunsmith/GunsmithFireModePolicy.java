package com.miningdim.job.munitions.gunsmith;

import java.util.List;
import java.util.Objects;

public final class GunsmithFireModePolicy {

    private GunsmithFireModePolicy() {
    }

    public static <T> T preserveAndSelectFirst(List<T> sourceFireModes, List<T> assembledFireModes) {
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
        if (!sourceFireModes.equals(assembledFireModes)) {
            throw new IllegalArgumentException("Assembled firearm fire modes must exactly match the source order");
        }
        return sourceFireModes.get(0);
    }
}
