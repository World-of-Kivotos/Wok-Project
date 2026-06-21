package com.miningdim.champion;

/**
 * 冠军攻击类词条的【on-hit 内 CD / 嗜血激活 / 击飞限频】纯逻辑闸 (ChampionStarAffix spec 7.2 + 红线 4/5)。
 *
 * 攻击类词条多个机制带"内 CD/限频"防逐跳无限维持 (DoT 刷新 ≥1s/源、混沌击飞 ≥2s + 落地 ≥1s 恢复窗、双倍/
 * 四倍分跳但 DoT 刷新受同一内 CD)。这些是【时间窗判定】纯逻辑, 与世界/实体无关, 故抽成本闸: handler 在受击
 * 单点拿 nowTick 调本闸判定"本次命中可否刷新 DoT / 可否触发击飞", 闸内维护上次时间戳。GameTest 直接断言时间窗
 * (删内 CD → 逐跳可无限刷新 DoT 破红线 4 / 逐跳可连续击飞破红线 5, test 必挂)。
 *
 * 状态归属: 本闸是 per-(冠军→玩家) 的攻击交互状态 (一只冠军对一个玩家的 DoT 刷新窗/击飞窗), 由
 * {@link com.miningdim.champion.ChampionEffectRegistries} 或 handler 按 (championId, playerId) 键管理。服务端 tick
 * 串行写, 非线程安全 (与其它聚合器一致)。本闸不碰世界/实体/Champions, dev GameTest 触达安全。
 */
public final class ChampionStrikeGate {

    private static final long TICKS_PER_SECOND = 20L;

    /** DoT 刷新内 CD (spec 红线 4: ≥1s/源 = 20tick)。 */
    public static final long DOT_REFRESH_CD_TICKS =
            (long) (ChampionRedlines.DOT_REFRESH_INTERNAL_CD_SECONDS * TICKS_PER_SECOND);

    /** 混沌击飞内 CD (spec 7.2 混沌: ≥2s = 40tick)。 */
    public static final long CHAOS_KNOCKBACK_CD_TICKS = 2L * TICKS_PER_SECOND;

    /** 混沌击飞落地后不可再击飞的恢复窗 (spec 7.2 混沌: 落地后 ≥1s = 20tick)。 */
    public static final long CHAOS_LANDING_RECOVERY_TICKS = 1L * TICKS_PER_SECOND;

    /** 上次 DoT 刷新 tick (Long.MIN_VALUE = 从未刷新, 首次允许)。 */
    private long lastDotRefreshTick = Long.MIN_VALUE;

    /** 上次击飞触发 tick (Long.MIN_VALUE = 从未击飞)。 */
    private long lastKnockbackTick = Long.MIN_VALUE;

    /** 上次击飞的玩家预计落地 tick (击飞触发时由 handler 估算落地时间设入; Long.MIN_VALUE = 无在途击飞)。 */
    private long lastLandingTick = Long.MIN_VALUE;

    /**
     * 本次命中是否可刷新 DoT (燃烧/寒霜): 距上次刷新 ≥1s 才允许 (spec 红线 4 内 CD)。双倍/四倍分跳同帧多次命中
     * 也受同一内 CD 约束 —— 同一 tick 内第二跳调本法返 false (lastDotRefreshTick==nowTick, diff 0 < CD), 故分跳
     * 不破聚合封顶 (spec 7.2 双倍/四倍: DoT 刷新受内 CD)。允许时【不】自动落账, 由调用方确认刷新成功后调
     * {@link #markDotRefreshed} 落账 (分离判定与落账, 便于 handler 在真正施加层数后才推进窗)。
     *
     * @param nowTick 当前 gameTime tick
     * @return 本次是否在 DoT 刷新内 CD 之外 (可刷新)
     */
    public boolean canRefreshDot(long nowTick) {
        if (lastDotRefreshTick == Long.MIN_VALUE) {
            return true;
        }
        return nowTick - lastDotRefreshTick >= DOT_REFRESH_CD_TICKS;
    }

    /** 落账一次 DoT 刷新 (推进内 CD 窗起点到 nowTick)。 */
    public void markDotRefreshed(long nowTick) {
        lastDotRefreshTick = nowTick;
    }

    /**
     * 本次命中是否可触发混沌击飞 (spec 7.2 混沌红队): 须同时满足
     *  (1) 距上次击飞 ≥2s 内 CD;
     *  (2) 不在上次击飞的"落地后 ≥1s 恢复窗"内 (落地 tick + 1s 之后才允许)。
     * 二者任一不满足返 false (逐跳/连续击飞被挡, 不破控制红线 5)。允许时由调用方确认施加击飞后调
     * {@link #markKnockback} 落账 (含设落地预计 tick)。
     *
     * @param nowTick 当前 gameTime tick
     * @return 本次是否可触发击飞
     */
    public boolean canKnockback(long nowTick) {
        if (lastKnockbackTick != Long.MIN_VALUE && nowTick - lastKnockbackTick < CHAOS_KNOCKBACK_CD_TICKS) {
            return false; // 内 CD 内。
        }
        if (lastLandingTick != Long.MIN_VALUE && nowTick - lastLandingTick < CHAOS_LANDING_RECOVERY_TICKS) {
            return false; // 落地恢复窗内。
        }
        return true;
    }

    /**
     * 落账一次混沌击飞 (推进击飞内 CD 窗起点 + 设预计落地 tick)。
     *
     * @param nowTick             击飞触发 tick
     * @param estimatedLandingTick 由 handler 据击飞向量末端估算的落地 tick (≥nowTick); 落地恢复窗据此判定
     */
    public void markKnockback(long nowTick, long estimatedLandingTick) {
        if (estimatedLandingTick < nowTick) {
            throw new IllegalArgumentException(
                    "estimatedLandingTick must be >= nowTick, got " + estimatedLandingTick + " < " + nowTick);
        }
        lastKnockbackTick = nowTick;
        lastLandingTick = estimatedLandingTick;
    }

    /**
     * 双倍/四倍打击分跳数 (spec 7.2): 该词条 valueFor 即跳数 (双倍=2/四倍=4)。无多击词条返回 1 (单跳)。本法是
     * 跳数语义的纯解释 (供 handler 决定一次近战拆几跳施加 on-hit rider); 分跳间隔与施加由 handler 调度。
     *
     * @param multiStrikeDef  多击词条 (DOUBLE_STRIKE / QUADRUPLE_STRIKE); null = 无多击 → 1 跳
     * @param quality         多击词条品质 (跳数 5 档恒定, 仍按品质取档保接口一致)
     * @return 本次近战的分跳数 (≥1)
     */
    public static int strikeJumps(AffixDef multiStrikeDef, AffixQuality quality) {
        if (multiStrikeDef == null) {
            return 1;
        }
        if (multiStrikeDef != AffixDef.DOUBLE_STRIKE && multiStrikeDef != AffixDef.QUADRUPLE_STRIKE) {
            throw new IllegalArgumentException("not a multi-strike affix: " + multiStrikeDef);
        }
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        int jumps = (int) Math.round(multiStrikeDef.valueFor(quality));
        if (jumps < 1) {
            throw new IllegalStateException("multi-strike jumps must be >= 1, got " + jumps);
        }
        return jumps;
    }

    /** 上次 DoT 刷新 tick (诊断/测试用; Long.MIN_VALUE = 从未)。 */
    public long lastDotRefreshTick() {
        return lastDotRefreshTick;
    }

    /** 上次击飞 tick (诊断/测试用)。 */
    public long lastKnockbackTick() {
        return lastKnockbackTick;
    }
}
