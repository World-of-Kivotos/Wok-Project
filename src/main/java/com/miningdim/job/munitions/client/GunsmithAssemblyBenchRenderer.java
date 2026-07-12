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

public final class GunsmithAssemblyBenchRenderer
        implements BlockEntityRenderer<GunsmithAssemblyBenchBlockEntity> {

    private static final ResourceLocation TEXTURE = new ResourceLocation(
            MiningConstants.MODID, "textures/entity/gunsmith_assembly_arm.png");

    private final ModelPart root;
    private final ModelPart shoulder;
    private final ModelPart upperArm;
    private final ModelPart forearm;
    private final ModelPart wrist;
    private final ModelPart tool;
    private final ModelPart leftClaw;
    private final ModelPart rightClaw;

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
                        .texOffs(0, 32).addBox(-3.0F, -2.0F, -3.0F, 6.0F, 4.0F, 6.0F)
                        .texOffs(32, 0).addBox(-2.0F, -1.0F, -3.5F, 4.0F, 2.0F, 7.0F),
                PartPose.offset(24.5F, 8.0F, 24.0F));
        PartDefinition upperArm = shoulder.addOrReplaceChild("upper_arm",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-2.0F, -8.0F, -2.0F, 4.0F, 8.0F, 4.0F)
                        .texOffs(32, 0).addBox(-1.0F, -7.5F, -1.0F, 2.0F, 7.0F, 2.0F),
                PartPose.ZERO);
        PartDefinition elbow = upperArm.addOrReplaceChild("elbow",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-3.0F, -3.0F, -3.0F, 6.0F, 6.0F, 6.0F)
                        .texOffs(32, 0).addBox(-1.5F, -3.5F, -3.5F, 3.0F, 7.0F, 7.0F),
                PartPose.offset(0.0F, -8.0F, 0.0F));
        PartDefinition forearm = elbow.addOrReplaceChild("forearm",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 8.0F, 4.0F)
                        .texOffs(32, 0).addBox(-1.0F, 0.5F, -1.0F, 2.0F, 7.0F, 2.0F),
                PartPose.ZERO);
        PartDefinition wrist = forearm.addOrReplaceChild("wrist",
                CubeListBuilder.create()
                        .texOffs(0, 32).addBox(-3.0F, -2.0F, -3.0F, 6.0F, 4.0F, 6.0F)
                        .texOffs(32, 0).addBox(-2.0F, -1.0F, -3.5F, 4.0F, 2.0F, 7.0F),
                PartPose.offset(0.0F, 8.0F, 0.0F));
        PartDefinition tool = wrist.addOrReplaceChild("tool",
                CubeListBuilder.create()
                        .texOffs(0, 32).addBox(-1.5F, 0.0F, -2.0F, 3.0F, 3.0F, 4.0F)
                        .texOffs(32, 32).addBox(-1.0F, 2.5F, -1.5F, 2.0F, 2.0F, 3.0F),
                PartPose.offset(0.0F, 2.0F, 0.0F));
        PartDefinition gripper = tool.addOrReplaceChild("gripper",
                CubeListBuilder.create().texOffs(0, 0)
                        .addBox(-4.0F, 0.0F, -3.0F, 8.0F, 1.0F, 6.0F),
                PartPose.offset(0.0F, 4.0F, 0.0F));
        gripper.addOrReplaceChild("left_claw",
                CubeListBuilder.create()
                        .texOffs(32, 0).addBox(-1.0F, 0.0F, -1.5F, 2.0F, 3.0F, 3.0F)
                        .texOffs(32, 32).addBox(-0.75F, 2.5F, -1.25F, 1.5F, 1.0F, 2.5F),
                PartPose.offset(-3.0F, 1.0F, 0.0F));
        gripper.addOrReplaceChild("right_claw",
                CubeListBuilder.create()
                        .texOffs(32, 0).addBox(-1.0F, 0.0F, -1.5F, 2.0F, 3.0F, 3.0F)
                        .texOffs(32, 32).addBox(-0.75F, 2.5F, -1.25F, 1.5F, 1.0F, 2.5F),
                PartPose.offset(3.0F, 1.0F, 0.0F));
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
        poseStack.scale(1.0F / 16.0F, -1.0F / 16.0F, 1.0F / 16.0F);
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

        upperArm.zRot = -0.82F;
        forearm.zRot = 1.52F;
        wrist.zRot = -0.14F;
        leftClaw.zRot = -0.12F;
        rightClaw.zRot = 0.12F;
        if (!blockEntity.isAnimating()) {
            return;
        }

        float cycle = blockEntity.animationTicks(partialTick) * 0.16F;
        float sweep = Mth.sin(cycle * 0.55F);
        float reach = Mth.sin(cycle);
        float chatter = Mth.sin(cycle * 5.2F) * 0.038F;
        float grip = 0.08F + (Mth.sin(cycle * 3.1F) + 1.0F) * 0.04F;

        shoulder.yRot = sweep * 0.28F;
        shoulder.xRot = Mth.sin(cycle * 0.72F) * 0.05F;
        upperArm.zRot = -0.82F + reach * 0.16F + chatter;
        forearm.zRot = 1.52F - Mth.sin(cycle + 0.7F) * 0.28F - chatter * 1.7F;
        wrist.zRot = -0.14F + Mth.sin(cycle * 2.35F) * 0.19F;
        tool.xRot = Mth.sin(cycle * 4.6F) * 0.06F;
        leftClaw.zRot = -0.12F - grip;
        rightClaw.zRot = 0.12F + grip;
    }

    @Override
    public boolean shouldRenderOffScreen(GunsmithAssemblyBenchBlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 96;
    }
}
