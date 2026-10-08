package com.miningdim.district.notice;

import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * 什么时候能给玩家看自管区的私人通知 (设计文档 22.12)。
 *
 * <p>生产装的是 {@link LoginGateNoticeGate}: 按 {@code com.miningdim.core.auth.PlayerLoginGate} 判定, 登录确认之后才发。
 * {@link DefaultNoticeGate} (身份已验证的服务器上上线即发; 离线模式的服务器上一条都不发, 留在队列里) 只剩两个用途:
 * {@code DistrictSystem.register} 装 gate 之前的占位, 以及 GameTest 里核对"不放行"的行为。
 */
public interface NoticeDeliveryGate {

    /** 现在能不能投递 (提交后的即时投递、上线补发与只发在线的广播都先问它)。 */
    boolean canDeliverNow(ServerPlayer player);

    /** register 时调一次: 登记"这名玩家可以收通知了"的回调。回调自己接住一切异常。 */
    void install(Consumer<ServerPlayer> deliverPending);

    /** DistrictSystem.onPlayerLoggedIn 在首次登录补写之后调。 */
    void onPlayerJoined(ServerPlayer player);
}
