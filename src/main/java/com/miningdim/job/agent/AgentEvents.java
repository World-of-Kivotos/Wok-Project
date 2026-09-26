package com.miningdim.job.agent;

import com.miningdim.champion.AffixDef;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 特勤干员模块对外的多播监听接口 (Achievement_System_DesignSpec 9.10, 写法参照
 * {@code MiningServices.registerInstanceResetListener})。成就等只读消费方在 mod 构造期注册进来, 封印申请成功之后
 * 由本模块广播, 特勤模块不引用任何消费方。校验、占槽或真改任一步失败的申请都不广播。
 *
 * <p>监听表随进程存在: 本模块没有任何停服或重置钩子会清它 (封印账本与恢复快照的重置都不碰这里; 生产代码只在
 * mod 构造期注册, 清掉就再也收不到)。异常由消费方自己捕获并记录日志, 本类不替它们吞掉, 与 {@code MiningServices}
 * 的广播一致。
 */
public final class AgentEvents {

    private static final List<SealListener> SEAL_LISTENERS = new CopyOnWriteArrayList<>();

    private AgentEvents() {
    }

    /** 注册一个封印监听器; 可以注册多个, 按注册顺序逐个通知。 */
    public static void addSealListener(SealListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Cannot register null AgentEvents.SealListener");
        }
        SEAL_LISTENERS.add(listener);
    }

    /**
     * 广播一次成功的封印 (主线程)。生产代码里唯一的调用点是集成层 {@code AgentSealHandler#requestSeal} 的成功分支:
     * 已占槽、词条已从精英身上移除、入职标志已置位之后。
     */
    public static void fireSeal(ServerPlayer agent, int star, AffixDef affix, SealCategory category) {
        for (SealListener listener : SEAL_LISTENERS) {
            listener.onSeal(agent, star, affix, category);
        }
    }

    /** 封印监听器。 */
    @FunctionalInterface
    public interface SealListener {

        /**
         * @param agent    申请封印的干员
         * @param star     目标精英的星级
         * @param affix    被封印的词条
         * @param category 该词条的封印类别
         */
        void onSeal(ServerPlayer agent, int star, AffixDef affix, SealCategory category);
    }
}
