package com.miningdim.quest;

/**
 * 任务来源 (四类任务的唯一分类轴)。
 *
 * 四类任务共用同一套 {@link QuestDefinition} / {@link QuestObjective} / {@link QuestProgress} / {@link QuestBoard},
 * 差异<b>只</b>体现在本枚举的两个属性上 —— 何时进池 (periodic) 与能否花信用点重摇 (refreshable)。刻意不做成
 * 四套并行子系统: 那会得到四份周期时钟、四份进度存档与四个发奖出口, 改一次发奖规则要改四遍。
 *
 * 扩展位: 特勤职业的悬赏 (SpecialAgent_Job_DesignSpec 十二章, 当前 DEFERRED) 将来作为第五个来源 BOUNTY 接入
 * —— 它本质就是"周期 + 击杀类目标 + 发奖", 与本枚举已有的 WEEKLY 只差一道职业门。届时新增枚举值并让
 * {@link QuestPool} 按职业等级过滤即可, 无需改动进度/存档/发奖三层。在悬赏数值拍板前不预先添加未启用的枚举
 * 值 (空壳数据比缺失更难排查)。
 */
public enum QuestSource {

    /** 每日任务: UTC 翻日重置, 固定 4 个槽位, 可花信用点单槽重摇。 */
    DAILY(true, true),

    /** 每周任务: ISO 翻周重置, 固定 1 个槽位, 可花信用点重摇。 */
    WEEKLY(true, true),

    /**
     * 特殊任务: 随机事件触发 (如路过村庄), 不占日常/周常槽位, 不可重摇。
     * 无周期戳 —— 它的生命周期由"接取到完成/放弃"界定, 不随翻日翻周清零。
     */
    SPECIAL(false, false),

    /**
     * 隐藏任务: 特定事件解锁的多阶段任务线 (见 {@link QuestChain}), 不可重摇。
     * 同样无周期戳 —— 任务线进度跨日跨周保留, 否则多阶段线永远走不完。
     */
    HIDDEN(false, false);

    private final boolean periodic;
    private final boolean refreshable;

    QuestSource(boolean periodic, boolean refreshable) {
        this.periodic = periodic;
        this.refreshable = refreshable;
    }

    /**
     * 是否受周期戳管辖 (跨戳自动清空并重新发放)。
     *
     * 与 {@link #refreshable} 分开两个字段而非合并: 二者当前取值恰好一致, 但回答的是不同问题 —— 前者决定
     * 服务端翻日/翻周时要不要重发, 后者决定界面上要不要显示"重摇"按钮。合并会让将来出现"周期性但不可重摇"
     * 的来源 (如悬赏) 时被迫拆回来。
     */
    public boolean periodic() {
        return periodic;
    }

    /** 玩家是否可花信用点重摇该来源的任务槽。 */
    public boolean refreshable() {
        return refreshable;
    }
}
