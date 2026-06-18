package com.miningdim.job;

import net.minecraft.world.entity.player.Player;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * {@link IJobService} 实现 (JobFramework_Shared_Foundation_DesignSpec 第十二章)。无状态门面: 全部数据落
 * 玩家 capability ({@link JobCapability}), 本类只做 capability 解包 + 委派 {@link JobProgress#grantXp}。
 *
 * UTC 翻日时钟 (第四章): 信用点每日 faucet 上限与职业经验软上限共用同一 UTC 翻日时钟。该时钟即
 * {@code Instant.now().atZone(UTC).toLocalDate().toEpochDay()} (与 economy.AbuseGuard.currentPlayerDayStamp
 * 同口径)。此处内联同一纯表达式而非 import economy 实现类, 维持 job/economy 子系统解耦 (模块化铁律 2);
 * 两者口径必须一致 (UTC epochDay), 任一改动须同步。
 */
public final class JobServiceImpl implements IJobService {

    @Override
    public int level(Player player, JobId job) {
        Optional<IJobPlayerData> data = JobCapability.get(player);
        return data.map(d -> d.jobProgress(job).level()).orElse(JobXpCurve.MIN_LEVEL);
    }

    @Override
    public long totalXp(Player player, JobId job) {
        Optional<IJobPlayerData> data = JobCapability.get(player);
        return data.map(d -> d.jobProgress(job).xp()).orElse(0L);
    }

    @Override
    public long grantXp(Player player, JobId job, long rawXp) {
        // 负值非法在 JobProgress.grantXp 内抛, 自然冒泡 (异常纪律: 不在此生吞)。先解包 capability。
        Optional<IJobPlayerData> data = JobCapability.get(player);
        if (data.isEmpty()) {
            return 0L; // 未挂载能力 (极端时序/非玩家实体): 无处入账, 返回 0 让调用方短路。
        }
        return data.get().jobProgress(job).grantXp(rawXp, currentUtcDayStamp());
    }

    @Override
    public JobProgress progress(Player player, JobId job) {
        Optional<IJobPlayerData> data = JobCapability.get(player);
        return data.map(d -> d.jobProgress(job)).orElse(null);
    }

    /** 当前 UTC 日戳 (epochDay; 与 economy 翻日时钟同口径)。供职业经验每日衰减翻日判定。 */
    static long currentUtcDayStamp() {
        return Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toEpochDay();
    }
}
