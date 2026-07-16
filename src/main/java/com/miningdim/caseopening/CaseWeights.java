package com.miningdim.caseopening;

/** Integer probability table. The total is fixed to 100000 to avoid floating-point boundary drift. */
public record CaseWeights(int blue, int purple, int pink, int red, int gold) {

    public static final int TOTAL = 100_000;
    public static final CaseWeights DEFAULT = new CaseWeights(79_110, 15_500, 4_000, 990, 400);

    public CaseWeights {
        if (blue <= 0 || purple <= 0 || pink <= 0 || red <= 0 || gold <= 0) {
            throw new IllegalArgumentException("all case rarity weights must be positive");
        }
        long sum = (long) blue + purple + pink + red + gold;
        if (sum != TOTAL) {
            throw new IllegalArgumentException("case rarity weights must total " + TOTAL + ", got " + sum);
        }
    }

    public int weight(CaseRarity rarity) {
        return switch (rarity) {
            case BLUE -> blue;
            case PURPLE -> purple;
            case PINK -> pink;
            case RED -> red;
            case GOLD -> gold;
        };
    }
}
