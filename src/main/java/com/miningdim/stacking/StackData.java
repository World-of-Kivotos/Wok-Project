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
}
