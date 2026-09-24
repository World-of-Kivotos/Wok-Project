package com.miningdim.job.fisher.size;

import java.util.Locale;

/** 体长/体重的显示格式。数字按 ROOT 区域格式化, 单位不走翻译, 两个语言文件共用同一写法。 */
public final class FishSizeFormat {
    private FishSizeFormat() {
    }

    public static String length(int lengthMm) {
        return String.format(Locale.ROOT, "%.1f cm", lengthMm / 10.0D);
    }

    /** 10 g 以下保留一位小数, 1 kg 以下取整克, 以上按千克两位小数。 */
    public static String weight(long weightMg) {
        if (weightMg < 10_000L) {
            return String.format(Locale.ROOT, "%.1f g", weightMg / 1000.0D);
        }
        if (weightMg < 1_000_000L) {
            return String.format(Locale.ROOT, "%d g", Math.round(weightMg / 1000.0D));
        }
        return String.format(Locale.ROOT, "%.2f kg", weightMg / 1_000_000.0D);
    }
}
