package com.miningdim.champion.reward;

import java.util.UUID;

/**
 * 单个玩家对某精英怪的有效伤害贡献记录 (ChampionStarAffix spec 第十一章奖励与经济闸)。贡献池盖章/瓜分的
 * 输入单元, 不可变值对象, 无世界引用 (UUID 标识玩家, 不持 ServerPlayer)。
 *
 * 召唤物排除 (spec 红线 8 / 第十一章): summonedByAffix=true 的实体伤害不计入贡献 —— 该排除由数据采集层
 * (b 阶段 onHurt 归因) 在构造贡献前就过滤掉, 故本记录里的 effectiveDamage 已是排除召唤物后的纯玩家有效伤害。
 *
 * 首次有效伤害时间戳 (spec 第十一章防补刀刷入): firstHitTick 记录该玩家首次造成有效伤害的 gameTime,
 * 离线没收/补刀判定据此; 0 表示未参战 (不应出现在贡献列表)。
 *
 * 离线没收 (spec 第十一章): online=false 的玩家不参与盖章/瓜分 (隐含于双门槛 + 首次有效伤害时间戳机制)。
 */
public final class DamageContribution {

    private final UUID playerId;
    private final double effectiveDamage;
    private final long firstHitTick;
    private final boolean online;

    /**
     * @param playerId        玩家 UUID
     * @param effectiveDamage 该玩家对本怪的累计有效伤害 (已排除召唤物; 必须 &gt;=0)
     * @param firstHitTick    首次有效伤害的 gameTime tick (&gt;=0)
     * @param online          结算时是否在线 (离线没收)
     */
    public DamageContribution(UUID playerId, double effectiveDamage, long firstHitTick, boolean online) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId must not be null");
        }
        if (effectiveDamage < 0.0D || Double.isNaN(effectiveDamage)) {
            throw new IllegalArgumentException("effectiveDamage must be >= 0, got " + effectiveDamage);
        }
        if (firstHitTick < 0L) {
            throw new IllegalArgumentException("firstHitTick must be >= 0, got " + firstHitTick);
        }
        this.playerId = playerId;
        this.effectiveDamage = effectiveDamage;
        this.firstHitTick = firstHitTick;
        this.online = online;
    }

    public UUID playerId() {
        return playerId;
    }

    public double effectiveDamage() {
        return effectiveDamage;
    }

    public long firstHitTick() {
        return firstHitTick;
    }

    public boolean online() {
        return online;
    }
}
