package com.miningdim.job.agent.client;

import com.miningdim.job.agent.panel.AgentScanSnapshot;

/**
 * 客户端战术扫描快照镜像 (SpecialAgent_Job_DesignSpec 五章; 范式对齐 {@link com.miningdim.job.ClientJobState} /
 * {@code network.ClientDangerState})。仅客户端逻辑端加载 (经 DistExecutor 隔离, 见
 * {@link com.miningdim.job.agent.network.AgentScanSyncS2C#handle}); 专用服务器永不触本类。
 *
 * 服务端权威: 本类只缓存 S2C 推来的最新扫描快照供面板 Screen 读取渲染, 绝不自算解密分级。网络主线程写、客户端
 * 渲染线程读 (单写多读, volatile 引用整体替换保证可见性)。
 */
public final class ClientAgentScanState {

    private ClientAgentScanState() {
    }

    /** 最近一次扫描快照 (null = 尚未收到任何扫描; 面板打开前可能为 null, Screen 须空判)。 */
    private static volatile AgentScanSnapshot latest;

    /** 由 {@link com.miningdim.job.agent.network.AgentScanSyncS2C} 客户端 handler 在客户端主线程调用: 整体替换镜像。 */
    public static void accept(AgentScanSnapshot snapshot) {
        latest = snapshot;
    }

    /** 当前面板应渲染的扫描快照 (可能为 null; 面板 Screen 须空判后渲染空态)。 */
    public static AgentScanSnapshot snapshot() {
        return latest;
    }

    /** 关闭面板 / 切存档时清镜像 (防跨次扫描脏渲染)。 */
    public static void clear() {
        latest = null;
    }
}
