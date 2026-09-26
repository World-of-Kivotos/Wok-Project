package com.miningdim.achievement.trigger;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 统计项每日上限的"当天已计次数" (Achievement_System_DesignSpec 6.1, 表 {@code achievement_daily_counter})。
 * 业务代码只经本接口访问, 当前实现为 {@link SqliteDailyCounterRepository}, 为多子服预留。
 *
 * "天"一律是 UTC 纪元日 ({@code LocalDate.now(ZoneOffset.UTC).toEpochDay()}), 与经济系统的每日口径同源。
 * 全部方法在服务端主线程同步调用。
 */
public interface DailyCounterRepository {

    /** 有效撤离的每日计数键 (上限 dailyExtractionCap)。 */
    String EXTRACTION_KEY = "mining_extraction";
    /** 困难有效撤离的每日计数键 (上限 dailyHardExtractionCap), 与上一项分开计。 */
    String HARD_EXTRACTION_KEY = "mining_extraction_hard";
    /** 任务领取的每日计数键 (上限 dailyQuestCap)。 */
    String QUESTS_COMPLETED_KEY = "quests_completed";
    /** 领完当天每日任务的计数键; "天"取任务板的日常周期戳, 上限 1。 */
    String QUEST_DAILY_CLEAR_KEY = "quest_daily_clear";

    /** 今天的 UTC 纪元日。 */
    static long today() {
        return LocalDate.now(ZoneOffset.UTC).toEpochDay();
    }

    /**
     * 当天计数低于 cap 时加一并返回 true; 已到上限返回 false 且不改动。cap ≤ 0 恒为 false。
     * 判断与加一在一条语句里完成, 不存在"先读后写"之间被插队的窗口。
     */
    boolean incrementIfBelow(UUID player, String counterKey, long day, int cap);

    /** 当天已计次数; 没有记录为 0。 */
    int count(UUID player, String counterKey, long day);

    /** 删除 day 之前 (不含) 的全部计数行, 返回删除的行数。上限只看当天, 旧行只是占地方。 */
    int deleteBefore(long day);
}
