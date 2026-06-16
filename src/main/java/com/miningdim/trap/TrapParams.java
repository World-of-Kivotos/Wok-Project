package com.miningdim.trap;

import com.miningdim.core.Difficulty;

/**
 * 陷阱系统数值表 (设计文档 9.3 / 9.6 / 9.7 的 PENDING 初值)。
 *
 * 取值来源分工: IMiningConfig (16.2.5) 提供可热调的 trap.baseChance / localRiskMax / dynamicEnabled /
 * minSpacingBlocks; 而 9.3 的"每难度 difficultyFactor / TRAP_CHANCE_MAX / 子区致死上限 / 致死最小间距"
 * 与 9.7 身后刷怪几何约束在 core 配置门面里没有对应键, 故作为本子系统专属常量集中于此 (单一来源, 杜绝散落)。
 * 阶段2 若把这些纳入 ForgeConfigSpec, 改为读 MiningServices.config() 即可, 业务代码不动。
 */
public final class TrapParams {

    private TrapParams() {
    }

    // ---- 9.3 difficultyFactor ----

    private static final double FACTOR_EASY = 0.00;
    private static final double FACTOR_MEDIUM = 0.35;
    private static final double FACTOR_HARD = 1.00;

    /** 9.3 difficultyFactor: Easy 0 (新手区无静态致死陷阱) / Medium 0.35 / Hard 1.00。 */
    public static double difficultyFactor(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> FACTOR_EASY;
            case MEDIUM -> FACTOR_MEDIUM;
            case HARD -> FACTOR_HARD;
        };
    }

    // ---- 9.3 TRAP_CHANCE_MAX (单格触发概率硬上限) ----

    private static final double CHANCE_MAX_EASY = 0.00;
    private static final double CHANCE_MAX_MEDIUM = 0.12;
    private static final double CHANCE_MAX_HARD = 0.25;

    public static double trapChanceMax(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> CHANCE_MAX_EASY;
            case MEDIUM -> CHANCE_MAX_MEDIUM;
            case HARD -> CHANCE_MAX_HARD;
        };
    }

    // ---- 9.3 每 16^3 子区致死陷阱数上限 ----

    private static final int LETHAL_CAP_EASY = 0;
    private static final int LETHAL_CAP_MEDIUM = 2;
    private static final int LETHAL_CAP_HARD = 4;

    public static int lethalPerSubzoneCap(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> LETHAL_CAP_EASY;
            case MEDIUM -> LETHAL_CAP_MEDIUM;
            case HARD -> LETHAL_CAP_HARD;
        };
    }

    // ---- 9.3 两个致死陷阱最小间距 ----

    private static final int SPACING_MEDIUM = 6;
    private static final int SPACING_HARD = 5;

    /** 致死陷阱最小间距 (9.3); Easy 无致死陷阱故取 Medium 值占位。 */
    public static int minLethalSpacing(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY, MEDIUM -> SPACING_MEDIUM;
            case HARD -> SPACING_HARD;
        };
    }

    // ---- 9.5 / 11.x 出生安全半径 ----

    /** SPAWN_SAFE_R (9.5 步骤1 / 11.7, 建议 8 格): 半径内剔除全部致死陷阱。 */
    public static final int SPAWN_SAFE_R = 8;

    // ---- 9.7 身后刷怪安全约束 ----

    /** 最小生成距离 (9.7): 不得贴脸。 */
    public static final int MOB_MIN_SPAWN_DIST = 8;

    /** 最大生成距离 (9.7): 太远无压力意义。 */
    public static final int MOB_MAX_SPAWN_DIST = 20;

    /** 视锥外阈值 cos(70deg): dot(look,dir) < 此值即在视野外 (9.7)。 */
    public static final double MOB_BEHIND_COS = Math.cos(Math.toRadians(70.0));

    /** 同玩家身后刷怪冷却 tick (9.7)。 */
    public static final int MOB_BEHIND_COOLDOWN_TICKS = 100;

    // ---- 9.6 动态陷阱节流 ----

    /** 局部坍塌: 每玩家最小间隔 tick (9.6)。 */
    public static final int COLLAPSE_PER_PLAYER_COOLDOWN_TICKS = 200;

    /** 岩浆喷发: 每实例冷却 tick (9.6)。 */
    public static final int LAVA_BURST_COOLDOWN_TICKS = 300;

    /** 岩浆喷发: 喷出后自动回收延迟 tick (9.6)。 */
    public static final int LAVA_BURST_RECYCLE_TICKS = 5;

    /** 坍塌累计伤害封顶 (9.4/9.6)。 */
    public static final float COLLAPSE_DAMAGE_CAP = 6.0f;

    /** 坍塌单次列数上下界 (9.6: 1-3 列)。 */
    public static final int COLLAPSE_MIN_COLUMNS = 1;
    public static final int COLLAPSE_MAX_COLUMNS = 3;

    // ---- 9.6 danger 门控阈值 (归一化 [0,1], 与第十章 danger 量纲一致) ----

    /** 身后刷苦力怕 danger 阈值 (DANGER_THRESH_CREEPER)。 */
    public static final float DANGER_THRESH_CREEPER = 0.50f;

    /** 局部坍塌 danger 阈值。 */
    public static final float DANGER_THRESH_COLLAPSE = 0.55f;

    /** 岩浆喷发 danger 阈值 (高危陷阱, 阈值最高)。 */
    public static final float DANGER_THRESH_LAVA = 0.70f;

    /** 单实例每评估周期动态陷阱触发次数封顶 (9.8 开销控制)。 */
    public static final int DYNAMIC_TRIGGERS_PER_EVAL = 1;

    // ---- 9.8 / 19.2 坍塌作用半径 (须 <= load.tickRadius) ----

    /** 动态坍塌/岩浆作用半径 (19.2: 必须落在 ticking 窗口内, 建议 <= load.tickRadius=4 区块对应方块半径)。 */
    public static final int DYNAMIC_EFFECT_RADIUS = 6;
}
