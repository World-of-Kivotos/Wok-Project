package com.miningdim.stacking;

import net.minecraft.world.entity.Entity;

/**
 * 实体堆叠数的读写工具 (需求规格 NFR-6 持久化; AC-8)。
 *
 * 堆叠数存在 {@link Entity#getPersistentData()} 的 {@value #KEY} 键 (int, 恒 >=1)。该 CompoundTag 是 Forge 提供的
 * 随实体 NBT 自动落盘的字段: 区块卸载/重载、服务端重启后随实体 save/load 往返不丢 (AC-8), 无需自建 SavedData。
 *
 * 不存在该键 (普通未堆叠实体) 语义上等价于堆叠数 1 —— 一个实体即代表它自己。{@link #getStackSize} 对缺键返回 1,
 * 故 "首次合并前" 与 "堆叠数 1" 表现一致, 调用方无需区分。setStackSize 对 <1 的入参抛 IllegalArgumentException
 * (堆叠数为 0 的实体是非法态: 它要么有至少 1 个个体, 要么应被 discard, 异常自然冒泡不掩盖)。
 */
public final class StackData {

    private StackData() {
    }

    /** 持久化键 (命名空间前缀避免与其它 mod / 原版 persistentData 撞键)。 */
    public static final String KEY = "miningdim:StackSize";

    /**
     * 读堆叠数。缺键返回 1 (未堆叠实体即代表自身一个个体)。
     */
    public static int getStackSize(Entity entity) {
        if (!entity.getPersistentData().contains(KEY)) {
            return 1;
        }
        return entity.getPersistentData().getInt(KEY);
    }

    /** 是否已带堆叠标记 (即已写过 KEY)。用于区分 "从未参与合并" 与 "堆叠数恰为 1"。 */
    public static boolean hasStackData(Entity entity) {
        return entity.getPersistentData().contains(KEY);
    }

    /**
     * 写堆叠数。size 必须 >=1; <1 非法 (堆叠数 0 的实体应 discard 而非保留, 见类注释)。
     *
     * @throws IllegalArgumentException 当 size < 1
     */
    public static void setStackSize(Entity entity, int size) {
        if (size < 1) {
            throw new IllegalArgumentException("stack size must be >= 1, got " + size);
        }
        entity.getPersistentData().putInt(KEY, size);
    }

    /**
     * 堆叠数自增 delta (delta 可为负以剥离个体, 如阶段 2 one_per_kill)。结果须 >=1, 否则非法 (调用方应在结果到 0
     * 前 discard 实体)。
     *
     * @return 自增后的新堆叠数
     * @throws IllegalArgumentException 当自增结果 < 1
     */
    public static int incr(Entity entity, int delta) {
        int next = getStackSize(entity) + delta;
        setStackSize(entity, next);
        return next;
    }

    /** 拆分保护期持久化键 (FR-5.1; {@link StackSplit#splitOne})。 */
    public static final String NO_MERGE_UNTIL_KEY = "miningdim:StackNoMergeUntil";

    /**
     * 读拆分保护期截止的绝对 gameTime。缺键返回 0 (即 "无保护期", 因为合法 gameTime 恒 >=0 且调用方总用
     * {@code level.getGameTime() < until} 判定, 0 必然已过期)。
     *
     * 用绝对 gameTime 而非倒计时 tick: 实体可能在保护期内被卸载 (区块 unload) 又重载, 绝对时刻随 NBT 落盘后语义
     * 仍正确 (重载后直接与新的当前 gameTime 比较即可); 倒计时则需要每 tick 主动递减, 卸载期间无法递减会导致
     * 保护期在实体离线时不消耗或语义漂移。
     */
    public static long getNoMergeUntil(Entity entity) {
        if (!entity.getPersistentData().contains(NO_MERGE_UNTIL_KEY)) {
            return 0L;
        }
        return entity.getPersistentData().getLong(NO_MERGE_UNTIL_KEY);
    }

    /** 写拆分保护期截止的绝对 gameTime (调用方传 {@code level.getGameTime() + graceTicks})。 */
    public static void setNoMergeUntil(Entity entity, long gameTime) {
        entity.getPersistentData().putLong(NO_MERGE_UNTIL_KEY, gameTime);
    }

    /**
     * 清除本系统在该实体上写入的全部 persistentData 键 (堆叠数 + 拆分保护期)。供旧存档消毒用
     * (见 {@link StackingSystem#onEntityJoinLevel}): 白名单化后, 非白名单实体身上残留的旧版堆叠数据须整体清除,
     * 而非只清其中一个键。
     */
    public static void clearStackData(Entity entity) {
        entity.getPersistentData().remove(KEY);
        entity.getPersistentData().remove(NO_MERGE_UNTIL_KEY);
    }
}
