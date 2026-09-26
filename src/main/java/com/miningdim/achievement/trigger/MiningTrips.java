package com.miningdim.achievement.trigger;

import com.miningdim.core.Difficulty;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 矿区"行程"状态机 (Achievement_System_DesignSpec 6.3): 进入矿区开一段行程, 途中记下死亡、计数挖掘与被怪物或陷阱命中的
 * 时刻, 离开时判定是否为一次<b>有效撤离</b>。事件接线在 {@link MiningTripHooks}, 本类只管状态, 不碰世界与玩家对象,
 * 因此判定逻辑可以脱离矿区维度直接测。
 *
 * <p>计时一律用主世界的 gameTime: 玩家的 tickCount 重登归零, 而主世界时钟是全服唯一口径。状态只在进程内, 不持久化:
 * 行程中下线直接作废 (重连回到矿区里从头计时), 停服时清空。做法参照任务模块的 QuestMiningVisits, 但本模块不引用任务包,
 * 阈值也只读自己的配置。
 *
 * <p>线程: 只在服务端主线程读写 (维度切换、死亡、受伤、方块破坏都在主线程); 用 ConcurrentHashMap 只为容忍停服清理时的可见性。
 */
public final class MiningTrips {

    /** 本次行程没被怪物或陷阱打过时的"距上次命中"值, 与 mining_extraction 触发器的约定一致。 */
    static final long NEVER_HIT = Long.MAX_VALUE;

    private static final Map<UUID, Trip> TRIPS = new ConcurrentHashMap<>();

    private MiningTrips() {
    }

    /** 一段在途的行程。只在主线程改动, 不对外暴露。 */
    private static final class Trip {
        final Difficulty difficulty;
        final long enterTick;
        boolean died;
        int countedBlocks;
        long lastCountedBreakTick;
        long gapTicks;
        long lastThreatHitTick;
        boolean threatHit;

        Trip(Difficulty difficulty, long enterTick) {
            this.difficulty = difficulty;
            this.enterTick = enterTick;
        }
    }

    /**
     * 一次有效撤离的结算产物。
     *
     * @param difficulty          行程的难度 (进入时记下的)
     * @param hardActiveTicks     本次行程可计入困难作业时长的 tick 数: 相邻两次计数挖掘的间隔 (单次已封顶) 之和, 且不超过
     *                            计数挖掘数 × 每块上限 (6.1)。只有困难行程才会用到
     * @param ticksSinceThreatHit 撤离时距最近一次被怪物或陷阱命中的 tick 数; 整趟没被打过为 {@link #NEVER_HIT}
     */
    record Extraction(Difficulty difficulty, long hardActiveTicks, long ticksSinceThreatHit) {
    }

    /** 开一段新行程 (覆盖同一玩家的旧行程: 重新进入或重连都从头计时)。 */
    static void open(UUID player, Difficulty difficulty, long nowTick) {
        TRIPS.put(player, new Trip(difficulty, nowTick));
    }

    /** 丢弃在途行程而不结算 (下线、死后重生)。 */
    static void discard(UUID player) {
        TRIPS.remove(player);
    }

    /** 标记本趟死亡: 之后即使走出矿区也不算撤离。没有在途行程时什么都不做。 */
    static void markDied(UUID player) {
        Trip trip = TRIPS.get(player);
        if (trip != null) {
            trip.died = true;
        }
    }

    /** 记一次被怪物或陷阱命中。没有在途行程时什么都不做。 */
    static void recordThreatHit(UUID player, long nowTick) {
        Trip trip = TRIPS.get(player);
        if (trip != null) {
            trip.threatHit = true;
            trip.lastThreatHitTick = nowTick;
        }
    }

    /**
     * 记一次计数挖掘。与上一次计数挖掘的间隔按 gapCapTicks 封顶后累加 (第一次没有"上一次", 不计间隔);
     * 时钟倒退 (比如 /time 调小) 时这一段间隔记 0, 不让负数冲减已累计的时长。
     */
    static void recordCountedBreak(UUID player, long nowTick, long gapCapTicks) {
        Trip trip = TRIPS.get(player);
        if (trip == null) {
            return;
        }
        if (trip.countedBlocks > 0) {
            long gap = Math.max(0L, nowTick - trip.lastCountedBreakTick);
            trip.gapTicks += Math.min(gap, gapCapTicks);
        }
        trip.countedBlocks++;
        trip.lastCountedBreakTick = nowTick;
    }

    /**
     * 结算并摘掉在途行程。无论结果如何记录都会被摘掉: 一趟就是一趟, 没达标也不能留着等下次出矿区再判。
     *
     * @return 有效撤离的结算产物; 没有行程、途中阵亡、停留不足 (含时钟倒退)、计数挖掘不足时为 null
     */
    static Extraction finish(UUID player, long nowTick, long minDwellTicks, int minBlocks, long perBlockCapTicks) {
        Trip trip = TRIPS.remove(player);
        if (trip == null || trip.died) {
            return null;
        }
        long dwell = nowTick - trip.enterTick;
        if (dwell < 0L || dwell < minDwellTicks || trip.countedBlocks < minBlocks) {
            return null;
        }
        long hardActiveTicks = Math.min(trip.gapTicks, trip.countedBlocks * perBlockCapTicks);
        long sinceThreat = trip.threatHit ? Math.max(0L, nowTick - trip.lastThreatHitTick) : NEVER_HIT;
        return new Extraction(trip.difficulty, hardActiveTicks, sinceThreat);
    }

    /** 玩家当前是否有在途行程 (GameTest 断言用)。 */
    static boolean isOpen(UUID player) {
        return TRIPS.containsKey(player);
    }

    /** 在途行程已记的计数挖掘数; 没有行程为 -1 (GameTest 断言用)。 */
    static int countedBlocks(UUID player) {
        Trip trip = TRIPS.get(player);
        return trip == null ? -1 : trip.countedBlocks;
    }

    /** 停服时清空 (进程内瞬时状态, 不跨存档)。 */
    static void reset() {
        TRIPS.clear();
    }
}
