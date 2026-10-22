package com.miningdim.client.social.tablet;

import java.util.Set;

/**
 * Which HUD layers stand aside for the tablet (DESIGN 3.5 third round, review F7; IMPL_PLAN D14).
 * Pure rules, no Minecraft state.
 *
 * <ul>
 *   <li>A tablet screen open, the animation opening or shown: everything but the white list is
 *   hidden, on every path and setting (the D2 device does not cover the screen, so the hotbar and
 *   ammo count would peek out around it).</li>
 *   <li>Closing on scheme A: the crosshair returns at p 0.40, the rest at p 0.17 (K0, the moment
 *   the tablet leaves the picture).</li>
 *   <li>Closing on scheme B or the quick setting: everything returns on the first frame (the
 *   falling device is drawn above the HUD).</li>
 * </ul>
 *
 * <p>White list: vanilla chat, titles and subtitles (anim-spec {@code a.hud.keep}) and this
 * module's own {@code tablet_motion} layer. Beyond the plan (IMPL_PLAN D14) the vanilla full-screen
 * world tints stay as well (vignette, spyglass, pumpkin / powder snow, frostbite, portal, sleep
 * fade): they are part of the world behind the device, not HUD that peeks out beside it.
 */
public final class TabletHudPolicy {
    /** What is hidden this frame. */
    public record Visibility(boolean hudHidden, boolean crosshairHidden) {
        /** Nothing hidden. */
        public static final Visibility NONE = new Visibility(false, false);
        /** Everything but the white list hidden. */
        public static final Visibility ALL = new Visibility(true, true);

        public boolean any() {
            return hudHidden || crosshairHidden;
        }
    }

    /** The vanilla crosshair layer (restored on its own while closing on scheme A). */
    public static final String CROSSHAIR = "minecraft:crosshair";
    /** This module's animation layer. */
    public static final String OWN_LAYER = "wok_infantry:tablet_motion";
    /** Layers that are never hidden. */
    public static final Set<String> KEEP = Set.of(
            "minecraft:chat_panel", "minecraft:title_text", "minecraft:subtitles",
            "minecraft:vignette", "minecraft:spyglass", "minecraft:helmet", "minecraft:frostbite",
            "minecraft:portal", "minecraft:sleep_fade", OWN_LAYER);

    private TabletHudPolicy() {
    }

    /**
     * What is hidden.
     *
     * @param tabletScreenOpen a tablet-family screen is the current screen (hidden whatever the
     *                         animation says)
     */
    public static Visibility of(TabletMotion.State state, TabletPath path, double p,
                                boolean tabletScreenOpen) {
        if (tabletScreenOpen) {
            return Visibility.ALL;
        }
        if (state == null) {
            return Visibility.NONE;
        }
        return switch (state) {
            case OPENING, SHOWN -> Visibility.ALL;
            case CLOSING -> path == TabletPath.A3D
                    ? new Visibility(p > TabletAnimationModel.HUD_RESTORE_ALL_AT_CLOSE_P,
                    p > TabletAnimationModel.HUD_RESTORE_CROSSHAIR_AT_CLOSE_P)
                    : Visibility.NONE;
            case IDLE -> Visibility.NONE;
        };
    }

    /** The vanilla vignette layer (scheme A hides it with the HUD, see {@link #hidesVignette}). */
    public static final String VIGNETTE = "minecraft:vignette";

    /**
     * Scheme A (batch B3): the vanilla vignette darkens the screen edges over everything the world
     * pass drew, the 3D tablet included, but not over the 2D device that takes over at READ, so at
     * night the case would brighten by up to a third at p 0.61 (measured in the uiTest world). On
     * scheme A it therefore stands aside while the tablet moves: from the opening's first frame
     * until p = 1, and on the close until the HUD returns (p 0.17). Once shown it is back, so the
     * shown terminal is the same picture on every setting (and the p = 1 hand-over stays 0
     * difference); schemes B, quick and off keep it throughout (their device is 2D).
     */
    public static boolean hidesVignette(TabletMotion.State state, TabletPath path, double p,
                                        boolean tabletScreenOpen) {
        if (path != TabletPath.A3D || state == null) {
            return false;
        }
        return switch (state) {
            case OPENING -> p < 1.0D;
            case CLOSING -> of(state, path, p, tabletScreenOpen).hudHidden();
            default -> false;
        };
    }

    /** Whether the overlay {@code id} ({@code namespace:path}) is cancelled under {@code visibility}. */
    public static boolean cancels(String id, Visibility visibility) {
        if (visibility == null || !visibility.any() || id == null) {
            return false;
        }
        if (CROSSHAIR.equals(id)) {
            return visibility.crosshairHidden();
        }
        return visibility.hudHidden() && !KEEP.contains(id);
    }
}
