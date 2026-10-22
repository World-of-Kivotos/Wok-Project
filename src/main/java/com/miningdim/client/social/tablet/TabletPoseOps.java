package com.miningdim.client.social.tablet;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import java.util.List;

/**
 * Replays a {@link TabletOp} list on a real {@link PoseStack} (the matrices of {@link TabletMat4}
 * and the vectors are the same lists replayed in double). Going through {@code translate},
 * {@code scale} and {@code mulPose} keeps the stack's normal matrix right, which the side walls'
 * lighting needs; {@link TabletOp.Kind#IDENTITY} resets both matrices of the top entry, so the
 * tablet never inherits view bobbing, TaCZ camera motion or the stamina sway already on the
 * shared hand-pass stack.
 */
public final class TabletPoseOps {
    private TabletPoseOps() {
    }

    /** Applies {@code ops} to the top of {@code pose}, in order. */
    public static void apply(PoseStack pose, List<TabletOp> ops) {
        if (pose == null || ops == null) {
            return;
        }
        for (TabletOp op : ops) {
            switch (op.kind()) {
                case IDENTITY -> {
                    PoseStack.Pose last = pose.last();
                    last.pose().identity();
                    last.normal().identity();
                }
                case TRANSLATE -> pose.translate(op.a(), op.b(), op.c());
                case SCALE -> pose.scale((float) op.a(), (float) op.b(), (float) op.c());
                case ROT_X -> {
                    if (op.a() != 0.0D) {
                        pose.mulPose(Axis.XP.rotationDegrees((float) op.a()));
                    }
                }
                case ROT_Y -> {
                    if (op.a() != 0.0D) {
                        pose.mulPose(Axis.YP.rotationDegrees((float) op.a()));
                    }
                }
                case ROT_Z -> {
                    if (op.a() != 0.0D) {
                        pose.mulPose(Axis.ZP.rotationDegrees((float) op.a()));
                    }
                }
            }
        }
    }
}
