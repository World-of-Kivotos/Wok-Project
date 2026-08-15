package com.miningdim.spawn;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单实例预生成的出生点池 + 运行期占用表 (设计文档 11.3 / 11.4)。
 *
 * pool 为预扫描的世界坐标合法站立点 (anchor 始终在首位, 11.3 spawnAnchor 处理), 不可变。
 * occupiedUntil 为运行期瞬态占用表, 带 TTL: 多玩家并发取点经主线程串行 + 占用标记避免叠人 (11.4)。
 * 占用状态的唯一真源是 occupiedUntil, 不另存重复状态。occupiedUntil 不持久化, 实例卸载/重置时
 * 随本对象一并丢弃; 条目数被池容量封顶, 不存在无界增长, 无需额外清理。
 *
 * 线程纪律 (D8): 取点 (claim) 只在服务端主线程串行调用, 单线程天然互斥 (11.4)。occupiedUntil 用并发
 * 集合仅为防御调试侧只读, 不替代主线程串行。
 */
public final class SpawnPool {

    private final List<BlockPos> pool;

    /** 占用点 -> 占用截止 tick (TTL)。到期自动可复用 (11.4 占用 TTL 后释放)。 */
    private final ConcurrentHashMap<BlockPos, Long> occupiedUntil = new ConcurrentHashMap<>();

    public SpawnPool(List<BlockPos> pool) {
        this.pool = List.copyOf(pool);
    }

    /** anchor (pool 首位); pool 为空时为 null (调用方据此触发兜底平台 11.5)。 */
    public BlockPos anchor() {
        return pool.isEmpty() ? null : pool.get(0);
    }

    public int size() {
        return pool.size();
    }

    public boolean isEmpty() {
        return pool.isEmpty();
    }

    public List<BlockPos> points() {
        return pool;
    }

    /**
     * 原子取点 (11.4): 从指定随机偏移起顺序找首个未占用且 TTL 已过期的点, 占用后返回。
     * 必须在主线程调用 (串行即互斥)。全部被占用返回 null, 调用方做微扰/兜底 (11.4 池耗尽)。
     *
     * @param startIndex 随机起点下标 (确定性可用 deriveInt, 运行期非确定性随机亦可, 11.3)
     * @param currentTick server game time (用于 TTL 过期判定)
     * @param ttlTicks   占用 TTL (tick)
     */
    public BlockPos claim(int startIndex, long currentTick, int ttlTicks) {
        if (pool.isEmpty()) {
            return null;
        }
        int n = pool.size();
        int start = Math.floorMod(startIndex, n);
        for (int i = 0; i < n; i++) {
            BlockPos pos = pool.get((start + i) % n);
            Long until = occupiedUntil.get(pos);
            boolean free = until == null || currentTick >= until;
            if (free) {
                occupiedUntil.put(pos, currentTick + ttlTicks);
                return pos;
            }
        }
        return null;
    }

    /** 显式占用一个 (兜底平台中心) 点 (11.5 step3 纳入 occupiedSpawns)。 */
    public void reserve(BlockPos pos, long currentTick, int ttlTicks) {
        occupiedUntil.put(pos, currentTick + ttlTicks);
    }

    /**
     * 查某坐标当前是否仍在占用 TTL 内 (F034 复核修正): 与 {@link #claim} 不同, 不要求 pos 属于
     * {@link #pool} 列表 —— 供兜底平台去重使用 (兜底候选点本就不在预扫描池里, 见 SpawnSystem)。
     */
    public boolean isOccupied(BlockPos pos, long currentTick) {
        Long until = occupiedUntil.get(pos);
        return until != null && currentTick < until;
    }

    /** 主动释放占用 (玩家离开/超时清理); 不强制, TTL 也会自然过期。 */
    public void release(BlockPos pos) {
        occupiedUntil.remove(pos);
    }

    /** 调试/测试: 当前尚未过期的占用数 (currentTick < until)。 */
    public int occupiedCount(long currentTick) {
        int count = 0;
        for (long until : occupiedUntil.values()) {
            if (currentTick < until) {
                count++;
            }
        }
        return count;
    }
}
