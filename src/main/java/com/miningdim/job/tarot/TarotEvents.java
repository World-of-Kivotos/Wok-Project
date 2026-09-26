package com.miningdim.job.tarot;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 塔罗师模块对外的多播监听接口 (Achievement_System_DesignSpec 9.10, 写法参照
 * {@code MiningServices.registerInstanceResetListener})。成就等只读消费方在 mod 构造期注册进来, 一张牌揭牌结算之后
 * 由本模块广播, 塔罗师模块不引用任何消费方。
 *
 * <p>只广播真正打出去的牌: 演出被打断而退牌的不算; 测试模式 ({@link TarotConfig#TEST_MODE}) 下打牌不扣牌、不给
 * 经验, 同样不广播。
 *
 * <p>监听表随进程存在: 本模块停服时清演出队列、冷却与运行期门面 ({@link TarotRuntime#reset}), 但不清它
 * (生产代码只在 mod 构造期注册, 清掉就再也收不到)。
 * 异常由消费方自己捕获并记录日志, 本类不替它们吞掉, 与 {@code MiningServices} 的广播一致。
 */
public final class TarotEvents {

    private static final List<PlayListener> PLAY_LISTENERS = new CopyOnWriteArrayList<>();

    private TarotEvents() {
    }

    /** 注册一个出牌监听器; 可以注册多个, 按注册顺序逐个通知。 */
    public static void addPlayListener(PlayListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Cannot register null TarotEvents.PlayListener");
        }
        PLAY_LISTENERS.add(listener);
    }

    /**
     * 广播一次出牌 (主线程)。生产代码里唯一的调用点是 {@link TarotPlayHandler#tryPlay} 排进演出队列的揭牌回调:
     * 牌效已应用、出牌经验已入账之后。
     */
    public static void firePlay(ServerPlayer player, int cardId, TarotQuality quality) {
        for (PlayListener listener : PLAY_LISTENERS) {
            listener.onPlay(player, cardId, quality);
        }
    }

    /** 出牌监听器。 */
    @FunctionalInterface
    public interface PlayListener {

        /**
         * @param player  打出这张牌的塔罗师
         * @param cardId  大阿卡纳编号 0~21 ({@link TarotArcana#cardId()})
         * @param quality 这张牌的品质
         */
        void onPlay(ServerPlayer player, int cardId, TarotQuality quality);
    }
}
