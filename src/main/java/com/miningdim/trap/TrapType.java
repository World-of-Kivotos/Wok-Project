package com.miningdim.trap;

/**
 * 陷阱类型枚举 (设计文档 9.2 总览 + 9.4 静态规格 + 9.6 动态规格)。
 *
 * lethal 标记区分致死类 (TNT/岩浆/苦力怕/岩浆喷发) 与非致死提示类 (崩塌/假矿): 致死类受 9.5 禁布过滤 1/2 与
 * 9.3 子区致死密度上限约束; 非致死类仅受概率与最小间距约束。reactionWindowTicks 为 TR-1 最短反应窗口下限。
 *
 * 数值照抄 9.4 / 9.6 / 9.7 表 (PENDING 初值); 半心 = 1.0 伤害口径 (9.4 前注)。
 */
public enum TrapType {

    // ---- 静态陷阱 (9.4, 离线布点) ----

    /** TNT 矿脉: 挖到引信方块触发, fuse 30 tick, explode power=3.0, 半径约 4 格 (9.4)。 */
    TNT_VEIN(Stage.STATIC, true, 30, 3.0f, 4.0),

    /** 岩浆池/岩浆袋: 破薄壁接触岩浆, 体积 <= 2x2x2, 接触伤害走原版机制 (9.4)。 */
    LAVA_POCKET(Stage.STATIC, true, 0, 0.0f, 2.0),

    /** 崩塌矿道: 移除支撑触发, 预警 10 tick 落沙, 单块 FallingBlockEntity, 累计封顶 6.0 (9.4)。 */
    COLLAPSING_TUNNEL(Stage.STATIC, false, 10, 2.0f, 1.0),

    /** 假矿石爆炸: 挖伪装矿石触发, explode power=2.0, 半径约 2.5 格 (9.4)。 */
    FAKE_ORE(Stage.STATIC, false, 0, 2.0f, 2.5),

    // ---- 动态陷阱 (9.6, 运行期事件驱动) ----

    /** 身后刷苦力怕: danger 阈值 + 玩家背向, 原版 creeper 引信 30 tick (9.6/9.7)。 */
    BEHIND_CREEPER(Stage.DYNAMIC, true, 30, 0.0f, 0.0),

    /** 局部坍塌: danger 概率 tick, 预警 10 tick, 1-3 列 FallingBlock, 累计封顶 6.0 (9.6)。 */
    LOCAL_COLLAPSE(Stage.DYNAMIC, false, 10, 2.0f, 1.0),

    /** 岩浆喷发: danger 概率 tick, 预警 20 tick, 接触 4.0/0.5s, 5 tick 后回收 (9.6)。 */
    LAVA_BURST(Stage.DYNAMIC, true, 20, 4.0f, 2.0);

    /** 陷阱阶段: 静态 (离线布点) / 动态 (运行期)。 */
    public enum Stage {
        STATIC,
        DYNAMIC
    }

    private final Stage stage;
    private final boolean lethal;
    private final int reactionWindowTicks;
    private final float damage;
    private final double radius;

    TrapType(Stage stage, boolean lethal, int reactionWindowTicks, float damage, double radius) {
        this.stage = stage;
        this.lethal = lethal;
        this.reactionWindowTicks = reactionWindowTicks;
        this.damage = damage;
        this.radius = radius;
    }

    public Stage stage() {
        return stage;
    }

    /** 致死类 (TR-2 禁布过滤与 9.3 致死密度上限只约束此类)。 */
    public boolean lethal() {
        return lethal;
    }

    /** 最短反应窗口 tick (TR-1)。 */
    public int reactionWindowTicks() {
        return reactionWindowTicks;
    }

    /** 伤害 (半心=1.0; 岩浆/苦力怕走原版接触机制时为 0, 由触发逻辑套原版伤害)。 */
    public float damage() {
        return damage;
    }

    /** 作用半径 (格)。 */
    public double radius() {
        return radius;
    }
}
