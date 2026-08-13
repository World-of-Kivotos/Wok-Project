package com.miningdim.webui.server;

import net.minecraft.server.level.ServerPlayer;

/**
 * WebUI 侧的权限判定唯一入口。
 *
 * 存在的理由不是"复用一行代码", 是防口径分裂: player.isOp、player.profile 的 isOp 字段、hub.panels 的 admin
 * 锁三处必须给出同一个答案, 否则会出现"导航栏不显示管理后台但 profile 说你是 OP"这种自相矛盾的界面。而这个
 * 判定恰恰是本仓库已经漂移过一次的地方 —— {@code MarketAdminActions} 的类头注释至今仍写着
 * {@code hasPermissions(2)}, 而它的实现早已换成 {@code PlayerList.isOp}。
 *
 * 红线不变 (架构铁律 1): 本类只服务<b>渲染决策</b>。每个 admin.* 动作仍各自在自己的 handler 内独立校验权限,
 * {@link WebUiServerDispatcher} 这个 Gateway 不做任何权限兜底 —— 前端拿到 true 不等于服务端会放行。
 */
public final class WebUiPermissions {

    private WebUiPermissions() {
    }

    /**
     * 发送者是否为 OP。
     *
     * 用 {@code PlayerList.isOp(GameProfile)} 而非 {@code ServerPlayer.hasPermissions(int)}: 后者在 ServerPlayer
     * 上的语义跨版本不一, 而权限判定错一次的代价是管理面板对全服敞开。
     */
    public static boolean isOp(ServerPlayer sender) {
        return sender.getServer().getPlayerList().isOp(sender.getGameProfile());
    }
}
