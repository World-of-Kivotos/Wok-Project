package com.miningdim.job.agent.panel;

/**
 * 战术扫描的实时透视 (SpecialAgent_Job_DesignSpec 五章"实时透视 L9+" + 第四章 L9 "全数值实时" / L10 "全属性实时")。
 *
 * 快照 ({@link AgentScanSnapshot}) 是脉冲当刻冻结的一份情报; 本对象是 {@code job.agent.state} 每次被读时对<b>仍在
 * 有效快照内</b>的目标重读一遍的活数值。防 X 光的边界由它的构造方 ({@link AgentScanSnapshotBuilder#buildLive}) 与
 * 调用方共同守住: 只刷已在快照里的目标 (不补扫新目标)、只刷数值不刷坐标 (坐标冻结在脉冲当刻, 见
 * {@code AgentWebUiActions.ScanTarget})、快照一过期就不再有任何实时数据。
 *
 * {@code tracked=false} 表示目标已死亡 / 离场 / 所在区块已卸载 / 已不是精英, 此时其余字段全是 null —— 读不到的东西
 * 不发旧值, 否则面板会把一只已经死掉的精英继续画成满血。
 *
 * @param tracked            目标此刻是否仍可读 (已加载 + 活着 + 仍是同一只盖章精英)
 * @param currentHp          当前血量 (6★+ 读影子血池权威值, 1-5★ 读原版血量; 与贡献/拦死同一权威)
 * @param maxHp              当前血量的上限 (与 currentHp 同一权威源, 用于画血条)
 * @param absorption         原版伤害吸收量 (即"护盾"一格; 精英体系本身没有独立护盾池, 这里只如实读原版吸收)
 * @param frostStacksOnYou   本精英寒霜此刻叠在扫描干员身上的层数 (0-5)
 * @param burningStacksOnYou 本精英燃烧此刻叠在扫描干员身上的层数 (0-5)
 * @param attributes         L10 全属性实时: 与快照 L3-L6 数值格同口径的活值; L9 为 null (加密)
 */
public record AgentScanLive(
        boolean tracked,
        Double currentHp,
        Double maxHp,
        Double absorption,
        Integer frostStacksOnYou,
        Integer burningStacksOnYou,
        Attributes attributes) {

    /** 目标已丢失 (死亡 / 离场 / 卸载 / 不再是精英): 只报一个 tracked=false, 其余一律不发。 */
    public static AgentScanLive lost() {
        return new AgentScanLive(false, null, null, null, null, null, null);
    }

    /**
     * L10 实时属性。口径逐字对齐 {@link AgentScanIntel} 的同名字段 (同一个读取器读出), 区别只在于快照那份是脉冲
     * 当刻的定格, 这份是每次读 state 时的现值 —— 例如超速词条在加速段/力竭段之间切换时移速会跟着变。
     */
    public record Attributes(
            double effectiveHp,
            double armor,
            double damageReductionPct,
            double bulletResistancePct,
            double attackDamage,
            double singleHitPct,
            double movementSpeed) {
    }
}
