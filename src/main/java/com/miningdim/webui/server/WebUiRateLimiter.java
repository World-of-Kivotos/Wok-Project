package com.miningdim.webui.server;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 每玩家令牌桶限流器 (F008)。时间由调用方以 {@code nowNanos} 传入, 类内部不读时钟 —— 测试因此能把时间当参数
 * 驱动, 不必靠 sleep 制造真实流逝。
 *
 * 并发注释与 {@code WebUiServerDispatcher.PROCESSED_REQUEST_IDS} 同款理由: 派发主路径在服务器主线程
 * (C2SWebUiRequest.handle 经 enqueueWork 切回主线程), 故同玩家两次 tryAcquire 无真实并发; 但玩家登出清理与
 * 登出竞态下的迟到包可能跨线程触达本表, 故 outer map 用 {@link ConcurrentHashMap}, 对单个玩家令牌桶
 * (非线程安全的可变字段) 的读改在 {@code synchronized (bucket)} 内完成保证原子。
 */
final class WebUiRateLimiter {

    private final int burstCapacity;
    private final double refillPerSecond;
    private final ConcurrentMap<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    WebUiRateLimiter(int burstCapacity, double refillPerSecond) {
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("burstCapacity must be positive: " + burstCapacity);
        }
        if (refillPerSecond <= 0) {
            throw new IllegalArgumentException("refillPerSecond must be positive: " + refillPerSecond);
        }
        this.burstCapacity = burstCapacity;
        this.refillPerSecond = refillPerSecond;
    }

    /**
     * 尝试为该玩家扣一枚令牌。新建的桶初始为满桶 (玩家进服第一下不该被限)。补充按
     * {@code (nowNanos - lastNanos) / 1e9 * refillPerSecond} 计算并钳到 {@link #burstCapacity};
     * 无论扣减成功与否都推进 {@code lastNanos}, 否则被拒绝的高频请求会让补充窗口原地冻结, 永远算不出新令牌。
     */
    boolean tryAcquire(UUID player, long nowNanos) {
        return tryAcquire(player, nowNanos, 1);
    }

    /**
     * 扣 {@code cost} 枚令牌 —— 供 {@code system.batch} 按真实条数计费。
     *
     * 必须按条数计费, 否则聚合请求就是一个限流旁路: 一批 24 条只付一枚令牌, 等于把每玩家上限凭空放大 24 倍,
     * 而那 24 个 handler 在服务器主线程上是实打实各跑一次的。
     *
     * 令牌不足时<b>一枚也不扣</b> (全有或全无), 不做部分扣减: 半批执行没有任何调用方能处理 —— 前端拿到
     * "一半成功一半失败"与拿到"整批被限流"是两种完全不同的恢复路径, 而后者才是它真正实现了的那一条。
     */
    boolean tryAcquire(UUID player, long nowNanos, int cost) {
        if (cost <= 0) {
            throw new IllegalArgumentException("cost must be positive: " + cost);
        }
        Bucket bucket = buckets.computeIfAbsent(player, k -> new Bucket(burstCapacity, nowNanos));
        synchronized (bucket) {
            double elapsedSeconds = (nowNanos - bucket.lastNanos) / 1_000_000_000.0;
            if (elapsedSeconds > 0) {
                bucket.tokens = Math.min(burstCapacity, bucket.tokens + elapsedSeconds * refillPerSecond);
            }
            bucket.lastNanos = nowNanos;
            if (bucket.tokens >= cost) {
                bucket.tokens -= cost;
                return true;
            }
            return false;
        }
    }

    /** 移除该玩家的令牌桶 (登出清理, 防止离线玩家的桶长期驻留造成内存泄漏)。 */
    void clear(UUID player) {
        buckets.remove(player);
    }

    private static final class Bucket {
        double tokens;
        long lastNanos;

        Bucket(int initialTokens, long lastNanos) {
            this.tokens = initialTokens;
            this.lastNanos = lastNanos;
        }
    }
}
