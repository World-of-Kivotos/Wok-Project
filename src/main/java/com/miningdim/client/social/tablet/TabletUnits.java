package com.miningdim.client.social.tablet;

/**
 * Units of one screen (preview {@code spec.units(tier, screen)}).
 *
 * @param guiScale window GUI scale S
 * @param factor   WOK layout factor f ({@code UiScale.factor()}; always 1 for the map)
 * @param lpW      logical layout width (lp): GUI-scaled width / f
 * @param lpH      logical layout height (lp)
 * @param guiW     GUI-scaled width
 * @param guiH     GUI-scaled height
 * @param pxW      physical (framebuffer) width
 * @param pxH      physical height
 */
public record TabletUnits(int guiScale, int factor, int lpW, int lpH, int guiW, int guiH,
                          int pxW, int pxH) {
    public TabletUnits {
        guiScale = Math.max(1, guiScale);
        factor = Math.max(1, factor);
    }

    /** A terminal screen: layout = GUI size / {@code uiFactor} (as {@code UiScale.layoutSize}). */
    public static TabletUnits terminal(int guiW, int guiH, int guiScale, int uiFactor) {
        int f = Math.max(1, uiFactor);
        return new TabletUnits(guiScale, f, guiW / f, guiH / f, guiW, guiH,
                guiW * Math.max(1, guiScale), guiH * Math.max(1, guiScale));
    }

    /** A full-screen page (map, loadout): no 2x layout factor. */
    public static TabletUnits fullscreen(int guiW, int guiH, int guiScale) {
        return terminal(guiW, guiH, guiScale, 1);
    }

    /** The same window with explicit physical sizes (a window not divisible by the GUI scale). */
    public TabletUnits withPhysical(int physicalW, int physicalH) {
        return new TabletUnits(guiScale, factor, lpW, lpH, guiW, guiH, physicalW, physicalH);
    }

    /** Physical pixels per logical pixel (S·f). */
    public int sf() {
        return guiScale * factor;
    }

    /** Window aspect ratio (width / height). */
    public double aspect() {
        return (double) pxW / (double) pxH;
    }
}
