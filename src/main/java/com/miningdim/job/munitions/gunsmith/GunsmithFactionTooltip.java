package com.miningdim.job.munitions.gunsmith;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** 服务端安全的组件名牌数据；实际名牌与可选势力标识仅在客户端绘制。 */
public record GunsmithFactionTooltip(GunsmithPartRarity rarity,
                                     @Nullable GunsmithFaction faction) implements TooltipComponent {

    public GunsmithFactionTooltip {
        Objects.requireNonNull(rarity, "rarity");
    }
}
