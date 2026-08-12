package com.miningdim.caseopening;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure integer probability logic. The winning result is decided before any client animation starts. */
public final class CaseRoller {

    public static final int REEL_SIZE = 40;
    public static final int STOP_INDEX = 35;

    @FunctionalInterface
    public interface BoundedRandom {
        int nextInt(int bound);
    }

    public record Reel(List<CaseSkin> entries, int stopIndex) {
        public Reel {
            entries = List.copyOf(entries);
            if (stopIndex < 0 || stopIndex >= entries.size()) {
                throw new IllegalArgumentException("reel stop index out of bounds: " + stopIndex);
            }
        }
    }

    private final BoundedRandom random;

    public CaseRoller() {
        SecureRandom secureRandom = new SecureRandom();
        this.random = secureRandom::nextInt;
    }

    public CaseRoller(BoundedRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public CaseSkin roll(CaseWeights weights) {
        int value = next(CaseWeights.TOTAL);
        int upper = 0;
        CaseRarity selected = null;
        for (CaseRarity rarity : CaseRarity.values()) {
            upper += weights.weight(rarity);
            if (value < upper) {
                selected = rarity;
                break;
            }
        }
        if (selected == null) {
            throw new IllegalStateException("case probability table did not cover roll " + value);
        }
        List<CaseSkin> pool = CaseCatalog.skins(selected);
        return pool.get(next(pool.size()));
    }

    public Reel buildReel(CaseWeights weights, CaseSkin result) {
        List<CaseSkin> entries = new ArrayList<>(REEL_SIZE);
        for (int i = 0; i < REEL_SIZE; i++) {
            entries.add(roll(weights));
        }
        entries.set(STOP_INDEX, result);
        return new Reel(entries, STOP_INDEX);
    }

    private int next(int bound) {
        int value = random.nextInt(bound);
        if (value < 0 || value >= bound) {
            throw new IllegalArgumentException("random source returned " + value + " outside [0," + bound + ")");
        }
        return value;
    }
}
