package com.miningdim.job;

import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import net.minecraft.world.entity.player.Player;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * {@link IJobService} 实现 (JobFramework_Shared_Foundation_DesignSpec 第十二章)。无状态门面: 全部职业进度
 * 落 entry 唯一权威玩家 capability ({@link MiningCapabilities}, 第 2.3 节并入), 本类只做 capability 解包 +
 * 委派 {@link JobProgress#grantXp}。不再持第二套 job.JobCapability (已删)。
 *
 * UTC 翻日时钟 (第四章): 信用点每日 faucet 上限与职业经验软上限共用同一 UTC 翻日时钟。该时钟即
 * {@code Instant.now().atZone(UTC).toLocalDate().toEpochDay()} (与 economy.AbuseGuard.currentPlayerDayStamp
 * 同口径)。此处内联同一纯表达式而非 import economy 实现类, 维持 job/economy 子系统解耦 (模块化铁律 2);
 * 两者口径必须一致 (UTC epochDay), 任一改动须同步。
 */
public final class JobServiceImpl implements IJobService {

    @Override
    public int level(Player player, JobId job) {
        return require(player, job).jobProgress(job).level();
    }

    @Override
    public long totalXp(Player player, JobId job) {
        return require(player, job).jobProgress(job).xp(job);
    }

    @Override
    public long grantXp(Player player, JobId job, long rawXp) {
        // 负值非法在 JobProgress.grantXp 内抛, 自然冒泡 (异常纪律: 不在此生吞)。先解包 capability。
        return require(player, job).jobProgress(job).grantXp(job, rawXp, currentUtcDayStamp());
    }

    @Override
    public JobProgress progress(Player player, JobId job) {
        return require(player, job).jobProgress(job);
    }

    /**
     * 解包玩家 capability, 缺失即视为不变量破坏并抛出。
     *
     * {@link MiningCapabilities#onAttachCapabilities} 对每个 Player 实体无条件挂载 Provider, 因此取不到
     * 只可能是能力生命周期错误 (如 invalidate 后仍被访问)。此处严禁回退默认值: 曾经的 orElse(0L) 会把
     * 故障伪装成"该玩家零经验", 使本次入账静默丢失且无任何日志可供定位。
     */
    private static IMiningPlayerData require(Player player, JobId job) {
        Optional<IMiningPlayerData> data = MiningCapabilities.get(player);
        return data.orElseThrow(() -> new IllegalStateException(
                "玩家 capability 缺失, 无法读写职业进度: player=" + player.getGameProfile().getName()
                        + " job=" + job));
    }

    /** 当前 UTC 日戳 (epochDay; 与 economy 翻日时钟同口径)。供职业经验每日衰减翻日判定。 */
    static long currentUtcDayStamp() {
        return Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toEpochDay();
    }
}
