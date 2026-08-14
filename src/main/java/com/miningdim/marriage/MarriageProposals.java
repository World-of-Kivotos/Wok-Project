package com.miningdim.marriage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 订婚意向的瞬态登记表 (结婚系统 spec 第二章流程: propose -> accept -> wed)。婚约意向只是"待办典礼"的临时状态,
 * 真正的婚姻关系在典礼 ({@link MarriageEngine#wed}) 才落 {@link MarriageRegistry}(持久); 故意向不持久化 ——
 * 服务端重启即清空 (双方重新 propose 即可), 避免给瞬态意向也做 SavedData 的过度设计 (YAGNI)。
 *
 * 语义: proposer -> target 的单向意向; target 经 accept 把意向标为"已接受"。wed 校验意向存在且已接受。
 * 一名玩家同一时刻只持一条 outgoing 意向 (再 propose 覆盖旧的)。线程: 仅服务端主线程访问 (命令回调主线程)。
 */
public final class MarriageProposals {

    /** 一条婚约意向: 求婚对象 + 对方是否已接受 (proposer 由所在 map 的键持有, 不重复存)。 */
    private static final class Proposal {
        final UUID target;
        boolean accepted;

        Proposal(UUID target) {
            this.target = target;
            this.accepted = false;
        }
    }

    /**
     * proposer UUID -> 其发出的意向 (一人一条 outgoing)。全表是婚约意向的唯一真源, 反查亦从它派生。
     *
     * 用 LinkedHashMap 而非 HashMap: {@link #proposersFor} 的结果直接决定婚姻面板"谁向我求婚"那张列表的显示序,
     * 而该列表在收件人过多时会被面板层按上限截断 —— 顺序不定则每次刷新被截掉的是不同的人。
     */
    private final Map<UUID, Proposal> byProposer = new LinkedHashMap<>();

    /** 登记一条新意向 (proposer 向 target 求婚); 覆盖 proposer 旧的 outgoing 意向。 */
    public void propose(UUID proposer, UUID target) {
        byProposer.put(proposer, new Proposal(target));
    }

    /**
     * target 接受 proposer 的求婚: 把对应意向标为已接受。返回是否找到这样一条 (proposer->target) 待接受意向。
     */
    public boolean accept(UUID proposer, UUID target) {
        Proposal p = byProposer.get(proposer);
        if (p == null || !p.target.equals(target)) {
            return false;
        }
        p.accepted = true;
        return true;
    }

    /** proposer 与 target 之间是否存在一条已被接受的意向 (wed 前置校验)。 */
    public boolean isAccepted(UUID proposer, UUID target) {
        Proposal p = byProposer.get(proposer);
        return p != null && p.target.equals(target) && p.accepted;
    }

    /** proposer 当前 outgoing 意向的对象; 无则 null (命令层提示用)。 */
    public UUID targetOf(UUID proposer) {
        Proposal p = byProposer.get(proposer);
        return p == null ? null : p.target;
    }

    /**
     * 向 target 求过婚的全部 proposer (反查; 婚姻面板"谁向我求婚"与 marriage.wed 自动定位伴侣用)。
     *
     * <b>刻意不建第二张 target -&gt; proposer 的索引表</b>: 那张表必须在三处同步失效 —— {@link #propose} 覆盖旧意向时
     * 从旧目标下摘除、{@link #clear} 时摘除、以及典礼后双方 clear 时摘除 —— 漏掉任何一处, 被求婚者的面板上就会永远
     * 挂着一条早已不存在的求婚, 而这种索引漂移在瞬态表上极难复现。正向表是唯一真源, 从它现扫一遍则结构上不可能分叉。
     *
     * 代价可忽略: 表的规模上限是"当前持有 outgoing 意向的玩家数", 不超过在线人数, 而调用点只有面板刷新与典礼定位
     * (清单 E3 亦明确允许 O(n) 扫描)。
     *
     * @return 求婚方 UUID, 按各自登记进表的先后序; 无人求婚返回空表
     */
    public List<UUID> proposersFor(UUID target) {
        List<UUID> proposers = new ArrayList<>();
        for (Map.Entry<UUID, Proposal> entry : byProposer.entrySet()) {
            if (entry.getValue().target.equals(target)) {
                proposers.add(entry.getKey());
            }
        }
        return proposers;
    }

    /** 清掉 proposer 的 outgoing 意向 (典礼完成/取消后)。 */
    public void clear(UUID proposer) {
        byProposer.remove(proposer);
    }
}
