package com.miningdim.district.flan;

import com.miningdim.district.core.PlotArea;

import java.util.Objects;
import java.util.UUID;

/**
 * 一块顶层个人领地 (Flan 的 owner 不为 null, 设计文档 22.20): 自管区外围的清单、摘要与计数用它。
 *
 * @param handle 领地句柄 (顶层, parentId 为 null)
 * @param owner  主人
 * @param area   X/Z 范围 (两端都含)
 * @param flat   2D 领地 (Flan 的 !is3d())
 * @param minY   底 (Flan 的 getDimensions().minY())
 * @param maxY   顶 (Flan 的 getDimensions().maxY(); 2D 领地是世界顶)
 */
public record PersonalClaim(ClaimHandle handle, UUID owner, PlotArea area, boolean flat, int minY, int maxY) {

    public PersonalClaim {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(area, "area");
    }
}
