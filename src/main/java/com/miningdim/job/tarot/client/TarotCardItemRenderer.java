package com.miningdim.job.tarot.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.tarot.TarotCardItem;
import com.miningdim.job.tarot.TarotQuality;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * True two-sided thin tarot card: NBT-selected art and quality frame on the
 * front, neutral card back on the reverse.
 */
public final class TarotCardItemRenderer extends BlockEntityWithoutLevelRenderer {

    private static final ResourceLocation CARD_BACK = texture("card_back");
    private static final float BACK_Z = 0.46875F;
    private static final float FRONT_Z = 0.53125F;
    private static final float FRAME_Z = 0.53225F;

    public TarotCardItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                             MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (!(stack.getItem() instanceof TarotCardItem)) {
            return;
        }

        ResourceLocation face;
        ResourceLocation frame;
        try {
            String id = String.format("%02d", TarotCardItem.cardId(stack));
            TarotQuality quality = TarotCardItem.quality(stack);
            String orientation = TarotCardItem.upright(stack) ? "" : "_reversed";
            face = texture(id + orientation);
            frame = texture("border_" + quality.id() + orientation);
        } catch (RuntimeException malformedCard) {
            face = texture("00");
            frame = texture("border_r");
        }

        pose.pushPose();
        drawSouthFace(pose, buffers.getBuffer(RenderType.entityCutout(face)),
                FRONT_Z, packedOverlay);
        drawSouthFace(pose, buffers.getBuffer(RenderType.entityCutout(frame)),
                FRAME_Z, packedOverlay);
        drawNorthFace(pose, buffers.getBuffer(RenderType.entityCutout(CARD_BACK)),
                BACK_Z, packedOverlay);
        pose.popPose();
    }

    private static ResourceLocation texture(String name) {
        return new ResourceLocation(MiningConstants.MODID, "textures/item/tarot/" + name + ".png");
    }

    private static void drawSouthFace(PoseStack pose, VertexConsumer vertices,
                                      float z, int packedOverlay) {
        Matrix4f matrix = pose.last().pose();
        Matrix3f normal = pose.last().normal();
        vertex(vertices, matrix, normal, 0.0F, 0.0F, z, 0.0F, 1.0F,
                0.0F, 0.0F, 1.0F, packedOverlay);
        vertex(vertices, matrix, normal, 1.0F, 0.0F, z, 1.0F, 1.0F,
                0.0F, 0.0F, 1.0F, packedOverlay);
        vertex(vertices, matrix, normal, 1.0F, 1.0F, z, 1.0F, 0.0F,
                0.0F, 0.0F, 1.0F, packedOverlay);
        vertex(vertices, matrix, normal, 0.0F, 1.0F, z, 0.0F, 0.0F,
                0.0F, 0.0F, 1.0F, packedOverlay);
    }

    private static void drawNorthFace(PoseStack pose, VertexConsumer vertices,
                                      float z, int packedOverlay) {
        Matrix4f matrix = pose.last().pose();
        Matrix3f normal = pose.last().normal();
        vertex(vertices, matrix, normal, 1.0F, 0.0F, z, 0.0F, 1.0F,
                0.0F, 0.0F, -1.0F, packedOverlay);
        vertex(vertices, matrix, normal, 0.0F, 0.0F, z, 1.0F, 1.0F,
                0.0F, 0.0F, -1.0F, packedOverlay);
        vertex(vertices, matrix, normal, 0.0F, 1.0F, z, 1.0F, 0.0F,
                0.0F, 0.0F, -1.0F, packedOverlay);
        vertex(vertices, matrix, normal, 1.0F, 1.0F, z, 0.0F, 0.0F,
                0.0F, 0.0F, -1.0F, packedOverlay);
    }

    private static void vertex(VertexConsumer vertices, Matrix4f matrix, Matrix3f normal,
                               float x, float y, float z, float u, float v,
                               float normalX, float normalY, float normalZ,
                               int packedOverlay) {
        vertices.vertex(matrix, x, y, z)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(packedOverlay)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(normal, normalX, normalY, normalZ)
                .endVertex();
    }
}
