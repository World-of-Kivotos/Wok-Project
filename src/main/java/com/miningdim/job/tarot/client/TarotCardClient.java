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

    /** 本地玩家 UUID (tooltip 判"这张牌是不是我的"); 还没进世界时为 null。 */
    public static java.util.UUID localPlayerId() {
        net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        return player == null ? null : player.getUUID();
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
