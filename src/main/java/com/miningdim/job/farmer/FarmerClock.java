package com.miningdim.job.farmer;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * UTC 翻日时钟 (JobFramework_Shared_Foundation_DesignSpec 第四章)。
 *
 * 农夫卖菜每日收购计数 (经济衰减曲线) 必须与职业经验每日软上限共用同一 UTC 翻日口径
 * (epochDay = {@code Instant.now().atZone(UTC).toLocalDate().toEpochDay()})。
 * 框架的 {@code JobServiceImpl.currentUtcDayStamp()} 是包级私有不可跨包调用, economy.AbuseGuard 同口径方法也
 * 在他包; 此处内联同一纯表达式而非 import 他包实现类, 维持子系统解耦 (模块化铁律 2)。三处口径必须一致 (UTC
 * epochDay), 任一改动须同步 (见 notes)。
 */
public final class FarmerClock {

    private FarmerClock() {
    }

    /** 当前 UTC 日戳 (epochDay); 与框架经验衰减翻日、economy 信用点 faucet 翻日同口径。 */
    public static long currentUtcDayStamp() {
        return Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toEpochDay();
    }
}
