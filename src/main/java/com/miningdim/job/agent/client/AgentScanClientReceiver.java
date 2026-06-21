package com.miningdim.job.agent.client;

import com.miningdim.job.agent.panel.AgentScanSnapshot;
import net.minecraft.client.Minecraft;

/**
 * 战术扫描快照 S2C 的客户端接收器 (仅客户端逻辑端加载; 经 {@link com.miningdim.job.agent.network.AgentScanSyncS2C
 * #handle} 的 DistExecutor.unsafeRunWhenOn 隔离调用, 专用服务器永不触本类)。
 *
 * 单列客户端接收类 (而非内联进 S2C handle) 以保证客户端类 (Minecraft / Screen) 引用集中本客户端包, 专用服务器
 * 永不触类 (范式对齐 {@code WineCellarClient} 单列客户端入口)。
 *
 * 职责: 写入 {@link ClientAgentScanState} 镜像 + 若当前打开的是扫描面板则刷新其条目缓存 (服务端推新快照即时反映)。
 */
public final class AgentScanClientReceiver {

    private AgentScanClientReceiver() {
    }

    /** 在客户端主线程写镜像并刷新打开中的面板 (经 S2C handle 的 unsafeRunWhenOn 调用)。 */
    public static void accept(AgentScanSnapshot snapshot) {
        ClientAgentScanState.accept(snapshot);
        if (Minecraft.getInstance().screen instanceof AgentScanScreen screen) {
            screen.onSnapshotUpdated();
        }
    }
}
