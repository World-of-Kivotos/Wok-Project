package com.miningdim.error;

/**
 * 玩家可见提示文案的 translation key 常量集 (设计文档第二十章 20.2 / 第十八章 18.x)。
 *
 * 为什么集中在一处: 第二十章规定所有拒绝/失败经网络下发"可本地化文案"且"不暴露堆栈" (20.1)。
 * 各子系统在最外层兜底 (命令 / 网络 handler / 进入 Gateway) 调用 {@link MiningErrors} 下发这些 key,
 * 客户端按 key 本地化。把 key 收口成常量, 杜绝各子系统手写字符串字面量漂移 (CLAUDE.md 多维搜索之痛)。
 *
 * 命名空间恒为 {@code miningdim.msg.*} (20.1)。本类不含任何逻辑, 纯常量。
 */
public final class MiningMessages {

    private MiningMessages() {
    }

    // ---- 第二十章 20.2 逐场景文案 ----

    /** 连通性修复后重试/降级 (静默或调试可见); 对应 gen_retry。 */
    public static final String GEN_RETRY = "miningdim.msg.gen_retry";

    /** 实例池满 / 并发上限: 拒绝或排队 (排队时附带位次参数)。 */
    public static final String INSTANCES_FULL = "miningdim.msg.instances_full";

    /** 区块生成中, 进入流程等待提示。 */
    public static final String PREPARING = "miningdim.msg.preparing";

    /** 进入等待超时, 已退还费用。 */
    public static final String ENTER_TIMEOUT = "miningdim.msg.enter_timeout";

    /** 重置时实例内仍有玩家, 被送回进入前坐标。 */
    public static final String RESET_EVICT = "miningdim.msg.reset_evict";

    /** 矿山维度未正确加载 (数据包/配置错误, 不可恢复)。 */
    public static final String DIMENSION_MISSING = "miningdim.msg.dimension_missing";

    // ---- 第十八章 18.x 反滥用闸门拒绝文案 ----
    // 设计文档第十八章规定"闸门拒绝行为必须有明确玩家可见文案 (见第二十章)";第二十章表未逐条列举闸门 key,
    // 故按 18.2-18.6 各闸门补齐对应 key, 仍归 miningdim.msg.* 命名空间, 与 20.1 文案规范一致。

    /** 实例重置仍在冷却中 (18.2 reset.cooldownTicks)。 */
    public static final String RESET_COOLDOWN = "miningdim.msg.reset_cooldown";

    /** 实例当日重置次数已达上限 (18.2 reset.dailyLimitPerInstance)。 */
    public static final String RESET_DAILY_LIMIT = "miningdim.msg.reset_daily_limit";

    /** 重置成本不足, 扣费失败 (18.2 reset.costItem/costAmount)。 */
    public static final String RESET_COST_UNPAID = "miningdim.msg.reset_cost_unpaid";

    /** 高价矿物当日产出已达软上限, 收购价递减 (18.3 economy.daily.*)。 */
    public static final String ECONOMY_SOFTCAP = "miningdim.msg.economy_softcap";

    /** 死亡惩罚: 已被送回进入前坐标并进入再入冷却 (18.6 death.*)。 */
    public static final String DEATH_PENALTY = "miningdim.msg.death_penalty";

    /** 死亡再入冷却未结束, 暂不能再次进入 (18.6 death.reentryCooldownTicks)。 */
    public static final String DEATH_REENTRY_COOLDOWN = "miningdim.msg.death_reentry_cooldown";
}
