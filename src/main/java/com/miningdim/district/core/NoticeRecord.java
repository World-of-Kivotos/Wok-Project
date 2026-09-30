package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 通知队列 district_notice 的一行 (设计文档 22.11)。kind 是 wire 值原样 (投递时才解析: 回退构建之后可能有不认识的值);
 * args 是事件发生那一刻格式化好的参数, args_json 读不出来 (畸形行) 时为 null, 投递时当成不认识的行丢掉。
 */
public record NoticeRecord(
        long id,
        UUID recipient,
        String kind,
        @Nullable List<String> args,
        @Nullable String districtId,
        @Nullable String plotId,
        long createdAt) {

    public NoticeRecord {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(kind, "kind");
        args = args == null ? null : List.copyOf(args);
    }
}
