package com.miningdim.client.social.tablet;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One sound cue of the animation (preview {@code a.sfx.cues}, {@code b.sfx}, {@code b.map.sfx}).
 *
 * @param name       sound name; the event is {@code wok_infantry:tablet.<name>}
 * @param dir        the direction it plays in
 * @param atP        plays when the progress crosses this value in {@code dir}; NaN = only on the
 *                   start frame (see {@code startRange})
 * @param startRange plays on the first frame of an animation in {@code dir} that starts inside
 *                   [lo, hi]; {@code null} when it has none
 * @param hands      the hands it plays for
 * @param link       "ready": the controller plays it only with a battle link, otherwise "search"
 */
public record TabletCue(String name, Dir dir, double atP, double[] startRange,
                        Set<TabletHand> hands, boolean link) {
    /** Direction of a cue (and of a sound event). */
    public enum Dir {
        OPEN, CLOSE;

        /** {@code open} / {@code close}, the preview's spelling. */
        public String previewName() {
            return this == OPEN ? "open" : "close";
        }
    }

    public TabletCue {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dir, "dir");
        startRange = startRange == null ? null : new double[]{startRange[0], startRange[1]};
        hands = Collections.unmodifiableSet(hands == null || hands.isEmpty()
                ? EnumSet.allOf(TabletHand.class) : EnumSet.copyOf(hands));
    }

    /** Whether the cue is fired by crossing {@link #atP} (otherwise only on the start frame). */
    public boolean hasAtP() {
        return !Double.isNaN(atP);
    }

    @Override
    public double[] startRange() {
        return startRange == null ? null : startRange.clone();
    }

    /** Whether the start frame at {@code p} lies in {@link #startRange} (±1e-9). */
    public boolean startsAt(double p) {
        return startRange != null && p >= startRange[0] - 1e-9 && p <= startRange[1] + 1e-9;
    }

    public boolean playsFor(TabletHand hand) {
        return hands.contains(hand);
    }
}
