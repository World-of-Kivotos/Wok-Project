package com.miningdim.spawn;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单实例预生成的出生点池 + 运行期占用表 (设计文档 11.3 / 11.4)。
 *
 * pool 为预扫描的世界坐标合法站立点 (anchor 始终在首位, 11.3 spawnAnchor 处理), 不可变。
 * occupied 为运行期瞬态占用表, 带 TTL: 多玩家并发取点经主线程串行 + 占用标记避免叠人 (11.4)。
 * occupied 不持久化, 实例卸载/重置时随本对象一并丢弃。
 *
 * 线程纪律 (D8): 取点 (claim) 只在服务端主线程串行调用, 单线程天然互斥 (11.4)。occupied 用并发集合
 * 仅为防御调试侧只读, 不替代主线程串行。
 */
public final class SpawnPool {

    private final List<BlockPos> pool;

    /** 占用点 -> 占用截止 tick (TTL)。到期自动可复用 (11.4 占用 TTL 后释放)。 */
    private final Set<BlockPos> occupied = ConcurrentHashMap.newKeySet();
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
                occupied.add(pos);
                occupiedUntil.put(pos, currentTick + ttlTicks);
                return pos;
            }
        }
        return null;
    }

    /** 显式占用一个 (兜底平台中心) 点 (11.5 step3 纳入 occupiedSpawns)。 */
    public void reserve(BlockPos pos, long currentTick, int ttlTicks) {
        occupied.add(pos);
        occupiedUntil.put(pos, currentTick + ttlTicks);
    }

    /** 主动释放占用 (玩家离开/超时清理); 不强制, TTL 也会自然过期。 */
    public void release(BlockPos pos) {
        occupied.remove(pos);
        occupiedUntil.remove(pos);
    }

    /** 清理已过期占用条目, 避免 map 无界增长 (在 region tick 末调用)。 */
    public void pruneExpired(long currentTick) {
        List<BlockPos> expired = new ArrayList<>();
        for (var e : occupiedUntil.entrySet()) {
            if (currentTick >= e.getValue()) {
                expired.add(e.getKey());
            }
        }
        for (BlockPos pos : expired) {
            occupied.remove(pos);
            occupiedUntil.remove(pos);
        }
    }

    /** 调试: 当前占用数。 */
    public int occupiedCount() {
        return occupied.size();
    }
}
