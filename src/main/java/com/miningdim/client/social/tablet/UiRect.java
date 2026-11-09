package com.miningdim.client.social.tablet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Immutable layout rectangle [left, right) x [top, bottom) in logical GUI pixels (for a
 * {@link TacticalScreen} that is the layout space after the minimum 2x scale).
 *
 * <p>{@link #cols} and {@link #rows} port the preview's {@code UI.cols/UI.rows}: fixed pixel sizes
 * and {@code *} weights can be mixed, cells are separated by {@code gap}, and the last cell
 * always takes the remainder, so surface layouts can be copied from {@code ui-preview} as-is.
 */
public record UiRect(int left, int top, int right, int bottom) {
    public static final UiRect EMPTY = new UiRect(0, 0, 0, 0);

    public static UiRect of(int left, int top, int right, int bottom) {
        return new UiRect(left, top, right, bottom);
    }

    public static UiRect ofSize(int x, int y, int width, int height) {
        return new UiRect(x, y, x + width, y + height);
    }

    /** Width; never negative even when the rectangle is inverted. */
    public int width() {
        return Math.max(0, right - left);
    }

    /** Height; never negative even when the rectangle is inverted. */
    public int height() {
        return Math.max(0, bottom - top);
    }

    public boolean isEmpty() {
        return right <= left || bottom <= top;
    }

    public int centerX() {
        return left + (right - left) / 2;
    }

    public int centerY() {
        return top + (bottom - top) / 2;
    }

    public boolean contains(double x, double y) {
        return x >= left && x < right && y >= top && y < bottom;
    }

    public boolean contains(UiRect other) {
        return other.left >= left && other.right <= right
                && other.top >= top && other.bottom <= bottom;
    }

    /** True when the two rectangles share at least one pixel. */
    public boolean intersects(UiRect other) {
        return !isEmpty() && !other.isEmpty()
                && left < other.right && right > other.left
                && top < other.bottom && bottom > other.top;
    }

    public UiRect inset(int amount) {
        return inset(amount, amount);
    }

    public UiRect inset(int dx, int dy) {
        return new UiRect(left + dx, top + dy, right - dx, bottom - dy);
    }

    public UiRect inset(int dLeft, int dTop, int dRight, int dBottom) {
        return new UiRect(left + dLeft, top + dTop, right - dRight, bottom - dBottom);
    }

    public UiRect offset(int dx, int dy) {
        return new UiRect(left + dx, top + dy, right + dx, bottom + dy);
    }

    /** The top {@code size} pixels (clamped to this rectangle). */
    public UiRect topSlice(int size) {
        return new UiRect(left, top, right, Math.min(bottom, top + Math.max(0, size)));
    }

    /** The bottom {@code size} pixels (clamped to this rectangle). */
    public UiRect bottomSlice(int size) {
        return new UiRect(left, Math.max(top, bottom - Math.max(0, size)), right, bottom);
    }

    /** The left {@code size} pixels (clamped to this rectangle). */
    public UiRect leftSlice(int size) {
        return new UiRect(left, top, Math.min(right, left + Math.max(0, size)), bottom);
    }

    /** The right {@code size} pixels (clamped to this rectangle). */
    public UiRect rightSlice(int size) {
        return new UiRect(Math.max(left, right - Math.max(0, size)), top, right, bottom);
    }

    /** Overlap of both rectangles, or {@link #EMPTY} when they do not overlap. */
    public UiRect intersection(UiRect other) {
        int l = Math.max(left, other.left);
        int t = Math.max(top, other.top);
        int r = Math.min(right, other.right);
        int b = Math.min(bottom, other.bottom);
        return r <= l || b <= t ? EMPTY : new UiRect(l, t, r, b);
    }

    /** Splits horizontally, e.g. {@code cols(gap, Size.px(100), Size.STAR, Size.px(60))}. */
    public List<UiRect> cols(int gap, Size... sizes) {
        return split(gap, true, sizes);
    }

    /** Splits vertically, e.g. {@code rows(gap, Size.px(14), Size.STAR)}. */
    public List<UiRect> rows(int gap, Size... sizes) {
        return split(gap, false, sizes);
    }

    /** Splits horizontally with a preview-style spec such as {@code "100,*,60"} or {@code "*,2*"}. */
    public List<UiRect> cols(String spec, int gap) {
        return split(gap, true, Size.parseAll(spec));
    }

    /** Splits vertically with a preview-style spec such as {@code "14,*"}. */
    public List<UiRect> rows(String spec, int gap) {
        return split(gap, false, Size.parseAll(spec));
    }

    private List<UiRect> split(int gap, boolean horizontal, Size... sizes) {
        if (sizes == null || sizes.length == 0) {
            return List.of();
        }
        int total = horizontal ? right - left : bottom - top;
        int fixed = 0;
        double starSum = 0.0D;
        for (Size size : sizes) {
            if (size.isStar()) {
                starSum += size.weight();
            } else {
                fixed += size.pixels();
            }
        }
        if (starSum <= 0.0D) {
            starSum = 1.0D;
        }
        int free = Math.max(0, total - fixed - gap * (sizes.length - 1));
        int position = horizontal ? left : top;
        int end = horizontal ? right : bottom;
        List<UiRect> cells = new ArrayList<>(sizes.length);
        for (int index = 0; index < sizes.length; index++) {
            Size size = sizes[index];
            int extent = size.isStar() ? (int) Math.floor(free * size.weight() / starSum)
                    : size.pixels();
            if (index == sizes.length - 1) {
                extent = end - position;
            }
            cells.add(horizontal
                    ? new UiRect(position, top, position + extent, bottom)
                    : new UiRect(left, position, right, position + extent));
            position += extent + gap;
        }
        return cells;
    }

    /** One cell size of {@link #cols}/{@link #rows}: fixed pixels or a {@code *} weight. */
    public record Size(int pixels, double weight) {
        /** One share of the free space ({@code '*'} in the preview). */
        public static final Size STAR = new Size(0, 1.0D);

        public Size {
            if (weight < 0.0D || Double.isNaN(weight)) {
                throw new IllegalArgumentException("weight must be >= 0: " + weight);
            }
        }

        public static Size px(int pixels) {
            return new Size(Math.max(0, pixels), 0.0D);
        }

        public static Size star(double weight) {
            return new Size(0, weight <= 0.0D ? 1.0D : weight);
        }

        public boolean isStar() {
            return weight > 0.0D;
        }

        /** Parses {@code "120"}, {@code "*"} or {@code "2*"} (the preview's notation). */
        public static Size parse(String token) {
            String value = token == null ? "" : token.strip().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) {
                throw new IllegalArgumentException("empty size");
            }
            if (value.endsWith("*")) {
                String number = value.substring(0, value.length() - 1).strip();
                return number.isEmpty() ? STAR : star(Double.parseDouble(number));
            }
            return px(Integer.parseInt(value));
        }

        static Size[] parseAll(String spec) {
            if (spec == null || spec.isBlank()) {
                return new Size[0];
            }
            String[] tokens = spec.split("[,\\s]+");
            List<Size> sizes = new ArrayList<>(tokens.length);
            for (String token : tokens) {
                if (!token.isBlank()) {
                    sizes.add(parse(token));
                }
            }
            return sizes.toArray(Size[]::new);
        }
    }
}
