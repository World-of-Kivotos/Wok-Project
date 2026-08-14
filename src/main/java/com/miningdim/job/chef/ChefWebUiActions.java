package com.miningdim.job.chef;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;

/**
 * 厨师面板的 job.chef.state WebUiAction (品质档表 + 效果数值矩阵)。
 *
 * 全部数值每次调用实时 {@code ChefConfig.*.get()} —— 运营改了 toml 就该立刻在面板上看到, 抄一份静态副本等于
 * 让面板永远显示进程启动那一刻的数值。
 *
 * 落在 {@code com.miningdim.job.chef} 包内是硬要求: 逐效果逐品质的数值快照唯一实现
 * {@link ChefEffectMagnitude#snapshot} 是包私有, 本类同包即天然可见 —— 既不用为了面板放宽它的可见性,
 * 也不用在面板层重写一份 type -&gt; ChefConfig 的 switch (那份副本迟早与掷出时用的真值分叉)。
 *
 * 前端契约 (webui/src/lib/types.ts): job.chef.state -&gt;
 * {level,qualityCapTier,qualities[5],effects[18],seasoningCostCredit}。
 * effects 按"效果成行、品质成列": 真实数据是 18x5 的矩阵, 且各效果 magnitude 语义不同 (倍率 x100 / 千分比 /
 * 秒 / 1-based 等级 / 概率千分比), 既压不进"一档一个值"的单列表, 也就必须每行自带 unit。
 */
public final class ChefWebUiActions {

    private static final Gson GSON = new Gson();

    private ChefWebUiActions() {
    }

    /** 把 job.chef.state 注册进派发器 (由 {@link ChefSystem#register} 调用一次)。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("job.chef.state", STATE);
    }

    static final WebUiAction STATE = (sender, payload) -> {
        int level = JobServices.jobService().level(sender, JobId.CHEF);

        JsonObject result = new JsonObject();
        result.addProperty("level", level);
        result.addProperty("qualityCapTier", ChefQualityResolver.qualityCapForLevel(level).tier());

        JsonArray qualities = new JsonArray();
        for (ChefQuality quality : ChefQuality.values()) {
            JsonObject row = new JsonObject();
            row.addProperty("qualityId", quality.id());
            row.addProperty("tier", quality.tier());
            row.addProperty("nameKey", quality.prefixKey());
            row.addProperty("maxEffects", quality.maxEffects());
            row.addProperty("noFailure", quality.noFailure());
            row.addProperty("combatUnlocked", quality.combatUnlocked());
            row.addProperty("rawXp", ChefConfig.rawXp(quality));
            qualities.add(row);
        }
        result.add("qualities", qualities);

        JsonArray effects = new JsonArray();
        for (ChefEffectType type : ChefEffectType.values()) {
            JsonObject row = new JsonObject();
            row.addProperty("effectId", type.id());
            // 拼法与 ChefTooltipHandler 一致 (同一批 lang 键, 不另起命名)。
            row.addProperty("labelKey", "chef.effect." + type.id());
            row.addProperty("combat", type.isCombat());
            row.addProperty("negative", type.isNegative());
            row.addProperty("windowed", type.isWindowed());
            row.addProperty("unit", unitOf(type));

            JsonArray magnitudes = new JsonArray();
            JsonArray durations = new JsonArray();
            for (ChefQuality quality : ChefQuality.values()) {
                // 0 是真值不是缺数据: 各效果都有自己的档位门 (膳香只在高/超凡/闪耀, 负面只在低/中/高), 门外恒 0。
                magnitudes.add(ChefEffectMagnitude.snapshot(type, quality));
                durations.add(durationSeconds(type, quality));
            }
            row.add("magnitudes", magnitudes);
            row.add("durationSeconds", durations);
            effects.add(row);
        }
        result.add("effects", effects);

        result.addProperty("seasoningCostCredit", ChefConfig.TABLE_USE_COST_CREDIT.get());
        return GSON.toJson(result);
    };

    /**
     * magnitude 的量纲。逐条取自 {@link ChefEffectType} 各成员的结算语义注释 —— 前端拿它决定把 120 显示成
     * "x1.2" 还是 "12.0%" 还是 "120 秒", 发错一条就是把玩家的数值观整个带偏。
     */
    private static String unitOf(ChefEffectType type) {
        return switch (type) {
            case AMPLIFY, NOURISH_FOOD, AFTERTASTE_SAT -> "mul_x100";
            case SATED_JUMP, REFRESH, NAUSEA -> "level";
            case NOURISH_HEAL, SHIELD, GREASE, AFTERTASTE_REGEN, STABLE_AIM, ENDURANCE,
                 UNDERDONE, SCORCHED -> "permille";
            case PURIFY -> "count";
            case NIGHT_SIGHT -> "seconds";
            // 多盐 (饱和减半) 与失败品 (销毁菜肴) 是固定语义, magnitude 恒 0 且不参与结算。
            case OVERSALT, SPOILED -> "none";
        };
    }

    /**
     * 该效果在该品质档下的独立持续时间 (秒); 0 = 进食一次性结算, 没有独立时长。
     *
     * 四个战斗向窗口效果的窗口长度与品质无关 (5 档同值), 这是 ChefConfig 的既有形态, 不在此按档伪造差异。
     */
    private static int durationSeconds(ChefEffectType type, ChefQuality quality) {
        return switch (type) {
            case ENDURANCE -> ChefConfig.enduranceSeconds(quality);
            case REFRESH -> ChefConfig.refreshSeconds(quality);
            // 夜照的 magnitude 本身就是时长秒, 两栏同值是它的语义 (不是重复发送)。
            case NIGHT_SIGHT -> ChefConfig.nightSeconds(quality);
            case SHIELD -> ChefConfig.SHIELD_WINDOW_SECONDS.get();
            case GREASE -> ChefConfig.GREASE_WINDOW_SECONDS.get();
            case AFTERTASTE_REGEN -> ChefConfig.REGEN_WINDOW_SECONDS.get();
            case STABLE_AIM -> ChefConfig.STABLE_AIM_WINDOW_SECONDS.get();
            case UNDERDONE -> ChefEffectMagnitude.underdoneSeconds(quality);
            case NAUSEA -> ChefEffectMagnitude.nauseaSeconds(quality);
            case AMPLIFY, NOURISH_FOOD, AFTERTASTE_SAT, SATED_JUMP, NOURISH_HEAL, PURIFY,
                 OVERSALT, SPOILED, SCORCHED -> 0;
        };
    }
}
