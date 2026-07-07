package com.miningdim.job.agent.integration;

import com.miningdim.job.agent.AgentLevels;
import com.miningdim.job.agent.SealCategory;
import com.miningdim.job.agent.SealRegistry;
import com.miningdim.job.agent.panel.AgentScanSnapshot;
import com.miningdim.job.agent.panel.AgentScanSnapshotBuilder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import top.theillusivec4.champions.api.IAffix;
import top.theillusivec4.champions.api.IChampion;

import java.util.ArrayList;
import java.util.List;

/**
 * 战术扫描快照构建集成层 (SpecialAgent_Job_DesignSpec 五章面板; Champions 触点)。读真精英词条 -> 过滤可封候选 ->
 * 翻译成 champions-free 的 {@link AgentScanSnapshotBuilder.RawAffix} -> 交纯逻辑构建器做分级解密 + 可封门裁决。
 *
 * 解密分级 (哪些条目对客户端可见) + 可封门 (类别/星级) 全在纯逻辑 {@link AgentScanSnapshotBuilder} (GameTest 可断言);
 * 本集成层只负责: (1) 探测目标是否本工程盖章精英 (非则不扫); (2) 读真词条列表并经 {@link AgentAffixClassifier} 过滤
 * 出可封候选 + 归类 + 取显示名 lang key; (3) 查 {@link SealRegistry} 活跃账本标注哪些词条当前已封印中。
 *
 * 词条显示名 lang key: 与封印类别归类同口径, 取词条 identifier 的 "affix.<namespace>.<path>" 约定键 (Champions
 * 词条 lang key 惯例; 客户端 Component.translatable 渲染; 缺资源回显 raw key 不崩)。
 *
 * compileOnly 隔离: 本类 import top.theillusivec4.champions.* —— 仅 ModList 守卫下经 {@link AgentIntegrationBootstrap}
 * 触达 (bind 进 {@code AgentSealSeam}, dev 不加载)。
 */
final class AgentScanProbe {

    private AgentScanProbe() {
    }

    /**
     * 构建某干员对某目标的扫描快照 (经 {@code AgentSealSeam.ScanSnapshotRequest} bind 调用)。目标非本工程盖章精英
     * 返 null (面板侧据此不发包: 普通怪无可扫情报)。
     *
     * @param agent  申请扫描的干员 (提供等级)
     * @param target 目标实体
     * @return 扫描快照; 非本工程精英返 null
     */
    static AgentScanSnapshot buildSnapshot(ServerPlayer agent, LivingEntity target) {
        IChampion champion = AgentChampionData.championOf(target);
        if (champion == null || !AgentChampionData.isOurChampion(champion)) {
            return null; // 非本工程盖章精英: 无可扫情报。
        }
        int star = AgentChampionData.starOf(champion);
        if (star < 1) {
            return null;
        }
        int level = AgentLevels.agentLevel(agent);
        long nowTick = target.level().getGameTime();

        // 读真词条 -> 过滤可封候选 (外来/纯防御词条 classify 返 null, 跳过, 不进面板) -> 翻译为 RawAffix。
        List<AgentScanSnapshotBuilder.RawAffix> raws = new ArrayList<>();
        for (IAffix affix : champion.getServer().getAffixes()) {
            SealCategory category = AgentAffixClassifier.classify(affix);
            if (category == null) {
                continue; // 不可封词条不进扫描面板候选 (面板只承载可封情报)。
            }
            String affixId = AgentAffixClassifier.affixId(affix);
            String displayKey = displayKey(affixId);
            boolean sealed = SealRegistry.isAffixSealed(target.getUUID(), affixId, nowTick);
            raws.add(new AgentScanSnapshotBuilder.RawAffix(affixId, displayKey, category, sealed));
        }

        return AgentScanSnapshotBuilder.build(target.getId(), star, level, raws);
    }

    /**
     * 词条全限定注册名 (namespace:path) -> Champions 词条显示名 lang key (affix.<namespace>.<path> 惯例)。
     * 客户端 translatable 渲染; 缺资源回显 raw key (不崩, 仅显示原 key)。
     */
    private static String displayKey(String affixId) {
        int sep = affixId.indexOf(':');
        if (sep < 0) {
            return "affix." + affixId; // 无命名空间分隔 (异常): 仍给可渲染 key。
        }
        String namespace = affixId.substring(0, sep);
        String path = affixId.substring(sep + 1);
        return "affix." + namespace + "." + path;
    }
}
