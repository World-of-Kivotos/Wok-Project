package com.miningdim.district.flan;

import com.miningdim.district.core.DistrictAudience;
import com.miningdim.district.core.PermissionCatalog;
import com.miningdim.district.core.PermissionCells;
import com.miningdim.district.core.PermissionItemDef;
import com.miningdim.district.core.PlotAudience;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 权限全表 (设计文档 20.3): 由 Flan 当前的权限表生成每个位置 (父领地居民组、父领地默认、地块三组与默认、冻结) 的值。
 * 纯函数: 输入是已知权限列表与开关表, 不碰 Flan, GameTest 可以直接测。
 *
 * <table>
 *   <tr><th>类别</th><th>父领地居民组</th><th>父领地默认</th><th>户主组</th><th>朋友 / 居民 / 地块默认</th><th>冻结</th></tr>
 *   <tr><td>目录项</td><td>住户列</td><td>外人列</td><td>真</td><td>各自那一列</td><td>假</td></tr>
 *   <tr><td>跟随目录项</td><td>所跟随项的住户列</td><td>所跟随项的外人列</td><td>真</td><td>所跟随项的那一列</td><td>假</td></tr>
 *   <tr><td>固定为真</td><td>真</td><td>真</td><td>真</td><td>真</td><td>假</td></tr>
 *   <tr><td>管理类、固定为假</td><td>假</td><td>假</td><td>假</td><td>假</td><td>假</td></tr>
 *   <tr><td>表外的未知权限</td><td>假</td><td>假</td><td>真</td><td>假</td><td>假</td></tr>
 * </table>
 * 全局权限: 父领地上, 7 项目录区域规则按全区列写 (flanInverted 时取反), 其余写 Flan 的 defaultVal; 地块上一律 UNSET。
 */
public final class FlanPermissionPolicy {

    /** 一个非全局权限归哪一类。 */
    public enum Category {
        CATALOG,
        FOLLOWS,
        FIXED_TRUE,
        ADMIN,
        FIXED_FALSE,
        UNKNOWN
    }

    private final List<KnownPermission> table;
    private final List<KnownPermission> nonGlobal;
    private final List<KnownPermission> global;
    private final Map<String, PermissionItemDef> memberItemByFlanId;
    private final Map<String, PermissionItemDef> regionItemByFlanId;
    private final Map<String, PermissionItemDef> catalogById;

    private FlanPermissionPolicy(List<KnownPermission> table) {
        this.table = List.copyOf(table);
        List<KnownPermission> nonGlobalList = new ArrayList<>();
        List<KnownPermission> globalList = new ArrayList<>();
        for (KnownPermission permission : this.table) {
            (permission.global() ? globalList : nonGlobalList).add(permission);
        }
        this.nonGlobal = List.copyOf(nonGlobalList);
        this.global = List.copyOf(globalList);
        Map<String, PermissionItemDef> member = new LinkedHashMap<>();
        for (PermissionItemDef item : PermissionCatalog.memberItems()) {
            item.flanIds().forEach(id -> member.put(id, item));
        }
        Map<String, PermissionItemDef> region = new LinkedHashMap<>();
        for (PermissionItemDef item : PermissionCatalog.regionItems()) {
            item.flanIds().forEach(id -> region.put(id, item));
        }
        Map<String, PermissionItemDef> byId = new LinkedHashMap<>();
        PermissionCatalog.items().forEach(item -> byId.put(item.permissionId(), item));
        this.memberItemByFlanId = Collections.unmodifiableMap(member);
        this.regionItemByFlanId = Collections.unmodifiableMap(region);
        this.catalogById = Collections.unmodifiableMap(byId);
    }

    public static FlanPermissionPolicy of(List<KnownPermission> table) {
        return new FlanPermissionPolicy(table);
    }

    public List<KnownPermission> table() {
        return table;
    }

    public List<KnownPermission> nonGlobal() {
        return nonGlobal;
    }

    public List<KnownPermission> global() {
        return global;
    }

    /** 一个非全局权限的类别。 */
    public Category category(String flanId) {
        if (memberItemByFlanId.containsKey(flanId)) {
            return Category.CATALOG;
        }
        if (FlanPermissions.FOLLOWS.containsKey(flanId)) {
            return Category.FOLLOWS;
        }
        if (FlanPermissions.FIXED_TRUE.contains(flanId)) {
            return Category.FIXED_TRUE;
        }
        if (FlanPermissions.ADMIN.contains(flanId)) {
            return Category.ADMIN;
        }
        if (FlanPermissions.FIXED_FALSE.contains(flanId)) {
            return Category.FIXED_FALSE;
        }
        return Category.UNKNOWN;
    }

    /** 目录项或跟随项所取值的目录项; 其余类别为 null。 */
    @Nullable
    public PermissionItemDef governingItem(String flanId) {
        PermissionItemDef direct = memberItemByFlanId.get(flanId);
        if (direct != null) {
            return direct;
        }
        String followed = FlanPermissions.FOLLOWS.get(flanId);
        return followed == null ? null : catalogById.get(followed);
    }

    /** 表外的未知非全局权限 (数据包或别的模组新加的), 开服与 /reload 后记一条 INFO。 */
    public List<String> unknownIds() {
        List<String> unknown = new ArrayList<>();
        for (KnownPermission permission : nonGlobal) {
            if (category(permission.id()) == Category.UNKNOWN) {
                unknown.add(permission.id());
            }
        }
        for (KnownPermission permission : global) {
            if (!FlanPermissions.GLOBAL.contains(permission.id())) {
                unknown.add(permission.id());
            }
        }
        return List.copyOf(unknown);
    }

    /**
     * 本模块显式要写的 id 缺了、或全局标志与预期不符 (20.2 第 8 条): 目录的 30 个 id、管理类、固定为真的三个都必须是
     * 非全局; 7 项区域规则必须是全局。返回问题清单, 空表示齐全。
     */
    public List<String> problems() {
        Map<String, Boolean> globalById = new LinkedHashMap<>();
        table.forEach(permission -> globalById.put(permission.id(), permission.global()));
        Set<String> nonGlobalRequired = new LinkedHashSet<>(memberItemByFlanId.keySet());
        nonGlobalRequired.addAll(FlanPermissions.ADMIN);
        nonGlobalRequired.addAll(FlanPermissions.FIXED_TRUE);
        List<String> problems = new ArrayList<>();
        for (String id : nonGlobalRequired) {
            Boolean isGlobal = globalById.get(id);
            if (isGlobal == null) {
                problems.add("Flan 权限表里没有 " + id);
            } else if (isGlobal) {
                problems.add(id + " 在 Flan 里是全局权限, 本模块要按组写它");
            }
        }
        for (String id : regionItemByFlanId.keySet()) {
            Boolean isGlobal = globalById.get(id);
            if (isGlobal == null) {
                problems.add("Flan 权限表里没有 " + id);
            } else if (!isGlobal) {
                problems.add(id + " 在 Flan 里不是全局权限, 区域规则要求它是");
            }
        }
        return List.copyOf(problems);
    }

    // ================================================================
    // 父领地
    // ================================================================

    /** 父领地居民组: 每个非全局权限都显式写 (公共区域的住户列)。 */
    public Map<String, PermValue> districtResidentGroup(PermissionCells cells) {
        return nonGlobalColumn(item -> cells.district(item, DistrictAudience.RESIDENT), false);
    }

    /** 父领地默认 (外人) 与全局权限 (区域规则): 非全局按外人列, 全局按全区列 / Flan 出厂值。 */
    public Map<String, PermValue> districtDefaults(PermissionCells cells) {
        Map<String, PermValue> values = nonGlobalColumn(item -> cells.district(item, DistrictAudience.OUTSIDER), false);
        for (KnownPermission permission : global) {
            PermissionItemDef region = regionItemByFlanId.get(permission.id());
            boolean value = region == null
                    ? permission.defaultValue()
                    : region.flanInverted() != cells.district(region, DistrictAudience.DISTRICT);
            values.put(permission.id(), PermValue.of(value));
        }
        return values;
    }

    // ================================================================
    // 地块
    // ================================================================

    /** 户主组: 全部非管理类为真, 固定为假的除外; 表外的未知权限也为真 ("这是 TA 的家")。 */
    public Map<String, PermValue> plotOwnerGroup() {
        Map<String, PermValue> values = new LinkedHashMap<>();
        for (KnownPermission permission : nonGlobal) {
            Category category = category(permission.id());
            boolean value = category != Category.ADMIN && category != Category.FIXED_FALSE;
            values.put(permission.id(), PermValue.of(value));
        }
        return values;
    }

    /**
     * 朋友 / 其他住户 / 外人某一列 (组或地块默认的非全局部分)。
     *
     * @param cells     这块地的三列; 为 null 时取目录的地块默认值 (空置的地)
     */
    public Map<String, PermValue> plotColumn(@Nullable PermissionCells cells, PlotAudience audience) {
        return nonGlobalColumn(item -> cells == null ? item.plotDefault(audience) : cells.plot(item, audience), false);
    }

    /** 地块默认: 外人列, 外加全部全局权限 UNSET (跟随父领地)。 */
    public Map<String, PermValue> plotDefaults(@Nullable PermissionCells cells) {
        Map<String, PermValue> values = plotColumn(cells, PlotAudience.OUTSIDER);
        putGlobalUnset(values);
        return values;
    }

    /** 冻结的地块: 三个组都用它, 全部非全局权限为假 (含 can_stay、drop、flight)。 */
    public Map<String, PermValue> frozenGroup() {
        Map<String, PermValue> values = new LinkedHashMap<>();
        nonGlobal.forEach(permission -> values.put(permission.id(), PermValue.FALSE));
        return values;
    }

    /** 冻结的地块默认: 非全局全假, 全局仍 UNSET。 */
    public Map<String, PermValue> frozenDefaults() {
        Map<String, PermValue> values = frozenGroup();
        putGlobalUnset(values);
        return values;
    }

    // ================================================================
    // 内部
    // ================================================================

    private Map<String, PermValue> nonGlobalColumn(Predicate<PermissionItemDef> column, boolean unknownValue) {
        Map<String, PermValue> values = new LinkedHashMap<>();
        for (KnownPermission permission : nonGlobal) {
            String id = permission.id();
            boolean value = switch (category(id)) {
                case CATALOG, FOLLOWS -> {
                    PermissionItemDef item = governingItem(id);
                    yield item != null && column.test(item);
                }
                case FIXED_TRUE -> true;
                case ADMIN, FIXED_FALSE -> false;
                case UNKNOWN -> unknownValue;
            };
            values.put(id, PermValue.of(value));
        }
        return values;
    }

    private void putGlobalUnset(Map<String, PermValue> values) {
        global.forEach(permission -> values.put(permission.id(), PermValue.UNSET));
    }
}
