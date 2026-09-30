package com.miningdim.district.flan;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 同一条日志每小时至多 WARN 一次 (设计文档 20.4): 修不了的差异、父领地找不到、非服务器线程调用这类会每一轮都重复
 * 出现的问题, 第一次 WARN / ERROR, 之后一小时内降成 DEBUG, 免得刷屏。
 */
public final class LogThrottle {

    /** 一小时。 */
    public static final long WINDOW_MS = 3_600_000L;

    private final LongSupplier clock;
    private final Map<String, Long> lastLoud = new HashMap<>();

    public LogThrottle(LongSupplier clock) {
        this.clock = clock;
    }

    /** 这一条现在该不该大声记 (距上一次大声记不足一小时则否)。 */
    public synchronized boolean loud(String key) {
        long now = clock.getAsLong();
        Long previous = lastLoud.get(key);
        if (previous != null && now - previous < WINDOW_MS) {
            return false;
        }
        lastLoud.put(key, now);
        return true;
    }
}
