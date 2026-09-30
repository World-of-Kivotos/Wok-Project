package com.miningdim.district.flan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本模块对 Flan 权限的固定分类 (设计文档 20.3 的权限全表, Flan 1.20.1-1.11.16)。
 *
 * <p>每个位置的值由 {@link FlanPermissionPolicy} 按 Flan 实际的权限表生成; 这里只放"哪个 id 归哪一类"。表外的 id
 * (数据包或别的模组新加的) 按"这是别人的家"保守处理, 见 Policy。
 */
public final class FlanPermissions {

    private FlanPermissions() {
    }

    /**
     * 管理类权限: 在任何组、任何默认权限里一律显式写假, 给出去等于交出领地设置。claim_message (改进出领地的提示语)
     * 同样是改领地设置, 一并归到这里 (20.3)。
     */
    public static final List<String> ADMIN = List.of("flan:edit_claim", "flan:edit_perms", "flan:edit_potions",
            "flan:claim_message");

    /**
     * 固定为真的非全局权限 (冻结时为假): 能否停留、能否丢东西、能否飞 (飞行模组与创造模式的飞行; 给飞行的
     * may_flight 另在固定为假里)。
     */
    public static final List<String> FIXED_TRUE = List.of("flan:can_stay", "flan:drop", "flan:flight");

    /**
     * 固定为假的非全局权限: 传送到领地、在领地里触发袭击、给飞行 (may_flight)、免饥饿 (no_hunger)、机械动力装置
     * (create_contraption, 只在装了机械动力时存在, 阶段 3 另有禁令)。may_flight 与 no_hunger 是 Flan 里仅有的两个
     * requireExplicitSet: 写真就等于给出去。
     */
    public static final List<String> FIXED_FALSE = List.of("flan:teleport", "flan:raid", "flan:may_flight",
            "flan:no_hunger", "flan:create_contraption");

    /**
     * 跟随关系: 目录外的 Flan 权限 -> 它跟随的目录项 (PermissionCatalog 的 permissionId)。取所跟随项那一列的值。
     * 刻意不进目录的 flanIds: flanIds 随 district.permissions 下发, 改它就是改契约数据 (20.3)。
     */
    public static final Map<String, String> FOLLOWS = followMap();

    /** Flan 的全局类权限: 不能按组设, 只能对整块领地设; 地块上一律写 UNSET, 让它们跟随自管区。 */
    public static final Set<String> GLOBAL = Set.of(
            "flan:animal_spawn", "flan:enderman", "flan:explosions", "flan:fake_player", "flan:fire_spread",
            "flan:hurt_player", "flan:lightning", "flan:lock_items", "flan:mob_spawn", "flan:piston_border",
            "flan:player_mob_spawn", "flan:sculk", "flan:snow_golem", "flan:water_border", "flan:wither");

    /**
     * Flan 1.20.1-1.11.16 的内置权限表 (jar 里的 67 个 claim_permissions JSON 去掉要求机械动力的 create_contraption,
     * 即开发环境里的 66 个)。记录型与 Disabled 网关用它代替 PermissionManager, 让 Policy 在没有 Flan 的 GameTest
     * 里按同一张表生成全表。
     */
    public static final List<KnownPermission> BUILTIN = builtin();

    public static boolean admin(String flanPermId) {
        return ADMIN.contains(flanPermId);
    }

    private static Map<String, String> followMap() {
        Map<String, String> follows = new LinkedHashMap<>();
        follows.put("flan:archeology", "break");
        follows.put("flan:jukebox", "container");
        follows.put("flan:lectern_take", "container");
        follows.put("flan:noteblock", "redstone");
        follows.put("flan:target_block", "button");
        follows.put("flan:projectiles", "button");
        follows.put("flan:xp", "pickup");
        follows.put("flan:frost_walker", "place");
        follows.put("flan:endcrystal_place", "place");
        follows.put("flan:chorus_fruit", "ender_pearl");
        return Collections.unmodifiableMap(follows);
    }

    private static List<KnownPermission> builtin() {
        // 非全局、出厂值为假 (目录项、跟随项、管理类、固定为假里除两个 requireExplicit 之外的)。
        Set<String> nonGlobalFalse = new LinkedHashSet<>(List.of(
                "animal_interact", "anvil", "archeology", "armorstand", "beacon", "bed", "boat", "break",
                "break_non_living", "bucket", "button_lever", "chorus_fruit", "claim_message", "door", "edit_claim",
                "edit_perms", "edit_potions", "endcrystal_place", "ender_pearl", "fence_gate", "frost_walker",
                "hurt_animal", "hurt_named", "interact_block", "interact_sign", "itemframe_rotate", "jukebox",
                "lectern_take", "minecart", "noteblock", "open_container", "place", "pressure_plate", "projectiles",
                "raid", "redstone", "target_block", "teleport", "trading", "trample", "trapdoor", "xp"));
        Set<String> nonGlobalTrue = new LinkedHashSet<>(List.of(
                "can_stay", "drop", "enchantment", "enderchest", "flight", "pickup", "portal"));
        Set<String> explicit = new LinkedHashSet<>(List.of("may_flight", "no_hunger"));
        Set<String> globalTrue = new LinkedHashSet<>(List.of("enderman", "lock_items", "snow_golem"));
        List<KnownPermission> table = new ArrayList<>();
        nonGlobalFalse.forEach(id -> table.add(new KnownPermission("flan:" + id, false, false, false)));
        nonGlobalTrue.forEach(id -> table.add(new KnownPermission("flan:" + id, false, true, false)));
        explicit.forEach(id -> table.add(new KnownPermission("flan:" + id, false, false, true)));
        for (String id : GLOBAL) {
            table.add(new KnownPermission(id, true, globalTrue.contains(id.substring("flan:".length())), false));
        }
        table.sort((a, b) -> a.id().compareTo(b.id()));
        return List.copyOf(table);
    }
}
