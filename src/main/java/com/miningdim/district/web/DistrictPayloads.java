package com.miningdim.district.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.service.PlotLayoutService;
import com.miningdim.webui.server.WebUiPayloads;
import org.jetbrains.annotations.Nullable;

/**
 * 自管区动作的入参读取 (设计文档 14.1 第 5 条)。
 *
 * 读取分两档, 对应"在哪一步报错":
 * <ul>
 *   <li>机器字段 (districtId、plotId、playerName、permissionId 这类必填字符串, 以及 plot.buy 的 expectedPrice 与
 *       expectedBounds) 缺失或类型不对时当场报 INVALID_REQUEST, 排在一切业务检查之前 —— 用 {@link WebUiPayloads} 的
 *       读取器与 {@link #requiredArea};</li>
 *   <li>其余字段 (开关值、枚举取值、移出原因、坐标、单价与尺寸) 在服务层按契约检查顺序校验它的那一步才报错:
 *       这里只把它们读成"可空的值", 取不到就交 null 给服务层, 由服务层在对应的那一步按契约报码。</li>
 * </ul>
 * 开关值与 allowNeverJoined 刻意分两种口径: 开关值只收 JSON 布尔, 缺省、null、字符串一律交 null 让服务层拒绝, 绝不
 * 当成 false 或"不设置"; allowNeverJoined 只有 JSON {@code true} 算真, 其余一律算假 (漏传的请求就是"没确认")。
 */
final class DistrictPayloads {

    /** 范围对象的四个坐标键, 也是缺失时报出的顺序。 */
    private static final String[] AREA_FIELDS = {"minX", "minZ", "maxX", "maxZ"};

    private DistrictPayloads() {
    }

    /** 必填的机器字符串字段 (缺失或不是字符串即 INVALID_REQUEST)。 */
    static String id(JsonObject payload, String field) {
        return WebUiPayloads.requiredString(payload, field);
    }

    /** 开关值: 只有 JSON 布尔才返回值, 其余 (缺省、null、字符串、数字) 一律 null, 由服务层报 INVALID_REQUEST。 */
    @Nullable
    static Boolean strictBoolean(JsonObject payload, String field) {
        JsonElement raw = payload.get(field);
        if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isBoolean()) {
            return null;
        }
        return raw.getAsBoolean();
    }

    /** 只有 JSON {@code true} 算真 (allowNeverJoined): 缺键、null、字符串 "true" 都算假。 */
    static boolean optionalTrue(JsonObject payload, String field) {
        JsonElement raw = payload.get(field);
        return raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isBoolean() && raw.getAsBoolean();
    }

    /** 可空的字符串: 缺键、null、不是字符串一律 null (枚举取值、移出原因由服务层在对应的那一步校验)。 */
    @Nullable
    static String optionalString(JsonObject payload, String field) {
        JsonElement raw = payload.get(field);
        if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()) {
            return null;
        }
        return raw.getAsString();
    }

    /**
     * 可空的安全整数: 是整数且在 ±(2^53 - 1) 以内才返回, 其余一律 null (单价与尺寸由服务层报 INVALID_PRICE /
     * INVALID_SIZE_LIMIT)。
     */
    @Nullable
    static Long optionalSafeLong(JsonObject payload, String field) {
        JsonElement raw = payload.get(field);
        if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return safeLong(raw.getAsJsonPrimitive());
    }

    /**
     * 地块范围 {@code {minX, minZ, maxX, maxZ}}: 四个坐标都是 32 位整数时给出范围; 否则指出第一个缺失或不是整数的
     * 字段 (area 本身不是对象时为 "area"), 由服务层在身份门之后报 INVALID_AREA。
     */
    static PlotLayoutService.AreaInput area(JsonObject payload) {
        JsonElement raw = payload.get("area");
        if (raw == null || !raw.isJsonObject()) {
            return PlotLayoutService.AreaInput.invalid("area");
        }
        JsonObject area = raw.getAsJsonObject();
        int[] values = new int[4];
        for (int i = 0; i < AREA_FIELDS.length; i++) {
            Integer value = intOf(area.get(AREA_FIELDS[i]));
            if (value == null) {
                return PlotLayoutService.AreaInput.invalid(AREA_FIELDS[i]);
            }
            values[i] = value;
        }
        return PlotLayoutService.AreaInput.of(new PlotArea(values[0], values[1], values[2], values[3]));
    }

    /**
     * 必填的机器范围字段 (plot.buy 的 expectedBounds: 界面把确认框里那块地的范围原样送回来, 人手碰不到它):
     * 缺失、不是对象、四个坐标里有一个缺失或不是 32 位整数, 一律当场 INVALID_REQUEST {field}, 排在一切业务检查之前。
     */
    static PlotArea requiredArea(JsonObject payload, String field) {
        JsonElement raw = WebUiPayloads.requiredField(payload, field);
        if (!raw.isJsonObject()) {
            throw WebUiPayloads.wrongType(field, "范围对象 {minX, minZ, maxX, maxZ}");
        }
        JsonObject area = raw.getAsJsonObject();
        int[] values = new int[4];
        for (int i = 0; i < AREA_FIELDS.length; i++) {
            Integer value = intOf(area.get(AREA_FIELDS[i]));
            if (value == null) {
                throw WebUiPayloads.wrongType(field, "范围对象 {minX, minZ, maxX, maxZ}, 四个坐标都是整数");
            }
            values[i] = value;
        }
        return new PlotArea(values[0], values[1], values[2], values[3]);
    }

    @Nullable
    private static Integer intOf(@Nullable JsonElement raw) {
        if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        Long value = safeLong(raw.getAsJsonPrimitive());
        if (value == null || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            return null;
        }
        return value.intValue();
    }

    @Nullable
    private static Long safeLong(JsonPrimitive number) {
        double value = number.getAsDouble();
        if (value != Math.floor(value) || value < -WebUiPayloads.MAX_SAFE_INTEGER
                || value > WebUiPayloads.MAX_SAFE_INTEGER) {
            return null;
        }
        return (long) value;
    }
}
