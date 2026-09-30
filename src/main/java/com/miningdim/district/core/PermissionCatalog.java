package com.miningdim.district.core;

import com.miningdim.district.DistrictLimits;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 权限目录: 36 项 (29 member + 7 region) 与一条固定规则。label、detail、outsiderRisk、districtRisk、plotRisk 逐字抄自
 * webui/src/mock/district-seed.ts 第 407–593 行, 它们是玩家看到的文字; 改文案两边一起改。
 *
 * 公共区域的默认值: 住户常用项全开, 只关"踩坏农田和海龟蛋"与"伤害有名字的动物"; 外人是 Flan 自带 Visitor 组的
 * 11 项外加"捡地上的东西"; 区域规则与 Flan 新领地一致。地块三列按"这是别人的家"定, 比公共区域保守
 * (三张名单见 {@link #PLOT_FRIEND_DEFAULT_OFF} 等)。
 */
public final class PermissionCatalog {

    private PermissionCatalog() {
    }

    /** 固定规则 (district.permissions fixedRules): 服务端写死、谁都改不了。 */
    public record FixedRule(String ruleId, String label, String valueText, String detail) {
    }

    /** 一组开关。 */
    public record Group(String groupId, String label, PermissionScope scope, List<PermissionItemDef> items) {

        public Group {
            items = List.copyOf(items);
        }
    }

    /** 地块朋友列默认关的项; 其余全开。 */
    private static final Set<String> PLOT_FRIEND_DEFAULT_OFF = Set.of("trample", "hurt_animal", "hurt_named");
    /** 地块其他住户列默认开的项: 无害交互, 外加"捡地上的东西"。 */
    private static final Set<String> PLOT_RESIDENT_DEFAULT_ON =
            Set.of("door", "trapdoor", "fence_gate", "button", "plate", "pickup");
    /** 地块外人列默认开的项: 在其他住户的基础上再收掉活板门和栅栏门。 */
    private static final Set<String> PLOT_OUTSIDER_DEFAULT_ON = Set.of("door", "button", "plate", "pickup");

    /** 机械动力禁令 (设计文档 22.4、22.5、22.13): 机器禁用、装饰方块放行, OP 亲手放置例外。 */
    public static final List<FixedRule> FIXED_RULES = List.of(new FixedRule(
            "create_ban",
            "机械动力",
            "机器禁用，装饰可放，OP 例外",
            "自管区内和外围 " + DistrictLimits.BUFFER_BLOCKS
                    + " 格内不能放机械动力的机器（会转、会动或带功能的方块，轨道也算），外壳、支架、梯子、石材、玻璃这类纯装饰方块"
                    + "照常可放；机械动力的机器也改不了自管区里的方块。管理员（OP）亲手放置不受限。服务器直接拦，这条规则谁都改不了"));

    public static final List<Group> GROUPS = List.of(
            new Group("build", "建造", PermissionScope.MEMBER, List.of(
                    member("build", "place", "放置方块", "骨粉、睡莲也算", List.of("flan:place"), true, false,
                            "任何路过的玩家都能在公共区域随手放方块，可能堵路、乱盖。",
                            "别人能在这块地里随手放方块，可能堵门、乱盖。"),
                    member("build", "break", "破坏方块", "挖掉这里的任何方块", List.of("flan:break"), true, false,
                            "任何路过的玩家都能拆公共区域的建筑、挖走方块，拆掉的只能手动修回来。",
                            "别人能拆这块地里的建筑、挖走方块，拆掉的只能手动修回来。"),
                    member("build", "bucket", "用桶", "装、倒水和岩浆", List.of("flan:bucket"), true, false,
                            "任何路过的玩家都能往公共区域倒岩浆、放水，可能烧掉或淹掉建筑。",
                            "别人能往这块地里倒岩浆、放水，房子可能被烧掉或淹掉。"),
                    member("build", "sign", "改告示牌", "改文字、染色、上蜡", List.of("flan:interact_sign"), true, false,
                            null, null),
                    member("build", "trample", "踩坏农田和海龟蛋", "关着时在耕地上跳也踩不坏庄稼",
                            List.of("flan:trample"), false, false,
                            "任何路过的玩家都能踩坏公共区域农田里的庄稼、踩碎海龟蛋。",
                            "别人能踩坏这块地里的庄稼、踩碎海龟蛋。"))),
            new Group("storage", "箱子与机器", PermissionScope.MEMBER, List.of(
                    member("storage", "container", "开箱子等容器",
                            "箱子、木桶、熔炉、酿造台、漏斗等带物品栏的方块，也包括运输矿车",
                            List.of("flan:open_container"), true, false,
                            "任何路过的玩家都能拿走公共区域的箱子、熔炉里的东西。",
                            "别人能拿走这块地里箱子、熔炉里的东西。"),
                    member("storage", "use_block", "用工作台等功能方块",
                            "工作台、切石机、织布机等没有单独开关的方块，也包括没有物品栏的模组机器",
                            List.of("flan:interact_block"), true, false, null, null),
                    member("storage", "anvil", "用铁砧", null, List.of("flan:anvil"), true, false, null, null),
                    member("storage", "enchant", "用附魔台", null, List.of("flan:enchantment"), true, true, null, null),
                    member("storage", "enderchest", "用末影箱", "里面是各人自己的东西，别人拿不到",
                            List.of("flan:enderchest"), true, true, null, null),
                    member("storage", "beacon", "调信标", "更换信标给的效果", List.of("flan:beacon"), true, false,
                            null, null))),
            new Group("doors", "门与红石", PermissionScope.MEMBER, List.of(
                    member("doors", "door", "开关门", null, List.of("flan:door"), true, true, null, null),
                    member("doors", "trapdoor", "开关活板门", null, List.of("flan:trapdoor"), true, true, null, null),
                    member("doors", "fence_gate", "开关栅栏门", null, List.of("flan:fence_gate"), true, true,
                            null, null),
                    member("doors", "button", "按按钮和拉杆", null, List.of("flan:button_lever"), true, true,
                            null, null),
                    member("doors", "plate", "踩压力板", null, List.of("flan:pressure_plate"), true, true, null, null),
                    member("doors", "redstone", "调红石元件", "中继器、比较器、红石粉、阳光探测器",
                            List.of("flan:redstone"), true, false, null, null))),
            new Group("living", "生活", PermissionScope.MEMBER, List.of(
                    member("living", "bed", "睡床", "也会把重生点设在这里", List.of("flan:bed"), true, true, null, null),
                    member("living", "trading", "和村民交易", "也包括流浪商人", List.of("flan:trading"), true, true,
                            null, null),
                    member("living", "itemframe", "转展示框里的物品", null, List.of("flan:itemframe_rotate"), true, true,
                            null, null),
                    member("living", "armorstand", "给盔甲架换装备", "能把盔甲架上的装备拿下来",
                            List.of("flan:armorstand"), true, false,
                            "任何路过的玩家都能从公共区域的盔甲架上拿走装备。",
                            "别人能从这块地的盔甲架上拿走装备。"),
                    member("living", "break_entity", "打掉展示框和盔甲架", "也包括停着的矿车和船",
                            List.of("flan:break_non_living"), true, false,
                            "任何路过的玩家都能打掉公共区域的展示框和盔甲架，里面的东西会掉出来被捡走。",
                            "别人能打掉这块地里的展示框和盔甲架，里面的东西会掉出来被捡走。"))),
            new Group("animals", "动物", PermissionScope.MEMBER, List.of(
                    member("animals", "animal", "喂养和剪毛挤奶", "也包括繁殖、上鞍；自己驯服的宠物不受这项限制",
                            List.of("flan:animal_interact"), true, false, null, null),
                    member("animals", "hurt_animal", "伤害动物", "包括宰杀家畜", List.of("flan:hurt_animal"), true, false,
                            "任何路过的玩家都能宰杀公共区域养的动物。",
                            "别人能宰杀这块地里养的动物。"),
                    member("animals", "hurt_named", "伤害有名字的动物",
                            "挂了命名牌的宠物和家畜；这项关着，就算上一项开着也打不了它们",
                            List.of("flan:hurt_named"), false, false,
                            "任何路过的玩家都能伤害公共区域里起了名字的宠物和家畜。",
                            "别人能伤害这块地里起了名字的宠物和家畜。"))),
            new Group("items", "物品与移动", PermissionScope.MEMBER, List.of(
                    member("items", "pickup", "捡地上的东西", "关着时，死在这里的人连自己的掉落物也可能捡不回来",
                            List.of("flan:pickup"), true, true, null, null),
                    member("items", "ender_pearl", "扔末影珍珠", "关着时珍珠落地就消失，不会传送",
                            List.of("flan:ender_pearl"), true, false, null, null),
                    member("items", "ride", "坐船和矿车", "关着时也不能坐着船进来",
                            List.of("flan:boat", "flan:minecart"), true, false, null, null),
                    member("items", "portal", "走下界传送门", null, List.of("flan:portal"), true, true, null, null))),
            new Group("region", "区域规则", PermissionScope.REGION, List.of(
                    region("pvp", "玩家互相攻击（PvP）", "关着时谁在本区都打不了人", "flan:hurt_player", false, false,
                            "在本区谁都能打谁，住户也可能被路过的人打死。"),
                    region("explosions", "爆炸伤害（方块和生物）", "TNT、苦力怕能不能炸坏本区方块、伤到生物",
                            "flan:explosions", false, false,
                            "TNT、苦力怕会炸坏本区建筑，也会伤到区里的人和动物。"),
                    region("wither", "凋灵破坏方块", null, "flan:wither", false, false,
                            "凋灵会炸掉本区的方块，建筑可能被炸出大洞。"),
                    region("fire_spread", "火焰蔓延", "火能不能在本区烧开", "flan:fire_spread", false, false,
                            "火会在本区烧开，木头建筑可能整栋烧掉。"),
                    region("mob_spawn", "怪物自然生成", "僵尸、苦力怕等能不能在本区刷出来", "flan:mob_spawn", true, true,
                            null),
                    region("enderman", "末影人搬方块", null, "flan:enderman", true, false, null),
                    region("liquid_border", "水和岩浆流过边界", "只管从区外横着流进来", "flan:water_border", false, false,
                            "区外的水和岩浆能流进本区，边上的建筑可能被淹掉或烧掉。"))));

    private static final Map<String, PermissionItemDef> BY_ID;
    private static final List<PermissionItemDef> ALL;
    private static final List<PermissionItemDef> MEMBER_ITEMS;
    private static final List<PermissionItemDef> REGION_ITEMS;

    static {
        Map<String, PermissionItemDef> byId = new LinkedHashMap<>();
        List<PermissionItemDef> member = new ArrayList<>();
        List<PermissionItemDef> region = new ArrayList<>();
        for (Group group : GROUPS) {
            for (PermissionItemDef item : group.items()) {
                if (byId.put(item.permissionId(), item) != null) {
                    throw new IllegalStateException("duplicate district permission id " + item.permissionId());
                }
                (item.region() ? region : member).add(item);
            }
        }
        // 三张地块默认名单里的 id 必须都在目录里、且都不是区域规则, 否则名单写错了也不会有任何一格变化。
        for (Set<String> ids : List.of(PLOT_FRIEND_DEFAULT_OFF, PLOT_RESIDENT_DEFAULT_ON, PLOT_OUTSIDER_DEFAULT_ON)) {
            for (String id : ids) {
                PermissionItemDef item = byId.get(id);
                if (item == null || item.region()) {
                    throw new IllegalStateException("plot default list names unknown or region item " + id);
                }
            }
        }
        BY_ID = Collections.unmodifiableMap(byId);
        ALL = List.copyOf(byId.values());
        MEMBER_ITEMS = List.copyOf(member);
        REGION_ITEMS = List.copyOf(region);
    }

    /** 全部项, 按目录顺序。 */
    public static List<PermissionItemDef> items() {
        return ALL;
    }

    /** member 项 (地块里能开关的那些), 按目录顺序。 */
    public static List<PermissionItemDef> memberItems() {
        return MEMBER_ITEMS;
    }

    /** 区域规则项, 按目录顺序。 */
    public static List<PermissionItemDef> regionItems() {
        return REGION_ITEMS;
    }

    public static Optional<PermissionItemDef> find(@Nullable String permissionId) {
        return permissionId == null ? Optional.empty() : Optional.ofNullable(BY_ID.get(permissionId));
    }

    /** 地块住户列默认为真的项的名称, 按目录顺序 (district.plots residentDefaults)。 */
    public static List<String> residentDefaultLabels() {
        List<String> labels = new ArrayList<>();
        for (PermissionItemDef item : MEMBER_ITEMS) {
            if (item.plotDefault(PlotAudience.RESIDENT)) {
                labels.add(item.label());
            }
        }
        return List.copyOf(labels);
    }

    private static PermissionItemDef member(String groupId, String permissionId, String label, @Nullable String detail,
                                            List<String> flanIds, boolean residentDefault, boolean outsiderDefault,
                                            @Nullable String outsiderRisk, @Nullable String plotRisk) {
        PermissionItemDef.PlotDefaults plotDefaults = new PermissionItemDef.PlotDefaults(
                !PLOT_FRIEND_DEFAULT_OFF.contains(permissionId),
                PLOT_RESIDENT_DEFAULT_ON.contains(permissionId),
                PLOT_OUTSIDER_DEFAULT_ON.contains(permissionId));
        return new PermissionItemDef(permissionId, groupId, PermissionScope.MEMBER, label, detail, flanIds, false,
                residentDefault, outsiderDefault, null, outsiderRisk, null, plotDefaults, plotRisk);
    }

    private static PermissionItemDef region(String permissionId, String label, @Nullable String detail, String flanId,
                                            boolean districtDefault, boolean inverted, @Nullable String risk) {
        return new PermissionItemDef(permissionId, "region", PermissionScope.REGION, label, detail, List.of(flanId),
                inverted, null, null, districtDefault, null, risk, null, null);
    }
}
