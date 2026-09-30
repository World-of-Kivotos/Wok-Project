package com.miningdim.job.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.miningdim.webui.server.WebUiPayloads;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.server.level.ServerPlayer;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * 特勤悬赏板的 WebUI 投影: {@code job.agent.state} 回执里的 {@code bounty} 段 + 接取 action {@code job.agent.bounty.accept}。
 *
 * 与扫描/封印分开一个类, 是因为两块的契约各自演进: 悬赏段只读悬赏板 ({@link AgentBountyService}), 不碰脉冲快照。
 * 规则一律在服务端裁决 (槽位、星级门、翻期都在 {@link BountyBoard}), 本层只做入参校验与 JSON 化。
 *
 * 时间口径: 翻期是 UTC 墙钟 (日常 UTC 零点、周常 ISO 周一零点), 不是 game tick, 所以这里发的是剩余<b>秒</b>, 与扫描段
 * 发剩余 tick 的口径不同, 字段名里带单位以免混用。
 */
public final class AgentBountyWebUi {

    /** 与 {@code AgentWebUiActions} 同纪律: serializeNulls, null 是"这一格没有值"的真值 (如非词条类悬赏的 targetPool)。 */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private static final long SECONDS_PER_DAY = 86_400L;

    private AgentBountyWebUi() {
    }

    /** 注册 {@code job.agent.bounty.accept} (由 {@code AgentWebUiActions.registerAll} 调用一次)。 */
    static void registerAll() {
        WebUiServerDispatcher.register("job.agent.bounty.accept", ACCEPT);
    }

    /**
     * 接取一张悬赏。入参 {@code {period: "DAILY"|"WEEKLY", bountyId}}; 槽位/星级/翻期全由悬赏板裁决, 回执带上最新整段
     * bounty 投影, 前端直接替换, 不在本地推算"接了之后剩几个槽"。
     */
    static final WebUiAction ACCEPT = (sender, payload) -> {
        String periodName = WebUiPayloads.requiredString(payload, "period");
        String bountyId = WebUiPayloads.requiredString(payload, "bountyId");
        BountyDefinition.Period period;
        if ("DAILY".equals(periodName)) {
            period = BountyDefinition.Period.DAILY;
        } else if ("WEEKLY".equals(periodName)) {
            period = BountyDefinition.Period.WEEKLY;
        } else {
            // EVENT (世界 BOSS 讨伐令) 不需要接取, 也不接受客户端指定。
            throw WebUiPayloads.illegalValue("period", periodName, "只能接取 DAILY 或 WEEKLY 悬赏");
        }
        if (bountyId.isBlank()) {
            throw WebUiPayloads.illegalValue("bountyId", bountyId, "悬赏 id 不能为空");
        }

        AgentBountyService.AcceptResult accepted = AgentBountyService.accept(sender, period, bountyId);
        JsonObject result = new JsonObject();
        result.addProperty("ok", accepted.outcome() == BountyBoard.AcceptOutcome.OK);
        result.addProperty("outcomeCode", accepted.outcome().name());
        result.addProperty("newlyActiveAgent", accepted.newlyActiveAgent());
        result.addProperty("activeAgent",
                AgentBountySavedData.get(sender.server.overworld()).isActiveAgent(sender.getUUID()));
        result.add("bounty", bountyJson(sender));
        return GSON.toJson(result);
    };

    /**
     * {@code job.agent.state} 回执里的 bounty 段。打开面板即取板 (会按当前等级翻期/补掷), 这是悬赏板唯一的建板入口
     * 之一 —— 打开特勤面板本身就是在做特勤, 与击杀结算不为路人建板的口径不冲突。
     */
    public static JsonObject bountyJson(ServerPlayer player) {
        int level = AgentLevels.agentLevel(player);
        AgentBountySavedData data = AgentBountySavedData.get(player.server.overworld());
        boolean available = AgentBountyConfig.enabled();

        JsonObject bounty = new JsonObject();
        bounty.addProperty("available", available);
        bounty.addProperty("dailySlots", AgentSkillTable.dailyBountySlots(level));
        bounty.addProperty("weeklySlots", AgentSkillTable.weeklyBountySlots(level));
        bounty.addProperty("weeklyUnlocked", AgentSkillTable.isWeeklyBountyUnlocked(level));
        bounty.addProperty("weeklyUnlockLevel", AgentSkillTable.WEEKLY_BOUNTY_UNLOCK_LEVEL);
        bounty.addProperty("maxBountyStar", AgentSkillTable.maxBountyStar(level));
        bounty.addProperty("worldBossUnlocked", AgentSkillTable.isWorldBossBountyUnlocked(level));
        bounty.addProperty("worldBossUnlockLevel", AgentSkillTable.WORLD_BOSS_BOUNTY_UNLOCK_LEVEL);
        bounty.addProperty("weeklyAzureGranted",
                data.weeklyAzureGranted(player.getUUID(), AgentClock.currentUtcWeekStamp()));
        bounty.addProperty("weeklyAzureCap", AgentBountySavedData.WEEKLY_AZURE_SOFT_CAP);
        bounty.addProperty("dailyResetRemainingSeconds", secondsUntilNextUtcDay());
        bounty.addProperty("weeklyResetRemainingSeconds", secondsUntilNextIsoWeek());

        BountyRewardTable table = AgentBountyConfig.table();
        BountyDefinition order = BountyGenerator.worldBossOrder(table);
        JsonObject worldBoss = new JsonObject();
        worldBoss.addProperty("unlocked", AgentSkillTable.isWorldBossBountyUnlocked(level));
        worldBoss.addProperty("minStar", order.minStar());
        worldBoss.addProperty("creditReward", order.creditReward());
        worldBoss.addProperty("xpReward", order.xpReward());
        worldBoss.addProperty("azureReward", order.azureReward());

        if (!available) {
            bounty.addProperty("dailyAccepted", 0);
            bounty.addProperty("weeklyAccepted", 0);
            bounty.addProperty("pendingAzure", 0L);
            bounty.add("daily", new JsonArray());
            bounty.add("weekly", new JsonArray());
            worldBoss.addProperty("completedThisWeek", 0);
            bounty.add("worldBossOrder", worldBoss);
            return bounty;
        }

        BountyBoard board = AgentBountyService.board(player);
        bounty.addProperty("dailyAccepted", BountyBoard.acceptedCount(board.daily()));
        bounty.addProperty("weeklyAccepted", BountyBoard.acceptedCount(board.weekly()));
        bounty.addProperty("pendingAzure", board.pendingAzure());
        bounty.add("daily", entriesJson(board.daily()));
        bounty.add("weekly", entriesJson(board.weekly()));
        worldBoss.addProperty("completedThisWeek", board.worldBossOrders());
        bounty.add("worldBossOrder", worldBoss);
        return bounty;
    }

    private static JsonArray entriesJson(List<BountyProgress> list) {
        JsonArray array = new JsonArray();
        for (BountyProgress progress : list) {
            BountyDefinition def = progress.definition();
            JsonObject json = new JsonObject();
            json.addProperty("bountyId", def.id());
            json.addProperty("period", def.period().name());
            json.addProperty("targetType", def.targetType().name());
            json.addProperty("minStar", def.minStar());
            if (def.targetPool() == null) {
                json.add("targetPool", JsonNull.INSTANCE);
            } else {
                json.addProperty("targetPool", def.targetPool().name());
            }
            json.addProperty("requiredCount", def.requiredCount());
            json.addProperty("killCount", progress.killCount());
            json.addProperty("accepted", progress.accepted());
            json.addProperty("completed", progress.isComplete());
            json.addProperty("creditReward", def.creditReward());
            json.addProperty("xpReward", def.xpReward());
            json.addProperty("azureReward", def.azureReward());
            array.add(json);
        }
        return array;
    }

    /** 距下一个 UTC 零点的秒数 (日常翻期)。 */
    static long secondsUntilNextUtcDay() {
        long now = Instant.now().getEpochSecond();
        return SECONDS_PER_DAY - Math.floorMod(now, SECONDS_PER_DAY);
    }

    /** 距下一个 ISO 周一 UTC 零点的秒数 (周常翻期, 与 {@link AgentClock#currentUtcWeekStamp} 同一周历)。 */
    static long secondsUntilNextIsoWeek() {
        Instant now = Instant.now();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return nextMonday.atStartOfDay(ZoneOffset.UTC).toEpochSecond() - now.getEpochSecond();
    }
}
