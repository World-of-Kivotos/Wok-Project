package com.miningdim.quest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;

/**
 * UTC 翻日 + ISO 周序时钟 (每日任务翻日重置 / 每周任务翻周重置)。
 *
 * 日戳 = epochDay, 周戳 = ISO-8601 周序编码 {@code weekBasedYear*100 + weekOfWeekBasedYear}。周戳编码保证相邻
 * 周严格单调递增 (年内 +1; 跨年从 YYYY53 跳 (YYYY+1)01 = +48, 仍递增), 故可直接用 {@code !=} 判翻周, 不依赖
 * 差值大小。{@link IsoFields} 是 JDK 内建 ISO 周历, 自动处理跨年周 (12 月 31 日可能属次年第 1 周) 的归属,
 * 不自造易错的 floorDiv 周历。
 *
 * 为什么不 import 现成的 {@code job.agent.AgentClock}: 模块化铁律 2 禁止子系统硬编码 import 对方实现类, 仓库
 * 既有的 {@code MunitionsClock} / {@code FarmerClock} / {@code AgentClock} 已按同一范式各自内联同一纯表达式。
 * 本类沿用该范式。将来悬赏并入任务板时, 应删除 {@code AgentClock} 并统一到本类 (而非反向依赖)。
 *
 * 纯函数, 无世界引用。GameTest 把日戳/周戳作纯函数入参注入 ({@link #isoWeekStampOf}), 不依赖 {@code Instant.now()}
 * 实时值即可断言翻转边界。
 */
public final class QuestClock {

    private QuestClock() {
    }

    /** 当前 UTC 日戳 (epochDay); 与框架经验衰减翻日同口径。 */
    public static long currentUtcDayStamp() {
        return Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toEpochDay();
    }

    /** 当前 UTC ISO 周戳; 跨 ISO 周 (周一 UTC 0 点) 变化触发周常重置。 */
    public static long currentUtcWeekStamp() {
        return isoWeekStampOf(currentUtcDayStamp());
    }

    /**
     * 把 epochDay 折算成 ISO 周戳。纯函数, 供测试注入固定 epochDay 断言翻周边界 (同 ISO 周多日同周戳;
     * 跨周一周戳变化)。
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

    /**
     * 取指定来源当前的周期戳。
     *
     * 非周期来源 (SPECIAL / HIDDEN) 没有周期戳, 调用即装配缺陷 —— 按 CLAUDE.md "异常必须痛" 直接抛, 不返回
     * 0 或 -1 之类的哨兵值: 哨兵值会让"特殊任务被当成 1970-01-01 的日常任务"这种错误一路静默传到存档层。
     */
    public static long currentStampOf(QuestSource source) {
        return switch (source) {
            case DAILY -> currentUtcDayStamp();
            case WEEKLY -> currentUtcWeekStamp();
            case SPECIAL, HIDDEN -> throw new UnsupportedOperationException(
                    "quest source " + source + " is not periodic and has no period stamp");
        };
    }
}
