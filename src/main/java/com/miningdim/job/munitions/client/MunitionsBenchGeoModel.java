package com.miningdim.job.munitions.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.block.MunitionsBenchBlock;
import com.miningdim.job.munitions.block.MunitionsBenchBlockEntity;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import software.bernie.geckolib.model.GeoModel;

public final class MunitionsBenchGeoModel extends GeoModel<MunitionsBenchBlockEntity> {

    private static final ResourceLocation ANIMATION = resource("animations/block/munitions_bench.animation.json");

    @Override
    public ResourceLocation getModelResource(MunitionsBenchBlockEntity animatable) {
        if (animatable.getBlockState().getValue(MunitionsBenchBlock.LAYOUT)
                == MunitionsBenchBlock.Layout.LEGACY_DEPTH) {
            return resource("geo/block/munitions_bench_legacy_empty.geo.json");
        }
        String benchId = benchId(animatable);
        return resource("geo/block/" + benchId + ".geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(MunitionsBenchBlockEntity animatable) {
        String benchId = benchId(animatable);
        String suffix = benchId.substring("munitions_bench".length());
        return resource("textures/block/munitions_bench_geo" + suffix + ".png");
    }

    @Override
    public ResourceLocation getAnimationResource(MunitionsBenchBlockEntity animatable) {
        return ANIMATION;
    }

    @Override
    public RenderType getRenderType(MunitionsBenchBlockEntity animatable, ResourceLocation texture) {
        return RenderType.entityTranslucent(texture);
    }

    private static String benchId(MunitionsBenchBlockEntity animatable) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(animatable.getBlockState().getBlock());
        if (id == null || !MiningConstants.MODID.equals(id.getNamespace())
                || !(id.getPath().equals("munitions_bench") || id.getPath().startsWith("munitions_bench_"))) {
            throw new IllegalStateException("Unexpected block for munitions bench renderer: " + id);
        }
        return id.getPath();
    }

    private static ResourceLocation resource(String path) {
        return new ResourceLocation(MiningConstants.MODID, path);
    }
}
