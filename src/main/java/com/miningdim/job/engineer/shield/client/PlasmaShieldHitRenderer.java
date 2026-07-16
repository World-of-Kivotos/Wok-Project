package com.miningdim.job.engineer.shield.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.engineer.shield.PlasmaShieldVisualProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.List;

/** World-space camera-facing honeycomb shield flash for the hit player and tracking observers. */
@Mod.EventBusSubscriber(modid = MiningConstants.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlasmaShieldHitRenderer {

    private static final ResourceLocation TEXTURE = new ResourceLocation(
            MiningConstants.MODID, "textures/entity/plasma_shield_hit.png");
    private static final RenderType RENDER_TYPE = RenderType.entityTranslucentEmissive(TEXTURE, false);

    private PlasmaShieldHitRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        List<ClientPlasmaShieldHitEffects.Frame> frames =
                ClientPlasmaShieldHitEffects.frames(event.getPartialTick());
        if (frames.isEmpty()) {
            return;
        }

        PoseStack pose = event.getPoseStack();
        Vec3 cameraPosition = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(RENDER_TYPE);

        for (ClientPlasmaShieldHitEffects.Frame frame : frames) {
            Entity entity = minecraft.level.getEntity(frame.entityId());
            if (entity == null
                    || (event.getCamera().getEntity() == entity
                    && minecraft.options.getCameraType().isFirstPerson())) {
                continue;
            }
            Vec3 position = entity.getPosition(event.getPartialTick());
            double centerY = position.y + entity.getBbHeight() * 0.5D;
            float diameter = Math.max(2.2F, entity.getBbHeight() + 0.5F) * frame.scale();
            PlasmaShieldVisualProfile.Style style = PlasmaShieldVisualProfile.style(frame.type());

            pose.pushPose();
            pose.translate(
                    position.x - cameraPosition.x,
                    centerY - cameraPosition.y,
                    position.z - cameraPosition.z);
            pose.mulPose(event.getCamera().rotation());
            drawPass(pose, consumer, diameter * 1.08F,
                    style.primaryRgb(), frame.alpha() * 0.32F);
            drawPass(pose, consumer, diameter,
                    style.highlightRgb(), frame.alpha() * 0.88F);
            pose.popPose();
        }

        buffers.endBatch(RENDER_TYPE);
        RenderSystem.applyModelViewMatrix();
    }

    private static void drawPass(PoseStack pose,
                                 VertexConsumer consumer,
                                 float diameter,
                                 int rgb,
                                 float alpha) {
        pose.pushPose();
        pose.scale(diameter, diameter, diameter);
        Matrix4f matrix = pose.last().pose();
        Matrix3f normal = pose.last().normal();
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;
        int alphaByte = Math.round(255.0F * alpha);

        vertex(consumer, matrix, normal, -0.5F, -0.5F, 0.0F, 1.0F,
                red, green, blue, alphaByte);
        vertex(consumer, matrix, normal, 0.5F, -0.5F, 1.0F, 1.0F,
                red, green, blue, alphaByte);
        vertex(consumer, matrix, normal, 0.5F, 0.5F, 1.0F, 0.0F,
                red, green, blue, alphaByte);
        vertex(consumer, matrix, normal, -0.5F, 0.5F, 0.0F, 0.0F,
                red, green, blue, alphaByte);
        pose.popPose();
    }

    private static void vertex(VertexConsumer consumer,
                               Matrix4f matrix,
                               Matrix3f normal,
                               float x,
                               float y,
                               float u,
                               float v,
                               int red,
                               int green,
                               int blue,
                               int alpha) {
        consumer.vertex(matrix, x, y, 0.0F)
                .color(red, green, blue, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(normal, 0.0F, 0.0F, 1.0F)
                .endVertex();
    }
}
