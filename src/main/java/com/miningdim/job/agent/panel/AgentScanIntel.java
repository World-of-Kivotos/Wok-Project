package com.miningdim.job.agent.panel;

import java.util.List;

/**
 * 战术扫描对单个目标的分级数值情报 (SpecialAgent_Job_DesignSpec 第四章探测列 L3-L7 + 五章"只给原始数据")。
 *
 * 与 {@link AgentScanEntry} 的词条行并列: 词条行回答"它身上有什么", 本对象回答"它有多肉 / 多硬 / 多疼 / 多快 /
 * 技能多久放一次"。每个字段绑一个 {@link com.miningdim.job.agent.AgentScanField} 解锁等级, 由
 * {@link AgentScanSnapshotBuilder} 按脉冲当刻的干员等级逐格裁决; 未解锁的格一律是 null —— null 是"这一格加密"的
 * 真值, 不是"它没有这项属性"。数值本身有 0 的合法取值 (无减伤词条的减伤率就是 0), 用 0 表示加密会让玩家把
 * "没解锁"读成"它不防"。
 *
 * 只装原始数值, 不装任何结论 (五章: "怕火/怕狙"由玩家自己研究)。字段口径全部写在各自的 @param 上, 集成层按同一
 * 口径读真值 ({@code AgentScanStatsReader}), 本对象不触 Champions/实体。
 *
 * @param effectiveHp         有效血量总池 (L3; 第四章"有效血量")。= 冠军盖章时的总有效血 (6★+ 即影子血池上限),
 *                            不是当前血 —— 当前血是 L9 实时那一格
 * @param armor               原版护甲属性值 (L4; 第四章"护甲")。原样读 {@code Attributes.ARMOR}
 * @param damageReductionPct  对全部伤害类型生效的比例减伤 (L4; 第四章"减伤%"), 0-0.75。复合装甲按满层上限计 +
 *                            缩小化体型折算, 已过红线 1 的 75% 净减伤帽
 * @param bulletResistancePct 仅对子弹生效的附加抗性 (L5; 第四章"子弹抗性"), 0-1。超高分子 + 重型护甲子弹抗 + 偏斜
 *                            期望闪避三源连乘合成; 与上一项再连乘后整体仍受 75% 帽约束 (帽在受击单点统一施加)
 * @param attackDamage        原版攻击伤害属性值 (L6; 第四章"攻击")
 * @param singleHitPct        近战单击被补足到的玩家最大血量比例 (L6; 第四章"单击"), 0-0.6。只在装配重炮 / 嗜血已激活 /
 *                            穿甲时才有补足, 否则真值 0 (单击伤害即 attackDamage)
 * @param movementSpeed       原版移速属性当前值 (L6; 第四章"移速"), 含高速/超速/自修定身等词条挂的修饰
 * @param mechanics           机制类词条的技能时序 (L7; 第四章"技能机制"), 只列已解密的机制行; 未解锁为 null,
 *                            已解锁但目标没有机制词条是空表
 */
public record AgentScanIntel(
        Double effectiveHp,
        Double armor,
        Double damageReductionPct,
        Double bulletResistancePct,
        Double attackDamage,
        Double singleHitPct,
        Double movementSpeed,
        List<Mechanic> mechanics) {

    /** 全部加密的情报 (只测词条行、不提供数值原料的调用方用; 生产路径恒经集成层给出真原料)。 */
    public static final AgentScanIntel WITHHELD = new AgentScanIntel(null, null, null, null, null, null, null, null);

    public AgentScanIntel {
        if (mechanics != null) {
            mechanics = List.copyOf(mechanics); // 不可变副本: 快照构建后外部改不动。
        }
    }

    /**
     * 一条机制类词条的技能时序。三个子项各自可缺 —— 缺 = 这条技能<b>根本没有</b>这个概念 (如视觉干扰瞬发无蓄力、
     * 小男孩一次性无冷却、自我修复靠近战命中打断而不是伤害阈值), 不是加密: 整组时序已随 L7 一并解锁。
     * 缺项绝不补 0 或编一个数, 0 秒蓄力与"没有蓄力"对玩家是两回事。
     *
     * @param affixId                  词条标识 (与同一目标 entries 里已解密行的 affixId 同口径, 前端按此 join)
     * @param chargeSeconds            起手后玩家可反应的窗口秒数: 蓄力 (电磁/小男孩)、落点预兆 (天雷/凯撒/利刃)、
     *                                 读条 (自我修复)、标记到处决的倒计时 (命定之死); 瞬发技能无此项为 null
     * @param interruptDamagePerPlayer 打断门槛的每人伤害 (蓄力期累计伤害达 到场人数 x 此值即打断); 无则 null
     * @param cooldownSeconds          施放周期 / 冷却秒数; 无则 null
     */
    public record Mechanic(String affixId, Double chargeSeconds, Double interruptDamagePerPlayer,
                           Double cooldownSeconds) {

        public Mechanic {
            if (affixId == null || affixId.isEmpty()) {
                throw new IllegalArgumentException("mechanic affixId must not be null/empty");
            }
        }
    }
}
