package com.miningdim.job.munitions;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * UTC 翻日时钟 (JobFramework_Shared_Foundation_DesignSpec 第四章; Munitions_Job_DesignSpec 七章每日软上限)。
 *
 * 军火商离线追算产弹的每日产弹归属计数 (并入产弹经验每日软上限) 必须与职业经验每日软上限共用同一 UTC 翻日口径
 * (epochDay = {@code Instant.now().atZone(UTC).toLocalDate().toEpochDay()})。
 * 框架的 {@code JobServiceImpl.currentUtcDayStamp()} 是包级私有不可跨包调用; 此处内联同一纯表达式而非 import
 * 他包实现类, 维持子系统解耦 (模块化铁律 2; 与 {@link com.miningdim.job.farmer.FarmerClock} 同范式)。
 */
public final class MunitionsClock {

    private MunitionsClock() {
    }

    /** 当前 UTC 日戳 (epochDay); 与框架经验衰减翻日同口径。 */
    public static long currentUtcDayStamp() {
        return Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toEpochDay();
    }
}
