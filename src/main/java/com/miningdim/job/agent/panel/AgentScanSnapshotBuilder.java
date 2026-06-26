package com.miningdim.job.agent.panel;

import com.miningdim.job.agent.AgentScanTier;
import com.miningdim.job.agent.SealCategory;
import com.miningdim.job.agent.SealPlan;

import java.util.ArrayList;
import java.util.List;

/**
 * 战术扫描快照构建纯逻辑 (SpecialAgent_Job_DesignSpec 五章面板 + 第四章探测词条列): 给定干员等级 + 目标星级 +
 * 一组原始词条描述, 逐条裁决 (a) 是否解密 (b) 是否可封, 装配成 {@link AgentScanSnapshot}。
 *
 * 解密分级 (五章只给原始数据): 词条数曲线由 {@link AgentScanTier#visibleAffixCount} 控 ——
 *  - L1-L3: 仅前 1/2/3 条解密 (其余加密占位);
 *  - L4+: 全被动词条解密, 机制类词条 L5+ 才解密 (L4 只全被动, L5 起含机制/技能)。
 * 逐条解密判定: 词条原始顺序内的前 N 条 (N=visibleAffixCount) 总解密; 超出 N 的条目对 L4+ 按类别解密
 * (被动总解密 / 机制需 L5+), 对 L1-L3 一律加密。
 *
 * 可封判定: 解密条目再经 {@link SealPlan#canSeal} 三门 (类别解锁 / 星级门; 槽位占用门不在静态判定, 那依赖活跃
 * 账本由服务端发包前另查并写入 sealed)。未解密条目 sealable 恒 false (未解密不可点封)。
 *
 * 纯逻辑, 不触 Champions/实体: 入参 {@link RawAffix} 是 champions-free 描述 (集成层从真 IAffix 翻译, GameTest 喂
 * 合成描述), 故解密分级 + 可封门 逻辑可在 dev GameTest 直断言 (删本类逻辑必挂)。
 */
public final class AgentScanSnapshotBuilder {

    private AgentScanSnapshotBuilder() {
    }

    /**
     * 集成层翻译真 IAffix 后的 champions-free 词条原料 (供构建器消费; 集成层一条真 IAffix 对应一条本描述)。
     *
     * @param affixId    词条全限定注册名 (namespace:path)
     * @param displayKey 词条显示名 lang key
     * @param category   封印类别 (被动/机制)
     * @param sealed     该词条当前是否已被封印中 (集成层先查 {@link com.miningdim.job.agent.SealRegistry} 活跃账本)
     */
    public record RawAffix(String affixId, String displayKey, SealCategory category, boolean sealed) {

        public RawAffix {
            if (affixId == null || displayKey == null || category == null) {
                throw new IllegalArgumentException("affixId/displayKey/category must not be null");
            }
        }
    }

    /**
     * 装配某干员等级对某精英的扫描快照。逐条按原始顺序裁决解密 + 可封, 头部带目标网络 id / 星级 / 干员等级。
     *
     * @param targetNetworkId 目标精英网络 id (Entity.getId())
     * @param star            目标精英初始星级 (1-10)
     * @param agentLevel      干员等级 (内部经 clampLevel 夹 [1,10])
     * @param rawAffixes      目标精英全部可封候选词条 (集成层已过滤掉不可封/外来词条; 按精英词条原始顺序)
     * @return 不可变扫描快照
     */
    public static AgentScanSnapshot build(int targetNetworkId, int star, int agentLevel, List<RawAffix> rawAffixes) {
        if (rawAffixes == null) {
            throw new IllegalArgumentException("rawAffixes must not be null (use empty list for no affixes)");
        }
        int visibleCount = AgentScanTier.visibleAffixCount(agentLevel); // L1-L3=N条; L4+= -1 哨兵 (全词条按类别)。
        boolean showsAllPassive = AgentScanTier.showsAllPassiveAffixes(agentLevel); // L4+
        boolean showsSkill = AgentScanTier.showsSkillAffixes(agentLevel);           // L5+

        List<AgentScanEntry> entries = new ArrayList<>(rawAffixes.size());
        for (int i = 0; i < rawAffixes.size(); i++) {
            RawAffix raw = rawAffixes.get(i);
            boolean decrypted = isDecrypted(i, raw.category(), visibleCount, showsAllPassive, showsSkill);
            // 可封: 仅已解密条目经 SealPlan 三门 (类别/星级门); 未解密恒不可封。
            boolean sealable = decrypted && SealPlan.canSeal(agentLevel, star, raw.category());
            entries.add(new AgentScanEntry(
                    raw.affixId(),
                    decrypted ? raw.displayKey() : "", // 未解密不泄漏真名 (客户端显示加密占位)。
                    raw.category(),
                    decrypted,
                    sealable,
                    raw.sealed()));
        }
        return new AgentScanSnapshot(targetNetworkId, star, agentLevel, entries);
    }

    /**
     * 逐条解密裁决 (第四章探测词条列): 原始顺序前 N 条 (N=visibleCount, L1-L3) 解密, 但机制类真名恒需 L5+
     * (showsSkill) —— 即便机制词条落在前 N 位, L1-L3 也不解密其真名 (脱敏占位), 否则机制类核弹技能的真名会因
     * 原始顺序靠前而提前泄漏 (agent-03: 机制类应 L5+ 才解密)。超出 N 或 L4+ (visibleCount 为哨兵) 时按类别 ——
     * 被动需 L4+ (showsAllPassive), 机制需 L5+ (showsSkill)。
     *
     * @param index           词条在原始顺序中的下标 (0-based)
     * @param category        词条类别
     * @param visibleCount    {@link AgentScanTier#visibleAffixCount} 返回值 (L1-L3 精确条数; L4+ = ALL_AFFIXES 哨兵)
     * @param showsAllPassive L4+ 是否全被动解密
     * @param showsSkill      L5+ 是否机制/技能解密
     */
    private static boolean isDecrypted(int index, SealCategory category, int visibleCount,
                                       boolean showsAllPassive, boolean showsSkill) {
        if (visibleCount != AgentScanTier.ALL_AFFIXES) {
            // L1-L3: 前 visibleCount 条解密, 但机制类真名恒需 L5+ (此区段 showsSkill 必为 false), 故机制类在前 N 位
            // 仍加密 (类别门控不被原始顺序击穿)。
            if (category == SealCategory.MECHANIC) {
                return showsSkill;
            }
            return index < visibleCount;
        }
        // L4+: 哨兵态, 按类别全解密。被动需 L4+; 机制需 L5+。
        return switch (category) {
            case PASSIVE -> showsAllPassive;
            case MECHANIC -> showsSkill;
        };
    }
}
