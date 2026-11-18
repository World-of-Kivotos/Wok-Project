package com.miningdim.client.social.tablet;

import org.joml.Matrix4f;

import java.util.List;

/**
 * 4×4 matrices as {@code double[16]}, column-major (as JOML {@code Matrix4f.get(float[])}), the
 * preview's {@code lib/posestack.js}. Every operation post-multiplies like
 * {@code com.mojang.blaze3d.vertex.PoseStack}; angles are degrees with the sign of
 * {@code Axis.XP.rotationDegrees}. Rotations by exactly 0 are skipped (as the preview does).
 */
public final class TabletMat4 {
    private static final double DEG = Math.PI / 180.0D;

    private TabletMat4() {
    }

    public static double[] identity() {
        double[] m = new double[16];
        m[0] = 1.0D;
        m[5] = 1.0D;
        m[10] = 1.0D;
        m[15] = 1.0D;
        return m;
    }

    /** {@code a · b} (a new array). */
    public static double[] multiply(double[] a, double[] b) {
        double[] r = new double[16];
        for (int c = 0; c < 4; c++) {
            for (int row = 0; row < 4; row++) {
                double s = 0.0D;
                for (int k = 0; k < 4; k++) {
                    s += a[k * 4 + row] * b[c * 4 + k];
                }
                r[c * 4 + row] = s;
            }
        }
        return r;
    }

    public static double[] translation(double x, double y, double z) {
        double[] m = identity();
        m[12] = x;
        m[13] = y;
        m[14] = z;
        return m;
    }

    public static double[] scaling(double x, double y, double z) {
        double[] m = identity();
        m[0] = x;
        m[5] = y;
        m[10] = z;
        return m;
    }

    public static double[] rotationX(double degrees) {
        double r = degrees * DEG;
        double c = Math.cos(r);
        double s = Math.sin(r);
        double[] m = identity();
        m[5] = c;
        m[6] = s;
        m[9] = -s;
        m[10] = c;
        return m;
    }

    public static double[] rotationY(double degrees) {
        double r = degrees * DEG;
        double c = Math.cos(r);
        double s = Math.sin(r);
        double[] m = identity();
        m[0] = c;
        m[2] = -s;
        m[8] = s;
        m[10] = c;
        return m;
    }

    public static double[] rotationZ(double degrees) {
        double r = degrees * DEG;
        double c = Math.cos(r);
        double s = Math.sin(r);
        double[] m = identity();
        m[0] = c;
        m[1] = s;
        m[4] = -s;
        m[5] = c;
        return m;
    }

    /** {@code m · translation}. */
    public static double[] translate(double[] m, double x, double y, double z) {
        return multiply(m, translation(x, y, z));
    }

    public static double[] scale(double[] m, double x, double y, double z) {
        return multiply(m, scaling(x, y, z));
    }

    public static double[] rotX(double[] m, double degrees) {
        return degrees == 0.0D ? m.clone() : multiply(m, rotationX(degrees));
    }

    public static double[] rotY(double[] m, double degrees) {
        return degrees == 0.0D ? m.clone() : multiply(m, rotationY(degrees));
    }

    public static double[] rotZ(double[] m, double degrees) {
        return degrees == 0.0D ? m.clone() : multiply(m, rotationZ(degrees));
    }

    /** Applies one operation to {@code m} (returns a new array). */
    public static double[] apply(double[] m, TabletOp op) {
        return switch (op.kind()) {
            case IDENTITY -> identity();
            case TRANSLATE -> translate(m, op.a(), op.b(), op.c());
            case SCALE -> scale(m, op.a(), op.b(), op.c());
            case ROT_X -> rotX(m, op.a());
            case ROT_Y -> rotY(m, op.a());
            case ROT_Z -> rotZ(m, op.a());
        };
    }

    /** {@code opsMatrix}: replays {@code ops} on {@code init} (identity when {@code null}). */
    public static double[] ops(List<TabletOp> ops, double[] init) {
        double[] m = init == null ? identity() : init.clone();
        if (ops != null) {
            for (TabletOp op : ops) {
                m = apply(m, op);
            }
        }
        return m;
    }

    /** {@code [x, y, z]} transformed as a point (no perspective divide). */
    public static double[] transformPoint(double[] m, double x, double y, double z) {
        return new double[]{
                m[0] * x + m[4] * y + m[8] * z + m[12],
                m[1] * x + m[5] * y + m[9] * z + m[13],
                m[2] * x + m[6] * y + m[10] * z + m[14]};
    }

    /** The JOML matrix of {@code m} (column-major float). */
    public static Matrix4f toJoml(double[] m) {
        float[] f = new float[16];
        for (int i = 0; i < 16; i++) {
            f[i] = (float) m[i];
        }
        return new Matrix4f().set(f);
    }
}
