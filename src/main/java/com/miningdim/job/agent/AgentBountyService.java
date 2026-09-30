package com.miningdim.job.agent;

import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyServices;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 特勤悬赏的服务端编排 (SpecialAgent_Job_DesignSpec 7.2 / 10.5 / 十一章): 取板、接取、击杀推进、发奖。
 *
 * 纯规则在 {@link BountyBoard} / {@link BountyGenerator} / {@link BountyDefinition}, 本类只负责把它们接到玩家、存档、
 * 经济与经验上。所有入口仅服务端主线程调用。
 *
 * 发奖口径 (每张悬赏完成那一刻自动发, 不设"领取"按钮 —— 领取按钮带来的只有"忘了领就翻期作废"和"领取时机套利"):
 *  - 信用点: {@code grantDaily} 并入全服每人每日信用点主闸 ({@code credit_faucet}), 与矿工卖矿、加强奖励共享衰减
 *    (十一章 DECIDED)。悬赏虽然也受槽位硬封, 但它的供给随职业等级涨到每天 5 + 每周 3 张, 不援引任务模块的独立键例外。
 *  - 经验: {@link AgentLevels#grantRawXp}, 走职业框架经验软上限。
 *  - 青辉石: 先过本周悬赏青辉石软上限 ({@link AgentBountySavedData#tryGrantWeeklyAzure}), 再经与精英掉落共享的
 *    每人每日硬上限入账; 日上限截掉的部分记进板上的待补发量, 登录或下次完成悬赏时补发 (见 {@link BountyBoard})。
 */
public final class AgentBountyService {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/agent/bounty");

    private AgentBountyService() {
    }

    /** 接取的结果: 结果码 + 本次是否让该玩家首次入职。 */
    public record AcceptResult(BountyBoard.AcceptOutcome outcome, boolean newlyActiveAgent) {
    }

    /**
     * 取该玩家的悬赏板, 并按当前等级与时钟翻期 / 补掷。会为从没碰过悬赏板的玩家建板, 故只应由玩家主动打开面板、
     * 接取这类入口调用; 击杀结算走 {@link #onQualifiedKill}, 那里不建板。
     */
    public static BountyBoard board(ServerPlayer player) {
        AgentBountySavedData data = data(player);
        BountyBoard board = data.board(player.getUUID());
        if (board.refresh(AgentClock.currentUtcDayStamp(), AgentClock.currentUtcWeekStamp(),
                AgentLevels.agentLevel(player), AgentBountyConfig.table(), player.getRandom())) {
            data.setDirty();
        }
        return board;
    }

    /**
     * 接取一张悬赏。成功即置位入职标志 (7.0, 2026-09-30 拍板: 接第一张悬赏就入职), 此后加强奖励与对精英增伤生效。
     */
    public static AcceptResult accept(ServerPlayer player, BountyDefinition.Period period, String bountyId) {
        if (!AgentBountyConfig.enabled()) {
            return new AcceptResult(BountyBoard.AcceptOutcome.DISABLED, false);
        }
        AgentBountySavedData data = data(player);
        BountyBoard board = board(player);
        BountyBoard.AcceptOutcome outcome = board.accept(period, bountyId, AgentLevels.agentLevel(player));
        boolean newlyActive = false;
        if (outcome == BountyBoard.AcceptOutcome.OK) {
            data.setDirty();
            newlyActive = data.markActiveAgent(player.getUUID());
        }
        return new AcceptResult(outcome, newlyActive);
    }

    /**
     * 一名合格击杀者 (达入池门槛、在线) 的一次精英击杀: 推进其已接悬赏, 刚完成的立即发奖。
     * 没有悬赏板的玩家直接跳过 —— 不接悬赏就没有可推进的东西, 也不该为打精英的路人凭空建板。
     */
    public static void onQualifiedKill(ServerPlayer player, BountyKill kill) {
        if (!AgentBountyConfig.enabled()) {
            return;
        }
        AgentBountySavedData data = data(player);
        BountyBoard board = data.existingBoard(player.getUUID());
        if (board == null) {
            return;
        }
        // 先翻期再记: 跨过 UTC 零点后的第一只击杀不能记到昨天那张已作废的日常上。
        boolean dirty = board.refresh(AgentClock.currentUtcDayStamp(), AgentClock.currentUtcWeekStamp(),
                AgentLevels.agentLevel(player), AgentBountyConfig.table(), player.getRandom());
        int before = progressSum(board);
        List<BountyDefinition> completed = board.recordKill(kill);
        dirty |= progressSum(board) != before;
        for (BountyDefinition def : completed) {
            pay(player, def, board, data);
        }
        if (dirty || !completed.isEmpty()) {
            data.setDirty();
        }
    }

    /**
     * 世界 BOSS 讨伐令 (L8+ 已入职干员; 不占槽位、无需接取)。世界 BOSS 由管理员指令召唤, 出现时间不定, 每只被击倒时
     * 对每名达入池门槛的合格干员各结算一次。
     *
     * @return 是否发放了讨伐令 (等级或入职不满足时为 false)
     */
    public static boolean onWorldBossKill(ServerPlayer player) {
        if (!AgentBountyConfig.enabled()) {
            return false;
        }
        if (!AgentSkillTable.isWorldBossBountyUnlocked(AgentLevels.agentLevel(player))) {
            return false;
        }
        AgentBountySavedData data = data(player);
        if (!data.isActiveAgent(player.getUUID())) {
            return false;
        }
        BountyBoard board = board(player);
        board.recordWorldBossOrder();
        pay(player, BountyGenerator.worldBossOrder(AgentBountyConfig.table()), board, data);
        data.setDirty();
        return true;
    }

    /** 补发日上限截掉的悬赏青辉石 (登录时调用; 完成悬赏时 {@link #pay} 也会顺带补)。 */
    public static void flushPendingAzure(ServerPlayer player) {
        AgentBountySavedData data = data(player);
        BountyBoard board = data.existingBoard(player.getUUID());
        if (board == null || board.pendingAzure() <= 0L || !EconomyServices.isRegistered()) {
            return;
        }
        long paid = EconomyServices.economyService().grantAzureDaily(player, board.pendingAzure(),
                EconomyConstants.AZURE_DAILY_FAUCET_CAP);
        if (paid > 0L) {
            board.settlePendingAzure(paid);
            data.setDirty();
        }
    }

    private static void pay(ServerPlayer player, BountyDefinition def, BountyBoard board, AgentBountySavedData data) {
        if (!EconomyServices.isRegistered()) {
            // 经济门面未就绪只会出现在启动早期, 那时不可能有精英被击杀; 真出现就是装配顺序坏了, 留日志别静默吞。
            LOGGER.error("bounty {} completed by {} but economy is not registered; reward not paid",
                    def.id(), player.getGameProfile().getName());
            return;
        }
        long credit = 0L;
        if (def.creditReward() > 0L) {
            credit = EconomyServices.economyService().grantDaily(player, def.creditReward(),
                    EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY,
                    EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER);
        }
        long xp = 0L;
        if (def.xpReward() > 0L) {
            xp = AgentLevels.grantRawXp(player, def.xpReward());
        }
        long azureQueued = 0L;
        if (def.azureReward() > 0L) {
            azureQueued = data.tryGrantWeeklyAzure(player.getUUID(), def.azureReward(),
                    AgentClock.currentUtcWeekStamp());
            board.addPendingAzure(azureQueued);
        }
        flushPendingAzure(player);
        LOGGER.info("bounty-complete player={} bounty={} {} star>={} credit={} xp={} azureQueued={} pendingAzure={}",
                player.getGameProfile().getName(), def.id(), def.targetType(), def.minStar(), credit, xp,
                azureQueued, board.pendingAzure());
    }

    private static int progressSum(BountyBoard board) {
        int sum = 0;
        for (BountyProgress progress : board.daily()) {
            sum += progress.killCount();
        }
        for (BountyProgress progress : board.weekly()) {
            sum += progress.killCount();
        }
        return sum;
    }

    private static AgentBountySavedData data(ServerPlayer player) {
        return AgentBountySavedData.get(player.server.overworld());
    }
}
