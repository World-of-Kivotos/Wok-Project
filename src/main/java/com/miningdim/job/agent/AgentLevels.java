package com.miningdim.job.agent;

import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import net.minecraft.world.entity.player.Player;

/**
 * 特勤干员等级 / 经验入账的薄封装 (SpecialAgent_Job_DesignSpec 八章升级线 + 十三章并入 JobFramework)。
 *
 * 等级曲线 (八章 61,900 XP) + 每日衰减 (8.3 软上限) + 经验入账由共享职业框架 ({@link com.miningdim.job.JobXpCurve} /
 * IJobService.grantXp) 统一裁决; 本类只负责: 经 {@link JobServices#jobService()} 读 AGENT 等级 + 入账原始经验
 * (职业侧只发"找/扫/杀/悬赏"循环产出的原始经验, 衰减/翻日/升级由框架管, 职业不得自行折算; 范式对齐
 * {@link com.miningdim.job.munitions.MunitionsLevels})。
 *
 * 严禁本类自行折算衰减或重定义曲线 (框架 spec: 消除 spec 漂移)。spec 8.2 逐级 XP 表与已落地 JobXpCurve 逐级
 * 断点不一致 (总量同 61,900), 本实现一律用 JobXpCurve 现值不复制 spec 8.2; 8.3 每日衰减地板亦以 JobXpCurve
 * 现行档为准 (实质冲突已上报用户定夺, 见交付 notes/risks, 改框架影响全职业属越界)。
 */
public final class AgentLevels {

    /** 干员等级合法区间 (第一章 1-10, 与 {@link AgentSkillTable} 同尺度)。 */
    public static final int MIN_LEVEL = AgentSkillTable.MIN_LEVEL;
    public static final int MAX_LEVEL = AgentSkillTable.MAX_LEVEL;

    private AgentLevels() {
    }

    /** 读玩家干员等级 (经职业框架门面; 未挂载 capability 返回 1 级)。 */
    public static int agentLevel(Player player) {
        return JobServices.jobService().level(player, JobId.AGENT);
    }

    /**
     * 给玩家入账一笔干员原始经验 (经职业框架统一衰减/翻日/升级软上限)。
     *
     * @param rawXp 原始经验 (>=0; 负数由框架抛 IllegalArgumentException 自然冒泡)
     * @return 经每日衰减折算后实际入账的有效经验 (>=0; 未挂载 capability 返回 0)
     */
    public static long grantRawXp(Player player, long rawXp) {
        return JobServices.jobService().grantXp(player, JobId.AGENT, rawXp);
    }

    /** 把任意等级值夹到合法区间 (防越界查表; 委派 {@link AgentSkillTable#clampLevel})。 */
    public static int clampLevel(int level) {
        return AgentSkillTable.clampLevel(level);
    }
}
