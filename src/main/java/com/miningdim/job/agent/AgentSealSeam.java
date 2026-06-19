package com.miningdim.job.agent;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * 特勤封印的 champions-free 接缝 (SpecialAgent_Job_DesignSpec 六章; 模块化 + compileOnly 解耦)。纯逻辑层
 * {@link com.miningdim.job.agent.AgentSystem} 与封印申请发起方 (b 阶段扫描面板服务端处理) 经本接缝调用封印执行,
 * 而真封印实现 ({@code com.miningdim.job.agent.integration.AgentSealHandler}) import top.theillusivec4.champions.*,
 * 故只能在 {@code ModList.isLoaded("champions")} 守卫下 {@link #bind} 注入。
 *
 * Champions 未加载 (dev / 未装) 时接缝未绑定: 封印申请 {@link #requestSeal} 优雅短路返 false (纯逻辑层照常工作,
 * GameTest 不触 Champions); 生命周期清理 {@link #onServerStopping} 空操作。范式对齐
 * {@code com.miningdim.champion.ChampionSpawnSeam} (升格接缝同样 champions-free holder + ModList 守卫 bind/unbind)。
 *
 * 本接缝不持任何 Champions 类型: 封印申请的入参是原版/Forge 类型 ({@link ServerPlayer}/{@link LivingEntity}/String),
 * 真 IChampion 探测在接缝另一侧 (integration 层) 完成。
 */
public final class AgentSealSeam {

    private AgentSealSeam() {
    }

    /** 封印申请回调 (integration 层 bind 真实现; 未绑定 = Champions 未加载)。 */
    @FunctionalInterface
    public interface SealRequest {
        /**
         * 处理一次封印申请。
         *
         * @param agent   申请封印的干员 (服务端玩家)
         * @param target  目标精英实体
         * @param affixId 目标词条全限定注册名
         * @return 是否封印成功 (true = 占槽 + 真改成功; false = 校验/占槽/真改任一失败或 Champions 未加载)
         */
        boolean requestSeal(ServerPlayer agent, LivingEntity target, String affixId);
    }

    /** 服务端停止清理回调 (integration 层 bind 真实现, 清执行侧词条快照; 未绑定 = 空操作)。 */
    @FunctionalInterface
    public interface ServerStopCleanup {
        void onServerStopping();
    }

    private static volatile SealRequest sealRequest;
    private static volatile ServerStopCleanup stopCleanup;

    /**
     * 绑定真封印执行 (仅 {@code ModList.isLoaded("champions")} 守卫下由集成层 bootstrap 调用一次)。
     *
     * @param request 封印申请真实现
     * @param cleanup 服务端停止清执行侧快照真实现
     */
    public static void bind(SealRequest request, ServerStopCleanup cleanup) {
        if (request == null || cleanup == null) {
            throw new IllegalArgumentException("seal request/cleanup must not be null");
        }
        sealRequest = request;
        stopCleanup = cleanup;
    }

    /** 解绑 (服务端停止防跨存档脏引用; 范式对齐 ChampionSpawnSeam.unbind)。 */
    public static void unbind() {
        sealRequest = null;
        stopCleanup = null;
    }

    /** 接缝是否已绑定 (= Champions 已加载且集成层已装配)。 */
    public static boolean isBound() {
        return sealRequest != null;
    }

    /**
     * 经接缝发起封印申请 (b 阶段面板服务端处理调用)。Champions 未加载 (接缝未绑定) 优雅短路返 false。
     *
     * @return 是否封印成功 (Champions 未加载 / 校验失败 / 占槽失败 / 真改失败 均返 false)
     */
    public static boolean requestSeal(ServerPlayer agent, LivingEntity target, String affixId) {
        SealRequest request = sealRequest;
        if (request == null) {
            return false; // Champions 未加载: 优雅短路。
        }
        return request.requestSeal(agent, target, affixId);
    }

    /** 经接缝执行服务端停止清理 (清执行侧词条快照; 未绑定空操作)。 */
    public static void onServerStopping() {
        ServerStopCleanup cleanup = stopCleanup;
        if (cleanup != null) {
            cleanup.onServerStopping();
        }
    }
}
