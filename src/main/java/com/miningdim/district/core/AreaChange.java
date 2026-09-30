package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

/** 划地块 (from 为 null)、调范围、删地块 (to 为 null) 的范围变化。 */
public record AreaChange(@Nullable PlotArea from, @Nullable PlotArea to) {
}
