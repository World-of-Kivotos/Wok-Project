package com.miningdim.job.farmer.granary.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import com.miningdim.job.farmer.granary.block.GranaryBlockItem;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import com.miningdim.job.farmer.granary.block.GranaryCenterBlockEntity;
import com.miningdim.job.farmer.granary.block.GranaryVentBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
import software.bernie.geckolib.renderer.GeoItemRenderer;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Renders the same Bedrock geometry in the world and in all 93 block items. */
@Mod.EventBusSubscriber(modid = MiningConstants.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GranaryGeoRenderers {

    private static final Set<String> WOODS = Set.of("oak", "spruce", "birch", "jungle", "acacia",
            "dark_oak", "mangrove", "cherry", "bamboo", "crimson", "warped");
    private static final Set<String> STONES = Set.of("quartz", "smooth_quartz", "stone", "smooth_stone",
            "cobblestone", "mossy_cobblestone", "stone_bricks", "mossy_stone_bricks", "bricks",
            "deepslate", "cobbled_deepslate", "deepslate_bricks", "deepslate_tiles", "blackstone",
            "polished_blackstone", "polished_blackstone_bricks", "sandstone", "smooth_sandstone",
            "red_sandstone", "smooth_red_sandstone");
    private static final Map<String, ResourceLocation> TEXTURES = new HashMap<>();

    private GranaryGeoRenderers() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(GranaryBlocks.BIN_ENTITY.get(),
                context -> new BlockRenderer<GranaryBinBlockEntity>(GranaryBlockItem.Kind.BIN));
        event.registerBlockEntityRenderer(GranaryBlocks.CENTER_ENTITY.get(),
                context -> new BlockRenderer<GranaryCenterBlockEntity>(GranaryBlockItem.Kind.CENTER));
        event.registerBlockEntityRenderer(GranaryBlocks.VENT_ENTITY.get(),
                context -> new BlockRenderer<GranaryVentBlockEntity>(GranaryBlockItem.Kind.VENT));
    }

    public static IClientItemExtensions itemExtension(GranaryBlockItem item) {
        return new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    renderer = new ItemRenderer(item);
                }
                return renderer;
            }
        };
    }

    private static final class BlockRenderer<T extends BlockEntity & GeoBlockEntity> extends GeoBlockRenderer<T> {

        private BlockRenderer(GranaryBlockItem.Kind kind) {
            super(new BlockModel<>(kind));
        }

        @Override
        public void renderRecursively(PoseStack poseStack, T animatable, GeoBone bone,
                                      RenderType renderType, MultiBufferSource bufferSource,
                                      VertexConsumer buffer, boolean isReRender, float partialTick,
                                      int packedLight, int packedOverlay,
                                      float red, float green, float blue, float alpha) {
            if (bone.getName().startsWith("mat_")) {
                ResourceLocation texture = boneTexture(animatable.getBlockState().getBlock(), bone.getName());
                renderType = RenderType.entityCutout(texture);
                buffer = bufferSource.getBuffer(renderType);
            }
            super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer,
                    isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
        }
    }

    private static final class ItemRenderer extends GeoItemRenderer<GranaryBlockItem> {

        private ItemRenderer(GranaryBlockItem item) {
            super(new ItemModel(item.kind()));
        }

        @Override
        public void renderRecursively(PoseStack poseStack, GranaryBlockItem animatable, GeoBone bone,
                                      RenderType renderType, MultiBufferSource bufferSource,
                                      VertexConsumer buffer, boolean isReRender, float partialTick,
                                      int packedLight, int packedOverlay,
                                      float red, float green, float blue, float alpha) {
            if (bone.getName().startsWith("mat_")) {
                ResourceLocation texture = boneTexture(animatable.getBlock(), bone.getName());
                renderType = RenderType.entityCutout(texture);
                buffer = bufferSource.getBuffer(renderType);
            }
            super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer,
                    isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
        }
    }

    private static final class BlockModel<T extends BlockEntity & GeoBlockEntity> extends GeoModel<T> {

        private final ResourceLocation model;

        private BlockModel(GranaryBlockItem.Kind kind) {
            this.model = model(kind);
        }

        @Override
        public ResourceLocation getModelResource(T animatable) {
            return model;
        }

        @Override
        public ResourceLocation getTextureResource(T animatable) {
            return texture(animatable.getBlockState().getBlock(), "wood");
        }

        @Override
        public ResourceLocation getAnimationResource(T animatable) {
            return null;
        }

        @Override
        public RenderType getRenderType(T animatable, ResourceLocation texture) {
            return RenderType.entityCutout(texture);
        }
    }

    private static final class ItemModel extends GeoModel<GranaryBlockItem> {

        private final ResourceLocation model;

        private ItemModel(GranaryBlockItem.Kind kind) {
            this.model = model(kind);
        }

        @Override
        public ResourceLocation getModelResource(GranaryBlockItem animatable) {
            return model;
        }

        @Override
        public ResourceLocation getTextureResource(GranaryBlockItem animatable) {
            return texture(animatable.getBlock(), "wood");
        }

        @Override
        public ResourceLocation getAnimationResource(GranaryBlockItem animatable) {
            return null;
        }

        @Override
        public RenderType getRenderType(GranaryBlockItem animatable, ResourceLocation texture) {
            return RenderType.entityCutout(texture);
        }
    }

    private static ResourceLocation model(GranaryBlockItem.Kind kind) {
        return new ResourceLocation(MiningConstants.MODID, "geo/block/" + kind.modelName() + ".geo.json");
    }

    private static ResourceLocation boneTexture(Block block, String boneName) {
        int separator = boneName.indexOf("__", 4);
        if (!boneName.startsWith("mat_") || separator < 5) {
            throw new IllegalArgumentException("Invalid granary material bone: " + boneName);
        }
        return texture(block, boneName.substring(4, separator));
    }

    private static ResourceLocation texture(Block block, String key) {
        String material = material(block);
        return TEXTURES.computeIfAbsent(material + ":" + key,
                ignored -> source(material, key));
    }

    private static String material(Block block) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
        if (id == null || !MiningConstants.MODID.equals(id.getNamespace())) {
            throw new IllegalArgumentException("Unregistered granary block: " + block);
        }
        String path = id.getPath();
        if (path.equals("granary_bin") || path.equals("granary_center") || path.equals("granary_vent")) {
            return "oak";
        }
        int separator = path.indexOf("_granary_");
        if (separator <= 0) {
            throw new IllegalArgumentException("Unknown granary block ID: " + id);
        }
        String material = path.substring(0, separator);
        if (!WOODS.contains(material) && !STONES.contains(material)) {
            throw new IllegalArgumentException("Unknown granary material in " + id);
        }
        return material;
    }

    private static ResourceLocation source(String material, String key) {
        return switch (key) {
            case "wood" -> vanilla(base(material));
            case "wood_top" -> vanilla(top(material));
            case "wood_bottom" -> vanilla(bottom(material));
            case "trim" -> vanilla(trim(material));
            case "trim_top" -> vanilla(trimTop(material));
            case "iron", "latch" -> vanilla("iron_block");
            case "metal_dark" -> vanilla("anvil");
            case "brass" -> vanilla("gold_block");
            case "cloth" -> vanilla("green_wool");
            case "seal" -> vanilla("red_terracotta");
            case "ink", "shadow" -> vanilla("black_concrete");
            case "feather" -> vanilla("white_wool");
            case "paper" -> detail("page_edges");
            case "ledger" -> detail("ledger_page");
            case "wheat" -> detail("wheat_mark");
            default -> throw new IllegalArgumentException("Unknown granary bone material: " + key);
        };
    }

    private static String base(String material) {
        if (WOODS.contains(material)) {
            return material + "_planks";
        }
        return switch (material) {
            case "quartz" -> "quartz_block_side";
            case "smooth_quartz" -> "quartz_block_bottom";
            case "smooth_sandstone" -> "sandstone_top";
            case "smooth_red_sandstone" -> "red_sandstone_top";
            default -> material;
        };
    }

    private static String top(String material) {
        return switch (material) {
            case "quartz" -> "quartz_block_top";
            case "deepslate", "blackstone", "sandstone", "red_sandstone" -> material + "_top";
            default -> base(material);
        };
    }

    private static String bottom(String material) {
        return switch (material) {
            case "quartz" -> "quartz_block_bottom";
            case "deepslate", "blackstone" -> material + "_top";
            case "sandstone", "red_sandstone" -> material + "_bottom";
            default -> base(material);
        };
    }

    private static String trim(String material) {
        if (!WOODS.contains(material)) {
            return base(material);
        }
        return switch (material) {
            case "bamboo" -> "stripped_bamboo_block";
            case "crimson", "warped" -> "stripped_" + material + "_stem";
            default -> "stripped_" + material + "_log";
        };
    }

    private static String trimTop(String material) {
        return switch (material) {
            case "quartz" -> "quartz_block_top";
            case "deepslate", "blackstone" -> material + "_top";
            case "sandstone", "red_sandstone" -> material + "_top";
            default -> WOODS.contains(material) ? trim(material) + "_top" : base(material);
        };
    }

    private static ResourceLocation vanilla(String name) {
        return new ResourceLocation("minecraft", "textures/block/" + name + ".png");
    }

    private static ResourceLocation detail(String name) {
        return new ResourceLocation(MiningConstants.MODID, "textures/block/granary/" + name + ".png");
    }
}
