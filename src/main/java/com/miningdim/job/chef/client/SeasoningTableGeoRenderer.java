package com.miningdim.job.chef.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.chef.ChefBlockEntities;
import com.miningdim.job.chef.SeasoningTableBlock;
import com.miningdim.job.chef.SeasoningTableBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
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

        private static final ItemStack TOMATO = icon("farmersdelight", "tomato");
        private static final ItemStack CABBAGE = icon("farmersdelight", "cabbage");
        private static final ItemStack POTATO = new ItemStack(Items.POTATO);

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

        @Override
        public void postRender(PoseStack poseStack, SeasoningTableBlockEntity table, BakedGeoModel model,
                               MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                               float partialTick, int packedLight, int packedOverlay,
                               float red, float green, float blue, float alpha) {
            if (isReRender) {
                return;
            }
            renderPrepIcon(poseStack, table, bufferSource, TOMATO,
                    1.55F, -4.45F, -12.0F, 0.34F, packedLight, packedOverlay, 1);
            renderPrepIcon(poseStack, table, bufferSource, CABBAGE,
                    4.0F, -3.4F, 8.0F, 0.37F, packedLight, packedOverlay, 2);
            renderPrepIcon(poseStack, table, bufferSource, POTATO,
                    6.45F, -4.25F, -18.0F, 0.33F, packedLight, packedOverlay, 3);
        }

        private static void renderPrepIcon(PoseStack poseStack, SeasoningTableBlockEntity table,
                                           MultiBufferSource bufferSource, ItemStack stack,
                                           float modelX, float modelZ, float yaw, float scale,
                                           int packedLight, int packedOverlay, int seed) {
            if (stack.isEmpty()) {
                return;
            }
            poseStack.pushPose();
            poseStack.translate(modelX / 16.0F, 12.08F / 16.0F, modelZ / 16.0F);
            poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            poseStack.scale(scale, scale, scale);
            Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED,
                    packedLight, packedOverlay, poseStack, bufferSource, table.getLevel(), seed);
            poseStack.popPose();
        }

        private static ItemStack icon(String namespace, String path) {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(namespace, path));
            return item == null || item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
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
