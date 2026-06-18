package com.miningdim.job.engineer.effect;

import com.miningdim.job.engineer.EngineerConfig;

/**
 * 纳米末影心肺反应器 (图腾) 的纯逻辑核心 (MillenniumEngineer_Mod_DesignSpec 6.2)。把 "共享 CD 是否就绪"、
 * "复活血量" 抽出, 供 {@link NanoReactorHandler} 调用 + GameTest 用确定值断言 (人级 30min 共享 CD 防多命)。
 *
 * 共享 CD (人级, 非按件): nanoReactorCdEndTick 存玩家 capability (JobProgress.nanoReactorCdEndTick)。
 * 叠穿多件图腾甲共享同一 CD —— 30min 内仍只救一次 (冗余保险非额外命数; 防战力叠叠乐铁律)。
 */
public final class NanoReactor {

    private NanoReactor() {
    }

    /** 当前 gameTime 是否已过共享 CD 截止 tick (就绪可触发)。 */
    public static boolean cooldownReady(long nowTick, long cdEndTick) {
        return nowTick >= cdEndTick;
    }

    /** 触发后新的 CD 截止 tick = now + 共享 CD 时长 (实时 config; 默认 36000 = 30min)。 */
    public static long nextCdEndTick(long nowTick) {
        return nowTick + EngineerConfig.TOTEM_SHARED_CD_TICKS.get();
    }

    /** 复活血量 = 最大血量 * 复活百分比 (% 最大血量建模; 80 血 * 50% = 40 血)。至少 1 防 0 血。 */
    public static float reviveHealth(float maxHealth) {
        float h = (float) (maxHealth * EngineerConfig.TOTEM_REVIVE_HEALTH_PCT.get());
        return Math.max(1.0f, h);
    }
}
