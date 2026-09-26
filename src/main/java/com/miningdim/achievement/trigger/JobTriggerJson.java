package com.miningdim.achievement.trigger;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.Function;

/**
 * 职业触发器 (6.2 的 P2 职业各行) 条件字段里的枚举值读取。与 {@link TriggerJson} 同一纪律: 写错的值整条拒收
 * (抛 {@link JsonSyntaxException}, 原版加载进度时跳过这一个进度), 不退化成"不限"。
 */
final class JobTriggerJson {

    private JobTriggerJson() {
    }

    /**
     * 可选的枚举字段, 按各枚举自己的稳定小写 id 匹配; 缺省为 null (不限)。
     *
     * @param values 该枚举的全部取值
     * @param idOf   取值 -> JSON 里的写法
     */
    @Nullable
    static <E> E optionalId(JsonObject json, String field, E[] values, Function<E, String> idOf) {
        if (!json.has(field)) {
            return null;
        }
        String raw = GsonHelper.getAsString(json, field);
        for (E value : values) {
            if (idOf.apply(value).equals(raw)) {
                return value;
            }
        }
        throw new JsonSyntaxException("unknown value '" + raw + "' in '" + field + "', expected one of "
                + Arrays.stream(values).map(idOf).toList());
    }

    /** 没有自带小写 id 的枚举 (封印类别、护甲板档位) 在 JSON 里的写法: 枚举名小写。 */
    static String lowerName(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
