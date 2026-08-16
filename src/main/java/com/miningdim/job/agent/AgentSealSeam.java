package com.miningdim.job.agent;

import com.miningdim.job.agent.panel.AgentScanSnapshot;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * 特勤封印 + 战术扫描的 champions-free 接缝 (SpecialAgent_Job_DesignSpec 五章面板 + 六章封印; 模块化解耦)。纯逻辑层
 * {@link com.miningdim.job.agent.AgentSystem} 与面板网络层 (扫描快照构建 / 封印申请发起) 经本接缝调用集成层执行,
 * 而真实现 ({@code com.miningdim.job.agent.integration.AgentSealHandler}/{@code AgentScanProbe}) 落在
 * {@code com.miningdim.job.agent.integration} 包, 经 {@link #bind} 注入。
 *
 * 本接缝现在的职责是装配期解耦 + 给 GameTest 留装桩点: 接缝未绑定 (集成层尚未装配, 即启动早期) 时, 封印申请
 * {@link #requestSealResult} 优雅短路返 {@link SealOutcome#NOT_BOUND}; 扫描快照构建 {@link #buildScanSnapshot} 返
 * null; 生命周期清理 {@link #onServerStopping} 空操作 (纯逻辑层照常工作, GameTest 可自由 unbind 测短路分支再
 * 重新 bind 装回真实现)。{@link #NOT_BOUND} 常量保留 —— 它是 job.agent.seal 九态回执的前端契约的一部分
 * (见 webui/src/lib/types.ts 的 outcomeCode 词表), 删枚举等于破坏契约, 语义改为"集成层尚未装配 (启动早期) 或
 * 测试桩未绑定"。
 *
 * 本接缝不持任何第三方 mod 类型: 入参是原版/Forge 类型 ({@link ServerPlayer}/{@link LivingEntity}/String), 出参是
 * champions-free 的 {@link SealOutcome}/{@link AgentScanSnapshot}; 真探测 (自研 MiningChampions capability) 在
 * 接缝另一侧 (integration 层) 完成。
 */
public final class AgentSealSeam {

    private AgentSealSeam() {
    }

    /**
     * 封印申请的接缝级结果 (champions-free; 聚合 {@code AgentSealHandler.FailReason} 全分支 + OK + NOT_BOUND)。
     * 网络层据此回执玩家 (五章面板失败原因提示)。集成层把 {@code AgentSealHandler.Result} 翻译为本枚举回传。
     */
    public enum SealOutcome {
        /** 封印成功 (占槽 + 真改均成功)。 */
        OK,
        /** 集成层尚未装配 (启动早期) 或测试桩未绑定 (扫描离线; 优雅降级)。 */
        NOT_BOUND,
        /** 目标非本工程盖章精英 / 不存在。 */
        NO_TARGET,
        /** 该词条不可封 (外来词条 / 纯防御词条 / 列表无此词条 / 真改未生效)。 */
        AFFIX_NOT_SEALABLE,
        /** 本类别封印未解锁 (被动 L&lt;3 / 机制 L&lt;8)。 */
        CATEGORY_LOCKED,
        /** 目标星级超过干员可封星级。 */
        STAR_TOO_HIGH,
        /** 全部封印槽已被占。 */
        ALL_SLOTS_OCCUPIED,
        /** 该词条已被封印中 (互斥)。 */
        AFFIX_ALREADY_SEALED,
        /** 该干员该词条类别仍在封印 CD 内 (六章封印 CD 强制点)。 */
        ON_COOLDOWN
    }

    /** 封印申请回调 (integration 层 bind 真实现; 未绑定 = 集成层尚未装配 / 测试桩)。 */
    @FunctionalInterface
    public interface SealResultRequest {
        /**
         * 处理一次封印申请 (集成层内聚合干员资格/分级/星级/类别/槽位全门 + 真改, 返聚合结果)。
         *
         * @param agent   申请封印的干员 (服务端玩家)
         * @param target  目标精英实体
         * @param affixId 目标词条全限定注册名
         * @return 接缝级封印结果 (OK / 各失败原因; 绝不返 NOT_BOUND, 那由接缝未绑定路径产生)
         */
        SealOutcome requestSeal(ServerPlayer agent, LivingEntity target, String affixId);
    }

    /** 扫描快照构建回调 (integration 层 bind 真实现; 未绑定 = 集成层尚未装配, 接缝返 null)。 */
    @FunctionalInterface
    public interface ScanSnapshotRequest {
        /**
         * 对某目标精英按申请干员等级构建分级解密扫描快照 (五章: 探测脉冲产出推面板的快照)。
         *
         * @param agent  申请扫描的干员 (服务端玩家; 提供等级)
         * @param target 目标精英实体
         * @return 扫描快照 (目标非本工程精英 / 无可封候选词条时集成层仍可返带空条目的快照或 null; null = 无法扫描)
         */
        AgentScanSnapshot buildSnapshot(ServerPlayer agent, LivingEntity target);
    }

    /** 服务端停止清理回调 (integration 层 bind 真实现, 清执行侧词条快照; 未绑定 = 空操作)。 */
    @FunctionalInterface
    public interface ServerStopCleanup {
        void onServerStopping();
    }

    private static volatile SealResultRequest sealRequest;
    private static volatile ScanSnapshotRequest scanRequest;
    private static volatile ServerStopCleanup stopCleanup;

    /**
     * 绑定真封印 + 扫描执行 (由集成层 bootstrap 装配期调用一次; GameTest 亦可 unbind 后重新调用装回真实现)。
     *
     * @param request  封印申请真实现 (聚合结果)
     * @param scan     扫描快照构建真实现
     * @param cleanup  服务端停止清执行侧快照真实现
     */
    public static void bind(SealResultRequest request, ScanSnapshotRequest scan, ServerStopCleanup cleanup) {
        if (request == null || scan == null || cleanup == null) {
            throw new IllegalArgumentException("seal request / scan request / cleanup must not be null");
        }
        sealRequest = request;
        scanRequest = scan;
        stopCleanup = cleanup;
    }

    /** 解绑 (服务端停止防跨存档脏引用; 范式对齐 ChampionSpawnSeam.unbind)。 */
    public static void unbind() {
        sealRequest = null;
        scanRequest = null;
        stopCleanup = null;
    }

    /** 接缝是否已绑定 (= 集成层已装配)。 */
    public static boolean isBound() {
        return sealRequest != null;
    }

    /**
     * 经接缝发起封印申请 (五章面板点已解密词条的服务端处理调用)。集成层尚未装配 (启动早期) / 测试桩未绑定时
     * 优雅短路返 {@link SealOutcome#NOT_BOUND}。
     *
     * @return 接缝级封印结果 (OK / 各失败原因 / NOT_BOUND)
     */
    public static SealOutcome requestSealResult(ServerPlayer agent, LivingEntity target, String affixId) {
        SealResultRequest request = sealRequest;
        if (request == null) {
            return SealOutcome.NOT_BOUND; // 集成层尚未装配: 优雅短路。
        }
        return request.requestSeal(agent, target, affixId);
    }

    /**
     * 经接缝构建扫描快照 (五章探测脉冲服务端处理调用)。集成层尚未装配 (接缝未绑定) 返 null (调用方据此不发包)。
     *
     * @return 扫描快照; 未绑定 / 无法扫描时返 null
     */
    public static AgentScanSnapshot buildScanSnapshot(ServerPlayer agent, LivingEntity target) {
        ScanSnapshotRequest request = scanRequest;
        if (request == null) {
            return null; // 集成层尚未装配: 无法读精英词条, 不构建快照。
        }
        return request.buildSnapshot(agent, target);
    }

    /** 经接缝执行服务端停止清理 (清执行侧词条快照; 未绑定空操作)。 */
    public static void onServerStopping() {
        ServerStopCleanup cleanup = stopCleanup;
        if (cleanup != null) {
            cleanup.onServerStopping();
        }
    }
}
