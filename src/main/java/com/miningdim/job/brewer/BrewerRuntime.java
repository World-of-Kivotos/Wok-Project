package com.miningdim.job.brewer;

/**
 * 酿酒师运行期单例服务持有 (本包内静态访问点; 范式照 {@link com.miningdim.job.tarot.TarotRuntime})。喝酒固化
 * 永久层 ({@link BrewEffectEngine#applyOnDrink}) 与酿酒台发茅台加成经验 (station 包) 需取金酒 maxHP 管理器 /
 * 永久特殊施加器, 但这些是有状态运行期对象, 不宜每处 new。
 *
 * 由 {@link BrewerSystem} 在 register 内 {@link #init} 一次性装配 (服务端单进程), 本包/子包类经 getter 取用;
 * 未 init 即取用抛 IllegalStateException (装配缺陷暴露, 异常纪律)。{@link #reset} 在 ServerStopping 清引用。
 */
public final class BrewerRuntime {

    private BrewerRuntime() {
    }

    private static GinMaxHealthManager ginMaxHealth;
    private static BrewPermanentBuffs permanentBuffs;

    static void init(GinMaxHealthManager ginMaxHealth, BrewPermanentBuffs permanentBuffs) {
        BrewerRuntime.ginMaxHealth = ginMaxHealth;
        BrewerRuntime.permanentBuffs = permanentBuffs;
    }

    static void reset() {
        ginMaxHealth = null;
        permanentBuffs = null;
    }

    /** 是否已装配 (供测试/极端时序短路, 不抛)。 */
    public static boolean isReady() {
        return permanentBuffs != null;
    }

    public static GinMaxHealthManager ginMaxHealth() {
        return require(ginMaxHealth, "GinMaxHealthManager");
    }

    public static BrewPermanentBuffs permanentBuffs() {
        return require(permanentBuffs, "BrewPermanentBuffs");
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalStateException("BrewerRuntime." + name
                    + " not initialized (BrewerSystem.register must run first)");
        }
        return value;
    }
}
