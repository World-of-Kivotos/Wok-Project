package com.miningdim.job.tarot;

/** Shared server/client timing contract for the tarot card presentation. */
public final class TarotCastTiming {

    /** The orbit completes and the card becomes fully readable. */
    public static final int CARD_REVEAL_TICKS = 56;

    /**
     * Server-authoritative effect resolution. The eight-tick gap after reveal absorbs ordinary
     * packet latency so the card presentation reaches its climax before gameplay changes occur.
     */
    public static final int EFFECT_RESOLVE_TICKS = 64;

    private TarotCastTiming() {
    }
}
