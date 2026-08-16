package com.miningdim.job.agent.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.job.agent.AgentLevels;
import com.miningdim.job.agent.SealCategory;
import com.miningdim.job.agent.SealRegistry;
import com.miningdim.job.agent.panel.AgentScanSnapshot;
import com.miningdim.job.agent.panel.AgentScanSnapshotBuilder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 战术扫描快照构建集成层 (SpecialAgent_Job_DesignSpec 五章面板; 已自研脱离 Champions)。读自研冠军数据的词条 ->
 * 过滤可封候选 -> 翻译成 champions-free 的 {@link AgentScanSnapshotBuilder.RawAffix} -> 交纯逻辑构建器做分级
 * 解密 + 可封门裁决。
 *
 * 解密分级 (哪些条目对客户端可见) + 可封门 (类别/星级) 全在纯逻辑 {@link AgentScanSnapshotBuilder} (GameTest 可断言);
 * 本集成层只负责: (1) 探测目标是否本工程盖章精英 (非则不扫); (2) 读词条→品质 Map 并经 {@link AgentAffixClassifier}
 * 过滤出可封候选 + 归类 + 取显示名 lang key; (3) 查 {@link SealRegistry} 活跃账本标注哪些词条当前已封印中。
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
        MiningChampionData champ = MiningChampions.get(target).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return null; // 非本工程盖章精英: 无可扫情报。
        }
        int star = champ.star();
        int level = AgentLevels.agentLevel(agent);
        long nowTick = target.level().getGameTime();

        // 候选表 = 当前装配词条 并入 当前被封印中 (已从 capability 移除, 靠执行侧快照补回, 否则面板永远看不到
        // "封印中"那一行, sealed 与 AFFIX_ALREADY_SEALED 会成为结构性不可达的死字段)。EnumMap 保证遍历序恒等于
        // AffixDef 声明序 (分级解密按"原始顺序前 N 条"裁决, 顺序必须确定)。
        // 不用 EnumMap(Map) 拷贝构造: champ.affixes() 是 Collections.unmodifiableMap 包装 (非 EnumMap 实例),
        // 该构造器对非 EnumMap 来源要求"至少一条映射才能推断键类型", 全部词条已被封印剥空 (仅靠下一行的封印中
        // 快照补全候选表) 时会抛 IllegalArgumentException("Specified map is empty")。改用 class 构造 + putAll 规避。
        EnumMap<AffixDef, AffixQuality> visible = new EnumMap<>(AffixDef.class);
        visible.putAll(champ.affixes());
        visible.putAll(AgentSealExecutor.sealedAffixesOf(target.getUUID()));

        List<AgentScanSnapshotBuilder.RawAffix> raws = new ArrayList<>();
        for (Map.Entry<AffixDef, AffixQuality> entry : visible.entrySet()) {
            AffixDef def = entry.getKey();
            SealCategory category = AgentAffixClassifier.classify(def);
            if (category == null) {
                continue; // 不可封词条 (纯防御): 不进扫描面板候选。
            }
            String affixId = AgentAffixClassifier.affixId(def);
            String displayKey = AgentAffixClassifier.displayKey(def);
            boolean sealed = SealRegistry.isAffixSealed(target.getUUID(), affixId, nowTick);
            raws.add(new AgentScanSnapshotBuilder.RawAffix(affixId, displayKey, category, sealed));
        }

        return AgentScanSnapshotBuilder.build(target.getId(), star, level, raws);
    }
}
