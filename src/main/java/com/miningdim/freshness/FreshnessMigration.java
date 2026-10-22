package com.miningdim.freshness;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * 开服迁移 (规格「开服迁移」): 把启用之前就存在的老货按**一条规则**补上章。
 *
 * 规则只有一条, 全部落在 {@link #migratedBatchMinute}: 让物品在**启用那一刻**正好剩
 * {@code graceHours} 小时 (默认 6), 并且把停表额度记满。于是:
 * <ul>
 *   <li>老货不会因为"存了半年"一开服就整箱烂掉 (那会直接劝退所有人);</li>
 *   <li>老货也不会比新货更值钱 —— 6 小时是"够你上线处理一次"的量, 不是保鲜;</li>
 *   <li>额度记满 + 带 {@code m} 标记 = **永不和新货合并**, 否则拿一批老货去稀释新货就能白嫖额度
 *       (反漏洞 #21: 时间胶囊)。</li>
 * </ul>
 *
 * 三个入口共用这里的判定: 区块存档里的方块实体数据 ({@link #migrateTag})、容器 ({@link #migrateContainer}、
 * 玩家背包与末影箱) 以及实体内容 (由 {@code FreshnessMigrationHooks} 逐个接口取值后调 {@link #stampMigrated})。
 * 已经带章的东西**一律不碰** —— 章就是"它已经在本系统里了"的凭据, 谁盖过章谁说了算。
 */
public final class FreshnessMigration {

    private FreshnessMigration() {
    }

    /** NBT 递归深度上限: 存档里被恶意/意外套娃的数据不该把区块加载拖住。 */
    private static final int MAX_NBT_DEPTH = 32;

    private static final String KEY_ID = "id";
    private static final String KEY_COUNT = "Count";
    private static final String KEY_TAG = "tag";

    /**
     * 迁移章的批次时刻: 启用时刻 + 宽限 − 保质期 − 停表额度。
     *
     * 按「剩余 = h + f + 保质期 − 现在」反推: 启用那一刻剩余 = 宽限小时 (h 里的 − 保质期 与公式里的
     * + 保质期 相消, − 额度 与 f = 额度 相消)。**这个式子是整个迁移的唯一正源**, 三处入口都调它。
     */
    public static long migratedBatchMinute(long enabledAtMinute, int shelfLifeHours, int graceHours,
                                           int stopCapHours) {
        return enabledAtMinute + (long) graceHours * 60L
                - (long) shelfLifeHours * 60L - (long) stopCapHours * 60L;
    }

    /**
     * 给一件**没盖过章**的易腐品盖迁移章 (批次按 {@link #migratedBatchMinute} 反推、停表额度记满、
     * 打上迁移标记)。返回是否真盖了。
     *
     * 不腐烂的东西、已经带章的东西、空栈一律不动: 前者没有保质期可算, 后两者是"已经进过系统"的货。
     */
    public static boolean stampMigrated(ItemStack stack, long enabledAtMinute, int graceHours, int stopCapHours) {
        if (stack.isEmpty() || !FreshnessCategories.spoils(stack) || FreshnessData.isStamped(stack)) {
            return false;
        }
        int shelfLifeHours = FreshnessCategories.shelfLifeHours(stack);
        FreshnessData.stampAt(stack,
                migratedBatchMinute(enabledAtMinute, shelfLifeHours, graceHours, stopCapHours), stopCapHours);
        FreshnessData.markMigrated(stack);
        return true;
    }

    /** 迁移一个容器 (玩家背包/末影箱、实体里的箱子) 的每一格; 返回迁移了几件。 */
    public static int migrateContainer(Container container, long enabledAtMinute, int graceHours, int stopCapHours) {
        if (container == null) {
            return 0;
        }
        int migrated = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (stampMigrated(container.getItem(slot), enabledAtMinute, graceHours, stopCapHours)) {
                migrated++;
            }
        }
        return migrated;
    }

    /**
     * 递归迁移一段 NBT 里的物品条目 (区块存档里方块实体的数据), 返回迁移了几件。
     *
     * 判据刻意不认任何"容器形状": 凡是**同时有 {@code id}(字符串) 与 {@code Count}** 的复合标签就当成物品条目。
     * 这样箱子、抽屉、保险库、任意层嵌套的潜影盒/篮子/包裹都能一次覆盖 —— 认形状的话, 每加一种容器就要回来
     * 改一次, 而漏掉的那种正好是走私者用的那种。
     *
     * 读不出来的条目 (id 非法、结构畸形) 直接跳过, **绝不抛**: 这段代码跑在区块加载的路径上, 一个畸形的存档
     * 条目不该让整个区块加载失败。
     */
    public static int migrateTag(Tag root, long enabledAtMinute, int graceHours, int stopCapHours) {
        return migrateTag(root, enabledAtMinute, graceHours, stopCapHours, 0);
    }

    private static int migrateTag(Tag tag, long enabledAtMinute, int graceHours, int stopCapHours, int depth) {
        if (tag == null || depth > MAX_NBT_DEPTH) {
            return 0;
        }
        if (tag instanceof ListTag list) {
            int migrated = 0;
            for (int i = 0; i < list.size(); i++) {
                migrated += migrateTag(list.get(i), enabledAtMinute, graceHours, stopCapHours, depth + 1);
            }
            return migrated;
        }
        if (!(tag instanceof CompoundTag compound)) {
            return 0;
        }
        int migrated = 0;
        if (looksLikeItemEntry(compound)) {
            migrated += migrateEntry(compound, enabledAtMinute, graceHours, stopCapHours);
        }
        // 条目自己的内容物也要走一遍 (潜影盒里的东西在 item.tag.BlockEntityTag.Items 下面)。
        for (String key : compound.getAllKeys().toArray(new String[0])) {
            migrated += migrateTag(compound.get(key), enabledAtMinute, graceHours, stopCapHours, depth + 1);
        }
        return migrated;
    }

    /** 物品条目 = 有字符串 id 与 Count; 方块实体自己的 {@code id} 没有 Count, 因此不会被误判。 */
    private static boolean looksLikeItemEntry(CompoundTag compound) {
        return compound.contains(KEY_ID, Tag.TAG_STRING) && compound.contains(KEY_COUNT);
    }

    private static int migrateEntry(CompoundTag entry, long enabledAtMinute, int graceHours, int stopCapHours) {
        ItemStack stack;
        try {
            stack = ItemStack.of(entry);
        } catch (RuntimeException malformed) {
            return 0; // id 非法等畸形数据: 当作不是物品, 不抛
        }
        if (!stampMigrated(stack, enabledAtMinute, graceHours, stopCapHours)) {
            return 0;
        }
        // 只回写 tag: id/Count/Slot 这些键是别的代码写的, 这里不动它们。
        entry.put(KEY_TAG, stack.getTag());
        return 1;
    }
}
