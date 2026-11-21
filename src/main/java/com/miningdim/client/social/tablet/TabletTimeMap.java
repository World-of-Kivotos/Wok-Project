package com.miningdim.client.social.tablet;

import java.util.Arrays;
import java.util.Objects;

/**
 * A time → progress map (preview {@code motion.js} {@code makeMap} / {@code evalMap} /
 * {@code invertMap}): nodes {@code [ms, p]}, eased between nodes with {@link #ease} (linear for
 * every map but the key-finish one). Times are milliseconds from the start of the map.
 *
 * @param pts   the nodes, time ascending (a repeated time is a step: the later segment wins)
 * @param ease  easing between two nodes
 * @param durMs time of the last node
 */
public record TabletTimeMap(double[][] pts, TabletEasing ease, double durMs) {
    public TabletTimeMap {
        Objects.requireNonNull(pts, "pts");
        Objects.requireNonNull(ease, "ease");
        if (pts.length == 0) {
            throw new IllegalArgumentException("a time map needs at least one node");
        }
        double[][] copy = new double[pts.length][];
        for (int i = 0; i < pts.length; i++) {
            copy[i] = new double[]{pts[i][0], pts[i][1]};
        }
        pts = copy;
    }

    /** {@code makeMap(pts, scale)}: every node time multiplied by {@code scale}, linear. */
    public static TabletTimeMap of(double[][] pts, double scale) {
        double k = scale == 0.0D ? 1.0D : scale;
        double[][] q = new double[pts.length][];
        for (int i = 0; i < pts.length; i++) {
            q[i] = new double[]{pts[i][0] * k, pts[i][1]};
        }
        return new TabletTimeMap(q, TabletEasing.LINEAR, q[q.length - 1][0]);
    }

    /** Number of nodes. */
    public int size() {
        return pts.length;
    }

    /** Progress of node {@code index}. */
    public double nodeP(int index) {
        return pts[index][1];
    }

    /** Time of node {@code index}. */
    public double nodeMs(int index) {
        return pts[index][0];
    }

    /** Progress at the first node (where this map starts). */
    public double startP() {
        return pts[0][1];
    }

    /** Progress at the last node (where this map ends). */
    public double endP() {
        return pts[pts.length - 1][1];
    }

    /** {@code evalMap}: progress at {@code tMs}; repeated node times take the later segment. */
    public double eval(double tMs) {
        int n = pts.length;
        if (n == 1 || tMs < pts[0][0]) {
            return pts[0][1];
        }
        if (tMs >= durMs) {
            return pts[n - 1][1];
        }
        int i = 0;
        while (i < n - 2 && tMs >= pts[i + 1][0]) {
            i++;
        }
        double[] a = pts[i];
        double[] b = pts[i + 1];
        double span = b[0] - a[0];
        double f = span > 0.0D ? (tMs - a[0]) / span : 1.0D;
        return a[1] + (b[1] - a[1]) * ease.apply(f);
    }

    /**
     * {@code invertMap}: the first time this monotonic map reaches {@code p} (clamped to its
     * range); eased segments are solved by 60 bisection steps.
     */
    public double invert(double target) {
        int n = pts.length;
        if (n == 1) {
            return 0.0D;
        }
        double lo = Math.min(pts[0][1], pts[n - 1][1]);
        double hi = Math.max(pts[0][1], pts[n - 1][1]);
        double p = Math.min(hi, Math.max(lo, target));
        for (int i = 0; i < n - 1; i++) {
            double[] a = pts[i];
            double[] b = pts[i + 1];
            if (a[1] == b[1]) {
                if (p == a[1]) {
                    return a[0];
                }
                continue;
            }
            boolean inSeg = (p - a[1]) * (p - b[1]) <= 0.0D;
            if (!inSeg) {
                continue;
            }
            double t = (p - a[1]) / (b[1] - a[1]);
            if (ease == TabletEasing.LINEAR) {
                return a[0] + (b[0] - a[0]) * t;
            }
            double x0 = 0.0D;
            double x1 = 1.0D;
            for (int it = 0; it < 60; it++) {
                double m = (x0 + x1) / 2.0D;
                if (ease.apply(m) < t) {
                    x0 = m;
                } else {
                    x1 = m;
                }
            }
            return a[0] + (b[0] - a[0]) * (x0 + x1) / 2.0D;
        }
        return pts[0][1] == p ? pts[0][0] : durMs;
    }

    @Override
    public String toString() {
        return "TabletTimeMap" + Arrays.deepToString(pts) + " " + ease + " " + durMs + "ms";
    }
}
