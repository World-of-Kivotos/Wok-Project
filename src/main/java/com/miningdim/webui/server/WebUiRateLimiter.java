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
        Bucket bucket = buckets.computeIfAbsent(player, k -> new Bucket(burstCapacity, nowNanos));
        synchronized (bucket) {
            double elapsedSeconds = (nowNanos - bucket.lastNanos) / 1_000_000_000.0;
            if (elapsedSeconds > 0) {
                bucket.tokens = Math.min(burstCapacity, bucket.tokens + elapsedSeconds * refillPerSecond);
            }
            bucket.lastNanos = nowNanos;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
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
