package com.miningdim.job.tarot.codex;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 塔罗图鉴的玩家生命周期接线：登录 / 重生后补扫背包，把“当前看得到的牌”补进图鉴。
 *
 * <p>只补当前持有，不追历史消耗；老玩家首次登录会点亮现有收藏，之后按新获得增量记录。</p>
 */
public final class TarotCodexEvents {

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TarotCodex.scan(player);
        }
    }

    @SubscribeEvent
    public void onClone(PlayerEvent.Clone event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TarotCodex.scan(player);
        }
    }
}
