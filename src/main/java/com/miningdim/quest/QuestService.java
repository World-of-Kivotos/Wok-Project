package com.miningdim.quest;

import com.miningdim.quest.objective.TurnInItemObjective;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

import java.util.List;

/**
 * 任务子系统的唯一业务门面。事件钩子、命令、(后续的) Web UI action 全部只经本类, 不直接碰
 * {@link QuestBoard} / {@link QuestSavedData}。
 *
 * 存在的理由是把三件必须成套发生的事收在一处: 取板前先翻转周期、任何改动后标脏存档、发奖与推进阶段的顺序。
 * 散在各调用点必然漏其中一件 —— 漏翻转就是玩家跨天不刷新, 漏标脏就是重启丢进度。
 *
 * 线程: 全部方法只在服务端主线程调用。
 */
public final class QuestService {

    private final QuestPool pool;

    public QuestService(QuestPool pool) {
        if (pool == null) {
            throw new IllegalArgumentException("pool must not be null");
        }
        this.pool = pool;
    }

    public QuestPool pool() {
        return pool;
    }

    /** 领奖结果。 */
    public enum ClaimOutcome {
        /** 已发奖。 */
        CLAIMED,
        /** 板上没有这条任务 (id 过期或伪造)。 */
        NOT_FOUND,
        /** 尚未达标。 */
        NOT_COMPLETE,
        /** 已经领过了。 */
        ALREADY_CLAIMED
    }

    /**
     * @param credit 实际入账的信用点 (过完全服衰减主闸; 仅 {@link ClaimOutcome#CLAIMED} 时有意义)
     */
    public record ClaimResult(ClaimOutcome outcome, QuestDefinition definition, long credit) {
    }

    /** 重摇结果。 */
    public enum RefreshOutcome {
        REFRESHED,
        /** 信用点不足, 未扣费也未重摇。 */
        NOT_ENOUGH_CREDIT
    }

    public record RefreshResult(RefreshOutcome outcome, QuestProgress replacement, long cost) {
    }

    /**
     * 取该玩家的任务板, 并在取之前完成周期翻转 (跨 UTC 日重发日常, 跨 ISO 周重发周常)。
     *
     * 翻转放在"取板"里而不是挂一个定时器: 定时器要么在玩家离线时空转, 要么在玩家跨天登录的那一刻还没跑到。
     * 按需翻转天然覆盖"离线三天后再登录"这种情形, 且与玩家在不在线无关。
     */
    public QuestBoard boardOf(ServerPlayer player) {
        ServerLevel overworld = player.server.overworld();
        QuestSavedData data = QuestSavedData.get(overworld, pool);
        QuestBoard board = data.board(player.getUUID());
        boolean rolled = board.rolloverIfStale(pool, overworld.getRandom(),
                QuestClock.currentUtcDayStamp(), QuestClock.currentUtcWeekStamp(),
                QuestConfig.DAILY_SLOTS.get(), QuestConfig.DAILY_HARD_SLOTS.get(),
                QuestConfig.WEEKLY_SLOTS.get());
        if (rolled) {
            data.setDirty();
        }
        return board;
    }

    /**
     * 把一次事实计入该玩家板上所有在途任务。
     *
     * @return 计数真的变了的任务 (供调用方发进度提示); 空表表示这次事实与该玩家的任务全都无关
     */
    public List<QuestProgress> record(QuestFacts facts) {
        ServerPlayer player = facts.player();
        QuestBoard board = boardOf(player);
        List<QuestProgress> changed = board.record(facts);
        if (!changed.isEmpty()) {
            QuestSavedData.get(player.server.overworld(), pool).setDirty();
        }
        return changed;
    }

    /**
     * 领取一条已达标任务的奖励。
     *
     * <b>顺序: 先发钱, 后打领取标记。</b> 反过来 (先标记后发钱) 时, 一旦发奖路径抛异常, 任务已被标成"已领"
     * 而钱没到手, 玩家永久损失且无自助补救; 先发钱则异常时什么都没变, 玩家可以直接重试。
     *
     * 已知局限 (不在本轮解决): 任务进度存 NBT (随世界存档定期落盘), 而货币走 SQLite (立即落盘)。服务器在
     * "已发钱、存档尚未落盘"之间硬崩时, 重启后该任务仍显示未领, 可以再领一次。彻底解决需要把领取动作也纳入
     * 货币层的幂等事务 (开箱系统的 Saga 就是这么做的), 代价是给任务系统引入一张 SQLite 表 —— 当前奖励量级
     * (单条千级信用点) 不值这个复杂度, 记录在此以备将来重估。
     */
    public ClaimResult claim(ServerPlayer player, String definitionId) {
        QuestBoard board = boardOf(player);
        QuestProgress progress = board.find(definitionId);
        if (progress == null) {
            return new ClaimResult(ClaimOutcome.NOT_FOUND, null, 0);
        }
        QuestDefinition definition = progress.definition();
        if (progress.claimed()) {
            return new ClaimResult(ClaimOutcome.ALREADY_CLAIMED, definition, 0);
        }
        if (!progress.isComplete()) {
            return new ClaimResult(ClaimOutcome.NOT_COMPLETE, definition, 0);
        }

        long credit = QuestRewards.payout(player, definition);
        if (!progress.tryClaim()) {
            // 上面刚校验过"已达标且未领取", 主线程内无并发, 走到这里说明有重入 —— 让它痛, 别静默重复发奖。
            throw new IllegalStateException("quest " + definitionId + " refused claim after a successful payout");
        }

        QuestChainState chainState = board.chainOf(definitionId);
        if (chainState != null) {
            chainState.advance();
        } else if (definition.source() == QuestSource.SPECIAL) {
            // 特殊任务是一次性的: 领完即摘牌, 把在途名额还给下一次随机事件。
            board.removeSpecial(definitionId);
        }
        QuestSavedData.get(player.server.overworld(), pool).setDirty();
        return new ClaimResult(ClaimOutcome.CLAIMED, definition, credit);
    }

    /**
     * 花信用点重摇一个周期任务槽。
     *
     * 扣费在重摇之前: 扣费失败就什么都不动。已领过奖的槽同样可以重摇 —— 重摇给的是新任务, 与旧任务是否领过
     * 无关, 拦下来只会让玩家把额度浪费在一个已完成的槽上。
     */
    public RefreshResult refresh(ServerPlayer player, QuestSource source, int index) {
        QuestBoard board = boardOf(player);
        long cost = QuestRewards.refreshCost(source);
        if (!QuestRewards.chargeRefresh(player, source)) {
            return new RefreshResult(RefreshOutcome.NOT_ENOUGH_CREDIT, null, cost);
        }
        RandomSource random = player.server.overworld().getRandom();
        QuestProgress replacement = board.refreshSlot(source, index, pool, random);
        QuestSavedData.get(player.server.overworld(), pool).setDirty();
        return new RefreshResult(RefreshOutcome.REFRESHED, replacement, cost);
    }

    /**
     * 随机事件触发点 (如玩家踏入村庄) 请求向该玩家抛一条特殊任务: 依次过冷却闸、概率闸、在途上限闸。
     *
     * <b>冷却在掷骰之前就记下, 无论骰子结果如何。</b> 只在"真的抛出了任务"时才记冷却的话, 一个站在村庄里的
     * 玩家会被每 {@code structureScanIntervalTicks} 掷一次骰, 概率闸形同虚设 —— 站得久必中。先记冷却才让
     * "村庄里平均多久遇到一次"这件事真正由配置说了算。
     *
     * @return 真正挂上的任务; 被任一道闸拦下时返回 null
     */
    public QuestProgress tryOfferSpecial(ServerPlayer player, RandomSource random) {
        ServerLevel overworld = player.server.overworld();
        QuestBoard board = boardOf(player);
        long gameTime = overworld.getGameTime();
        if (!board.specialOfferCooledDown(gameTime, QuestConfig.VILLAGE_TRIGGER_COOLDOWN_TICKS.get())) {
            return null;
        }
        board.markSpecialOffered(gameTime);
        QuestSavedData data = QuestSavedData.get(overworld, pool);
        data.setDirty();

        if (random.nextDouble() >= QuestConfig.VILLAGE_TRIGGER_CHANCE.get()) {
            return null;
        }
        List<QuestDefinition> drawn = pool.draw(QuestSource.SPECIAL, 1, random, java.util.Set.of());
        if (drawn.isEmpty()) {
            return null;
        }
        QuestDefinition definition = drawn.get(0);
        if (!board.addSpecial(definition, QuestConfig.MAX_ACTIVE_SPECIAL.get())) {
            return null;
        }
        data.setDirty();
        return board.find(definition.id());
    }

    /** 上交结果。 */
    public enum TurnInOutcome {
        TURNED_IN,
        /** 板上没有这条任务。 */
        NOT_FOUND,
        /** 这条任务不是上交类。 */
        NOT_A_TURN_IN,
        /** 已经交够了。 */
        ALREADY_COMPLETE,
        /** 背包里一个都没有。 */
        NOTHING_TO_TURN_IN
    }

    /** @param count 本次实际上交 (并从背包扣掉) 的个数 */
    public record TurnInResult(TurnInOutcome outcome, QuestDefinition definition, int count) {
    }

    /**
     * 上交物品推进一条上交类任务。
     *
     * <b>顺序: 先按剩余需求裁剪, 再从背包扣, 扣掉之后才记进度。</b> 反过来 (先记进度再扣) 时, 扣物品那步一旦
     * 失败, 玩家就白得了进度; 先扣则最坏情况是物品没了而进度没记, 这在同一次主线程调用里不会发生 (中间没有
     * 任何可失败的外部调用)。
     *
     * 只按物品种类比对, 不看 NBT: 上交类任务选的都是无实例状态的原版材料 (腐肉/骨头/火药/图腾/下界之星),
     * 没有"附魔过的下界之星"这种东西。将来若要收可损耗或带 NBT 的物品, 这里必须改成显式的匹配规则。
     */
    public TurnInResult turnIn(ServerPlayer player, String definitionId) {
        QuestBoard board = boardOf(player);
        QuestProgress progress = board.find(definitionId);
        if (progress == null) {
            return new TurnInResult(TurnInOutcome.NOT_FOUND, null, 0);
        }
        QuestDefinition definition = progress.definition();
        if (!(definition.objective() instanceof TurnInItemObjective objective)) {
            return new TurnInResult(TurnInOutcome.NOT_A_TURN_IN, definition, 0);
        }
        if (progress.isComplete()) {
            return new TurnInResult(TurnInOutcome.ALREADY_COMPLETE, definition, 0);
        }
        int wanted = progress.requiredCount() - progress.count();
        int taken = removeFromInventory(player, objective.item(), wanted);
        if (taken <= 0) {
            return new TurnInResult(TurnInOutcome.NOTHING_TO_TURN_IN, definition, 0);
        }
        record(new QuestFacts.ItemTurnIn(player, objective.item(), taken));
        return new TurnInResult(TurnInOutcome.TURNED_IN, definition, taken);
    }

    /**
     * 从背包 (主背包 + 副手) 扣掉至多 wanted 个指定物品, 返回实际扣掉的个数。
     *
     * 不碰盔甲槽 —— 那里放着的同种物品多半是玩家正穿着的, 静默扒下来交任务是玩家绝不会预期的行为。
     */
    private static int removeFromInventory(ServerPlayer player, net.minecraft.world.item.Item item, int wanted) {
        int remaining = wanted;
        remaining -= takeFrom(player.getInventory().items, item, remaining);
        remaining -= takeFrom(player.getInventory().offhand, item, remaining);
        return wanted - remaining;
    }

    private static int takeFrom(java.util.List<net.minecraft.world.item.ItemStack> stacks,
                                net.minecraft.world.item.Item item, int wanted) {
        int taken = 0;
        for (int i = 0; i < stacks.size() && taken < wanted; i++) {
            net.minecraft.world.item.ItemStack stack = stacks.get(i);
            if (stack.isEmpty() || stack.getItem() != item) {
                continue;
            }
            int take = Math.min(stack.getCount(), wanted - taken);
            stack.shrink(take);
            taken += take;
        }
        return taken;
    }

    /**
     * 解锁一条隐藏任务线。
     *
     * @return true = 本次真的解锁了 (首次); false = 早已解锁或该任务线不存在
     */
    public boolean unlockChain(ServerPlayer player, String chainId) {
        QuestChain chain = pool.chain(chainId);
        if (chain == null) {
            return false;
        }
        QuestBoard board = boardOf(player);
        if (!board.unlockChain(chain)) {
            return false;
        }
        QuestSavedData.get(player.server.overworld(), pool).setDirty();
        return true;
    }
}
