package com.miningdim.job.farmer;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/** Read-only crop output table derived from the live farmland tier values. */
public final class FarmerCropTable {
    private FarmerCropTable() {
    }

    public static List<Row> rows() {
        return Arrays.stream(FarmerTier.values()).map(FarmerCropTable::row).toList();
    }

    public static Row row(FarmerTier tier) {
        double harvestsPerHour = 60.0D / tier.growthIntervalMinutes();
        double farmerWheatPerHour = harvestsPerHour * tier.yieldPerHarvest();
        return new Row(tier, tier.unlockLevel(), tier.growthIntervalMinutes(),
                tier.yieldPerHarvest(), farmerWheatPerHour,
                Math.round(farmerWheatPerHour * 6.0D));
    }

    public static String amount(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    public record Row(FarmerTier tier, int unlockLevel, int growthMinutes,
                      int yieldMultiplier, double farmerWheatPerHour,
                      long farmerWheatPerSixHours) {
    }
}
