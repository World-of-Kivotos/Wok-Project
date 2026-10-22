package com.miningdim.client.social.tablet;

/**
 * One PoseStack operation of an operation list (preview {@code PoseStack.apply}): the Java side
 * replays the same list with {@code PoseStack} calls in the same order.
 *
 * @param kind operation
 * @param a    x (translate / scale) or the angle in degrees (rotations)
 * @param b    y (translate / scale)
 * @param c    z (translate / scale)
 */
public record TabletOp(Kind kind, double a, double b, double c) {
    public enum Kind { IDENTITY, TRANSLATE, SCALE, ROT_X, ROT_Y, ROT_Z }

    public static TabletOp identity() {
        return new TabletOp(Kind.IDENTITY, 0, 0, 0);
    }

    public static TabletOp translate(double x, double y, double z) {
        return new TabletOp(Kind.TRANSLATE, x, y, z);
    }

    public static TabletOp scale(double x, double y, double z) {
        return new TabletOp(Kind.SCALE, x, y, z);
    }

    public static TabletOp rotX(double degrees) {
        return new TabletOp(Kind.ROT_X, degrees, 0, 0);
    }

    public static TabletOp rotY(double degrees) {
        return new TabletOp(Kind.ROT_Y, degrees, 0, 0);
    }

    public static TabletOp rotZ(double degrees) {
        return new TabletOp(Kind.ROT_Z, degrees, 0, 0);
    }
}
