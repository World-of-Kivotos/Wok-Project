package com.miningdim.job.agent;

import java.util.EnumSet;
import java.util.Set;

/**
 * 探测扫描分级解密纯逻辑 (SpecialAgent_Job_DesignSpec 五章 + 第四章探测列): 干员等级 -> 本次脉冲可解密的字段集
 * + 可见词条数。逐级把精英数据"逐格解密"; 未解锁字段在面板显示"加密/未解析"。
 *
 * 纯逻辑, 不触实体: 字段集只决定"哪些字段允许推送", 真值读取 (IChampion.getData / getAffixes) 落在集成层
 * (compileOnly, ModList 守卫)。GameTest 直断言等级 -> 字段集 + 词条数, 删解密分级必挂。
 *
 * 可见词条数曲线 (第四章探测词条列): L1=1 / L2=2 / L3=3 / L4=全被动词条 / L5+=全词条。本层无法在 dev 知道
 * 某精英"全被动/全词条"的真实条数 (那要读真 IChampion), 故对 L4+ 返回 {@link #ALL_AFFIXES} 哨兵, 集成层据此
 * 推送对应子集 (L4 = 全被动, L5+ = 含技能名的全部)。L1-L3 返回精确条数上限。
 */
public final class AgentScanTier {

    private AgentScanTier() {
    }

    /** 可见词条数哨兵: L4+ 不限具体条数 (推全被动/全部, 真实条数由集成层读 IChampion 决定)。 */
    public static final int ALL_AFFIXES = -1;

    /** L4 起推全被动词条的起始等级 (第四章: L4 "全被动词条")。 */
    public static final int ALL_PASSIVE_AFFIXES_LEVEL = 4;

    /** L5 起推含技能名的全部词条的起始等级 (第四章: L5 "全词条(含技能名)")。 */
    public static final int ALL_AFFIXES_INCLUDING_SKILL_LEVEL = 5;

    /**
     * 某等级本次脉冲可解密的字段集 (按 {@link AgentScanField#unlockLevel} 过滤)。等级越高字段越多。
     *
     * @param level 干员等级 (内部经 {@link AgentSkillTable#clampLevel} 夹 [1,10])
     * @return 不可变语义的可见字段集 (返回新 EnumSet, 调用方不污染本逻辑)
     */
    public static Set<AgentScanField> visibleFields(int level) {
        int lv = AgentSkillTable.clampLevel(level);
        EnumSet<AgentScanField> out = EnumSet.noneOf(AgentScanField.class);
        for (AgentScanField field : AgentScanField.values()) {
            if (lv >= field.unlockLevel()) {
                out.add(field);
            }
        }
        return out;
    }

    /** 某等级某字段是否已解密。 */
    public static boolean canDecrypt(int level, AgentScanField field) {
        return AgentSkillTable.clampLevel(level) >= field.unlockLevel();
    }

    /**
     * 某等级本次脉冲可见的词条数 (第四章探测词条列): L1=1 / L2=2 / L3=3 / L4+={@link #ALL_AFFIXES} 哨兵。
     *
     * @param level 干员等级
     * @return 精确条数 (L1-L3) 或 {@link #ALL_AFFIXES} (L4+, 集成层按 L4=全被动 / L5+=全部 推送)
     */
    public static int visibleAffixCount(int level) {
        int lv = AgentSkillTable.clampLevel(level);
        if (lv >= ALL_PASSIVE_AFFIXES_LEVEL) {
            return ALL_AFFIXES;
        }
        return lv; // L1=1 / L2=2 / L3=3 条。
    }

    /** 某等级是否推全被动词条 (L4+) 而非精确前 N 条 (L1-L3)。 */
    public static boolean showsAllPassiveAffixes(int level) {
        return AgentSkillTable.clampLevel(level) >= ALL_PASSIVE_AFFIXES_LEVEL;
    }

    /** 某等级是否连技能名一起推 (L5+, 即全部词条含技能; 第四章 L5 "全词条(含技能名)")。 */
    public static boolean showsSkillAffixes(int level) {
        return AgentSkillTable.clampLevel(level) >= ALL_AFFIXES_INCLUDING_SKILL_LEVEL;
    }
}
