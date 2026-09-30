package com.miningdim.district.service;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.access.DistrictAccess;
import com.miningdim.district.core.DistrictActorRole;
import com.miningdim.district.core.DistrictError;
import com.miningdim.district.core.DistrictLogAction;
import com.miningdim.district.core.DistrictLogEntry;
import com.miningdim.district.core.DistrictRuleException;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.PermissionCatalog;
import com.miningdim.district.core.PermissionChange;
import com.miningdim.district.flan.FlanGroupNames;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

import static com.miningdim.district.DistrictTestEnv.expect;
import static com.miningdim.district.DistrictTestEnv.player;

/**
 * 公共区域开关表 (district.permissions / district.setPermission / district.resetPermissions): 检查顺序、只收布尔、
 * 不变不写、恢复默认逐格记录、区域规则只归管理员、Flan 口径 (住户列写居民组、外人列与全区列写默认权限、flanInverted
 * 写取反值)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class DistrictPermissionGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_permissions";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void catalogHasThirtySixItemsAndSixResidentPlotDefaults(GameTestHelper helper) {
        helper.assertTrue(PermissionCatalog.items().size() == 36 && PermissionCatalog.memberItems().size() == 29
                        && PermissionCatalog.regionItems().size() == 7,
                "目录 36 项 = 29 member + 7 region");
        helper.assertTrue(PermissionCatalog.residentDefaultLabels().equals(List.of("开关门", "开关活板门", "开关栅栏门",
                        "按按钮和拉杆", "踩压力板", "捡地上的东西")),
                "地块住户列默认为真的 6 项, 实为 " + PermissionCatalog.residentDefaultLabels());
        helper.assertTrue(PermissionCatalog.find("hurt_animal").map(i -> !i.plotDefault(
                        com.miningdim.district.core.PlotAudience.FRIEND)).orElse(false),
                "朋友列默认关伤害动物");
        helper.assertTrue(PermissionCatalog.FIXED_RULES.get(0).detail().equals(
                        "自管区内和外围 8 格内不能放机械动力的机器（会转、会动或带功能的方块，轨道也算），外壳、支架、梯子、石材、"
                                + "玻璃这类纯装饰方块照常可放；机械动力的机器也改不了自管区里的方块。管理员（OP）亲手放置不受限。"
                                + "服务器直接拦，这条规则谁都改不了")
                        && PermissionCatalog.FIXED_RULES.get(0).valueText().equals("机器禁用，装饰可放，OP 例外"),
                "固定规则文案逐字对齐契约 (22.13)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void setPermissionChecksInOrder(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Warden_Wu");
            env.resident("abydos", "Resident_Rae");
            env.warden("abydos", "Warden_Wu");
            DistrictPermissionService perms = env.ctx.permissions();
            expect(helper, DistrictError.DISTRICT_NOT_FOUND, "不存在的区",
                    () -> perms.setPermission(env.admin, "nowhere", "bogus", "bogus", null));
            DistrictRuleException denied = expect(helper, DistrictError.PERMISSION_DENIED, "住户改开关",
                    () -> perms.setPermission(player("Resident_Rae"), "abydos", "bogus", "bogus", null));
            helper.assertTrue(denied.getMessage().equals("只有本区区务长或管理员可以改本区公共区域的权限"),
                    "文案, 实为 " + denied.getMessage());
            expect(helper, DistrictError.PERMISSION_ITEM_UNKNOWN, "未知项排在列之前",
                    () -> perms.setPermission(player("Warden_Wu"), "abydos", "bogus", "bogus", null));
            DistrictRuleException memberColumn = expect(helper, DistrictError.INVALID_REQUEST, "member 项写全区列",
                    () -> perms.setPermission(player("Warden_Wu"), "abydos", "place", "district", true));
            helper.assertTrue("audience".equals(memberColumn.params().get("field")), "params.field = audience");
            expect(helper, DistrictError.INVALID_REQUEST, "region 项写住户列 (列错排在管理员门之前)",
                    () -> perms.setPermission(player("Warden_Wu"), "abydos", "pvp", "resident", true));
            DistrictRuleException region = expect(helper, DistrictError.PERMISSION_DENIED, "区务长改区域规则 (值也缺)",
                    () -> perms.setPermission(player("Warden_Wu"), "abydos", "pvp", "district", null));
            helper.assertTrue(region.getMessage().equals("区域规则只有管理员可以改")
                    && "admin".equals(region.params().get("requires")), "文案与 requires, 实为 " + region.params());
            DistrictRuleException value = expect(helper, DistrictError.INVALID_REQUEST, "开关值缺失、null 或字符串",
                    () -> perms.setPermission(player("Warden_Wu"), "abydos", "place", "outsider", null));
            helper.assertTrue("enabled".equals(value.params().get("field")), "params.field = enabled, 实为 "
                    + value.params());
            helper.assertTrue(env.repo.districtLog("abydos", 50).stream()
                    .noneMatch(e -> e.action() == DistrictLogAction.PERMISSION), "被拒的请求不写记录");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unchangedValueReturnsNullLogAndWritesNothing(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.recording().clear();
            DistrictPermissionService.SetResult same = env.ctx.permissions().setPermission(env.admin, "abydos",
                    "place", "resident", true);
            helper.assertTrue(same.logEntry() == null, "值和现在一样时 logEntry 为 null");
            helper.assertTrue(env.repo.districtLog("abydos", 50).isEmpty(), "不写记录");
            helper.assertTrue(env.recording().calls().isEmpty(), "不写 Flan, 实为 " + env.recording().calls());
            helper.assertTrue(same.access() == DistrictAccess.ADMIN, "回执带调用者的身份 (供按身份裁剪)");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void changedValueWritesCellLogAndFlan(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Warden_Wu");
            env.warden("abydos", "Warden_Wu");
            UUID claim = env.districtRecord("abydos").flanClaimId();
            DistrictPermissionService.SetResult outsider = env.ctx.permissions().setPermission(player("Warden_Wu"),
                    "abydos", "place", "outsider", true);
            DistrictLogEntry log = outsider.logEntry();
            helper.assertTrue(log != null && log.action() == DistrictLogAction.PERMISSION
                            && log.actorRole() == DistrictActorRole.WARDEN && log.targetName() == null
                            && log.reason() == null
                            && new PermissionChange("place", "放置方块", "outsider", false, true).equals(log.permission()),
                    "改权限的记录内容, 实为 " + log);
            helper.assertTrue(outsider.cells().require("place", "outsider"), "回执带改完后的值");
            helper.assertTrue(Boolean.TRUE.equals(env.recording().defaultPerm(claim, "flan:place")),
                    "外人列写自管区的默认权限");

            env.ctx.permissions().setPermission(env.admin, "abydos", "ride", "resident", false);
            String group = FlanGroupNames.districtResident("abydos");
            helper.assertTrue(Boolean.FALSE.equals(env.recording().groupPerm(claim, group, "flan:boat"))
                            && Boolean.FALSE.equals(env.recording().groupPerm(claim, group, "flan:minecart")),
                    "住户列写本区居民组, 一项对多条 Flan 权限时一起写");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void resetWritesOneRowPerChangedCellWithRestoreReason(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Warden_Wu");
            env.warden("abydos", "Warden_Wu");
            DistrictPermissionService perms = env.ctx.permissions();
            perms.setPermission(env.admin, "abydos", "door", "outsider", false);
            perms.setPermission(env.admin, "abydos", "place", "outsider", true);
            perms.setPermission(env.admin, "abydos", "pvp", "district", true);

            DistrictPermissionService.ResetResult member = perms.resetPermissions(player("Warden_Wu"), "abydos",
                    "member");
            List<DistrictLogEntry> logs = member.logEntries();
            helper.assertTrue(logs.size() == 2 && "place".equals(logs.get(0).permission().permissionId())
                            && "door".equals(logs.get(1).permission().permissionId()),
                    "区务长恢复 member: 每改回一格一条, 按目录顺序 (place 在 door 之前), 实为 " + logs);
            helper.assertTrue(logs.stream().allMatch(e -> DistrictTexts.RESTORE_DEFAULT.equals(e.reason())),
                    "缘由为 恢复默认");
            helper.assertTrue(member.cells().require("pvp", "district"), "member 范围不动区域规则");

            DistrictPermissionService.ResetResult all = perms.resetPermissions(env.admin, "abydos", "all");
            helper.assertTrue(all.logEntries().size() == 1 && "pvp".equals(all.logEntries().get(0).permission()
                            .permissionId()) && !all.cells().require("pvp", "district"),
                    "管理员恢复 all 连区域规则一起, 实为 " + all.logEntries());
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void resetChecksAndEmptyResult(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Warden_Wu");
            env.resident("abydos", "Resident_Rae");
            env.warden("abydos", "Warden_Wu");
            DistrictPermissionService perms = env.ctx.permissions();
            DistrictRuleException resident = expect(helper, DistrictError.PERMISSION_DENIED, "住户恢复默认",
                    () -> perms.resetPermissions(player("Resident_Rae"), "abydos", "member"));
            helper.assertTrue(resident.getMessage().equals("只有本区区务长或管理员可以恢复本区的默认权限"),
                    "文案, 实为 " + resident.getMessage());
            DistrictRuleException scope = expect(helper, DistrictError.INVALID_REQUEST, "未知范围",
                    () -> perms.resetPermissions(player("Warden_Wu"), "abydos", "everything"));
            helper.assertTrue("scope".equals(scope.params().get("field")), "params.field = scope");
            DistrictRuleException all = expect(helper, DistrictError.PERMISSION_DENIED, "区务长恢复 all",
                    () -> perms.resetPermissions(player("Warden_Wu"), "abydos", "all"));
            helper.assertTrue(all.getMessage().equals("区域规则只有管理员可以改，区务长只能恢复住户和外人的开关"),
                    "文案, 实为 " + all.getMessage());
            DistrictPermissionService.ResetResult nothing = perms.resetPermissions(env.admin, "abydos", "all");
            helper.assertTrue(nothing.logEntries().isEmpty(), "一格都不用改时 logEntries 为空");
            helper.assertTrue(env.repo.districtLog("abydos", 50).stream()
                    .noneMatch(e -> e.action() == DistrictLogAction.PERMISSION), "也不写记录");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mobSpawnIsWrittenInverted(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            UUID claim = env.districtRecord("abydos").flanClaimId();
            helper.assertTrue(!Boolean.TRUE.equals(env.recording().defaultPerm(claim, "flan:mob_spawn")),
                    "建区时写满开关: 怪物自然生成默认开, flanInverted 写取反值 false (父领地是顶层领地, Flan 存盘只存真值,"
                            + " 假与缺省判定相同, 按差异写时不必显式写)");
            helper.assertTrue(Boolean.TRUE.equals(env.recording().defaultPerm(claim, "flan:enderman")),
                    "不取反的区域规则照原值写");
            env.ctx.permissions().setPermission(env.admin, "abydos", "mob_spawn", "district", false);
            helper.assertTrue(Boolean.TRUE.equals(env.recording().defaultPerm(claim, "flan:mob_spawn")),
                    "关掉怪物自然生成写 Flan 的 true");
            helper.assertTrue(Boolean.FALSE.equals(env.recording().groupPerm(claim,
                            FlanGroupNames.districtResident("abydos"), "flan:edit_perms")),
                    "管理类权限在居民组里显式写假");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void permissionWriteFailureFlagsReconcile(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.recording().failNext("setDefaultPermission", "区块未加载");
            DistrictPermissionService.SetResult result = env.ctx.permissions().setPermission(env.admin, "abydos",
                    "place", "outsider", true);
            helper.assertTrue(result.logEntry() != null && result.cells().require("place", "outsider"),
                    "Flan 写失败不回滚库里的开关");
            helper.assertTrue(env.districtRecord("abydos").needsReconcile(),
                    "本区开关写失败没有契约字段可展示: 置 needs_reconcile 等对账");
        }
        helper.succeed();
    }
}
