package com.miningdim.district.flan;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.core.DistrictAudience;
import com.miningdim.district.core.PermissionCatalog;
import com.miningdim.district.core.PermissionCells;
import com.miningdim.district.core.PermissionItemDef;
import com.miningdim.district.core.PlotAudience;
import com.miningdim.district.flan.real.FlanCompat;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限全表 (设计文档 20.3) 与开服自检的签名核对器 (20.2), 两者都是纯函数, 不需要 Flan。
 *
 * <p>全表用一张合成的权限表: Flan 1.20.1-1.11.16 的内置 66 个, 外加一个假想的非全局权限与一个假想的全局权限 (模拟
 * 数据包或别的模组新加的)。逐个位置核对 20.3 的表: 目录项取各自那一列、跟随项取所跟随项那一列、固定为真、管理类
 * (含 claim_message) 与固定为假恒假、表外的未知权限户主组为真其余为假、冻结全假、地块上全局权限一律 UNSET。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FlanPermissionPolicyGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_flan_policy";

    private static final String HYPO_LOCAL = "othermod:hypo_local";
    private static final String HYPO_GLOBAL = "othermod:hypo_global";

    private static FlanPermissionPolicy syntheticPolicy() {
        List<KnownPermission> table = new ArrayList<>(FlanPermissions.BUILTIN);
        table.add(new KnownPermission(HYPO_LOCAL, false, true, false));
        table.add(new KnownPermission(HYPO_GLOBAL, true, true, false));
        return FlanPermissionPolicy.of(table);
    }

    /** 目录默认值的开关表 (公共区域: 住户 / 外人 / 全区三列; 地块: 朋友 / 住户 / 外人三列)。 */
    private static PermissionCells defaultDistrictCells() {
        Map<String, Map<String, Boolean>> cells = new LinkedHashMap<>();
        for (PermissionItemDef item : PermissionCatalog.items()) {
            Map<String, Boolean> row = new LinkedHashMap<>();
            for (DistrictAudience audience : DistrictAudience.values()) {
                if (audience.appliesTo(item.scope())) {
                    row.put(audience.wire(), item.districtDefault(audience));
                }
            }
            cells.put(item.permissionId(), row);
        }
        return new PermissionCells(cells);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void builtinTableMatchesTheVerifiedFlan(GameTestHelper helper) {
        helper.assertTrue(FlanPermissions.BUILTIN.size() == 66, "内置表 66 个 (开发环境没有机械动力), 实为 "
                + FlanPermissions.BUILTIN.size());
        long nonGlobal = FlanPermissions.BUILTIN.stream().filter(permission -> !permission.global()).count();
        helper.assertTrue(nonGlobal == 51, "非全局 51 个, 实为 " + nonGlobal);
        helper.assertTrue(FlanPermissions.BUILTIN.stream().filter(KnownPermission::global)
                        .map(KnownPermission::id).collect(java.util.stream.Collectors.toSet()).equals(FlanPermissions.GLOBAL),
                "全局的恰好是 FlanPermissions.GLOBAL 那 15 个");
        FlanPermissionPolicy policy = FlanPermissionPolicy.of(FlanPermissions.BUILTIN);
        helper.assertTrue(policy.problems().isEmpty() && policy.unknownIds().isEmpty(),
                "内置表里没有表外的权限, 必需的 id 齐全, 实为 " + policy.problems() + " / " + policy.unknownIds());
        helper.assertTrue(policy.category("flan:claim_message") == FlanPermissionPolicy.Category.ADMIN,
                "claim_message 归管理类");
        helper.assertTrue(policy.category("flan:may_flight") == FlanPermissionPolicy.Category.FIXED_FALSE
                        && policy.category("flan:no_hunger") == FlanPermissionPolicy.Category.FIXED_FALSE,
                "两个 requireExplicit 权限固定为假");
        helper.assertTrue(policy.category("flan:archeology") == FlanPermissionPolicy.Category.FOLLOWS
                        && "break".equals(policy.governingItem("flan:archeology").permissionId()),
                "archeology 跟随破坏方块");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyPositionFollowsTheTable(GameTestHelper helper) {
        FlanPermissionPolicy policy = syntheticPolicy();
        helper.assertTrue(policy.unknownIds().equals(List.of(HYPO_LOCAL, HYPO_GLOBAL)),
                "表外的两个 id 列进 unknownIds, 实为 " + policy.unknownIds());
        PermissionCells cells = defaultDistrictCells();

        Map<String, PermValue> resident = policy.districtResidentGroup(cells);
        helper.assertTrue(resident.size() == 52 && resident.keySet().stream().noneMatch(FlanPermissions.GLOBAL::contains),
                "居民组: 每个非全局权限都显式写 (51 + 表外 1), 全局一个都不写, 实为 " + resident.size());
        for (PermissionItemDef item : PermissionCatalog.memberItems()) {
            for (String id : item.flanIds()) {
                helper.assertTrue(resident.get(id) == PermValue.of(item.districtDefault(DistrictAudience.RESIDENT)),
                        "目录项取住户列: " + id);
            }
        }
        helper.assertTrue(resident.get("flan:archeology") == resident.get("flan:break")
                        && resident.get("flan:jukebox") == resident.get("flan:open_container")
                        && resident.get("flan:chorus_fruit") == resident.get("flan:ender_pearl"),
                "跟随项取所跟随项的住户列");
        for (String id : FlanPermissions.FIXED_TRUE) {
            helper.assertTrue(resident.get(id) == PermValue.TRUE, "固定为真: " + id);
        }
        for (String id : List.of("flan:edit_claim", "flan:edit_perms", "flan:edit_potions", "flan:claim_message",
                "flan:teleport", "flan:raid", "flan:may_flight", "flan:no_hunger", HYPO_LOCAL)) {
            helper.assertTrue(resident.get(id) == PermValue.FALSE, "管理类、固定为假、表外的未知权限为假: " + id);
        }

        Map<String, PermValue> defaults = policy.districtDefaults(cells);
        helper.assertTrue(defaults.get("flan:mob_spawn") == PermValue.FALSE,
                "mob_spawn 取反: 目录允许怪物自然生成 (默认开) 写 Flan 的 false");
        helper.assertTrue(defaults.get("flan:enderman") == PermValue.TRUE
                        && defaults.get("flan:hurt_player") == PermValue.FALSE,
                "其余区域规则按全区列原值写");
        helper.assertTrue(defaults.get("flan:lock_items") == PermValue.TRUE && defaults.get("flan:snow_golem") == PermValue.TRUE
                        && defaults.get("flan:animal_spawn") == PermValue.FALSE
                        && defaults.get("flan:fake_player") == PermValue.FALSE && defaults.get(HYPO_GLOBAL) == PermValue.TRUE,
                "目录外的全局权限写 Flan 的出厂值 (未知的也一样)");
        helper.assertTrue(defaults.get("flan:place") == PermValue.of(PermissionCatalog.find("place").orElseThrow()
                .districtDefault(DistrictAudience.OUTSIDER)), "外人列");

        Map<String, PermValue> owner = policy.plotOwnerGroup();
        helper.assertTrue(owner.get("flan:break") == PermValue.TRUE && owner.get(HYPO_LOCAL) == PermValue.TRUE
                        && owner.get("flan:can_stay") == PermValue.TRUE && owner.get("flan:claim_message") == PermValue.FALSE
                        && owner.get("flan:may_flight") == PermValue.FALSE && owner.get("flan:edit_perms") == PermValue.FALSE,
                "户主组: 全部非管理类为真 (表外的也为真), 固定为假的除外");

        Map<String, PermValue> friend = policy.plotColumn(null, PlotAudience.FRIEND);
        helper.assertTrue(friend.get("flan:trample") == PermValue.FALSE && friend.get("flan:place") == PermValue.TRUE
                        && friend.get("flan:frost_walker") == PermValue.TRUE && friend.get(HYPO_LOCAL) == PermValue.FALSE,
                "朋友列 (空置地块取目录默认): 跟随项跟着走, 表外的为假");
        Map<String, PermValue> plotDefaults = policy.plotDefaults(null);
        for (String id : FlanPermissions.GLOBAL) {
            helper.assertTrue(plotDefaults.get(id) == PermValue.UNSET, "地块上全局权限一律 UNSET: " + id);
        }
        helper.assertTrue(plotDefaults.get(HYPO_GLOBAL) == PermValue.UNSET, "未知的全局权限在地块上也 UNSET");

        Map<String, PermValue> frozen = policy.frozenGroup();
        helper.assertTrue(frozen.size() == 52 && frozen.values().stream().allMatch(value -> value == PermValue.FALSE),
                "冻结: 全部非全局权限为假 (含 can_stay、drop、flight)");
        Map<String, PermValue> frozenDefaults = policy.frozenDefaults();
        helper.assertTrue(frozenDefaults.get("flan:can_stay") == PermValue.FALSE
                        && frozenDefaults.get("flan:explosions") == PermValue.UNSET,
                "冻结的默认: 非全局全假, 全局仍 UNSET");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void missingOrMisclassifiedRequiredIdsAreProblems(GameTestHelper helper) {
        List<KnownPermission> table = new ArrayList<>();
        for (KnownPermission permission : FlanPermissions.BUILTIN) {
            if (permission.id().equals("flan:break")) {
                continue;
            }
            if (permission.id().equals("flan:explosions")) {
                table.add(new KnownPermission(permission.id(), false, false, false));
                continue;
            }
            table.add(permission);
        }
        List<String> problems = FlanPermissionPolicy.of(table).problems();
        helper.assertTrue(problems.stream().anyMatch(problem -> problem.contains("flan:break"))
                        && problems.stream().anyMatch(problem -> problem.contains("flan:explosions")),
                "缺了目录的 id、区域规则不再是全局都算问题, 实为 " + problems);
        helper.succeed();
    }

    /**
     * 开服自检的签名核对器 (20.2): 用 JDK 的类核对"查得到 / 返回类型不对 / 成员不存在 / static 不符 / final 不符 /
     * 类不存在"六种情形; 再核对没有版本 (Flan 不在位) 时整体不通过、版本对但表里有个不存在的类时问题清单点名那个类。
     * 真 Flan 上整张表通过由 FlanRealGameTests.selfCheckPassesOnTheApprovedFlan 核对。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void selfCheckReportsEverySignatureMismatch(GameTestHelper helper) {
        ClassLoader loader = FlanPermissionPolicyGameTests.class.getClassLoader();
        List<FlanCompat.Signature> good = List.of(
                FlanCompat.Signature.method("java.util.ArrayList", "add", "boolean", "java.lang.Object"),
                FlanCompat.Signature.staticMethod("java.lang.Integer", "parseInt", "int", "java.lang.String"),
                FlanCompat.Signature.constructor("java.util.ArrayList", "int"),
                FlanCompat.Signature.field("java.lang.Integer", "MAX_VALUE", "int", FlanCompat.Req.PUBLIC,
                        FlanCompat.Req.STATIC, FlanCompat.Req.FINAL));
        helper.assertTrue(FlanCompat.checkSignatures(good, loader).isEmpty(),
                "签名全对: 没有问题, 实为 " + FlanCompat.checkSignatures(good, loader));
        List<FlanCompat.Signature> bad = List.of(
                FlanCompat.Signature.method("java.util.ArrayList", "add", "int", "java.lang.Object"),
                FlanCompat.Signature.method("java.util.ArrayList", "add", "boolean", "java.lang.Object", "boolean"),
                FlanCompat.Signature.method("java.lang.Integer", "parseInt", "int", "java.lang.String"),
                FlanCompat.Signature.field("java.lang.Integer", "MAX_VALUE", "int", FlanCompat.Req.PUBLIC,
                        FlanCompat.Req.STATIC, FlanCompat.Req.NOT_FINAL),
                FlanCompat.Signature.method("io.github.nowhere.Missing", "x", "void"));
        List<String> problems = FlanCompat.checkSignatures(bad, loader);
        helper.assertTrue(problems.size() == 5, "五项各报一个问题, 实为 " + problems);
        helper.assertTrue(problems.get(0).contains("return type") && problems.get(1).startsWith("missing")
                        && problems.get(2).contains("is static") && problems.get(3).contains("is final")
                        && problems.get(4).startsWith("missing class"),
                "问题清单点名每一项, 实为 " + problems);

        FlanCompat.Result notLoaded = FlanCompat.check(null, FlanCompat.expectedSignatures(), loader, null);
        helper.assertTrue(!notLoaded.ok(), "Flan 不在位: 不通过");
        // 开发运行时加载着真 Flan (20.1 出路 ③): 表里另加一个 Flan 包下并不存在的类 (在 ClaimStorage 的类名后接一段,
        // 本包不许直接写 Flan 的包名), 核对"版本对但缺类"。
        List<FlanCompat.Signature> withMissingClass = new ArrayList<>(FlanCompat.expectedSignatures());
        String missingOwner = FlanCompat.expectedSignatures().get(0).owner() + "Missing";
        withMissingClass.add(FlanCompat.Signature.method(missingOwner, "x", "void"));
        FlanCompat.Result absent = FlanCompat.check(FlanCompat.VERIFIED_VERSION, withMissingClass, loader, null);
        if (com.miningdim.district.DistrictTestEnv.withoutFlanRun()) {
            // -PwithoutFlan 的专门一轮: Flan 的类一个都不在, 每一项都报缺类。
            helper.assertTrue(!absent.ok() && absent.problems().size() == withMissingClass.size()
                            && absent.problems().stream().allMatch(problem -> problem.contains("missing class")),
                    "没有 Flan 的运行时: 签名表每一项都报缺类, 实为 " + absent.problems());
        } else {
            helper.assertTrue(!absent.ok() && absent.problems().size() == 1 && absent.problems().stream()
                            .anyMatch(problem -> problem.contains("missing class") && problem.contains(missingOwner)),
                    "版本对但类不在: 问题清单只点名缺的那个类, 实为 " + absent.problems());
        }
        FlanCompat.Result wrongVersion = FlanCompat.check("1.20.1-1.11.15", List.of(), loader, null);
        helper.assertTrue(!wrongVersion.ok() && wrongVersion.problems().get(0).contains("1.20.1-1.11.15"),
                "别的版本一律不认, 即使签名表为空");
        helper.assertTrue(FlanCompat.expectedSignatures().size() >= 50, "签名表覆盖生产代码用到的全部 Flan 成员");
        helper.succeed();
    }
}
