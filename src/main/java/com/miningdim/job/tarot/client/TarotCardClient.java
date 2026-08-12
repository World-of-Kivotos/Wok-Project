package com.miningdim.job.tarot.client;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.NotNull;

/** Client extension that binds every tarot-card stack to the true two-sided renderer. */
public final class TarotCardClient implements IClientItemExtensions {

    private BlockEntityWithoutLevelRenderer renderer;

    private TarotCardClient() {
    }

    public static IClientItemExtensions extension() {
        return new TarotCardClient();
    }

    @Override
    @NotNull
    public BlockEntityWithoutLevelRenderer getCustomRenderer() {
        if (renderer == null) {
            renderer = new TarotCardItemRenderer();
        }
        return renderer;
    }
}
