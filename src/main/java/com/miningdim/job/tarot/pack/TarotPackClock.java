package com.miningdim.job.tarot.pack;

import java.time.Instant;
import java.time.ZoneOffset;

/** UTC day clock shared by tarot pack purchase and derived-pack limits. */
public final class TarotPackClock {

    private TarotPackClock() {
    }

    public static long currentUtcDayStamp() {
        return Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toEpochDay();
    }
}
