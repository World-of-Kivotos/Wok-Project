package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一张开关表 (公共区域或一块地): permissionId -> (audience wire -> 开 / 关)。每一格都是库里的一行、只有开或关;
 * 缺格是代码 bug (开服时按目录回填), 读的一方用 {@link #require} 当场报出来, 不默默当成默认值。
 */
public final class PermissionCells {

    private final Map<String, Map<String, Boolean>> values;

    public PermissionCells(Map<String, Map<String, Boolean>> values) {
        Map<String, Map<String, Boolean>> copy = new LinkedHashMap<>();
        values.forEach((permissionId, row) -> copy.put(permissionId, Collections.unmodifiableMap(new LinkedHashMap<>(row))));
        this.values = Collections.unmodifiableMap(copy);
    }

    @Nullable
    public Boolean get(String permissionId, String audience) {
        Map<String, Boolean> row = values.get(permissionId);
        return row == null ? null : row.get(audience);
    }

    public boolean require(String permissionId, String audience) {
        Boolean value = get(permissionId, audience);
        if (value == null) {
            throw new IllegalStateException("permission cell " + permissionId + "/" + audience
                    + " is missing; the startup backfill should have written every catalog cell");
        }
        return value;
    }

    public boolean plot(PermissionItemDef item, PlotAudience audience) {
        return require(item.permissionId(), audience.wire());
    }

    public boolean district(PermissionItemDef item, DistrictAudience audience) {
        return require(item.permissionId(), audience.wire());
    }

    public Map<String, Map<String, Boolean>> asMap() {
        return values;
    }
}
