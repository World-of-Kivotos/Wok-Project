package com.miningdim.job.tarot;

import net.minecraft.world.inventory.tooltip.TooltipComponent;

/** 客户端悬停完整卡面的公共数据载体; 不引用任何客户端类, 专用服务器可安全加载。 */
public record TarotCardTooltip(int cardId, TarotQuality quality, boolean upright) implements TooltipComponent {

    public TarotCardTooltip {
        TarotArcana.byId(cardId);
        if (quality == null) {
            throw new IllegalArgumentException("tarot tooltip quality must not be null");
        }
    }
}
