package com.miningdim.achievement.trigger;

import com.miningdim.achievement.AchievementServices;
import com.miningdim.caseopening.CaseServices;
import com.miningdim.economy.EconomyServices;
import com.miningdim.market.MarketServices;
import com.miningdim.marriage.MarriageEvents;
import com.miningdim.quest.QuestServices;
import com.miningdim.store.MiningStore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * 经济与社交页签 P2 部分 (Achievement_System_DesignSpec 9.4、9.5、6.6) 的装配点, 由 {@code AchievementSystem} 在
 * mod 构造期调用一次。
 *
 * <ul>
 *   <li>把本模块的监听器注册进各玩法模块在自己包里提供的监听接口 (9.10): 经济的 faucet 入账、市场的成交、开箱的结算、
 *       任务的领奖、婚姻的婚礼与传送。依赖方向是成就依赖这些模块, 它们不知道成就模块的存在; 这些接口的监听器列表
 *       随进程存活, 注册一次即可。</li>
 *   <li>ServerStarting: 在存储子系统开好的共享连接上绑定市场记账表 (MiningSchema V8)。停服时随
 *       {@code AchievementServices.reset} 一起清掉。</li>
 *   <li>登录: 按记账表补查市场成就 (离线期间卖出的货), 不静默; 上线追溯 (静默) 在 {@link PlayerProgressHooks}。</li>
 * </ul>
 */
public final class SocialEconomyHooks {

    private SocialEconomyHooks() {
    }

    /** 注册监听器与 Forge 事件。 */
    public static void register(IEventBus forgeBus) {
        EconomyServices.registerFaucetListener(EconomyHooks::onFaucetCredited);
        CaseServices.registerOpeningListener(EconomyHooks::onOpeningSettled);
        MarketServices.registerTradeListener(MarketTradeHooks::onTrade);
        QuestServices.registerClaimListener(QuestClaimHooks::onQuestClaimed);
        MarriageEvents.registerWeddingListener(MarriageHooks::onWedding);
        MarriageEvents.registerTeleportListener(MarriageHooks::onSpouseTeleport);
        forgeBus.addListener(EventPriority.NORMAL, false, ServerStartingEvent.class,
                event -> AchievementServices.bindMarketPartners(
                        new SqliteMarketPartnerRepository(MiningStore.connection())));
        forgeBus.addListener(EventPriority.NORMAL, false, PlayerEvent.PlayerLoggedInEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                MarketTradeHooks.recheck(player);
            }
        });
    }
}
