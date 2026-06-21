package com.miningdim.job.brewer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 闪耀永久特殊的生命周期事件接线 (阶段 5(iii)(iv) 闭环): 死亡清 + 登录重挂 + 周期回血。
 *
 *  - 死亡 ({@link LivingDeathEvent}): 清该玩家全部永久层 ({@link BrewBuffStore#clearAll}) + 移除身上全部永久修饰/
 *    效果 ({@link BrewPermanentBuffs#clearAll}) —— 一条命语义。
 *  - 登录 ({@link PlayerEvent.PlayerLoggedInEvent}): 据存的层数重挂全部永久特殊 (属性/effect 不跨会话, 必须重挂)。
 *  - 玩家 tick: 推进威士忌/香槟周期回血 (按存的层数读最大血回血)。
 *
 * 持 {@link BrewPermanentBuffs} 引用 (由 {@link BrewerSystem} 装配)。仅服务端逻辑 (ServerPlayer 守卫)。
 */
public final class BrewPermanentBuffHandlers {

    private final BrewPermanentBuffs buffs;

    public BrewPermanentBuffHandlers(BrewPermanentBuffs buffs) {
        this.buffs = buffs;
    }

    // LOW 优先级: 在塔罗复活契约 (HIGH 处 setCanceled) 之后跑, 确保读到本次致死的最终取消态 —— 契约救命时不清层。
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onDeath(LivingDeathEvent event) {
        if (event.isCanceled()) {
            // 别的子系统 (如塔罗复活契约) 已拦截本次致死: 玩家未真死, 不清永久层 (continuation 仍有效)。
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // 一条命: 真死才清层 + 清身上修饰/效果。即便 store 无层也清身上 (兜底防残留)。
        BrewBuffStore.get(player.server.overworld()).clearAll(player.getUUID());
        buffs.clearAll(player);
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        BrewBuffStore store = BrewBuffStore.get(player.server.overworld());
        if (!store.hasAnyLayers(player.getUUID()) && store.moonshinePerks(player.getUUID()).isEmpty()) {
            return; // 无任何永久层: 不重挂 (省开销)。
        }
        buffs.remountAll(player, store, BrewPermanentBuffs.tarotMaxHealthBonus(player));
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player)) {
            return;
        }
        long t = player.tickCount;
        // 仅在两个周期边界的 tick 才取 store (摊薄: 每 20/600 tick 才读一次, 非每 tick)。
        boolean champagneTick = t % BrewerConstants.CHAMPAGNE_HEAL_INTERVAL_TICKS == 0;
        boolean whiskeyTick = t % BrewerConstants.WHISKEY_HEAL_INTERVAL_TICKS == 0;
        if (!champagneTick && !whiskeyTick) {
            return;
        }
        BrewBuffStore store = BrewBuffStore.get(player.server.overworld());
        int whiskeyLayers = store.layers(player.getUUID(), WineType.WHISKEY);
        int champagneLayers = store.layers(player.getUUID(), WineType.CHAMPAGNE);
        if (whiskeyLayers <= 0 && champagneLayers <= 0) {
            return;
        }
        buffs.tickPeriodicHeal(player, whiskeyLayers, champagneLayers);
    }
}
