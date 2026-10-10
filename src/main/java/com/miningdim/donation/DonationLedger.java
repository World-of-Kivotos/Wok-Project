package com.miningdim.donation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 捐赠箱存取流水。只用于查账, 不产生任何回报 (不发信用点、不记贡献分, 不接经济模块)。
 *
 * 三块数据, 全部随方块实体 NBT 落盘:
 *  - 最近 {@link #MAX_ENTRIES} 条流水 (环形, 满了丢最旧的一条)。永久记录不靠这里, 靠
 *    {@link DonationAudit} 每条一行写进服务端日志。
 *  - 每位玩家的累计放入/取出总数 (不随环形淘汰, 不设上限: 条目数受全服玩家数约束)。自动化输入单独累计。
 *  - 自动化输入的待落账聚合: 漏斗每 8 tick 送一个物品, 逐个记账会把 300 条环形缓冲在几十秒内冲刷干净, 所以
 *    同一窗口 ({@link #AUTOMATION_WINDOW_TICKS}) 内按物品聚合, 窗口到期由方块实体 tick 落成一条。
 *
 * 界面会话的聚合在 {@link DonationSession} 完成, 这里只接收聚合好的结果。时间戳用现实挂钟毫秒 (查账要的是
 * "哪天几点"), 自动化窗口用游戏刻 (与方块实体 tick 同一把尺子)。
 */
public final class DonationLedger {

    /** 方块实体里保留的最近流水条数。 */
    public static final int MAX_ENTRIES = 300;

    /** 自动化输入的聚合窗口: 5 分钟。一条漏斗链全速运转时, 300 条缓冲约可覆盖一天。 */
    public static final long AUTOMATION_WINDOW_TICKS = 6000L;

    private static final long NO_WINDOW = Long.MIN_VALUE;

    public enum Action {
        DEPOSIT,
        WITHDRAW
    }

    /**
     * 一条流水。{@code actorId == null} 表示自动化输入 (漏斗等), 此时 actorName 为空串, 显示层自行本地化。
     */
    public record Entry(long timeMillis, @Nullable UUID actorId, String actorName, Action action,
                        ResourceLocation itemId, int count) {

        public boolean automation() {
            return actorId == null;
        }
    }

    /** 某位玩家的累计存取 (件数, 不分物品)。 */
    public record Totals(UUID playerId, String playerName, long deposited, long withdrawn) {
    }

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final Map<UUID, Totals> totals = new LinkedHashMap<>();
    private long automationDeposited;
    private final Map<ResourceLocation, Integer> pendingAutomation = new LinkedHashMap<>();
    private long automationWindowStart = NO_WINDOW;

    // ---- 写入 ----

    /**
     * 落账一次界面会话: 每个 (动作, 物品) 一条, 先放入后取出。返回本次新增的条目 (调用方逐条写服务端日志)。
     */
    public List<Entry> recordSession(UUID actorId, String actorName,
                                     Map<ResourceLocation, Integer> deposits,
                                     Map<ResourceLocation, Integer> withdrawals,
                                     long nowMillis) {
        List<Entry> added = new ArrayList<>();
        long deposited = 0L;
        long withdrawn = 0L;
        for (Map.Entry<ResourceLocation, Integer> e : deposits.entrySet()) {
            if (e.getValue() > 0) {
                added.add(append(new Entry(nowMillis, actorId, actorName, Action.DEPOSIT, e.getKey(), e.getValue())));
                deposited += e.getValue();
            }
        }
        for (Map.Entry<ResourceLocation, Integer> e : withdrawals.entrySet()) {
            if (e.getValue() > 0) {
                added.add(append(new Entry(nowMillis, actorId, actorName, Action.WITHDRAW, e.getKey(), e.getValue())));
                withdrawn += e.getValue();
            }
        }
        if (deposited > 0L || withdrawn > 0L) {
            Totals previous = totals.get(actorId);
            long baseDeposited = previous == null ? 0L : previous.deposited();
            long baseWithdrawn = previous == null ? 0L : previous.withdrawn();
            totals.put(actorId, new Totals(actorId, actorName, baseDeposited + deposited, baseWithdrawn + withdrawn));
        }
        return added;
    }

    /** 记一笔自动化输入 (尚未落账)。窗口在第一笔到来时开启。 */
    public void addAutomation(ResourceLocation itemId, int count, long gameTime) {
        if (count <= 0) {
            return;
        }
        if (automationWindowStart == NO_WINDOW) {
            automationWindowStart = gameTime;
        }
        pendingAutomation.merge(itemId, count, Integer::sum);
    }

    /**
     * 自动化窗口是否到期。游戏刻倒退 (/time 不影响 gameTime, 但换存档/数据修复可能) 时同样视为到期,
     * 防止窗口永远等不到。
     */
    public boolean automationDue(long gameTime) {
        if (pendingAutomation.isEmpty() || automationWindowStart == NO_WINDOW) {
            return false;
        }
        return gameTime < automationWindowStart || gameTime - automationWindowStart >= AUTOMATION_WINDOW_TICKS;
    }

    /** 把待落账的自动化输入逐物品落成条目并清空窗口; 返回新增条目。 */
    public List<Entry> flushAutomation(long nowMillis) {
        if (pendingAutomation.isEmpty()) {
            automationWindowStart = NO_WINDOW;
            return List.of();
        }
        List<Entry> added = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Integer> e : pendingAutomation.entrySet()) {
            added.add(append(new Entry(nowMillis, null, "", Action.DEPOSIT, e.getKey(), e.getValue())));
            automationDeposited += e.getValue();
        }
        pendingAutomation.clear();
        automationWindowStart = NO_WINDOW;
        return added;
    }

    /**
     * 丢弃未落账的自动化输入并关闭窗口, 返回丢弃的件数。只给方块实体的 Clearable 清空用: /clone ... move 的目标
     * 会从快照里带着这份待落账继续记账, 源头再落一次就会记重。
     */
    public int discardPendingAutomation() {
        int total = 0;
        for (int count : pendingAutomation.values()) {
            total += count;
        }
        pendingAutomation.clear();
        automationWindowStart = NO_WINDOW;
        return total;
    }

    private Entry append(Entry entry) {
        entries.addLast(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
        return entry;
    }

    // ---- 读取 ----

    public int size() {
        return entries.size();
    }

    public List<Entry> entriesOldestFirst() {
        return List.copyOf(entries);
    }

    public List<Entry> entriesNewestFirst() {
        List<Entry> list = new ArrayList<>(entries.size());
        Iterator<Entry> it = entries.descendingIterator();
        while (it.hasNext()) {
            list.add(it.next());
        }
        return list;
    }

    /** 累计表, 按放入件数降序, 同数按名字排序 (显示稳定)。 */
    public List<Totals> totals() {
        List<Totals> list = new ArrayList<>(totals.values());
        list.sort(Comparator.comparingLong(Totals::deposited).reversed()
                .thenComparing(Totals::playerName, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    public Optional<Totals> totalsOf(UUID playerId) {
        return Optional.ofNullable(totals.get(playerId));
    }

    public long automationDeposited() {
        return automationDeposited;
    }

    public Map<ResourceLocation, Integer> pendingAutomation() {
        return Collections.unmodifiableMap(pendingAutomation);
    }

    // ---- 持久化 ----

    private static final String K_ENTRIES = "Entries";
    private static final String K_TOTALS = "Totals";
    private static final String K_AUTO_TOTAL = "AutomationDeposited";
    private static final String K_PENDING = "PendingAutomation";
    private static final String K_PENDING_SINCE = "PendingSince";
    private static final String K_TIME = "Time";
    private static final String K_ACTOR = "Actor";
    private static final String K_NAME = "Name";
    private static final String K_ACTION = "Action";
    private static final String K_ITEM = "Item";
    private static final String K_COUNT = "Count";
    private static final String K_DEPOSITED = "Deposited";
    private static final String K_WITHDRAWN = "Withdrawn";

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Entry e : entries) {
            CompoundTag row = new CompoundTag();
            row.putLong(K_TIME, e.timeMillis());
            if (e.actorId() != null) {
                row.putUUID(K_ACTOR, e.actorId());
            }
            row.putString(K_NAME, e.actorName());
            row.putString(K_ACTION, e.action().name());
            row.putString(K_ITEM, e.itemId().toString());
            row.putInt(K_COUNT, e.count());
            list.add(row);
        }
        tag.put(K_ENTRIES, list);

        ListTag totalsList = new ListTag();
        for (Totals t : totals.values()) {
            CompoundTag row = new CompoundTag();
            row.putUUID(K_ACTOR, t.playerId());
            row.putString(K_NAME, t.playerName());
            row.putLong(K_DEPOSITED, t.deposited());
            row.putLong(K_WITHDRAWN, t.withdrawn());
            totalsList.add(row);
        }
        tag.put(K_TOTALS, totalsList);
        tag.putLong(K_AUTO_TOTAL, automationDeposited);

        ListTag pending = new ListTag();
        for (Map.Entry<ResourceLocation, Integer> e : pendingAutomation.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putString(K_ITEM, e.getKey().toString());
            row.putInt(K_COUNT, e.getValue());
            pending.add(row);
        }
        tag.put(K_PENDING, pending);
        if (automationWindowStart != NO_WINDOW) {
            tag.putLong(K_PENDING_SINCE, automationWindowStart);
        }
        return tag;
    }

    /**
     * 读回。坏行 (物品 id 不合法、数量非正、动作名未知) 逐行跳过而不是整体作废 —— 账本丢一行好过丢全部。
     */
    public void load(CompoundTag tag) {
        entries.clear();
        totals.clear();
        pendingAutomation.clear();
        automationWindowStart = NO_WINDOW;

        ListTag list = tag.getList(K_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag row = list.getCompound(i);
            ResourceLocation item = ResourceLocation.tryParse(row.getString(K_ITEM));
            Action action = parseAction(row.getString(K_ACTION));
            int count = row.getInt(K_COUNT);
            if (item == null || action == null || count <= 0) {
                continue;
            }
            UUID actor = row.hasUUID(K_ACTOR) ? row.getUUID(K_ACTOR) : null;
            append(new Entry(row.getLong(K_TIME), actor, row.getString(K_NAME), action, item, count));
        }

        ListTag totalsList = tag.getList(K_TOTALS, Tag.TAG_COMPOUND);
        for (int i = 0; i < totalsList.size(); i++) {
            CompoundTag row = totalsList.getCompound(i);
            if (!row.hasUUID(K_ACTOR)) {
                continue;
            }
            UUID id = row.getUUID(K_ACTOR);
            totals.put(id, new Totals(id, row.getString(K_NAME),
                    Math.max(0L, row.getLong(K_DEPOSITED)), Math.max(0L, row.getLong(K_WITHDRAWN))));
        }
        automationDeposited = Math.max(0L, tag.getLong(K_AUTO_TOTAL));

        ListTag pending = tag.getList(K_PENDING, Tag.TAG_COMPOUND);
        for (int i = 0; i < pending.size(); i++) {
            CompoundTag row = pending.getCompound(i);
            ResourceLocation item = ResourceLocation.tryParse(row.getString(K_ITEM));
            int count = row.getInt(K_COUNT);
            if (item != null && count > 0) {
                pendingAutomation.merge(item, count, Integer::sum);
            }
        }
        if (!pendingAutomation.isEmpty()) {
            // 缺键的旧档: 从 0 起算, 下一次 tick 必然判到期, 立即落账而不是丢掉。
            automationWindowStart = tag.contains(K_PENDING_SINCE) ? tag.getLong(K_PENDING_SINCE) : 0L;
        }
    }

    @Nullable
    private static Action parseAction(String name) {
        for (Action action : Action.values()) {
            if (action.name().equals(name)) {
                return action;
            }
        }
        return null;
    }
}
