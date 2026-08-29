package com.miningdim.progression;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.JobExperienceTracks;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Set;

/**
 * 全服经验路由层 GameTest (docs/modules/experience/README.md 第 5 节"验证要求"逐条落用例)。
 *
 * 分两层断言, 都是具体数值或具体异常类型 (删掉被测裁决即挂):
 *  - 隔离层: 另造一个 {@link ExperienceModule.ExperienceRouter} 实例, 配桩轨道处理器驱动全部 fail-fast
 *    分支 (重复轨道注册 / 来源改绑别的轨道 / 未登记来源 / 来源与轨道不匹配 / 轨道返回负有效经验), 并断言
 *    被拒的注册与入账"确实没落地"(既有 handler 未被顶替、桩的 award 一次都没被调到);
 *  - 在役层: 打进程级正式服务, 断言职业轨道/来源在装配期已就位, 跨轨道发放对两条轨道都是零增量, 且旧
 *    {@code IJobService.grantXp} 与新来源 award 对同一条农夫轨道给出同一条曲线结果。
 *
 * 本类 import com.miningdim.job 仅是测试侧的跨模块联测 (与 entry/market/trap 各自的 GameTest 同范式),
 * 不构成 progression 生产代码对 job 的反向依赖 —— 路由器本体仍不认识 JobId/JobProgress。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ExperienceGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "experience";

    // 隔离路由器专用 ID: 只出现在本类新造的实例里, 从不进正式注册表。
    private static final ResourceLocation PROBE_TRACK =
            new ResourceLocation(MiningConstants.MODID, "test/probe_track");
    private static final ResourceLocation OTHER_TRACK =
            new ResourceLocation(MiningConstants.MODID, "test/other_track");
    private static final ResourceLocation ABSENT_TRACK =
            new ResourceLocation(MiningConstants.MODID, "test/absent_track");
    private static final ResourceLocation PROBE_SOURCE =
            new ResourceLocation(MiningConstants.MODID, "test/probe_source");
    private static final ResourceLocation UNREGISTERED_SOURCE =
            new ResourceLocation(MiningConstants.MODID, "test/unregistered_source");

    /**
     * 农夫细分来源 ID 的字面量副本。README 第 2 节把它列为对外稳定契约, 而 {@code FarmerExperience} 是包内
     * 可见的, 本类拿不到那份常量 —— 这里刻意写死字面量, 让"农夫改了来源 ID 却没同步文档/接入方"直接测挂。
     */
    private static final ResourceLocation FARMER_HARVEST_SOURCE =
            new ResourceLocation(MiningConstants.MODID, "farmer/harvest");

    // ============================================================
    // 隔离路由器: 五条 fail-fast 分支
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void duplicateTrackRegistrationIsRejectedWithoutReplacingTheIncumbent(GameTestHelper helper) {
        ExperienceModule.ExperienceRouter router = new ExperienceModule.ExperienceRouter();
        ProbeHandler incumbent = new ProbeHandler(3L);
        router.registerTrack(PROBE_TRACK, incumbent);

        // 同一实例重复注册幂等: 装配期重入不该炸 (JobFrameworkSystem 对八个职业逐个注册, 顺序敏感度要为零)。
        router.registerTrack(PROBE_TRACK, incumbent);
        helper.assertTrue(router.hasTrack(PROBE_TRACK), "track must stay registered after idempotent re-register");

        ProbeHandler intruder = new ProbeHandler(100L);
        boolean rejected = false;
        try {
            router.registerTrack(PROBE_TRACK, intruder);
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "registering a second handler for one track must fail fast");

        // 被拒的注册不得半途改写注册表: 走一笔账, 必须落在原 handler 上 (x3), 而不是入侵者 (x100)。
        Player mock = helper.makeMockPlayer();
        router.registerSource(PROBE_SOURCE, PROBE_TRACK);
        ExperienceAward award = router.award(mock, new ExperienceGrant(PROBE_TRACK, PROBE_SOURCE, 7L));
        helper.assertTrue(award.effectiveXp() == 21L,
                "rejected duplicate registration must not replace the incumbent handler (7 x 3 = 21)");
        helper.assertTrue(incumbent.awardCalls == 1 && intruder.awardCalls == 0,
                "only the incumbent handler may receive the award call");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sourceCannotBeReboundToAnotherTrack(GameTestHelper helper) {
        ExperienceModule.ExperienceRouter router = new ExperienceModule.ExperienceRouter();
        ProbeHandler owner = new ProbeHandler(2L);
        ProbeHandler stranger = new ProbeHandler(2L);
        router.registerTrack(PROBE_TRACK, owner);
        router.registerTrack(OTHER_TRACK, stranger);
        router.registerSource(PROBE_SOURCE, PROBE_TRACK);
        router.registerSource(PROBE_SOURCE, PROBE_TRACK); // 幂等重入

        boolean reboundRejected = false;
        try {
            router.registerSource(PROBE_SOURCE, OTHER_TRACK);
        } catch (IllegalStateException expected) {
            reboundRejected = true;
        }
        helper.assertTrue(reboundRejected, "a source already bound to one track must not be rebound");

        boolean unknownTrackRejected = false;
        try {
            router.registerSource(UNREGISTERED_SOURCE, ABSENT_TRACK);
        } catch (IllegalStateException expected) {
            unknownTrackRejected = true;
        }
        helper.assertTrue(unknownTrackRejected, "binding a source to an unregistered track must fail fast");
        helper.assertFalse(router.hasSource(UNREGISTERED_SOURCE),
                "a rejected source binding must leave no entry behind");

        // 原绑定仍然有效且未被改写: 发到原轨道成功, 发到 OTHER_TRACK 仍被判为跨轨道。
        Player mock = helper.makeMockPlayer();
        helper.assertTrue(router.award(mock, new ExperienceGrant(PROBE_TRACK, PROBE_SOURCE, 5L)).effectiveXp() == 10L,
                "the original binding must still route to its own track (5 x 2 = 10)");
        boolean stillMismatch = false;
        try {
            router.award(mock, new ExperienceGrant(OTHER_TRACK, PROBE_SOURCE, 5L));
        } catch (IllegalStateException expected) {
            stillMismatch = true;
        }
        helper.assertTrue(stillMismatch, "the rejected rebind must not have leaked the source into the other track");
        helper.assertTrue(stranger.awardCalls == 0, "the other track must never receive this source's XP");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unknownAndMismatchedSourcesAreRejectedBeforeAnyXpLands(GameTestHelper helper) {
        ExperienceModule.ExperienceRouter router = new ExperienceModule.ExperienceRouter();
        ProbeHandler owner = new ProbeHandler(3L);
        ProbeHandler stranger = new ProbeHandler(3L);
        router.registerTrack(PROBE_TRACK, owner);
        router.registerTrack(OTHER_TRACK, stranger);
        router.registerSource(PROBE_SOURCE, PROBE_TRACK);
        Player mock = helper.makeMockPlayer();

        boolean unknownRejected = false;
        try {
            router.award(mock, new ExperienceGrant(PROBE_TRACK, UNREGISTERED_SOURCE, 10L));
        } catch (IllegalStateException expected) {
            unknownRejected = true;
        }
        helper.assertTrue(unknownRejected, "an unregistered source must not be able to award XP");

        boolean mismatchRejected = false;
        try {
            router.award(mock, new ExperienceGrant(OTHER_TRACK, PROBE_SOURCE, 10L));
        } catch (IllegalStateException expected) {
            mismatchRejected = true;
        }
        helper.assertTrue(mismatchRejected, "a source must not award XP into a track it does not belong to");
        helper.assertTrue(owner.awardCalls == 0 && stranger.awardCalls == 0,
                "both rejected grants must fail before reaching any track handler");

        ExperienceAward award = router.award(mock, new ExperienceGrant(PROBE_TRACK, PROBE_SOURCE, 10L));
        helper.assertTrue(owner.awardCalls == 1 && stranger.awardCalls == 0,
                "only the owning track handler settles the grant");
        helper.assertTrue(award.trackId().equals(PROBE_TRACK) && award.sourceId().equals(PROBE_SOURCE),
                "the award receipt must echo the routed track and source");
        helper.assertTrue(award.rawXp() == 10L && award.effectiveXp() == 30L,
                "the award receipt must carry the raw request (10) and the handler's effective result (30)");
        // snapshot 必须是入账之后的状态: 若路由器在 handler.award 之前取快照, 这里读到的会是 0 / 0 级。
        helper.assertTrue(award.snapshot().totalXp() == 30L && award.snapshot().level() == 3,
                "the award snapshot must be taken after the handler persisted the grant");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void negativeXpIsRejectedAtBothTheGrantAndTheTrackResult(GameTestHelper helper) {
        ExperienceModule.ExperienceRouter router = new ExperienceModule.ExperienceRouter();
        ProbeHandler thief = new ProbeHandler(-1L);
        router.registerTrack(PROBE_TRACK, thief);
        router.registerSource(PROBE_SOURCE, PROBE_TRACK);

        boolean rawRejected = false;
        try {
            new ExperienceGrant(PROBE_TRACK, PROBE_SOURCE, -1L);
        } catch (IllegalArgumentException expected) {
            rawRejected = true;
        }
        helper.assertTrue(rawRejected, "a grant carrying negative raw XP must be rejected at construction");

        boolean effectiveRejected = false;
        try {
            router.award(helper.makeMockPlayer(), new ExperienceGrant(PROBE_TRACK, PROBE_SOURCE, 5L));
        } catch (IllegalStateException expected) {
            effectiveRejected = true;
        }
        helper.assertTrue(effectiveRejected, "a track returning negative effective XP must fail fast");
        helper.assertTrue(thief.awardCalls == 1,
                "the negative result must be caught after the handler ran, not by skipping it");
        helper.succeed();
    }

    // ============================================================
    // 在役正式服务: 装配契约 + 跨轨道零增量 + 新旧入口同曲线
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void liveServiceRegistersEveryJobTrackAndItsLegacySource(GameTestHelper helper) {
        IExperienceService service = ExperienceServices.experienceService();
        Set<ResourceLocation> tracks = service.trackIds();
        for (JobId job : JobId.values()) {
            helper.assertTrue(tracks.contains(JobExperienceTracks.track(job)),
                    "job track must be registered in the server-wide experience service: " + job.id());
            helper.assertTrue(service.hasSource(JobExperienceTracks.legacySource(job)),
                    "legacy job source must be registered so IJobService.grantXp can route: " + job.id());
        }
        helper.assertTrue(service.hasSource(FARMER_HARVEST_SOURCE),
                "the documented farmer harvest source must stay registered under its published ID");
        helper.assertFalse(service.hasTrack(PROBE_TRACK),
                "isolated test tracks must never leak into the process-wide registry");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void liveServiceRejectsCrossJobSourceWithZeroXpDelta(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        IExperienceService service = ExperienceServices.experienceService();
        ResourceLocation farmerTrack = JobExperienceTracks.track(JobId.FARMER);
        ResourceLocation minerTrack = JobExperienceTracks.track(JobId.MINER);

        boolean unknownRejected = false;
        try {
            service.award(player, new ExperienceGrant(farmerTrack, UNREGISTERED_SOURCE, 100L));
        } catch (IllegalStateException expected) {
            unknownRejected = true;
        }
        helper.assertTrue(unknownRejected, "an unregistered source must not reach a live job track");

        // 正是审查所说的事故形态: 矿工来源错发进农夫轨道。必须抛, 且两条轨道一分都不能动。
        boolean crossRejected = false;
        try {
            service.award(player, new ExperienceGrant(farmerTrack,
                    JobExperienceTracks.legacySource(JobId.MINER), 100L));
        } catch (IllegalStateException expected) {
            crossRejected = true;
        }
        helper.assertTrue(crossRejected, "a miner source must not be able to award farmer XP");
        helper.assertTrue(service.snapshot(player, farmerTrack).totalXp() == 0L,
                "a rejected cross-track grant must leave the target track at zero");
        helper.assertTrue(service.snapshot(player, minerTrack).totalXp() == 0L,
                "a rejected cross-track grant must leave the source's own track at zero");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void legacyJobGrantAndFarmerSourceAwardShareOneCurve(GameTestHelper helper) {
        // README 第 5 节: 旧 IJobService.grantXp 与新来源入口对同一轨道必须得到相同曲线结果。
        // 两个全新 mock 玩家保证当日已结算有效经验都从 0 起跑, 否则第二笔会踩在第一笔推高的衰减指针上。
        ServerPlayer viaLegacy = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer viaSource = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        IExperienceService service = ExperienceServices.experienceService();
        ResourceLocation farmerTrack = JobExperienceTracks.track(JobId.FARMER);

        // 农夫表C: 当日 0 起点入 2000 原始, 先填 [0,1500) 的 x1.0 得 1500, 余 500 原始落 x0.30 得 150, 合 1650。
        // 刻意跨衰减档取值 —— 若哪天有人把职业轨道换成不带衰减的直加, 这个数会变成 2000 而当场测挂。
        long rawXp = 2_000L;
        long legacyEffective = JobServices.jobService().grantXp(viaLegacy, JobId.FARMER, rawXp);
        helper.assertTrue(legacyEffective == 1_650L,
                "legacy grantXp must route through the farmer daily-decay curve (2000 raw -> 1650 effective)");

        ExperienceAward award = service.award(viaSource,
                new ExperienceGrant(farmerTrack, FARMER_HARVEST_SOURCE, rawXp));
        helper.assertTrue(award.effectiveXp() == legacyEffective,
                "a source-tagged award must yield exactly the same effective XP as the legacy entry point");
        helper.assertTrue(service.snapshot(viaLegacy, farmerTrack).totalXp() == 1_650L
                        && service.snapshot(viaSource, farmerTrack).totalXp() == 1_650L,
                "both entry points must persist the same total XP on their own player");
        helper.assertTrue(award.snapshot().totalXp() == 1_650L && award.snapshot().level() == 1,
                "the award snapshot must report the post-grant farmer state (1650 XP is still level 1)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void serviceLocatorRefusesToBeHijackedAfterStartup(GameTestHelper helper) {
        IExperienceService live = ExperienceServices.experienceService();

        boolean nullRejected = false;
        try {
            ExperienceServices.register(null);
        } catch (IllegalArgumentException expected) {
            nullRejected = true;
        }
        helper.assertTrue(nullRejected, "the locator must refuse a null service");

        boolean hijackRejected = false;
        try {
            ExperienceServices.register(new ExperienceModule.ExperienceRouter());
        } catch (IllegalStateException expected) {
            hijackRejected = true;
        }
        helper.assertTrue(hijackRejected, "a second, different service instance must be refused");

        // 同实例重复注册幂等 (模块 register 重入不该炸), 且冲突注册没换掉已就位的实例。
        ExperienceServices.register(live);
        helper.assertTrue(ExperienceServices.experienceService() == live,
                "the live service instance must survive both the null and the hijack attempt");
        helper.succeed();
    }

    /**
     * 轨道桩: 有效经验 = 原始经验 x factor, 等级 = 已入账有效经验 / 10。factor 取负用来驱动路由器"轨道返回
     * 负有效经验"的兜底分支。负值时不累加, 否则 {@link ExperienceSnapshot} 自身的非负校验会先炸,
     * 就测不到路由器那一层了。
     */
    private static final class ProbeHandler implements ExperienceTrackHandler {

        private final long factor;
        private int awardCalls;
        private long granted;

        private ProbeHandler(long factor) {
            this.factor = factor;
        }

        @Override
        public ExperienceSnapshot snapshot(Player player) {
            return new ExperienceSnapshot(granted, (int) (granted / 10L));
        }

        @Override
        public long award(Player player, long rawXp) {
            awardCalls++;
            long effective = rawXp * factor;
            if (effective > 0L) {
                granted += effective;
            }
            return effective;
        }
    }
}
