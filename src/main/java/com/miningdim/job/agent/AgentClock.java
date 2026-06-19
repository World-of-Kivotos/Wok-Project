package com.miningdim.job.agent;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;

/**
 * UTC 翻日 + ISO 周序时钟 (SpecialAgent_Job_DesignSpec 10.5 悬赏 UTC 翻日 + ISO 周重置)。
 *
 * 日序 = epochDay ({@code Instant.now().atZone(UTC).toLocalDate().toEpochDay()}), 与框架经验衰减翻日同口径
 * (与 {@link com.miningdim.job.munitions.MunitionsClock}/{@link com.miningdim.job.farmer.FarmerClock} 同范式:
 * 内联同一纯表达式而非 import 他包 package-private 实现, 维持子系统解耦, 模块化铁律 2)。
 *
 * 周序 = ISO-8601 周序 (周一为周首, 与 spec 10.5 "ISO 周重置" 一致): 用 {@code year*100 + isoWeekOfYear}
 * 编成单调可比的 long 周戳 —— 同一 ISO 周内日常翻日不影响周戳, 跨 ISO 周 (周一 UTC 0 点) 周戳变化触发周常 +
 * 周青辉石产出计数重置。{@link IsoFields#WEEK_OF_WEEK_BASED_YEAR} + {@link IsoFields#WEEK_BASED_YEAR} 是 JDK
 * 内建 ISO 周历, 自动处理跨年周 (如 12 月 31 日可能属次年第 1 周) 的归属, 不自造易错的 floorDiv 周历。
 *
 * 周戳单调性: WEEK_BASED_YEAR 单调递增, 周内序 1-53, 故 year*100+week 在相邻周间严格递增 (年内 +1, 跨年
 * 从 YYYY53 跳 (YYYY+1)01 = +48, 仍单调), 可直接用 != 判翻周 (不依赖差值大小)。
 *
 * 纯函数, 无世界引用。GameTest 把日戳/周戳作纯函数入参注入 (不依赖 Instant.now() 实时) 断言翻转边界。
 */
public final class AgentClock {

    private AgentClock() {
    }

    /** 当前 UTC 日戳 (epochDay); 与框架经验衰减翻日同口径。 */
    public static long currentUtcDayStamp() {
        return Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toEpochDay();
    }

    /**
     * 当前 UTC ISO 周戳 ({@code isoWeekBasedYear*100 + isoWeekOfWeekBasedYear}); 跨 ISO 周变化触发周常重置。
     * 编码保证相邻周严格单调递增, 可直接用 {@code !=} 判翻周 (见类注释)。
     */
    public static long currentUtcWeekStamp() {
        return isoWeekStampOf(currentUtcDayStamp());
    }

    /**
     * 把 epochDay 折算成 ISO 周戳 ({@code isoWeekBasedYear*100 + isoWeekOfWeekBasedYear})。纯函数, 供测试
     * 注入固定 epochDay 断言翻周边界 (同 ISO 周多日同周戳; 跨周一周戳变化)。
     *
     * @param epochDay UTC epochDay (1970-01-01 = 0)
     * @return ISO 周戳 (单调可比)
     */
    public static long isoWeekStampOf(long epochDay) {
        java.time.LocalDate date = java.time.LocalDate.ofEpochDay(epochDay);
        int weekBasedYear = date.get(IsoFields.WEEK_BASED_YEAR);
        int weekOfYear = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return (long) weekBasedYear * 100L + weekOfYear;
    }
}
