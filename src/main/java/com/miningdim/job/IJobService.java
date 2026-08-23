package com.miningdim.job;

import net.minecraft.world.entity.player.Player;

/**
 * 职业框架门面 (JobFramework_Shared_Foundation_DesignSpec 第十二章地基服务)。各职业子系统经
 * {@link JobServices#jobService()} 取用本接口查等级/加经验/读进度, 不 import 框架实现类 (模块化铁律 2)。
 *
 * {@link #grantXp(Player, JobId, long)} 是旧职业调用的兼容门面，运行时会进入全服
 * {@link com.miningdim.progression.IExperienceService}；新模块应直接提交带明确来源 ID 的 ExperienceGrant。
 * 衰减/翻日/升级仍由职业轨道统一裁决，职业不得自行折算。
 */
public interface IJobService {

    /** 取某玩家某职业当前等级 (1-10); 未挂载 capability 返回 1 级默认 (新人, 不抛)。 */
    int level(Player player, JobId job);

    /** 取某玩家某职业累计有效经验; 未挂载返回 0。 */
    long totalXp(Player player, JobId job);

    /**
     * 给某玩家某职业入账一笔原始经验 (兼容入口；经全服经验路由与每日软上限，UTC 翻日)。
     *
     * @param rawXp 本次原始经验 (>=0; 负数抛 IllegalArgumentException, 异常自然冒泡由命令/事件边界兜底)
     * @return 经衰减折算后实际入账的有效经验 (>=0); 未挂载 capability 返回 0 (无处入账, 调用方据此短路)
     */
    long grantXp(Player player, JobId job, long rawXp);

    /**
     * 取某玩家某职业进度对象 (读 level/xp/dailyXp/职业特有字段)。
     * 未挂载 capability 返回 null (调用方据此短路; 与 capability "未挂载 empty" 同语义, 不静默掩盖)。
     */
    JobProgress progress(Player player, JobId job);
}
