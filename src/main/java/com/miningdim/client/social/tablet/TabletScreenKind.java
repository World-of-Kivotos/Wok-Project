package com.miningdim.client.social.tablet;

/**
 * Which kind of tablet-family screen is animated. The preview names are {@code squad},
 * {@code formation} and {@code map}; terminal screens are a white list (preview
 * {@code spec.isTerminal}), every other tablet screen takes the map path.
 */
public enum TabletScreenKind {
    /** The battle terminal's squad screen: the terminal key closes it. */
    SQUAD("squad"),
    /** Any other {@code TacticalScreen} (formation page, uiTest pages): only Esc closes it. */
    TERMINAL("formation"),
    /** The tactical map and the loadout screen: the old full-screen frame, only Esc closes it. */
    FULLSCREEN("map");

    private final String previewName;

    TabletScreenKind(String previewName) {
        this.previewName = previewName;
    }

    /** The preview's screen id ({@code squad} / {@code formation} / {@code map}). */
    public String previewName() {
        return previewName;
    }

    /** The kind of a preview screen id; unknown ids take the map path (white list). */
    public static TabletScreenKind byPreviewName(String name) {
        if ("squad".equals(name)) {
            return SQUAD;
        }
        return "formation".equals(name) ? TERMINAL : FULLSCREEN;
    }

    /** A D2 terminal screen (device stops in its D region, B slides the whole device). */
    public boolean isTerminal() {
        return this != FULLSCREEN;
    }

    /** Whether the terminal key closes this screen (only the squad screen). */
    public boolean closesOnTerminalKey() {
        return this == SQUAD;
    }
}
