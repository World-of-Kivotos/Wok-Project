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

    // ---- 11 夜照 (纯夜视): 时长秒 (60/120/240/480/900) ----
    public static final ForgeConfigSpec.IntValue NIGHT_SEC_LOW;
    public static final ForgeConfigSpec.IntValue NIGHT_SEC_MEDIUM;
    public static final ForgeConfigSpec.IntValue NIGHT_SEC_HIGH;
    public static final ForgeConfigSpec.IntValue NIGHT_SEC_EXTRAORDINARY;
    public static final ForgeConfigSpec.IntValue NIGHT_SEC_RADIANT;

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

        b.push("night_sight");
        b.comment("11 pure night vision duration seconds (60/120/240/480/900)");
        NIGHT_SEC_LOW = b.defineInRange("low", 60, 1, 3600);
        NIGHT_SEC_MEDIUM = b.defineInRange("medium", 120, 1, 3600);
        NIGHT_SEC_HIGH = b.defineInRange("high", 240, 1, 3600);
        NIGHT_SEC_EXTRAORDINARY = b.defineInRange("extraordinary", 480, 1, 3600);
        NIGHT_SEC_RADIANT = b.defineInRange("radiant", 900, 1, 3600);
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

    public static int nightSeconds(ChefQuality q) {
        return byTier(q, NIGHT_SEC_LOW, NIGHT_SEC_MEDIUM, NIGHT_SEC_HIGH, NIGHT_SEC_EXTRAORDINARY, NIGHT_SEC_RADIANT);
    }

    /** 单菜原始经验 (按达成品质)。 */
    public static int rawXp(ChefQuality q) {
        return byTier(q, XP_LOW, XP_MEDIUM, XP_HIGH, XP_EXTRAORDINARY, XP_RADIANT);
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
