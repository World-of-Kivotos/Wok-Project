package com.miningdim.client.social.tablet;

/**
 * One press, one open or close (DESIGN 2.2, preview {@code motion.createKeyLatch}): while the
 * terminal key is held its repeats neither close the screen it just opened nor reopen the one it
 * just closed. Opening or closing latches; only a release clears the latch.
 *
 * <p>Besides the release events, {@link #poll} clears the latch whenever the key is not physically
 * held (IMPL_PLAN D7): uiTest's {@code KeyMapping.click} and its direct {@code screen.keyReleased}
 * never produce a Forge release event.
 */
public final class TabletKeyLatch {
    private boolean latched;

    /**
     * A press arrives: returns whether it may open or close something (and latches if so). Repeats
     * and presses while latched are ignored.
     */
    public boolean press(boolean repeat) {
        if (repeat || latched) {
            return false;
        }
        latched = true;
        return true;
    }

    /** Latches without a press (something was opened or closed by the key some other way). */
    public void latch() {
        latched = true;
    }

    /** The key was released. */
    public void release() {
        latched = false;
    }

    /** Per tick: clears the latch when the key is no longer physically held. */
    public void poll(boolean physicallyDown) {
        if (!physicallyDown) {
            latched = false;
        }
    }

    public boolean latched() {
        return latched;
    }
}
