package com.miningdim.district.service;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictLimits;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.access.PlotRelation;
import com.miningdim.district.core.DistrictError;
import com.miningdim.district.core.DistrictRuleException;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.FriendSyncStatus;
import com.miningdim.district.core.PermissionChange;
import com.miningdim.district.core.PlotActorRole;
import com.miningdim.district.core.PlotLogAction;
import com.miningdim.district.core.PlotLogEntry;
import com.miningdim.district.core.PlotSyncStatus;
import com.miningdim.district.flan.FlanGroupNames;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

import static com.miningdim.district.DistrictTestEnv.expect;
import static com.miningdim.district.DistrictTestEnv.player;
import static com.miningdim.district.DistrictTestEnv.uuidOf;

/**
 * 户主管自己的地块 (plot.detail / plot.setPermission / plot.resetPermissions / plot.addFriend / plot.removeFriend /
 * plot.restoreFriend): 关系判定与文案、闸门顺序 (冻结先于空置)、管理员代改、朋友的上限与暂停、三列开关的检查顺序与
 * 恢复默认的记录顺序、任期与归档的可见性。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PlotOwnerGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_plot_owner";

    /** 阿拜多斯 + 区务长 Warden_Wu + 户主 Owner_One 的 abydos-01 + 另一名住户 Resident_Rae。 */
    private static String ownedPlot(DistrictTestEnv env) {
        env.abydos();
        env.resident("abydos", "Warden_Wu");
        env.warden("abydos", "Warden_Wu");
        env.resident("abydos", "Owner_One");
        env.resident("abydos", "Resident_Rae");
        String plot = env.plot("abydos", 10, 10, 25, 25);
        env.own(plot, "Owner_One");
        return plot;
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void detailRelationsOwnerAdminWardenMessage(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String plot = ownedPlot(env);
            DistrictQueryService queries = env.ctx.queries();
            helper.assertTrue(queries.plotDetail(player("Owner_One"), "abydos", plot).relation() == PlotRelation.OWNER,
                    "户主打开自己的地块");
            helper.assertTrue(queries.plotDetail(env.admin, "abydos", plot).relation() == PlotRelation.ADMIN,
                    "管理员打开任何地块");
            DistrictRuleException warden = expect(helper, DistrictError.PERMISSION_DENIED, "区务长看别人的地块",
                    () -> queries.plotDetail(player("Warden_Wu"), "abydos", plot));
            helper.assertTrue(warden.getMessage().equals("别人的地块由户主做主，区务长只能看地块列表，不能看或改朋友和权限")
                    && "owner".equals(warden.params().get("requires")), "区务长用专门的文案, 实为 " + warden.getMessage());
            DistrictRuleException resident = expect(helper, DistrictError.PERMISSION_DENIED, "别的住户",
                    () -> queries.plotDetail(player("Resident_Rae"), "abydos", plot));
            helper.assertTrue(resident.getMessage().equals("只有户主本人和管理员能看这块地的朋友和权限"), "通用文案");
            DistrictRuleException write = expect(helper, DistrictError.PERMISSION_DENIED, "别的住户改",
                    () -> env.ctx.plotOwners().addFriend(player("Resident_Rae"), "abydos", plot, "Anyone_Ann", true));
            helper.assertTrue(write.getMessage().equals("只有户主本人和管理员能改这块地的朋友和权限"), "改的文案");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void editableFalseForVacantAndFrozen(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String owned = ownedPlot(env);
            String vacant = env.plot("abydos", 40, 10, 55, 25);
            DistrictQueryService queries = env.ctx.queries();
            helper.assertTrue(queries.plotDetail(env.admin, "abydos", owned).editable(), "有户主: 管理员可改");
            helper.assertTrue(queries.plotDetail(player("Owner_One"), "abydos", owned).editable(), "有户主: 户主可改");
            helper.assertTrue(!queries.plotDetail(env.admin, "abydos", vacant).editable(), "空置: 不可改");
            env.ctx.residents().removeResident(env.admin, "abydos", "Owner_One", "inactive", "不上线");
            DistrictQueryService.PlotDetailView frozen = queries.plotDetail(env.admin, "abydos", owned);
            helper.assertTrue(!frozen.editable() && frozen.freeze() != null && !frozen.freeze().canRestore(),
                    "冻结: 不可改, 原户主不在名单上时 canRestore 为假");
            expect(helper, DistrictError.PERMISSION_DENIED, "原户主已不是户主, 看不了",
                    () -> queries.plotDetail(player("Owner_One"), "abydos", owned));
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ownerSeesCurrentTenureAdminSeesArchive(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String plot = ownedPlot(env);
            env.ctx.plotOwners().addFriend(player("Owner_One"), "abydos", plot, "Resident_Rae", false);
            env.ctx.residents().removeResident(env.admin, "abydos", "Owner_One", "inactive", "不上线");
            env.ctx.freezes().reclaimNow(env.admin, "abydos", plot);
            env.own(plot, "Resident_Rae");
            env.ctx.plotOwners().setPermission(player("Resident_Rae"), "abydos", plot, "door", "outsider", false);

            List<PlotLogEntry> ownerLog = env.ctx.queries().plotDetail(player("Resident_Rae"), "abydos", plot).log();
            helper.assertTrue(ownerLog.size() == 2 && ownerLog.get(0).action() == PlotLogAction.PERMISSION
                            && ownerLog.get(1).action() == PlotLogAction.VACATE
                            && ownerLog.stream().allMatch(e -> e.tenure() == 2),
                    "户主只看本任期 (收回那条起), 实为 " + ownerLog);
            List<PlotLogEntry> adminLog = env.ctx.queries().plotDetail(env.admin, "abydos", plot).log();
            helper.assertTrue(adminLog.size() == 5 && adminLog.get(0).tenure() == 2 && adminLog.get(2).tenure() == 1
                            && adminLog.get(2).action() == PlotLogAction.FREEZE
                            && adminLog.get(4).action() == PlotLogAction.CREATE,
                    "管理员另看历任归档, 排在本任期之后, 实为 " + adminLog);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gateOrderFrozenBeforeVacant(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String plot = ownedPlot(env);
            String vacant = env.plot("abydos", 40, 10, 55, 25);
            PlotOwnerService owners = env.ctx.plotOwners();
            DistrictRuleException empty = expect(helper, DistrictError.PLOT_VACANT, "管理员改空置地块",
                    () -> owners.setPermission(env.admin, "abydos", vacant, "bogus", "bogus", null));
            helper.assertTrue(empty.getMessage().equals("阿拜多斯-02 现在空置，没有户主，不能加朋友或改权限"), "文案");
            expect(helper, DistrictError.PERMISSION_DENIED, "区务长的关系先于冻结判定",
                    () -> owners.addFriend(player("Warden_Wu"), "abydos", plot, "Anyone_Ann", true));
            env.ctx.residents().removeResident(env.admin, "abydos", "Owner_One", "inactive", "不上线");
            DistrictRuleException frozen = expect(helper, DistrictError.PLOT_FROZEN, "管理员改冻结地块",
                    () -> owners.removeFriend(env.admin, "abydos", plot, "Anyone_Ann"));
            helper.assertTrue(frozen.getMessage().equals("阿拜多斯-01 冻结中，朋友和权限要等解除冻结后才能改"), "文案");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void adminChangesAreOnBehalfOfOwner(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String plot = ownedPlot(env);
            env.seen("Friend_Fay", "Friend_Gil");
            PlotOwnerService.FriendResult byAdmin = env.ctx.plotOwners().addFriend(env.admin, "abydos", plot,
                    "Friend_Fay", false);
            helper.assertTrue(byAdmin.logEntry().onBehalfOfOwner() && byAdmin.logEntry().actorRole() == PlotActorRole.ADMIN
                            && "Op_Admin".equals(byAdmin.logEntry().actorName()),
                    "管理员加朋友标代改, 实为 " + byAdmin.logEntry());
            PlotOwnerService.FriendResult byOwner = env.ctx.plotOwners().addFriend(player("Owner_One"), "abydos", plot,
                    "Friend_Gil", false);
            helper.assertTrue(!byOwner.logEntry().onBehalfOfOwner() && byOwner.logEntry().actorRole() == PlotActorRole.OWNER,
                    "户主自己加不标代改");
            helper.assertTrue(byOwner.friend().syncStatus() == FriendSyncStatus.SYNCED && !byOwner.isResident()
                            && "Owner_One".equals(byOwner.friend().addedByName()),
                    "进过服的朋友已生效; 不在本区名单上 isResident 为假");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void friendRulesInOrder(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String plot = ownedPlot(env);
            PlotOwnerService owners = env.ctx.plotOwners();
            expect(helper, DistrictError.INVALID_PLAYER_NAME, "非法名字",
                    () -> owners.addFriend(player("Owner_One"), "abydos", plot, "x", true));
            DistrictRuleException self = expect(helper, DistrictError.FRIEND_IS_OWNER, "户主加自己",
                    () -> owners.addFriend(player("Owner_One"), "abydos", plot, "owner_one", true));
            helper.assertTrue(self.getMessage().equals("户主本人不用加成朋友：自己的地块本来就什么都能做"), "文案");
            owners.addFriend(player("Owner_One"), "abydos", plot, "Resident_Rae", false);
            DistrictRuleException twice = expect(helper, DistrictError.ALREADY_FRIEND, "重复加",
                    () -> owners.addFriend(player("Owner_One"), "abydos", plot, "resident_rae", false));
            helper.assertTrue(twice.getMessage().equals("Resident_Rae 已经是这块地的朋友了")
                    && "false".equals(twice.params().get("suspended")), "文案, 实为 " + twice.getMessage());
            DistrictRuleException never = expect(helper, DistrictError.PLAYER_NEVER_JOINED, "从没进过服",
                    () -> owners.addFriend(player("Owner_One"), "abydos", plot, "Brand_New", false));
            helper.assertTrue(never.getMessage().equals("没有找到 Brand_New 的登录记录"), "文案");
            PlotOwnerService.FriendResult pending = owners.addFriend(player("Owner_One"), "abydos", plot, "Brand_New",
                    true);
            helper.assertTrue(pending.friend().syncStatus() == FriendSyncStatus.PENDING
                            && pending.friend().uuid().equals(uuidOf("Brand_New")),
                    "确认后以待生效记入, 按离线 UUID");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void friendLimitCountsSuspendedAndRestoreMessage(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String plot = ownedPlot(env);
            PlotOwnerService owners = env.ctx.plotOwners();
            owners.addFriend(player("Owner_One"), "abydos", plot, "Resident_Rae", false);
            for (int i = 1; i < DistrictLimits.FRIEND_LIMIT; i++) {
                owners.addFriend(player("Owner_One"), "abydos", plot, "Pal_" + i, true);
            }
            env.ctx.residents().removeResident(env.admin, "abydos", "Resident_Rae", "violation", "违反区规");
            DistrictRuleException suspended = expect(helper, DistrictError.ALREADY_FRIEND, "已暂停的朋友再加",
                    () -> owners.addFriend(player("Owner_One"), "abydos", plot, "Resident_Rae", false));
            helper.assertTrue(suspended.getMessage().equals("Resident_Rae 已经在朋友名单上（已暂停），点 TA 旁边的“恢复”就行")
                    && "true".equals(suspended.params().get("suspended")), "文案, 实为 " + suspended.getMessage());
            DistrictRuleException full = expect(helper, DistrictError.FRIEND_LIMIT_REACHED, "已暂停的也占名额",
                    () -> owners.addFriend(player("Owner_One"), "abydos", plot, "One_More", true));
            helper.assertTrue(full.getMessage().equals("朋友已满 8 人，先移除一位再加")
                    && "8".equals(full.params().get("limit")), "文案");
            PlotOwnerService.FriendResult restored = owners.restoreFriend(player("Owner_One"), "abydos", plot,
                    "resident_rae");
            helper.assertTrue(!restored.friend().suspended() && !restored.isResident()
                            && restored.logEntry().action() == PlotLogAction.RESTORE_FRIEND,
                    "恢复后不再暂停; TA 已不是住户, isResident 为假");
            DistrictRuleException again = expect(helper, DistrictError.FRIEND_NOT_SUSPENDED, "没暂停的恢复",
                    () -> owners.restoreFriend(player("Owner_One"), "abydos", plot, "Resident_Rae"));
            helper.assertTrue(again.getMessage().equals("Resident_Rae 的朋友身份没有暂停，不需要恢复"), "文案");
            DistrictRuleException missing = expect(helper, DistrictError.FRIEND_NOT_FOUND, "不是朋友",
                    () -> owners.restoreFriend(player("Owner_One"), "abydos", plot, "Stranger_Stu"));
            helper.assertTrue(missing.getMessage().equals("Stranger_Stu 不是这块地的朋友"), "文案");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void removeFriendIsCaseInsensitive(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String plot = ownedPlot(env);
            env.ctx.plotOwners().addFriend(player("Owner_One"), "abydos", plot, "Resident_Rae", false);
            PlotLogEntry removed = env.ctx.plotOwners().removeFriend(player("Owner_One"), "abydos", plot, "RESIDENT_RAE");
            helper.assertTrue(removed.action() == PlotLogAction.REMOVE_FRIEND && "Resident_Rae".equals(removed.targetName()),
                    "不分大小写找到朋友, 记录写规范名, 实为 " + removed);
            helper.assertTrue(env.repo.friendsOf(plot).isEmpty(), "朋友已删除");
            expect(helper, DistrictError.FRIEND_NOT_FOUND, "再删一次",
                    () -> env.ctx.plotOwners().removeFriend(player("Owner_One"), "abydos", plot, "Resident_Rae"));
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void plotPermissionChecksInOrder(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String plot = ownedPlot(env);
            PlotOwnerService owners = env.ctx.plotOwners();
            DistrictRuleException audience = expect(helper, DistrictError.INVALID_REQUEST, "列不对 (项也不对)",
                    () -> owners.setPermission(player("Owner_One"), "abydos", plot, "bogus", "district", null));
            helper.assertTrue("audience".equals(audience.params().get("field")), "params.field = audience");
            DistrictRuleException value = expect(helper, DistrictError.INVALID_REQUEST, "值不是布尔 (项也不对)",
                    () -> owners.setPermission(player("Owner_One"), "abydos", plot, "bogus", "friend", null));
            helper.assertTrue("enabled".equals(value.params().get("field")), "params.field = enabled");
            expect(helper, DistrictError.PERMISSION_ITEM_UNKNOWN, "未知项",
                    () -> owners.setPermission(player("Owner_One"), "abydos", plot, "bogus", "friend", true));
            DistrictRuleException region = expect(helper, DistrictError.REGION_RULE_NOT_IN_PLOT, "区域规则",
                    () -> owners.setPermission(player("Owner_One"), "abydos", plot, "pvp", "friend", true));
            helper.assertTrue(region.getMessage().equals("区域规则全区统一，只有管理员能在自管区里改，地块里不能单独改"), "文案");
            PlotOwnerService.SetResult same = owners.setPermission(player("Owner_One"), "abydos", plot, "door",
                    "outsider", true);
            helper.assertTrue(same.logEntry() == null, "值没变时 logEntry 为 null");
            PlotOwnerService.SetResult changed = owners.setPermission(env.admin, "abydos", plot, "door", "outsider",
                    false);
            helper.assertTrue(changed.logEntry() != null && changed.logEntry().onBehalfOfOwner()
                            && new PermissionChange("door", "开关门", "outsider", true, false)
                            .equals(changed.logEntry().permission()),
                    "改了的写记录, 管理员标代改, 实为 " + changed.logEntry());
            UUID claim = env.plotRecord(plot).flanClaimId();
            helper.assertTrue(Boolean.FALSE.equals(env.recording().defaultPerm(claim, "flan:door"))
                            && env.plotRecord(plot).syncStatus() == PlotSyncStatus.SYNCED,
                    "外人列写地块默认权限, 整块重写后已生效");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void plotResetWritesItemThenAudienceOrder(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String plot = ownedPlot(env);
            PlotOwnerService owners = env.ctx.plotOwners();
            owners.setPermission(player("Owner_One"), "abydos", plot, "door", "resident", false);
            owners.setPermission(player("Owner_One"), "abydos", plot, "place", "outsider", true);
            owners.setPermission(player("Owner_One"), "abydos", plot, "place", "friend", false);
            PlotOwnerService.ResetResult reset = owners.resetPermissions(player("Owner_One"), "abydos", plot);
            List<String> order = reset.logEntries().stream()
                    .map(e -> e.permission().permissionId() + "/" + e.permission().audience()).toList();
            helper.assertTrue(order.equals(List.of("place/friend", "place/outsider", "door/resident")),
                    "先按目录项、每项内按朋友 / 住户 / 外人, 实为 " + order);
            helper.assertTrue(reset.logEntries().stream().allMatch(e -> DistrictTexts.RESTORE_DEFAULT.equals(e.reason())
                    && !e.onBehalfOfOwner()), "缘由 恢复默认");
            helper.assertTrue(owners.resetPermissions(player("Owner_One"), "abydos", plot).logEntries().isEmpty(),
                    "一格都不用改时为空");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void suspendedFriendFallsBackToResidentGroup(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String plot = ownedPlot(env);
            UUID rae = uuidOf("Resident_Rae");
            env.ctx.plotOwners().addFriend(player("Owner_One"), "abydos", plot, "Resident_Rae", false);
            UUID claim = env.plotRecord(plot).flanClaimId();
            helper.assertTrue(FlanGroupNames.plotFriend(plot).equals(env.recording().membersOf(claim).get(rae)),
                    "既是朋友又是住户的人按朋友算 (一人一组, 取最高)");
            env.ctx.residents().removeResident(env.admin, "abydos", "Resident_Rae", "violation", "违反区规");
            helper.assertTrue(!env.recording().membersOf(claim).containsKey(rae),
                    "暂停后不是住户也不是有效朋友: 按外人算, 不在任何组");
            env.ctx.residents().addResident(env.admin, "abydos", "Resident_Rae", false);
            helper.assertTrue(FlanGroupNames.plotResident(plot).equals(env.recording().membersOf(claim).get(rae)),
                    "加回本区后按其他住户算 (朋友身份仍暂停)");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pendingFriendIsNotInFriendGroup(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String plot = ownedPlot(env);
            env.ctx.plotOwners().addFriend(player("Owner_One"), "abydos", plot, "Late_Lou", true);
            UUID claim = env.plotRecord(plot).flanClaimId();
            helper.assertTrue(!env.recording().membersOf(claim).containsKey(uuidOf("Late_Lou")),
                    "待生效的朋友首次登录前不进朋友组");
            helper.assertTrue(env.plotRecord(plot).syncStatus() == PlotSyncStatus.SYNCED, "地块本身已生效");
        }
        helper.succeed();
    }
}
