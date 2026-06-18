package com.miningdim.job.engineer;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 千年工程师 SERVER 级配置 spec 持有者 (MillenniumEngineer_Mod_DesignSpec 10.3 C6 硬约束: 全部平衡数值进
 * ForgeConfigSpec, 业务类内硬编码字面量即缺陷)。所有矿石绑档消耗 / 生成耗时 / 修复曲线 / 单板原始经验 / 特效
 * 阈值与系数 / 图腾 CD 与复活百分比 / 护盾免疫窗与次数 / 机能修复递减 / 闪耀概率与残骸 的唯一数据源。
 *
 * 本任务铁律: 不修改中央 config.MiningServerConfig (那是别的子系统拥有的文件)。故工程师自带一份独立 SERVER spec
 * (文件 miningdim-engineer.toml), 由 {@link EngineerSystem#register} 经 ModLoadingContext.registerConfig 注册。
 * 集成阶段若决定合并进 MiningServerConfig, 迁移只是搬运 spec 段, 业务代码经 *.get() 实时读取不受影响 (不缓存)。
 *
 * 战斗向铁律 (部署环境 80 血 + TACZ 高 DPS + 死亡不掉落): 全部战斗向数值用 % 最大血量 / % 最大耐久 建模,
 * 绝不套原版 20 血常量。机能修复递减安全阀 (100/50/25/12.5) 与图腾共享 CD (人级 30min) 是防战力叠叠乐核心。
 */
public final class EngineerConfig {

    public static final ForgeConfigSpec SPEC;

    // ---- 3.2 矿石绑档生产配方 (单板矿耗为定值) ----
    public static final ForgeConfigSpec.IntValue LOW_IRON_COST;
    public static final ForgeConfigSpec.IntValue MEDIUM_GOLD_COST;
    public static final ForgeConfigSpec.IntValue HIGH_DIAMOND_COST;
    public static final ForgeConfigSpec.IntValue SUPERIOR_NETHERITE_COST;
    public static final ForgeConfigSpec.IntValue TRANSCENDENT_NETHERITE_COST;
    public static final ForgeConfigSpec.IntValue RADIANT_NETHERITE_COST;
    /** 极品板单次产出数 (1 下界合金锭 -> 2 板)。其余档单次产出 1 板。 */
    public static final ForgeConfigSpec.IntValue SUPERIOR_OUTPUT_COUNT;

    // ---- 4.1 生成耗时 (机器档决定; 单位 tick; 实现期标定, 给保守初值) ----
    public static final ForgeConfigSpec.IntValue PRODUCE_TICKS_LOW;
    public static final ForgeConfigSpec.IntValue PRODUCE_TICKS_MEDIUM;
    public static final ForgeConfigSpec.IntValue PRODUCE_TICKS_HIGH;
    public static final ForgeConfigSpec.IntValue PRODUCE_TICKS_SUPERIOR;
    public static final ForgeConfigSpec.IntValue PRODUCE_TICKS_TRANSCENDENT;
    public static final ForgeConfigSpec.IntValue PRODUCE_TICKS_RADIANT;

    // ---- 5.1 修复效率曲线 (低中高固定值; 极品/超凡按最大耐久百分比; 闪耀 100%) ----
    public static final ForgeConfigSpec.IntValue REPAIR_FIXED_LOW;
    public static final ForgeConfigSpec.IntValue REPAIR_FIXED_MEDIUM;
    public static final ForgeConfigSpec.IntValue REPAIR_FIXED_HIGH;
    public static final ForgeConfigSpec.DoubleValue REPAIR_PERCENT_SUPERIOR;
    public static final ForgeConfigSpec.DoubleValue REPAIR_PERCENT_TRANSCENDENT;

    // ---- 7.4 单档原始经验 (示例值; 用自己板修甲额外 +50%) ----
    public static final ForgeConfigSpec.IntValue RAW_XP_LOW;
    public static final ForgeConfigSpec.IntValue RAW_XP_MEDIUM;
    public static final ForgeConfigSpec.IntValue RAW_XP_HIGH;
    public static final ForgeConfigSpec.IntValue RAW_XP_SUPERIOR;
    public static final ForgeConfigSpec.IntValue RAW_XP_TRANSCENDENT;
    public static final ForgeConfigSpec.IntValue RAW_XP_RADIANT;
    /** 用自己产的板修甲, 修复经验相对单档原始经验的额外加成 (0.5 = +50%)。 */
    public static final ForgeConfigSpec.DoubleValue OWN_PLATE_REPAIR_XP_BONUS;
    /**
     * 4.2/7.4 品质->经验杠杆: 该板生产时累计品质命中数对原始经验的线性加成系数 (xpMult = 1 + coef*qualityHits)。
     * 品质越高该板携带的原始经验越高 (再过每日衰减档入账)。0 = 关闭品质对经验的影响。
     */
    public static final ForgeConfigSpec.DoubleValue PRODUCTION_XP_QUALITY_COEF;

    // ---- 4.2 纳米校准 QTE (服务端时序权威) ----
    /** 每轮扫描条总宽 (逻辑刻度); 游标在 [0,WIDTH) 往返。 */
    public static final ForgeConfigSpec.IntValue CALIBRATION_BAR_WIDTH;
    /** 游标每服务端 tick 推进的刻度 (扫描速度)。 */
    public static final ForgeConfigSpec.IntValue CALIBRATION_CURSOR_SPEED;
    /** 绿色校准区宽度 (逻辑刻度); 每轮随机落点。 */
    public static final ForgeConfigSpec.IntValue CALIBRATION_GREEN_WIDTH;
    /** 命中绿区一次推进的生产进度。 */
    public static final ForgeConfigSpec.IntValue CALIBRATION_HIT_PROGRESS;
    /** 未命中 (点了但不在绿区) 推进的生产进度 (远小于命中)。 */
    public static final ForgeConfigSpec.IntValue CALIBRATION_MISS_PROGRESS;
    /** 完成一次生产所需累计进度。 */
    public static final ForgeConfigSpec.IntValue CALIBRATION_PROGRESS_GOAL;
    /** 品质达到该阈值 (累计命中次数) 时, 产量有概率额外 +1 板。 */
    public static final ForgeConfigSpec.IntValue CALIBRATION_QUALITY_BONUS_THRESHOLD;
    /** 品质达阈值后额外 +1 板的概率 (0-1)。 */
    public static final ForgeConfigSpec.DoubleValue CALIBRATION_BONUS_PLATE_CHANCE;

    // ---- 6.2 纳米重塑 (回护甲耐久; 损失 > 阈值失效) ----
    /** 重塑失效的耐久损失阈值 (0.40 = 损失超 40% 即停; 规格建议放宽到 40 防枪火立即触发)。 */
    public static final ForgeConfigSpec.DoubleValue RESHAPE_FAIL_DAMAGE_PCT;
    /** 重塑每生效 tick 回的护甲耐久点数。 */
    public static final ForgeConfigSpec.IntValue RESHAPE_DURABILITY_PER_TICK;
    /** 重塑/机能修复的生效周期 (tick); 每隔此间隔结算一次。 */
    public static final ForgeConfigSpec.IntValue EFFECT_TICK_INTERVAL;

    // ---- 6.2 纳米机能修复 (回血 % 最大血量; 耐久 < 50% 失效; 递减安全阀 100/50/25/12.5) ----
    /** 单件机能修复每周期回血占最大血量比例 (0.02 = 2%; 规格示例 2%/s)。 */
    public static final ForgeConfigSpec.DoubleValue VITALITY_HEAL_PCT_PER_TICK;
    /** 机能修复失效的耐久阈值 (0.50 = 耐久低于 50% 该件停止回血)。 */
    public static final ForgeConfigSpec.DoubleValue VITALITY_FAIL_DURABILITY_PCT;

    // ---- 6.2 纳米多重护盾 (X 秒全免疫; 每 60s 一次; 5 次用尽) ----
    /** 护盾免疫窗时长 (tick); 规格留 X 可配且保守 (勿破坏 TACZ attrition)。 */
    public static final ForgeConfigSpec.IntValue SHIELD_IMMUNITY_TICKS;
    /** 护盾两次生成的间隔 (tick); 规格每 60s 一次 = 1200 tick。 */
    public static final ForgeConfigSpec.IntValue SHIELD_REGEN_INTERVAL_TICKS;
    /** 护盾按件可生成总次数 (用尽后该件失去护盾)。 */
    public static final ForgeConfigSpec.IntValue SHIELD_MAX_CHARGES;

    // ---- 6.2 纳米末影心肺反应器 (图腾; 拦截致死; 复活到 % 最大血量; 人级共享 CD) ----
    /** 图腾共享 CD (tick); 规格 30min = 36000 tick。 */
    public static final ForgeConfigSpec.IntValue TOTEM_SHARED_CD_TICKS;
    /** 图腾复活后血量占最大血量比例 (0.50 = 50%; 80 血时 = 40 血)。 */
    public static final ForgeConfigSpec.DoubleValue TOTEM_REVIVE_HEALTH_PCT;
    /** 图腾复活后伤害免疫窗 (tick)。 */
    public static final ForgeConfigSpec.IntValue TOTEM_INVULN_TICKS;
    /** 图腾触发后, 每件带图腾效果的甲各扣最大耐久比例 (0.25 = 各扣 25%)。 */
    public static final ForgeConfigSpec.DoubleValue TOTEM_DURABILITY_COST_PCT;

    // ---- 6.1 高档起掷特效概率 (品质越高越高; base + qualityCoef * 品质命中次数, 钳 [0,1]) ----
    public static final ForgeConfigSpec.DoubleValue EFFECT_ROLL_BASE_CHANCE;
    public static final ForgeConfigSpec.DoubleValue EFFECT_ROLL_QUALITY_COEF;

    // ---- 3.2 闪耀板概率产出 + 残骸返还 (PENDING 12.4 给可配初值) ----
    /** 闪耀板单次产出 1 板的概率 (失败返还残骸)。 */
    public static final ForgeConfigSpec.DoubleValue RADIANT_SUCCESS_CHANCE;
    /** 闪耀失败时返还的下界合金锭残骸数 (0=不返还)。 */
    public static final ForgeConfigSpec.IntValue RADIANT_FAIL_REFUND;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("recipe");
        LOW_IRON_COST = b.comment("Iron ingots consumed per low nano plate (3.2: 4 iron -> 1)")
                .defineInRange("lowIronCost", 4, 1, 64);
        MEDIUM_GOLD_COST = b.comment("Gold ingots per medium plate (5 gold -> 1)")
                .defineInRange("mediumGoldCost", 5, 1, 64);
        HIGH_DIAMOND_COST = b.comment("Diamonds per high plate (3 diamond -> 1)")
                .defineInRange("highDiamondCost", 3, 1, 64);
        SUPERIOR_NETHERITE_COST = b.comment("Netherite ingots per superior batch (1 -> 2 plates)")
                .defineInRange("superiorNetheriteCost", 1, 1, 64);
        TRANSCENDENT_NETHERITE_COST = b.comment("Netherite ingots per transcendent plate (1 -> 1)")
                .defineInRange("transcendentNetheriteCost", 1, 1, 64);
        RADIANT_NETHERITE_COST = b.comment("Netherite ingots per radiant attempt (2 -> chance 1)")
                .defineInRange("radiantNetheriteCost", 2, 1, 64);
        SUPERIOR_OUTPUT_COUNT = b.comment("Plates produced per superior batch (3.2: 1 netherite -> 2)")
                .defineInRange("superiorOutputCount", 2, 1, 64);
        b.pop();

        b.push("produceTicks");
        b.comment("Ticks to complete one production cycle (4.1 machine tier sets speed; calibrated later).");
        PRODUCE_TICKS_LOW = b.defineInRange("low", 100, 1, 72000);
        PRODUCE_TICKS_MEDIUM = b.defineInRange("medium", 120, 1, 72000);
        PRODUCE_TICKS_HIGH = b.defineInRange("high", 160, 1, 72000);
        PRODUCE_TICKS_SUPERIOR = b.defineInRange("superior", 200, 1, 72000);
        PRODUCE_TICKS_TRANSCENDENT = b.defineInRange("transcendent", 240, 1, 72000);
        PRODUCE_TICKS_RADIANT = b.defineInRange("radiant", 300, 1, 72000);
        b.pop();

        b.push("repair");
        b.comment("5.1 repair curve. low/medium/high are fixed durability points; superior/transcendent are % of max durability; radiant is always 100%.");
        REPAIR_FIXED_LOW = b.defineInRange("fixedLow", 100, 1, 100000);
        REPAIR_FIXED_MEDIUM = b.defineInRange("fixedMedium", 250, 1, 100000);
        REPAIR_FIXED_HIGH = b.defineInRange("fixedHigh", 600, 1, 100000);
        REPAIR_PERCENT_SUPERIOR = b.defineInRange("percentSuperior", 0.30, 0.0, 1.0);
        REPAIR_PERCENT_TRANSCENDENT = b.defineInRange("percentTranscendent", 0.65, 0.0, 1.0);
        b.pop();

        b.push("xp");
        b.comment("7.4 raw xp per plate tier; framework applies daily decay on top.");
        RAW_XP_LOW = b.defineInRange("rawLow", 15, 0, 100000);
        RAW_XP_MEDIUM = b.defineInRange("rawMedium", 30, 0, 100000);
        RAW_XP_HIGH = b.defineInRange("rawHigh", 60, 0, 100000);
        RAW_XP_SUPERIOR = b.defineInRange("rawSuperior", 110, 0, 100000);
        RAW_XP_TRANSCENDENT = b.defineInRange("rawTranscendent", 200, 0, 100000);
        // 7.4 单档原始经验只给到 "超凡 200", 未给闪耀值 (PENDING 12.7 单板经验标定)。闪耀是 L10 毕业档、
        // 成本最高 (2 下界合金锭概率产出), 经验不应低于超凡; spec 缺值, 此处暂沿用超凡 200 待标定 (config 可调)。
        RAW_XP_RADIANT = b.comment("PENDING 12.7: spec 7.4 leaves radiant raw xp undefined; provisionally equals transcendent (200) until calibrated.")
                .defineInRange("rawRadiant", 200, 0, 100000);
        OWN_PLATE_REPAIR_XP_BONUS = b.comment("Extra repair xp multiplier when repairing with your own plate (0.5 = +50%)")
                .defineInRange("ownPlateRepairBonus", 0.5, 0.0, 10.0);
        PRODUCTION_XP_QUALITY_COEF = b.comment("4.2/7.4 quality->xp lever: raw xp multiplier per quality hit baked into the plate (xpMult = 1 + coef * qualityHits). 0 disables quality effect on xp.")
                .defineInRange("productionXpQualityCoef", 0.05, 0.0, 10.0);
        b.pop();

        b.push("calibration");
        b.comment("4.2 nano-calibration QTE (server authoritative). Green zone is random each round (anti-macro).");
        CALIBRATION_BAR_WIDTH = b.defineInRange("barWidth", 200, 10, 10000);
        CALIBRATION_CURSOR_SPEED = b.defineInRange("cursorSpeed", 6, 1, 1000);
        CALIBRATION_GREEN_WIDTH = b.defineInRange("greenWidth", 30, 1, 10000);
        CALIBRATION_HIT_PROGRESS = b.defineInRange("hitProgress", 20, 1, 100000);
        CALIBRATION_MISS_PROGRESS = b.defineInRange("missProgress", 2, 0, 100000);
        CALIBRATION_PROGRESS_GOAL = b.defineInRange("progressGoal", 100, 1, 1000000);
        CALIBRATION_QUALITY_BONUS_THRESHOLD = b.comment("Hits accumulated before bonus +1 plate becomes possible")
                .defineInRange("qualityBonusThreshold", 4, 0, 100000);
        CALIBRATION_BONUS_PLATE_CHANCE = b.defineInRange("bonusPlateChance", 0.5, 0.0, 1.0);
        b.pop();

        b.push("effectTick");
        EFFECT_TICK_INTERVAL = b.comment("6.2 reshape/vitality settle period in ticks (20 = 1s)")
                .defineInRange("intervalTicks", 20, 1, 200);
        b.pop();

        b.push("reshape");
        b.comment("6.2 nano-reshape: regen armor's own durability; fails when lost durability exceeds threshold.");
        RESHAPE_FAIL_DAMAGE_PCT = b.defineInRange("failDamagePct", 0.40, 0.0, 1.0);
        RESHAPE_DURABILITY_PER_TICK = b.defineInRange("durabilityPerTick", 2, 1, 100000);
        b.pop();

        b.push("vitality");
        b.comment("6.2 nano-vitality: heal wearer % max health; fails when armor durability < threshold; decaying safety valve 100/50/25/12.5% across 4 pieces.");
        VITALITY_HEAL_PCT_PER_TICK = b.defineInRange("healPctPerTick", 0.02, 0.0, 1.0);
        VITALITY_FAIL_DURABILITY_PCT = b.defineInRange("failDurabilityPct", 0.50, 0.0, 1.0);
        b.pop();

        b.push("shield");
        b.comment("6.2 nano-multishield: X-second full immunity window; one charge generated each interval; bounded charges.");
        SHIELD_IMMUNITY_TICKS = b.defineInRange("immunityTicks", 40, 1, 1200);
        SHIELD_REGEN_INTERVAL_TICKS = b.defineInRange("regenIntervalTicks", 1200, 20, 72000);
        SHIELD_MAX_CHARGES = b.defineInRange("maxCharges", 5, 1, 100);
        b.pop();

        b.push("totem");
        b.comment("6.2 nano-reactor totem: intercept lethal hit; revive to % max health; person-level shared CD; durability cost per piece.");
        TOTEM_SHARED_CD_TICKS = b.defineInRange("sharedCdTicks", 36000, 20, 1728000);
        TOTEM_REVIVE_HEALTH_PCT = b.defineInRange("reviveHealthPct", 0.50, 0.05, 1.0);
        TOTEM_INVULN_TICKS = b.defineInRange("invulnTicks", 40, 0, 1200);
        TOTEM_DURABILITY_COST_PCT = b.defineInRange("durabilityCostPct", 0.25, 0.0, 1.0);
        b.pop();

        b.push("effectRoll");
        b.comment("6.1 effect roll chance on repair (high tier+). chance = base + coef * qualityHits, clamped [0,1].");
        EFFECT_ROLL_BASE_CHANCE = b.defineInRange("baseChance", 0.20, 0.0, 1.0);
        EFFECT_ROLL_QUALITY_COEF = b.defineInRange("qualityCoef", 0.05, 0.0, 1.0);
        b.pop();

        b.push("radiant");
        b.comment("3.2 radiant plate probabilistic output (PENDING 12.4: tuneable initial values).");
        RADIANT_SUCCESS_CHANCE = b.defineInRange("successChance", 0.50, 0.0, 1.0);
        RADIANT_FAIL_REFUND = b.comment("Netherite ingots refunded on a failed radiant attempt (debris)")
                .defineInRange("failRefund", 1, 0, 64);
        b.pop();

        SPEC = b.build();
    }

    /**
     * GameTest 专用: 若 SPEC 尚未被 Forge 加载 (本子系统在集成阶段才接进 MiningDim, 故 runGameTestServer 时
     * 配置未注册), 用一份填满默认值的内存 config 绑定 SPEC, 使各 {@code .get()} 返回 spec 默认值。
     * 集成接线后 Forge 会以真实 toml 覆盖此绑定 (isLoaded 已 true 时本方法直接返回, 不覆盖运行期配置)。
     *
     * 仅供 {@link EngineerGameTests} 调用; 生产路径由 EngineerSystem.register 经 ModLoadingContext 加载, 不走此。
     */
    public static void ensureLoadedForTest() {
        if (SPEC.isLoaded()) {
            return;
        }
        CommentedConfig config = CommentedConfig.inMemory();
        SPEC.correct(config);   // 用 spec 默认值填满空 config。
        SPEC.setConfig(config); // 绑定, 使 .get() 返回默认值。
    }

    private EngineerConfig() {
    }
}
