package com.miningdim.achievement.reward;

import com.miningdim.achievement.AchievementServices;
import com.miningdim.achievement.AchievementStoreException;
import com.miningdim.achievement.meta.AchievementCatalog;
import com.miningdim.achievement.meta.AchievementMeta;
import com.miningdim.store.MiningStoreException;
import com.miningdim.title.GrantResult;
import com.miningdim.title.ITitleService;
import com.miningdim.title.TitleServices;
import com.miningdim.title.TitleSource;
import com.miningdim.title.store.TitleStoreException;
import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 待领取奖励与成就点 (Achievement_System_DesignSpec 第七章、第十一章) 的业务入口; 命令与以后的 G 面板都调这里,
 * 不直接碰仓库。
 *
 * <ul>
 *   <li>获得成就 ({@link #recordEarned}): 只处理会产生奖励的进度 (7.1: miningdim 命名空间、非配方, 点数 &gt; 0 或附带
 *       称号), 页签根与缺元数据的进度什么都不做。按 (玩家, 进度 id) 主键写一条待领取奖励, 点数、档位与称号取获得那一刻的
 *       查询快照; 撤销后再次授予被主键挡下, 不重复生成, 也不再提示。只有新写入时才给玩家本人发带 [领取] 的聊天提示。</li>
 *   <li>领取 ({@link #claim} / {@link #claimAll}): 一次领取 (单条或全部) 是 MiningStore 上的<b>一个</b>事务: 逐条标记已领取,
 *       点数 &gt; 0 时入账 (余额与累计获得) 并写一条 claim 流水, 附带称号经 {@code ITitleService#grantInTransaction}
 *       写在同一条连接上。任何一步失败 (含称号发不出去) 整个事务回滚, 一条也不发; 提交之后才给新得到的称号补发
 *       "获得称号"提示。玩家原本就拥有的称号 (ALREADY_OWNED) 不算失败。</li>
 *   <li>管理员调整 ({@link #addPoints} / {@link #removePoints}): 各自一个事务, 流水 reason 记 admin、ref 记执行者;
 *       扣点最多扣到 0。</li>
 * </ul>
 * 撤销进度不收回已领取的奖励, 也不删流水 (7.2)。全部方法在服务端主线程调用。领取与管理员调整在共享连接上已开着
 * (调用方的) 事务时直接抛 {@link IllegalStateException}, 与称号模块写穿操作的口径相同: 并入外层事务时, 领取自己的回滚
 * 会被外层吞掉 (前面几条的标记、入账与称号随外层提交, 结果却报"已全部撤销"), 提交后的提示也会先于真正落盘发出。
 */
public final class AchievementRewardService {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/achievement");
    private static final Logger ADMIN_LOGGER = LoggerFactory.getLogger("miningdim/achievement/admin");

    private AchievementRewardService() {
    }

    /**
     * 获得进度时记录待领取奖励 (由 {@link AchievementRewardSystem} 在 AdvancementEarnEvent 上调用)。不产生奖励的进度、
     * 撤销后再授予 (主键已有记录) 什么都不做; 写库失败只记错误日志, 不能让异常冒进原版的授予流程。
     */
    public static void recordEarned(ServerPlayer player, Advancement advancement) {
        ResourceLocation id = advancement.getId();
        AchievementCatalog catalog = AchievementServices.catalog();
        if (!catalog.isRewarding(id)) {
            return;
        }
        // isRewarding 成立意味着有元数据且不是页签根 (页签根 0 点、无称号), 档位一定存在。
        AchievementMeta meta = catalog.meta(id).orElseThrow();
        UUID uuid = player.getUUID();
        AchievementReward reward = new AchievementReward(uuid, id, meta.tier(), meta.points(), meta.title(),
                System.currentTimeMillis(), null);
        boolean inserted;
        try {
            inserted = AchievementServices.rewards().insertReward(uuid, id, reward.tier(), reward.points(),
                    reward.titleId(), reward.earnedAt());
        } catch (AchievementStoreException failure) {
            LOGGER.error("[miningdim] failed to record achievement reward {} for {} ({}): tier {}, {} point(s), "
                            + "title {}; the player has to be compensated by hand", id,
                    player.getGameProfile().getName(), uuid, reward.tier().id(), reward.points(), reward.titleId(),
                    failure);
            return;
        }
        if (inserted) {
            player.sendSystemMessage(AchievementRewardText.earnedNotice(player.server, reward));
        }
    }

    /**
     * 领取一条奖励。
     *
     * @throws IllegalStateException 共享连接上正开着调用方的事务
     */
    public static ClaimResult claim(ServerPlayer player, ResourceLocation advancementId) {
        AchievementRewardRepository repository = AchievementServices.rewards();
        Optional<AchievementReward> reward;
        try {
            requireNoOpenTransaction(repository, "claim");
            reward = repository.reward(player.getUUID(), advancementId);
        } catch (AchievementStoreException failure) {
            return storeFailed(player, failure);
        }
        if (reward.isEmpty()) {
            return ClaimResult.rejected(ClaimResult.Status.NOT_FOUND, advancementId, null);
        }
        if (reward.get().isClaimed()) {
            return ClaimResult.rejected(ClaimResult.Status.ALREADY_CLAIMED, advancementId, null);
        }
        return claimInOneTransaction(player, List.of(reward.get()));
    }

    /**
     * 一次领取全部待领取的奖励 (同一个事务, 一条失败则全部不发)。
     *
     * @throws IllegalStateException 共享连接上正开着调用方的事务
     */
    public static ClaimResult claimAll(ServerPlayer player) {
        AchievementRewardRepository repository = AchievementServices.rewards();
        List<AchievementReward> pending;
        try {
            requireNoOpenTransaction(repository, "claimAll");
            pending = repository.pending(player.getUUID());
        } catch (AchievementStoreException failure) {
            return storeFailed(player, failure);
        }
        if (pending.isEmpty()) {
            return ClaimResult.rejected(ClaimResult.Status.NOTHING_PENDING, null, null);
        }
        return claimInOneTransaction(player, pending);
    }

    /**
     * 管理员加点: 余额与累计获得同时增加 (仓库只有这一种入账), 写一条 admin 流水。
     *
     * @return 调整后的成就点
     * @throws IllegalStateException 共享连接上正开着调用方的事务
     */
    public static PointBalance addPoints(UUID player, int amount, String issuer) {
        requirePositive(amount);
        AchievementRewardRepository repository = AchievementServices.rewards();
        requireNoOpenTransaction(repository, "addPoints");
        long now = System.currentTimeMillis();
        repository.inTransaction(tx -> {
            repository.credit(player, amount);
            repository.appendLedger(player, amount, LedgerReason.ADMIN, issuer, now);
            return null;
        });
        PointBalance balance = repository.points(player);
        ADMIN_LOGGER.info("[miningdim] achievement points admin add: issuer={} targetUuid={} amount={} balance={} "
                + "lifetime={}", issuer, player, amount, balance.balance(), balance.lifetime());
        return balance;
    }

    /**
     * 管理员扣点: 余额不足时只扣到 0, 不会出现负余额; 累计获得不变。实际扣了多少就写多少的 admin 流水,
     * 余额本来就是 0 时什么都不写。
     *
     * @return 实际扣除的点数
     * @throws IllegalStateException 共享连接上正开着调用方的事务
     */
    public static long removePoints(UUID player, int amount, String issuer) {
        requirePositive(amount);
        AchievementRewardRepository repository = AchievementServices.rewards();
        requireNoOpenTransaction(repository, "removePoints");
        long now = System.currentTimeMillis();
        long removed = repository.inTransaction(tx -> {
            long take = Math.min(amount, repository.points(player).balance());
            if (take <= 0L) {
                return 0L;
            }
            if (!repository.debit(player, take)) {
                // 同一事务里刚读到的余额够扣却扣不动, 只能是仓库实现出了错; 让它痛, 别静默写一条对不上的流水。
                throw new IllegalStateException("debit of " + take + " refused right after reading a sufficient "
                        + "balance for " + player);
            }
            repository.appendLedger(player, -take, LedgerReason.ADMIN, issuer, now);
            return take;
        });
        ADMIN_LOGGER.info("[miningdim] achievement points admin remove: issuer={} targetUuid={} requested={} "
                + "removed={}", issuer, player, amount, removed);
        return removed;
    }

    private static ClaimResult claimInOneTransaction(ServerPlayer player, List<AchievementReward> rewards) {
        UUID uuid = player.getUUID();
        AchievementRewardRepository repository = AchievementServices.rewards();
        long now = System.currentTimeMillis();
        Committed committed;
        try {
            committed = repository.inTransaction(tx -> {
                List<ResourceLocation> granted = new ArrayList<>();
                for (AchievementReward reward : rewards) {
                    ResourceLocation id = reward.advancementId();
                    if (!repository.markClaimed(uuid, id, now)) {
                        throw new ClaimAborted(ClaimResult.Status.ALREADY_CLAIMED, id, null);
                    }
                    if (reward.points() > 0) {
                        repository.credit(uuid, reward.points());
                        repository.appendLedger(uuid, reward.points(), LedgerReason.CLAIM, id.toString(), now);
                    }
                    ResourceLocation titleId = reward.titleId();
                    if (titleId != null && grantTitle(tx, uuid, id, titleId) == GrantResult.GRANTED) {
                        granted.add(titleId);
                    }
                }
                // 余额在事务内读: 提交之后再读一旦失败, 会把已经提交的领取误报成失败。
                return new Committed(granted, repository.points(uuid));
            });
        } catch (ClaimAborted aborted) {
            LOGGER.warn("[miningdim] achievement claim of {} for {} ({}) rolled back: {} at {} (title {})",
                    rewards.size() == 1 ? rewards.get(0).advancementId() : rewards.size() + " rewards",
                    player.getGameProfile().getName(), uuid, aborted.status, aborted.advancementId, aborted.titleId);
            return ClaimResult.rejected(aborted.status, aborted.advancementId, aborted.titleId);
        } catch (AchievementStoreException | TitleStoreException | MiningStoreException failure) {
            return storeFailed(player, failure);
        }
        // 事务已提交: 称号提示只发给这次真正新得到的称号 (原本就有的不重复提示)。
        for (ResourceLocation titleId : committed.newTitles()) {
            TitleServices.titleService().notifyGranted(player, titleId);
        }
        LOGGER.info("[miningdim] achievement rewards claimed by {} ({}): {} reward(s), {} point(s), new titles {}",
                player.getGameProfile().getName(), uuid, rewards.size(),
                rewards.stream().mapToLong(AchievementReward::points).sum(), committed.newTitles());
        return ClaimResult.claimed(rewards, committed.balance());
    }

    /** 事务内发放附带称号; 发不出去 (门面未注入、定义缺失、不可发放) 时抛出以回滚整次领取。 */
    private static GrantResult grantTitle(Connection tx, UUID player, ResourceLocation advancementId,
                                          ResourceLocation titleId) {
        if (!TitleServices.isRegistered()) {
            throw new ClaimAborted(ClaimResult.Status.TITLE_UNAVAILABLE, advancementId, titleId);
        }
        ITitleService titles = TitleServices.titleService();
        GrantResult result = titles.grantInTransaction(tx, player, titleId, TitleSource.ACHIEVEMENT,
                advancementId.toString());
        if (result != GrantResult.GRANTED && result != GrantResult.ALREADY_OWNED) {
            throw new ClaimAborted(ClaimResult.Status.TITLE_UNAVAILABLE, advancementId, titleId);
        }
        return result;
    }

    private static ClaimResult storeFailed(ServerPlayer player, RuntimeException failure) {
        LOGGER.error("[miningdim] achievement claim for {} ({}) failed; nothing was claimed",
                player.getGameProfile().getName(), player.getUUID(), failure);
        return ClaimResult.rejected(ClaimResult.Status.STORE_FAILED, null, null);
    }

    /** 领取事务提交的内容: 这次新得到的称号, 与提交时的成就点。 */
    private record Committed(List<ResourceLocation> newTitles, PointBalance balance) {
    }

    /**
     * 前置校验, 理由见类注释: 领取与管理员调整各自开自己的事务, 不能并入调用方已开着的事务 (StoreTx 并入时既不提交也不回滚,
     * 本类对失败的整体回滚就不成立了)。这是接线错误, 不是可恢复的运行期状态。
     */
    private static void requireNoOpenTransaction(AchievementRewardRepository repository, String operation) {
        if (repository.inOpenTransaction()) {
            throw new IllegalStateException("achievement " + operation + " must not run inside the caller's open "
                    + "transaction: it commits or rolls back its own transaction and notifies only after its commit");
        }
    }

    private static void requirePositive(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("point amount must be positive, got " + amount);
        }
    }

    /** 领取中途放弃: 抛出即让外层事务整体回滚, 由 {@link #claimInOneTransaction} 转成对应的结果。 */
    private static final class ClaimAborted extends RuntimeException {

        private final ClaimResult.Status status;
        private final ResourceLocation advancementId;
        @Nullable
        private final ResourceLocation titleId;

        ClaimAborted(ClaimResult.Status status, ResourceLocation advancementId, @Nullable ResourceLocation titleId) {
            super(status + " at " + advancementId, null, false, false);
            this.status = status;
            this.advancementId = advancementId;
            this.titleId = titleId;
        }
    }
}
