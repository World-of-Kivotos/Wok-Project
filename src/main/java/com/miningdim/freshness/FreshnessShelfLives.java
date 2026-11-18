package com.miningdim.freshness;

/** 服务端同步载荷读取当前世界配置，客户端显示读取上次收到的快照。 */
public final class FreshnessShelfLives {

    private FreshnessShelfLives() {
    }

    /** 客户端由同步包写入的六类保质期; null = 还没同步过。 */
    private static volatile int[] synced;

    /** 这一类的保质期 (小时)。 */
    public static int clientHours(FreshnessCategory category) {
        int[] override = synced;
        if (override != null && category.ordinal() < override.length) {
            return override[category.ordinal()];
        }
        return category.defaultShelfLifeHours();
    }

    /** 客户端收到同步包时写入 (六个值, 顺序 = {@link FreshnessCategory} 的序)。 */
    public static void applySynced(int[] values) {
        synced = values == null ? null : values.clone();
    }

    /** 断开连接时清掉 (免得把上一个服务器的保质期带进下一个服务器)。 */
    public static void clearSynced() {
        synced = null;
    }

    /** 服务端打包用: 当下配置里的六个值 (顺序 = 枚举序)。 */
    public static int[] currentValues() {
        FreshnessCategory[] all = FreshnessCategory.values();
        int[] values = new int[all.length];
        for (int i = 0; i < all.length; i++) {
            values[i] = FreshnessConfig.shelfLifeHours(all[i]);
        }
        return values;
    }
}
