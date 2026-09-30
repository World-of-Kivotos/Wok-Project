package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 一块已删地块的墓碑。它的地块记录不搬行: 墓碑按 plot_id 读 district_plot_log 的全部任期 —— 区务长能删空置地块,
 * 但不能借此抹掉以前的记录。
 */
public record TombstoneRecord(
        String plotId,
        String districtId,
        String code,
        PlotArea area,
        long deletedAt,
        @Nullable UUID deletedByUuid,
        String deletedByName) {
}
