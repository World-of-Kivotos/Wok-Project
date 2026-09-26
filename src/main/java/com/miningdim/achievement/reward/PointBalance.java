package com.miningdim.achievement.reward;

/**
 * 一名玩家的成就点 (表 {@code achievement_points})。
 *
 * @param balance  当前余额 (领取加、兑换扣)
 * @param lifetime 累计获得, 只增不减
 */
public record PointBalance(long balance, long lifetime) {

    /** 从未获得过成就点的玩家。 */
    public static final PointBalance ZERO = new PointBalance(0L, 0L);
}
