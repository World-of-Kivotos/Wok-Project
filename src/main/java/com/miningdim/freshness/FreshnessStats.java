package com.miningdim.freshness;

import net.minecraft.world.item.ItemStack;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/** 进程级结算计数; 只在服务端主线程读写。 */
public final class FreshnessStats {

    private static long stamped;
    private static long wouldRot;
    private static long rotted;
    private static final ReferenceQueue<ItemStack> STALE_STACKS = new ReferenceQueue<>();
    private static final Map<StackReference, Integer> COUNTED_DRY_RUN_STACKS = new HashMap<>();

    private FreshnessStats() {
    }

    /** 盖章件数。 */
    public static void recordStamped(long count) {
        stamped += count;
    }

    /** 兼容单件调用口。 */
    public static void recordStamped() {
        recordStamped(1L);
    }

    /** 新发现的试运行过期栈按件数记一次, 同一存活 ItemStack 的重复扫描会被忽略。 */
    public static void recordWouldRot(ItemStack stack) {
        purgeStaleStacks();
        StackReference key = new StackReference(stack, null);
        Integer previousCount = COUNTED_DRY_RUN_STACKS.get(key);
        int count = stack.getCount();
        if (previousCount == null) {
            COUNTED_DRY_RUN_STACKS.put(new StackReference(stack, STALE_STACKS), count);
            wouldRot += count;
        } else if (count > previousCount) {
            wouldRot += count - previousCount;
            COUNTED_DRY_RUN_STACKS.put(key, count);
        } else if (count < previousCount) {
            COUNTED_DRY_RUN_STACKS.put(key, count);
        }
    }

    /** 兼容直接计单件的调用口。 */
    public static void recordWouldRot() {
        wouldRot++;
    }

    /** 实际替换的件数。 */
    public static void recordRotted(long count) {
        rotted += count;
    }

    /** 兼容单件调用口。 */
    public static void recordRotted() {
        recordRotted(1L);
    }

    /** 取走并清零本小时计数; 试运行去重状态跨小时保留。 */
    public static Snapshot drain() {
        Snapshot snapshot = new Snapshot(stamped, wouldRot, rotted);
        clearCounters();
        return snapshot;
    }

    /** 看一眼不清零。 */
    public static Snapshot peek() {
        return new Snapshot(stamped, wouldRot, rotted);
    }

    /** 清零计数但保留跨小时去重状态。 */
    public static void drainCounters() {
        clearCounters();
    }

    /** 清零计数与去重状态 (GameTest 复位)。 */
    public static void resetForTest() {
        clearCounters();
        COUNTED_DRY_RUN_STACKS.clear();
        while (STALE_STACKS.poll() != null) {
            // Drain stale identity references before the next test batch.
        }
    }

    private static void clearCounters() {
        stamped = 0L;
        wouldRot = 0L;
        rotted = 0L;
    }

    private static void purgeStaleStacks() {
        StackReference stale;
        while ((stale = (StackReference) STALE_STACKS.poll()) != null) {
            COUNTED_DRY_RUN_STACKS.remove(stale);
        }
    }

    private static final class StackReference extends WeakReference<ItemStack> {
        private final int identityHash;

        private StackReference(ItemStack stack, ReferenceQueue<ItemStack> queue) {
            super(stack, queue);
            identityHash = System.identityHashCode(stack);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof StackReference reference)) {
                return false;
            }
            ItemStack stack = get();
            return stack != null && stack == reference.get();
        }
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
