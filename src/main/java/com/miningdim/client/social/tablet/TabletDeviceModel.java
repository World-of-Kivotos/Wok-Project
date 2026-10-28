package com.miningdim.client.social.tablet;



import java.util.ArrayList;
import java.util.List;

/**
 * The 3D D2 device of scheme A as plain geometry (DESIGN 3.5, preview {@code models/device.js}):
 * the front face quad over the E region, the side walls traced along the alpha outline of the
 * front-face image and pulled back by the case thickness, the back plate and the sleep line. Pure
 * logic; {@code TabletRenderer} only feeds these numbers to a vertex consumer.
 *
 * <p>Tablet space as {@link TabletPose3D}: origin = centre of the device plane (the whole terminal
 * picture, {@code w × h} layout pixels), +x right, +y up, +z towards the eye, unit block. A layout
 * point (x, y) lies at ((x/w − 0.5)·Wf, (0.5 − y/h)·Hf, 0). The front face is at z = 0, the back
 * plate at z = −thick.
 */
public final class TabletDeviceModel {
    /** Depth of the sleep line in front of the face (preview {@code 1/1024}). */
    public static final double SLEEP_LINE_Z = 1.0D / 1024.0D;
    /** A pixel of the front-face image counts as solid from this alpha on (preview: ≥ 128). */
    public static final int SOLID_ALPHA = 128;

    private TabletDeviceModel() {
    }

    /** Case thickness in blocks ({@code d2.thickU} modelling units). */
    public static double thickness() {
        return TabletAnimationModel.D2_THICK_U * TabletAnimationModel.U;
    }

    /** Layout x → tablet x. */
    public static double planeX(TabletPose3D.Face fc, int w, double x) {
        return (x / w - 0.5D) * fc.wf();
    }

    /** Layout y (down) → tablet y (up). */
    public static double planeY(TabletPose3D.Face fc, int h, double y) {
        return (0.5D - y / h) * fc.hf();
    }

    /**
     * The front face over E (vectors {@code tiers[].face3D}): tablet corners, the texture
     * coordinates of E in the whole-picture image (v = 0 at the top row), the sleep line's centre
     * and full width (the glass opening S), and the thickness.
     */
    public record FaceQuad(double x0, double x1, double yTop, double yBottom, double u0, double u1,
                           double v0, double v1, double sleepCenterX, double sleepCenterY,
                           double sleepWidth, double thick) {
    }

    /** The front face of geometry {@code g} on face {@code fc}. */
    public static FaceQuad faceQuad(TabletPose3D.Face fc, TabletD2Geometry g) {
        UiRect e = g.e();
        UiRect s = g.s();
        int w = g.w();
        int h = g.h();
        return new FaceQuad(planeX(fc, w, e.left()), planeX(fc, w, e.right()),
                planeY(fc, h, e.top()), planeY(fc, h, e.bottom()),
                (double) e.left() / w, (double) e.right() / w,
                (double) e.top() / h, (double) e.bottom() / h,
                planeX(fc, w, (s.left() + s.right()) / 2.0D),
                planeY(fc, h, (s.top() + s.bottom()) / 2.0D),
                planeX(fc, w, s.right()) - planeX(fc, w, s.left()), thickness());
    }

    /** Material of a side wall: case inside D, rubber outside it (bumpers, side keys). */
    public enum Wall {
        CASE,
        RUBBER
    }

    /**
     * One merged side wall along the outline, from layout grid point (x0, y0) to (x1, y1),
     * clockwise with y down (the solid side on the right). Its outward normal in tablet space is
     * ({@code dy}, {@code dx}, 0), with (dx, dy) the signs of the direction (preview
     * {@code n = [s.dy, s.dx, 0]}).
     */
    public record Edge(int x0, int y0, int x1, int y1, Wall wall) {
        public int dx() {
            return Integer.signum(x1 - x0);
        }

        public int dy() {
            return Integer.signum(y1 - y0);
        }

        public int length() {
            return Math.abs(x1 - x0) + Math.abs(y1 - y0);
        }
    }

    /** A rectangle of the back plate, [left, right) × [top, bottom) layout pixels. */
    public record BackRect(int left, int top, int right, int bottom) {
        public int area() {
            return (right - left) * (bottom - top);
        }
    }

    /**
     * Side walls and back plate of one front-face image.
     *
     * @param solid pixels counted solid (the back plate covers exactly these)
     */
    public record Mesh(int width, int height, List<Edge> edges, List<BackRect> back, int solid) {
    }

    /**
     * Traces the walls and the back plate of the {@code w × h} ARGB image (alpha ≥ 128 is solid):
     * every pixel side between a solid and an empty pixel is a wall, merged along a row or column
     * while the material stays the same; the back plate is the solid row runs, merged downwards
     * while a run keeps its columns. {@code device} is D (walls of pixels inside it are case).
     */
    public static Mesh mesh(int[] argb, int w, int h, UiRect device) {
        boolean[] solid = new boolean[Math.max(0, w * h)];
        int count = 0;
        for (int i = 0; i < solid.length; i++) {
            solid[i] = (argb[i] >>> 24) >= SOLID_ALPHA;
            if (solid[i]) {
                count++;
            }
        }
        List<Edge> edges = new ArrayList<>();
        // Top and bottom sides, merged along each row.
        for (int y = 0; y < h; y++) {
            horizontal(edges, solid, w, h, y, device, true);
            horizontal(edges, solid, w, h, y, device, false);
        }
        // Left and right sides, merged down each column.
        for (int x = 0; x < w; x++) {
            vertical(edges, solid, w, h, x, device, true);
            vertical(edges, solid, w, h, x, device, false);
        }
        return new Mesh(w, h, List.copyOf(edges), backRects(solid, w, h), count);
    }

    private static boolean at(boolean[] solid, int w, int h, int x, int y) {
        return x >= 0 && y >= 0 && x < w && y < h && solid[y * w + x];
    }

    private static Wall wallOf(UiRect d, int x, int y) {
        return d != null && x >= d.left() && x < d.right() && y >= d.top() && y < d.bottom()
                ? Wall.CASE : Wall.RUBBER;
    }

    /** Top sides run left → right at y; bottom sides run right → left at y + 1. */
    private static void horizontal(List<Edge> out, boolean[] solid, int w, int h, int y, UiRect d,
                                   boolean top) {
        int start = -1;
        Wall wall = null;
        for (int x = 0; x <= w; x++) {
            boolean exposed = x < w && at(solid, w, h, x, y) && !at(solid, w, h, x, top ? y - 1 : y + 1);
            Wall here = exposed ? wallOf(d, x, y) : null;
            if (start >= 0 && here != wall) {
                int line = top ? y : y + 1;
                out.add(top ? new Edge(start, line, x, line, wall) : new Edge(x, line, start, line, wall));
                start = -1;
            }
            if (exposed && start < 0) {
                start = x;
                wall = here;
            }
        }
    }

    /** Right sides run top → bottom at x + 1; left sides run bottom → top at x. */
    private static void vertical(List<Edge> out, boolean[] solid, int w, int h, int x, UiRect d,
                                 boolean left) {
        int start = -1;
        Wall wall = null;
        for (int y = 0; y <= h; y++) {
            boolean exposed = y < h && at(solid, w, h, x, y) && !at(solid, w, h, left ? x - 1 : x + 1, y);
            Wall here = exposed ? wallOf(d, x, y) : null;
            if (start >= 0 && here != wall) {
                int line = left ? x : x + 1;
                out.add(left ? new Edge(line, y, line, start, wall) : new Edge(line, start, line, y, wall));
                start = -1;
            }
            if (exposed && start < 0) {
                start = y;
                wall = here;
            }
        }
    }

    /** Solid row runs merged downwards while a run keeps exactly its columns. */
    private static List<BackRect> backRects(boolean[] solid, int w, int h) {
        List<BackRect> done = new ArrayList<>();
        List<int[]> open = new ArrayList<>();   // {left, top, right}
        for (int y = 0; y <= h; y++) {
            List<int[]> runs = new ArrayList<>();
            if (y < h) {
                int x = 0;
                while (x < w) {
                    if (!solid[y * w + x]) {
                        x++;
                        continue;
                    }
                    int x0 = x;
                    while (x < w && solid[y * w + x]) {
                        x++;
                    }
                    runs.add(new int[]{x0, x});
                }
            }
            List<int[]> next = new ArrayList<>();
            for (int[] run : runs) {
                int[] match = null;
                for (int[] o : open) {
                    if (o[0] == run[0] && o[2] == run[1]) {
                        match = o;
                        break;
                    }
                }
                if (match != null) {
                    open.remove(match);
                    next.add(match);
                } else {
                    next.add(new int[]{run[0], y, run[1]});
                }
            }
            for (int[] o : open) {
                done.add(new BackRect(o[0], o[1], o[2], y));
            }
            open = next;
        }
        return List.copyOf(done);
    }
}
