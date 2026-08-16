package com.miningdim.quest;

/**
 * 单条已发放任务的进度 (计数 + 是否已领奖)。纯逻辑, 无世界引用, 不取实时时钟。
 *
 * 刻意不持周期戳: 每日/每周翻转时任务是<b>整条重抽</b>而非把旧任务计数清零 (玩家每天应当看到新任务), 周期
 * 判定因此归 {@link QuestBoard} 统一管, 本类只管一条任务从 0 数到达标。
 *
 * 达标后计数封顶不再增长 —— 既防溢出, 也让"3/3"这类展示不会跳成"7/3"。
 */
public final class QuestProgress {

    private final QuestDefinition definition;
    private int count;
    private boolean claimed;

    public QuestProgress(QuestDefinition definition) {
        this(definition, 0, false);
    }

    /**
     * 从存档重建。
     *
     * @param count   已累计计数 (越界值按 [0, requiredCount] 夹取: 存档可能来自定义调整前的旧版本, 夹取比
     *                拒绝加载整块存档更合适 —— 后者会让玩家丢掉全部任务进度)
     * @param claimed 是否已领过奖 (防重复发奖的唯一依据)
     */
    public QuestProgress(QuestDefinition definition, int count, boolean claimed) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        this.definition = definition;
        this.count = Math.max(0, Math.min(count, definition.objective().requiredCount()));
        this.claimed = claimed;
    }

    public QuestDefinition definition() {
        return definition;
    }

    public int count() {
        return count;
    }

    public int requiredCount() {
        return definition.objective().requiredCount();
    }

    public boolean claimed() {
        return claimed;
    }

    /**
     * 把一次事实计入本条任务。
     *
     * @return 计数是否真的变了 (调用方据此决定要不要给玩家发进度提示、要不要标脏存档)
     */
    public boolean record(QuestFacts facts) {
        if (isComplete()) {
            return false;
        }
        int delta = definition.objective().match(facts);
        if (delta <= 0) {
            return false;
        }
        count = Math.min(count + delta, requiredCount());
        return true;
    }

    public boolean isComplete() {
        return count >= requiredCount();
    }

    /**
     * 尝试领奖: 已达标且未领过则标记已领并返回 true (调用方据此真正发钱), 否则返回 false 且不发。
     *
     * 标记与发钱分离在两层: 本类只保证"一条任务的领奖标记至多翻一次", 真实发钱在 {@link QuestRewards}。
     */
    public boolean tryClaim() {
        if (!isComplete() || claimed) {
            return false;
        }
        claimed = true;
        return true;
    }
}
