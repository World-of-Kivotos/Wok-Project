package com.miningdim.district.service;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.core.AreaChange;
import com.miningdim.district.core.DistrictActorRole;
import com.miningdim.district.core.DistrictError;
import com.miningdim.district.core.DistrictLogAction;
import com.miningdim.district.core.DistrictLogEntry;
import com.miningdim.district.core.DistrictRuleException;
import com.miningdim.district.core.PermissionCatalog;
import com.miningdim.district.core.PermissionItemDef;
import com.miningdim.district.core.PlotActorRole;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.core.PlotAudience;
import com.miningdim.district.core.PlotLogAction;
import com.miningdim.district.core.PlotLogEntry;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.PlotSyncStatus;
import com.miningdim.district.flan.FlanGroupNames;
import com.miningdim.district.flan.FlanPermissions;
import com.miningdim.district.flan.RecordingFlanGateway;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.miningdim.district.DistrictTestEnv.expect;
import static com.miningdim.district.DistrictTestEnv.player;

/**
 * 划地块 (plot.create)、调范围 (plot.resize)、删地块 (plot.delete): 范围校验的五个码与顺序、贴边与共用坐标、编号永不复用、子领地一建好就把每一格
 * 写成明确值且组名独有、区务长只动空置地块、管理员代改通知户主、删地块把记录留进墓碑。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PlotLayoutGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_plot_layout";

    private static PlotLayoutService.AreaInput area(int minX, int minZ, int maxX, int maxZ) {
        return PlotLayoutService.AreaInput.of(new PlotArea(minX, minZ, maxX, maxZ));
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void geometryCodesInOrder(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Resident_Rae");
            env.plot("abydos", 10, 10, 25, 25);
            PlotLayoutService layout = env.ctx.layout();
            DistrictRuleException denied = expect(helper, DistrictError.PERMISSION_DENIED, "住户划地块",
                    () -> layout.create(player("Resident_Rae"), "abydos", PlotLayoutService.AreaInput.invalid("minX")));
            helper.assertTrue(denied.getMessage().equals("只有本区区务长和管理员可以划地块"), "文案");
            DistrictRuleException type = expect(helper, DistrictError.INVALID_AREA, "坐标不是整数",
                    () -> layout.create(env.admin, "abydos", PlotLayoutService.AreaInput.invalid("maxZ")));
            helper.assertTrue(type.getMessage().equals("坐标必须是整数") && "maxZ".equals(type.params().get("field")),
                    "类型错的文案与 field, 实为 " + type.getMessage() + " / " + type.params());
            DistrictRuleException inverted = expect(helper, DistrictError.INVALID_AREA, "两个角分不开",
                    () -> layout.create(env.admin, "abydos", area(30, 30, 20, 45)));
            helper.assertTrue(inverted.getMessage().equals("坐标必须是整数，且两个角要分得开"), "文案");
            DistrictRuleException outside = expect(helper, DistrictError.OUT_OF_DISTRICT, "超出范围",
                    () -> layout.create(env.admin, "abydos", area(190, 10, 205, 25)));
            helper.assertTrue(outside.getMessage().equals("超出自管区范围（X 0 ~ 199，Z 0 ~ 199）")
                            && "199".equals(outside.params().get("maxX")),
                    "文案写自管区的范围, 实为 " + outside.getMessage());
            DistrictRuleException edge = expect(helper, DistrictError.TOO_CLOSE_TO_EDGE, "离边界 1 格",
                    () -> layout.create(env.admin, "abydos", area(1, 40, 16, 55)));
            helper.assertTrue(edge.getMessage().equals("离自管区边界太近：四周至少要留 2 格公共区域"), "文案");
            DistrictRuleException size = expect(helper, DistrictError.SIZE_OUT_OF_RANGE, "边长 7",
                    () -> layout.create(env.admin, "abydos", area(40, 40, 46, 55)));
            helper.assertTrue(size.getMessage().equals("每边要在 8 到 48 格之间（现在 7 × 16）")
                            && "7".equals(size.params().get("width")),
                    "文案, 实为 " + size.getMessage());
            DistrictRuleException overlap = expect(helper, DistrictError.OVERLAPS_PLOT, "重叠",
                    () -> layout.create(env.admin, "abydos", area(20, 20, 35, 35)));
            helper.assertTrue(overlap.getMessage().equals("和地块 阿拜多斯-01 重叠了")
                            && "abydos-01".equals(overlap.params().get("plotId")),
                    "文案, 实为 " + overlap.getMessage());
            helper.assertTrue(env.repo.plotsOf("abydos").size() == 1, "被拒的请求不建地块");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void touchingIsAllowedSharingACoordinateOverlaps(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.plot("abydos", 10, 10, 25, 25);
            String touching = env.plot("abydos", 26, 10, 41, 25);
            helper.assertTrue(env.plotRecord(touching).area().minX() == 26, "贴边 (相邻的两格) 不算重叠");
            DistrictRuleException shared = expect(helper, DistrictError.OVERLAPS_PLOT, "共用 x = 41",
                    () -> env.ctx.layout().create(env.admin, "abydos", area(41, 10, 56, 25)));
            helper.assertTrue("abydos-02".equals(shared.params().get("plotId")), "共用一个坐标就算重叠");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void edgeGapOfExactlyTwoPasses(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.plot("abydos", 2, 2, 17, 17);
            env.plot("abydos", 182, 182, 197, 197);
            expect(helper, DistrictError.TOO_CLOSE_TO_EDGE, "右边只留 1 格",
                    () -> env.ctx.layout().create(env.admin, "abydos", area(183, 100, 198, 115)));
            helper.assertTrue(env.repo.plotsOf("abydos").size() == 2, "四边恰好留 2 格可以");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void overlapChecksFrozenPlots(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_One");
            env.ctx.residents().removeResident(env.admin, "abydos", "Owner_One", "inactive", "不上线");
            expect(helper, DistrictError.OVERLAPS_PLOT, "和冻结中的地块重叠",
                    () -> env.ctx.layout().create(env.admin, "abydos", area(20, 20, 35, 35)));
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void numbersAreNeverReusedAfterDelete(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.plot("abydos", 10, 10, 25, 25);
            String second = env.plot("abydos", 40, 10, 55, 25);
            env.ctx.layout().delete(env.admin, "abydos", second);
            String third = env.plot("abydos", 40, 10, 55, 25);
            PlotRecord record = env.plotRecord(third);
            helper.assertTrue("abydos-03".equals(third) && record.plotNo() == 3 && "阿拜多斯-03".equals(record.code()),
                    "删掉的编号不复用, 实为 " + third);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void createWritesEveryCellExplicitlyWithPlotUniqueGroups(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Warden_Wu");
            env.warden("abydos", "Warden_Wu");
            PlotLayoutService.LayoutResult created = env.ctx.layout().create(player("Warden_Wu"), "abydos",
                    area(10, 10, 25, 25));
            PlotRecord plot = created.plot();
            helper.assertTrue(plot.syncStatus() == PlotSyncStatus.SYNCED && plot.flanClaimId() != null,
                    "回执读到的是推送后的真值: 子领地已建、已生效, 实为 " + plot);
            DistrictLogEntry log = created.logEntry();
            helper.assertTrue(log.action() == DistrictLogAction.CREATE_PLOT && "阿拜多斯-01".equals(log.targetName())
                            && "16 × 16，256 格".equals(log.reason()) && log.actorRole() == DistrictActorRole.WARDEN
                            && new AreaChange(null, new PlotArea(10, 10, 25, 25)).equals(log.area()),
                    "本区记录 createPlot, 实为 " + log);
            PlotLogEntry plotLog = env.repo.plotLog(plot.plotId(), 1, 1, 5).get(0);
            helper.assertTrue(plotLog.action() == PlotLogAction.CREATE && plotLog.actorRole() == PlotActorRole.WARDEN
                            && plotLog.targetName() == null && !plotLog.onBehalfOfOwner(),
                    "地块记录 create, 实为 " + plotLog);
            helper.assertTrue(env.repo.plotCells(plot.plotId()).asMap().size() == 29, "三列开关每一项都有行");

            RecordingFlanGateway flan = env.recording();
            UUID claim = plot.flanClaimId();
            String plotId = plot.plotId();
            helper.assertTrue(flan.groupsOf(claim).equals(Set.of(FlanGroupNames.plotOwner(plotId),
                            FlanGroupNames.plotFriend(plotId), FlanGroupNames.plotResident(plotId))),
                    "子领地只有这块地独有的三个组, 实为 " + flan.groupsOf(claim));
            for (PermissionItemDef item : PermissionCatalog.memberItems()) {
                for (String flanId : item.flanIds()) {
                    helper.assertTrue(Boolean.valueOf(item.plotDefault(PlotAudience.RESIDENT))
                                    .equals(flan.groupPerm(claim, FlanGroupNames.plotResident(plotId), flanId))
                                    && Boolean.valueOf(item.plotDefault(PlotAudience.OUTSIDER))
                                    .equals(flan.defaultPerm(claim, flanId)),
                            flanId + " 在居民组与默认权限里都写成明确值");
                }
            }
            for (String admin : FlanPermissions.ADMIN) {
                helper.assertTrue(Boolean.FALSE.equals(flan.groupPerm(claim, FlanGroupNames.plotOwner(plotId), admin))
                                && Boolean.FALSE.equals(flan.defaultPerm(claim, admin)),
                        admin + " 在户主组与默认里显式写假");
            }
            for (PermissionItemDef region : PermissionCatalog.regionItems()) {
                helper.assertTrue(flan.defaultPerm(claim, region.flanIds().get(0)) == null,
                        "区域规则刻意不写在地块上: " + region.permissionId());
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void createdPlotHasNoInheritedGroups(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            UUID resident = env.resident("abydos", "Resident_Rae").uuid();
            UUID districtClaim = env.districtRecord("abydos").flanClaimId();
            helper.assertTrue(env.recording().groupsOf(districtClaim).contains(FlanGroupNames.districtResident("abydos"))
                            && env.recording().membersOf(districtClaim).containsKey(resident),
                    "父领地上有本区居民组与成员");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            UUID claim = env.plotRecord(plot).flanClaimId();
            helper.assertTrue(env.recording().groupsOf(claim).stream().noneMatch(g -> g.startsWith("d_")),
                    "子领地里没有继承来的 d_ 组, 实为 " + env.recording().groupsOf(claim));
            helper.assertTrue(FlanGroupNames.plotResident(plot).equals(env.recording().membersOf(claim).get(resident)),
                    "成员只按期望状态放进这块地自己的居民组");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wardenCannotResizeOwnedPlot(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Warden_Wu");
            env.warden("abydos", "Warden_Wu");
            env.resident("abydos", "Owner_One");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_One");
            DistrictRuleException occupied = expect(helper, DistrictError.PLOT_OCCUPIED, "区务长调有户主的地块",
                    () -> env.ctx.layout().resize(player("Warden_Wu"), "abydos", plot,
                            PlotLayoutService.AreaInput.invalid("minX")));
            helper.assertTrue(occupied.getMessage().equals("阿拜多斯-01 是 Owner_One 的家，区务长不能改有户主的地块范围")
                            && "Owner_One".equals(occupied.params().get("ownerName")),
                    "PLOT_OCCUPIED 排在坐标校验之前, 文案, 实为 " + occupied.getMessage());
            expect(helper, DistrictError.PLOT_OCCUPIED, "区务长删有户主的地块",
                    () -> env.ctx.layout().delete(player("Warden_Wu"), "abydos", plot));
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void adminResizeOfOwnedPlotIsOnBehalfAndNotified(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_One");
            PlotLayoutService.LayoutResult resized = env.ctx.layout().resize(env.admin, "abydos", plot,
                    area(10, 10, 29, 25));
            helper.assertTrue(resized.ownerNotified() && resized.plot().area().equals(new PlotArea(10, 10, 29, 25)),
                    "管理员可以调有户主的地块, 回执 ownerNotified = true");
            helper.assertTrue(resized.logEntry().reason().equals("管理员代改，已通知户主 Owner_One")
                            && new AreaChange(new PlotArea(10, 10, 25, 25), new PlotArea(10, 10, 29, 25))
                            .equals(resized.logEntry().area()),
                    "本区记录的缘由与范围变化, 实为 " + resized.logEntry());
            PlotLogEntry notice = env.repo.plotLog(plot, 1, 1, 1).get(0);
            helper.assertTrue(notice.action() == PlotLogAction.RESIZE && notice.onBehalfOfOwner()
                            && notice.actorRole() == PlotActorRole.ADMIN,
                    "地块记录 resize 标代改, 这一行就是给户主的通知, 实为 " + notice);
            helper.assertTrue(new PlotArea(10, 10, 29, 25).equals(env.recording().areaOf(env.plotRecord(plot)
                    .flanClaimId())), "子领地范围跟着改");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void vacantResizeByWardenAndUnchangedArea(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Warden_Wu");
            env.warden("abydos", "Warden_Wu");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            DistrictRuleException unchanged = expect(helper, DistrictError.PLOT_AREA_UNCHANGED, "范围不变",
                    () -> env.ctx.layout().resize(player("Warden_Wu"), "abydos", plot, area(10, 10, 25, 25)));
            helper.assertTrue(unchanged.getMessage().equals("范围和原来一样，没有改动"), "文案");
            PlotLayoutService.LayoutResult resized = env.ctx.layout().resize(player("Warden_Wu"), "abydos", plot,
                    area(10, 10, 25, 30));
            helper.assertTrue(!resized.ownerNotified() && resized.logEntry().reason().equals("16 × 16 → 16 × 21"),
                    "空置地块的缘由写尺寸变化, 实为 " + resized.logEntry().reason());
            expect(helper, DistrictError.PLOT_NOT_FOUND, "不存在的地块",
                    () -> env.ctx.layout().resize(player("Warden_Wu"), "abydos", "abydos-99", area(1, 1, 2, 2)));
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void frozenPlotCannotBeResizedOrDeleted(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_One");
            env.ctx.residents().removeResident(env.admin, "abydos", "Owner_One", "inactive", "不上线");
            DistrictRuleException resize = expect(helper, DistrictError.PLOT_FROZEN, "管理员调冻结中的地块",
                    () -> env.ctx.layout().resize(env.admin, "abydos", plot, area(10, 10, 29, 25)));
            helper.assertTrue(resize.getMessage().equals("阿拜多斯-01 冻结中，不能改范围；要先解除冻结或收回"), "文案");
            DistrictRuleException delete = expect(helper, DistrictError.PLOT_FROZEN, "管理员删冻结中的地块",
                    () -> env.ctx.layout().delete(env.admin, "abydos", plot));
            helper.assertTrue(delete.getMessage().equals("阿拜多斯-01 冻结中，不能删；要先解除冻结或收回"), "文案");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void deleteMovesWholeLogIntoTombstone(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            env.resident("abydos", "Warden_Wu");
            env.warden("abydos", "Warden_Wu");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_One");
            env.ctx.residents().removeResident(env.admin, "abydos", "Owner_One", "inactive", "不上线");
            env.ctx.freezes().reclaimNow(env.admin, "abydos", plot);
            UUID claim = env.plotRecord(plot).flanClaimId();
            env.advance(1000);

            DistrictLogEntry log = env.ctx.layout().delete(player("Warden_Wu"), "abydos", plot);
            helper.assertTrue(log.action() == DistrictLogAction.DELETE_PLOT
                            && log.reason().equals("删掉后这片地回到公共区域")
                            && new AreaChange(new PlotArea(10, 10, 25, 25), null).equals(log.area()),
                    "本区记录 deletePlot, 实为 " + log);
            helper.assertTrue(env.repo.plot(plot).isEmpty() && env.repo.friendsOf(plot).isEmpty()
                            && env.repo.plotCells(plot).asMap().isEmpty(),
                    "地块行删除, 三列与朋友级联删除");
            List<PlotLogEntry> history = env.repo.plotLog(plot, 1, Integer.MAX_VALUE, 50);
            helper.assertTrue(history.size() == 3 && history.get(0).action() == PlotLogAction.VACATE
                            && history.get(2).action() == PlotLogAction.CREATE,
                    "地块记录一行不删 (create / freeze / vacate), 实为 " + history);
            helper.assertTrue(!env.recording().exists(claim), "提交后删子领地");

            DistrictQueryService.PlotsView adminView = env.ctx.queries().plots(env.admin, null, "abydos");
            helper.assertTrue(adminView.tombstones() != null && adminView.tombstones().size() == 1
                            && adminView.tombstones().get(0).log().size() == 3
                            && "Warden_Wu".equals(adminView.tombstones().get(0).tombstone().deletedByName()),
                    "墓碑带这块地的全部记录, 只给管理员");
            DistrictQueryService.PlotsView wardenView = env.ctx.queries().plots(player("Warden_Wu"), null, "abydos");
            helper.assertTrue(wardenView.tombstones() == null, "区务长看不到墓碑 (null, 不是空列表)");
        }
        helper.succeed();
    }
}
