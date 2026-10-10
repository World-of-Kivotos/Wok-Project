package com.miningdim.champion.reward;

import java.util.UUID;

/**
 * 单个玩家对某精英怪的伤害贡献记录 (ChampionStarAffix spec 第十一章奖励与经济闸)。贡献池盖章/瓜分的输入单元,
 * 不可变值对象, 无世界引用 (UUID 标识玩家, 不持 ServerPlayer)。
 *
 * 有效伤害 = 净伤 (方案 D1): 该玩家本次实际从精英身上扣掉的血的累计 —— 6★+ 为经词条净减伤、FLAT 削顶后再按
 * 扣血前剩余影子血截断的值, 1-5★ 为原版结算后按扣血前血量截断的值。重甲整次免疫、刚毅封顶削掉的部分与溢出
 * 伤害都不在内, 于是全体有效伤害之和等于实际击杀量, 份额即真实杀伤占比。门槛、份额、特勤门槛全部只看它。
 * grossDamage 是同批命中的毛伤 (记账点看到的入伤名义值), 只供诊断日志, 不参与任何结算。
 *
 * 召唤物排除 (spec 红线 8 / 第十一章): summonedByAffix=true 的实体伤害不计入贡献 —— 该排除由数据采集层
 * 在记账前就过滤掉, 故本记录里的伤害已是排除召唤物后的纯玩家伤害。
 *
 * firstHitTick / lastHitTick: 首次与最近一次造成净伤的 gameTime。lastHitTick 供结算资格的近期门槛
 * (方案 D3: 结算时刻距最近命中超过 contributionRecencyTicks 则该份额作废)。
 *
 * online: 结算时是否在线 (离线没收, 由快照时现查注入)。
 */
public final class DamageContribution {

    private final UUID playerId;
    private final double effectiveDamage;
    private final double grossDamage;
    private final long firstHitTick;
    private final long lastHitTick;
    private final boolean online;

    /**
     * 单次命中的简写: 毛伤 = 净伤, 最近命中 = 首次命中。
     *
     * @param playerId        玩家 UUID
     * @param effectiveDamage 该玩家对本怪的累计净伤 (必须 &gt;=0)
     * @param firstHitTick    首次造成净伤的 gameTime tick (&gt;=0)
     * @param online          结算时是否在线 (离线没收)
     */
    public DamageContribution(UUID playerId, double effectiveDamage, long firstHitTick, boolean online) {
        this(playerId, effectiveDamage, effectiveDamage, firstHitTick, firstHitTick, online);
    }

    /**
     * @param playerId        玩家 UUID
     * @param effectiveDamage 累计净伤 (必须 &gt;=0; 份额与门槛的唯一口径)
     * @param grossDamage     累计毛伤 (必须 &gt;=0; 仅诊断)
     * @param firstHitTick    首次造成净伤的 gameTime tick (&gt;=0)
     * @param lastHitTick     最近一次造成净伤的 gameTime tick (&gt;= firstHitTick)
     * @param online          结算时是否在线 (离线没收)
     */
    public DamageContribution(UUID playerId, double effectiveDamage, double grossDamage,
                              long firstHitTick, long lastHitTick, boolean online) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId must not be null");
        }
        if (effectiveDamage < 0.0D || Double.isNaN(effectiveDamage)) {
            throw new IllegalArgumentException("effectiveDamage must be >= 0, got " + effectiveDamage);
        }
        if (grossDamage < 0.0D || Double.isNaN(grossDamage)) {
            throw new IllegalArgumentException("grossDamage must be >= 0, got " + grossDamage);
        }
        if (firstHitTick < 0L) {
            throw new IllegalArgumentException("firstHitTick must be >= 0, got " + firstHitTick);
        }
        if (lastHitTick < firstHitTick) {
            throw new IllegalArgumentException(
                    "lastHitTick must be >= firstHitTick, got " + lastHitTick + " < " + firstHitTick);
        }
        this.playerId = playerId;
        this.effectiveDamage = effectiveDamage;
        this.grossDamage = grossDamage;
        this.firstHitTick = firstHitTick;
        this.lastHitTick = lastHitTick;
        this.online = online;
    }

    public UUID playerId() {
        return playerId;
    }

    /** 累计净伤 (实际扣血量)。 */
    public double effectiveDamage() {
        return effectiveDamage;
    }

    /** 累计毛伤 (仅诊断)。 */
    public double grossDamage() {
        return grossDamage;
    }

    public long firstHitTick() {
        return firstHitTick;
    }

    public long lastHitTick() {
        return lastHitTick;
    }

    public boolean online() {
        return online;
    }
}
