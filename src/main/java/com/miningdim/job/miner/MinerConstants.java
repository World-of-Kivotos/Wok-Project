package com.miningdim.job.miner;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 矿工职业全部成长曲线 / CD / 充能 / 时运 / danger 系数的唯一数值源 (Miner_Job_DesignSpec 第三-八章 + 契约 numbers)。
 *
 * 为何落在本子系统而非中央 {@link com.miningdim.config.MiningServerConfig}: 与 economy 子系统
 * {@link com.miningdim.economy.EconomyConstants} 同理 —— 中央配置门面 {@link com.miningdim.core.IMiningConfig}
 * 为阶段0 定稿, 不暴露 {@code miner.*} 键; 在 ConfigSystem 把 {@code miner.*} 接入 ForgeConfigSpec 之前,
 * 本类是矿工各能力生效所必需的真实数值 (非占位)。集成阶段把这些初值搬进 MiningServerConfig 后, MinerSkills
 * 改读配置门面 (见 foundationGaps), 当前阶段以本类为唯一来源, 与设计 spec 表逐项对齐。
 *
 * 等级口径: 矿工等级 1-10。"解锁后逐级变强" —— 未达解锁级数值为 0 / 不生效; 解锁级到 L10 线性插值
 * (见 {@link MinerSkills} 的 lerp 工具)。本类只存端点与解锁级, 逐级值由 MinerSkills 计算 (单一解析点)。
 */
public final class MinerConstants {

    private MinerConstants() {
    }

    /** 每秒 tick 数 (原版固定 20), 秒<->tick 换算。 */
    public static final int TICKS_PER_SECOND = 20;

    /** 矿工状态 HUD 的服务端节流推送间隔 (tick): 每 0.5s 推一次瞬态态 (充能/开关/探测 CD) 到客户端 overlay。 */
    public static final int HUD_STATUS_PUSH_INTERVAL_TICKS = 10;

    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 10;

    // ============================================================
    // 五、被动类 (纯成长, 无 CD)
    // ============================================================

    /** 挖矿提速: 解锁 L1。挖速倍率加成 +15%(L1) -> +110%(L10); event.setNewSpeed(speed * (1 + bonus))。 */
    public static final int DIG_SPEED_UNLOCK_LEVEL = 1;
    public static final double DIG_SPEED_BONUS_AT_UNLOCK = 0.15D;
    public static final double DIG_SPEED_BONUS_AT_MAX = 1.10D;

    /** 省耐久: 解锁 L1。不耗耐久概率 5%(L1) -> 30%(L10) 封顶。 */
    public static final int DURABILITY_SAVE_UNLOCK_LEVEL = 1;
    public static final double DURABILITY_SAVE_CHANCE_AT_UNLOCK = 0.05D;
    public static final double DURABILITY_SAVE_CHANCE_AT_MAX = 0.30D;

    /** 抗疲劳: 解锁 L4 (里程碑)。免疫挖掘疲劳 (与挖速同走 BreakSpeed 统一结算)。 */
    public static final int MINING_FATIGUE_IMMUNE_UNLOCK_LEVEL = 4;

    /** 便利偏好: 自动入包解锁 L2; 自动熔炼基础(铁/铜) L6; 自动熔炼加金 L8。 */
    public static final int AUTO_COLLECT_UNLOCK_LEVEL = 2;
    public static final int AUTO_SMELT_BASE_UNLOCK_LEVEL = 6;
    public static final int AUTO_SMELT_GOLD_UNLOCK_LEVEL = 8;

    // ============================================================
    // 六、获取更多矿 (矿脉时运, 方案 B)
    // ============================================================

    /** 矿脉时运: 解锁 L4。额外掉落期望 +8%(L4) -> +50%(L10); 时运对一切矿生效, 产出仍受隐藏软上限封顶。 */
    public static final int FORTUNE_UNLOCK_LEVEL = 4;
    public static final double FORTUNE_EXTRA_AT_UNLOCK = 0.08D;
    public static final double FORTUNE_EXTRA_AT_MAX = 0.50D;

    // ============================================================
    // 四、速挖类 (连锁 / 隧道, 慢充能 + 长 CD)
    // ============================================================

    /** 连锁挖矿: 解锁 L2。充能池 16(L2) -> 48(L10) 块; 整池回满 ~5 分(L2) -> ~3.5 分(L10)。 */
    public static final int CHAIN_UNLOCK_LEVEL = 2;
    public static final int CHAIN_POOL_AT_UNLOCK = 16;
    public static final int CHAIN_POOL_AT_MAX = 48;
    /** 整池回满时长 (tick): 5 分钟 = 6000 tick (L2) -> 3.5 分钟 = 4200 tick (L10)。 */
    public static final int CHAIN_REFILL_FULL_TICKS_AT_UNLOCK = 5 * 60 * TICKS_PER_SECOND; // 6000
    public static final int CHAIN_REFILL_FULL_TICKS_AT_MAX = (int) (3.5 * 60 * TICKS_PER_SECOND); // 4200

    /** 隧道挖 (满级): 解锁 L9。3x3 隧道一段; CD 30s(L9) -> 20s(L10)。 */
    public static final int TUNNEL_UNLOCK_LEVEL = 9;
    public static final int TUNNEL_CD_TICKS_AT_UNLOCK = 30 * TICKS_PER_SECOND; // 600
    public static final int TUNNEL_CD_TICKS_AT_MAX = 20 * TICKS_PER_SECOND;    // 400
    /** 隧道一段挖掘的纵深格数 (3x3 横截面, 沿玩家朝向掘进)。 */
    public static final int TUNNEL_DEPTH = 4;

    // ============================================================
    // 三、探测类 (开关/脉冲 + 长 CD, 服务端权威)
    // ============================================================

    /** 矿物探测: 解锁 L3。半径 6(L3) -> 16(L10); CD 300s(L3) -> 180s(L10); 脉冲 ~8s 熄灭。 */
    public static final int ORE_SCAN_UNLOCK_LEVEL = 3;
    public static final int ORE_SCAN_RADIUS_AT_UNLOCK = 6;
    public static final int ORE_SCAN_RADIUS_AT_MAX = 16;
    public static final int ORE_SCAN_CD_TICKS_AT_UNLOCK = 300 * TICKS_PER_SECOND; // 6000
    public static final int ORE_SCAN_CD_TICKS_AT_MAX = 180 * TICKS_PER_SECOND;    // 3600
    /** 探测高亮脉冲存活时长 (~8s)。 */
    public static final int SCAN_PULSE_TICKS = 8 * TICKS_PER_SECOND; // 160
    /** 单矿种探测每次下发坐标上限 (防一次洗出整张矿图; 服务端只下发球内确有的, 再加此硬顶)。 */
    public static final int ORE_SCAN_MAX_RESULTS = 64;

    /** 矿物探测里程碑: L3 铁/煤; L6 +钻; L8 +金/残骸。 */
    public static final int ORE_SCAN_DIAMOND_LEVEL = 6;
    public static final int ORE_SCAN_GOLD_DEBRIS_LEVEL = 8;

    /** 陷阱探测: 解锁 L5。半径 6(L5) -> 12(L10) (spec 未给起止, 取矿探一半量级的合理初值, PENDING 校验); CD 240s -> 150s。 */
    public static final int TRAP_SCAN_UNLOCK_LEVEL = 5;
    public static final int TRAP_SCAN_RADIUS_AT_UNLOCK = 6;
    public static final int TRAP_SCAN_RADIUS_AT_MAX = 12;
    public static final int TRAP_SCAN_CD_TICKS_AT_UNLOCK = 240 * TICKS_PER_SECOND; // 4800
    public static final int TRAP_SCAN_CD_TICKS_AT_MAX = 150 * TICKS_PER_SECOND;    // 3000
    /** 陷阱探测含致死陷阱的里程碑: L5 仅非致死; L8 含致死 (TNT/岩浆袋)。 */
    public static final int TRAP_SCAN_LETHAL_LEVEL = 8;
    /** 单次陷阱探测下发坐标上限。 */
    public static final int TRAP_SCAN_MAX_RESULTS = 64;

    // ============================================================
    // 七、生存类 (守不漂战斗力红线)
    // ============================================================

    /** 耐压 (减 danger 时间项累积): 解锁 L4, 被动。时间项系数 0.85x(L4) -> 0.6x 封底(L10); 不低于 0.6, 不钳 0。 */
    public static final int DANGER_RESIST_UNLOCK_LEVEL = 4;
    public static final double DANGER_TIME_FACTOR_AT_UNLOCK = 0.85D;
    public static final double DANGER_TIME_FACTOR_AT_MAX = 0.60D;
    /** 耐压封底 (红线): 即使满级也不得低于此, 防矿工实质免疫压力系统。 */
    public static final double DANGER_TIME_FACTOR_FLOOR = 0.60D;

    /** 矿脉抗性 (减陷阱专属来源伤): 解锁 L5, 被动。-10%(L5) -> -35%(L10); 仅陷阱专属来源, 对怪/枪/玩家 TNT 零作用。 */
    public static final int VEIN_RESIST_UNLOCK_LEVEL = 5;
    public static final double VEIN_RESIST_REDUCTION_AT_UNLOCK = 0.10D;
    public static final double VEIN_RESIST_REDUCTION_AT_MAX = 0.35D;
    /** 矿脉抗性减伤封顶 (红线): 不得超过 35%, 防变战斗减伤天赋。 */
    public static final double VEIN_RESIST_REDUCTION_CAP = 0.35D;
    /** 无法精确区分陷阱专属来源时的降级反应窗时长 (~0.5s)。 */
    public static final int VEIN_RESIST_FALLBACK_IFRAME_TICKS = 10;

    /** 脱险归途: 解锁 L7, 主动·开关+长 CD。CD 8 分(L7) -> 5 分(L10); 读条 ~3s; 受伤/移动即打断。 */
    public static final int EVACUATE_UNLOCK_LEVEL = 7;
    public static final int EVACUATE_CD_TICKS_AT_UNLOCK = 8 * 60 * TICKS_PER_SECOND; // 9600
    public static final int EVACUATE_CD_TICKS_AT_MAX = 5 * 60 * TICKS_PER_SECOND;    // 6000
    public static final int EVACUATE_CHANNEL_TICKS = 3 * TICKS_PER_SECOND; // 60
    /** 读条打断的位移阈值平方 (格^2): 偏离起点超过则打断 (约 0.6 格)。 */
    public static final double EVACUATE_MOVE_BREAK_DIST_SQ = 0.36D;

    /** 声东击西 (降压窗口): 解锁 L9, 主动·长 CD。短时压后方刷怪 ~数秒; CD 5 分(L9) -> 3.5 分(L10)。 */
    public static final int DECOY_UNLOCK_LEVEL = 9;
    public static final int DECOY_CD_TICKS_AT_UNLOCK = 5 * 60 * TICKS_PER_SECOND;     // 6000
    public static final int DECOY_CD_TICKS_AT_MAX = (int) (3.5 * 60 * TICKS_PER_SECOND); // 4200
    /** 声东击西的降压窗口时长 (复用 spawnFreeze 机制, 数秒)。 */
    public static final int DECOY_SPAWN_FREEZE_TICKS = 5 * TICKS_PER_SECOND; // 100

    // ============================================================
    // 八、难度门控 (L4 Medium / L8 Hard / L1-3 Easy)
    // ============================================================

    public static final int MEDIUM_MIN_MINER_LEVEL = 4;
    public static final int HARD_MIN_MINER_LEVEL = 8;

    // ============================================================
    // 速挖类硬白名单 / 硬排除 (代码级, 物理排除高价矿)
    // ============================================================

    /**
     * 连锁/隧道硬白名单 (Miner spec 第四章): 仅这些普通方块可连带破坏。
     * 石/深板岩/凝灰岩/花岗岩 + 煤/铁/铜 (含深层变体)。其余 (尤其高价矿) 一律停在边界。
     */
    public static final Block[] CHAIN_WHITELIST = {
            Blocks.STONE, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.GRANITE,
            Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
            Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
            Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE
    };

    /**
     * 连锁硬排除 (Miner spec 第四章): 高价矿 + 绿宝石, 物理排除使连锁停在其边界。
     * 此名单与 CHAIN_WHITELIST 互斥; 既不在白名单也不在排除名单的方块同样不连锁 (默认拒绝)。
     */
    public static final Block[] CHAIN_HARD_EXCLUDE = {
            Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE,
            Blocks.ANCIENT_DEBRIS,
            Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE
    };
}
