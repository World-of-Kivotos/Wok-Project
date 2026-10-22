package com.miningdim.client.social.tablet;

import com.miningdim.client.social.tablet.TabletCue.Dir;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The sound cue tables (preview {@code a.sfx.cues}, {@code b.sfx}, {@code b.map.sfx}), in the
 * preview's order (cues fired on one frame are emitted in this order).
 *
 * <p>Java patch (IMPL_PLAN D3, review leftover ①): the stow cue is split by hand. Guns and items
 * keep atP 0.52; the empty hand fires at 0.60, so the main hit (350 ms into the file) lands 2.0 ms
 * after K0 instead of 71.8 ms late on the shorter empty-hand C1 close.
 */
public final class TabletCues {
    /** The empty-hand stow trigger (preview {@code meta.javaPatches[0].emptyAtP}). */
    public static final double STOW_EMPTY_AT_P = 0.60D;
    /** The gun / item stow trigger (anim-spec). */
    public static final double STOW_AT_P = 0.52D;

    private static final double NONE = Double.NaN;

    /** Scheme A: every cue, patched. */
    public static final List<TabletCue> A3D = List.of(
            cue("holster", Dir.OPEN, 0, null, EnumSet.of(TabletHand.GUN), false),
            cue("rustle", Dir.OPEN, 0, null, EnumSet.of(TabletHand.EMPTY, TabletHand.ITEM), false),
            cue("draw", Dir.OPEN, 0.14, null, null, false),
            cue("grip", Dir.OPEN, 0.53, null, null, false),
            cue("power", Dir.OPEN, 0.59, null, null, false),
            cue("boot", Dir.OPEN, 0.61, new double[]{0.61, 0.74}, null, false),
            cue("ready", Dir.OPEN, 0.74, null, null, true),
            cue("zoom", Dir.OPEN, 0.77, null, null, false),
            cue("power", Dir.CLOSE, NONE, new double[]{0.74, 1}, null, false),
            cue("sleep", Dir.CLOSE, 0.74, new double[]{0.61, 0.74}, null, false),
            cue("stow", Dir.CLOSE, STOW_AT_P, new double[]{0.17, STOW_AT_P},
                    EnumSet.of(TabletHand.GUN, TabletHand.ITEM), false),
            cue("stow", Dir.CLOSE, STOW_EMPTY_AT_P, new double[]{0.17, STOW_EMPTY_AT_P},
                    EnumSet.of(TabletHand.EMPTY), false),
            cue("raise", Dir.CLOSE, 0.15, new double[]{0.02, 0.15}, EnumSet.of(TabletHand.GUN), false),
            cue("rustle", Dir.CLOSE, 0.15, new double[]{0.02, 0.15},
                    EnumSet.of(TabletHand.EMPTY, TabletHand.ITEM), false));

    /** Scheme B on a terminal: electronic sounds only (review S10). */
    public static final List<TabletCue> B2D_TERMINAL = List.of(
            cue("boot", Dir.OPEN, 0.001, null, null, false),
            cue("ready", Dir.OPEN, 0.999, null, null, true),
            cue("sleep", Dir.CLOSE, 0.55, null, null, false));

    /** Scheme B on the map (and every other full-screen page). */
    public static final List<TabletCue> B2D_MAP = List.of(
            cue("boot", Dir.OPEN, 0.001, null, null, false),
            cue("ready", Dir.OPEN, 0.999, null, null, true),
            cue("sleep", Dir.CLOSE, 0.999, null, null, false));

    /** During a key finish only these play. */
    public static final Set<String> FINISH_KEEP = TabletAnimationModel.SFX_FINISH_KEEP;

    /** Every sound name the animation can play (the twelve files of sounds.json). */
    public static final List<String> SOUND_NAMES = List.of("holster", "draw", "grip", "power",
            "boot", "ready", "search", "zoom", "sleep", "stow", "raise", "rustle");

    private TabletCues() {
    }

    /** The cues of {@code path} on {@code screen}; empty for the quick setting and OFF. */
    public static List<TabletCue> forPath(TabletPath path, TabletScreenKind screen) {
        if (path == TabletPath.A3D) {
            return A3D;
        }
        if (path == TabletPath.B2D) {
            return screen != null && screen.isTerminal() ? B2D_TERMINAL : B2D_MAP;
        }
        return List.of();
    }

    private static TabletCue cue(String name, Dir dir, double atP, double[] startRange,
                                 Set<TabletHand> hands, boolean link) {
        return new TabletCue(name, dir, atP, startRange, hands, link);
    }
}
