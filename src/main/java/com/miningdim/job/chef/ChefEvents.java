package com.miningdim.job.chef;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 厨师模块对外的多播监听接口 (Achievement_System_DesignSpec 9.10, 写法参照
 * {@code MiningServices.registerInstanceResetListener})。成就等只读消费方在 mod 构造期注册进来, 调味台出菜之后由
 * 本模块广播, 厨师模块不引用任何消费方。
 *
 * <p>监听表随进程存在: 本模块没有任何停服或重置钩子会清它 (生产代码只在 mod 构造期注册, 清掉就再也收不到)。
 * 异常由消费方自己捕获并记录日志, 本类不替它们吞掉, 与 {@code MiningServices} 的广播一致。
 */
public final class ChefEvents {

    private static final List<DishListener> DISH_LISTENERS = new CopyOnWriteArrayList<>();

    private ChefEvents() {
    }

    /** 注册一个出菜监听器; 可以注册多个, 按注册顺序逐个通知。 */
    public static void addDishListener(DishListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Cannot register null ChefEvents.DishListener");
        }
        DISH_LISTENERS.add(listener);
    }

    /**
     * 广播一道完成的菜 (主线程)。生产代码里唯一的调用点是 {@link SeasoningTableBlockEntity} 的结算末尾: 成品已交给
     * 厨师、调料已扣、经验已入账之后。
     */
    public static void fireDish(ServerPlayer chef, ChefQuality quality, ChefQuality target) {
        for (DishListener listener : DISH_LISTENERS) {
            listener.onDish(chef, quality, target);
        }
    }

    /** 调味台出菜监听器。 */
    @FunctionalInterface
    public interface DishListener {

        /**
         * @param chef    操作小游戏并完成出菜的厨师 (谁做谁得)
         * @param quality 成品的达成品质
         * @param target  开工时选定的挑战目标品质
         */
        void onDish(ServerPlayer chef, ChefQuality quality, ChefQuality target);
    }
}
