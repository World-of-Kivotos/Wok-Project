package com.miningdim.webui.server;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

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

    /**
     * admin.* 动作的统一权限门: 非 OP 即抛 {@link WebUiErrorCodes#PERMISSION_DENIED} 业务异常。
     *
     * 收成一个抛出点是为了让"权限拒绝"只有一种回执形状。此前四个 admin 动作类各写各的, 分裂成了三种:
     * 裸 {@code IllegalStateException} 落进 Gateway 通用兜底 (无 errorCode, 且带整条 WARN 堆栈)、
     * 套 {@code INVALID_REQUEST} 冒充入参非法、以及本方法。前端为同一种拒绝写三套分支, 迟早漏一套。
     *
     * 仍然是每个 handler 各自调用, 不是 Gateway 兜底 —— 架构铁律 1 未变: 派发器不做任何权限判断。
     *
     * @param action 被拒的 action 名, 进 params.action 供前端定位是哪个后台功能被拦
     */
    public static void requireOp(ServerPlayer sender, String action) {
        if (!isOp(sender)) {
            throw new WebUiBusinessException(WebUiErrorCodes.PERMISSION_DENIED,
                    "需要 OP 权限", false, Map.of("action", action));
        }
    }
}
