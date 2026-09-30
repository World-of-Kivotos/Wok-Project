package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

/**
 * 移出住户的原因种类。连带后果只看种类: 只有 {@link #VIOLATION} 会暂停 TA 在本区别人地块的朋友身份;
 * 原因原文只进本区记录, 不解析。
 */
public enum RemoveReasonKind {
    INACTIVE("inactive"),
    VIOLATION("violation"),
    SELF_REQUEST("selfRequest"),
    OTHER("other");

    private final String wire;

    RemoveReasonKind(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** 取值不在四种之内 (含 null) 时返回 null, 由调用方报 INVALID_REQUEST。 */
    @Nullable
    public static RemoveReasonKind fromWire(@Nullable String raw) {
        for (RemoveReasonKind value : values()) {
            if (value.wire.equals(raw)) {
                return value;
            }
        }
        return null;
    }
}
