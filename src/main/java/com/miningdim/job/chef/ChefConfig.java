package com.miningdim.job.chef;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 厨师全部平衡数值的唯一来源 (Chef_Job_DesignSpec 第九/十二章: 硬编码即缺陷 C6, 走 ForgeConfigSpec)。
 * 自持一份 SERVER 级 SPEC, 由 {@link ChefSystem} 在自己 package 内注册 (不碰中央 MiningServerConfig)。
 *
 * 业务代码经各 *.get() 实时读取 (禁缓存, 与工程惯例一致)。逐级数值表以 5 元数组 (低/中/高/超凡/闪耀) 表达,
 * 经 {@link ChefQuality#tier()} 索引。默认值照抄第六/七章定稿表 (膳香按第十二章第 4 项决议改 %最大血量)。
 *
 * 注释里的 "千分比/百分比" 单位约定: 战斗向 %最大血量数值以 "千分比基点" 存 (如 50 = 5.0% 最大血), 整数
 * 避免浮点; 时长以秒存; 倍率以 "x100 整数" 存 (如 120 = x1.2)。各 type 结算时还原 (见 ChefConsumeHandler)。
 */
public final class ChefConfig {

    private ChefConfig() {
    }

    public static final ForgeConfigSpec SPEC;

    // ---- 7.2 单菜原始经验 (按达成品质) ----
    public static final ForgeConfigSpec.IntValue XP_LOW;
    public static final ForgeConfigSpec.IntValue XP_MEDIUM;
    public static final ForgeConfigSpec.IntValue XP_HIGH;
    public static final ForgeConfigSpec.IntValue XP_EXTRAORDINARY;
    public static final ForgeConfigSpec.IntValue XP_RADIANT;

    // ---- 6.1 增香: 时长倍率 x100 (逐级 低/中/高/超凡/闪耀 = 1.2/1.5/2/3/5) ----
    public static final ForgeConfigSpec.IntValue AMPLIFY_LOW;
    public static final ForgeConfigSpec.IntValue AMPLIFY_MEDIUM;
    public static final ForgeConfigSpec.IntValue AMPLIFY_HIGH;
    public static final ForgeConfigSpec.IntValue AMPLIFY_EXTRAORDINARY;
    public static final ForgeConfigSpec.IntValue AMPLIFY_RADIANT;

    // ---- 6.1 增量: 饱食倍率 x100 (1.5/2/3/4/8) ----
    public static final ForgeConfigSpec.IntValue NOURISH_FOOD_LOW;
    public static final ForgeConfigSpec.IntValue NOURISH_FOOD_MEDIUM;
    public static final ForgeConfigSpec.IntValue NOURISH_FOOD_HIGH;
    public static final ForgeConfigSpec.IntValue NOURISH_FOOD_EXTRAORDINARY;
    public static final ForgeConfigSpec.IntValue NOURISH_FOOD_RADIANT;

    // ---- 6.1 回味: 饱和倍率 x100 (1.5/2/3/4/5) ----
    public static final ForgeConfigSpec.IntValue AFTERTASTE_SAT_LOW;
    public static final ForgeConfigSpec.IntValue AFTERTASTE_SAT_MEDIUM;
    public static final ForgeConfigSpec.IntValue AFTERTASTE_SAT_HIGH;
    public static final ForgeConfigSpec.IntValue AFTERTASTE_SAT_EXTRAORDINARY;
    public static final ForgeConfigSpec.IntValue AFTERTASTE_SAT_RADIANT;

    // ---- 6.1 膳香 (战斗向回血): %最大血量千分比基点 (改 % 后: 25/50/75/100/1000 = 2.5%/5%/7.5%/10%/100%) ----
    public static final ForgeConfigSpec.IntValue HEAL_HIGH;
    public static final ForgeConfigSpec.IntValue HEAL_EXTRAORDINARY;
    public static final ForgeConfigSpec.IntValue HEAL_RADIANT;

    // ---- 11 披甲 (黄心护盾): %最大血量千分比 (高/超凡/闪耀 = 4%/6%/8%) ----
    public static final ForgeConfigSpec.IntValue SHIELD_HIGH;
    public static final ForgeConfigSpec.IntValue SHIELD_EXTRAORDINARY;
    public static final ForgeConfigSpec.IntValue SHIELD_RADIANT;
    public static final ForgeConfigSpec.IntValue SHIELD_WINDOW_SECONDS;

    // ---- 11 凝脂 (仅爆炸减伤): 百分比千分比 (高/超凡/闪耀 = 30%/45%/60%) ----
    public static final ForgeConfigSpec.IntValue GREASE_HIGH;
    public static final ForgeConfigSpec.IntValue GREASE_EXTRAORDINARY;
    public static final ForgeConfigSpec.IntValue GREASE_RADIANT;
    public static final ForgeConfigSpec.IntValue GREASE_WINDOW_SECONDS;

    // ---- 11 余韵 (延迟微再生): 总回血 %最大血量千分比 (高/超凡/闪耀, 替代沁脾) ----
    public static final ForgeConfigSpec.IntValue REGEN_HIGH;
    public static final ForgeConfigSpec.IntValue REGEN_EXTRAORDINARY;
    public static final ForgeConfigSpec.IntValue REGEN_RADIANT;
    public static final ForgeConfigSpec.IntValue REGEN_WINDOW_SECONDS;

    // ---- 11 稳膛 (抗击退): 百分比千分比 (中/高/超凡/闪耀 = 50%/70%/85%/100%; 低级 — ) ----
    public static final ForgeConfigSpec.IntValue STABLE_AIM_MEDIUM;
    public static final ForgeConfigSpec.IntValue STABLE_AIM_HIGH;
    public static final ForgeConfigSpec.IntValue STABLE_AIM_EXTRAORDINARY;
    public static final ForgeConfigSpec.IntValue STABLE_AIM_RADIANT;
    public static final ForgeConfigSpec.IntValue STABLE_AIM_WINDOW_SECONDS;

    // ---- 11 耐饥 (减饥饿衰减): 减衰减百分比千分比 + 窗口秒 (15%/30%/50%/70%/90%, 120/180/300/480/900s) ----
    public static final ForgeConfigSpec.IntValue ENDURANCE_PCT_LOW;
    public static final ForgeConfigSpec.IntValue ENDURANCE_PCT_MEDIUM;
    public static final ForgeConfigSpec.IntValue ENDURANCE_PCT_HIGH;
    public static final ForgeConfigSpec.IntValue ENDURANCE_PCT_EXTRAORDINARY;
    public static final ForgeConfigSpec.IntValue ENDURANCE_PCT_RADIANT;
    public static final ForgeConfigSpec.IntValue ENDURANCE_SEC_LOW;
    public static final ForgeConfigSpec.IntValue ENDURANCE_SEC_MEDIUM;
    public static final ForgeConfigSpec.IntValue ENDURANCE_SEC_HIGH;
    public static final ForgeConfigSpec.IntValue ENDURANCE_SEC_EXTRAORDINARY;
    public static final ForgeConfigSpec.IntValue ENDURANCE_SEC_RADIANT;

    // ---- 6.1 提神 (急速): 时长秒, 按品质逐级 (低/中/高/超凡/闪耀 = 90/150/240/360/600); 急速等级在 magnitude ----
    public static final ForgeConfigSpec.IntValue REFRESH_SEC_LOW;
    public static final ForgeConfigSpec.IntValue REFRESH_SEC_MEDIUM;
    public static final ForgeConfigSpec.IntValue REFRESH_SEC_HIGH;
    public static final ForgeConfigSpec.IntValue REFRESH_SEC_EXTRAORDINARY;
    public static final ForgeConfigSpec.IntValue REFRESH_SEC_RADIANT;

    // ---- 11 夜照 (纯夜视): 时长秒 (60/120/240/480/900) ----
    public static final ForgeConfigSpec.IntValue NIGHT_SEC_LOW;
    public static final ForgeConfigSpec.IntValue NIGHT_SEC_MEDIUM;
    public static final ForgeConfigSpec.IntValue NIGHT_SEC_HIGH;
    public static final ForgeConfigSpec.IntValue NIGHT_SEC_EXTRAORDINARY;
    public static final ForgeConfigSpec.IntValue NIGHT_SEC_RADIANT;

    // ---- 已选探索/后勤效果 (窗口秒；镇火中级只灭火，故时长为 0) ----
    public static final ForgeConfigSpec.IntValue SATIATION_SEC_LOW;
    public static final ForgeConfigSpec.IntValue SATIATION_SEC_MEDIUM;
    public static final ForgeConfigSpec.IntValue SATIATION_SEC_HIGH;
    public static final ForgeConfigSpec.IntValue SATIATION_SEC_EXTRAORDINARY;
    public static final ForgeConfigSpec.IntValue SATIATION_SEC_RADIANT;
    public static final ForgeConfigSpec.IntValue FIRE_QUELL_SEC_HIGH;
    public static final ForgeConfigSpec.IntValue FIRE_QUELL_SEC_EXTRAORDINARY;
    public static final ForgeConfigSpec.IntValue FIRE_QUELL_SEC_RADIANT;
    public static final ForgeConfigSpec.IntValue GILLS_SEC_LOW;
    public static final ForgeConfigSpec.IntValue GILLS_SEC_MEDIUM;
    public static final ForgeConfigSpec.IntValue GILLS_SEC_HIGH;
    public static final ForgeConfigSpec.IntValue GILLS_SEC_EXTRAORDINARY;
    public static final ForgeConfigSpec.IntValue GILLS_SEC_RADIANT;
    public static final ForgeConfigSpec.IntValue FEATHER_SEC_MEDIUM;
    public static final ForgeConfigSpec.IntValue FEATHER_SEC_HIGH;
    public static final ForgeConfigSpec.IntValue FEATHER_SEC_EXTRAORDINARY;
    public static final ForgeConfigSpec.IntValue FEATHER_SEC_RADIANT;
    public static final ForgeConfigSpec.IntValue FIREFLY_SEC_LOW;
    public static final ForgeConfigSpec.IntValue FIREFLY_SEC_MEDIUM;
    public static final ForgeConfigSpec.IntValue FIREFLY_SEC_HIGH;
    public static final ForgeConfigSpec.IntValue FIREFLY_SEC_EXTRAORDINARY;
    public static final ForgeConfigSpec.IntValue FIREFLY_SEC_RADIANT;
    public static final ForgeConfigSpec.IntValue SATED_JUMP_SECONDS;
    public static final ForgeConfigSpec.IntValue FIREFLY_PARTICLE_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue FIREFLY_PARTICLE_COUNT;

    // ---- 掷池门控/调料偏置 ----
    public static final ForgeConfigSpec.IntValue ENDURANCE_UNLOCK_LEVEL;
    public static final ForgeConfigSpec.IntValue COMBAT_UNLOCK_LEVEL;
    public static final ForgeConfigSpec.IntValue ADVANCED_COMBAT_UNLOCK_LEVEL;
    public static final ForgeConfigSpec.IntValue SEASONING_BIASED_WEIGHT;
    public static final ForgeConfigSpec.IntValue SEASONING_NEUTRAL_WEIGHT;
    public static final ForgeConfigSpec.IntValue COMPLEX_VIRTUAL_HITS;

    // ---- 调味小游戏（服务端权威） ----
    public static final ForgeConfigSpec.IntValue HEAT_MAX;
    public static final ForgeConfigSpec.IntValue HEAT_RISE_PER_TICK;
    public static final ForgeConfigSpec.IntValue HEAT_FALL_PER_TICK;
    public static final ForgeConfigSpec.IntValue HEAT_GREEN_START;
    public static final ForgeConfigSpec.IntValue HEAT_GREEN_END;
    public static final ForgeConfigSpec.IntValue SEASONING_DURATION_TICKS;
    public static final ForgeConfigSpec.IntValue SEASONING_SCORING_START_TICK;
    public static final ForgeConfigSpec.IntValue QTE_WINDOW_TICKS;
    public static final ForgeConfigSpec.IntValue QTE_MIN_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue QTE_MAX_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue QTE_MIN_WINDOW_TICKS;
    public static final ForgeConfigSpec.IntValue QTE_MIN_GAP_TICKS;
    public static final ForgeConfigSpec.IntValue QTE_DIFFICULTY_TICKS_PER_QUALITY_TIER;
    public static final ForgeConfigSpec.IntValue QTE_EASE_TICKS_PER_CHEF_LEVEL;
    public static final ForgeConfigSpec.IntValue QTE_COUNT;
    public static final ForgeConfigSpec.IntValue QTE_COUNT_PER_QUALITY_TIER;
    public static final ForgeConfigSpec.IntValue QTE_TABLE_TIERS_PER_REDUCTION;
    public static final ForgeConfigSpec.IntValue SEASONING_TABLE_MAX_TIER;
    public static final ForgeConfigSpec.IntValue TARGET_BASE_CHANCE_LOW_PER_MILLE;
    public static final ForgeConfigSpec.IntValue TARGET_BASE_CHANCE_MEDIUM_PER_MILLE;
    public static final ForgeConfigSpec.IntValue TARGET_BASE_CHANCE_HIGH_PER_MILLE;
    public static final ForgeConfigSpec.IntValue TARGET_BASE_CHANCE_EXTRAORDINARY_PER_MILLE;
    public static final ForgeConfigSpec.IntValue TARGET_BASE_CHANCE_RADIANT_PER_MILLE;
    public static final ForgeConfigSpec.IntValue TARGET_HEAT_BONUS_PER_MILLE;
    public static final ForgeConfigSpec.IntValue TARGET_QTE_HIT_BONUS_PER_MILLE;
    public static final ForgeConfigSpec.IntValue TARGET_DIFFICULTY_HIGH_PER_MILLE;
    public static final ForgeConfigSpec.IntValue TARGET_DIFFICULTY_EXTRAORDINARY_PER_MILLE;
    public static final ForgeConfigSpec.IntValue TARGET_DIFFICULTY_RADIANT_PER_MILLE;
    public static final ForgeConfigSpec.IntValue LEVEL_1_OPEN_QUALITY_SUCCESS_MULTIPLIER_PER_MILLE;
    public static final ForgeConfigSpec.IntValue LEVEL_10_SUCCESS_MULTIPLIER_PER_MILLE;
    public static final ForgeConfigSpec.IntValue TABLE_SUCCESS_BONUS_PER_TIER_PER_MILLE;

    // ---- 11 翻车负面 (仅低/中/高): 夹生概率千分比 + 时长秒, 烧焦自伤 %千分比, 倒胃中毒等级 ----
    public static final ForgeConfigSpec.IntValue UNDERDONE_CHANCE_LOW;
    public static final ForgeConfigSpec.IntValue UNDERDONE_CHANCE_MEDIUM;
    public static final ForgeConfigSpec.IntValue UNDERDONE_CHANCE_HIGH;
    public static final ForgeConfigSpec.IntValue UNDERDONE_SEC_LOW;
    public static final ForgeConfigSpec.IntValue UNDERDONE_SEC_MEDIUM;
    public static final ForgeConfigSpec.IntValue UNDERDONE_SEC_HIGH;
    public static final ForgeConfigSpec.IntValue SCORCHED_PCT_LOW;
    public static final ForgeConfigSpec.IntValue SCORCHED_PCT_MEDIUM;
    public static final ForgeConfigSpec.IntValue SCORCHED_PCT_HIGH;
    public static final ForgeConfigSpec.IntValue NAUSEA_SEC_LOW;
    public static final ForgeConfigSpec.IntValue NAUSEA_SEC_MEDIUM;
    public static final ForgeConfigSpec.IntValue NAUSEA_SEC_HIGH;

    // ---- 四档调味台合成成本 (信用点 sink, 经 IEconomyService; 闪耀台无配方=最贵, 见 ChefXpHandler/SeasoningTableBlock) ----
    public static final ForgeConfigSpec.IntValue TABLE_USE_COST_CREDIT;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("xp");
        b.comment("7.2 raw xp per dish by achieved quality (low 50 / medium 80 / high 130 / extra 220 / radiant 400)");
        XP_LOW = b.defineInRange("rawXpLow", 50, 0, 100000);
        XP_MEDIUM = b.defineInRange("rawXpMedium", 80, 0, 100000);
        XP_HIGH = b.defineInRange("rawXpHigh", 130, 0, 100000);
        XP_EXTRAORDINARY = b.defineInRange("rawXpExtraordinary", 220, 0, 100000);
        XP_RADIANT = b.defineInRange("rawXpRadiant", 400, 0, 100000);
        b.pop();

        b.push("amplify");
        b.comment("6.1 buff-duration multiplier x100 (1.2/1.5/2/3/5); multiplies ONLY duration, blacklist applies");
        AMPLIFY_LOW = b.defineInRange("low", 120, 100, 10000);
        AMPLIFY_MEDIUM = b.defineInRange("medium", 150, 100, 10000);
        AMPLIFY_HIGH = b.defineInRange("high", 200, 100, 10000);
        AMPLIFY_EXTRAORDINARY = b.defineInRange("extraordinary", 300, 100, 10000);
        AMPLIFY_RADIANT = b.defineInRange("radiant", 500, 100, 10000);
        b.pop();

        b.push("nourish_food");
        b.comment("6.1 food (hunger) multiplier x100 (1.5/2/3/4/8); capped by the 20-bar limit at consume time");
        NOURISH_FOOD_LOW = b.defineInRange("low", 150, 100, 10000);
        NOURISH_FOOD_MEDIUM = b.defineInRange("medium", 200, 100, 10000);
        NOURISH_FOOD_HIGH = b.defineInRange("high", 300, 100, 10000);
        NOURISH_FOOD_EXTRAORDINARY = b.defineInRange("extraordinary", 400, 100, 10000);
        NOURISH_FOOD_RADIANT = b.defineInRange("radiant", 800, 100, 10000);
        b.pop();

        b.push("aftertaste_saturation");
        b.comment("6.1 saturation multiplier x100 (1.5/2/3/4/5); saturation self-limited <= food");
        AFTERTASTE_SAT_LOW = b.defineInRange("low", 150, 100, 10000);
        AFTERTASTE_SAT_MEDIUM = b.defineInRange("medium", 200, 100, 10000);
        AFTERTASTE_SAT_HIGH = b.defineInRange("high", 300, 100, 10000);
        AFTERTASTE_SAT_EXTRAORDINARY = b.defineInRange("extraordinary", 400, 100, 10000);
        AFTERTASTE_SAT_RADIANT = b.defineInRange("radiant", 500, 100, 10000);
        b.pop();

        b.push("nourish_heal");
        b.comment("6.1 combat heal as PER-MILLE of max HP (spec ch.12.4 mandates %maxHP; radiant=100% full heal). high/extra/radiant only");
        HEAL_HIGH = b.defineInRange("high", 75, 0, 1000);
        HEAL_EXTRAORDINARY = b.defineInRange("extraordinary", 100, 0, 1000);
        HEAL_RADIANT = b.defineInRange("radiant", 1000, 0, 1000);
        b.pop();

        b.push("shield");
        b.comment("11 absorption shield as PER-MILLE of max HP (high 4% / extra 6% / radiant 8%); refresh-not-stack");
        SHIELD_HIGH = b.defineInRange("high", 40, 0, 1000);
        SHIELD_EXTRAORDINARY = b.defineInRange("extraordinary", 60, 0, 1000);
        SHIELD_RADIANT = b.defineInRange("radiant", 80, 0, 1000);
        SHIELD_WINDOW_SECONDS = b.defineInRange("windowSeconds", 120, 1, 3600);
        b.pop();

        b.push("grease");
        b.comment("11 explosion-only damage reduction as PER-MILLE (high 30% / extra 45% / radiant 60%)");
        GREASE_HIGH = b.defineInRange("high", 300, 0, 1000);
        GREASE_EXTRAORDINARY = b.defineInRange("extraordinary", 450, 0, 1000);
        GREASE_RADIANT = b.defineInRange("radiant", 600, 0, 1000);
        GREASE_WINDOW_SECONDS = b.defineInRange("windowSeconds", 120, 1, 3600);
        b.pop();

        b.push("aftertaste_regen");
        b.comment("11 delayed regen total heal as PER-MILLE of max HP (replaces qinpi); amortized over window");
        REGEN_HIGH = b.defineInRange("high", 50, 0, 1000);
        REGEN_EXTRAORDINARY = b.defineInRange("extraordinary", 60, 0, 1000);
        REGEN_RADIANT = b.defineInRange("radiant", 100, 0, 1000);
        REGEN_WINDOW_SECONDS = b.defineInRange("windowSeconds", 30, 1, 3600);
        b.pop();

        b.push("stable_aim");
        b.comment("11 knockback resistance as PER-MILLE (medium 50% / high 70% / extra 85% / radiant 100%); via LivingKnockBackEvent NOT attribute");
        STABLE_AIM_MEDIUM = b.defineInRange("medium", 500, 0, 1000);
        STABLE_AIM_HIGH = b.defineInRange("high", 700, 0, 1000);
        STABLE_AIM_EXTRAORDINARY = b.defineInRange("extraordinary", 850, 0, 1000);
        STABLE_AIM_RADIANT = b.defineInRange("radiant", 1000, 0, 1000);
        STABLE_AIM_WINDOW_SECONDS = b.defineInRange("windowSeconds", 60, 1, 3600);
        b.pop();

        b.push("endurance");
        b.comment("11 hunger-decay reduction PER-MILLE + window seconds (15/30/50/70/90%, 120/180/300/480/900s)");
        ENDURANCE_PCT_LOW = b.defineInRange("pctLow", 150, 0, 1000);
        ENDURANCE_PCT_MEDIUM = b.defineInRange("pctMedium", 300, 0, 1000);
        ENDURANCE_PCT_HIGH = b.defineInRange("pctHigh", 500, 0, 1000);
        ENDURANCE_PCT_EXTRAORDINARY = b.defineInRange("pctExtraordinary", 700, 0, 1000);
        ENDURANCE_PCT_RADIANT = b.defineInRange("pctRadiant", 900, 0, 1000);
        ENDURANCE_SEC_LOW = b.defineInRange("secLow", 120, 1, 3600);
        ENDURANCE_SEC_MEDIUM = b.defineInRange("secMedium", 180, 1, 3600);
        ENDURANCE_SEC_HIGH = b.defineInRange("secHigh", 300, 1, 3600);
        ENDURANCE_SEC_EXTRAORDINARY = b.defineInRange("secExtraordinary", 480, 1, 3600);
        ENDURANCE_SEC_RADIANT = b.defineInRange("secRadiant", 900, 1, 3600);
        b.pop();

        b.push("refresh");
        b.comment("6.1 refresh (haste) duration seconds per quality (low/medium/high/extraordinary/radiant = 90/150/240/360/600)");
        REFRESH_SEC_LOW = b.defineInRange("low", 90, 1, 3600);
        REFRESH_SEC_MEDIUM = b.defineInRange("medium", 150, 1, 3600);
        REFRESH_SEC_HIGH = b.defineInRange("high", 240, 1, 3600);
        REFRESH_SEC_EXTRAORDINARY = b.defineInRange("extraordinary", 360, 1, 3600);
        REFRESH_SEC_RADIANT = b.defineInRange("radiant", 600, 1, 3600);
        b.pop();

        b.push("night_sight");
        b.comment("11 pure night vision duration seconds (60/120/240/480/900)");
        NIGHT_SEC_LOW = b.defineInRange("low", 60, 1, 3600);
        NIGHT_SEC_MEDIUM = b.defineInRange("medium", 120, 1, 3600);
        NIGHT_SEC_HIGH = b.defineInRange("high", 240, 1, 3600);
        NIGHT_SEC_EXTRAORDINARY = b.defineInRange("extraordinary", 480, 1, 3600);
        NIGHT_SEC_RADIANT = b.defineInRange("radiant", 900, 1, 3600);
        b.pop();

        b.push("satiation");
        b.comment("fullness window seconds (60/120/240/420/720): immunity to Hunger plus saturation upkeep");
        SATIATION_SEC_LOW = b.defineInRange("low", 60, 1, 3600);
        SATIATION_SEC_MEDIUM = b.defineInRange("medium", 120, 1, 3600);
        SATIATION_SEC_HIGH = b.defineInRange("high", 240, 1, 3600);
        SATIATION_SEC_EXTRAORDINARY = b.defineInRange("extraordinary", 420, 1, 3600);
        SATIATION_SEC_RADIANT = b.defineInRange("radiant", 720, 1, 3600);
        b.pop();

        b.push("exploration");
        b.comment("fire_quell high/extra/radiant fire-resistance seconds (8/12/18); medium extinguishes only");
        FIRE_QUELL_SEC_HIGH = b.defineInRange("fireQuellHighSeconds", 8, 0, 3600);
        FIRE_QUELL_SEC_EXTRAORDINARY = b.defineInRange("fireQuellExtraordinarySeconds", 12, 0, 3600);
        FIRE_QUELL_SEC_RADIANT = b.defineInRange("fireQuellRadiantSeconds", 18, 0, 3600);
        b.comment("gills water breathing and dolphins grace seconds (30/60/120/240/480)");
        GILLS_SEC_LOW = b.defineInRange("gillsLowSeconds", 30, 1, 3600);
        GILLS_SEC_MEDIUM = b.defineInRange("gillsMediumSeconds", 60, 1, 3600);
        GILLS_SEC_HIGH = b.defineInRange("gillsHighSeconds", 120, 1, 3600);
        GILLS_SEC_EXTRAORDINARY = b.defineInRange("gillsExtraordinarySeconds", 240, 1, 3600);
        GILLS_SEC_RADIANT = b.defineInRange("gillsRadiantSeconds", 480, 1, 3600);
        b.comment("feather slow-falling seconds (medium/high/extraordinary/radiant = 20/40/80/160)");
        FEATHER_SEC_MEDIUM = b.defineInRange("featherMediumSeconds", 20, 0, 3600);
        FEATHER_SEC_HIGH = b.defineInRange("featherHighSeconds", 40, 0, 3600);
        FEATHER_SEC_EXTRAORDINARY = b.defineInRange("featherExtraordinarySeconds", 80, 0, 3600);
        FEATHER_SEC_RADIANT = b.defineInRange("featherRadiantSeconds", 160, 0, 3600);
        b.comment("firefly glowing seconds (30/60/120/240/600)");
        FIREFLY_SEC_LOW = b.defineInRange("fireflyLowSeconds", 30, 1, 3600);
        FIREFLY_SEC_MEDIUM = b.defineInRange("fireflyMediumSeconds", 60, 1, 3600);
        FIREFLY_SEC_HIGH = b.defineInRange("fireflyHighSeconds", 120, 1, 3600);
        FIREFLY_SEC_EXTRAORDINARY = b.defineInRange("fireflyExtraordinarySeconds", 240, 1, 3600);
        FIREFLY_SEC_RADIANT = b.defineInRange("fireflyRadiantSeconds", 600, 1, 3600);
        SATED_JUMP_SECONDS = b.defineInRange("satedJumpSeconds", 60, 1, 3600);
        FIREFLY_PARTICLE_INTERVAL_TICKS = b.defineInRange("fireflyParticleIntervalTicks", 20, 1, 1200);
        FIREFLY_PARTICLE_COUNT = b.defineInRange("fireflyParticleCount", 3, 1, 64);
        b.pop();

        b.push("effect_pool");
        b.comment("chef-level unlock thresholds for the seasoning pool");
        ENDURANCE_UNLOCK_LEVEL = b.defineInRange("enduranceUnlockLevel", 3, 1, 10);
        COMBAT_UNLOCK_LEVEL = b.defineInRange("combatUnlockLevel", 5, 1, 10);
        ADVANCED_COMBAT_UNLOCK_LEVEL = b.defineInRange("advancedCombatUnlockLevel", 7, 1, 10);
        b.comment("seasoning pool weights; matched direction is biased:neutral (default 3:1)");
        SEASONING_BIASED_WEIGHT = b.defineInRange("biasedWeight", 3, 1, 100);
        SEASONING_NEUTRAL_WEIGHT = b.defineInRange("neutralWeight", 1, 1, 100);
        COMPLEX_VIRTUAL_HITS = b.defineInRange("complexVirtualHits", 1, 0, 3);
        b.pop();

        b.push("minigame");
        b.comment("server-authoritative heat and QTE parameters");
        HEAT_MAX = b.defineInRange("heatMax", 200, 1, 10000);
        HEAT_RISE_PER_TICK = b.defineInRange("heatRisePerTick", 2, 1, 1000);
        HEAT_FALL_PER_TICK = b.defineInRange("heatFallPerTick", 1, 1, 1000);
        HEAT_GREEN_START = b.defineInRange("heatGreenStart", 120, 0, 10000);
        HEAT_GREEN_END = b.defineInRange("heatGreenEnd", 160, 0, 10000);
        SEASONING_DURATION_TICKS = b.defineInRange("durationTicks", 160, 20, 72000);
        SEASONING_SCORING_START_TICK = b.defineInRange("scoringStartTick", 60, 0, 72000);
        QTE_WINDOW_TICKS = b.defineInRange("qteWindowTicks", 20, 1, 1200);
        QTE_MIN_INTERVAL_TICKS = b.defineInRange("qteMinIntervalTicks", 15, 1, 72000);
        QTE_MAX_INTERVAL_TICKS = b.defineInRange("qteMaxIntervalTicks", 35, 1, 72000);
        b.comment("reachable lower bounds when a low-level chef selects a high-quality target");
        QTE_MIN_WINDOW_TICKS = b.defineInRange("qteMinWindowTicks", 4, 1, 1200);
        QTE_MIN_GAP_TICKS = b.defineInRange("qteMinGapTicks", 3, 1, 72000);
        b.comment("each target-quality tier shortens both the QTE window and cue gap; each chef level above L1 adds time back");
        QTE_DIFFICULTY_TICKS_PER_QUALITY_TIER = b.defineInRange(
                "qteDifficultyTicksPerQualityTier", 4, 0, 300);
        QTE_EASE_TICKS_PER_CHEF_LEVEL = b.defineInRange("qteEaseTicksPerChefLevel", 1, 0, 300);
        QTE_COUNT = b.defineInRange("qteCount", 4, 4, 64);
        b.comment("additional QTE cues required by each target-quality tier above low");
        QTE_COUNT_PER_QUALITY_TIER = b.defineInRange("qteCountPerQualityTier", 1, 0, 16);
        b.comment("each complete group of table tiers removes one cue; default tiers 0/1/2/3/4 remove 0/0/1/1/2 cues");
        QTE_TABLE_TIERS_PER_REDUCTION = b.defineInRange("qteTableTiersPerReduction", 2, 2, 4);
        SEASONING_TABLE_MAX_TIER = b.defineInRange("tableMaxTier", 4, 0, 4);
        b.pop();

        b.push("quality_resolution");
        b.comment("target-quality success chances in PER-MILLE; heat bonus scales with green-zone accuracy and each correct QTE adds the configured flat bonus");
        TARGET_BASE_CHANCE_LOW_PER_MILLE = b.defineInRange("targetBaseChanceLowPerMille", 1000, 0, 1000);
        TARGET_BASE_CHANCE_MEDIUM_PER_MILLE = b.defineInRange("targetBaseChanceMediumPerMille", 700, 0, 1000);
        TARGET_BASE_CHANCE_HIGH_PER_MILLE = b.defineInRange("targetBaseChanceHighPerMille", 450, 0, 1000);
        TARGET_BASE_CHANCE_EXTRAORDINARY_PER_MILLE = b.defineInRange("targetBaseChanceExtraordinaryPerMille", 250, 0, 1000);
        TARGET_BASE_CHANCE_RADIANT_PER_MILLE = b.defineInRange("targetBaseChanceRadiantPerMille", 100, 0, 1000);
        TARGET_HEAT_BONUS_PER_MILLE = b.defineInRange("targetHeatBonusPerMille", 500, 0, 1000);
        TARGET_QTE_HIT_BONUS_PER_MILLE = b.defineInRange("targetQteHitBonusPerMille", 100, 0, 1000);
        b.comment("quality difficulty multiplier applied after performance bonuses: high 85%, extraordinary 65%, radiant 45%; low/medium remain 100%");
        TARGET_DIFFICULTY_HIGH_PER_MILLE = b.defineInRange("targetDifficultyHighPerMille", 850, 0, 1000);
        TARGET_DIFFICULTY_EXTRAORDINARY_PER_MILLE = b.defineInRange("targetDifficultyExtraordinaryPerMille", 650, 0, 1000);
        TARGET_DIFFICULTY_RADIANT_PER_MILLE = b.defineInRange("targetDifficultyRadiantPerMille", 450, 0, 1000);
        b.comment("chef-level multiplier applied to MEDIUM and higher targets after performance bonuses; all qualities are selectable on every table tier");
        LEVEL_1_OPEN_QUALITY_SUCCESS_MULTIPLIER_PER_MILLE = b.defineInRange(
                "level1OpenQualitySuccessMultiplierPerMille", 100, 0, 1000);
        LEVEL_10_SUCCESS_MULTIPLIER_PER_MILLE = b.defineInRange("level10SuccessMultiplierPerMille", 1000, 0, 1000);
        b.comment("multiplicative success bonus supplied by each seasoning-table tier, in PER-MILLE");
        TABLE_SUCCESS_BONUS_PER_TIER_PER_MILLE = b.defineInRange(
                "tableSuccessBonusPerTierPerMille", 50, 0, 250);
        b.pop();

        b.push("negatives");
        b.comment("11 failures (low/medium/high only). underdone: trigger chance PER-MILLE + debuff seconds; scorched: self-damage PER-MILLE maxHP");
        UNDERDONE_CHANCE_LOW = b.defineInRange("underdoneChanceLow", 800, 0, 1000);
        UNDERDONE_CHANCE_MEDIUM = b.defineInRange("underdoneChanceMedium", 500, 0, 1000);
        UNDERDONE_CHANCE_HIGH = b.defineInRange("underdoneChanceHigh", 250, 0, 1000);
        UNDERDONE_SEC_LOW = b.defineInRange("underdoneSecLow", 12, 1, 600);
        UNDERDONE_SEC_MEDIUM = b.defineInRange("underdoneSecMedium", 8, 1, 600);
        UNDERDONE_SEC_HIGH = b.defineInRange("underdoneSecHigh", 6, 1, 600);
        SCORCHED_PCT_LOW = b.defineInRange("scorchedPctLow", 80, 0, 1000);
        SCORCHED_PCT_MEDIUM = b.defineInRange("scorchedPctMedium", 50, 0, 1000);
        SCORCHED_PCT_HIGH = b.defineInRange("scorchedPctHigh", 30, 0, 1000);
        // 倒胃中毒时长秒 (spec 第十一章: 低 毒II/8s · 中 毒I/6s · 高 毒I/4s; 等级在 ChefEffectMagnitude.snapshot)。
        NAUSEA_SEC_LOW = b.defineInRange("nauseaSecLow", 8, 1, 600);
        NAUSEA_SEC_MEDIUM = b.defineInRange("nauseaSecMedium", 6, 1, 600);
        NAUSEA_SEC_HIGH = b.defineInRange("nauseaSecHigh", 4, 1, 600);
        b.pop();

        b.push("economy");
        b.comment("credit cost charged to the chef per dish seasoned (sink; 0 disables charging)");
        TABLE_USE_COST_CREDIT = b.defineInRange("tableUseCostCredit", 5, 0, 1000000);
        b.pop();

        SPEC = b.build();
    }

    /**
     * 测试兜底加载: 若 SPEC 尚未绑定任何 config (例如 GameTest 时 ChefSystem 未接入 MiningDim, registerConfig
     * 未跑), 用一个空的内存 config 触发 spec 自校正填默认值, 使 *.get() 可读 (否则 dev 环境 get 抛 ISE)。
     * 已加载则空操作 (避免覆盖真实 SERVER toml)。仅 GameTest 调用; 运行期由 ModLoadingContext.registerConfig
     * 正常加载, 不依赖本法。
     */
    public static void ensureLoadedForTest() {
        if (!SPEC.isLoaded()) {
            SPEC.setConfig(CommentedConfig.inMemory());
        }
    }

    // ---- 逐级取值助手 (按 ChefQuality.tier 索引; 越界 IndexOutOfBounds 自然冒泡, 不掩盖) ----

    /** 增香时长倍率 x100 (按品质档)。 */
    public static int amplifyMul(ChefQuality q) {
        return byTier(q, AMPLIFY_LOW, AMPLIFY_MEDIUM, AMPLIFY_HIGH, AMPLIFY_EXTRAORDINARY, AMPLIFY_RADIANT);
    }

    public static int nourishFoodMul(ChefQuality q) {
        return byTier(q, NOURISH_FOOD_LOW, NOURISH_FOOD_MEDIUM, NOURISH_FOOD_HIGH, NOURISH_FOOD_EXTRAORDINARY, NOURISH_FOOD_RADIANT);
    }

    public static int aftertasteSatMul(ChefQuality q) {
        return byTier(q, AFTERTASTE_SAT_LOW, AFTERTASTE_SAT_MEDIUM, AFTERTASTE_SAT_HIGH, AFTERTASTE_SAT_EXTRAORDINARY, AFTERTASTE_SAT_RADIANT);
    }

    /** 膳香回血千分比 (仅 高/超凡/闪耀; 低/中返回 0)。 */
    public static int healPerMille(ChefQuality q) {
        return switch (q) {
            case HIGH -> HEAL_HIGH.get();
            case EXTRAORDINARY -> HEAL_EXTRAORDINARY.get();
            case RADIANT -> HEAL_RADIANT.get();
            default -> 0;
        };
    }

    public static int shieldPerMille(ChefQuality q) {
        return switch (q) {
            case HIGH -> SHIELD_HIGH.get();
            case EXTRAORDINARY -> SHIELD_EXTRAORDINARY.get();
            case RADIANT -> SHIELD_RADIANT.get();
            default -> 0;
        };
    }

    public static int greasePerMille(ChefQuality q) {
        return switch (q) {
            case HIGH -> GREASE_HIGH.get();
            case EXTRAORDINARY -> GREASE_EXTRAORDINARY.get();
            case RADIANT -> GREASE_RADIANT.get();
            default -> 0;
        };
    }

    public static int regenPerMille(ChefQuality q) {
        return switch (q) {
            case HIGH -> REGEN_HIGH.get();
            case EXTRAORDINARY -> REGEN_EXTRAORDINARY.get();
            case RADIANT -> REGEN_RADIANT.get();
            default -> 0;
        };
    }

    /** 稳膛抗击退千分比 (中/高/超凡/闪耀; 低级返回 0)。 */
    public static int stableAimPerMille(ChefQuality q) {
        return switch (q) {
            case MEDIUM -> STABLE_AIM_MEDIUM.get();
            case HIGH -> STABLE_AIM_HIGH.get();
            case EXTRAORDINARY -> STABLE_AIM_EXTRAORDINARY.get();
            case RADIANT -> STABLE_AIM_RADIANT.get();
            default -> 0;
        };
    }

    public static int endurancePctPerMille(ChefQuality q) {
        return byTier(q, ENDURANCE_PCT_LOW, ENDURANCE_PCT_MEDIUM, ENDURANCE_PCT_HIGH, ENDURANCE_PCT_EXTRAORDINARY, ENDURANCE_PCT_RADIANT);
    }

    public static int enduranceSeconds(ChefQuality q) {
        return byTier(q, ENDURANCE_SEC_LOW, ENDURANCE_SEC_MEDIUM, ENDURANCE_SEC_HIGH, ENDURANCE_SEC_EXTRAORDINARY, ENDURANCE_SEC_RADIANT);
    }

    /** 提神急速时长 (秒, 按品质逐级 90/150/240/360/600; 急速等级另由 magnitude=tier+1 决定)。 */
    public static int refreshSeconds(ChefQuality q) {
        return byTier(q, REFRESH_SEC_LOW, REFRESH_SEC_MEDIUM, REFRESH_SEC_HIGH, REFRESH_SEC_EXTRAORDINARY, REFRESH_SEC_RADIANT);
    }

    public static int nightSeconds(ChefQuality q) {
        return byTier(q, NIGHT_SEC_LOW, NIGHT_SEC_MEDIUM, NIGHT_SEC_HIGH, NIGHT_SEC_EXTRAORDINARY, NIGHT_SEC_RADIANT);
    }

    public static int satiationSeconds(ChefQuality q) {
        return byTier(q, SATIATION_SEC_LOW, SATIATION_SEC_MEDIUM, SATIATION_SEC_HIGH,
                SATIATION_SEC_EXTRAORDINARY, SATIATION_SEC_RADIANT);
    }

    public static int fireQuellSeconds(ChefQuality q) {
        return switch (q) {
            case HIGH -> FIRE_QUELL_SEC_HIGH.get();
            case EXTRAORDINARY -> FIRE_QUELL_SEC_EXTRAORDINARY.get();
            case RADIANT -> FIRE_QUELL_SEC_RADIANT.get();
            default -> 0;
        };
    }

    public static int gillsSeconds(ChefQuality q) {
        return byTier(q, GILLS_SEC_LOW, GILLS_SEC_MEDIUM, GILLS_SEC_HIGH, GILLS_SEC_EXTRAORDINARY, GILLS_SEC_RADIANT);
    }

    public static int featherSeconds(ChefQuality q) {
        return switch (q) {
            case MEDIUM -> FEATHER_SEC_MEDIUM.get();
            case HIGH -> FEATHER_SEC_HIGH.get();
            case EXTRAORDINARY -> FEATHER_SEC_EXTRAORDINARY.get();
            case RADIANT -> FEATHER_SEC_RADIANT.get();
            default -> 0;
        };
    }

    public static int fireflySeconds(ChefQuality q) {
        return byTier(q, FIREFLY_SEC_LOW, FIREFLY_SEC_MEDIUM, FIREFLY_SEC_HIGH,
                FIREFLY_SEC_EXTRAORDINARY, FIREFLY_SEC_RADIANT);
    }

    public static int satedJumpSeconds() {
        return SATED_JUMP_SECONDS.get();
    }

    public static int shieldWindowSeconds() {
        return SHIELD_WINDOW_SECONDS.get();
    }

    public static int greaseWindowSeconds() {
        return GREASE_WINDOW_SECONDS.get();
    }

    public static int regenWindowSeconds() {
        return REGEN_WINDOW_SECONDS.get();
    }

    public static int stableAimWindowSeconds() {
        return STABLE_AIM_WINDOW_SECONDS.get();
    }

    public static int fireflyParticleIntervalTicks() {
        return FIREFLY_PARTICLE_INTERVAL_TICKS.get();
    }

    public static int fireflyParticleCount() {
        return FIREFLY_PARTICLE_COUNT.get();
    }

    public static int enduranceUnlockLevel() {
        return ENDURANCE_UNLOCK_LEVEL.get();
    }

    public static int combatUnlockLevel() {
        return COMBAT_UNLOCK_LEVEL.get();
    }

    public static int advancedCombatUnlockLevel() {
        return ADVANCED_COMBAT_UNLOCK_LEVEL.get();
    }

    public static int seasoningBiasedWeight() {
        return SEASONING_BIASED_WEIGHT.get();
    }

    public static int seasoningNeutralWeight() {
        return SEASONING_NEUTRAL_WEIGHT.get();
    }

    public static int complexVirtualHits() {
        return COMPLEX_VIRTUAL_HITS.get();
    }

    public static int seasoningCreditCost() {
        return TABLE_USE_COST_CREDIT.get();
    }

    public static int heatMax() {
        return HEAT_MAX.get();
    }

    public static int heatRisePerTick() {
        return HEAT_RISE_PER_TICK.get();
    }

    public static int heatFallPerTick() {
        return HEAT_FALL_PER_TICK.get();
    }

    public static int heatGreenStart() {
        return HEAT_GREEN_START.get();
    }

    public static int heatGreenEnd() {
        return HEAT_GREEN_END.get();
    }

    public static int seasoningDurationTicks() {
        return SEASONING_DURATION_TICKS.get();
    }

    public static int seasoningScoringStartTick() {
        return SEASONING_SCORING_START_TICK.get();
    }

    public static int qteWindowTicks() {
        return QTE_WINDOW_TICKS.get();
    }

    public static int qteMinIntervalTicks() {
        return QTE_MIN_INTERVAL_TICKS.get();
    }

    public static int qteMaxIntervalTicks() {
        return QTE_MAX_INTERVAL_TICKS.get();
    }

    public static int qteMinWindowTicks() {
        return QTE_MIN_WINDOW_TICKS.get();
    }

    public static int qteMinGapTicks() {
        return QTE_MIN_GAP_TICKS.get();
    }

    public static int qteDifficultyTicksPerQualityTier() {
        return QTE_DIFFICULTY_TICKS_PER_QUALITY_TIER.get();
    }

    public static int qteEaseTicksPerChefLevel() {
        return QTE_EASE_TICKS_PER_CHEF_LEVEL.get();
    }

    public static int qteCount() {
        return QTE_COUNT.get();
    }

    public static int qteCountPerQualityTier() {
        return QTE_COUNT_PER_QUALITY_TIER.get();
    }

    public static int qteTableTiersPerReduction() {
        return QTE_TABLE_TIERS_PER_REDUCTION.get();
    }

    public static int seasoningTableMaxTier() {
        return SEASONING_TABLE_MAX_TIER.get();
    }

    public static int targetBaseChancePerMille(ChefQuality quality) {
        return byTier(quality, TARGET_BASE_CHANCE_LOW_PER_MILLE, TARGET_BASE_CHANCE_MEDIUM_PER_MILLE,
                TARGET_BASE_CHANCE_HIGH_PER_MILLE, TARGET_BASE_CHANCE_EXTRAORDINARY_PER_MILLE,
                TARGET_BASE_CHANCE_RADIANT_PER_MILLE);
    }

    public static int targetHeatBonusPerMille() {
        return TARGET_HEAT_BONUS_PER_MILLE.get();
    }

    public static int targetQteHitBonusPerMille() {
        return TARGET_QTE_HIT_BONUS_PER_MILLE.get();
    }

    public static int targetDifficultyMultiplierPerMille(ChefQuality quality) {
        return switch (quality) {
            case LOW, MEDIUM -> 1000;
            case HIGH -> TARGET_DIFFICULTY_HIGH_PER_MILLE.get();
            case EXTRAORDINARY -> TARGET_DIFFICULTY_EXTRAORDINARY_PER_MILLE.get();
            case RADIANT -> TARGET_DIFFICULTY_RADIANT_PER_MILLE.get();
        };
    }

    public static int level1OpenQualitySuccessMultiplierPerMille() {
        return LEVEL_1_OPEN_QUALITY_SUCCESS_MULTIPLIER_PER_MILLE.get();
    }

    public static int level10SuccessMultiplierPerMille() {
        return LEVEL_10_SUCCESS_MULTIPLIER_PER_MILLE.get();
    }

    public static int tableSuccessBonusPerTierPerMille() {
        return TABLE_SUCCESS_BONUS_PER_TIER_PER_MILLE.get();
    }

    /** 单菜原始经验 (按达成品质)。 */
    public static int rawXp(ChefQuality q) {
        return byTier(q, XP_LOW, XP_MEDIUM, XP_HIGH, XP_EXTRAORDINARY, XP_RADIANT);
    }

    /** gameplay 的品质经验读取合约；保留 rawXp 以兼容已经完成的厨师经验路径。 */
    public static int xpForQuality(ChefQuality q) {
        return rawXp(q);
    }

    private static int byTier(ChefQuality q, ForgeConfigSpec.IntValue low, ForgeConfigSpec.IntValue medium,
                              ForgeConfigSpec.IntValue high, ForgeConfigSpec.IntValue extra,
                              ForgeConfigSpec.IntValue radiant) {
        return switch (q) {
            case LOW -> low.get();
            case MEDIUM -> medium.get();
            case HIGH -> high.get();
            case EXTRAORDINARY -> extra.get();
            case RADIANT -> radiant.get();
        };
    }
}
