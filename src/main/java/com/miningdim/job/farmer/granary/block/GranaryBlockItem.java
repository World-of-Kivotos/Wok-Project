package com.miningdim.job.farmer.granary.block;

import com.miningdim.job.farmer.granary.client.GranaryGeoRenderers;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.minecraftforge.fml.DistExecutor;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.function.Consumer;

/** The placed granary geometry is also the inventory item geometry. */
public final class GranaryBlockItem extends BlockItem implements GeoItem {

    public enum Kind {
        BIN("granary_bin"), CENTER("granary_center"), VENT("granary_vent");

        private final String modelName;

        Kind(String modelName) {
            this.modelName = modelName;
        }

        public String modelName() {
            return modelName;
        }
    }

    private final Kind kind;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public GranaryBlockItem(Block block, Properties properties, Kind kind) {
        super(block, properties);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // The granary geometry is static; there is no animation controller to register.
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> consumer.accept(GranaryGeoRenderers.itemExtension(this)));
    }
}
