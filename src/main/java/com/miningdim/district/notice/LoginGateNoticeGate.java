package com.miningdim.district.notice;

import com.miningdim.core.auth.PlayerLoginGate;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 按登录门判定的投递时机 (设计文档 22.12 的合并清单): 玩家通过登录门之后才看得到自管区的私人通知。
 *
 * <p>登录门自己覆盖全部服务器形态: 单人、局域网、没装 AccessHub 的服务器上进服那一刻就放行 (监听者以 atJoin = true
 * 触发), 装了 AccessHub 的服务器上等 /login 生效 (巡检以 atJoin = false 触发)。所以这里不再自己判断服务器验不验证身份。
 */
public final class LoginGateNoticeGate implements NoticeDeliveryGate {

    @Override
    public boolean canDeliverNow(ServerPlayer player) {
        return PlayerLoginGate.allows(player);
    }

    @Override
    public void install(Consumer<ServerPlayer> deliverPending) {
        Objects.requireNonNull(deliverPending, "deliverPending");
        // 登记对象不留: 登录门停服只清等待表、不清监听者, 生产代码每个进程只登记这一次, 没有要撤销的时刻。
        PlayerLoginGate.onLoginConfirmed((player, atJoin) -> deliverPending.accept(player));
    }

    @Override
    public void onPlayerJoined(ServerPlayer player) {
        // 进服时不投递: 登录门在 PlayerLoggedInEvent 的 LOWEST 里判定 (排在自管区 NORMAL 的首次登录补写之后),
        // 放行就触发 install 登记的监听者。
    }
}
