package com.miningdim.district.flan;

import org.jetbrains.annotations.Nullable;

/**
 * 一次 Flan 写入的结果。error 是管理员能看懂的一句中文 (如"区块未加载""领地对接尚未启用（阶段 1）"), 原样进
 * sync_error, 只给管理员看。
 */
public record FlanResult<T>(boolean ok, @Nullable T value, @Nullable String error) {

    public static <T> FlanResult<T> success(@Nullable T value) {
        return new FlanResult<>(true, value, null);
    }

    public static FlanResult<Void> success() {
        return new FlanResult<>(true, null, null);
    }

    public static <T> FlanResult<T> failure(String error) {
        return new FlanResult<>(false, null, error);
    }
}
