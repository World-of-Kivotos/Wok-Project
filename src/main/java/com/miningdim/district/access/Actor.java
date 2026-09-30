package com.miningdim.district.access;

import com.miningdim.district.DistrictLimits;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * 谁在操作 (设计文档 7.1)。平板: 发送者的 UUID 与名字, op 取 WebUiPermissions.isOp; 命令: 玩家或控制台 (控制台 uuid
 * 为 null), op 恒为真 (命令根已要求 2 级权限); 冻结期满自动收回: {@link #system()}, 记录里的身份固定为 system。
 * 身份 (区务长、住户、户主) 不放在这里: 每次请求都从库里现算, 不缓存。
 */
public record Actor(@Nullable UUID uuid, String name, boolean op, boolean isSystem) {

    public Actor {
        Objects.requireNonNull(name, "name");
    }

    /** 平板或命令的玩家。 */
    public static Actor player(UUID uuid, String name, boolean op) {
        return new Actor(Objects.requireNonNull(uuid, "uuid"), name, op, false);
    }

    /** 控制台 / RCON 执行命令。 */
    public static Actor console() {
        return new Actor(null, DistrictLimits.CONSOLE_ACTOR_NAME, true, false);
    }

    /** 服务器自己 (冻结期满自动收回)。 */
    public static Actor system() {
        return new Actor(null, DistrictLimits.SYSTEM_ACTOR_NAME, false, true);
    }
}
