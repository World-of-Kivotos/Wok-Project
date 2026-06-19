package com.miningdim.job.agent;

/**
 * 探测扫描可解密字段 (SpecialAgent_Job_DesignSpec 五章战术扫描面板 + 第四章探测列逐级解锁)。
 *
 * 每个字段绑定一个"最低解锁等级": 干员等级 >= 该值时本次扫描脉冲可把该字段从"加密/未解析"解密成原始数据
 * 推送到面板 (五章: 只给原始数据不给弱点结论)。逐级解锁结构逐行对齐第四章探测列。
 *
 * 纯枚举, 不触实体; 字段到 IChampion 真数据的读取落在集成层 (compileOnly, ModList 守卫), 本枚举只承载
 * "等级 -> 可见字段集"的解密分级逻辑 (见 {@link AgentScanTier})。
 */
public enum AgentScanField {

    /** 词条名 + 数量 (L1: 1 词条; L2: 2 词条; L3: 3 词条; L4+ 全被动词条; L5+ 全词条)。逐级词条数另由 {@link AgentScanTier#visibleAffixCount} 控。 */
    AFFIX_LIST(1),

    /** 精英星级 (L1 起: 第四章 "1 词条+星级")。 */
    STAR(1),

    /** 有效血量 (总血池; L3 起: 第四章 "3 词条+有效血量")。 */
    EFFECTIVE_HP(3),

    /** 护甲 / 减伤百分比 (L4 起: 第四章 "全被动词条+护甲/减伤%")。 */
    ARMOR_DR_PERCENT(4),

    /** 技能名 (L5 起: 第四章 "全词条(含技能名)")。 */
    SKILL_NAME(5),

    /** 子弹抗性 (L5 起: 第四章 "+子弹抗性")。 */
    BULLET_RESISTANCE(5),

    /** 攻击 / 单击伤害 + 移速 (L6 起: 第四章 "+攻击/单击+移速")。 */
    ATTACK_AND_SPEED(6),

    /** 悬赏雷达标注 (L6 起: 第四章 "+悬赏雷达"; 面板顶部标是否当前悬赏目标)。 */
    BOUNTY_RADAR(6),

    /** 技能机制 (蓄力/打断阈值/CD; L7 起: 第四章 "+技能机制")。 */
    SKILL_MECHANICS(7),

    /** 全品质表 (每词条品质色标全解; L8 起: 第四章 "全品质表")。 */
    QUALITY_TABLE(8),

    /** Glowing 高亮 (穿墙可见; L8 起: 第四章 "Glowing高亮", 走原版发光)。 */
    GLOWING_HIGHLIGHT(8),

    /** 全数值实时刷新 (血/盾/层/CD 实时; L9 起: 第四章 "全数值实时")。 */
    REALTIME_NUMBERS(9),

    /** 全属性实时 (L10: 第四章 "全属性实时 · 跨区块")。 */
    REALTIME_ALL_ATTRIBUTES(10);

    private final int unlockLevel;

    AgentScanField(int unlockLevel) {
        this.unlockLevel = unlockLevel;
    }

    /** 本字段的最低解锁等级 (干员等级 >= 此值才可解密)。 */
    public int unlockLevel() {
        return unlockLevel;
    }
}
