package com.miningdim.district.flan;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * 一块 Flan 领地的句柄: 维度、领地 id、父领地 id (自管区本身为 null, 地块为自管区的 id)。
 * Flan 的地块 id 只在兄弟之间唯一、且不在 claimUUIDMap 里, 所以地块一律按 (自管区, 地块) 两级定位。
 */
public record ClaimHandle(String dimension, UUID claimId, @Nullable UUID parentId) {

    public ClaimHandle {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(claimId, "claimId");
    }
}
