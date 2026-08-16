package com.miningdim.job.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.integration.ChampionPromoter;
import com.miningdim.core.MiningConstants;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.JobId;
import com.miningdim.job.agent.integration.AgentIntegrationBootstrap;
import com.miningdim.job.agent.panel.AgentScanSnapshotBuilder;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * W4a 特勤的 job.agent.state / job.agent.scan / job.agent.seal GameTest。
 *
 * 本组的被测面是"服务端这条路自洽": 防 X 光的三道门 (脉冲 CD / 球形半径 / 分级解密) 与封印的两道前置门
 * (快照有效 / 词条已解密), 加上九态裁决的逐字透传。
 *
 * 为什么要给接缝装桩: {@link AgentSealSeam} 的真实现住在集成层 (读真精英词条 + 聚合 SealPlan/SealRegistry),
 * 桩把"分级解密九态透传"整段替换成一张 champions-free 的固定词条表, 好让"九态透传"能被逐个断言而不是只测到
 * OK 一条。桩<b>不再</b>替代"目标是不是精英"这一判据 (F082 剪枝后 {@code AgentWebUiActions.SCAN} 的
 * {@code getEntitiesOfClass} predicate 直接调 {@code MiningChampions.isChampion}, 自定义名标记的假目标会被
 * 整批筛掉): {@link #spawnMarkedTarget} 改为对生成的僵尸真调
 * {@link com.miningdim.champion.integration.ChampionPromoter#applyChampion} 盖章 (真写
 * {@code MiningChampionData} capability), {@link #markedStar} 相应改读该 capability 的 {@code star()},
 * 星级词条表本身仍完全由 {@link #STUB_SCAN}/{@link #STUB_SEAL} 桩提供 (真盖章只用来通过预筛, 不参与九态裁决)。
 *
 * 桩绑定后不解绑: 同一批 (batch) 内的用例在同一轮 tick 里陆续执行, 中途解绑会打断同批其它用例。真正需要
 * "未绑定"的那一条单独放在 {@value #BATCH_OFFLINE} 批 (批与批之间串行), 且 {@code AgentGameTests
 * .sealSeamShortCircuitsWhenUnbound} 自己会先 unbind, 故批序如何排列都能自愈; 两条用例末尾都会调
 * {@link com.miningdim.job.agent.integration.AgentIntegrationBootstrap#bindSeam} 把真实现装回来 (接缝现在
 * 启动期就绑好且 {@code ServerStopping} 不再解绑, 测试留下的未绑定状态会一直污染到进程重启)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class AgentWebUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui_w4a";
    /** 接缝未绑定的用例必须与装桩用例分批 (批间串行), 否则两者会互相拆台。 */
    private static final String BATCH_OFFLINE = "webui_w4a_offline";

    private static final String STATE_ACTION = "job.agent.state";
    private static final String SCAN_ACTION = "job.agent.scan";
    private static final String SEAL_ACTION = "job.agent.seal";

    /** 桩词条表里唯一的机制类词条 (用来验机制真名需 L5+ 才解密)。 */
    private static final String MECHANIC_AFFIX_ID = "miningdim:webui_mechanic";

    /**
     * 桩的候选词条表: 每个可由集成层产出的 {@link AgentSealSeam.SealOutcome} 各一条被动词条, 末尾追一条机制词条。
     * 顺序即解密顺序 (L1 只解密第 0 条), 故 index 0 恒为 OK 那条。
     */
    private static final List<AgentScanSnapshotBuilder.RawAffix> STUB_RAW_AFFIXES = buildStubAffixes();

    private static final AgentSealSeam.ScanSnapshotRequest STUB_SCAN = (agent, target) -> {
        int star = markedStar(target);
        if (star < 1) {
            return null; // 未打标 = 非本工程盖章精英 (与 AgentScanProbe 对非精英返 null 同语义)。
        }
        return AgentScanSnapshotBuilder.build(target.getId(), star, AgentLevels.agentLevel(agent), STUB_RAW_AFFIXES);
    };

    private static final AgentSealSeam.SealResultRequest STUB_SEAL = (agent, target, affixId) -> {
        for (AgentSealSeam.SealOutcome outcome : AgentSealSeam.SealOutcome.values()) {
            if (outcome != AgentSealSeam.SealOutcome.NOT_BOUND && outcomeAffixId(outcome).equals(affixId)) {
                return outcome;
            }
        }
        return AgentSealSeam.SealOutcome.AFFIX_NOT_SEALABLE;
    };

    /** 桩没有执行侧词条快照可清 (真改是 Champions 集成层的事), 服务端停止回调空转即正确行为。 */
    private static final AgentSealSeam.ServerStopCleanup STUB_CLEANUP = () -> {
    };

    // ============================================================
    // 1. 注册
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void agentActionsAreRegisteredUnderContractNames(GameTestHelper helper) {
        ensureAgentActionsRegistered();
        helper.assertTrue(WebUiServerDispatcher.resolve(STATE_ACTION) != null
                        && WebUiServerDispatcher.resolve(SCAN_ACTION) != null
                        && WebUiServerDispatcher.resolve(SEAL_ACTION) != null,
                "三条 job.agent.* 必须由 AgentWebUiActions.registerAll 注册进派发器");
        helper.assertTrue(WebUiServerDispatcher.resolve("agent.scan") == null
                        && WebUiServerDispatcher.resolve("job.agent.pulse") == null,
                "不得注册别名 action (前端契约里只有 job.agent.state/scan/seal 三条)");
        helper.succeed();
    }

    // ============================================================
    // 2. job.agent.state: 表值逐格对齐, 且不推进任何状态
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void agentStateOnFreshAccountReportsRealTableValues(GameTestHelper helper) {
        ensureSeamStubBound();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonObject state = handle(helper, STATE_ACTION, player, new JsonObject());

        helper.assertTrue(state.get("level").getAsInt() == 1, "新号干员 1 级");
        helper.assertTrue(state.get("scanRadiusBlocks").getAsInt() == 64,
                "L1 扫描范围 64 格 (第四章范围列), 实得 " + state.get("scanRadiusBlocks").getAsInt());
        helper.assertTrue(!state.get("scanCrossChunk").getAsBoolean(), "跨区块是 L10 才有的形态");
        helper.assertTrue(state.get("scanPulseCooldownTicks").getAsInt() == 1200,
                "L1 脉冲 CD 60s = 1200 tick, 实得 " + state.get("scanPulseCooldownTicks").getAsInt());
        helper.assertTrue(state.get("scanCooldownRemainingTicks").getAsLong() == 0L
                        && state.get("snapshotRemainingTicks").getAsLong() == 0L,
                "从未扫过时两个剩余量都必须是真值 0 (不许下溢成巨大正数)");
        helper.assertTrue(state.getAsJsonArray("targets").isEmpty(), "从未扫过时候选表是空数组");

        JsonObject seal = state.getAsJsonObject("seal");
        helper.assertTrue(!seal.get("passiveUnlocked").getAsBoolean()
                        && !seal.get("mechanicUnlocked").getAsBoolean(),
                "L1 被动封印 (L3) 与机制封印 (L8) 都未解锁");
        helper.assertTrue(seal.get("maxSealableStar").getAsInt() == 0,
                "未解锁封印时可封星级是真值 0, 实得 " + seal.get("maxSealableStar").getAsInt());
        helper.assertTrue(seal.get("passiveWindowSeconds").getAsInt() == 0
                        && seal.get("passiveCooldownSeconds").getAsInt() == 0,
                "L1 被动窗口/CD 均为 0 (不可封)");
        helper.assertTrue(seal.get("slotsDefault").getAsInt() == 0 && seal.get("slotsVsStar8Plus").getAsInt() == 0,
                "L1 未解锁封印, 槽容量恒 0");
        helper.assertTrue(seal.get("passiveCooldownRemainingTicks").getAsLong() == 0L,
                "从未封过时封印 CD 剩余是 0 (nextAllowedTick 无记录返 0, 减法不得变成负数)");

        JsonObject bounty = state.getAsJsonObject("bounty");
        helper.assertTrue(bounty.get("dailySlots").getAsInt() == 1 && bounty.get("weeklySlots").getAsInt() == 0,
                "L1 日 1 槽 / 周 0 槽 (第四章), 实得 " + bounty.get("dailySlots").getAsInt()
                        + "/" + bounty.get("weeklySlots").getAsInt());
        helper.assertTrue(!bounty.get("weeklyUnlocked").getAsBoolean()
                        && !bounty.get("worldBossUnlocked").getAsBoolean(),
                "L1 周常 (L4) 与世界 BOSS 悬赏 (L8) 都未解锁");
        helper.assertTrue(bounty.get("weeklyAzureGranted").getAsLong() == 0L
                        && bounty.get("weeklyAzureCap").getAsLong() == AgentBountySavedData.WEEKLY_AZURE_SOFT_CAP,
                "新号本周青辉石产出 0, 上限恒发 " + AgentBountySavedData.WEEKLY_AZURE_SOFT_CAP);
        helper.assertTrue(!bounty.get("available").getAsBoolean(),
                "F017/F078: 悬赏接取/进度/发奖尚未上线, available 必须诚实报 false, 不得把等级门槛预览包装成可用系统");

        helper.assertTrue(Math.abs(state.get("enhancedRewardMultiplier").getAsDouble() - 1.0D) < 1.0E-9D,
                "L1 加强奖励倍率 ×1.0");
        helper.assertTrue(state.get("damageBonusPercent").getAsInt() == 5, "L1 对精英伤害加成 +5%");
        helper.assertTrue(!state.get("activeAgent").getAsBoolean(),
                "从未做过特勤活计的新号入职标志必须是 false (否则特勤专属福利对全服敞开)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void agentStateAtMaxLevelReportsCrossChunkAndLiveSealCooldown(GameTestHelper helper) {
        ensureSeamStubBound();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(player, 10);
        long now = player.serverLevel().getGameTime();
        // 真往 SealRegistry 的活账本里占一次槽: state 的封印 CD 必须读这本账, 自己另记一份就是开后门。
        SealRegistry.ApplyResult applied = SealRegistry.applySeal(UUID.randomUUID(), player.getUUID(),
                "miningdim:webui_state_probe", SealCategory.PASSIVE, 10, 10, now);
        helper.assertTrue(applied.ok(), "前提校验: L10 对 10★ 占槽必须成功, 否则本条测不到 CD 回显");

        JsonObject state = handle(helper, STATE_ACTION, player, new JsonObject());
        helper.assertTrue(state.get("level").getAsInt() == 10, "满级干员 10 级");
        helper.assertTrue(state.get("scanCrossChunk").getAsBoolean(), "L10 范围列是跨区块");
        helper.assertTrue(state.get("scanRadiusBlocks").getAsInt() >= 448,
                "跨区块的有效半径不得比 L9 的 448 还短 (曲线必须单调), 实得 "
                        + state.get("scanRadiusBlocks").getAsInt());
        helper.assertTrue(state.get("scanPulseCooldownTicks").getAsInt() == 600,
                "L10 脉冲 CD 30s = 600 tick, 实得 " + state.get("scanPulseCooldownTicks").getAsInt());

        JsonObject seal = state.getAsJsonObject("seal");
        helper.assertTrue(seal.get("passiveUnlocked").getAsBoolean() && seal.get("mechanicUnlocked").getAsBoolean(),
                "L10 两类封印都已解锁");
        helper.assertTrue(seal.get("maxSealableStar").getAsInt() == 10, "L10 可封 10★");
        helper.assertTrue(seal.get("passiveWindowSeconds").getAsInt() == 12
                        && seal.get("passiveCooldownSeconds").getAsInt() == 18,
                "L10 被动窗口 12s / CD 18s (第四章), 实得 " + seal.get("passiveWindowSeconds").getAsInt()
                        + "s/" + seal.get("passiveCooldownSeconds").getAsInt() + "s");
        helper.assertTrue(seal.get("mechanicWindowSeconds").getAsInt() == 5
                        && seal.get("mechanicCooldownSeconds").getAsInt() == 45,
                "L10 机制窗口 5s / CD 45s (第四章)");
        helper.assertTrue(seal.get("slotsDefault").getAsInt() == 1 && seal.get("slotsVsStar8Plus").getAsInt() == 2,
                "L10 对普通目标 1 槽, 对 8★+ 才 2 槽 (防叠叠乐), 实得 " + seal.get("slotsDefault").getAsInt()
                        + "/" + seal.get("slotsVsStar8Plus").getAsInt());
        helper.assertTrue(seal.get("passiveCooldownRemainingTicks").getAsLong() == 360L,
                "刚占过槽: 被动封印 CD 剩余 = 18s × 20 = 360 tick, 实得 "
                        + seal.get("passiveCooldownRemainingTicks").getAsLong());
        helper.assertTrue(seal.get("mechanicCooldownRemainingTicks").getAsLong() == 0L,
                "封印 CD 按类别分别计: 封了被动不该把机制也一起锁上");

        JsonObject bounty = state.getAsJsonObject("bounty");
        helper.assertTrue(bounty.get("dailySlots").getAsInt() == 5 && bounty.get("weeklySlots").getAsInt() == 3,
                "L10 日 5 槽 / 周 3 槽");
        helper.assertTrue(bounty.get("worldBossUnlocked").getAsBoolean(), "L10 世界 BOSS 悬赏已开");
        helper.assertTrue(!bounty.get("available").getAsBoolean(),
                "L10 满级也一样: 悬赏系统对任何等级都尚未上线, available 不得随等级变 true");
        helper.assertTrue(state.get("damageBonusPercent").getAsInt() == 15, "L10 对精英伤害加成 +15%");
        helper.succeed();
    }

    // ============================================================
    // 3. job.agent.scan: 离线降级 (未绑定接缝)
    // ============================================================

    /**
     * Champions 未加载时扫描必须是"免费的空转": 报离线、不烧 CD、不写快照。
     * 若把离线也当成一次脉冲烧掉 30-60 秒, 就是让玩家为一个他无法影响的离线子系统付钱。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH_OFFLINE)
    public static void agentScanIsOfflineAndFreeWhenSeamUnbound(GameTestHelper helper) {
        AgentSealSeam.unbind();
        helper.assertTrue(!AgentSealSeam.isBound(), "前提校验: 本条要的就是接缝未绑定");
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        JsonObject first = handle(helper, SCAN_ACTION, player, new JsonObject());
        helper.assertTrue(!first.get("scanOnline").getAsBoolean(), "接缝未绑定必须报 scanOnline=false");
        helper.assertTrue(first.getAsJsonArray("targets").isEmpty(), "离线时候选表是空数组");
        helper.assertTrue(first.get("scanCooldownRemainingTicks").getAsLong() == 0L
                        && first.get("snapshotRemainingTicks").getAsLong() == 0L,
                "离线扫描不得烧 CD, 实得剩余 " + first.get("scanCooldownRemainingTicks").getAsLong());

        // 没烧 CD 就意味着紧接着再扫一次仍然放行 (而不是撞 CD 门)。
        JsonObject second = handle(helper, SCAN_ACTION, player, new JsonObject());
        helper.assertTrue(!second.get("scanOnline").getAsBoolean(), "离线状态下第二次扫描同样只是空转");
        // 离线也不许写快照: 封印的快照前置门必须仍然拒绝。
        JsonObject sealPayload = new JsonObject();
        sealPayload.addProperty("targetNetworkId", player.getId());
        sealPayload.addProperty("affixId", MECHANIC_AFFIX_ID);
        WebUiBusinessException rejected = rejection(helper, SEAL_ACTION, player, sealPayload);
        helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(rejected.errorCode())
                        && "targetNetworkId".equals(rejected.params().get("field")),
                "离线扫描没有写下任何快照, 封印必须撞快照门, 实得 " + rejected.errorCode()
                        + " / " + rejected.params());

        // 装回真实现: 接缝现在在启动期就绑好且 ServerStopping 不再解绑, 本用例自己 unbind 留下的未绑定态若不
        // 收拾, 会一直污染到进程重启 (同批其它用例 / 后续批次都会误判"接缝未绑定")。
        AgentIntegrationBootstrap.bindSeam();
        helper.succeed();
    }

    // ============================================================
    // 4. job.agent.scan: 半径门 (球) + CD 门
    // ============================================================

    /**
     * 半径门。同一只被打标的精英对 L1 (64 格) 不可见、对 L10 (跨区块) 可见 —— 把"目标是否在实体表里"这个变量
     * 消掉后, 唯一还能解释差异的就是半径门本身。放宽半径或改成 AABB 立方体, 本条即挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void agentScanRadiusGateHidesTargetsBeyondTheLevelRange(GameTestHelper helper) {
        ensureSeamStubBound();
        ServerPlayer rookie = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer veteran = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(veteran, 10);
        veteran.setNoGravity(true);
        veteran.teleportTo(rookie.getX(), rookie.getY(), rookie.getZ());

        LivingEntity near = spawnMarkedTarget(helper, rookie, 2.0D, 5);
        LivingEntity far = spawnMarkedTarget(helper, rookie, 70.0D, 5);

        JsonArray rookieTargets = handle(helper, SCAN_ACTION, rookie, new JsonObject()).getAsJsonArray("targets");
        helper.assertTrue(containsTarget(rookieTargets, near.getId()),
                "L1 半径 64: 2 格外的精英必须扫得到");
        helper.assertTrue(!containsTarget(rookieTargets, far.getId()),
                "L1 半径 64: 70 格外的精英绝不许下发 (半径门), 实得 " + rookieTargets);

        JsonArray veteranTargets = handle(helper, SCAN_ACTION, veteran, new JsonObject()).getAsJsonArray("targets");
        helper.assertTrue(containsTarget(veteranTargets, far.getId()),
                "同一只 70 格外的精英对 L10 (跨区块) 必须可见 —— 否则上一条断言测的是'实体不在表里'而不是半径门");

        near.discard();
        far.discard();
        helper.succeed();
    }

    /**
     * CD 门。一次脉冲之后同 tick 再扫必被拒, 且被拒的一次不得延长既有 CD (延长就等于按一次按钮罚一轮)。
     * 扫空同样烧 CD 由 {@link #agentScanNeverMarksActiveAgent} 一并锁住。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void agentScanBurnsPulseCooldownAndRejectsTheSecondPulse(GameTestHelper helper) {
        ensureSeamStubBound();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(player, 10);
        LivingEntity target = spawnMarkedTarget(helper, player, 2.0D, 6);

        JsonObject first = handle(helper, SCAN_ACTION, player, new JsonObject());
        helper.assertTrue(first.get("scanOnline").getAsBoolean(), "接缝已装桩, 扫描必须在线");
        helper.assertTrue(first.get("scanCooldownRemainingTicks").getAsLong() == 600L,
                "L10 一次脉冲烧掉整轮 600 tick, 实得 " + first.get("scanCooldownRemainingTicks").getAsLong());
        helper.assertTrue(first.get("snapshotRemainingTicks").getAsLong() == 600L,
                "快照有效期与脉冲 CD 同长 (同一个 pulseTick 派生), 实得 "
                        + first.get("snapshotRemainingTicks").getAsLong());

        WebUiBusinessException cooling = rejection(helper, SCAN_ACTION, player, new JsonObject());
        helper.assertTrue(WebUiErrorCodes.SKILL_ON_COOLDOWN.equals(cooling.errorCode()),
                "冷却中再扫应回 SKILL_ON_COOLDOWN, 实得 " + cooling.errorCode());
        helper.assertTrue("tactical_scan".equals(cooling.params().get("skill"))
                        && "600".equals(cooling.params().get("remainingTicks")),
                "冷却拒绝必须带 skill 与剩余 tick (发 tick 不发墙钟), 实得 " + cooling.params());

        // 被拒的一次不得重置或延长既有 CD: 剩余量仍是同一个 600。
        JsonObject state = handle(helper, STATE_ACTION, player, new JsonObject());
        helper.assertTrue(state.get("scanCooldownRemainingTicks").getAsLong() == 600L,
                "被 CD 门拒绝的一次不得延长冷却, 实得 " + state.get("scanCooldownRemainingTicks").getAsLong());

        target.discard();
        helper.succeed();
    }

    // ============================================================
    // 5. job.agent.scan: 分级解密 + 坐标门 + 硬上限
    // ============================================================

    /**
     * 未解密条目不许泄漏身份。快照 record 里 affixId/category 一直是真值 (S2C 面板靠 decrypted 自律不显示),
     * 但送进浏览器就等于在开发者工具里明码给出词条身份 —— 回执必须把这两格连同 displayKey 一起打成 null。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void agentScanWithholdsIdentityOfUndecryptedAffixes(GameTestHelper helper) {
        ensureSeamStubBound();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        LivingEntity target = spawnMarkedTarget(helper, player, 2.0D, 3);

        JsonArray targets = handle(helper, SCAN_ACTION, player, new JsonObject()).getAsJsonArray("targets");
        JsonObject row = findTargetRow(helper, targets, target.getId());
        helper.assertTrue(row.get("star").getAsInt() == 3, "星级取自快照头部, 实得 " + row.get("star").getAsInt());
        JsonArray entries = row.getAsJsonArray("entries");
        helper.assertTrue(entries.size() == STUB_RAW_AFFIXES.size(),
                "条目数与候选词条数一致 (加密条目照样占一行, 只是内容为空), 实得 " + entries.size());

        JsonObject decrypted = entries.get(0).getAsJsonObject();
        helper.assertTrue(decrypted.get("decrypted").getAsBoolean(), "L1 解密原始顺序第 1 条");
        helper.assertTrue(outcomeAffixId(AgentSealSeam.SealOutcome.OK).equals(decrypted.get("affixId").getAsString()),
                "已解密条目带真 affixId, 实得 " + decrypted.get("affixId"));
        helper.assertTrue("PASSIVE".equals(decrypted.get("category").getAsString()),
                "已解密条目带真类别, 实得 " + decrypted.get("category"));
        helper.assertTrue(!decrypted.get("displayKey").getAsString().isEmpty(), "已解密条目带真显示名 key");

        JsonObject encrypted = entries.get(1).getAsJsonObject();
        helper.assertTrue(!encrypted.get("decrypted").getAsBoolean(), "L1 不解密第 2 条");
        helper.assertTrue(encrypted.get("affixId").isJsonNull(),
                "未解密条目绝不许下发 affixId (发了就等于在浏览器里明码给出词条身份), 实得 "
                        + encrypted.get("affixId"));
        helper.assertTrue(encrypted.get("category").isJsonNull() && encrypted.get("displayKey").isJsonNull(),
                "未解密条目的类别与显示名同样必须是 JSON null");
        helper.assertTrue(!encrypted.get("sealable").getAsBoolean(), "未解密条目恒不可封");

        // 机制词条排在末位, L1 一律加密 (真名需 L5+)。
        JsonObject mechanic = entries.get(entries.size() - 1).getAsJsonObject();
        helper.assertTrue(!mechanic.get("decrypted").getAsBoolean() && mechanic.get("affixId").isJsonNull(),
                "机制类词条在 L1 必须仍是加密占位");

        target.discard();
        helper.succeed();
    }

    /**
     * 坐标门。精确坐标是第四章 L8 那一格 (Glowing 高亮, 穿墙可见) 才解密的东西, L7 拿到坐标就等于提前七级
     * 拿到穿墙透视。未解锁必须发 JSON null 而不是 0 —— 0 是一个真实存在的坐标。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void agentScanWithholdsExactPositionBelowGlowingLevel(GameTestHelper helper) {
        ensureSeamStubBound();
        ServerPlayer seven = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(seven, 7);
        ServerPlayer eight = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(eight, 8);
        eight.setNoGravity(true);
        eight.teleportTo(seven.getX(), seven.getY(), seven.getZ());
        LivingEntity target = spawnMarkedTarget(helper, seven, 3.0D, 7);

        JsonObject atSeven = findTargetRow(helper,
                handle(helper, SCAN_ACTION, seven, new JsonObject()).getAsJsonArray("targets"), target.getId());
        helper.assertTrue(atSeven.get("pos").isJsonNull(),
                "L7 不得下发精确坐标 (Glowing 高亮 L8 才解锁), 实得 " + atSeven.get("pos"));

        JsonObject atEight = findTargetRow(helper,
                handle(helper, SCAN_ACTION, eight, new JsonObject()).getAsJsonArray("targets"), target.getId());
        JsonObject pos = atEight.getAsJsonObject("pos");
        helper.assertTrue(pos.get("x").getAsInt() == target.blockPosition().getX()
                        && pos.get("y").getAsInt() == target.blockPosition().getY()
                        && pos.get("z").getAsInt() == target.blockPosition().getZ(),
                "L8 下发的坐标必须是目标脉冲当刻的真实方块坐标, 实得 " + pos);

        target.discard();
        helper.succeed();
    }

    /**
     * 回执硬上限。列表类 action 必须自带上限, 不许指望派发器 32767 字符的收口兜底 (兜底是保命不是设计)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void agentScanCapsTargetCountAndFlagsTruncation(GameTestHelper helper) {
        ensureSeamStubBound();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(player, 10);

        int spawnCount = AgentWebUiActions.MAX_SCAN_TARGETS + 3;
        List<LivingEntity> spawned = new ArrayList<>();
        for (int i = 0; i < spawnCount; i++) {
            spawned.add(spawnMarkedTarget(helper, player, 1.0D + i, 4));
        }

        JsonObject result = handle(helper, SCAN_ACTION, player, new JsonObject());
        JsonArray targets = result.getAsJsonArray("targets");
        helper.assertTrue(targets.size() == AgentWebUiActions.MAX_SCAN_TARGETS,
                "一次脉冲最多下发 " + AgentWebUiActions.MAX_SCAN_TARGETS + " 个目标, 实得 " + targets.size());
        helper.assertTrue(result.get("truncated").getAsBoolean(),
                "达上限且球内仍有候选时必须报 truncated=true, 否则前端会把截断后的表当成全部");
        // 截断按距离由近及远: 最近的那几只必须在, 最远的那几只必须被截掉。
        helper.assertTrue(containsTarget(targets, spawned.get(0).getId()),
                "最近的目标必须在下发表里");
        helper.assertTrue(!containsTarget(targets, spawned.get(spawnCount - 1).getId()),
                "最远的目标应被截断掉 (截断按距离升序), 实得 " + targets);

        for (LivingEntity entity : spawned) {
            entity.discard();
        }
        helper.succeed();
    }

    /**
     * 扫描<b>永不</b>置位入职标志 —— 无论扫空还是扫到。
     *
     * 该标志是加强奖励与对精英伤害放大的唯一资格门, 且一经置位永久保留; 它原本的唯一置位点是"封印成功"
     * (经 SealPlan 要求被动 L3+)。而扫描面板对全员开放、职业等级又对所有人默认 1 级, 一旦在扫描里置位,
     * 入职门槛就塌成"站在精英旁边点一次按钮"。删掉 AgentWebUiActions.SCAN 里那道"不置位"的克制, 本条即挂。
     *
     * 同时锁住"扫空一样烧 CD"(让扫空免费重试等于把 CD 变成'扫到为止')。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void agentScanNeverMarksActiveAgent(GameTestHelper helper) {
        ensureSeamStubBound();
        AgentBountySavedData data = AgentBountySavedData.get(helper.getLevel().getServer().overworld());

        // 刻意不断言这一枪扫空。L10 半径 448 格远超单个 GameTest 结构区, 而同批用例是并行铺在一张网格上的,
        // "附近没有别的用例的目标"根本不由本用例控制 (实测已撞到过邻居用例的 8 星目标)。
        // 本条真正要锁的两件事 —— 入职标志不置位、脉冲照样烧 CD —— 对扫空与扫到都成立, 无需依赖那个前提。
        ServerPlayer blank = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(blank, 10);
        JsonObject blankResult = handle(helper, SCAN_ACTION, blank, new JsonObject());
        helper.assertTrue(!data.isActiveAgent(blank.getUUID()),
                "没主动做过特勤活计, 入职标志不得置位");
        helper.assertTrue(blankResult.get("scanCooldownRemainingTicks").getAsLong() == 600L,
                "一次脉冲必烧整轮 CD, 扫不扫得到都一样 (让扫空免费重试就等于把 CD 变成'扫到为止')");

        ServerPlayer worker = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(worker, 10);
        LivingEntity target = spawnMarkedTarget(helper, worker, 2.0D, 8);
        helper.assertTrue(!data.isActiveAgent(worker.getUUID()), "前提校验: 干活之前必须还没有入职标志");
        JsonObject hit = handle(helper, SCAN_ACTION, worker, new JsonObject());
        helper.assertTrue(!hit.getAsJsonArray("targets").isEmpty(),
                "前提校验: 这一枪必须真扫到精英, 否则测不到'扫到了也不入职'这一点");
        helper.assertTrue(!data.isActiveAgent(worker.getUUID()),
                "扫到精英也不算入职: 该标志是加强奖励 (每星 600 信用点) 与对精英伤害放大的唯一资格门, 且一旦"
                        + "置位永久保留。扫描对全员开放且职业等级默认 1 级, 在此置位等于把门槛降成'站在精英"
                        + "旁边点一次按钮', 特勤专属福利就漏给了全服每个打精英的人。入职只认封印成功。");

        target.discard();
        helper.succeed();
    }

    // ============================================================
    // 6. job.agent.seal: 两道前置门 + 九态透传
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void agentSealRequiresAValidSnapshotFirst(GameTestHelper helper) {
        ensureSeamStubBound();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(player, 10);
        LivingEntity target = spawnMarkedTarget(helper, player, 2.0D, 5);

        // 一、没扫过就封: 撞快照门。
        JsonObject payload = sealPayload(target.getId(), outcomeAffixId(AgentSealSeam.SealOutcome.OK));
        WebUiBusinessException noSnapshot = rejection(helper, SEAL_ACTION, player, payload);
        helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(noSnapshot.errorCode())
                        && "targetNetworkId".equals(noSnapshot.params().get("field")),
                "没有有效快照时封印必须被拒 (探测与封印合一), 实得 " + noSnapshot.errorCode()
                        + " / " + noSnapshot.params());

        // 二、扫过之后, 快照里没有的目标仍然封不了。
        handle(helper, SCAN_ACTION, player, new JsonObject());
        WebUiBusinessException unknownTarget = rejection(helper, SEAL_ACTION, player,
                sealPayload(player.getId(), outcomeAffixId(AgentSealSeam.SealOutcome.OK)));
        helper.assertTrue("targetNetworkId".equals(unknownTarget.params().get("field")),
                "快照里没有的目标必须被拒, 实得 " + unknownTarget.params());

        // 三、快照里没有的词条同样封不了 (NOT_BOUND 那条从不进候选表)。
        WebUiBusinessException unknownAffix = rejection(helper, SEAL_ACTION, player,
                sealPayload(target.getId(), outcomeAffixId(AgentSealSeam.SealOutcome.NOT_BOUND)));
        helper.assertTrue("affixId".equals(unknownAffix.params().get("field")),
                "快照里没有的词条必须被拒, 实得 " + unknownAffix.params());

        // 四、空 affixId 是入参非法, 不是业务分支。
        WebUiBusinessException blank = rejection(helper, SEAL_ACTION, player, sealPayload(target.getId(), "   "));
        helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(blank.errorCode())
                        && "affixId".equals(blank.params().get("field")),
                "空白 affixId 必须被入参校验拦下, 实得 " + blank.errorCode() + " / " + blank.params());

        target.discard();
        helper.succeed();
    }

    /**
     * 解密门。集成层的 requestSeal 只查词条可封性、<b>不查解密态</b> —— 少了本层这道门, 客户端直接送一个加密
     * 词条的注册名就能封, 六章"未解密的词条点不了"整条形同虚设。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void agentSealRefusesAffixesThatAreStillEncrypted(GameTestHelper helper) {
        ensureSeamStubBound();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        // L1: 候选表里只有第 0 条解密, 其余全加密 (机制类更是 L5+ 才解密)。
        LivingEntity target = spawnMarkedTarget(helper, player, 2.0D, 1);
        handle(helper, SCAN_ACTION, player, new JsonObject());

        String encryptedAffix = outcomeAffixId(AgentSealSeam.SealOutcome.NO_TARGET); // 原始顺序第 2 条
        WebUiBusinessException rejected = rejection(helper, SEAL_ACTION, player,
                sealPayload(target.getId(), encryptedAffix));
        helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(rejected.errorCode())
                        && "affixId".equals(rejected.params().get("field")),
                "加密词条的注册名即便被客户端猜到也封不了, 实得 " + rejected.errorCode()
                        + " / " + rejected.params());

        WebUiBusinessException mechanic = rejection(helper, SEAL_ACTION, player,
                sealPayload(target.getId(), MECHANIC_AFFIX_ID));
        helper.assertTrue("affixId".equals(mechanic.params().get("field")),
                "机制类词条在 L1 仍是加密态, 同样封不了");

        // 同一份快照里已解密的那条则能一路走到接缝 (证明上面两条被拒的原因是解密门, 不是别的什么都被拒)。
        JsonObject ok = handle(helper, SEAL_ACTION, player,
                sealPayload(target.getId(), outcomeAffixId(AgentSealSeam.SealOutcome.OK)));
        helper.assertTrue(ok.get("ok").getAsBoolean() && "OK".equals(ok.get("outcomeCode").getAsString()),
                "已解密的那条必须能走到接缝并拿回它的裁决, 实得 " + ok);

        target.discard();
        helper.succeed();
    }

    /**
     * 词条身份预言机。"目标身上有这条但没解密"与"目标身上根本没这条"两种拒绝<b>必须逐字不可区分</b>。
     *
     * 拒绝文案经 businessErrorJson 的 error 字段原样进浏览器。一旦两者可分, 客户端就能拿 Champions 那二十来个
     * 公开注册名逐个试探 (无速率限制, 换个 requestId 即可), 约二十次请求就在 L1 反推出整张词条表 ——
     * entryJson 把未解密行的 affixId/displayKey/category 打成 null 的脱敏被完全绕开, 分级解密白做。
     *
     * 把 SEAL 里那句合并的拒绝拆回两句不同文案, 本条立刻挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void agentSealRejectionsRevealNothingAboutAffixIdentity(GameTestHelper helper) {
        ensureSeamStubBound();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        // L1: 候选表里只有第 0 条解密, 其余全是加密占位。
        LivingEntity target = spawnMarkedTarget(helper, player, 2.0D, 1);
        handle(helper, SCAN_ACTION, player, new JsonObject());

        // A: 目标身上真有这条词条, 但当前等级还没解密它。
        WebUiBusinessException encrypted = rejection(helper, SEAL_ACTION, player,
                sealPayload(target.getId(), outcomeAffixId(AgentSealSeam.SealOutcome.NO_TARGET)));
        // B: 目标身上压根没有这条词条 (瞎猜一个注册名)。
        WebUiBusinessException absent = rejection(helper, SEAL_ACTION, player,
                sealPayload(target.getId(), "DEFINITELY_NOT_AN_AFFIX"));

        helper.assertTrue(encrypted.errorCode().equals(absent.errorCode()),
                "两种拒绝的错误码必须相同, 实得 " + encrypted.errorCode() + " vs " + absent.errorCode());
        helper.assertTrue(encrypted.getMessage().equals(absent.getMessage()),
                "两种拒绝的文案必须逐字相同, 否则文案本身就是一台词条身份预言机。实得 \""
                        + encrypted.getMessage() + "\" vs \"" + absent.getMessage() + "\"");
        helper.assertTrue(encrypted.params().get("field").equals(absent.params().get("field")),
                "两种拒绝指向的字段也必须相同, 实得 " + encrypted.params() + " vs " + absent.params());

        // 前提校验: 这两条确实走的是"不可封印"这道门, 而不是碰巧都被别的什么拦下。
        helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(encrypted.errorCode())
                        && "affixId".equals(encrypted.params().get("field")),
                "前提校验: 两条都应是 affixId 的入参拒绝, 实得 " + encrypted.errorCode()
                        + " / " + encrypted.params());

        target.discard();
        helper.succeed();
    }

    /**
     * 九态逐字透传。前端的九分支 switch 照的就是这份枚举名, 本层若把任何一态改写/合并/吞掉, 玩家看到的失败
     * 原因就会与服务端真实拒绝的原因对不上。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void agentSealPassesEverySealOutcomeThroughVerbatim(GameTestHelper helper) {
        ensureSeamStubBound();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(player, 10);
        LivingEntity target = spawnMarkedTarget(helper, player, 2.0D, 10);
        handle(helper, SCAN_ACTION, player, new JsonObject());

        int checked = 0;
        for (AgentSealSeam.SealOutcome outcome : AgentSealSeam.SealOutcome.values()) {
            if (outcome == AgentSealSeam.SealOutcome.NOT_BOUND) {
                // 接缝契约: 真实现绝不返 NOT_BOUND, 它只由未绑定路径产生, 而未绑定时根本写不出快照。
                continue;
            }
            JsonObject result = handle(helper, SEAL_ACTION, player,
                    sealPayload(target.getId(), outcomeAffixId(outcome)));
            helper.assertTrue(outcome.name().equals(result.get("outcomeCode").getAsString()),
                    "outcomeCode 必须是 Java 枚举名原文 " + outcome.name() + ", 实得 " + result.get("outcomeCode"));
            helper.assertTrue(result.get("ok").getAsBoolean() == (outcome == AgentSealSeam.SealOutcome.OK),
                    "ok 只有 OK 一态为真, " + outcome.name() + " 实得 " + result.get("ok"));
            helper.assertTrue(result.get("targetNetworkId").getAsInt() == target.getId()
                            && outcomeAffixId(outcome).equals(result.get("affixId").getAsString()),
                    "回执必须回显本次申请的目标与词条, 实得 " + result);
            helper.assertTrue("PASSIVE".equals(result.get("category").getAsString()),
                    "桩的九态词条都是被动类, 实得 " + result.get("category"));
            helper.assertTrue(result.get("windowSeconds").getAsInt() == 12
                            && result.get("cooldownSeconds").getAsInt() == 18,
                    "窗口/CD 取的是 SealRegistry 占槽时用的同一张表 (L10 被动 12s/18s), 实得 "
                            + result.get("windowSeconds") + "s/" + result.get("cooldownSeconds") + "s");
            checked++;
        }
        helper.assertTrue(checked == AgentSealSeam.SealOutcome.values().length - 1,
                "九态里除 NOT_BOUND 外的八态都必须被逐个验过, 实得 " + checked);

        target.discard();
        helper.succeed();
    }

    /**
     * 快照失效。过期之后 targetNetworkId 立刻作废 (封印被拒), 同一时刻脉冲 CD 也一并释放 (可以重扫) ——
     * 二者必须同时发生, 否则要么留下"拿旧情报反复封印"的口子, 要么留下"既不能重扫又不能封印"的死区。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void agentSnapshotExpiryVoidsSealAndReleasesTheCooldownTogether(GameTestHelper helper) {
        ensureSeamStubBound();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(player, 10);
        LivingEntity target = spawnMarkedTarget(helper, player, 2.0D, 9);

        handle(helper, SCAN_ACTION, player, new JsonObject());
        JsonObject fresh = handle(helper, SEAL_ACTION, player,
                sealPayload(target.getId(), outcomeAffixId(AgentSealSeam.SealOutcome.OK)));
        helper.assertTrue(fresh.get("ok").getAsBoolean(), "前提校验: 快照仍有效时封印必须走得通");

        // 真等 600 tick 在测试里不可行, 把脉冲时间戳整体往回拨一整轮 CD 等价于"时间到了"。
        helper.assertTrue(AgentWebUiActions.rewindPulseForTest(player.getUUID(), 600L),
                "前提校验: 必须真的有一条脉冲记录可拨");

        JsonObject state = handle(helper, STATE_ACTION, player, new JsonObject());
        helper.assertTrue(state.get("snapshotRemainingTicks").getAsLong() == 0L
                        && state.get("scanCooldownRemainingTicks").getAsLong() == 0L,
                "到期后快照与 CD 必须同时归零, 实得 " + state.get("snapshotRemainingTicks").getAsLong()
                        + "/" + state.get("scanCooldownRemainingTicks").getAsLong());
        helper.assertTrue(state.getAsJsonArray("targets").isEmpty(),
                "到期的快照不得继续把候选表挂在面板上");

        WebUiBusinessException expired = rejection(helper, SEAL_ACTION, player,
                sealPayload(target.getId(), outcomeAffixId(AgentSealSeam.SealOutcome.OK)));
        helper.assertTrue("targetNetworkId".equals(expired.params().get("field")),
                "过期快照里的 targetNetworkId 必须作废, 实得 " + expired.params());

        JsonObject rescan = handle(helper, SCAN_ACTION, player, new JsonObject());
        helper.assertTrue(rescan.get("scanCooldownRemainingTicks").getAsLong() == 600L,
                "同一时刻脉冲 CD 也已释放, 重扫必须放行并起新一轮 CD");

        target.discard();
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 注册守卫。<b>刻意不在测试侧补注册</b>: 补上了, "AgentSystem.register 忘了调 AgentWebUiActions.registerAll"
     * 这一类装配缺陷就永远测不出来 —— 把生产侧那一行删掉, 本文件全绿, 而真服上前端调 job.agent.* action 只会拿到
     * 派发器的 "unknown Web UI action" 失败回执, 整个面板全黑。
     *
     * 没注册就是 AgentSystem.register 的接线掉了, 直接炸。
     */
    private static void ensureAgentActionsRegistered() {
        if (WebUiServerDispatcher.resolve(STATE_ACTION) == null) {
            throw new IllegalStateException(
                    "job.agent.* action 未注册: AgentSystem.register 没有调用 AgentWebUiActions.registerAll");
        }
    }

    /**
     * 幂等装桩 (见类注释: 装了不解绑, 否则会打断同批其它用例)。F024 之后 {@code AgentSystem.register} 在启动期
     * 就无条件把接缝绑到真实现 (不再受 ModList 守卫), 于是 {@code isBound()} 在本组用例跑之前恒为 true ——
     * 原先"未绑定才绑"的守卫会把真实现误判成"已经是桩", 整批用例改成走真实现而不是桩表 (桩表覆盖 9 态 SealOutcome,
     * 真实现只按目标真实装配的词条走, 两者语义完全不同)。bind 只是静态字段赋值, 重复绑同一份桩本身幂等无副作用,
     * 故直接无条件覆盖绑定, 不再查 isBound。
     */
    private static void ensureSeamStubBound() {
        AgentSealSeam.bind(STUB_SEAL, STUB_SCAN, STUB_CLEANUP);
    }

    private static List<AgentScanSnapshotBuilder.RawAffix> buildStubAffixes() {
        List<AgentScanSnapshotBuilder.RawAffix> raws = new ArrayList<>();
        for (AgentSealSeam.SealOutcome outcome : AgentSealSeam.SealOutcome.values()) {
            if (outcome == AgentSealSeam.SealOutcome.NOT_BOUND) {
                continue;
            }
            String id = outcomeAffixId(outcome);
            raws.add(new AgentScanSnapshotBuilder.RawAffix(id, "affix." + id.replace(':', '.'),
                    SealCategory.PASSIVE, false));
        }
        raws.add(new AgentScanSnapshotBuilder.RawAffix(MECHANIC_AFFIX_ID, "affix.miningdim.webui_mechanic",
                SealCategory.MECHANIC, false));
        return List.copyOf(raws);
    }

    private static String outcomeAffixId(AgentSealSeam.SealOutcome outcome) {
        return "miningdim:webui_" + outcome.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 桩识别目标的判据 (F082 剪枝后与 SCAN 的 predicate 同一份真理): "本工程盖章精英" = 挂了
     * {@link MiningChampionData} capability 且 star≥1 的实体, 星级直接读 capability, 不再解析自定义名。
     */
    private static int markedStar(LivingEntity target) {
        return MiningChampions.get(target).map(MiningChampionData::star).orElse(0);
    }

    /**
     * 在玩家身边 offsetX 格处放一只<b>真盖章</b>的精英。
     *
     * 必须相对<b>玩家</b>放而不是相对测试结构: mock 玩家由 placeNewPlayer 落在世界出生点, 不在结构内, 按结构
     * 坐标放目标等于放在扫描球外, 用例就只跑得到"扫空"分支 (与 MinerWebUiGameTests 同一个坑)。
     *
     * 真盖章而非自定义名 (F082): {@code AgentWebUiActions.SCAN} 的 predicate 现在直接调
     * {@code MiningChampions.isChampion} 做入表预筛, 打自定义名的假目标从此连候选表都进不去。词条内容不重要
     * (九态透传仍完全由 {@link #STUB_SCAN}/{@link #STUB_SEAL} 桩接管, 这里的词条只用来满足"合法装配"), 随便给
     * 两条 minStar=1 的被动词条即可; 品质固定 COMMON 且不给体型词条, 换算不出的有效血不会离谱到需要关心的地步。
     */
    private static LivingEntity spawnMarkedTarget(GameTestHelper helper, ServerPlayer near, double offsetX, int star) {
        ServerLevel level = helper.getLevel();
        Zombie zombie = EntityType.ZOMBIE.create(level);
        if (zombie == null) {
            helper.fail("无法创建测试用僵尸实体");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        zombie.moveTo(near.getX() + offsetX, near.getY(), near.getZ(), 0.0F, 0.0F);
        zombie.setNoAi(true);          // 别让它在断言之间走出扫描球。
        zombie.setNoGravity(true);
        zombie.setInvulnerable(true);
        level.addFreshEntity(zombie);
        Map<AffixDef, AffixQuality> stubAffixes = new EnumMap<>(AffixDef.class);
        stubAffixes.put(AffixDef.BURNING, AffixQuality.COMMON);
        stubAffixes.put(AffixDef.REGEN_TISSUE, AffixQuality.COMMON);
        ChampionPromoter.applyChampion(zombie, star, stubAffixes);
        return zombie;
    }

    private static JsonObject sealPayload(int targetNetworkId, String affixId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("targetNetworkId", targetNetworkId);
        payload.addProperty("affixId", affixId);
        return payload;
    }

    private static boolean containsTarget(JsonArray targets, int networkId) {
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).getAsJsonObject().get("targetNetworkId").getAsInt() == networkId) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject findTargetRow(GameTestHelper helper, JsonArray targets, int networkId) {
        for (int i = 0; i < targets.size(); i++) {
            JsonObject row = targets.get(i).getAsJsonObject();
            if (row.get("targetNetworkId").getAsInt() == networkId) {
                return row;
            }
        }
        helper.fail("扫描回执里找不到目标 " + networkId + ", 实得 " + targets);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static JsonObject handle(GameTestHelper helper, String action, ServerPlayer sender, JsonObject payload) {
        return JsonParser.parseString(handler(helper, action).handle(sender, payload)).getAsJsonObject();
    }

    /** 调 action 并要求它抛业务拒绝; 没抛就地判失败。 */
    private static WebUiBusinessException rejection(GameTestHelper helper, String action, ServerPlayer sender,
                                                    JsonObject payload) {
        try {
            handler(helper, action).handle(sender, payload);
        } catch (WebUiBusinessException rejected) {
            return rejected;
        }
        helper.fail("该请求本应被业务拒绝, 实际却成功返回了: " + action);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static WebUiAction handler(GameTestHelper helper, String action) {
        ensureAgentActionsRegistered();
        WebUiAction handler = WebUiServerDispatcher.resolve(action);
        if (handler == null) {
            helper.fail("action " + action + " 未注册进派发器");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return handler;
    }

    private static void setAgentLevel(ServerPlayer player, int level) {
        MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"))
                .jobProgress(JobId.AGENT).setLevel(level);
    }
}
