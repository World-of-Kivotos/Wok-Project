package com.miningdim.quest;

import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单个玩家的任务板: 四类任务的全部在途状态。纯逻辑, 无世界引用, 不取实时时钟 (周期戳由调用方注入)。
 *
 * 周期任务的翻转策略是<b>整组重抽</b>而非清零重来 —— 玩家每天应当看到一组新任务, 而不是同一批任务的计数被
 * 抹掉。这也是 {@link QuestProgress} 不持周期戳的原因: 周期归本类管, 一条进度只管从 0 数到达标。
 *
 * 线程: 只在服务端主线程读写 (事件与命令均在主线程)。任何变更后调用方须让 {@link QuestSavedData} 标脏。
 */
public final class QuestBoard {

    /**
     * "从未发过牌"的哨兵。任何真实的 epochDay / ISO 周戳都不可能取到这个值, 因此首次
     * {@link #rolloverIfStale} 必然触发发牌。用哨兵而不是额外的 boolean 字段: 两者等价, 但哨兵不会出现
     * "flag 与 stamp 不同步"的第二种状态。
     */
    private static final long NEVER_DEALT = Long.MIN_VALUE;

    private long dailyStamp = NEVER_DEALT;
    private long weeklyStamp = NEVER_DEALT;

    /**
     * 上一次向该玩家抛出特殊任务的世界游戏时间 ({@code Level.getGameTime()})。
     *
     * 用游戏时间而非玩家 tickCount: 后者随重登归零, 玩家掉线重连即可绕过冷却反复摇特殊任务。游戏时间随存档
     * 持久化, 是唯一不会被玩家重连重置的单调时钟。
     */
    private long lastSpecialOfferGameTime = NEVER_DEALT;

    private final List<QuestProgress> daily = new ArrayList<>();
    private final List<QuestProgress> weekly = new ArrayList<>();
    private final List<QuestProgress> special = new ArrayList<>();
    private final Map<String, QuestChainState> chains = new LinkedHashMap<>();

    public List<QuestProgress> daily() {
        return List.copyOf(daily);
    }

    public List<QuestProgress> weekly() {
        return List.copyOf(weekly);
    }

    public List<QuestProgress> special() {
        return List.copyOf(special);
    }

    public Collection<QuestChainState> chains() {
        return List.copyOf(chains.values());
    }

    public QuestChainState chain(String chainId) {
        return chains.get(chainId);
    }

    public long dailyStamp() {
        return dailyStamp;
    }

    public long weeklyStamp() {
        return weeklyStamp;
    }

    public long lastSpecialOfferGameTime() {
        return lastSpecialOfferGameTime;
    }

    /**
     * 特殊任务的抛出冷却是否已过。
     *
     * 从未抛过 ({@link #NEVER_DEALT}) 直接放行。另外显式处理 {@code gameTime < last} 的情况: 存档被回档或
     * 世界时间被 /time 指令调小时, 差值会变成负数从而永久锁死冷却 —— 此时按"冷却已过"处理并让调用方重置,
     * 宁可多给一次机会, 也不留一个玩家自己无法解除的死锁。
     */
    public boolean specialOfferCooledDown(long gameTime, int cooldownTicks) {
        if (lastSpecialOfferGameTime == NEVER_DEALT || gameTime < lastSpecialOfferGameTime) {
            return true;
        }
        return gameTime - lastSpecialOfferGameTime >= cooldownTicks;
    }

    /** 记下本次抛出特殊任务的时间 (无论玩家最终是否真的挂上了任务, 都要记, 否则失败会导致每 tick 重摇)。 */
    public void markSpecialOffered(long gameTime) {
        this.lastSpecialOfferGameTime = gameTime;
    }

    /**
     * 跨周期戳时重发对应的周期任务。
     *
     * @param dayStamp  当前 UTC 日戳
     * @param weekStamp 当前 ISO 周戳
     * @return 是否发生了重发 (调用方据此标脏存档 / 通知玩家)
     */
    public boolean rolloverIfStale(QuestPool pool, RandomSource random, long dayStamp, long weekStamp,
                                   int dailySlots, int dailyHardSlots, int weeklySlots) {
        boolean changed = false;
        if (dailyStamp != dayStamp) {
            dailyStamp = dayStamp;
            dealStratified(daily, pool, QuestSource.DAILY, dailySlots, dailyHardSlots, random);
            changed = true;
        }
        if (weeklyStamp != weekStamp) {
            weeklyStamp = weekStamp;
            // 周常整池都在难档 (设计上就是要 1-3 天才做得完), 故不分层, 全部按难档发。
            dealStratified(weekly, pool, QuestSource.WEEKLY, weeklySlots, weeklySlots, random);
            changed = true;
        }
        return changed;
    }

    /**
     * 分层发牌: 先发简单档, 再发难档 (日常 "3 简单 + 1 难", 主控 2026-08-16 定)。
     *
     * 简单档先发是有意的 —— 难档回落到全池时 ({@link QuestPool#drawTier} 的兜底) 已发的简单档会进排除表,
     * 不至于出现"难档兜底又摇出同一条简单任务"。
     */
    private static void dealStratified(List<QuestProgress> slots, QuestPool pool, QuestSource source,
                                       int totalSlots, int hardSlots, RandomSource random) {
        slots.clear();
        int hard = Math.min(Math.max(hardSlots, 0), totalSlots);
        int easy = totalSlots - hard;
        Set<String> taken = new HashSet<>();
        dealTier(slots, pool, source, false, easy, random, taken);
        dealTier(slots, pool, source, true, hard, random, taken);
    }

    private static void dealTier(List<QuestProgress> slots, QuestPool pool, QuestSource source, boolean hardTier,
                                 int count, RandomSource random, Set<String> taken) {
        if (count < 1) {
            return;
        }
        for (QuestDefinition definition : pool.drawTier(source, hardTier, count, random, taken)) {
            slots.add(new QuestProgress(definition));
            taken.add(definition.id());
        }
    }

    /**
     * 重摇一个周期任务槽 (调用方须已收过重摇费)。
     *
     * 新任务排除该来源当前持有的全部任务 id, 保证摇出来的确实不一样 (内容池不足时的兜底见
     * {@link QuestPool#draw})。
     *
     * <b>重摇必须在原槽位所属的难度档内摇</b>: 否则玩家花信用点就能把难档槽换成简单档槽, 那是一条稳赚的固定
     * 套利 (简单任务奖励低但按难度线性算, 单位时间收益更高), 重摇会从"换一个不想做的任务"退化成"买便宜任务"。
     * 档位直接读原槽位当前那条任务的难度, 不额外持久化槽位属性 —— 少一份可能与真相不同步的状态。
     *
     * @param index 槽位下标
     * @return 新任务; 下标越界或该来源不可重摇时抛异常 (属调用方校验缺失, 不静默吞)
     */
    public QuestProgress refreshSlot(QuestSource source, int index, QuestPool pool, RandomSource random) {
        if (!source.refreshable()) {
            throw new IllegalArgumentException("quest source " + source + " is not refreshable");
        }
        List<QuestProgress> slots = mutableSlots(source);
        if (index < 0 || index >= slots.size()) {
            throw new IllegalArgumentException(
                    "slot index out of range for " + source + ": " + index + " (size " + slots.size() + ")");
        }
        boolean hardTier = QuestPool.isHardTier(slots.get(index).definition());
        Set<String> held = new HashSet<>();
        for (QuestProgress progress : slots) {
            held.add(progress.definition().id());
        }
        List<QuestDefinition> drawn = pool.drawTier(source, hardTier, 1, random, held);
        if (drawn.isEmpty()) {
            throw new IllegalStateException("quest pool has no definition for source " + source);
        }
        QuestProgress replacement = new QuestProgress(drawn.get(0));
        slots.set(index, replacement);
        return replacement;
    }

    private List<QuestProgress> mutableSlots(QuestSource source) {
        return switch (source) {
            case DAILY -> daily;
            case WEEKLY -> weekly;
            case SPECIAL -> special;
            case HIDDEN -> throw new IllegalArgumentException("hidden quests live in chains, not in slots");
        };
    }

    /**
     * 挂上一条特殊任务 (随机事件触发)。
     *
     * @param maxActive 同时在途的特殊任务上限
     * @return true = 已挂上; false = 已达上限或该任务已在途 (调用方据此决定要不要提示玩家)
     */
    public boolean addSpecial(QuestDefinition definition, int maxActive) {
        if (definition.source() != QuestSource.SPECIAL) {
            throw new IllegalArgumentException("not a special quest: " + definition.id());
        }
        if (special.size() >= maxActive) {
            return false;
        }
        for (QuestProgress progress : special) {
            if (progress.definition().id().equals(definition.id())) {
                return false;
            }
        }
        special.add(new QuestProgress(definition));
        return true;
    }

    /** 摘掉一条特殊任务 (领奖后回收槽位)。 */
    public boolean removeSpecial(String definitionId) {
        return special.removeIf(progress -> progress.definition().id().equals(definitionId));
    }

    /**
     * 解锁一条隐藏任务线。已解锁则原样保留 (不会把进度打回第一阶段)。
     *
     * @return true = 本次真的解锁了 (调用方据此给玩家发提示)
     */
    public boolean unlockChain(QuestChain chain) {
        if (chains.containsKey(chain.id())) {
            return false;
        }
        chains.put(chain.id(), new QuestChainState(chain));
        return true;
    }

    /** 从存档重建任务线状态 (绕过"已解锁则不动"的语义, 直接放入)。 */
    void restoreChain(QuestChainState state) {
        chains.put(state.chain().id(), state);
    }

    /** 从存档重建周期槽位。 */
    void restorePeriodic(long dayStamp, List<QuestProgress> dailySlots, long weekStamp,
                         List<QuestProgress> weeklySlots) {
        this.dailyStamp = dayStamp;
        this.weeklyStamp = weekStamp;
        this.daily.clear();
        this.daily.addAll(dailySlots);
        this.weekly.clear();
        this.weekly.addAll(weeklySlots);
    }

    /** 从存档重建特殊任务与抛出冷却。 */
    void restoreSpecial(List<QuestProgress> progresses, long lastOfferGameTime) {
        this.special.clear();
        this.special.addAll(progresses);
        this.lastSpecialOfferGameTime = lastOfferGameTime;
    }

    /**
     * 把一次事实喂给板上所有在途任务。
     *
     * 不做"命中一条就停"的短路: 同一次击杀可以同时推进日常"击杀僵尸 15 只"与周常"击杀僵尸 150 只", 这是
     * 设计意图而非重复计数 —— 两条任务各自独立发奖, 不存在同一份奖励被领两次。
     *
     * @return 本次计数真的变了的任务 (供调用方发进度提示并标脏存档)
     */
    public List<QuestProgress> record(QuestFacts facts) {
        List<QuestProgress> changed = new ArrayList<>();
        recordInto(daily, facts, changed);
        recordInto(weekly, facts, changed);
        recordInto(special, facts, changed);
        for (QuestChainState state : chains.values()) {
            QuestProgress current = state.current();
            if (current != null && current.record(facts)) {
                changed.add(current);
            }
        }
        return changed;
    }

    private static void recordInto(List<QuestProgress> slots, QuestFacts facts, List<QuestProgress> changed) {
        for (QuestProgress progress : slots) {
            if (progress.record(facts)) {
                changed.add(progress);
            }
        }
    }

    /**
     * 按定义 id 定位板上的一条在途任务 (领奖入口用)。
     *
     * @return 找到的进度; 不存在返回 null (玩家可能提交了一个过期或伪造的 id, 由调用方转成业务拒绝)
     */
    public QuestProgress find(String definitionId) {
        for (List<QuestProgress> slots : List.of(daily, weekly, special)) {
            for (QuestProgress progress : slots) {
                if (progress.definition().id().equals(definitionId)) {
                    return progress;
                }
            }
        }
        for (QuestChainState state : chains.values()) {
            QuestProgress current = state.current();
            if (current != null && current.definition().id().equals(definitionId)) {
                return current;
            }
        }
        return null;
    }

    /** 定位某条在途任务所属的任务线 (领奖后要推进阶段); 不属于任何任务线返回 null。 */
    public QuestChainState chainOf(String definitionId) {
        for (QuestChainState state : chains.values()) {
            QuestProgress current = state.current();
            if (current != null && current.definition().id().equals(definitionId)) {
                return state;
            }
        }
        return null;
    }
}
