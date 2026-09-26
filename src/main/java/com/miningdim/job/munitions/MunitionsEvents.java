package com.miningdim.job.munitions;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 军火商模块对外的多播监听接口 (Achievement_System_DesignSpec 9.10, 写法参照
 * {@code MiningServices.registerInstanceResetListener})。成就等只读消费方在 mod 构造期注册进来, 军火台每落账一次
 * 产弹经验就由本模块广播一次, 军火商模块不引用任何消费方。
 *
 * <p>军火台有两个经验落账点, 都广播: 被动挂机结算 (一次结算可能追算出多批, 只广播一次, 带合计发数) 与手动批次
 * 完成。工费扣不动、缺料、缺电而作废的批次不落账, 也不广播。
 *
 * <p>监听表随进程存在: 本模块没有任何停服或重置钩子会清它 (生产代码只在 mod 构造期注册, 清掉就再也收不到)。
 * 异常由消费方自己捕获并记录日志, 本类不替它们吞掉, 与 {@code MiningServices} 的广播一致。
 */
public final class MunitionsEvents {

    private static final List<BatchListener> BATCH_LISTENERS = new CopyOnWriteArrayList<>();

    private MunitionsEvents() {
    }

    /** 注册一个产弹监听器; 可以注册多个, 按注册顺序逐个通知。 */
    public static void addBatchListener(BatchListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Cannot register null MunitionsEvents.BatchListener");
        }
        BATCH_LISTENERS.add(listener);
    }

    /**
     * 广播一次产弹落账 (主线程)。生产代码里的两个调用点都在 {@code MunitionsBenchBlockEntity}: 被动结算与手动
     * 批次完成, 各自位于扣料、扣费、入缓冲与经验入账之后。
     */
    public static void fireBatch(ServerPlayer owner, MunitionsCaliber caliber, int rounds) {
        for (BatchListener listener : BATCH_LISTENERS) {
            listener.onBatch(owner, caliber, rounds);
        }
    }

    /** 军火台产弹监听器。 */
    @FunctionalInterface
    public interface BatchListener {

        /**
         * @param owner   台主 (谁产谁得)
         * @param caliber 本次产出的口径
         * @param rounds  本次落账的发数 (大于 0)
         */
        void onBatch(ServerPlayer owner, MunitionsCaliber caliber, int rounds);
    }
}
