package com.miningdim.core;

/**
 * 静默授予原版进度的线程局部开关 (Achievement_System_DesignSpec 9.9 上线追溯)。
 *
 * <p>在 {@link #run} 里完成的进度既不做全服聊天公告, 也不在客户端弹 Toast, 其余一切照常: 原版的授予流程、
 * 奖励函数、Forge 的 AdvancementEarnEvent (成就模块据此生成待领取奖励) 都不受影响。两处抑制都在
 * {@code mixin.PlayerAdvancementsSilenceMixin} 里, 依据是 1.20.1 的原版代码路径 (javap 核实):
 * <ul>
 *   <li>公告: {@code PlayerAdvancements#award} 在进度刚完成、显示信息要求公告且游戏规则 announceAdvancements 为真时
 *       调 {@code PlayerList#broadcastSystemMessage}; 开关打开时跳过这一次调用。</li>
 *   <li>Toast: 客户端只在收到<b>非重置</b>的 {@code ClientboundUpdateAdvancementsPacket} 且其中某个进度已完成、
 *       显示信息要求 Toast 时才弹 ({@code ClientAdvancements#update})。重置包 (玩家登录后的第一个包) 从不弹。
 *       登录事件里授予的进度本来就落在第一个包里; 若玩家的第一个包已经发过, 开关打开期间有进度完成时, 下一次
 *       {@code flushDirty} 改发一个完整的重置包, 与登录时的第一个包内容相同。</li>
 * </ul>
 * 代价: 重置包同一 tick 里别的 (非静默的) 完成也不再弹 Toast, 公告照常。只在登录这类低频时刻使用。
 *
 * <p>开关按线程计深度, 可以嵌套; 期间由授予连带触发的授予 (比如成就数量达标) 同样静默。只在服务端主线程上用。
 */
public final class AdvancementSilence {

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private AdvancementSilence() {
    }

    /** 在静默状态下执行 body; body 抛出也会恢复原来的状态。 */
    public static void run(Runnable body) {
        int[] depth = DEPTH.get();
        depth[0]++;
        try {
            body.run();
        } finally {
            depth[0]--;
        }
    }

    /** 当前线程是否处在静默状态。 */
    public static boolean active() {
        return DEPTH.get()[0] > 0;
    }
}
