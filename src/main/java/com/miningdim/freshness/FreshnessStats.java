package com.miningdim.freshness;

/**
 * 进程级的结算计数 (Spoilage_Granary_DesignSpec「安全阀」: 试运行时照常盖章, 但不换腐烂物、不拦截,
 * 只把「本该腐烂多少件」记进统计)。
 *
 * {@link FreshnessSettle} 是唯一的记数点, 每小时由 {@link FreshnessModule} 排空一次写进
 * {@link FreshnessStatsRing}。只在服务端主线程读写, 所以用普通静态字段就够 (不做并发防御)。
 */
public final class FreshnessStats {

    private static long stamped;
    private static long wouldRot;
    private static long rotted;

    private FreshnessStats() {
    }

    /** 盖了一枚新章。 */
    public static void recordStamped() {
        stamped++;
    }

    /** 发现一件本该腐烂的东西, 但当前档位只统计不替换 (试运行)。 */
    public static void recordWouldRot() {
        wouldRot++;
    }

    /** 真的换掉了一件腐烂物。 */
    public static void recordRotted() {
        rotted++;
    }

    /** 取走并清零 (每小时调用一次)。 */
    public static Snapshot drain() {
        Snapshot snapshot = new Snapshot(stamped, wouldRot, rotted);
        resetForTest();
        return snapshot;
    }

    /** 看一眼不清零。 */
    public static Snapshot peek() {
        return new Snapshot(stamped, wouldRot, rotted);
    }

    /** 清零 (排空之后与 GameTest 复位用)。 */
    public static void resetForTest() {
        stamped = 0L;
        wouldRot = 0L;
        rotted = 0L;
    }

    /** 一段时间的计数。 */
    public record Snapshot(long stamped, long wouldRot, long rotted) {

        public boolean isEmpty() {
            return stamped == 0L && wouldRot == 0L && rotted == 0L;
        }

        public Snapshot plus(Snapshot other) {
            return new Snapshot(stamped + other.stamped, wouldRot + other.wouldRot, rotted + other.rotted);
        }
    }
}
