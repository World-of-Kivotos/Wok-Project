package com.miningdim.job.brewer;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 酿酒师模块对外的多播监听接口 (Achievement_System_DesignSpec 9.10, 写法参照
 * {@code MiningServices.registerInstanceResetListener})。成就等只读消费方在 mod 构造期注册进来, 酿酒台出酒之后由
 * 本模块广播, 酿酒师模块不引用任何消费方。
 *
 * <p>只有在线操作者那一支才广播: 操作者离线时酒照常产出, 但与经验一样没有可归属的玩家, 不广播。
 *
 * <p>监听表随进程存在: 本模块没有任何停服或重置钩子会清它 (生产代码只在 mod 构造期注册, 清掉就再也收不到)。
 * 异常由消费方自己捕获并记录日志, 本类不替它们吞掉, 与 {@code MiningServices} 的广播一致。
 */
public final class BrewerEvents {

    private static final List<BrewListener> BREW_LISTENERS = new CopyOnWriteArrayList<>();

    private BrewerEvents() {
    }

    /** 注册一个酿成监听器; 可以注册多个, 按注册顺序逐个通知。 */
    public static void addBrewListener(BrewListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Cannot register null BrewerEvents.BrewListener");
        }
        BREW_LISTENERS.add(listener);
    }

    /**
     * 广播一次酿成 (主线程)。生产代码里唯一的调用点是酿酒台结算里给在线操作者记经验的那一支, 位于酒已进输出槽、
     * 经验已入账之后。
     */
    public static void fireBrew(ServerPlayer brewer, WineType type, WineQuality quality) {
        for (BrewListener listener : BREW_LISTENERS) {
            listener.onBrew(brewer, type, quality);
        }
    }

    /** 酿酒台酿成监听器。 */
    @FunctionalInterface
    public interface BrewListener {

        /**
         * @param brewer  在线的操作者 (谁酿谁得)
         * @param type    酿出的酒类型
         * @param quality 本轮开始时锁定的品质
         */
        void onBrew(ServerPlayer brewer, WineType type, WineQuality quality);
    }
}
