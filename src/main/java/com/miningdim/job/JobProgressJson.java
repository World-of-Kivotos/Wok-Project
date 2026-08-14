package com.miningdim.job;

import com.google.gson.JsonObject;

/**
 * 单职业进度行的 JSON 形状唯一实现 (player.profile 的 jobs[] 与 job.progress 共用同一份)。
 *
 * 落在 job 包而不是 market 包: 现有包依赖方向是单向的 market -&gt; job, 让 job 反向依赖 market 会造成包环。
 * 两条 action 共用本类保证"同形"不是靠注释约定, 而是编译期只有一份实现。
 */
public final class JobProgressJson {

    private JobProgressJson() {
    }

    /**
     * 单个职业的进度行。
     *
     * levelXp / nextLevelXp 没有现成 getter, 在此由曲线派生。nextLevelXp 是<b>本级跨度</b>而不是"还差多少",
     * 因为前端拿 (levelXp / nextLevelXp) 当进度条的 value/max, 只有跨度口径才落在 [0,1]。
     *
     * 满级 (level == MAX_LEVEL) 时两栏<b>同时</b>发 0: 前端据 nextLevelXp === 0 判满级并改画一句结论, 而不是
     * 画一个 0/0 的 NaN 宽度空槽; 也不必靠 level === 10 硬编码去猜。
     *
     * totalXp 用带 JobId 的重载: 存档加载时就是按同一口径 (FARMER 走 round, 其余 floor) 反推 level 的, 两边
     * 同源才保证 levelXp 恒非负。
     *
     * 不发 displayName: 专用服务端不加载 lang, 服务端不下发中文; 前端按 {@code job.miningdim.<jobId>} 自解。
     *
     * @param todayStamp 调用方一次取好的当日日戳 (八个职业共用同一次取值, 见 dailyXp 的翻日说明)
     */
    public static JsonObject of(JobId job, JobProgress progress, long todayStamp) {
        int level = progress.level();
        long totalXp = progress.xp(job);
        long reachedAt = JobXpCurve.cumulativeXpForLevel(level);
        boolean graduated = level >= JobXpCurve.MAX_LEVEL;

        JsonObject entry = new JsonObject();
        entry.addProperty("jobId", job.id());
        entry.addProperty("level", level);
        entry.addProperty("totalXp", totalXp);
        entry.addProperty("levelXp", graduated ? 0L : totalXp - reachedAt);
        entry.addProperty("nextLevelXp",
                graduated ? 0L : JobXpCurve.cumulativeXpForLevel(level + 1) - reachedAt);
        // 带日戳的只读重载: 无日戳版本直接读字段, 跨日后到该职业当天首次入账前会一直吐昨天的值, 而首屏正是
        // 玩家最可能在"今天还没开工"时看到的地方。只读不翻日 —— 清零权独归入账路径, 顺手翻日等于把衰减档位
        // 洗回第 0 档印钞。
        entry.addProperty("dailyXp", progress.dailyXp(job, todayStamp));
        entry.addProperty("dailyRemaining", progress.dailyRemaining(job, todayStamp));
        return entry;
    }
}
