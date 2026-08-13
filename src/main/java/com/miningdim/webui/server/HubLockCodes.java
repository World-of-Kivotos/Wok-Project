package com.miningdim.webui.server;

/**
 * hub 面板锁定原因码全集 (WebUI 接线契约 W1 / 决策 D2)。
 *
 * 与 {@link WebUiErrorCodes} 是<b>两个命名空间</b>, 从第一天就分表, 严禁合并:
 * 错误码回答的是"这次调用为什么失败", 锁定码回答的是"这个面板此刻为什么进不去"。合成一张表之后, 后续要加
 * "职业等级不足""未婚"这类锁定原因就会被迫塞进错误码表, 而前端一张本地化字典同时服务两种语义, 撞键时
 * 两边的文案会静默串号 —— 症状是玩家点开一个正常面板看到一句"余额不足"。
 *
 * 前端同理: lockCode 与 errorCode 的中文文案必须是分开的两张映射表。
 *
 * 码值即对外契约, 一旦下发不许改名 (改名 = 前端文案整条失配, 玩家看到英文原码)。
 */
public final class HubLockCodes {

    private HubLockCodes() {
    }

    /**
     * 该面板仅 OP 可进。当前唯一持有者是 {@code panelId='admin'}; 判据是
     * {@code PlayerList.isOp(GameProfile)}, 与 player.isOp / player.profile.isOp 同一口径
     * (见 {@link WebUiPermissions#isOp}), 服务端不存在第二套判定。
     */
    public static final String NOT_OP = "NOT_OP";
}
