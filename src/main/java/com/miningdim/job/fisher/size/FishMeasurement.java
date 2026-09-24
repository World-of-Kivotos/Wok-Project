package com.miningdim.job.fisher.size;

/** 一次钓获的测量结果: 长度按毫米、重量按毫克取整存储, 整数便于比较与紧凑存盘。 */
public record FishMeasurement(int lengthMm, long weightMg, FishSizeClass sizeClass) {
    public FishMeasurement {
        if (lengthMm <= 0 || weightMg <= 0L || sizeClass == null) {
            throw new IllegalArgumentException("Invalid fish measurement: " + lengthMm + " mm, " + weightMg + " mg");
        }
    }
}
