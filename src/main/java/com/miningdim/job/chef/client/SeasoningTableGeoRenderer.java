package com.miningdim.job.chef.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.chef.ChefBlockEntities;
import com.miningdim.job.chef.SeasoningTableBlock;
import com.miningdim.job.chef.SeasoningTableBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/** 客户端仅在主半格渲染一次完整双格料理工位。 */
@Mod.EventBusSubscriber(modid = MiningConstants.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SeasoningTableGeoRenderer {

    private SeasoningTableGeoRenderer() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ChefBlockEntities.SEASONING_TABLE.get(), Renderer::new);
    }

    private static final class Renderer extends GeoBlockRenderer<SeasoningTableBlockEntity> {

        private Renderer(BlockEntityRendererProvider.Context context) {
            super(new Model());
        }

        @Override
        public void preRender(PoseStack poseStack, SeasoningTableBlockEntity table, BakedGeoModel model,
                              MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                              float partialTick, int packedLight, int packedOverlay,
                              float red, float green, float blue, float alpha) {
            Direction right = table.getBlockState().getValue(SeasoningTableBlock.FACING).getClockWise();
            // Geo 模型以两格之间的边界为中心；从主格中心沿朝向右侧平移半格后再旋转。
            poseStack.translate(right.getStepX() * 0.5D, 0.0D, right.getStepZ() * 0.5D);
            super.preRender(poseStack, table, model, bufferSource, buffer, isReRender,
                    partialTick, packedLight, packedOverlay, red, green, blue, alpha);
        }

        @Override
        public void renderRecursively(PoseStack poseStack, SeasoningTableBlockEntity table, GeoBone bone,
                                      RenderType renderType, MultiBufferSource bufferSource,
                                      VertexConsumer buffer, boolean isReRender, float partialTick,
                                      int packedLight, int packedOverlay,
                                      float red, float green, float blue, float alpha) {
            int boneLight = bone.getName().startsWith("fire_") ? LightTexture.FULL_BRIGHT : packedLight;
            super.renderRecursively(poseStack, table, bone, renderType, bufferSource, buffer,
                    isReRender, partialTick, boneLight, packedOverlay, red, green, blue, alpha);
        }
    }

    private static final class Model extends GeoModel<SeasoningTableBlockEntity> {

        private static final ResourceLocation MODEL = new ResourceLocation(
                MiningConstants.MODID, "geo/block/seasoning_table.geo.json");
        private static final ResourceLocation ANIMATION = new ResourceLocation(
                MiningConstants.MODID, "animations/block/seasoning_table.animation.json");

        @Override
        public ResourceLocation getModelResource(SeasoningTableBlockEntity table) {
            return MODEL;
        }

        @Override
        public ResourceLocation getTextureResource(SeasoningTableBlockEntity table) {
            SeasoningTableBlock block = (SeasoningTableBlock) table.getBlockState().getBlock();
            return new ResourceLocation(MiningConstants.MODID,
                    "textures/block/seasoning_table_" + block.tierCap().id() + "_geo.png");
        }

        @Override
        public ResourceLocation getAnimationResource(SeasoningTableBlockEntity table) {
            return ANIMATION;
        }

        @Override
        public RenderType getRenderType(SeasoningTableBlockEntity table, ResourceLocation texture) {
            return RenderType.entityTranslucent(texture);
        }
    }
}
