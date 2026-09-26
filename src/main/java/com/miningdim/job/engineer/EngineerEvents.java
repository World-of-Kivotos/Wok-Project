package com.miningdim.job.engineer;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 铸甲师 (工程师) 模块对外的多播监听接口 (Achievement_System_DesignSpec 9.10, 写法参照
 * {@code MiningServices.registerInstanceResetListener})。成就等只读消费方在 mod 构造期注册进来, 生产台结算出板之后
 * 由本模块广播, 铸甲师模块不引用任何消费方。闪耀档失败 (0 板) 不广播。
 *
 * <p>广播点是出板, 不是取板: 生产经验要等生产者手取输出时才结算, 与本广播分开。
 *
 * <p>监听表随进程存在: 本模块没有任何停服或重置钩子会清它 (生产代码只在 mod 构造期注册, 清掉就再也收不到)。
 * 异常由消费方自己捕获并记录日志, 本类不替它们吞掉, 与 {@code MiningServices} 的广播一致。
 */
public final class EngineerEvents {

    private static final List<PlateListener> PLATE_LISTENERS = new CopyOnWriteArrayList<>();

    private EngineerEvents() {
    }

    /** 注册一个出板监听器; 可以注册多个, 按注册顺序逐个通知。 */
    public static void addPlateListener(PlateListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Cannot register null EngineerEvents.PlateListener");
        }
        PLATE_LISTENERS.add(listener);
    }

    /**
     * 广播一次出板 (主线程)。生产代码里唯一的调用点是 {@code ProductionTableBlockEntity#finishProduction} 的末尾:
     * 矿石已扣、护甲板已放进输出槽之后, 且只在产出大于 0 时。
     */
    public static void firePlates(ServerPlayer engineer, NanoTier tier, int plates) {
        for (PlateListener listener : PLATE_LISTENERS) {
            listener.onPlates(engineer, tier, plates);
        }
    }

    /** 生产台出板监听器。 */
    @FunctionalInterface
    public interface PlateListener {

        /**
         * @param engineer 完成校准的生产者
         * @param tier     护甲板档位
         * @param plates   本轮产出的板数 (大于 0)
         */
        void onPlates(ServerPlayer engineer, NanoTier tier, int plates);
    }
}
