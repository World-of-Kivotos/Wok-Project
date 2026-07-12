package com.miningdim.champion;

import net.minecraft.world.entity.LivingEntity;

/**
 * 精英怪诊断追踪门控 (真服首验期临时基建; 清理权归用户)。各效果 handler (减伤/攻击/DoT/自身被动) 的逐事件诊断
 * 日志统一经 {@link #shouldTrace} 过滤 —— 只追踪【10 格内有玩家】的怪, 防远处刷怪场/其他玩家战斗把日志刷爆,
 * 保证打出来的每一行都是测试者眼前正在打的那只。
 *
 * 低频事件 (升格盖章 / 死亡结算 / BOSS 条创建摘除) 不经本门控 (本就稀疏且全局有价值)。
 */
public final class ChampionDiagnostics {

    /** 追踪半径 (格): 怪距最近玩家 &lt;= 此值才打逐事件诊断日志。 */
    public static final double TRACE_RANGE = 10.0D;

    private ChampionDiagnostics() {
    }

    /**
     * 该怪是否在任一玩家 {@value #TRACE_RANGE} 格内 (= 测试者眼前的怪, 逐事件诊断日志放行)。
     *
     * @param mob 被追踪的怪 (冠军)
     * @return 是否放行诊断日志
     */
    public static boolean shouldTrace(LivingEntity mob) {
        return mob.level().getNearestPlayer(mob, TRACE_RANGE) != null;
    }
}
