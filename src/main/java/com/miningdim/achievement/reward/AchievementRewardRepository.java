package com.miningdim.achievement.reward;

import com.miningdim.achievement.tier.AchievementTier;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 待领取奖励与成就点账本的持久化边界 (Achievement_System_DesignSpec 7.2, 表 achievement_reward /
 * achievement_points / achievement_point_ledger)。业务代码只经本接口访问数据库, 当前实现为
 * {@link SqliteAchievementRewardRepository}; 以后改为多子服时新增共享数据库的实现即可。
 *
 * <p>领取要求"标记已领取 + 成就点入账 + 写流水 + 发称号"在同一个事务里 (7.1): 调用方用 {@link #inTransaction}
 * 包住这几步, 称号经 {@code ITitleService#grantInTransaction} 写在回调拿到的同一条连接上。本接口的写方法本身不开事务,
 * 在事务外单独调用时各自自动提交。
 *
 * 全部方法在服务端主线程同步调用。
 */
public interface AchievementRewardRepository {

    /**
     * 写入一条待领取奖励, 按 (玩家, 进度 id) 主键幂等: 已有记录 (无论是否已领取) 时不覆盖, 返回 false。
     * 管理员撤销进度后再次授予, 因此不会重复发奖 (7.2)。
     *
     * @return true 表示本次新插入
     */
    boolean insertReward(UUID player, ResourceLocation advancementId, AchievementTier tier, int points,
                         @Nullable ResourceLocation titleId, long earnedAt);

    /** 一条奖励记录 (含已领取的); 不存在为空。 */
    Optional<AchievementReward> reward(UUID player, ResourceLocation advancementId);

    /** 该玩家全部待领取的奖励, 按获得时间、进度 id 升序。 */
    List<AchievementReward> pending(UUID player);

    /**
     * 把一条待领取奖励标记为已领取。只改 claimed_at 为 NULL 的行, 所以重复领取、领取不存在的奖励都返回 false,
     * 调用方据此回滚并报"已领取"。
     */
    boolean markClaimed(UUID player, ResourceLocation advancementId, long claimedAt);

    /** 当前余额与累计获得; 从未获得过成就点为 {@link PointBalance#ZERO}。 */
    PointBalance points(UUID player);

    /**
     * 入账: 余额与累计获得同时增加 amount。
     *
     * @throws IllegalArgumentException amount 不是正数
     */
    void credit(UUID player, long amount);

    /**
     * 扣点: 余额足够时减少 amount 并返回 true; 不足 (含从未有过成就点) 返回 false 且不改动。累计获得不变。
     *
     * @throws IllegalArgumentException amount 不是正数
     */
    boolean debit(UUID player, long amount);

    /** 追加一条流水 (撤销进度也不收回已领取的奖励, 流水只增不删)。 */
    void appendLedger(UUID player, long delta, LedgerReason reason, @Nullable String ref, long at);

    /**
     * 在一个事务里执行 body, 把事务所在的连接交给它 (供 grantInTransaction 之类需要连接的调用)。已处于外层事务时并入
     * 外层、不提前提交; body 抛出的异常使整个事务回滚后原样抛出。
     */
    <T> T inTransaction(Function<Connection, T> body);

    /**
     * 共享连接上此刻是否正开着事务 (调用方的外层事务)。领取与管理员调整据此拒绝嵌套调用: 并入外层时自己的回滚会被外层吞掉,
     * 提交后的提示也会先于真正落盘发出。
     */
    boolean inOpenTransaction();
}
