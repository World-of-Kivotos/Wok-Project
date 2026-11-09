package com.miningdim.client.social.tablet;

/** Which animation plays (preview {@code motion.pathFor}; the preview-only {@code NOW} is not ported). */
public enum TabletPath {
    /** Scheme A, both hands lift the tablet in the hand render pass. */
    A3D,
    /** Scheme B, the 2D device slides up from the bottom edge (A's automatic fallback). */
    B2D,
    /** The quick setting: the lit device slides in within 80 ms. */
    QUICK,
    /** No animation: the screen simply opens and closes. */
    OFF
}
