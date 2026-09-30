package com.miningdim.district.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 业务拒绝 (unchecked, 设计文档 6.3)。在事务内抛出时 StoreTx 整体回滚; 平板层一对一转成 WebUiBusinessException,
 * 命令层转成 sendFailure。message 就是玩家看到的中文句子; params 的值全部是非 null 字符串, 保持写入顺序。
 *
 * 不分配堆栈: 拒绝是任何人都能无限次触发的, 与 WebUiBusinessException 同理。
 */
public final class DistrictRuleException extends RuntimeException {

    private final DistrictError code;
    private final Map<String, String> params;

    public DistrictRuleException(DistrictError code, String message, Map<String, String> params) {
        super(Objects.requireNonNull(message, "message"), null, false, false);
        this.code = Objects.requireNonNull(code, "code");
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "param key"),
                    Objects.requireNonNull(entry.getValue(), () -> "param " + entry.getKey()));
        }
        this.params = Collections.unmodifiableMap(copy);
    }

    public DistrictRuleException(DistrictError code, String message) {
        this(code, message, Map.of());
    }

    public DistrictError code() {
        return code;
    }

    public Map<String, String> params() {
        return params;
    }

    /** 按顺序拼 params 的便捷写法: {@code params("playerName", name, "unbound", "true")}。 */
    public static Map<String, String> params(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("params needs key/value pairs");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** 机器字段取值域外 (枚举、开关值): INVALID_REQUEST + {field[, value]}, 回显值截断到 64 字符。 */
    public static DistrictRuleException invalidRequest(String field, @org.jetbrains.annotations.Nullable String value) {
        if (value == null) {
            return new DistrictRuleException(DistrictError.INVALID_REQUEST,
                    "字段 " + field + " 缺失或类型不对", params("field", field));
        }
        return new DistrictRuleException(DistrictError.INVALID_REQUEST,
                "字段 " + field + " 不接受这个取值", params("field", field, "value", DistrictTexts.echo(value)));
    }
}
