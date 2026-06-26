package com.miningdim.job.tarot.card;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条牌效原子操作 (TarotReader spec 第六章; 数据驱动的可执行单元)。从 datapack JSON 的一个对象解析。
 *
 * 数值字段语义随 {@link #kind} 而定 (见各字段注释); 解析期缺字段直接由 {@link GsonHelper} 抛 JsonSyntaxException
 * 冒泡, 不静默给默认 (spec 第十一章 C9)。可选字段经 hasXxx 判定, 不混淆 "默认值" 与 "字段缺失"。
 *
 * 不可变 record (1.20.1 允许; 非注册期 Codec, 不触 1.20.5+ MapCodec 红线)。
 */
public record TarotEffectOp(
        TarotEffectKind kind,
        /** 原版 MobEffect 注册名 (如 minecraft:speed); 仅 *_POTION 用, 其余空串。 */
        String effectId,
        /** MobEffect amplifier (0-based); 仅 *_POTION 用。 */
        int amplifier,
        /** 持续/归还 ticks; *_POTION 的药水时长, SELF_MAX_HEALTH 的归还延迟, SELF_HEAL_OVER_TIME 的周期。 */
        int durationTicks,
        /** 数量值; 治疗量/伤害量/吸收量/最大生命增减量 (语义随 kind)。 */
        double amount,
        /** AoE 半径 (格); 仅 AOE_* 用。 */
        double radius,
        /** 周期次数; 仅 SELF_HEAL_OVER_TIME 用。 */
        int count,
        /** 最大生命增的上限 (绝对 HP); 仅 SELF_MAX_HEALTH 增向用。 */
        double capUp,
        /** 最大生命减的下限 (绝对 HP, 不得低于此); 仅 SELF_MAX_HEALTH 减向用。 */
        double floorDown,
        /** 概率 0.0-1.0; 仅 SELF_DEATH_GAMBLE (当场死亡概率) 用。 */
        double chance,
        /** 百分比 0.0-1.0; 反伤比/吸血比 (SELF_REFLECT / SELF_LIFESTEAL) 用。 */
        double percent,
        /** 阈值 (绝对 HP); 斩杀准星目标的处决血线 (ENEMY_TARGET_DAMAGE) 用。 */
        double threshold,
        /** 周期间隔 ticks; 仅 AOE_*_OVER_TIME 用 (与 durationTicks 总时长配合, 次数 = duration/period)。 */
        int periodTicks,
        /** 免疫的 MobEffect 注册名列表; 仅 IMMUNITY 用 (可空, 表示仅免疫易伤)。不可变。 */
        List<String> effects,
        /** 是否在易伤仲裁点免疫易伤放大; 仅 IMMUNITY 用。 */
        boolean immuneVulnerability
) {

    /**
     * 从 datapack JSON 对象解析一条操作。必填: kind。其余按 kind 需要读取; 不做 "缺字段给 0" 的静默兜底 ——
     * 缺必要字段时 GsonHelper.getAsXxx 抛 JsonSyntaxException 自然冒泡 (spec C9)。
     */
    public static TarotEffectOp fromJson(JsonObject obj) {
        TarotEffectKind kind = TarotEffectKind.byId(GsonHelper.getAsString(obj, "kind"));
        String effectId = "";
        int amplifier = 0;
        int durationTicks = 0;
        double amount = 0.0D;
        double radius = 0.0D;
        int count = 0;
        double capUp = 0.0D;
        double floorDown = 0.0D;
        double chance = 0.0D;
        double percent = 0.0D;
        double threshold = 0.0D;
        int periodTicks = 0;
        List<String> effects = List.of();
        boolean immuneVulnerability = false;

        switch (kind) {
            case SELF_POTION:
            case AOE_ENEMY_POTION:
            case AOE_ALLY_POTION:
                effectId = GsonHelper.getAsString(obj, "effect");
                amplifier = GsonHelper.getAsInt(obj, "amplifier");
                durationTicks = GsonHelper.getAsInt(obj, "durationTicks");
                if (kind != TarotEffectKind.SELF_POTION) {
                    radius = GsonHelper.getAsDouble(obj, "radius");
                }
                break;
            case SELF_HEAL_OVER_TIME:
                amount = GsonHelper.getAsDouble(obj, "amount");
                durationTicks = GsonHelper.getAsInt(obj, "periodTicks");
                count = GsonHelper.getAsInt(obj, "count");
                break;
            case SELF_PERIODIC_ABSORPTION:
                amount = GsonHelper.getAsDouble(obj, "amount");
                durationTicks = GsonHelper.getAsInt(obj, "periodTicks");
                count = GsonHelper.getAsInt(obj, "count");
                break;
            case SELF_HEAL:
            case SELF_TRUE_DAMAGE:
            case SELF_ABSORPTION:
                amount = GsonHelper.getAsDouble(obj, "amount");
                break;
            case SELF_FULL_HEAL:
                // 无额外字段 (回满当前最大生命)。
                break;
            case SELF_MAX_HEALTH:
                amount = GsonHelper.getAsDouble(obj, "amount");
                durationTicks = GsonHelper.getAsInt(obj, "durationTicks");
                // 增向读 capUp, 减向读 floorDown; 解析期不区分朝向, 两者按存在性读 (二选一)。
                if (obj.has("capUp")) {
                    capUp = GsonHelper.getAsDouble(obj, "capUp");
                }
                if (obj.has("floorDown")) {
                    floorDown = GsonHelper.getAsDouble(obj, "floorDown");
                }
                break;
            case SELF_CLEANSE:
                // 无额外字段。
                break;
            case SELF_DEATH_GAMBLE:
                // 以命相赌 (倒吊人逆位): chance 概率当场死亡; 成功则牺牲最大生命 amount, durationTicks 后归还。
                chance = GsonHelper.getAsDouble(obj, "chance");
                amount = GsonHelper.getAsDouble(obj, "amount");
                durationTicks = GsonHelper.getAsInt(obj, "durationTicks");
                floorDown = GsonHelper.getAsDouble(obj, "floorDown");
                break;
            case SELF_DEATH_CONTRACT:
                // 复活契约 (死神逆位): durationTicks 内拦截 1 次致死, 复活回 amount 血。
                amount = GsonHelper.getAsDouble(obj, "amount");
                durationTicks = GsonHelper.getAsInt(obj, "durationTicks");
                break;
            case SELF_KNOCKBACK_IMMUNITY:
                durationTicks = GsonHelper.getAsInt(obj, "durationTicks");
                break;
            case SELF_LIFESTEAL:
                percent = GsonHelper.getAsDouble(obj, "percent");
                durationTicks = GsonHelper.getAsInt(obj, "durationTicks");
                break;
            case SELF_REFLECT:
                percent = GsonHelper.getAsDouble(obj, "percent");
                capUp = GsonHelper.getAsDouble(obj, "capUp"); // 单次反伤封顶 (绝对 HP)。
                durationTicks = GsonHelper.getAsInt(obj, "durationTicks");
                break;
            case SELF_REFLECT_ACCUM:
                // 累计反击窗 (正义闪耀): percent 回击比, capUp 单次封顶, radius 结算半径, durationTicks 窗口时长。
                percent = GsonHelper.getAsDouble(obj, "percent");
                capUp = GsonHelper.getAsDouble(obj, "capUp");
                radius = GsonHelper.getAsDouble(obj, "radius");
                durationTicks = GsonHelper.getAsInt(obj, "durationTicks");
                break;
            case SELF_DELAYED_LEDGER:
                // 延迟记账冻死窗 (倒吊人闪耀): percent 结算比 (0.50), amount 存活回血 (40), durationTicks 窗口时长。
                percent = GsonHelper.getAsDouble(obj, "percent");
                amount = GsonHelper.getAsDouble(obj, "amount");
                durationTicks = GsonHelper.getAsInt(obj, "durationTicks");
                break;
            case SELF_INVULNERABLE:
                durationTicks = GsonHelper.getAsInt(obj, "durationTicks");
                break;
            case SHINY_BIND_SHARE_LIFE:
                // 恋人闪耀: durationTicks 绑定时长, radius 解绑距离 (50), count 一方死后另一方延迟死亡 ticks (60=3s)。
                durationTicks = GsonHelper.getAsInt(obj, "durationTicks");
                radius = GsonHelper.getAsDouble(obj, "radius");
                count = GsonHelper.getAsInt(obj, "count");
                break;
            case AOE_EXECUTE_BELOW_PCT:
                // 死神闪耀: percent 处决血占比阈值 (0.30), radius 半径 (12), amount 无目标穿刺 (50),
                // threshold 每杀回血 (20), amplifier 力量叠层上限 (4=V)。
                percent = GsonHelper.getAsDouble(obj, "percent");
                radius = GsonHelper.getAsDouble(obj, "radius");
                amount = GsonHelper.getAsDouble(obj, "amount");
                threshold = GsonHelper.getAsDouble(obj, "threshold");
                amplifier = GsonHelper.getAsInt(obj, "amplifier");
                break;
            case ENEMY_TARGET_DAMAGE:
                // 准星单体 (死神正位): 准星目标血 < threshold 处决, 否则 amount 伤害。reach=radius。
                amount = GsonHelper.getAsDouble(obj, "amount");
                radius = GsonHelper.getAsDouble(obj, "radius");
                threshold = GsonHelper.getAsDouble(obj, "threshold");
                break;
            case ENEMY_TARGET_AVERAGE_HEALTH:
                // 均值化 (正义逆位): 准星目标与自身当前血各设为均值, 单次最多 ±capUp。reach=radius。
                radius = GsonHelper.getAsDouble(obj, "radius");
                capUp = GsonHelper.getAsDouble(obj, "capUp");
                break;
            case AOE_ENEMY_RANDOM_DAMAGE:
                // 随机 1 敌 (恋人逆位): 半径内随机抽 1 敌 amount 伤害, 无敌则自身双倍真伤。
                amount = GsonHelper.getAsDouble(obj, "amount");
                radius = GsonHelper.getAsDouble(obj, "radius");
                break;
            case AOE_ENEMY_DAMAGE:
            case AOE_ALLY_HEAL:
            case AOE_ALLY_ABSORPTION:
                amount = GsonHelper.getAsDouble(obj, "amount");
                radius = GsonHelper.getAsDouble(obj, "radius");
                break;
            case AOE_ENEMY_DAMAGE_OVER_TIME:
            case AOE_ALLY_HEAL_OVER_TIME:
                // 太阳每秒灼敌 / 闪耀每秒为友回血: 扁平每跳值 amount + 半径 + 周期 + 总时长 (次数引擎按 duration/period 算)。
                amount = GsonHelper.getAsDouble(obj, "amount");
                radius = GsonHelper.getAsDouble(obj, "radius");
                periodTicks = GsonHelper.getAsInt(obj, "periodTicks");
                durationTicks = GsonHelper.getAsInt(obj, "durationTicks");
                break;
            case IMMUNITY:
                // 免疫窗: durationTicks + 免疫的 effect 列表 (可缺, 缺即仅免易伤) + 是否免易伤放大。
                durationTicks = GsonHelper.getAsInt(obj, "durationTicks");
                immuneVulnerability = GsonHelper.getAsBoolean(obj, "vulnerability");
                effects = parseEffectList(obj);
                break;
            default:
                throw new IllegalArgumentException("Unhandled tarot effect kind in fromJson: " + kind);
        }

        return new TarotEffectOp(kind, effectId, amplifier, durationTicks, amount, radius, count,
                capUp, floorDown, chance, percent, threshold, periodTicks, effects, immuneVulnerability);
    }

    /**
     * 解析 IMMUNITY 的免疫 effect 列表 (JSON 数组字段 "effects", 每项为 MobEffect 注册名字符串)。字段缺失即返回空表
     * (语义: 该免疫窗仅免易伤、不免任何 MobEffect; 非静默兜底缺陷, 而是 "免疫列表为空" 的合法配置)。返回不可变表。
     */
    private static List<String> parseEffectList(JsonObject obj) {
        if (!obj.has("effects")) {
            return List.of();
        }
        JsonArray arr = GsonHelper.getAsJsonArray(obj, "effects");
        List<String> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            out.add(GsonHelper.convertToString(el, "effect"));
        }
        return List.copyOf(out);
    }
}
