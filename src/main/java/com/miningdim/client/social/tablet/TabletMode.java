package com.miningdim.client.social.tablet;

import java.util.Locale;

/** The client option {@code ui.tabletAnimation} (DESIGN 2.7). */
public enum TabletMode {
    /** Both hands lift the tablet (scheme A, falls back to B without a first-person hand pass). */
    FULL,
    /** The lit tablet slides in within 80 ms (map: zooms in within 80 ms). */
    QUICK,
    /** No animation; the screens still open on the key press. */
    OFF;

    /** {@code FULL} / {@code quick} / {@code off} (any case, surrounding blanks ignored), or {@code null}. */
    public static TabletMode parse(String value) {
        if (value == null) {
            return null;
        }
        String key = value.strip().toUpperCase(Locale.ROOT);
        for (TabletMode mode : values()) {
            if (mode.name().equals(key)) {
                return mode;
            }
        }
        return null;
    }
}
