package com.miningdim.job.agent.panel;

import com.miningdim.job.agent.AgentScanField;
import com.miningdim.job.agent.AgentScanTier;
import com.miningdim.job.agent.SealCategory;
import com.miningdim.job.agent.SealPlan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * 未解密条目脱敏 (F081): affixId 与 displayKey 一并置空串, 不是只脱 displayKey 留 affixId 明码。真名本来就
 * 不该进入"要下行的对象" —— 脱敏做在本构建层, 任何下行路径 (今天的 WebUI JSON, 将来任何重新接上的通道)
 * 共享同一份已脱敏数据, 而不是各自在序列化处再补一遍 (序列化层各自补脱敏等于把同一道门开在 N 处, 少补一处
 * 就漏)。{@link AgentScanEntry} 的紧凑构造器只拒 null, 空串是合法的脱敏取值。
 *
 * 纯逻辑, 不触 Champions/实体: 入参 {@link RawAffix} 是 champions-free 描述 (集成层从真 IAffix 翻译, GameTest 喂
 * 合成描述), 故解密分级 + 可封门 逻辑可在 dev GameTest 直断言 (删本类逻辑必挂)。
 *
 * 数值情报分级 (第四章探测列 L3-L10): 词条行之外的每一格 (有效血 / 护甲减伤 / 子弹抗性 / 攻击单击移速 / 技能时序 /
 * 品质 / 实时数值 / 实时属性) 同样在本类按 {@link AgentScanField} 的解锁等级逐格裁决 —— 集成层 ({@link RawStats} /
 * {@link RawLive}) 只管把真值读全, 哪一格发、哪一格打成 null 一律在这里定。把分级散到集成层或 JSON 层, 就等于
 * 把同一道门开在两处, 迟早一处漏改 (与未解密条目脱敏下沉到本层是同一条理由)。
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
     * @param quality    词条品质 ({@code AffixQuality} 枚举名); null = 原料方不提供品质 (只测词条行的调用方),
     *                   此时无论等级多高品质格都发 null
     */
    public record RawAffix(String affixId, String displayKey, SealCategory category, boolean sealed, String quality) {

        public RawAffix {
            if (affixId == null || displayKey == null || category == null) {
                throw new IllegalArgumentException("affixId/displayKey/category must not be null");
            }
        }

        /** 不带品质的原料 (只关心解密/可封裁决的调用方用)。 */
        public RawAffix(String affixId, String displayKey, SealCategory category, boolean sealed) {
            this(affixId, displayKey, category, sealed, null);
        }
    }

    /**
     * 集成层读出的某精英全部数值原料 (champions-free; 字段口径逐一对应 {@link AgentScanIntel} 的同名字段, 见那里的
     * 注释)。这里一律是真值, 分级由 {@link #build} 裁决。
     *
     * @param mechanics 该精英全部机制类词条的技能时序 (含封印中的); 构建器只保留已解密行对应的那几条
     */
    public record RawStats(double effectiveHp, double armor, double damageReductionPct, double bulletResistancePct,
                           double attackDamage, double singleHitPct, double movementSpeed,
                           List<AgentScanIntel.Mechanic> mechanics) {

        public RawStats {
            if (mechanics == null) {
                throw new IllegalArgumentException("mechanics must not be null (use empty list)");
            }
            mechanics = List.copyOf(mechanics);
        }
    }

    /**
     * 集成层对一个仍在快照内的目标重读出的实时原料。目标读不到 (死亡 / 离场 / 卸载 / 不再是精英) 时集成层返回
     * null 而不是本对象, 构建器据此给出 {@link AgentScanLive#lost()}。
     *
     * @param attributes 与快照同口径的数值现值 (mechanics 忽略; 技能时序是按品质查表的定值, 不随时间变)
     */
    public record RawLive(double currentHp, double maxHp, double absorption, int frostStacksOnYou,
                          int burningStacksOnYou, RawStats attributes) {

        public RawLive {
            if (attributes == null) {
                throw new IllegalArgumentException("attributes must not be null");
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
     * @return 不可变扫描快照; 未解密条目的 affixId/displayKey 已在此脱敏为空串 (见类注释 F081); 数值情报全部加密
     *         ({@link AgentScanIntel#WITHHELD}, 本重载不收数值原料, 只供只测词条行裁决的调用方)
     */
    public static AgentScanSnapshot build(int targetNetworkId, int star, int agentLevel, List<RawAffix> rawAffixes) {
        return build(targetNetworkId, star, agentLevel, rawAffixes, null);
    }

    /**
     * 装配某干员等级对某精英的完整扫描快照: 词条行逐条裁决 (同上一重载) + 品质格 (L8) + 数值情报逐格裁决 (L3-L7)。
     *
     * @param rawStats 该精英的数值原料 (集成层读真值); null = 不提供, 数值情报全部加密
     * @return 不可变扫描快照
     */
    public static AgentScanSnapshot build(int targetNetworkId, int star, int agentLevel, List<RawAffix> rawAffixes,
                                          RawStats rawStats) {
        if (rawAffixes == null) {
            throw new IllegalArgumentException("rawAffixes must not be null (use empty list for no affixes)");
        }
        int visibleCount = AgentScanTier.visibleAffixCount(agentLevel); // L1-L3=N条; L4+= -1 哨兵 (全词条按类别)。
        boolean showsAllPassive = AgentScanTier.showsAllPassiveAffixes(agentLevel); // L4+
        boolean showsSkill = AgentScanTier.showsSkillAffixes(agentLevel);           // L5+
        boolean showsQuality = AgentScanTier.canDecrypt(agentLevel, AgentScanField.QUALITY_TABLE); // L8+

        List<AgentScanEntry> entries = new ArrayList<>(rawAffixes.size());
        for (int i = 0; i < rawAffixes.size(); i++) {
            RawAffix raw = rawAffixes.get(i);
            boolean decrypted = isDecrypted(i, raw.category(), visibleCount, showsAllPassive, showsSkill);
            // 可封: 仅已解密 + 当前未被封印中的条目才经 SealPlan 三门 (类别/星级门); 未解密恒不可封。已封印中的
            // 条目若仍标 sealable=true, 玩家点下去只会拿到 AFFIX_NOT_SEALABLE ("不可封印") 而非更准确的
            // "已被封印中" —— sealed 必须先短路 sealable, 否则面板会给出误导性的可点提示 (F024 复核发现)。
            boolean sealable = decrypted && !raw.sealed() && SealPlan.canSeal(agentLevel, star, raw.category());
            entries.add(new AgentScanEntry(
                    decrypted ? raw.affixId() : "", // 未解密不泄漏真名: affixId 与 displayKey 同口径脱敏。
                    decrypted ? raw.displayKey() : "", // 未解密不泄漏真名 (客户端显示加密占位)。
                    raw.category(),
                    decrypted,
                    sealable,
                    raw.sealed(),
                    // 品质是 L8 "全品质表"那一格, 且只跟已解密行走: 加密行连是哪条都不知道, 谈不上它的品质。
                    decrypted && showsQuality ? raw.quality() : null));
        }
        AgentScanIntel intel = rawStats == null ? AgentScanIntel.WITHHELD : tierIntel(agentLevel, rawStats, entries);
        return new AgentScanSnapshot(targetNetworkId, star, agentLevel, entries, intel);
    }

    /**
     * 数值情报逐格裁决 (第四章探测列): L3 有效血 / L4 护甲+减伤 / L5 子弹抗性 / L6 攻击+单击+移速 / L7 技能时序。
     * 未解锁格一律 null。技能时序另加一道行级门: 只保留在本快照里已解密的机制行对应的那几条 —— 今天 L7 时机制行
     * 恒已解密 (L5 起), 这道门是防将来有人调低 SKILL_MECHANICS 或调高机制解密等级时, 时序表把加密行的身份
     * (affixId) 从旁路漏出去。
     */
    private static AgentScanIntel tierIntel(int agentLevel, RawStats raw, List<AgentScanEntry> entries) {
        boolean hp = AgentScanTier.canDecrypt(agentLevel, AgentScanField.EFFECTIVE_HP);
        boolean armor = AgentScanTier.canDecrypt(agentLevel, AgentScanField.ARMOR_DR_PERCENT);
        boolean bullet = AgentScanTier.canDecrypt(agentLevel, AgentScanField.BULLET_RESISTANCE);
        boolean attack = AgentScanTier.canDecrypt(agentLevel, AgentScanField.ATTACK_AND_SPEED);
        boolean mechanics = AgentScanTier.canDecrypt(agentLevel, AgentScanField.SKILL_MECHANICS);

        List<AgentScanIntel.Mechanic> visibleMechanics = null;
        if (mechanics) {
            Set<String> decryptedMechanicIds = new HashSet<>();
            for (AgentScanEntry entry : entries) {
                if (entry.decrypted() && entry.category() == SealCategory.MECHANIC) {
                    decryptedMechanicIds.add(entry.affixId());
                }
            }
            visibleMechanics = new ArrayList<>();
            for (AgentScanIntel.Mechanic mechanic : raw.mechanics()) {
                if (decryptedMechanicIds.contains(mechanic.affixId())) {
                    visibleMechanics.add(mechanic);
                }
            }
        }
        return new AgentScanIntel(
                hp ? raw.effectiveHp() : null,
                armor ? raw.armor() : null,
                armor ? raw.damageReductionPct() : null,
                bullet ? raw.bulletResistancePct() : null,
                attack ? raw.attackDamage() : null,
                attack ? raw.singleHitPct() : null,
                attack ? raw.movementSpeed() : null,
                visibleMechanics);
    }

    /**
     * 实时透视裁决 (第四章 L9 "全数值实时" / L10 "全属性实时")。
     *
     * @param agentLevel 发出这次脉冲时的干员等级 (与快照同一判据: 脉冲之后才升级不会让旧快照凭空多出实时格)
     * @param raw        集成层重读的实时原料; null = 目标已读不到
     * @return L9 以下 null (整格加密); 目标读不到时 {@link AgentScanLive#lost()}; 否则 L9 数值 + L10 起带属性现值
     */
    public static AgentScanLive buildLive(int agentLevel, RawLive raw) {
        if (!refreshesLive(agentLevel)) {
            return null;
        }
        if (raw == null) {
            return AgentScanLive.lost();
        }
        AgentScanLive.Attributes attributes = null;
        if (AgentScanTier.canDecrypt(agentLevel, AgentScanField.REALTIME_ALL_ATTRIBUTES)) {
            RawStats now = raw.attributes();
            attributes = new AgentScanLive.Attributes(now.effectiveHp(), now.armor(), now.damageReductionPct(),
                    now.bulletResistancePct(), now.attackDamage(), now.singleHitPct(), now.movementSpeed());
        }
        return new AgentScanLive(true, raw.currentHp(), raw.maxHp(), raw.absorption(),
                raw.frostStacksOnYou(), raw.burningStacksOnYou(), attributes);
    }

    /** 该等级发出的脉冲是否带实时透视 (L9+; job.agent.state 据此决定要不要为快照目标重读活数值)。 */
    public static boolean refreshesLive(int agentLevel) {
        return AgentScanTier.canDecrypt(agentLevel, AgentScanField.REALTIME_NUMBERS);
    }

    /** 该等级发出的脉冲是否给扫描干员本人高亮全部目标 (L8+; 第四章"Glowing 高亮")。 */
    public static boolean highlightsTargets(int agentLevel) {
        return AgentScanTier.canDecrypt(agentLevel, AgentScanField.GLOWING_HIGHLIGHT);
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
