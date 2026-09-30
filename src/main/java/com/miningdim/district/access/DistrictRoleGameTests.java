package com.miningdim.district.access;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.core.DistrictError;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictRuleException;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PlotActorRole;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.core.ResidentSyncStatus;
import com.miningdim.district.service.DistrictQueryService;
import com.miningdim.district.service.PlotLayoutService;
import com.miningdim.district.service.PlotOwnerService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static com.miningdim.district.DistrictTestEnv.expect;
import static com.miningdim.district.DistrictTestEnv.op;
import static com.miningdim.district.DistrictTestEnv.player;
import static com.miningdim.district.DistrictTestEnv.uuidOf;

/**
 * 身份判定 (设计文档第七章): 全局身份、对某个区的身份、对某块地的关系与 abilities。每次请求都从库里现算。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class DistrictRoleGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_roles";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void globalRoleFollowsTheDecidedOrder(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Warden_Wu");
            env.resident("abydos", "Resident_Rae");
            env.warden("abydos", "Warden_Wu");
            AccessResolver resolver = env.ctx.access();
            helper.assertTrue(resolver.globalRole(player("Warden_Wu")) == GlobalRole.WARDEN, "区务长的全局身份为 warden");
            helper.assertTrue(resolver.globalRole(player("Resident_Rae")) == GlobalRole.RESIDENT, "住户为 resident");
            helper.assertTrue(resolver.globalRole(player("Nobody_Ned")) == GlobalRole.OUTSIDER, "其余人为 outsider");
            helper.assertTrue(resolver.globalRole(Actor.console()) == GlobalRole.ADMIN, "控制台 (OP) 为 admin");
            helper.assertTrue(env.ctx.queries().state(player("Warden_Wu")).role() == GlobalRole.WARDEN,
                    "district.state viewer.role 与判定器同源");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void opIsAdminEvenWhenOnRoster(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            DistrictRecord abydos = env.abydos();
            env.resident("abydos", "Op_Olga");
            env.warden("abydos", "Op_Olga");
            Actor olga = op("Op_Olga");
            AccessResolver resolver = env.ctx.access();
            helper.assertTrue(resolver.globalRole(olga) == GlobalRole.ADMIN, "OP 即使在名单上、是区务长也算 admin");
            helper.assertTrue(resolver.access(olga, env.districtRecord("abydos")) == DistrictAccess.ADMIN,
                    "OP 对本区的身份恒为 ADMIN");
            DistrictQueryService.StateView state = env.ctx.queries().state(olga);
            helper.assertTrue(state.residency() != null && state.residency().isWarden(),
                    "OP 在名单上时 residency 照样给出 (且带区务长标记)");
            helper.assertTrue(abydos.districtId().equals(state.residency().district().districtId()),
                    "residency 指向 TA 学院的自管区");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wardenOfAnotherDistrictHasNoAccess(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.millennium();
            env.resident("millennium", "Mill_Warden");
            env.warden("millennium", "Mill_Warden");
            env.resident("abydos", "Owner_One");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_One");
            Actor stranger = player("Mill_Warden");
            DistrictRecord abydos = env.districtRecord("abydos");
            helper.assertTrue(env.ctx.access().access(stranger, abydos) == DistrictAccess.NONE,
                    "别区的区务长对本区的身份为 NONE");

            expect(helper, DistrictError.PERMISSION_DENIED, "district.detail",
                    () -> env.ctx.queries().detail(stranger, "abydos"));
            expect(helper, DistrictError.PERMISSION_DENIED, "district.plots",
                    () -> env.ctx.queries().plots(stranger, null, "abydos"));
            DistrictRuleException plotDetail = expect(helper, DistrictError.PERMISSION_DENIED, "plot.detail",
                    () -> env.ctx.queries().plotDetail(stranger, "abydos", plot));
            helper.assertTrue(plotDetail.getMessage().equals("只有户主本人和管理员能看这块地的朋友和权限"),
                    "别区区务长看本区地块时用通用文案, 不用本区区务长的那句, 实为 " + plotDetail.getMessage());
            helper.assertTrue(env.ctx.queries().permissions(stranger, "abydos").access() == DistrictAccess.NONE,
                    "district.permissions 不拒绝, 按外人裁剪");
            expect(helper, DistrictError.PERMISSION_DENIED, "district.addResident",
                    () -> env.ctx.residents().addResident(stranger, "abydos", "Anyone_Ann", true));
            expect(helper, DistrictError.PERMISSION_DENIED, "district.removeResident",
                    () -> env.ctx.residents().removeResident(stranger, "abydos", "Owner_One", "other", "x"));
            expect(helper, DistrictError.PERMISSION_DENIED, "district.setPermission",
                    () -> env.ctx.permissions().setPermission(stranger, "abydos", "place", "outsider", true));
            expect(helper, DistrictError.PERMISSION_DENIED, "plot.create", () -> env.ctx.layout().create(stranger,
                    "abydos", PlotLayoutService.AreaInput.of(new PlotArea(40, 40, 55, 55))));
            expect(helper, DistrictError.PERMISSION_DENIED, "plot.resize", () -> env.ctx.layout().resize(stranger,
                    "abydos", plot, PlotLayoutService.AreaInput.of(new PlotArea(10, 10, 26, 26))));
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unboundAcademyMemberIsOutsiderWithoutResidency(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Kept_Kim");
            env.ctx.admin().unbind(env.admin, "abydos");
            Actor kim = player("Kept_Kim");
            helper.assertTrue(env.repo.memberByUuid(uuidOf("Kept_Kim")).isPresent(), "解绑后名单仍保留");
            helper.assertTrue(env.ctx.access().globalRole(kim) == GlobalRole.OUTSIDER,
                    "已解绑学院的成员全局身份为 outsider");
            DistrictQueryService.StateView state = env.ctx.queries().state(kim);
            helper.assertTrue(state.residency() == null && state.districts().isEmpty(),
                    "已解绑学院的成员没有 residency, 自管区列表也不再列出它");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void abilitiesMatrixPerAccess(GameTestHelper helper) {
        MemberRecord synced = new MemberRecord(1, uuidOf("A_Aa"), "A_Aa", "abydos", 0, null, "op",
                ResidentSyncStatus.SYNCED, null);
        Abilities admin = Abilities.of(DistrictAccess.ADMIN, null);
        Abilities warden = Abilities.of(DistrictAccess.WARDEN, synced);
        Abilities resident = Abilities.of(DistrictAccess.RESIDENT, synced);
        helper.assertTrue(admin.equals(new Abilities(true, true, true, true, true, true, true, true, true, true, true,
                true, true, true, true, true, true)), "管理员全真, 实为 " + admin);
        helper.assertTrue(warden.equals(new Abilities(true, true, true, true, false, false, false, false, true, false,
                true, true, false, false, true, false, false)), "区务长的能力表, 实为 " + warden);
        helper.assertTrue(resident.equals(new Abilities(true, true, false, false, false, false, false, false, false,
                false, true, false, false, false, false, false, false)), "住户的能力表, 实为 " + resident);
        boolean rejected = false;
        try {
            Abilities.of(DistrictAccess.NONE, null);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "NONE 没有能力表 (district.detail 对 NONE 直接拒绝)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildAndInteractFollowOwnSyncStatus(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Synced_Sy");
            env.ctx.residents().addResident(env.admin, "abydos", "Pending_Pe", true);
            env.recording().failWhen(call -> call.op().equals("setMember")
                    && call.hasArg(uuidOf("Failed_Fa").toString()), "区块未加载");
            env.resident("abydos", "Failed_Fa");
            env.warden("abydos", "Pending_Pe");

            helper.assertTrue(env.ctx.queries().detail(player("Synced_Sy"), "abydos").abilities().build(),
                    "已生效的住户能建造");
            Abilities pending = env.ctx.queries().detail(player("Pending_Pe"), "abydos").abilities();
            helper.assertTrue(!pending.build() && !pending.interact() && pending.manageResidents(),
                    "待生效的区务长不能建造交互, 但管人的能力照旧, 实为 " + pending);
            Abilities failed = env.ctx.queries().detail(player("Failed_Fa"), "abydos").abilities();
            helper.assertTrue(!failed.build() && !failed.interact() && failed.viewPlotList(),
                    "同步失败的住户不能建造交互, 实为 " + failed);
            helper.assertTrue(env.ctx.queries().detail(env.admin, "abydos").abilities().build(),
                    "管理员恒能建造");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void opOwningAPlotActsAsOwnerNotOverride(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Op_Olga");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Op_Olga");
            Actor olga = op("Op_Olga");
            helper.assertTrue(env.ctx.access().relation(olga, env.plotRecord(plot)) == PlotRelation.OWNER,
                    "户主最先判: OP 管自己的地时关系是 OWNER");
            PlotOwnerService.SetResult set = env.ctx.plotOwners().setPermission(olga, "abydos", plot, "place",
                    "outsider", true);
            helper.assertTrue(set.logEntry() != null && set.logEntry().actorRole() == PlotActorRole.OWNER
                            && !set.logEntry().onBehalfOfOwner(),
                    "OP 改自己的地块记为户主本人改的, 不记代改, 实为 " + set.logEntry());
        }
        helper.succeed();
    }
}
