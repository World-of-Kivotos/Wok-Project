package com.miningdim.district.flan.real;

import io.github.flemmli97.flan.claim.Claim;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

/**
 * 真网关唯一要反射的两个私有字段 (设计文档 20.1、对接说明 D 节), 自检通过后解析一次:
 * <ul>
 *   <li>{@code Claim.permissions}: 删组键 (清理继承来的组、deleteGroup)。Flan 的 removePermGroup 要一个在自己位置上
 *       通过 EDITPERMS 的玩家, 服务端代办给不出来。直接删键不会标脏, 调用方必须自己 setDirty(true)。</li>
 *   <li>{@code Claim.playersGroups}: 只读成员表 (公开的替代路线 toJson 在父领地上会把每块地一起序列化)。</li>
 * </ul>
 */
final class FlanReflection {

    private final Field permissions;
    private final Field playersGroups;

    private FlanReflection(Field permissions, Field playersGroups) {
        this.permissions = permissions;
        this.playersGroups = playersGroups;
    }

    static FlanReflection resolve() throws ReflectiveOperationException {
        Field permissions = Claim.class.getDeclaredField("permissions");
        Field playersGroups = Claim.class.getDeclaredField("playersGroups");
        permissions.setAccessible(true);
        playersGroups.setAccessible(true);
        return new FlanReflection(permissions, playersGroups);
    }

    /** 组 -&gt; (权限 -&gt; 值) 的活 Map (可改, 改完自己标脏)。 */
    @SuppressWarnings("unchecked")
    Map<String, Map<ResourceLocation, Boolean>> permissions(Claim claim) {
        try {
            return (Map<String, Map<ResourceLocation, Boolean>>) permissions.get(claim);
        } catch (IllegalAccessException impossible) {
            throw new IllegalStateException("Claim.permissions became inaccessible", impossible);
        }
    }

    /** 成员 -&gt; 组的活 Map (只读; 改成员一律走 setPlayerGroup)。 */
    @SuppressWarnings("unchecked")
    Map<UUID, String> playersGroups(Claim claim) {
        try {
            return (Map<UUID, String>) playersGroups.get(claim);
        } catch (IllegalAccessException impossible) {
            throw new IllegalStateException("Claim.playersGroups became inaccessible", impossible);
        }
    }
}
