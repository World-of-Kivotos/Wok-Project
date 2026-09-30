package com.miningdim.job.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionAttackValues;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.StarRank;
import com.miningdim.champion.bloodpool.BloodPool;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.champion.integration.ChampionPromoter;
import com.miningdim.core.MiningConstants;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.JobId;
import com.miningdim.job.agent.integration.AgentIntegrationBootstrap;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiServerDispatcher;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 战术扫描分级情报 (第四章探测列 L3-L10) 走<b>真集成层</b>的端到端 GameTest: 真盖章精英 -> 真读取器 -> 构建层分级 ->
 * job.agent.scan / job.agent.state 的 JSON。与 {@link AgentWebUiGameTests} 的分工: 那边给接缝装桩测九态透传与防 X 光
 * 门, 这边必须用真实现 —— 被测的正是"读出来的数是不是精英身上的真值"与"锁住的格在 JSON 里是不是显式 null"。
 *
 * 批次纪律: 本组每条用例开头都把接缝装回真实现, 与装桩的 webui_w4a 批分开 (批内并行、批间串行, 同批混装会互相
 * 拆台)。首次发现经验单独一批 ({@value #BATCH_DISCOVERY}): 它断言的是回执里的经验总额, 同批其它用例放在出生点附近
 * 的精英会被一并"首次发现"而污染总额, 只有独占一批才测得出精确值。其余用例只按网络 id 找自己的那一行, 对邻居的
 * 目标免疫。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class AgentScanIntelGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "agent_scan_intel";
    private static final String BATCH_DISCOVERY = "agent_scan_discovery";

    private static final String STATE_ACTION = "job.agent.state";
    private static final String SCAN_ACTION = "job.agent.scan";

    private static final double EPSILON = 1.0E-9D;

    // ============================================================
    // 1. 锁住的格是显式 JSON null (键在, 值为 null), 解锁即真值
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void scanJsonSendsExplicitNullForLockedIntelAndTheRealValueOnceUnlocked(GameTestHelper helper) {
        AgentIntegrationBootstrap.bindSeam();
        ServerPlayer two = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(two, 2);
        ServerPlayer three = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(three, 3);
        three.setNoGravity(true);
        three.teleportTo(two.getX(), two.getY(), two.getZ());
        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.BURNING, AffixQuality.COMMON);
        affixes.put(AffixDef.REGEN_TISSUE, AffixQuality.COMMON);
        LivingEntity target = spawnChampion(helper, two, 2.0D, 3, affixes);

        JsonObject atTwoResult = handle(helper, SCAN_ACTION, two);
        helper.assertTrue(!atTwoResult.get("glowingHighlight").getAsBoolean(), "L2 脉冲没有高亮 (L8 才解)");
        JsonObject atTwo = findRow(helper, atTwoResult.getAsJsonArray("targets"), target.getId());
        for (String key : new String[]{"effectiveHp", "armor", "damageReductionPct", "bulletResistancePct",
                "attackDamage", "singleHitPct", "movementSpeed", "mechanics", "live"}) {
            helper.assertTrue(atTwo.has(key) && atTwo.get(key).isJsonNull(),
                    "L2 时 " + key + " 必须是显式 JSON null (键在、值为 null; 0 是真实存在的数值), 实得 "
                            + atTwo.get(key));
        }
        for (JsonElement entry : atTwo.getAsJsonArray("entries")) {
            JsonObject row = entry.getAsJsonObject();
            helper.assertTrue(row.has("quality") && row.get("quality").isJsonNull(),
                    "L2 时每条词条的品质格都是显式 null, 实得 " + row);
        }

        // 面板"需要 Lv.N"占位的等级表逐格等于 AgentScanField (前端不另抄一份)。
        JsonObject unlockLevels = handle(helper, STATE_ACTION, two).getAsJsonObject("scanFieldUnlockLevels");
        for (AgentScanField field : AgentScanField.values()) {
            helper.assertTrue(unlockLevels.get(field.name()).getAsInt() == field.unlockLevel(),
                    "scanFieldUnlockLevels." + field.name() + " 必须等于 AgentScanField 表值 " + field.unlockLevel());
        }

        JsonObject atThree = findRow(helper, handle(helper, SCAN_ACTION, three).getAsJsonArray("targets"),
                target.getId());
        double expectedHp = MiningChampions.get(target).map(MiningChampionData::effectiveHp).orElse(-1.0D);
        helper.assertTrue(Math.abs(atThree.get("effectiveHp").getAsDouble() - expectedHp) < EPSILON,
                "L3 解密有效血量, 必须等于精英 capability 里的总有效血 " + expectedHp + ", 实得 "
                        + atThree.get("effectiveHp"));
        helper.assertTrue(atThree.get("armor").isJsonNull(), "L3 护甲仍是 null (L4 才解)");

        target.discard();
        helper.succeed();
    }

    // ============================================================
    // 2. L10 全格: 每个数都来自精英身上的真值, 与结算链同一口径
    // ============================================================

    /**
     * 8★ 带三件套被动 (复合装甲 / 超高分子 / 重炮, 全高级) + 三条技能 (电磁蓄力高级 / 小男孩超凡 / 自我修复高级)。
     * 每一格都按结算链的同一个纯函数算期望值, 读取器口径一旦与结算链分叉本条即挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void scanJsonAtL10ReportsRealChampionValues(GameTestHelper helper) {
        AgentIntegrationBootstrap.bindSeam();
        ServerPlayer agent = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(agent, 10);
        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.COMPOSITE_ARMOR, AffixQuality.RARE);
        affixes.put(AffixDef.UHMWPE_ARMOR, AffixQuality.RARE);
        affixes.put(AffixDef.HEAVY_CANNON, AffixQuality.RARE);
        affixes.put(AffixDef.ELECTRO_CHARGE, AffixQuality.RARE);
        affixes.put(AffixDef.LITTLE_BOY, AffixQuality.EPIC);
        affixes.put(AffixDef.SELF_REPAIR, AffixQuality.RARE);
        LivingEntity target = spawnChampion(helper, agent, 2.0D, 8, affixes);

        JsonObject row = findRow(helper, handle(helper, SCAN_ACTION, agent).getAsJsonArray("targets"), target.getId());
        MiningChampionData champ = MiningChampions.get(target).orElseThrow();
        assertClose(helper, row, "effectiveHp", champ.effectiveHp());
        assertClose(helper, row, "armor", target.getAttributeValue(Attributes.ARMOR));
        // 复合装甲高级满层上限 0.55, 没有缩小化; 远低于 75% 帽。
        assertClose(helper, row, "damageReductionPct", AffixDef.COMPOSITE_ARMOR.valueFor(AffixQuality.RARE));
        // 超高分子高级子弹抗 0.22; 没有重型/偏斜。
        assertClose(helper, row, "bulletResistancePct", AffixDef.UHMWPE_ARMOR.valueFor(AffixQuality.RARE));
        assertClose(helper, row, "attackDamage", target.getAttributeValue(Attributes.ATTACK_DAMAGE));
        // 重炮高级 +65%, 满血嗜血不激活, 无穿甲: 与 ChampionAttackHandler.applyInstantDamage 同一个钳制函数。
        assertClose(helper, row, "singleHitPct", ChampionAttackValues.singleHitTotalPct(StarRank.STAR_8,
                StarRank.STAR_8.baseSingleHitPct(), AffixDef.HEAVY_CANNON.valueFor(AffixQuality.RARE), 0.0D, 0.0D));
        assertClose(helper, row, "movementSpeed", target.getAttributeValue(Attributes.MOVEMENT_SPEED));

        JsonArray mechanics = row.getAsJsonArray("mechanics");
        helper.assertTrue(mechanics.size() == 3, "三条技能各一行时序, 实得 " + mechanics);
        JsonObject electro = findMechanic(helper, mechanics, "ELECTRO_CHARGE");
        helper.assertTrue(electro.get("chargeSeconds").getAsDouble() == 2.0D
                        && electro.get("cooldownSeconds").getAsDouble() == 12.0D
                        && !electro.has("interruptDamagePerPlayer"),
                "电磁蓄力: 蓄力 2s / 高级周期 12s / 没有打断阈值 (缺键, 不是 null 也不是 0), 实得 " + electro);
        JsonObject littleBoy = findMechanic(helper, mechanics, "LITTLE_BOY");
        helper.assertTrue(littleBoy.get("chargeSeconds").getAsDouble() == 5.0D
                        && littleBoy.get("interruptDamagePerPlayer").getAsDouble() == 120.0D
                        && !littleBoy.has("cooldownSeconds"),
                "小男孩: 蓄力 5s / 每人 120 打断 / 一次性无冷却 (缺键), 实得 " + littleBoy);
        JsonObject selfRepair = findMechanic(helper, mechanics, "SELF_REPAIR");
        helper.assertTrue(!selfRepair.has("interruptDamagePerPlayer")
                        && selfRepair.get("cooldownSeconds").getAsDouble() == 25.0D,
                "自我修复没有伤害打断阈值 (近战命中即断), 冷却 25s, 实得 " + selfRepair);

        // 品质表 (L8): 每条进面板的词条带原料品质; 纯防御的复合/超高分子不进词条行。
        for (JsonElement element : row.getAsJsonArray("entries")) {
            JsonObject entry = element.getAsJsonObject();
            String affixId = entry.get("affixId").getAsString();
            AffixQuality expected = affixes.get(AffixDef.valueOf(affixId));
            helper.assertTrue(expected != null && expected.name().equals(entry.get("quality").getAsString()),
                    "L8 词条 " + affixId + " 的品质必须是盖章品质 " + expected + ", 实得 " + entry.get("quality"));
        }

        // L10 脉冲的回执当刻就带实时透视 (含全属性); 8★ 的血量权威是影子血池。
        JsonObject live = row.getAsJsonObject("live");
        BloodPool pool = BloodPoolRegistry.get(target.getUUID());
        helper.assertTrue(pool != null, "前提校验: 8★ 必须建了影子血池");
        helper.assertTrue(live.get("tracked").getAsBoolean()
                        && Math.abs(live.get("currentHp").getAsDouble() - pool.currentHp()) < EPSILON
                        && Math.abs(live.get("maxHp").getAsDouble() - pool.maxHp()) < EPSILON,
                "实时血量读影子血池权威值 (不读原版渲染镜像), 实得 " + live);
        helper.assertTrue(live.get("attributes").isJsonObject()
                        && Math.abs(live.getAsJsonObject("attributes").get("damageReductionPct").getAsDouble()
                        - AffixDef.COMPOSITE_ARMOR.valueFor(AffixQuality.RARE)) < EPSILON,
                "L10 实时全属性与快照同口径, 实得 " + live.get("attributes"));

        target.discard();
        helper.succeed();
    }

    // ============================================================
    // 3. 实时透视: L9 在 state 里刷新, L8 没有; 目标离场即 tracked=false
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void stateRefreshesLiveNumbersForL9PulsesOnly(GameTestHelper helper) {
        AgentIntegrationBootstrap.bindSeam();
        ServerPlayer nine = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(nine, 9);
        ServerPlayer eight = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(eight, 8);
        eight.setNoGravity(true);
        eight.teleportTo(nine.getX(), nine.getY(), nine.getZ());
        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.BURNING, AffixQuality.COMMON);
        LivingEntity target = spawnChampion(helper, nine, 2.0D, 3, affixes);

        handle(helper, SCAN_ACTION, nine);
        handle(helper, SCAN_ACTION, eight);

        JsonObject first = findRow(helper, handle(helper, STATE_ACTION, nine).getAsJsonArray("targets"),
                target.getId()).getAsJsonObject("live");
        helper.assertTrue(first.get("tracked").getAsBoolean()
                        && Math.abs(first.get("currentHp").getAsDouble() - target.getHealth()) < EPSILON,
                "L9: state 带目标此刻血量 (1-5★ 读原版血), 实得 " + first);
        helper.assertTrue(first.get("attributes").isJsonNull(), "L9 全属性实时仍加密 (L10 才解)");

        // 快照之后目标掉血: 再读 state 必须看到新值 —— 这正是"实时"与"快照"的区别。
        float lowered = target.getHealth() - 5.0F;
        target.setHealth(lowered);
        JsonObject second = findRow(helper, handle(helper, STATE_ACTION, nine).getAsJsonArray("targets"),
                target.getId()).getAsJsonObject("live");
        helper.assertTrue(Math.abs(second.get("currentHp").getAsDouble() - lowered) < 1.0E-4D,
                "L9 state 重读现值 " + lowered + ", 实得 " + second.get("currentHp"));

        JsonObject atEight = findRow(helper, handle(helper, STATE_ACTION, eight).getAsJsonArray("targets"),
                target.getId());
        helper.assertTrue(atEight.has("live") && atEight.get("live").isJsonNull(),
                "L8 脉冲没有实时透视, live 必须是显式 JSON null, 实得 " + atEight.get("live"));

        // 目标离场: 快照行仍在 (目标集合冻结在脉冲那一刻), 但实时数据只能报"读不到", 绝不发旧值。
        target.discard();
        JsonObject gone = findRow(helper, handle(helper, STATE_ACTION, nine).getAsJsonArray("targets"),
                target.getId()).getAsJsonObject("live");
        helper.assertTrue(!gone.get("tracked").getAsBoolean() && gone.get("currentHp").isJsonNull(),
                "目标离场后 tracked=false 且不发任何旧值, 实得 " + gone);
        helper.succeed();
    }

    // ============================================================
    // 4. 逐玩家高亮: 只发给扫描者本人, 快照到期即熄
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void glowHighlightReachesOnlyTheScanningAgentAndClearsOnExpiry(GameTestHelper helper) {
        AgentIntegrationBootstrap.bindSeam();
        ServerPlayer agent = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(agent, 8);
        ServerPlayer bystander = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer seven = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(seven, 7);
        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.BURNING, AffixQuality.COMMON);
        LivingEntity target = spawnChampion(helper, agent, 2.0D, 4, affixes);

        drainFlags(agent, target.getId());
        drainFlags(bystander, target.getId());
        JsonObject scan = handle(helper, SCAN_ACTION, agent);
        helper.assertTrue(scan.get("glowingHighlight").getAsBoolean(), "L8 脉冲回执报 glowingHighlight=true");
        helper.assertTrue(AgentScanGlow.isHighlighting(agent.getUUID(), target.getId()),
                "L8 扫描后该目标在扫描者的高亮会话里");
        List<Byte> sent = drainFlags(agent, target.getId());
        helper.assertTrue(sent.stream().anyMatch(AgentScanIntelGameTests::glowing),
                "扫描者必须收到一份发光位已置的共享标志位包, 实得 " + sent);
        helper.assertTrue(drainFlags(bystander, target.getId()).stream().noneMatch(AgentScanIntelGameTests::glowing),
                "旁观玩家绝不能收到发光位 (原版发光会对全场广播, 那等于把透视白送给旁人)");
        helper.assertTrue(!target.isCurrentlyGlowing(), "服务端实体本身一个比特都不改");

        // 快照到期: 高亮与快照同源同长, 一起拨到期后推进一次高亮处理。
        helper.assertTrue(AgentWebUiActions.rewindPulseForTest(agent.getUUID(), 20L * 60L),
                "前提校验: 必须真的有一条脉冲记录可拨");
        AgentScanGlow.tick(helper.getLevel().getServer());
        helper.assertTrue(!AgentScanGlow.isHighlighting(agent.getUUID(), target.getId()), "快照到期即结束高亮会话");
        List<Byte> afterExpiry = drainFlags(agent, target.getId());
        helper.assertTrue(!afterExpiry.isEmpty() && !glowing(afterExpiry.get(afterExpiry.size() - 1)),
                "到期时必须补发真实字节把高亮熄掉 (最后一份包发光位为 0), 实得 " + afterExpiry);

        // L7 脉冲不开高亮。
        handle(helper, SCAN_ACTION, seven);
        helper.assertTrue(!AgentScanGlow.isHighlighting(seven.getUUID(), target.getId()), "L7 脉冲不高亮");

        target.discard();
        helper.succeed();
    }

    // ============================================================
    // 5. 首次发现经验 (8.1): 每 (玩家, 精英) 一次, 召唤物不给
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH_DISCOVERY)
    public static void discoveryXpIsGrantedOncePerChampionPerPlayerAndNeverForSummons(GameTestHelper helper) {
        AgentIntegrationBootstrap.bindSeam();
        ServerPlayer first = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer second = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.BURNING, AffixQuality.COMMON);
        LivingEntity champion = spawnChampion(helper, first, 2.0D, 4, affixes);
        LivingEntity summon = spawnChampion(helper, first, 3.0D, 2, affixes);
        MiningChampions.get(summon).orElseThrow().markSummonedByAffix();
        helper.assertTrue(!AgentBountySavedData.get(helper.getLevel().getServer().overworld())
                .isActiveAgent(first.getUUID()), "前提校验: 新号未入职 (发现经验不受入职门约束)");

        long xpBefore = agentXp(first);
        JsonObject scan = handle(helper, SCAN_ACTION, first);
        helper.assertTrue(scan.get("discoveryXp").getAsLong() == 4L * AgentDiscoveryXp.XP_PER_STAR
                        && scan.get("discoveryCount").getAsInt() == 1,
                "首次扫到 4★ 精英给 4 x 8 = 32 经验, 召唤物不计, 实得 xp=" + scan.get("discoveryXp")
                        + " count=" + scan.get("discoveryCount"));
        helper.assertTrue(agentXp(first) - xpBefore == 32L,
                "经验真的入了特勤职业账 (未入职也照发, F016), 实得增量 " + (agentXp(first) - xpBefore));
        helper.assertTrue(AgentDiscoveryXp.hasDiscovered(champion, first.getUUID()), "发现记录写在精英自己身上");
        helper.assertTrue(!AgentDiscoveryXp.hasDiscovered(summon, first.getUUID()),
                "支援召唤物既不给经验也不登记 (与击杀结算同一道经济闸)");

        // 同一玩家再扫同一只: 不再给。
        AgentWebUiActions.rewindPulseForTest(first.getUUID(), 20L * 60L);
        JsonObject again = handle(helper, SCAN_ACTION, first);
        helper.assertTrue(again.get("discoveryXp").getAsLong() == 0L && again.get("discoveryCount").getAsInt() == 0,
                "同一玩家对同一只精英只算一次首次发现, 实得 " + again.get("discoveryXp"));

        // 另一名玩家扫同一只: 他也是第一次找到, 照给。
        JsonObject other = handle(helper, SCAN_ACTION, second);
        helper.assertTrue(other.get("discoveryXp").getAsLong() == 32L,
                "去重键是 (玩家, 精英), 另一名玩家首次扫到照给 32, 实得 " + other.get("discoveryXp"));

        champion.discard();
        summon.discard();
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    // ============================================================
    // 悬赏雷达 (第四章 L6): 目标能推进已接悬赏才亮, L6 以下整格 null
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bountyRadarMarksTargetsOfAcceptedBountiesFromLevelSix(GameTestHelper helper) {
        AgentIntegrationBootstrap.bindSeam();
        ServerPlayer six = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(six, 6);
        ServerPlayer five = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(five, 5);
        five.setNoGravity(true);
        five.teleportTo(six.getX(), six.getY(), six.getZ());

        // 固定种子把 six 的悬赏板掷成"第一张日常是星级类", 与悬赏集成用例同一手法 (掷取确定, 同期同级不重掷)。
        BountyDefinition wanted = null;
        long day = AgentClock.currentUtcDayStamp();
        long week = AgentClock.currentUtcWeekStamp();
        for (long seed = 0L; seed < 200L && wanted == null; seed++) {
            BountyBoard probe = new BountyBoard();
            probe.refresh(day, week, 6, AgentBountyConfig.table(), RandomSource.create(seed));
            if (probe.daily().get(0).definition().targetType() == BountyDefinition.TargetType.KILL_STAR_AT_LEAST) {
                AgentBountySavedData.get(six.server.overworld()).board(six.getUUID())
                        .refresh(day, week, 6, AgentBountyConfig.table(), RandomSource.create(seed));
                wanted = probe.daily().get(0).definition();
            }
        }
        helper.assertTrue(wanted != null, "前提: 应能掷出首张为星级类的日常");
        helper.assertTrue(AgentBountyService.accept(six, BountyDefinition.Period.DAILY, wanted.id()).outcome()
                == BountyBoard.AcceptOutcome.OK, "前提: 接取成功");

        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.BURNING, AffixQuality.COMMON);
        LivingEntity target = spawnChampion(helper, six, 2.0D, wanted.minStar(), affixes);
        LivingEntity tooLow = spawnChampion(helper, six, -2.0D, 1, affixes);

        JsonArray sixRows = handle(helper, SCAN_ACTION, six).getAsJsonArray("targets");
        helper.assertTrue(findRow(helper, sixRows, target.getId()).get("bountyTarget").getAsBoolean(),
                "L6: 达到已接悬赏星级门的目标亮起");
        helper.assertTrue(!findRow(helper, sixRows, tooLow.getId()).get("bountyTarget").getAsBoolean(),
                "L6: 星级不够的目标不亮 (是 false 不是 null)");

        JsonObject fiveRow = findRow(helper, handle(helper, SCAN_ACTION, five).getAsJsonArray("targets"),
                target.getId());
        helper.assertTrue(fiveRow.has("bountyTarget") && fiveRow.get("bountyTarget").isJsonNull(),
                "L5: 雷达未解锁, 键在、值为 JSON null, 实得 " + fiveRow.get("bountyTarget"));
        helper.succeed();
    }

    /**
     * 在玩家身边放一只真盖章精英 (相对玩家而非结构: mock 玩家落在世界出生点, 见 AgentWebUiGameTests 同名说明)。
     * 不动、不落、不死, 以免断言之间跑出扫描球或被别的用例误伤。
     */
    private static LivingEntity spawnChampion(GameTestHelper helper, ServerPlayer near, double offsetX, int star,
                                              Map<AffixDef, AffixQuality> affixes) {
        ServerLevel level = helper.getLevel();
        Zombie zombie = EntityType.ZOMBIE.create(level);
        if (zombie == null) {
            helper.fail("无法创建测试用僵尸实体");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        zombie.moveTo(near.getX() + offsetX, near.getY(), near.getZ(), 0.0F, 0.0F);
        zombie.setNoAi(true);
        zombie.setNoGravity(true);
        zombie.setInvulnerable(true);
        level.addFreshEntity(zombie);
        ChampionPromoter.applyChampion(zombie, star, affixes);
        return zombie;
    }

    private static JsonObject handle(GameTestHelper helper, String action, ServerPlayer sender) {
        WebUiServerDispatcher.WebUiAction handler = WebUiServerDispatcher.resolve(action);
        if (handler == null) {
            helper.fail("action " + action + " 未注册进派发器");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return JsonParser.parseString(handler.handle(sender, new JsonObject())).getAsJsonObject();
    }

    private static JsonObject findRow(GameTestHelper helper, JsonArray targets, int networkId) {
        for (JsonElement element : targets) {
            JsonObject row = element.getAsJsonObject();
            if (row.get("targetNetworkId").getAsInt() == networkId) {
                return row;
            }
        }
        helper.fail("回执里找不到目标 " + networkId + ", 实得 " + targets);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static JsonObject findMechanic(GameTestHelper helper, JsonArray mechanics, String affixId) {
        for (JsonElement element : mechanics) {
            JsonObject row = element.getAsJsonObject();
            if (affixId.equals(row.get("affixId").getAsString())) {
                return row;
            }
        }
        helper.fail("技能时序里找不到 " + affixId + ", 实得 " + mechanics);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static void assertClose(GameTestHelper helper, JsonObject row, String key, double expected) {
        JsonElement actual = row.get(key);
        helper.assertTrue(actual != null && !actual.isJsonNull()
                        && Math.abs(actual.getAsDouble() - expected) < EPSILON,
                key + " 应为精英真值 " + expected + ", 实得 " + actual);
    }

    /**
     * 读空 mock 连接出站队列, 按到达顺序取出发给指定实体的共享标志位字节 (其余包丢弃)。
     */
    private static List<Byte> drainFlags(ServerPlayer player, int entityId) {
        EmbeddedChannel channel = (EmbeddedChannel) player.connection.connection.channel();
        List<Byte> flags = new ArrayList<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            try {
                if (outbound instanceof ClientboundSetEntityDataPacket packet && packet.id() == entityId) {
                    for (SynchedEntityData.DataValue<?> value : packet.packedItems()) {
                        if (value.id() == Entity.DATA_SHARED_FLAGS_ID.getId() && value.value() instanceof Byte b) {
                            flags.add(b);
                        }
                    }
                }
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        }
        return flags;
    }

    private static boolean glowing(Byte flags) {
        return (flags & (1 << AgentScanGlow.FLAG_GLOWING)) != 0;
    }

    private static long agentXp(ServerPlayer player) {
        return MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"))
                .jobProgress(JobId.AGENT).xp();
    }

    private static void setAgentLevel(ServerPlayer player, int level) {
        MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"))
                .jobProgress(JobId.AGENT).setLevel(level);
    }
}
