package com.miningdim.quest;

/**
 * 单个玩家在一条任务线上的位置 (第几阶段 + 该阶段的进度)。
 *
 * 推进规则: 只有<b>领完奖</b>才进入下一阶段, 而不是达标即推进。理由是奖励发放是任务线上唯一的经济副作用,
 * 把"推进"绑在"发奖成功"之后, 才能保证服务端崩在中间时不会出现"阶段已推进但奖没发"的不可逆状态 —— 重进
 * 世界后玩家仍停在旧阶段, 重领即可。
 */
public final class QuestChainState {

    private final QuestChain chain;
    private int stageIndex;
    private QuestProgress current;

    public QuestChainState(QuestChain chain) {
        this(chain, 0, null);
    }

    /**
     * 从存档重建。
     *
     * @param stageIndex 当前阶段序号; 等于 {@code chain.stageCount()} 表示整条线已完成
     * @param restored   当前阶段的进度; 传 null 表示按该阶段从零开始 (存档里没有这一段, 或阶段刚推进)
     */
    public QuestChainState(QuestChain chain, int stageIndex, QuestProgress restored) {
        if (chain == null) {
            throw new IllegalArgumentException("chain must not be null");
        }
        if (stageIndex < 0 || stageIndex > chain.stageCount()) {
            throw new IllegalArgumentException("stageIndex out of range for chain " + chain.id() + ": " + stageIndex);
        }
        this.chain = chain;
        this.stageIndex = stageIndex;
        this.current = finished() ? null : (restored != null ? restored : new QuestProgress(chain.stages().get(stageIndex)));
    }

    public QuestChain chain() {
        return chain;
    }

    public int stageIndex() {
        return stageIndex;
    }

    /** 整条线是否已走完 (走完后 {@link #current} 为 null)。 */
    public boolean finished() {
        return stageIndex >= chain.stageCount();
    }

    /** 当前阶段进度; 整条线走完后返回 null。 */
    public QuestProgress current() {
        return current;
    }

    /**
     * 领完当前阶段的奖后推进到下一阶段。
     *
     * 调用方必须先确认 {@code current().tryClaim()} 返回了 true 并且钱已发出 —— 见类注释的顺序理由。
     *
     * @return 是否推进成功 (整条线已完成时返回 false)
     */
    public boolean advance() {
        if (finished()) {
            return false;
        }
        stageIndex++;
        current = finished() ? null : new QuestProgress(chain.stages().get(stageIndex));
        return true;
    }
}
