package com.miningdim.command;

import net.minecraft.commands.CommandSourceStack;

/**
 * 命令权限判定 (设计文档 17.1/17.5)。权限基于原版 OP 等级 source.hasPermission(level)。
 *
 * 17.2 等级语义: 0 普通玩家 / 2 管理查询 (list/tp) / 3 管理操作 (kick) / 4 破坏性 (reset)。
 * 17.5 PermissionAPI 对接为可选增强: 设计要求"无插件用 OP, 有插件用节点", 二者共用一套命令代码。
 * 本期 (命令子系统) 落地 OP 等级路径 —— 它是默认且开箱即用; PermissionAPI 节点注册涉及 registry 期的
 * PermissionNode 登记 (RegisterEvent), 属另一装配点, 不在命令包内伪造。节点名常量在此预登记供未来桥接复用,
 * 当前判定仅走 OP 等级回退 (与 17.5 "无 handler 回退 OP 等级"语义一致)。
 */
public final class MiningPermissions {

    private MiningPermissions() {
    }

    // 17.2 OP 等级回退值。
    public static final int LEVEL_PLAYER = 0;
    public static final int LEVEL_ADMIN_QUERY = 2;
    public static final int LEVEL_ADMIN_KICK = 3;
    public static final int LEVEL_RESET = 4;

    // 17.5 PermissionAPI 节点名 (供未来桥接; 当前未注册节点, 判定走 OP 回退)。
    public static final String NODE_ENTER = "miningdim.command.enter";
    public static final String NODE_ADMIN_LIST = "miningdim.command.admin.list";
    public static final String NODE_ADMIN_KICK = "miningdim.command.admin.kick";
    public static final String NODE_RESET = "miningdim.command.reset";

    /**
     * 判定 source 是否具备给定 OP 回退等级 (17.5 单一判定点)。
     * 命令树 .requires(...) 用本方法做静态门控; 玩家级命令传 LEVEL_PLAYER 恒真 (含非 OP)。
     */
    public static boolean has(CommandSourceStack source, int fallbackOpLevel) {
        return source.hasPermission(fallbackOpLevel);
    }
}
