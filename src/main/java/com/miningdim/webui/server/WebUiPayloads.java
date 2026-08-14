package com.miningdim.webui.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * Web UI action 入参的形状/取值校验词汇 (全部转 {@link WebUiErrorCodes#INVALID_REQUEST} + params.field)。
 *
 * 住在框架层而不是某个业务包: 校验词汇本就不属于任何一个域, 而回执 params 的形状 (field / value) 与超长值的
 * 截断上限是对外契约的一部分 —— 第二个域再抄一份, 两份就会各自漂移, 前端拿到的同一个 INVALID_REQUEST
 * 会有两种 params 形状。W3 起 job.farmer.sell 与存量 player.* 共用本类。
 */
public final class WebUiPayloads {

    private WebUiPayloads() {
    }

    public static JsonElement requiredField(JsonObject payload, String field) {
        JsonElement raw = payload.get(field);
        if (raw == null || raw.isJsonNull()) {
            throw new WebUiBusinessException(WebUiErrorCodes.INVALID_REQUEST,
                    "缺少必填字段 " + field, false, Map.of("field", field));
        }
        return raw;
    }

    public static boolean requiredBoolean(JsonObject payload, String field) {
        JsonElement raw = requiredField(payload, field);
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isBoolean()) {
            throw wrongType(field, "布尔值");
        }
        return raw.getAsBoolean();
    }

    public static String requiredString(JsonObject payload, String field) {
        JsonElement raw = requiredField(payload, field);
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()) {
            throw wrongType(field, "字符串");
        }
        return raw.getAsString();
    }

    public static int requiredInt(JsonObject payload, String field) {
        JsonElement raw = requiredField(payload, field);
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()) {
            throw wrongType(field, "整数");
        }
        double value = raw.getAsDouble();
        // NaN 自动落进第一个条件 (NaN != NaN); 无穷大与超 int 域的值落进后两个。
        if (value != Math.floor(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw wrongType(field, "32 位整数");
        }
        return (int) value;
    }

    /**
     * 类型不符只回 field, 不回 value: 这一档的实参可能是任意 JSON (整个对象/数组), 原样塞回 params 等于让
     * 客户端决定回执体积。取值域外那一档才回 value —— 那时它必定是个标量。
     */
    public static WebUiBusinessException wrongType(String field, String expected) {
        return new WebUiBusinessException(WebUiErrorCodes.INVALID_REQUEST,
                "字段 " + field + " 必须是" + expected, false, Map.of("field", field));
    }

    /** params 里回显客户端输入的字符上限, 理由见 {@link #illegalValue}。 */
    private static final int MAX_PARAM_VALUE_CHARS = 64;

    /**
     * 取值域外的拒绝: params 带 field 与 value, 让前端定位到具体控件并显示被拒的值。
     *
     * value 必须截断。回执经 S2CWebUiResponse 的 writeUtf 下行 (上限 32767 字符), 而入站 payload 的
     * readUtf 允许客户端送来近 32767 字符的标量 —— {@link #wrongType} 那句"取值域外必定是标量"只排除了
     * 对象与数组, 没排除超长标量。原样回显会让 resultJson 越界, 此时 EncoderException 是从
     * WebUiServerDispatcher.dispatchAndRespond 的 catch 块**内部**抛出的, 已不在任何 catch 的覆盖范围内:
     * 该 requestId 既收不到回执, 又已被防重放窗口烧掉 (同 id 重试只会得到 duplicate_request), 前端 Promise
     * 永不 settle。截断把回执体积与客户端输入解耦, 且占位符文案本就不需要展示一整段输入。
     */
    public static WebUiBusinessException illegalValue(String field, String value, String message) {
        String shown = value.length() <= MAX_PARAM_VALUE_CHARS
                ? value
                : value.substring(0, MAX_PARAM_VALUE_CHARS) + "...";
        return new WebUiBusinessException(WebUiErrorCodes.INVALID_REQUEST, message, false,
                Map.of("field", field, "value", shown));
    }
}
