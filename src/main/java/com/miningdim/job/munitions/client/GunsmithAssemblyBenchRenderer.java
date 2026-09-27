package com.miningdim.job.munitions.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.block.GunsmithAssemblyBenchBlock;
import com.miningdim.job.munitions.block.GunsmithAssemblyBenchBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.Map;
import java.util.WeakHashMap;

public final class GunsmithAssemblyBenchRenderer
        implements BlockEntityRenderer<GunsmithAssemblyBenchBlockEntity> {

    private static final ResourceLocation TEXTURE = new ResourceLocation(
            MiningConstants.MODID, "textures/entity/gunsmith_assembly_arm.png");
    private static final float ASSEMBLY_CYCLE_TICKS = 80.0F;
    // Idle: gripper parked above the tool-change pedestal at (20.25, 23.5). Work: gripper hovering over the rifle receiver.
    private static final float IDLE_UPPER_ARM_Z = 0.1704F;
    private static final float IDLE_FOREARM_Z = 1.023F;
    private static final float WORK_UPPER_ARM_Z = -0.8362F;
    private static final float WORK_FOREARM_Z = 2.5396F;
    // The shoulder pivot is (26.5, 23.5); the work point above the rifle receiver is (18, 13.5).
    private static final float WORK_BASE_YAW = -(float) Math.atan2(10D, 8.5D);

    private final ModelPart root;
    private final ModelPart shoulder;
    private final ModelPart upperArm;
    private final ModelPart forearm;
    private final ModelPart wrist;
    private final ModelPart tool;
    private final ModelPart leftClaw;
    private final ModelPart rightClaw;
    private final Map<GunsmithAssemblyBenchBlockEntity, Long> animationStartTicks = new WeakHashMap<>();

    public GunsmithAssemblyBenchRenderer(BlockEntityRendererProvider.Context context) {
        root = createBodyLayer().bakeRoot();
        shoulder = root.getChild("shoulder");
        upperArm = shoulder.getChild("upper_arm");
        ModelPart elbow = upperArm.getChild("elbow");
        forearm = elbow.getChild("forearm");
        wrist = forearm.getChild("wrist");
        tool = wrist.getChild("tool");
        ModelPart gripper = tool.getChild("gripper");
        leftClaw = gripper.getChild("left_claw");
        rightClaw = gripper.getChild("right_claw");
    }

    private static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition shoulder = root.addOrReplaceChild("shoulder",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-2.5F, -1.0F, -2.5F, 5.0F, 1.0F, 5.0F)
                        .texOffs(21, 0).addBox(-2.0F, -3.0F, -2.0F, 4.0F, 2.0F, 4.0F)
                        .texOffs(38, 0).addBox(-1.5F, -5.0F, -2.0F, 3.0F, 2.0F, 1.0F)
                        .texOffs(38, 0).addBox(-1.5F, -5.0F, 1.0F, 3.0F, 2.0F, 1.0F),
                PartPose.offset(18.5F, 4.5F, 15.5F));
        PartDefinition upperArm = shoulder.addOrReplaceChild("upper_arm",
                CubeListBuilder.create()
                        .texOffs(47, 0).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F)
                        .texOffs(0, 7).addBox(-1.0F, -7.0F, -1.0F, 2.0F, 6.0F, 2.0F),
                PartPose.offset(0.0F, -4.0F, 0.0F));
        PartDefinition elbow = upperArm.addOrReplaceChild("elbow",
                CubeListBuilder.create()
                        .texOffs(9, 7).addBox(-1.5F, -1.5F, -1.5F, 3.0F, 3.0F, 3.0F)
                        .texOffs(22, 7).addBox(-1.0F, -1.0F, -2.0F, 2.0F, 2.0F, 4.0F),
                PartPose.offset(0.0F, -7.0F, 0.0F));
        PartDefinition forearm = elbow.addOrReplaceChild("forearm",
                CubeListBuilder.create()
                        .texOffs(35, 7).addBox(-1.0F, 1.0F, -1.0F, 2.0F, 6.0F, 2.0F),
                PartPose.ZERO);
        PartDefinition wrist = forearm.addOrReplaceChild("wrist",
                CubeListBuilder.create()
                        .texOffs(47, 0).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F),
                PartPose.offset(0.0F, 8.0F, 0.0F));
        PartDefinition tool = wrist.addOrReplaceChild("tool",
                CubeListBuilder.create()
                        .texOffs(44, 7).addBox(-1.5F, 1.0F, -1.5F, 3.0F, 1.0F, 3.0F),
                PartPose.ZERO);
        PartDefinition gripper = tool.addOrReplaceChild("gripper",
                CubeListBuilder.create()
                        .texOffs(0, 16).addBox(-2.0F, 0.0F, -1.0F, 4.0F, 1.0F, 2.0F),
                PartPose.offset(0.0F, 2.0F, 0.0F));
        gripper.addOrReplaceChild("left_claw",
                CubeListBuilder.create()
                        .texOffs(13, 16).addBox(-0.5F, 0.0F, -0.5F, 1.0F, 2.0F, 1.0F),
                PartPose.offset(-1.5F, 1.0F, 0.0F));
        gripper.addOrReplaceChild("right_claw",
                CubeListBuilder.create()
                        .texOffs(13, 16).addBox(-0.5F, 0.0F, -0.5F, 1.0F, 2.0F, 1.0F),
                PartPose.offset(1.5F, 1.0F, 0.0F));
        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void render(GunsmithAssemblyBenchBlockEntity blockEntity, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource,
                       int packedLight, int packedOverlay) {
        applyPose(blockEntity, partialTick);
        Direction facing = blockEntity.getBlockState().getValue(GunsmithAssemblyBenchBlock.FACING);
        float rotation = switch (facing) {
            case EAST -> -90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 90.0F;
            default -> 0.0F;
        };

        poseStack.pushPose();
        poseStack.translate(0.5D, 1.0D, 0.5D);
        poseStack.mulPose(Axis.YP.rotationDegrees(rotation));
        // ModelPart converts both joint offsets and cube vertices from model pixels to blocks.
        poseStack.scale(1.0F, -1.0F, 1.0F);
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        root.render(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }

    private void applyPose(GunsmithAssemblyBenchBlockEntity blockEntity, float partialTick) {
        shoulder.xRot = 0.0F;
        shoulder.yRot = 0.0F;
        shoulder.zRot = 0.0F;
        upperArm.xRot = 0.0F;
        upperArm.yRot = 0.0F;
        forearm.xRot = 0.0F;
        forearm.yRot = 0.0F;
        wrist.xRot = 0.0F;
        wrist.yRot = 0.0F;
        tool.xRot = 0.0F;
        tool.yRot = 0.0F;
        tool.y = 0.0F;

        upperArm.zRot = IDLE_UPPER_ARM_Z;
        forearm.zRot = IDLE_FOREARM_Z;
        wrist.zRot = -(IDLE_UPPER_ARM_Z + IDLE_FOREARM_Z);
        leftClaw.zRot = -0.26F;
        rightClaw.zRot = 0.26F;
        if (!blockEntity.isAnimating()) {
            animationStartTicks.remove(blockEntity);
            return;
        }

        long now = blockEntity.getLevel().getGameTime();
        long startedAt = animationStartTicks.computeIfAbsent(blockEntity, ignored -> now);
        float phase = ((now - startedAt + partialTick) % ASSEMBLY_CYCLE_TICKS) / ASSEMBLY_CYCLE_TICKS;
        float turn = motionWindow(phase, 0.04F, 0.26F, 0.72F, 0.94F);
        float reach = motionWindow(phase, 0.12F, 0.32F, 0.68F, 0.88F);
        float grip = motionWindow(phase, 0.28F, 0.38F, 0.62F, 0.72F);
        float weld = motionWindow(phase, 0.40F, 0.46F, 0.58F, 0.64F);
        float precisionPulse = Mth.sin(phase * Mth.TWO_PI * 12.0F) * weld;

        shoulder.yRot = WORK_BASE_YAW * turn;
        upperArm.zRot = Mth.lerp(reach, IDLE_UPPER_ARM_Z, WORK_UPPER_ARM_Z);
        forearm.zRot = Mth.lerp(reach, IDLE_FOREARM_Z, WORK_FOREARM_Z);
        wrist.zRot = -(upperArm.zRot + forearm.zRot);
        wrist.yRot = precisionPulse * 0.07F;
        tool.yRot = -precisionPulse * 0.11F;
        leftClaw.zRot = Mth.lerp(grip, -0.26F, -0.08F);
        rightClaw.zRot = Mth.lerp(grip, 0.26F, 0.08F);
    }

    private static float motionWindow(float phase, float moveStart, float moveEnd,
                                      float returnStart, float returnEnd) {
        return easedStep(phase, moveStart, moveEnd)
                * (1.0F - easedStep(phase, returnStart, returnEnd));
    }

    private static float easedStep(float phase, float start, float end) {
        float progress = Mth.clamp((phase - start) / (end - start), 0.0F, 1.0F);
        return progress * progress * (3.0F - 2.0F * progress);
    }
}
