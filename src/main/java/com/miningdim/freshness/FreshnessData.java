package com.miningdim.freshness;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * 物品上的保鲜章: {@code MiningFresh:{h: 批次时刻(UTC 分钟序号), f: 已停表小时, m: 迁移货, v: 村民货}}。
 *
 * 本类是**唯一**写这枚章的地方 (规格「物品数据与存档」点名): f = 0 不写, 数值一律 IntTag ——
 * 同一批货若因标签类型不同 (IntTag / LongTag) 就会叠不上, 原版既有的叠放比较按 tag 全等判定。
 *
 * 保质期不写进物品: 剩余一律现算 ({@code h + f×60 + 保质期×60 − 现在}), 读取时把 h 压回不晚于现在、
 * f 压回不超过 504, 所以改配置全服存量立刻按新值算。所有方法都不依赖世界对象, 便于纯逻辑测试。
 */
public final class FreshnessData {

    /** 未盖章的批次时刻。 */
    public static final long UNSET_BATCH = Long.MIN_VALUE;

    private FreshnessData() {
    }

    // ============================================================
    // 读
    // ============================================================

    /** 是否已盖过章 (只看有没有 h, 不看 m / v)。 */
    public static boolean isStamped(ItemStack stack) {
        CompoundTag root = rootOrNull(stack);
        return root != null && root.contains(FreshnessConstants.KEY_BATCH, Tag.TAG_INT);
    }

    /** 批次时刻 (分钟序号); 未盖章返回 {@link #UNSET_BATCH}。 */
    public static long batchMinute(ItemStack stack) {
        CompoundTag root = rootOrNull(stack);
        if (root == null || !root.contains(FreshnessConstants.KEY_BATCH, Tag.TAG_INT)) {
            return UNSET_BATCH;
        }
        return root.getInt(FreshnessConstants.KEY_BATCH);
    }

    /** 已停表小时 (0 到 504)。 */
    public static int stoppedHours(ItemStack stack) {
        CompoundTag root = rootOrNull(stack);
        if (root == null || !root.contains(FreshnessConstants.KEY_STOPPED, Tag.TAG_INT)) {
            return 0;
        }
        return Math.max(0, Math.min(FreshnessConstants.STOP_CAP_HOURS,
                root.getInt(FreshnessConstants.KEY_STOPPED)));
    }

    /** 开服迁移货: 永不和新货合并。 */
    public static boolean isMigrated(ItemStack stack) {
        return hasFlag(stack, FreshnessConstants.KEY_MIGRATED);
    }

    /** 村民货: 到手只剩 6 小时。 */
    public static boolean isVillagerGoods(ItemStack stack) {
        return hasFlag(stack, FreshnessConstants.KEY_VILLAGER);
    }

    /**
     * 剩余分钟 = 批次时刻 + 已停表小时 × 60 + 保质期 × 60 − 现在。
     * 未盖章的物品按「到手开始计时」算, 剩余正好是一整个保质期。
     */
    public static long remainingMinutes(ItemStack stack, int shelfLifeHours, long nowMinute) {
        long batch = batchMinute(stack);
        if (batch == UNSET_BATCH) {
            return (long) shelfLifeHours * 60L;
        }
        return batch + stoppedHours(stack) * 60L + (long) shelfLifeHours * 60L - nowMinute;
    }

    /** 剩余 ≤ 0 就算腐烂 (规格: 「剩余 ≤ 0 就算腐烂」)。 */
    public static boolean isRotten(ItemStack stack, int shelfLifeHours, long nowMinute) {
        return remainingMinutes(stack, shelfLifeHours, nowMinute) <= 0L;
    }

    // ============================================================
    // 写 (唯一 writer)
    // ============================================================

    /** 给未盖章的物品盖「现在」这一章 (取整点)。已盖章的不动。 */
    public static void stamp(ItemStack stack, long nowMinute) {
        if (isStamped(stack)) {
            return;
        }
        write(stack, wholeHour(nowMinute), stoppedHours(stack));
    }

    /** 按指定的批次时刻盖章 (来源上下文 / 开服迁移 / 村民货用)。 */
    public static void stampAt(ItemStack stack, long batchMinute, int stoppedHours) {
        write(stack, batchMinute, stoppedHours);
    }

    /** 改已停表小时 (停表结算与合并用), 自动钳到 0..504。 */
    public static void setStoppedHours(ItemStack stack, int stoppedHours) {
        long batch = batchMinute(stack);
        write(stack, batch == UNSET_BATCH ? 0L : batch, stoppedHours);
    }

    /** 打「开服迁移货」标记: 迁移货不与新货合并 (反漏洞 #53)。 */
    public static void markMigrated(ItemStack stack) {
        putFlag(stack, FreshnessConstants.KEY_MIGRATED);
    }

    /** 打「村民货」标记, 并把手上的剩余压到 6 小时 (反漏洞 #46)。 */
    public static void markVillagerGoods(ItemStack stack, long nowMinute, int shelfLifeHours) {
        putFlag(stack, FreshnessConstants.KEY_VILLAGER);
        write(stack, nowMinute - (long) (shelfLifeHours - FreshnessConstants.VILLAGER_GOODS_HOURS) * 60L,
                stoppedHours(stack));
    }

    /**
     * 读取前的钳制: 批次时刻压回不晚于现在, 已停表小时压回不超过 504 (反漏洞 #40)。
     * 坏数据一律降级, 不报错。
     */
    public static void clamp(ItemStack stack, long nowMinute) {
        CompoundTag root = rootOrNull(stack);
        if (root == null) {
            return;
        }
        if (root.contains(FreshnessConstants.KEY_BATCH, Tag.TAG_INT)
                && root.getInt(FreshnessConstants.KEY_BATCH) > nowMinute) {
            root.putInt(FreshnessConstants.KEY_BATCH, (int) nowMinute);
        }
        if (root.contains(FreshnessConstants.KEY_STOPPED, Tag.TAG_INT)
                && root.getInt(FreshnessConstants.KEY_STOPPED) > FreshnessConstants.STOP_CAP_HOURS) {
            root.putInt(FreshnessConstants.KEY_STOPPED, FreshnessConstants.STOP_CAP_HOURS);
        }
    }

    /**
     * 「除新鲜度外其它数据完全相同」——合并与输出格并堆的比较器。
     * 只忽略 h 与 f; 厨师品质、村民货标记、迁移标记照常参与比较 (反漏洞 #52、#53)。
     */
    public static boolean sameExceptFreshness(ItemStack first, ItemStack second) {
        if (first.getItem() != second.getItem()) {
            return false;
        }
        CompoundTag left = stripFreshness(first.getTag());
        CompoundTag right = stripFreshness(second.getTag());
        boolean leftEmpty = left == null || left.isEmpty();
        boolean rightEmpty = right == null || right.isEmpty();
        if (leftEmpty || rightEmpty) {
            return leftEmpty && rightEmpty;
        }
        return left.equals(right);
    }

    // ============================================================
    // 内部
    // ============================================================

    private static void write(ItemStack stack, long batchMinute, int stoppedHours) {
        CompoundTag tag = stack.getOrCreateTag();
        CompoundTag root = tag.contains(FreshnessConstants.ROOT, Tag.TAG_COMPOUND)
                ? tag.getCompound(FreshnessConstants.ROOT)
                : new CompoundTag();
        root.putInt(FreshnessConstants.KEY_BATCH, (int) batchMinute);
        int capped = Math.max(0, Math.min(FreshnessConstants.STOP_CAP_HOURS, stoppedHours));
        if (capped > 0) {
            root.putInt(FreshnessConstants.KEY_STOPPED, capped);
        } else {
            root.remove(FreshnessConstants.KEY_STOPPED);
        }
        tag.put(FreshnessConstants.ROOT, root);
    }

    private static CompoundTag stripFreshness(CompoundTag tag) {
        if (tag == null || !tag.contains(FreshnessConstants.ROOT, Tag.TAG_COMPOUND)) {
            return tag;
        }
        CompoundTag copy = tag.copy();
        CompoundTag root = copy.getCompound(FreshnessConstants.ROOT);
        root.remove(FreshnessConstants.KEY_BATCH);
        root.remove(FreshnessConstants.KEY_STOPPED);
        if (root.isEmpty()) {
            copy.remove(FreshnessConstants.ROOT);
        }
        return copy;
    }

    private static boolean hasFlag(ItemStack stack, String key) {
        CompoundTag root = rootOrNull(stack);
        return root != null && root.getBoolean(key);
    }

    private static void putFlag(ItemStack stack, String key) {
        CompoundTag tag = stack.getOrCreateTag();
        CompoundTag root = tag.contains(FreshnessConstants.ROOT, Tag.TAG_COMPOUND)
                ? tag.getCompound(FreshnessConstants.ROOT)
                : new CompoundTag();
        root.putBoolean(key, true);
        tag.put(FreshnessConstants.ROOT, root);
    }

    private static CompoundTag rootOrNull(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(FreshnessConstants.ROOT, Tag.TAG_COMPOUND)) {
            return null;
        }
        return tag.getCompound(FreshnessConstants.ROOT);
    }

    /** 盖章取整点: 批次时刻 = 该小时的第 0 分钟。 */
    public static long wholeHour(long nowMinute) {
        return Math.floorDiv(nowMinute, 60L) * 60L;
    }
}
