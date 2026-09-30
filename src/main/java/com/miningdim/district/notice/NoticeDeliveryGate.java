package com.miningdim.district.notice;

import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * 什么时候能给玩家看自管区的私人通知 (设计文档 22.12)。
 *
 * <p>本分支的实现是 {@link DefaultNoticeGate} (身份已验证的服务器上上线即发; 离线模式的服务器上一条都不发, 留在队列里)。
 * 登录门分支 (PR #72) 合入之后, 换成一个按
 * {@code com.miningdim.core.auth.PlayerLoginGate} 判定的实现 (22.12 的合并清单): {@code canDeliverNow} =
 * {@code PlayerLoginGate.allows}, {@code install} 登记 {@code PlayerLoginGate.onLoginConfirmed}, {@code onPlayerJoined}
 * 什么都不做。本分支不复制登录门的任何代码。
 */
public interface NoticeDeliveryGate {

    /** 现在能不能投递 (提交后的即时投递、上线补发与只发在线的广播都先问它)。 */
    boolean canDeliverNow(ServerPlayer player);

    /** register 时调一次: 登记"这名玩家可以收通知了"的回调。回调自己接住一切异常。 */
    void install(Consumer<ServerPlayer> deliverPending);

    /** DistrictSystem.onPlayerLoggedIn 在首次登录补写之后调。 */
    void onPlayerJoined(ServerPlayer player);
}
