package com.miningdim.quest;

import com.miningdim.core.Difficulty;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跟踪玩家"这一趟矿洞"的在途状态, 用来判定撤离是否成功 (塔科夫撤离点)。
 *
 * <b>为什么自己跟踪而不是接 entry 子系统的钩子</b>: {@code EntrySystem.leaveToFallback} 确实是唯一的主动撤离
 * 路径, 挂在那里最直接; 但矿洞实例/区块票/重置那一整块当前正被三条未合的修复分支改动, 往里加钩子必然与它们
 * 冲突。本类只依赖两件公开事实 —— 玩家进出了矿洞维度、玩家死没死 —— 就能把"活着走出来"与"死了被抬出去"分开,
 * 不需要动 entry 一行代码。
 *
 * 状态是进程内瞬时的, 不持久化: 服务器重启时所有人本来就被踢出了矿洞, 重启后残留的"在途行程"只会是假的。
 * 玩家掉线同理直接判废 —— 掉线不是撤离。
 *
 * 线程: 只在服务端主线程读写 (维度切换与死亡事件均在主线程); 用 ConcurrentHashMap 仅为容忍停服清理与迟到
 * 事件的可见性。
 */
public final class QuestMiningVisits {

    /**
     * 一趟在途的矿洞行程。
     *
     * @param difficulty    进入时所在 region 的难度档
     * @param enterGameTime 进入时的世界游戏时间 (用世界时钟而非玩家 tickCount: 后者重登归零)
     */
    private record Visit(Difficulty difficulty, long enterGameTime, boolean died) {

        Visit markDied() {
            return new Visit(difficulty, enterGameTime, true);
        }
    }

    private static final Map<UUID, Visit> IN_FLIGHT = new ConcurrentHashMap<>();

    private QuestMiningVisits() {
    }

    /**
     * 玩家踏入矿洞维度: 开一趟新行程。
     *
     * 难度在<b>进入时</b>就记下来, 因为撤离发生时玩家已经不在矿洞里了, 那时再查区域只会查到主世界。
     */
    public static void onEnterMining(ServerPlayer player) {
        Difficulty difficulty = difficultyAt(player);
        if (difficulty == null) {
            return;
        }
        startVisit(player.getUUID(), difficulty, player.server.overworld().getGameTime());
    }

    /** 玩家在矿洞里死了: 本趟作废, 之后即使走出去也不算撤离。 */
    public static void onDiedInMining(ServerPlayer player) {
        markDied(player.getUUID());
    }

    /** 玩家掉线: 直接丢弃行程 (掉线不是撤离; 重连后从零开始算)。 */
    public static void onLoggedOut(ServerPlayer player) {
        IN_FLIGHT.remove(player.getUUID());
    }

    /**
     * 一趟行程的结算产物 (纯数据, 无世界引用)。
     *
     * 把它与 {@link QuestFacts.MiningExtraction} 分开: 后者要带 ServerPlayer, 而判定逻辑本身完全不需要玩家
     * 对象 —— 分开之后这套"死了不算 / 停留不够不算"的状态机可以脱离矿洞维度直接测。
     */
    record Extraction(Difficulty difficulty, long dwellTicks) {
    }

    /** 开一趟行程 (纯状态机入口, 供事件层与测试共用)。 */
    static void startVisit(UUID playerId, Difficulty difficulty, long enterGameTime) {
        IN_FLIGHT.put(playerId, new Visit(difficulty, enterGameTime, false));
    }

    /** 标记本趟死亡 (纯状态机入口)。无在途行程时什么都不做 —— 在矿洞外死亡与撤离判定无关。 */
    static void markDied(UUID playerId) {
        IN_FLIGHT.computeIfPresent(playerId, (id, visit) -> visit.markDied());
    }

    /**
     * 结算一趟行程 (纯状态机出口)。无论结果如何都会摘掉在途记录 —— 一趟就是一趟, 不能因为没达标就留着等下次
     * 出洞时再判一遍。
     *
     * @return 判定为成功撤离时返回结算产物; 没有记录 / 死过 / 停留不够 / 计时不可信时返回 null
     */
    static Extraction finishVisit(UUID playerId, long leaveGameTime, long minDwellTicks) {
        Visit visit = IN_FLIGHT.remove(playerId);
        if (visit == null || visit.died()) {
            return null;
        }
        long dwell = leaveGameTime - visit.enterGameTime();
        if (dwell < 0) {
            // 世界时间被 /time 调小或存档回档: 本趟无法可信计时, 判废而不是当成"停留了负数 tick"放行。
            return null;
        }
        if (dwell < minDwellTicks) {
            return null;
        }
        return new Extraction(visit.difficulty(), dwell);
    }

    /**
     * 玩家离开了矿洞维度: 结算本趟行程。
     *
     * @param minDwellTicks 系统级最短停留门槛 (反刷红线, 见 {@code QuestConfig.EXTRACTION_MIN_DWELL_TICKS})
     * @return 判定为成功撤离时返回对应事实; 死过、没记录、或停留不够时返回 null
     */
    public static QuestFacts.MiningExtraction onLeaveMining(ServerPlayer player, long minDwellTicks) {
        Extraction extraction =
                finishVisit(player.getUUID(), player.server.overworld().getGameTime(), minDwellTicks);
        if (extraction == null) {
            return null;
        }
        return new QuestFacts.MiningExtraction(player, extraction.difficulty(), extraction.dwellTicks());
    }

    /** 玩家当前是否有一趟在途行程 (诊断与测试用)。 */
    public static boolean hasVisit(ServerPlayer player) {
        return IN_FLIGHT.containsKey(player.getUUID());
    }

    /** 停服时清空 (进程内瞬时状态, 不跨存档)。 */
    public static void reset() {
        IN_FLIGHT.clear();
    }

    /**
     * 取玩家当前所在矿洞区域的难度档。
     *
     * 玩家不在矿洞维度时返回 null —— 这是正常情形 (本方法会被每次维度切换调用, 绝大多数切换与矿洞无关)。
     *
     * 不对实例门面加"是否已注入"的守卫: 玩家人已经站在矿洞维度里而实例门面没注入, 那是装配顺序破了,
     * 按 CLAUDE.md "异常必须痛" 就该在这里炸出来, 而不是静默地让所有撤离任务永远不计数。
     */
    private static Difficulty difficultyAt(ServerPlayer player) {
        if (!player.level().dimension().equals(MiningConstants.MINING_LEVEL)) {
            return null;
        }
        InstanceState region = MiningServices.instanceManager()
                .regionAt(player.blockPosition().getX(), player.blockPosition().getZ());
        return region == null ? null : region.difficulty();
    }
}
