package com.miningdim.job.agent.panel;

import java.util.List;

/**
 * 战术扫描面板整体快照 (SpecialAgent_Job_DesignSpec 五章): 服务端一次扫描脉冲对某精英的分级解密结果, 经 S2C
 * 推给客户端面板。头部 (目标实体网络 id + 星级 + 干员等级) + 词条条目列表 ({@link AgentScanEntry})。
 *
 * 纯数据值对象, 不触 Champions/实体: 服务端经集成层读真精英后由 {@link AgentScanSnapshotBuilder} 装配, 客户端只
 * 渲染本快照 (服务端权威, 客户端不自算解密分级)。
 *
 * targetNetworkId 用原版 {@code Entity.getId()} (int 网络 id, 同维度同 tick 稳定), 供客户端面板回点封印时回传,
 * 服务端经 {@code ServerLevel.getEntity(int)} 复原目标 (而非传 UUID 占额外 16 字节 + 客户端无现成 UUID->实体反查)。
 *
 * @param targetNetworkId 目标精英的原版网络 id (Entity.getId(); 封印回点 C2S 据此回传)
 * @param star            目标精英初始星级 (1-10; 面板顶部显示 + 客户端只读, 服务端再校验)
 * @param agentLevel      构建本快照时的干员等级 (面板顶部显示 + 决定哪些条目解密; 服务端权威)
 * @param entries         词条条目列表 (按精英词条原始顺序; 已解密在前由集成层决定, 本 record 不重排)
 */
public record AgentScanSnapshot(
        int targetNetworkId,
        int star,
        int agentLevel,
        List<AgentScanEntry> entries) {

    public AgentScanSnapshot {
        if (entries == null) {
            throw new IllegalArgumentException("entries must not be null (use empty list for no affixes)");
        }
        entries = List.copyOf(entries); // 不可变副本: 防构建后外部改, 网络编码读到稳定快照。
    }
}
