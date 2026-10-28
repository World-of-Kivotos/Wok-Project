package com.miningdim.freshness;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * 六类保质期的读取口 (规格「客户端显示」: 客户端要"同步下来的钟和保质期配置")。
 *
 * 为什么要有这一层: 保质期活在 **SERVER 配置**里, 而专用服务器上客户端读不到那份配置 —— 真去读会直接抛
 * "Cannot get config value before config is loaded" (开发期) 或读到未加载的空值 (生产期)。所以:
 * <ul>
 *   <li><b>服务端</b>: 读配置 (配置就是真源);</li>
 *   <li><b>客户端</b>: 读同步包写进来的那份; 还没收到同步包时退回**代码默认值** (六个类别的出厂保质期),
 *       只影响玩家刚进服那零点几秒的显示, 不会写进任何存档。</li>
 * </ul>
 *
 * 同一个方法服务两边, 于是"客户端算出来的剩余"与"服务端算出来的剩余"用的是同一套算术 —— 差别只在时间源。
 */
public final class FreshnessShelfLives {

    private FreshnessShelfLives() {
    }

    /** 客户端由同步包写入的六类保质期; null = 还没同步过 (服务端也一直是 null, 它读配置)。 */
    private static volatile int[] synced;

    /** 这一类的保质期 (小时)。 */
    public static int hours(FreshnessCategory category) {
        int[] override = synced;
        if (override != null && category.ordinal() < override.length) {
            return override[category.ordinal()];
        }
        return fallbackHours(category);
    }

    /**
     * 没有同步值时用的那一份: 服务端读配置, 客户端读代码默认值。
     *
     * 判 Dist 而不是判"配置是否加载" —— 后者在开发期会抛异常, 而这里需要的正是"客户端永远别碰 SERVER 配置"。
     */
    public static int fallbackHours(FreshnessCategory category) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            return category.defaultShelfLifeHours();
        }
        return FreshnessConfig.shelfLifeHours(category);
    }

    /** 客户端收到同步包时写入 (六个值, 顺序 = {@link FreshnessCategory} 的序)。 */
    public static void applySynced(int[] values) {
        synced = values == null ? null : values.clone();
    }

    /** 断开连接时清掉 (免得把上一个服务器的保质期带进下一个服务器)。 */
    public static void clearSynced() {
        synced = null;
    }

    public static boolean syncedFromServer() {
        return synced != null;
    }

    /** 服务端打包用: 当下配置里的六个值 (顺序 = 枚举序)。 */
    public static int[] currentValues() {
        FreshnessCategory[] all = FreshnessCategory.values();
        int[] values = new int[all.length];
        for (int i = 0; i < all.length; i++) {
            values[i] = fallbackHours(all[i]);
        }
        return values;
    }
}
