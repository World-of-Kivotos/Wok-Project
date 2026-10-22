package com.miningdim.client.social.tablet;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Pairs {@code ScreenEvent.Render.Pre} with its {@code Render.Post} per screen instance (IMPL_PLAN
 * 3: the Post event is posted even when the Pre was cancelled, and Forge's layered screens each
 * get their own pair). What the Pre changed is recorded with the frame it used, and the Post undoes
 * exactly that once. Client render thread only.
 *
 * @param <F> the frame the Pre drew with
 */
public final class TabletPassMarks<F> {
    /** What a Pre left open. */
    public enum Mark {
        /** Nothing to undo. */
        NONE,
        /** The page was not drawn (Pre cancelled); only the effects follow. */
        CANCELLED,
        /** A clip and a pose were pushed; pop them, then draw the effects. */
        PUSHED,
        /** Scheme A: a pose scaling the page into its rectangle was pushed; pop it, then the effects. */
        SCALED,
        /** Scheme A: the page went into the smooth-downscale target; lay it back, then the effects. */
        SMOOTH
    }

    /** One open pass. */
    public record Open<F>(Mark mark, F frame) {
        static final Open<?> NONE = new Open<>(Mark.NONE, null);
    }

    private final Map<Object, Open<F>> open = new IdentityHashMap<>();

    /** Records what the Pre of {@code screen} did (a second Pre before a Post replaces it). */
    public void begin(Object screen, Mark mark, F frame) {
        if (screen == null || mark == null || mark == Mark.NONE) {
            return;
        }
        open.put(screen, new Open<>(mark, frame));
    }

    /** The Post of {@code screen}: what to undo (and with which frame), at most once. */
    @SuppressWarnings("unchecked")
    public Open<F> end(Object screen) {
        Open<F> pass = screen == null ? null : open.remove(screen);
        return pass == null ? (Open<F>) Open.NONE : pass;
    }

    /** Whether a Pre of {@code screen} is still waiting for its Post. */
    public boolean isOpen(Object screen) {
        return screen != null && open.containsKey(screen);
    }

    /** Forgets every open pass (logout). */
    public void clear() {
        open.clear();
    }
}
