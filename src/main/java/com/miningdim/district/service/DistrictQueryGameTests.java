package com.miningdim.district.service;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.access.DistrictAccess;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.PermissionCatalog;
import com.miningdim.district.core.PlotStatus;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

import static com.miningdim.district.DistrictTestEnv.player;
import static com.miningdim.district.DistrictTestEnv.uuidOf;

/**
 * 只读快照 (district.state / district.detail / district.plots): 自管区按学院顺序、计数口径、"我是哪几块地的朋友"只列有户主的地块、地块摘要的派生字段、
 * 名单与记录只给管理者、朋友身份块数含冻结地块。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class DistrictQueryGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_queries";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void stateListsLiveDistrictsInAcademyOrderWithCounts(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.millennium();
            env.abydos();
            env.resident("abydos", "Owner_One");
            env.resident("abydos", "Owner_Two");
            env.ctx.residents().addResident(env.admin, "abydos", "Pending_Pe", true);
            env.recording().failNext("setMember", "区块未加载");
            env.resident("abydos", "Failed_Fa");
            String owned = env.plot("abydos", 10, 10, 25, 25);
            String frozen = env.plot("abydos", 40, 10, 55, 25);
            env.plot("abydos", 70, 10, 85, 25);
            env.own(owned, "Owner_One");
            env.own(frozen, "Owner_Two");
            env.ctx.residents().removeResident(env.admin, "abydos", "Owner_Two", "inactive", "不上线");

            DistrictQueryService.StateView state = env.ctx.queries().state(env.admin);
            List<String> order = state.districts().stream().map(s -> s.district().districtId()).toList();
            helper.assertTrue(order.equals(List.of("abydos", "millennium")), "按学院顺序, 实为 " + order);
            DistrictQueryService.DistrictSummaryView abydos = state.districts().get(0);
            helper.assertTrue(abydos.residentCount() == 3 && abydos.plotCount() == 3 && abydos.vacantPlotCount() == 1,
                    "住户数含待生效与同步失败, 地块数含冻结, 空置只数空置, 实为 " + abydos);
            helper.assertTrue(abydos.syncIssues().pending() == 1 && abydos.syncIssues().failed() == 1,
                    "syncIssues {pending 1, failed 1}, 实为 " + abydos.syncIssues());
            helper.assertTrue("阿拜多斯".equals(abydos.academy().shortName())
                    && "阿拜多斯学院".equals(abydos.academy().fullName())
                    && "阿拜多斯自管区".equals(abydos.district().displayName()), "学院简称、全称与显示名");
            helper.assertTrue(abydos.district().bounds().area() == 40_000L, "面积按 long 算");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void friendOfListsOnlyOwnedPlotsAcrossDistricts(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.millennium();
            env.abydos();
            env.resident("abydos", "Owner_A");
            env.resident("abydos", "Owner_B");
            env.resident("millennium", "Mill_Owner");
            env.seen("Buddy_Bea");
            String mill = env.plot("millennium", 1010, 10, 1025, 25);
            String plotA = env.plot("abydos", 10, 10, 25, 25);
            String plotB = env.plot("abydos", 40, 10, 55, 25);
            env.own(mill, "Mill_Owner");
            env.own(plotA, "Owner_A");
            env.own(plotB, "Owner_B");
            for (String[] pair : new String[][]{{"millennium", mill, "Mill_Owner"}, {"abydos", plotA, "Owner_A"},
                    {"abydos", plotB, "Owner_B"}}) {
                env.ctx.plotOwners().addFriend(player(pair[2]), pair[0], pair[1], "Buddy_Bea", false);
            }
            env.ctx.residents().removeResident(env.admin, "abydos", "Owner_B", "inactive", "不上线");
            DistrictQueryService.StateView state = env.ctx.queries().state(player("Buddy_Bea"));
            List<String> plots = state.friendOf().stream().map(f -> f.plot().plotId()).toList();
            helper.assertTrue(plots.equals(List.of(plotA, mill)),
                    "只列有户主的地块 (冻结的不列), 先按自管区顺序再按编号, 实为 " + plots);
            helper.assertTrue(state.residency() == null && !state.friendOfTruncated(), "外人没有 residency");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void plotSummaryDerivedFields(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            env.resident("abydos", "Owner_Two");
            String vacant = env.plot("abydos", 10, 10, 25, 25);
            String owned = env.plot("abydos", 40, 10, 55, 25);
            String frozen = env.plot("abydos", 70, 10, 85, 25);
            env.own(owned, "Owner_One");
            env.own(frozen, "Owner_Two");
            env.ctx.plotOwners().setPermission(player("Owner_One"), "abydos", owned, "bed", "resident", true);
            env.ctx.residents().removeResident(env.admin, "abydos", "Owner_Two", "inactive", "不上线");
            env.ctx.residents().addResident(env.admin, "abydos", "Owner_Two", false);

            DistrictQueryService.PlotsView view = env.ctx.queries().plots(player("Owner_One"), null, "abydos");
            DistrictQueryService.PlotSummaryView v = view.plots().get(0);
            DistrictQueryService.PlotSummaryView o = view.plots().get(1);
            DistrictQueryService.PlotSummaryView f = view.plots().get(2);
            helper.assertTrue(v.plot().status() == PlotStatus.VACANT && v.price() != null && v.price() == 1280L
                            && v.area() == 256L && v.residentColumnIsDefault()
                            && v.openToResidents().equals(PermissionCatalog.residentDefaultLabels()),
                    "空置: 有价格, 住户列按默认, 实为 " + v);
            helper.assertTrue(o.price() == null && !o.residentColumnIsDefault() && o.openToResidents().contains("睡床"),
                    "有户主: 没有价格, 住户列改过, 实为 " + o);
            helper.assertTrue(f.plot().status() == PlotStatus.FROZEN && f.openToResidents().isEmpty()
                            && !f.residentColumnIsDefault() && f.freeze() != null && f.freeze().canRestore()
                            && "Owner_Two".equals(f.freeze().formerOwnerName()),
                    "冻结: 不列任何一项, 原户主回来了 canRestore 为真, 实为 " + f);
            helper.assertTrue(view.myPlot() != null && owned.equals(view.myPlot().plotId())
                            && view.access() == DistrictAccess.RESIDENT && view.tombstones() == null,
                    "myPlot 是查看者的地块; 住户没有墓碑");
            helper.assertTrue(view.residentDefaults().size() == 6, "residentDefaults 6 项");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void detailRosterAndLogOnlyForManagers(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Warden_Wu");
            env.warden("abydos", "Warden_Wu");
            env.resident("abydos", "Owner_A");
            env.resident("abydos", "Owner_B");
            env.resident("abydos", "Social_Sam");
            String plotA = env.plot("abydos", 10, 10, 25, 25);
            String plotB = env.plot("abydos", 40, 10, 55, 25);
            env.own(plotA, "Owner_A");
            env.own(plotB, "Owner_B");
            env.ctx.plotOwners().addFriend(player("Owner_A"), "abydos", plotA, "Social_Sam", false);
            env.ctx.plotOwners().addFriend(player("Owner_B"), "abydos", plotB, "Social_Sam", false);
            env.ctx.residents().removeResident(env.admin, "abydos", "Owner_B", "inactive", "不上线");

            DistrictQueryService.DetailView resident = env.ctx.queries().detail(player("Social_Sam"), "abydos");
            helper.assertTrue(resident.residents() == null && resident.log() == null,
                    "住户拿到的名单与记录是 null (与空列表区分开)");
            DistrictQueryService.DetailView warden = env.ctx.queries().detail(player("Warden_Wu"), "abydos");
            helper.assertTrue(warden.residents() != null && warden.residents().size() == 3 && warden.log() != null
                            && !warden.log().isEmpty(),
                    "区务长拿到名单与记录 (含移出原因)");
            DistrictQueryService.ResidentView sam = warden.residents().stream()
                    .filter(r -> r.member().uuid().equals(uuidOf("Social_Sam"))).findFirst().orElseThrow();
            helper.assertTrue(sam.friendOfPlotCount() == 2 && sam.plot() == null && !sam.isWarden(),
                    "朋友身份块数含冻结地块, 实为 " + sam.friendOfPlotCount());
            DistrictQueryService.ResidentView owner = warden.residents().stream()
                    .filter(r -> r.member().uuid().equals(uuidOf("Owner_A"))).findFirst().orElseThrow();
            helper.assertTrue(owner.plot() != null && plotA.equals(owner.plot().plotId()) && owner.friendOfPlotCount() == 0,
                    "户主的现有地块; 自己的地块不算朋友身份");
            List<String> names = warden.residents().stream().map(r -> r.member().name()).toList();
            helper.assertTrue(names.equals(List.of("Warden_Wu", "Owner_A", "Social_Sam")),
                    "名单按入学顺序 (被移出的 Owner_B 已不在), 实为 " + names);
            DistrictRecord district = warden.summary().district();
            helper.assertTrue("Warden_Wu".equals(district.wardenName()), "摘要带区务长");
        }
        helper.succeed();
    }
}
