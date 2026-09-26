package com.miningdim.achievement.trigger;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.miningdim.core.Difficulty;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

/**
 * 自定义触发器条件字段的读取与校验。取值越界或写错一律抛 {@link JsonSyntaxException}: 原版加载进度时会记一条
 * 错误并跳过这一个进度, 不会带着一个静默失效的条件上线 (写错的难度 / 矿种若按"不限"处理, 等于把成就白送)。
 */
final class TriggerJson {

    private TriggerJson() {
    }

    /** 可选的矿区难度 (easy / medium / hard); 缺省为 null, 表示不限难度。 */
    @Nullable
    static Difficulty optionalDifficulty(JsonObject json, String field) {
        if (!json.has(field)) {
            return null;
        }
        String raw = GsonHelper.getAsString(json, field);
        try {
            return Difficulty.byConfigName(raw);
        } catch (IllegalArgumentException unknown) {
            throw new JsonSyntaxException("unknown difficulty '" + raw + "' in '" + field
                    + "', expected easy, medium or hard");
        }
    }

    /** 可选的 0~1 比例; 缺省为 null。 */
    @Nullable
    static Double optionalRatio(JsonObject json, String field) {
        if (!json.has(field)) {
            return null;
        }
        double value = GsonHelper.getAsDouble(json, field);
        if (!(value >= 0.0D && value <= 1.0D)) {
            throw new JsonSyntaxException("'" + field + "' must be within 0..1, got " + value);
        }
        return value;
    }

    /** 可选的非负数值 (距离等); 缺省为 null。 */
    @Nullable
    static Double optionalNonNegative(JsonObject json, String field) {
        if (!json.has(field)) {
            return null;
        }
        double value = GsonHelper.getAsDouble(json, field);
        if (!(value >= 0.0D) || Double.isInfinite(value)) {
            throw new JsonSyntaxException("'" + field + "' must be a finite non-negative number, got " + value);
        }
        return value;
    }

    /** 可选的非负 tick 数; 缺省为 null。 */
    @Nullable
    static Long optionalTicks(JsonObject json, String field) {
        if (!json.has(field)) {
            return null;
        }
        long value = GsonHelper.getAsLong(json, field);
        if (value < 0L) {
            throw new JsonSyntaxException("'" + field + "' must not be negative, got " + value);
        }
        return value;
    }

    /** 范围内的整数; 字段缺省时取 fallback (fallback 本身不校验, 由调用方保证在范围内)。 */
    static int intInRange(JsonObject json, String field, int fallback, int min, int max) {
        if (!json.has(field)) {
            return fallback;
        }
        int value = GsonHelper.getAsInt(json, field);
        if (value < min || value > max) {
            throw new JsonSyntaxException("'" + field + "' must be within " + min + ".." + max + ", got " + value);
        }
        return value;
    }

    /** 必填的范围内整数。 */
    static int requiredIntInRange(JsonObject json, String field, int min, int max) {
        if (!json.has(field)) {
            throw new JsonSyntaxException("missing required field '" + field + "'");
        }
        return intInRange(json, field, min, min, max);
    }
}
