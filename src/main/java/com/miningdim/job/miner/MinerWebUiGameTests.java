package com.miningdim.job.miner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.JobId;
import com.miningdim.ore.OreType;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * W3 职业一的 job.miner.state / job.miner.scan GameTest。
 *
 * 防 X 光 (K3) 是本文件的主线, 五条限制各有独立断言, 放宽任何一条必挂:
 *  1. 等级门: 未达 L3 抛 SKILL_LOCKED 且<b>不烧 CD</b> (烧了就等于给未解锁玩家立了个免费重试计时器);
 *  2. CD 门: 冷却中抛 SKILL_ON_COOLDOWN 且<b>不延长</b>既有 CD, 成功一次后立刻再探必被拒 (探空免费重试即失守);
 *  3. 半径门: 球外的矿不下发 (在筛选实现 {@link OreScanService#scanWorldDetailed} 上直接断言几何);
 *  4. 单矿种一次: 球内同时有铁与钻也只回优先序第一个矿种的坐标, 且 64 条硬顶真的截断;
 *  5. 脉冲熄灭: pulseTicks 恒发 {@link MinerConstants#SCAN_PULSE_TICKS}, 由前端据此自行熄灭。
 *
 * 另锁两个已知陷阱:
 *  - 无命中时 oreItemId / oreDescriptionId 必须是 <b>JSON null</b> 而不是整键缺席 (默认 Gson 会丢掉 null 成员,
 *    前端拿到 undefined 即契约破裂);
 *  - 从未探过时 scanCooldownRemainingTicks 必须是 0 —— cooldownReadyAt 未用过返回 Long.MIN_VALUE, 直接相减
 *    会下溢成巨大正数, 面板会显示"冷却中数亿秒"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MinerWebUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui_w3";

    private static final String STATE_ACTION = "job.miner.state";
    private static final String SCAN_ACTION = "job.miner.scan";

    /** 双精度断言容差: 数值行是插值出来的, 逐位相等会被最后一个 ulp 卡住。 */
    private static final double EPS = 1.0E-9D;

    /** 开关位的契约顺序与 id (前端按下标画三个标签, 顺序错了标签就串)。 */
    private static final String[] TOGGLE_IDS = {"chain", "auto_collect", "auto_smelt"};

    /** 被动数值行的契约顺序、键与量纲 (写死在测试里: 这是前端做文案表照着抄的那一份)。 */
    private static final String[][] PASSIVE_KEY_UNIT = {
            {"dig_speed", "multiplier"},
            {"durability_save", "percent"},
            {"fortune_extra", "flat"},
            {"danger_time_factor", "multiplier"},
            {"trap_damage_reduction", "percent"},
            {"chain_refill_full", "ticks"}};

    // ============================================================
    // 1. job.miner.state
    // ============================================================

    /**
     * 新号 (L1, 零记录): 各栏必须是"真实的 0/false", 不是缺省填充, 也不许出现负数或下溢的巨大正数。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void minerStateOnFreshAccountReportsRealZerosNotPlaceholders(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonObject state = handle(helper, STATE_ACTION, player);

        helper.assertTrue(state.get("level").getAsInt() == 1, "新号矿工 1 级");
        helper.assertTrue(state.get("charge").getAsInt() == 0 && state.get("chargeMax").getAsInt() == 0,
                "L1 连锁未解锁 (L2 起), 充能池容量与当前量都是 0");
        helper.assertTrue(!state.get("miningFatigueImmune").getAsBoolean(),
                "抗疲劳是 L4 里程碑, L1 必须为 false");
        helper.assertTrue(state.get("scanUnlockLevel").getAsInt() == MinerConstants.ORE_SCAN_UNLOCK_LEVEL
                        && state.get("scanUnlockLevel").getAsInt() == 3,
                "探矿解锁级恒发 3");
        helper.assertTrue(!state.get("scanUnlocked").getAsBoolean(), "L1 探矿未解锁");
        helper.assertTrue(state.get("scanRadius").getAsInt() == 0,
                "未解锁时半径是真值 0 (不是缺省填充), 实得 " + state.get("scanRadius").getAsInt());
        // 从未用过该技能 -> cooldownReadyAt 是 Long.MIN_VALUE, 少判一次 cooldownReady 就会下溢成巨大正数。
        helper.assertTrue(state.get("scanCooldownRemainingTicks").getAsLong() == 0L,
                "从未探过时剩余冷却必须是 0, 实得 " + state.get("scanCooldownRemainingTicks").getAsLong());

        JsonArray toggles = state.getAsJsonArray("toggles");
        helper.assertTrue(toggles.size() == 3, "toggles 恒 3 条, 实得 " + toggles.size());
        for (int i = 0; i < 3; i++) {
            JsonObject row = toggles.get(i).getAsJsonObject();
            helper.assertTrue(TOGGLE_IDS[i].equals(row.get("skillId").getAsString()),
                    "第 " + i + " 个开关必须是 " + TOGGLE_IDS[i] + ", 实得 " + row.get("skillId").getAsString());
            helper.assertTrue(!row.get("unlocked").getAsBoolean() && !row.get("enabled").getAsBoolean(),
                    "L1 三个开关都未解锁且都是关的 (" + TOGGLE_IDS[i] + ")");
        }

        JsonArray passives = state.getAsJsonArray("passives");
        assertPassiveShape(helper, passives);
        // L1 各被动的定稿值 (spec 表): 挖速 +15%, 省耐久 5%, 时运/耐压/矿脉抗性未解锁, 连锁回满取解锁档 6000。
        assertPassive(helper, passives, 0, 1.15D);
        assertPassive(helper, passives, 1, 0.05D);
        assertPassive(helper, passives, 2, 0.0D);
        assertPassive(helper, passives, 3, 1.0D);
        assertPassive(helper, passives, 4, 0.0D);
        assertPassive(helper, passives, 5, 6000.0D);
        helper.succeed();
    }

    /**
     * 三个开关各有各的解锁级 (连锁 L2 / 自动入包 L2 / 自动熔炼 L6): L5 必须是 "两开一锁"。
     * 把三个开关合成一个 chainEnabled 或者共用一个解锁判据, 本条即挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void minerStateTogglesFollowPerSkillUnlockLevels(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setMinerLevel(player, 5);
        MinerChargeState charge = MinerSystem.get().stateOf(player);
        charge.setToggle(MinerSkill.CHAIN, true);
        charge.setCharge(7.0D, MinerSkills.chainChargePool(5));

        JsonObject state = handle(helper, STATE_ACTION, player);
        JsonArray toggles = state.getAsJsonArray("toggles");
        helper.assertTrue(toggles.get(0).getAsJsonObject().get("unlocked").getAsBoolean(),
                "L5 连锁已解锁 (L2 起)");
        helper.assertTrue(toggles.get(1).getAsJsonObject().get("unlocked").getAsBoolean(),
                "L5 自动入包已解锁 (L2 起)");
        helper.assertTrue(!toggles.get(2).getAsJsonObject().get("unlocked").getAsBoolean(),
                "L5 自动熔炼仍未解锁 (L6 起)");
        helper.assertTrue(toggles.get(0).getAsJsonObject().get("enabled").getAsBoolean()
                        && !toggles.get(1).getAsJsonObject().get("enabled").getAsBoolean(),
                "enabled 逐个读运行态开关位: 只翻了连锁, 自动入包必须仍是关的");

        helper.assertTrue(state.get("chargeMax").getAsInt() == 28,
                "L5 充能池容量 = 28 (16@L2 -> 48@L10 线性), 实得 " + state.get("chargeMax").getAsInt());
        helper.assertTrue(state.get("charge").getAsInt() == 7,
                "charge 读当前池量 (本 action 只读不回充), 实得 " + state.get("charge").getAsInt());
        helper.assertTrue(state.get("scanUnlocked").getAsBoolean() && state.get("scanRadius").getAsInt() == 9,
                "L5 探矿半径 = 9 (6@L3 -> 16@L10 线性), 实得 " + state.get("scanRadius").getAsInt());
        helper.assertTrue(state.get("miningFatigueImmune").getAsBoolean(), "L5 已过 L4 抗疲劳里程碑");
        helper.succeed();
    }

    /**
     * 满级: 六条被动全部落在封顶/封底值上。耐压 0.60 与矿脉抗性 0.35 是两条红线 (防实质免疫压力系统 /
     * 防变战斗减伤天赋), 面板发的就是玩家能看到的那个数, 数值漂了本条必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void minerStateAtMaxLevelReportsCappedPassivesAndLiveCooldown(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setMinerLevel(player, 10);
        long now = player.serverLevel().getGameTime();
        MinerSystem.get().stateOf(player).startCooldown(MinerSkill.ORE_SCAN, now, 500);

        JsonObject state = handle(helper, STATE_ACTION, player);
        helper.assertTrue(state.get("level").getAsInt() == 10, "满级矿工 10 级");
        helper.assertTrue(state.get("chargeMax").getAsInt() == 48, "满级充能池 48");
        helper.assertTrue(state.get("scanRadius").getAsInt() == 16, "满级探矿半径 16");
        helper.assertTrue(state.get("scanCooldownRemainingTicks").getAsLong() == 500L,
                "剩余冷却 = readyAt - now = 500, 实得 " + state.get("scanCooldownRemainingTicks").getAsLong());

        JsonArray passives = state.getAsJsonArray("passives");
        assertPassiveShape(helper, passives);
        assertPassive(helper, passives, 0, 2.10D);
        assertPassive(helper, passives, 1, 0.30D);
        assertPassive(helper, passives, 2, 0.50D);
        assertPassive(helper, passives, 3, MinerConstants.DANGER_TIME_FACTOR_FLOOR);
        assertPassive(helper, passives, 4, MinerConstants.VEIN_RESIST_REDUCTION_CAP);
        assertPassive(helper, passives, 5, 4200.0D);
        helper.succeed();
    }

    // ============================================================
    // 2. job.miner.scan: 等级门 / CD 门 (K3 第 1、2 条)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void minerScanRejectsBelowUnlockLevelAndDoesNotBurnCooldown(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setMinerLevel(player, 2);
        MinerChargeState charge = MinerSystem.get().stateOf(player);

        WebUiBusinessException locked = rejection(helper, SCAN_ACTION, player);
        helper.assertTrue(WebUiErrorCodes.SKILL_LOCKED.equals(locked.errorCode()),
                "L2 探矿应回 SKILL_LOCKED, 实得 " + locked.errorCode());
        helper.assertTrue("ore_scan".equals(locked.params().get("skill"))
                        && "3".equals(locked.params().get("requiredLevel"))
                        && "2".equals(locked.params().get("currentLevel")),
                "等级门拒绝必须带 skill/requiredLevel/currentLevel 三个占位符实参, 实得 " + locked.params());
        // 被拒的一次不许烧 CD: 烧了就等于给未解锁玩家立了个计时器, 且升级当场还得等一轮。
        helper.assertTrue(charge.cooldownReadyAt(MinerSkill.ORE_SCAN) == Long.MIN_VALUE,
                "等级门拒绝后不得起冷却, 实得 readyAt=" + charge.cooldownReadyAt(MinerSkill.ORE_SCAN));
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void minerScanRejectsWhileCoolingDownAndDoesNotExtendTheCooldown(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setMinerLevel(player, 10);
        MinerChargeState charge = MinerSystem.get().stateOf(player);
        long now = player.serverLevel().getGameTime();
        charge.startCooldown(MinerSkill.ORE_SCAN, now, 500);

        WebUiBusinessException cooling = rejection(helper, SCAN_ACTION, player);
        helper.assertTrue(WebUiErrorCodes.SKILL_ON_COOLDOWN.equals(cooling.errorCode()),
                "冷却中探矿应回 SKILL_ON_COOLDOWN, 实得 " + cooling.errorCode());
        helper.assertTrue("ore_scan".equals(cooling.params().get("skill"))
                        && "500".equals(cooling.params().get("remainingTicks")),
                "冷却拒绝必须带 skill 与剩余 tick (发 tick 不发墙钟), 实得 " + cooling.params());
        helper.assertTrue(charge.cooldownReadyAt(MinerSkill.ORE_SCAN) == now + 500L,
                "被拒的一次不得重置或延长既有冷却, 实得 readyAt=" + charge.cooldownReadyAt(MinerSkill.ORE_SCAN));
        helper.succeed();
    }

    // ============================================================
    // 3. job.miner.scan 主路径: 逐字复用服务端裁决链 + 烧 CD + 脉冲
    // ============================================================

    /**
     * 成功路径必须与键位路径 (MinerActions.tryOreScan) 同判据同副作用: 回执的命中集合逐字等于
     * {@link OreScanService#scanDetailed} 的结果 (面板自己重写一份筛选即在此分叉), CD 真被烧掉,
     * 且紧接着的第二次探测必被 CD 门拒绝。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void minerScanBurnsCooldownAndReturnsExactlyTheSharedChainResult(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setMinerLevel(player, 10);
        ServerLevel level = helper.getLevel();
        // 矿必须放在<b>玩家</b>身边而不是测试结构里: mock 玩家由 placeNewPlayer 落在世界出生点, 不在结构内,
        // 按结构坐标放矿等于放在探测球外, 这条用例就永远只跑得到无命中分支。
        BlockPos ore = player.blockPosition().offset(1, 0, 0);
        level.setBlock(ore, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);

        MinerChargeState charge = MinerSystem.get().stateOf(player);
        long now = player.serverLevel().getGameTime();
        // 期望值取自被复用的裁决链本身 (只读, 不动 CD): 面板层若绕开它自建一条扫描, 两边立刻分叉。
        OreScanService.ScanHit expected = OreScanService.scanDetailed(player, 10);

        JsonObject result = handle(helper, SCAN_ACTION, player);

        helper.assertTrue(result.get("radius").getAsInt() == 16, "满级探测半径恒发 16");
        helper.assertTrue(result.get("pulseTicks").getAsInt() == MinerConstants.SCAN_PULSE_TICKS
                        && result.get("pulseTicks").getAsInt() == 160,
                "脉冲存活恒发 160 tick (8s), 前端据此自行熄灭, 实得 " + result.get("pulseTicks").getAsInt());
        helper.assertTrue(result.get("scanCooldownRemainingTicks").getAsLong() == 3600L,
                "满级探矿 CD 全长 3600 tick, 实得 " + result.get("scanCooldownRemainingTicks").getAsLong());
        helper.assertTrue(charge.cooldownReadyAt(MinerSkill.ORE_SCAN) == now + 3600L,
                "探测必须真起冷却 (无命中也一样), 实得 readyAt=" + charge.cooldownReadyAt(MinerSkill.ORE_SCAN));

        JsonArray hits = result.getAsJsonArray("hits");
        helper.assertTrue(hits.size() == expected.positions().size(),
                "命中数必须等于裁决链的结果 " + expected.positions().size() + ", 实得 " + hits.size());
        for (int i = 0; i < hits.size(); i++) {
            JsonObject pos = hits.get(i).getAsJsonObject();
            BlockPos actual = new BlockPos(pos.get("x").getAsInt(), pos.get("y").getAsInt(), pos.get("z").getAsInt());
            helper.assertTrue(expected.positions().contains(actual),
                    "回执坐标 " + actual + " 不在裁决链的命中集合内 (面板不许自建扫描)");
        }

        /*
         * 结果是确定的: GameTest 世界不是矿洞维度, 维度门排在 region 门之前, 故必定无命中。
         *
         * 这里原本写成"命中/无命中"两分支, 走哪一支取决于 mock 玩家出生点是否落进 EASY 区盒 —— 于是必有
         * 一支是死代码, 且断言的内容随世界种子漂移。命中侧的筛选正确性改由
         * oreScanFilteringEnforcesRadiusSingleOreAndHardCap 直接对 scanWorldDetailed 覆盖 (绕开两道门,
         * 结果确定), 矿种 JSON 化由 oreScanOreIdentityIsStable 覆盖。
         */
        helper.assertTrue(!player.level().dimension().equals(MiningConstants.MINING_LEVEL),
                "前提校验: 本用例建立在 GameTest 世界非矿洞维度之上");
        helper.assertTrue(expected.ore() == null && expected.positions().isEmpty(),
                "非矿洞维度必定无命中 (维度门在 region 门之前), 实得矿种 " + expected.ore());
        assertNullOreKeys(helper, result);

        // 探空/探到都不给免费重试: 同一 tick 再探必须撞 CD 门。
        WebUiBusinessException second = rejection(helper, SCAN_ACTION, player);
        helper.assertTrue(WebUiErrorCodes.SKILL_ON_COOLDOWN.equals(second.errorCode()),
                "刚探过就再探必须被 CD 门拒绝, 实得 " + second.errorCode());
        helper.succeed();
    }

    /**
     * 无命中时两个展示字段必须是 <b>JSON null</b> 而不是整键缺席 —— 契约写的是 {@code string | null},
     * 前端拿到 undefined 就是契约破裂 (默认 Gson 的 serializeNulls=false 正会丢掉这两个键)。
     *
     * 制造确定无命中的手法: 把玩家抬到结构上方 120 格。那里的探测球内只有空气, 无论矿洞门放不放行都必定
     * 无命中, 且不依赖"世界里恰好没有铁/煤"这种会被邻近用例污染的假设。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void minerScanEmitsExplicitJsonNullsWhenNothingIsFound(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setMinerLevel(player, 10);
        // 关重力: 让 mock 玩家停在高空, 免得它在后续 tick 里摔下去触发死亡事件干扰同批用例。
        player.setNoGravity(true);
        player.teleportTo(player.getX(), player.getY() + 120.0D, player.getZ());

        helper.assertTrue(OreScanService.scanDetailed(player, 10).ore() == null,
                "前置条件不成立: 高空探测球内竟有可探矿, 本条测不到无命中分支");

        JsonObject result = handle(helper, SCAN_ACTION, player);
        assertNullOreKeys(helper, result);
        helper.assertTrue(result.getAsJsonArray("hits").isEmpty(), "无命中时 hits 是空数组");
        helper.assertTrue(result.get("radius").getAsInt() == 16, "无命中不影响半径口径");
        helper.assertTrue(result.get("scanCooldownRemainingTicks").getAsLong() == 3600L,
                "无命中同样烧掉整轮 CD (让探空免费重试就等于把 CD 变成'探到为止')");
        helper.assertTrue(MinerSystem.get().stateOf(player).cooldownReadyAt(MinerSkill.ORE_SCAN)
                        > Long.MIN_VALUE,
                "无命中也必须真起冷却");
        helper.succeed();
    }

    /**
     * 冷却跨死亡与跨会话存活。
     *
     * 这一条守的是节流本身: CD 原先随 MinerChargeState 一起被死亡/登出/换维度整体丢弃, 于是 180 秒的探矿
     * 冷却只要自杀或重连一次 (几秒) 就归零 —— 与半径门叠加后, "有限半径 + CD" 这层节流事实上不存在。
     *
     * 分三段断言, 对应三条真实路径: 死亡 (重置瞬态) / 登出 (落 capability) / 登入 (从 capability 恢复)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oreScanCooldownSurvivesDeathAndRelog(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setMinerLevel(player, 10);

        MinerChargeState state = MinerSystem.get().stateOf(player);
        long now = player.serverLevel().getGameTime();
        state.startCooldown(MinerSkill.ORE_SCAN, now, MinerConstants.ORE_SCAN_CD_TICKS_AT_MAX);
        state.setCharge(5.0D, 10);
        long readyAt = state.cooldownReadyAt(MinerSkill.ORE_SCAN);
        helper.assertTrue(readyAt == now + MinerConstants.ORE_SCAN_CD_TICKS_AT_MAX,
                "前提校验: 冷却必须真的起了, 实得 readyAt=" + readyAt);

        // 一、死亡: 瞬态归零, 冷却留下。
        state.resetTransientKeepingCooldowns();
        helper.assertTrue(state.cooldownReadyAt(MinerSkill.ORE_SCAN) == readyAt,
                "死亡不得重置探矿冷却 (否则自杀就是免费的冷却重置), 实得 "
                        + state.cooldownReadyAt(MinerSkill.ORE_SCAN));
        helper.assertTrue(state.currentCharge() == 0,
                "死亡仍应清掉充能等瞬态, 实得 " + state.currentCharge());

        // 二、登出: 冷却落进 capability。
        com.miningdim.entry.IMiningPlayerData data = com.miningdim.entry.MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家未挂载 capability"));
        data.setMinerCooldowns(state.exportCooldowns());
        helper.assertTrue(data.minerCooldowns().contains(MinerSkill.ORE_SCAN.name(), net.minecraft.nbt.Tag.TAG_LONG),
                "登出必须把冷却写进 capability, 实得 " + data.minerCooldowns());

        // 三、登入: 新建的运行态从 capability 取回。
        MinerChargeState relogged = new MinerChargeState();
        relogged.importCooldowns(data.minerCooldowns());
        helper.assertTrue(relogged.cooldownReadyAt(MinerSkill.ORE_SCAN) == readyAt,
                "重连后冷却必须原样恢复 (否则登出重连就是免费的冷却重置), 实得 "
                        + relogged.cooldownReadyAt(MinerSkill.ORE_SCAN));
        helper.assertTrue(!relogged.cooldownReady(MinerSkill.ORE_SCAN, now),
                "恢复后在原时刻仍必须处于冷却中");

        helper.succeed();
    }

    /**
     * 旧存档没有冷却子标签时照常加载, 且认不出的技能名被跳过而不是抛在玩家加载路径上。
     *
     * 这条路径没有 Gateway 兜底 —— 反序列化抛出去的症状是玩家进不来, 所以缺键与脏值都必须自愈。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void minerCooldownsToleratesLegacyAndDirtyTags(GameTestHelper helper) {
        com.miningdim.entry.MiningPlayerData legacy = new com.miningdim.entry.MiningPlayerData();
        legacy.deserializeNBT(new net.minecraft.nbt.CompoundTag()); // 旧存档: 整个标签都没有这一键
        helper.assertTrue(legacy.minerCooldowns().isEmpty(),
                "旧存档缺键时冷却表应为空 (等价于全部就绪), 实得 " + legacy.minerCooldowns());

        net.minecraft.nbt.CompoundTag dirty = new net.minecraft.nbt.CompoundTag();
        dirty.putString("NOT_A_SKILL", "x");        // 认不出的键
        dirty.putString(MinerSkill.ORE_SCAN.name(), "not a long"); // 类型不符
        MinerChargeState state = new MinerChargeState();
        state.importCooldowns(dirty);
        helper.assertTrue(state.cooldownReadyAt(MinerSkill.ORE_SCAN) == Long.MIN_VALUE,
                "类型不符的值必须被跳过而不是被当成冷却, 实得 " + state.cooldownReadyAt(MinerSkill.ORE_SCAN));

        helper.succeed();
    }

    /**
     * 矿种 JSON 化的口径: 走石质变体的代表物品, 不随命中的是深板岩变体还是普通变体漂移。
     *
     * 独立成一条确定性用例 (不依赖任何一次真实扫描): 原先它挂在探矿成功分支里, 而那条分支在 GameTest
     * 环境下根本走不到, 于是 representativeItem 的口径实际无人守 —— 把它换成方块 id 或深板岩变体,
     * 全套测试照样全绿。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oreScanOreIdentityIsStable(GameTestHelper helper) {
        helper.assertTrue(OreType.IRON.representativeItem() == Blocks.IRON_ORE.asItem(),
                "铁矿的代表物品必须是石质变体 minecraft:iron_ore (深板岩变体只是同一矿种的另一种赋形)");
        helper.assertTrue("block.minecraft.iron_ore"
                        .equals(OreType.IRON.representativeItem().getDescriptionId()),
                "代表物品的翻译键必须是方块键 block.* —— 矿石是方块, 前端按 item.* 推键会解不出名字, 实得 "
                        + OreType.IRON.representativeItem().getDescriptionId());
        helper.succeed();
    }

    /**
     * 维度门: 矿工技能只在矿洞维度生效, 探矿在主世界/下界一律不出结果。
     *
     * 这一条守的是一个真实可利用的缺口: {@code RegionBox.contains} 只比 X/Z (Y 与维度都不参与), 而 EASY
     * 区盒是 X∈[0,256)、Z∈[0,256) —— 世界出生点通常就落在里面。少了维度判定, 玩家站在主世界出生点附近
     * 就能过 region 门, 而随后扫的是他当前所在维度, 探矿即成任意维度的透视器。
     *
     * 把玩家放到 (100, ·, 100): region 门在这个坐标必放行, 于是唯一还能挡住的只剩维度门。
     * 删掉 {@code scanDetailed} 里那句维度判定, 本条立刻挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oreScanRefusesOutsideTheMiningDimension(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setMinerLevel(player, 10);
        player.setNoGravity(true);
        player.teleportTo(100.5D, player.getY(), 100.5D);

        // 前提一: 本测试世界不是矿洞维度 (否则维度门放行, 本条测的就不是它该测的东西)。
        helper.assertTrue(!player.level().dimension().equals(MiningConstants.MINING_LEVEL),
                "前提校验: GameTest 世界不该是矿洞维度");
        // 前提二: region 门在此坐标必放行 —— 这样"无命中"就只可能来自维度门。
        helper.assertTrue(MiningServices.instanceManager().regionAt(100, 100) != null,
                "前提校验: (100,100) 必须落在某个区盒内, 否则挡住的是 region 门, 维度门被架空测不到");

        // 脚下真埋一颗铁矿: 没有它的话"无命中"可能只是因为附近本来就没矿。
        BlockPos underfoot = player.blockPosition().below();
        helper.getLevel().setBlock(underfoot, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(helper.getLevel().getBlockState(underfoot).is(Blocks.IRON_ORE),
                "前提校验: 脚下那颗铁矿必须真的放下去了");

        OreScanService.ScanHit hit = OreScanService.scanDetailed(player, 10);
        helper.assertTrue(hit.ore() == null && hit.positions().isEmpty(),
                "非矿洞维度一律不探, 实得矿种 " + hit.ore() + " / 坐标 " + hit.positions());
        helper.succeed();
    }

    // ============================================================
    // 4. K3 第 3、4 条: 半径门 / 单矿种 / 64 硬顶 (筛选实现的唯一落点)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oreScanFilteringEnforcesRadiusSingleOreAndHardCap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 往结构<b>上方</b>抬 8 格再造球: GameTest 的结构落在世界底部 (y 约 -60), 往下 8 格就越过了世界最低
        // 高度, setBlock 会静默失败, 整条几何断言会变成"对着一团空气断言"。
        BlockPos center = helper.absolutePos(new BlockPos(0, 1, 0)).above(8);
        int radius = 3;

        BlockPos nearIron1 = center.offset(1, 0, 0);
        BlockPos nearIron2 = center.offset(0, -2, 1);
        BlockPos farIron = center.offset(0, 0, 4); // 4 > 3: 球外
        /*
         * 对角样本: 三轴各 2 都 <= radius 故落在同边长立方体<b>内</b>, 但距离平方 12 > r2=9 在球<b>外</b>。
         *
         * 它专为守住 collectWithinSphere 的球面钳制而设 —— farIron 是单轴超界, 立方体化后它照样在外, 故删掉
         * 钳制那一句时无人发觉 (实测: 整句删除后全套测试仍全绿)。而球退化成立方体后对角线可达 radius*sqrt(3),
         * 满级 radius=16 时泄露到约 27.7 格外, 是一条真实的防 X 光弱化。
         */
        BlockPos cornerIron = center.offset(2, 2, 2);
        BlockPos nearDiamond = center.offset(-1, 0, 0);
        level.setBlock(nearIron1, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(nearIron2, Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(farIron, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(cornerIron, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(nearDiamond, Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);

        Set<OreType> l10 = OreScanService.allowedOres(10);
        OreScanService.ScanHit hit = OreScanService.scanWorldDetailed(level, center, radius, l10);

        helper.assertTrue(hit.ore() == OreType.IRON,
                "单矿种一次: 球内同时有铁与钻时只回优先序第一位的铁, 实得 " + hit.ore());
        helper.assertTrue(hit.positions().contains(nearIron1) && hit.positions().contains(nearIron2),
                "球内两颗铁矿 (含深板岩变体) 都必须命中");
        helper.assertTrue(!hit.positions().contains(farIron),
                "半径门: 球外的铁矿 " + farIron + " 绝不许下发");
        helper.assertTrue(!hit.positions().contains(cornerIron),
                "半径门是球不是立方体: 对角线上的铁矿 " + cornerIron + " (三轴均在半径内但距离超出) 绝不许下发");
        helper.assertTrue(!hit.positions().contains(nearDiamond) && hit.positions().size() == 2,
                "单矿种一次: 同一次结果里不许混进第二个矿种, 实得 " + hit.positions());

        // 等级门在筛选实现里的落点: L2 的可探集合是空的, 球内有矿也一律空返。
        OreScanService.ScanHit locked =
                OreScanService.scanWorldDetailed(level, center, radius, OreScanService.allowedOres(2));
        helper.assertTrue(locked.ore() == null && locked.positions().isEmpty(),
                "未解锁 (可探集合为空) 时即便球内有矿也不下发");
        // L3 里程碑: 铁/煤可探但钻不可探 —— 用只含钻石的球验证它确实被排除在外。
        OreScanService.ScanHit diamondOnly = OreScanService.scanWorldDetailed(
                level, nearDiamond, 1, OreScanService.allowedOres(MinerConstants.ORE_SCAN_UNLOCK_LEVEL));
        helper.assertTrue(diamondOnly.ore() == null,
                "L3 的可探集合不含钻石, 站在钻石上也探不到");

        // 64 条硬顶: 半径 3 的球内塞满 123 块铁矿, 下发必须恰好截到 64。
        int filled = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radius * radius) {
                        continue;
                    }
                    level.setBlock(center.offset(dx, dy, dz), Blocks.IRON_ORE.defaultBlockState(),
                            Block.UPDATE_ALL);
                    filled++;
                }
            }
        }
        helper.assertTrue(filled > MinerConstants.ORE_SCAN_MAX_RESULTS,
                "前置条件: 填进球内的矿数 " + filled + " 必须超过硬顶才测得到截断");
        List<BlockPos> capped = OreScanService.scanWorld(level, center, radius, EnumSet.of(OreType.IRON));
        helper.assertTrue(capped.size() == MinerConstants.ORE_SCAN_MAX_RESULTS && capped.size() == 64,
                "一次探测最多下发 64 条坐标 (防一次洗出整张矿图), 实得 " + capped.size());
        helper.succeed();
    }

    // ============================================================
    // 5. 翻译键与注册名
    // ============================================================

    /**
     * 服务端只发翻译键 (专用服务端不加载 lang), 键在资源里不存在时面板就显示原始键。数值行是回执里现取的,
     * 将来加一行被动却忘了补两份 lang, 本条即挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void minerPassiveLabelKeysExistInBothLangFiles(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonArray passives = handle(helper, STATE_ACTION, player).getAsJsonArray("passives");
        JsonObject zh = loadJsonResource("/assets/miningdim/lang/zh_cn.json");
        JsonObject en = loadJsonResource("/assets/miningdim/lang/en_us.json");

        for (int i = 0; i < passives.size(); i++) {
            JsonObject row = passives.get(i).getAsJsonObject();
            String labelKey = row.get("labelKey").getAsString();
            helper.assertTrue(("stat.miningdim.miner." + row.get("key").getAsString()).equals(labelKey),
                    "labelKey 必须由 key 派生 (stat.miningdim.miner.<key>), 实得 " + labelKey);
            helper.assertTrue(zh.has(labelKey), "zh_cn.json 缺翻译键 " + labelKey);
            helper.assertTrue(en.has(labelKey), "en_us.json 缺翻译键 " + labelKey);
        }
        // 开关位的键沿用矿工技能既有命名空间, 不另造一套。
        for (String skillId : TOGGLE_IDS) {
            String key = "skill.miningdim.miner." + skillId;
            helper.assertTrue(zh.has(key) && en.has(key), "两份 lang 都必须有开关翻译键 " + key);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void minerActionsAreRegisteredUnderContractNames(GameTestHelper helper) {
        ensureMinerActionsRegistered();
        helper.assertTrue(WebUiServerDispatcher.resolve(STATE_ACTION) != null
                        && WebUiServerDispatcher.resolve(SCAN_ACTION) != null,
                "job.miner.state 与 job.miner.scan 必须由 MinerWebUiActions.registerAll 注册进派发器");
        helper.assertTrue(WebUiServerDispatcher.resolve("job.miner.oreScan") == null
                        && WebUiServerDispatcher.resolve("miner.scan") == null,
                "不得注册探矿的别名 action (前端 SERVER_ACTIONS 里只有 job.miner.scan 一条)");
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    /** 幂等注册: 派发器注册表是进程级静态, register 用 putIfAbsent 守卫, 重复注册直接抛。 */
    private static void ensureMinerActionsRegistered() {
        if (WebUiServerDispatcher.resolve(STATE_ACTION) == null) {
            MinerWebUiActions.registerAll();
        }
    }

    private static JsonObject handle(GameTestHelper helper, String action, ServerPlayer sender) {
        return JsonParser.parseString(handler(helper, action).handle(sender, new JsonObject())).getAsJsonObject();
    }

    /** 调 action 并要求它抛业务拒绝; 没抛就地判失败。 */
    private static WebUiBusinessException rejection(GameTestHelper helper, String action, ServerPlayer sender) {
        try {
            handler(helper, action).handle(sender, new JsonObject());
        } catch (WebUiBusinessException rejected) {
            return rejected;
        }
        helper.fail("该请求本应被业务拒绝, 实际却成功返回了: " + action);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static WebUiAction handler(GameTestHelper helper, String action) {
        ensureMinerActionsRegistered();
        WebUiAction handler = WebUiServerDispatcher.resolve(action);
        if (handler == null) {
            helper.fail("action " + action + " 未注册进派发器");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return handler;
    }

    private static void setMinerLevel(ServerPlayer player, int level) {
        MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"))
                .jobProgress(JobId.MINER).setLevel(level);
    }

    private static void assertNullOreKeys(GameTestHelper helper, JsonObject result) {
        helper.assertTrue(result.has("oreItemId") && result.has("oreDescriptionId"),
                "无命中时两个矿种字段必须仍然在回执里 (契约是 string|null, 缺键会让前端拿到 undefined)");
        helper.assertTrue(result.get("oreItemId").isJsonNull() && result.get("oreDescriptionId").isJsonNull(),
                "无命中时两个矿种字段必须是 JSON null 而不是空串");
    }

    private static void assertPassiveShape(GameTestHelper helper, JsonArray passives) {
        helper.assertTrue(passives.size() == PASSIVE_KEY_UNIT.length,
                "passives 恒 " + PASSIVE_KEY_UNIT.length + " 条, 实得 " + passives.size());
        for (int i = 0; i < PASSIVE_KEY_UNIT.length; i++) {
            JsonObject row = passives.get(i).getAsJsonObject();
            helper.assertTrue(PASSIVE_KEY_UNIT[i][0].equals(row.get("key").getAsString()),
                    "第 " + i + " 行必须是 " + PASSIVE_KEY_UNIT[i][0] + ", 实得 " + row.get("key").getAsString());
            helper.assertTrue(PASSIVE_KEY_UNIT[i][1].equals(row.get("unit").getAsString()),
                    PASSIVE_KEY_UNIT[i][0] + " 的量纲必须是 " + PASSIVE_KEY_UNIT[i][1]
                            + " (发错量纲 = 前端把 0.3 显示成 x0.3), 实得 " + row.get("unit").getAsString());
        }
    }

    private static void assertPassive(GameTestHelper helper, JsonArray passives, int index, double expected) {
        JsonObject row = passives.get(index).getAsJsonObject();
        double actual = row.get("value").getAsDouble();
        helper.assertTrue(Math.abs(actual - expected) < EPS,
                row.get("key").getAsString() + " 应为 " + expected + ", 实得 " + actual);
    }

    private static JsonObject loadJsonResource(String path) {
        try (InputStream in = MinerWebUiGameTests.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("JSON resource not found on classpath: " + path);
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException("failed reading JSON resource: " + path, e);
        }
    }
}
