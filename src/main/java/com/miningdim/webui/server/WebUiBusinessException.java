package com.miningdim.webui.server;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Expected business rejection returned to WebUI without a warning stack trace. */
public final class WebUiBusinessException extends RuntimeException {

    private final String errorCode;
    private final boolean retrySameOpeningId;
    private final Map<String, String> params;

    /** 便捷重载: 无占位符实参的拒绝 (存量 case.* 抛出点走这条, 回执形状与加 params 之前逐字节一致)。 */
    public WebUiBusinessException(String errorCode, String message, boolean retrySameOpeningId) {
        this(errorCode, message, retrySameOpeningId, Map.of());
    }

    /**
     * @param params 错误码文案的占位符实参 (如 {@code {"field":"brandHue","value":"361"}})。前端只拿它填
     *               errorCode 对应的中文文案, 不参与任何计算, 故值一律字符串化 (数字也写成字符串);
     *               本地化文案住在前端, 服务端不下发中文占位内容。
     */
    public WebUiBusinessException(String errorCode, String message, boolean retrySameOpeningId,
                                  Map<String, String> params) {
        // Expected validation failures can be attacker-driven; avoid allocating a stack trace for each rejection.
        super(Objects.requireNonNull(message, "message"), null, false, false);
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("WebUI business error code must not be blank");
        }
        this.errorCode = errorCode;
        this.retrySameOpeningId = retrySameOpeningId;
        this.params = copyParams(params);
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retrySameOpeningId() {
        return retrySameOpeningId;
    }

    /** 占位符实参 (不可变, 保持调用方写入序); 无实参时为空 Map, 此时回执不写 params 键。 */
    public Map<String, String> params() {
        return params;
    }

    /**
     * 保留调用方写入序 (LinkedHashMap 而非 {@link Map#copyOf}) : 回执 JSON 的键序随之稳定, 日志与 GameTest
     * 才能逐字节比对同一个拒绝。空键/空值就地抛而不是静默丢弃 —— 契约声明 params 的值一律是字符串, 放一个
     * null 进去会在回执里长成 JSON null, 前端的形状校验会把整条业务错误判为契约破裂, 症状离病因太远。
     */
    private static Map<String, String> copyParams(Map<String, String> params) {
        Objects.requireNonNull(params, "params");
        if (params.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("WebUI business error param key must not be blank");
            }
            if (value == null) {
                throw new IllegalArgumentException("WebUI business error param '" + key + "' must not be null");
            }
            copy.put(key, value);
        }
        return Collections.unmodifiableMap(copy);
    }
}
