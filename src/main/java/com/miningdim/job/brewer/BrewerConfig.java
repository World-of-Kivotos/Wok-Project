package com.miningdim.job.brewer;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 酿酒师全部平衡数值的唯一来源 (Brewer_Job_DesignSpec F086: 硬编码即缺陷, 走 ForgeConfigSpec, 范式照抄
 * {@link com.miningdim.job.chef.ChefConfig})。自持一份 SERVER 级 SPEC, 由 {@link BrewerSystem} 在自己
 * package 内注册 (不碰中央 MiningServerConfig)。
 *
 * 业务代码经各 *.get() 实时读取 (禁缓存)。默认值一律照抄现状 (原 {@link BrewerConstants} 硬编码值), 本次
 * 搬迁不改任何数值语义, 拍板前调整只能改 toml 不改代码。
 *
 * 未搬入本类的常量 (MILLIS_PER_VINTAGE_YEAR / CELLAR_SETTLE_INTERVAL_TICKS / CELLAR_WINE_SLOTS /
 * MAX_LAYERS_PER_TYPE / VINTAGE_LAYER_T1-T3 / BREW_DURATION_TICKS / BREW_OUTPUT_COUNT) 仍留在
 * {@link BrewerConstants}, 理由见该类 javadoc。
 */
public final class BrewerConfig {

    private BrewerConfig() {
    }

    public static final ForgeConfigSpec SPEC;

    // ---- 燃料门控 (酒窖箱干小麦耗量) ----
    public static final ForgeConfigSpec.IntValue DRIED_WHEAT_PER_BOTTLE_YEAR;
    public static final ForgeConfigSpec.DoubleValue FUEL_QUAD_COEF;

    // ---- 断粮衰退 (酒窖箱) ----
    public static final ForgeConfigSpec.DoubleValue SPOILAGE_DECAY_YEARS_PER_DAY;

    // ---- 陈酿 (年份时钟) ----
    public static final ForgeConfigSpec.DoubleValue FULL_MOON_BONUS;

    // ---- 闪耀永久层数 (九种"一条命"特殊每层收益) ----
    public static final ForgeConfigSpec.DoubleValue GIN_MAX_HEALTH_PCT_PER_LAYER;
    public static final ForgeConfigSpec.DoubleValue GLOBAL_BONUS_MAX_HEALTH_CAP_PCT;
    public static final ForgeConfigSpec.DoubleValue VODKA_REDUCTION_PER_LAYER;
    public static final ForgeConfigSpec.DoubleValue WHISKEY_HEAL_PCT_PER_LAYER;
    public static final ForgeConfigSpec.IntValue WHISKEY_HEAL_INTERVAL_TICKS;
    public static final ForgeConfigSpec.DoubleValue CHAMPAGNE_HEAL_PCT_PER_LAYER;
    public static final ForgeConfigSpec.IntValue CHAMPAGNE_HEAL_INTERVAL_TICKS;
    public static final ForgeConfigSpec.DoubleValue RUM_MOVE_SPEED_PCT_PER_LAYER;
    public static final ForgeConfigSpec.DoubleValue TEQUILA_ATTACK_PER_LAYER;
    public static final ForgeConfigSpec.DoubleValue MAOTAI_XP_PCT_PER_LAYER;

    // ---- 喝酒效果软上限 (强度 S 缩放; 战斗类收紧/续航类放宽) ----
    public static final ForgeConfigSpec.DoubleValue COMBAT_SOFTCAP_KNEE;
    public static final ForgeConfigSpec.DoubleValue COMBAT_SOFTCAP_DIMINISH;
    public static final ForgeConfigSpec.DoubleValue LOOSE_SOFTCAP_KNEE;
    public static final ForgeConfigSpec.DoubleValue LOOSE_SOFTCAP_DIMINISH;
    public static final ForgeConfigSpec.DoubleValue AMP_PER_SOFT_STRENGTH;
    public static final ForgeConfigSpec.IntValue AMP_CAP_COMBAT;
    public static final ForgeConfigSpec.IntValue AMP_CAP_LOOSE;
    public static final ForgeConfigSpec.IntValue EFFECT_BASE_DURATION_TICKS;
    public static final ForgeConfigSpec.IntValue EFFECT_DURATION_PER_SOFT;
    public static final ForgeConfigSpec.IntValue EFFECT_MAX_DURATION_TICKS;
    public static final ForgeConfigSpec.DoubleValue WHISKEY_HEAL_PER_SOFT;
    public static final ForgeConfigSpec.IntValue MAOTAI_XP_PER_SOFT;

    // ---- 月光赌博 ----
    public static final ForgeConfigSpec.DoubleValue MOONSHINE_GOOD_BASE_PROB;
    public static final ForgeConfigSpec.DoubleValue MOONSHINE_GOOD_PROB_PER_STRENGTH;
    public static final ForgeConfigSpec.DoubleValue MOONSHINE_GOOD_PROB_MAX;
    public static final ForgeConfigSpec.IntValue MOONSHINE_BAD_DURATION_TICKS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("fuel");
        b.comment("每瓶酒每陈酿 1 年份的基础干小麦耗量 (年份 0 时的量; 实耗随年份二次递增, 见 quadCoef)");
        DRIED_WHEAT_PER_BOTTLE_YEAR = b.defineInRange("driedWheatPerBottleYear", 16, 0, 100000);
        b.comment("干小麦耗量随年份二次递增的系数 (超线性: 嫩酒便宜、高年份指数爆炸, 是软上限的经济一面)");
        FUEL_QUAD_COEF = b.defineInRange("quadCoef", 5.0D, 0.0D, 1000.0D);
        b.pop();

        b.push("spoilage");
        b.comment("断干小麦时年份的衰退速率 (每现实天倒扣的年份); 逼高端酒必须有不间断的农夫供应链");
        SPOILAGE_DECAY_YEARS_PER_DAY = b.defineInRange("decayYearsPerDay", 200.0D, 0.0D, 100000.0D);
        b.pop();

        b.push("aging");
        b.comment("满月 (moonPhase==0) 期间陈酿的额外年份加成比例 (与 Tide 满月稀有鱼同源的潮汐关联)");
        FULL_MOON_BONUS = b.defineInRange("fullMoonBonus", 0.25D, 0.0D, 10.0D);
        b.pop();

        b.push("permanent_layers");
        b.comment("金酒: 每层额外最大生命占 base 的比例 (满 5 层 = 本值×5, 再经跨职业全局帽钳)");
        GIN_MAX_HEALTH_PCT_PER_LAYER = b.defineInRange("ginMaxHealthPctPerLayer", 0.10D, 0.0D, 10.0D);
        b.comment("跨职业额外最大生命的全局硬帽 (占 base 的比例; 塔罗+金酒等加总不得超此, 防生命叠叠乐)");
        GLOBAL_BONUS_MAX_HEALTH_CAP_PCT = b.defineInRange("globalBonusMaxHealthCapPct", 1.0D, 0.0D, 10.0D);
        b.comment("伏特加: 每层全伤减伤率。上界锁 0.2 是硬不变式而非平衡取舍——满 5 层的 rate 必须落在 [0,1],"
                + " 否则 PlayerDamageReduction.keepFactor 会按脏值抛 IllegalArgumentException, 不可越界");
        VODKA_REDUCTION_PER_LAYER = b.defineInRange("vodkaReductionPerLayer", 0.05D, 0.0D, 0.2D);
        b.comment("威士忌: 每层每周期回复的最大生命比例");
        WHISKEY_HEAL_PCT_PER_LAYER = b.defineInRange("whiskeyHealPctPerLayer", 0.05D, 0.0D, 10.0D);
        b.comment("威士忌回血周期 (tick; 默认 600 = 现实 30 秒)");
        WHISKEY_HEAL_INTERVAL_TICKS = b.defineInRange("whiskeyHealIntervalTicks", 600, 1, 72000);
        b.comment("香槟: 每层每周期回复的最大生命比例");
        CHAMPAGNE_HEAL_PCT_PER_LAYER = b.defineInRange("champagneHealPctPerLayer", 0.01D, 0.0D, 10.0D);
        b.comment("香槟回血周期 (tick; 默认 20 = 现实 1 秒)");
        CHAMPAGNE_HEAL_INTERVAL_TICKS = b.defineInRange("champagneHealIntervalTicks", 20, 1, 72000);
        b.comment("朗姆: 每层额外移速比例 (MOVEMENT_SPEED MULTIPLY_BASE)");
        RUM_MOVE_SPEED_PCT_PER_LAYER = b.defineInRange("rumMoveSpeedPctPerLayer", 0.06D, 0.0D, 10.0D);
        b.comment("龙舌兰: 每层额外近战攻击 (ATTACK_DAMAGE ADDITION; 枪走自己伤害管线天然不吃)");
        TEQUILA_ATTACK_PER_LAYER = b.defineInRange("tequilaAttackPerLayer", 3.0D, 0.0D, 1000.0D);
        b.comment("茅台: 每层职业经验加成比例 (酿酒台发经验时乘)");
        MAOTAI_XP_PCT_PER_LAYER = b.defineInRange("maotaiXpPctPerLayer", 0.10D, 0.0D, 10.0D);
        b.pop();

        b.push("softcap");
        b.comment("战斗类软上限拐点 (强度超此值后按 diminish 折算, 保战斗平衡)");
        COMBAT_SOFTCAP_KNEE = b.defineInRange("combatKnee", 8.0D, 0.0D, 10000.0D);
        b.comment("战斗类超拐点后的递减系数 (越小越难继续推高)");
        COMBAT_SOFTCAP_DIMINISH = b.defineInRange("combatDiminish", 0.15D, 0.0D, 1.0D);
        b.comment("续航/工具/经济类软上限拐点");
        LOOSE_SOFTCAP_KNEE = b.defineInRange("looseKnee", 16.0D, 0.0D, 10000.0D);
        b.comment("续航/工具/经济类超拐点后的递减系数");
        LOOSE_SOFTCAP_DIMINISH = b.defineInRange("looseDiminish", 0.40D, 0.0D, 1.0D);
        b.comment("持续效果每多少软化强度提升 1 放大等级。下界锁 0.1 是硬不变式——"
                + "BrewEffectEngine.amplifierFor 拿它作除数, 归零会抛 ArithmeticException/产生 Infinity, 不可为 0");
        AMP_PER_SOFT_STRENGTH = b.defineInRange("ampPerSoftStrength", 6.0D, 0.1D, 10000.0D);
        b.comment("战斗类持续效果放大等级上限 (0-indexed; 直接战力收紧)");
        AMP_CAP_COMBAT = b.defineInRange("ampCapCombat", 1, 0, 10);
        b.comment("续航/工具类持续效果放大等级上限 (0-indexed)");
        AMP_CAP_LOOSE = b.defineInRange("ampCapLoose", 2, 0, 10);
        b.comment("持续效果基础时长 (tick)");
        EFFECT_BASE_DURATION_TICKS = b.defineInRange("effectBaseDurationTicks", 400, 1, 72000);
        b.comment("持续效果每点软化强度追加的时长 (tick)");
        EFFECT_DURATION_PER_SOFT = b.defineInRange("effectDurationPerSoft", 30, 0, 72000);
        b.comment("持续效果时长上限 (tick)");
        EFFECT_MAX_DURATION_TICKS = b.defineInRange("effectMaxDurationTicks", 6000, 1, 432000);
        b.comment("威士忌瞬间恢复: 每点软化强度恢复的生命值");
        WHISKEY_HEAL_PER_SOFT = b.defineInRange("whiskeyHealPerSoft", 0.5D, 0.0D, 1000.0D);
        b.comment("茅台: 每点软化强度给的经验值");
        MAOTAI_XP_PER_SOFT = b.defineInRange("maotaiXpPerSoft", 2, 0, 10000);
        b.pop();

        b.push("moonshine");
        b.comment("月光赌博: 好结果基础概率");
        MOONSHINE_GOOD_BASE_PROB = b.defineInRange("goodBaseProb", 0.40D, 0.0D, 1.0D);
        b.comment("月光赌博: 每点强度对好结果概率的加成");
        MOONSHINE_GOOD_PROB_PER_STRENGTH = b.defineInRange("goodProbPerStrength", 0.01D, 0.0D, 1.0D);
        b.comment("月光赌博: 好结果概率上限 (永远留一线翻车)");
        MOONSHINE_GOOD_PROB_MAX = b.defineInRange("goodProbMax", 0.85D, 0.0D, 1.0D);
        b.comment("月光赌博: 坏结果效果时长 (tick, 等级 I 小惩罚, 死亡不掉落服上不致死)");
        MOONSHINE_BAD_DURATION_TICKS = b.defineInRange("badDurationTicks", 300, 1, 72000);
        b.pop();

        SPEC = b.build();
    }

    /**
     * 测试兜底加载: 若 SPEC 尚未绑定任何 config (GameTest 时 BrewerSystem 未接入 MiningDim, registerConfig
     * 未跑), 用一个空的内存 config 触发 spec 自校正填默认值, 使 *.get() 可读 (否则 dev 环境 get 抛 ISE)。
     * 已加载则空操作 (避免覆盖真实 SERVER toml)。仅 GameTest 调用。
     */
    public static void ensureLoadedForTest() {
        if (!SPEC.isLoaded()) {
            SPEC.setConfig(CommentedConfig.inMemory());
        }
    }
}
