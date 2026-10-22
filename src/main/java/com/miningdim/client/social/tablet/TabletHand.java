package com.miningdim.client.social.tablet;

/**
 * What the player holds in the main hand when the tablet comes out (decided once, when it opens).
 * Guns and other items share the gun timeline; only guns play the sling and shoulder sounds.
 */
public enum TabletHand {
    /** A TaCZ or Superb Warfare gun. */
    GUN("gun"),
    /** Any other held item (medical kit, block …): gun timeline, cloth sounds. */
    ITEM("item"),
    /** Empty main hand: the vanilla arm lowers, shorter timeline. */
    EMPTY("empty");

    private final String previewName;

    TabletHand(String previewName) {
        this.previewName = previewName;
    }

    /** The preview's name ({@code gun} / {@code item} / {@code empty}). */
    public String previewName() {
        return previewName;
    }

    /** The hand of a preview name; anything unknown counts as a gun (preview {@code handOf}). */
    public static TabletHand byPreviewName(String name) {
        if ("empty".equals(name)) {
            return EMPTY;
        }
        return "item".equals(name) ? ITEM : GUN;
    }

    /**
     * Classification of the main hand (IMPL_PLAN D16): empty → {@link #EMPTY}; a TaCZ or SBW gun →
     * {@link #GUN}; anything else → {@link #ITEM}.
     */
    public static TabletHand classify(boolean emptyHand, boolean taczGun, boolean sbwGun) {
        if (emptyHand) {
            return EMPTY;
        }
        return taczGun || sbwGun ? GUN : ITEM;
    }

    /** Whether this hand runs the shorter empty-hand timeline. */
    public boolean empty() {
        return this == EMPTY;
    }
}
