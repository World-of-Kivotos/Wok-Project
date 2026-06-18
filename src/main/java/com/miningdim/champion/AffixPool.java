package com.miningdim.champion;

/**
 * 四池分类 (ChampionStarAffix spec 第一章/第七章: 生存 / 战斗 / 机动 / 技能)。
 *
 * 关键架构纪律 (任务红队核实): 这四池是本工程自建的"点数预算"概念, 不映射 Champions 真枚举
 * AffixCategory (CC/OFFENSE/DEFENSE 三类)。四池分配器/互斥/软上限全落在本工程 spawn 期分配器 +
 * 玩家侧 capability; 与 Champions 三分类物理分离。本枚举只承载"该池每星总点数预算"的取值口径
 * (具体每星点数表见 {@link StarRank}) 与"技能池仅供技能不转基础膨胀"的语义标记。
 *
 * 纯数据枚举, 无世界引用。
 */
public enum AffixPool {

    /** 生存池 (被动防御): 减伤/血量/回血; 剩余点 → 基础有效血/减伤膨胀。 */
    SURVIVAL(true),

    /** 战斗池 (被动攻击修正): 伤害/DoT/攻速; 剩余点 → 基础伤害膨胀。 */
    COMBAT(true),

    /** 机动池 (被动自身位移): 移速/传送; 剩余点 → 基础移速膨胀。 */
    MOBILITY(true),

    /**
     * 技能池 (主动有 CD 须预兆, 占技能数上限): spec 第四章明确"技能池仅供技能不转膨胀"。
     * 故 {@link #convertsRemainderToBaseStats()} 为 false —— 技能池剩余点数作废, 不换基础膨胀。
     */
    SKILL(false);

    private final boolean convertsRemainder;

    AffixPool(boolean convertsRemainder) {
        this.convertsRemainder = convertsRemainder;
    }

    /**
     * 该池买完词条后的剩余点数是否换算成基础属性膨胀 (spec 第四章)。
     * 生存/战斗/机动 = true (剩余 → 血/伤/移速); 技能池 = false (剩余作废, 防技能池膨胀)。
     */
    public boolean convertsRemainderToBaseStats() {
        return convertsRemainder;
    }
}
