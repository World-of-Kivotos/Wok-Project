package com.miningdim.district.notice;

import com.miningdim.district.DistrictLimits;
import com.miningdim.district.DistrictServices;
import com.miningdim.district.access.Actor;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.NoticeRecord;
import com.miningdim.district.store.DistrictRepository;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * 自管区的聊天通知 (设计文档 22.10–22.12): 入队、投递、只发在线的广播与保留期。每个 {@code DistrictContext} 一份
 * ({@code ctx.notices()})。
 *
 * <ul>
 *   <li><b>入队</b> ({@link #enqueue}): 只能在业务事务里调, 行随业务事务一起提交或回滚; 入队后超出每人 30 条就在同一事务里
 *       删掉最旧的; 登记提交后即时投递。</li>
 *   <li><b>投递</b> ({@link #deliverPending}): 先问 {@link NoticeDeliveryGate}; 按发生顺序逐条发聊天栏消息 (上线补发先发
 *       一行灰色的头), 然后删掉这些行 —— 先发后删, 至少送达一次: 删除失败时下次上线会重发, 比丢了强。</li>
 *   <li><b>不重复</b>: 同一事务里给同一人入队几条, 提交后只投一次 (一次读出全部); 投完的行已经删掉, 登录补发、提交后的
 *       即时投递、gate 的重复确认再触发也只会发新产生的。</li>
 *   <li><b>节流</b>: 每人最多 30 条、保留 30 天 (P29); "开放购买"的在线广播同一个区 10 分钟内至多一次。</li>
 *   <li><b>朋友通知防刷</b> (22.19): 任何户主都能对任何玩家反复加、移朋友。给朋友那一侧的通知
 *       ({@link DistrictNoticeKind#friendSide()}) 同一个收件人、同一块地、同一个户主只留一条合并后的净变化 (加了又移 =
 *       没发生); 超出 30 条时先删它们, 冻结、移出、收回这些要紧的挤不掉; 提交后即时送达同一个收件人 60 秒内至多一次,
 *       其余留在队列里, 由定时节拍 ({@link #flushHeld}) 或下次上线补发。</li>
 *   <li>库里缺 district_notice 表时 ({@link #disableStore}, 用阶段 1–2 的开发构建开过的存档): 入队、投递、换键、清过期
 *       一律什么都不做, 业务动作与登录照常; 功能同时降级为只读 (DistrictSystem)。</li>
 *   <li>一切数据库错误都接住、记 ERROR: 通知失败绝不能让登录或已经提交的业务动作失败。</li>
 * </ul>
 * 只在服务器线程上用 (平板动作经 enqueueWork 转到主线程, 命令与登录事件本来就在主线程)。
 */
public final class DistrictNotices {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");

    private final DistrictRepository repo;
    private final LongSupplier clock;
    private final Function<UUID, ServerPlayer> onlinePlayers;

    /**
     * 等提交后即时投递的收件人。每次入队都登记一次提交后动作, 动作把这里整批取走: 同一事务里同一人只投一次。回滚丢弃了
     * 提交后队列, 留在这里的人只会在下一次提交后多查一次 (空的或 gate 没放行而留着的) 队列, 不会多发。
     */
    private final Set<UUID> awaitingDelivery = new LinkedHashSet<>();
    /** 各区上一次"开放购买"广播的时刻。 */
    private final Map<String, Long> lastPurchaseBroadcast = new HashMap<>();
    /** 各收件人上一次即时送达朋友通知的时刻 (60 秒的间隔, 22.19)。 */
    private final Map<UUID, Long> lastFriendDelivery = new HashMap<>();
    /** 朋友通知因为间隔没到而留在队列里、等定时节拍补发的收件人。 */
    private final Set<UUID> heldFriendDelivery = new LinkedHashSet<>();
    /** 上一次清过期通知的时刻; 还没清过为 null。 */
    @Nullable
    private Long lastPruneAt;
    /** 库里有 district_notice 表 (缺表时为假, 一切通知操作什么都不做)。 */
    private volatile boolean storeReady = true;

    public DistrictNotices(DistrictRepository repo, LongSupplier clock, Function<UUID, ServerPlayer> onlinePlayers) {
        this.repo = Objects.requireNonNull(repo, "repo");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers");
    }

    // ================================================================
    // 入队
    // ================================================================

    /**
     * 入队一条通知 (在调用方的业务事务里)。
     *
     * @param args 事件发生那一刻格式化好的字符串 (名字、地块标签、金额、开关文字), 个数必须等于 {@link DistrictNoticeKind#arity}
     * @throws IllegalStateException 不在事务里 (代码错误: 行必须随业务事务一起提交或回滚)
     */
    public void enqueue(UUID recipient, DistrictNoticeKind kind, List<String> args, @Nullable String districtId,
                        @Nullable String plotId) {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(kind, "kind");
        if (!kind.queued()) {
            throw new IllegalArgumentException(kind + " is only sent to online players and is never queued");
        }
        if (args.size() != kind.arity()) {
            throw new IllegalArgumentException(kind + " takes " + kind.arity() + " argument(s), got " + args);
        }
        if (!repo.inOpenTransaction()) {
            throw new IllegalStateException("district notice " + kind.wire()
                    + " must be enqueued inside the business transaction");
        }
        if (!storeReady) {
            LOGGER.debug("[miningdim] district notices: {} for {} dropped (no district_notice table)", kind.wire(),
                    recipient);
            return;
        }
        long now = clock.getAsLong();
        DistrictNoticeKind stored = kind.friendSide() ? coalesceFriendNotice(recipient, kind, args, plotId) : kind;
        if (stored == null) {
            return;
        }
        repo.insertNotice(new NoticeRecord(0, recipient, stored.wire(), args, districtId, plotId, now));
        repo.deleteOldestNotices(recipient, DistrictLimits.NOTICE_KEEP_PER_PLAYER,
                DistrictNoticeKind.friendSideWires());
        if (stored.friendSide() && friendDeliveryThrottled(recipient, now)) {
            // 间隔没到: 留在队列里, 定时节拍 (flushHeld) 或下次上线补发。
            synchronized (heldFriendDelivery) {
                heldFriendDelivery.add(recipient);
            }
            return;
        }
        synchronized (awaitingDelivery) {
            awaitingDelivery.add(recipient);
        }
        repo.afterCommit(this::flushAwaiting);
    }

    /**
     * 朋友通知的合并 (22.19): 删掉这个收件人、这块地、这个户主 (参数的第一个) 还没送达的朋友通知, 返回合并之后要存的
     * 那一种; null = 与之前那条抵消 (例如加了又移)。
     */
    @Nullable
    private DistrictNoticeKind coalesceFriendNotice(UUID recipient, DistrictNoticeKind kind, List<String> args,
                                                    @Nullable String plotId) {
        String owner = args.isEmpty() ? null : args.get(0);
        List<NoticeRecord> earlier = new ArrayList<>();
        for (NoticeRecord row : repo.noticesFor(recipient)) {
            DistrictNoticeKind rowKind = DistrictNoticeKind.fromWire(row.kind());
            if (rowKind != null && rowKind.friendSide() && Objects.equals(row.plotId(), plotId)
                    && row.args() != null && !row.args().isEmpty() && Objects.equals(row.args().get(0), owner)) {
                earlier.add(row);
            }
        }
        if (earlier.isEmpty()) {
            return kind;
        }
        repo.deleteNotices(earlier.stream().map(NoticeRecord::id).toList());
        DistrictNoticeKind first = DistrictNoticeKind.fromWire(earlier.get(0).kind());
        return first == null ? kind : DistrictNoticeKind.coalesceFriend(first, kind);
    }

    /** 这个收件人上一次即时送达朋友通知还在 60 秒以内。 */
    private boolean friendDeliveryThrottled(UUID recipient, long now) {
        synchronized (lastFriendDelivery) {
            Long last = lastFriendDelivery.get(recipient);
            return last != null && now >= last && now - last < DistrictLimits.FRIEND_NOTICE_INTERVAL_MS;
        }
    }

    /**
     * 定时节拍里调 (DistrictSystem.sweep): 给间隔已到、因为间隔留在队列里的收件人补发 (在线才发); 顺带清掉过期的间隔
     * 记录。
     *
     * @return 这次补发给了几人
     */
    public int flushHeld() {
        if (!storeReady) {
            return 0;
        }
        long now = clock.getAsLong();
        List<UUID> due = new ArrayList<>();
        synchronized (heldFriendDelivery) {
            for (var it = heldFriendDelivery.iterator(); it.hasNext(); ) {
                UUID recipient = it.next();
                if (!friendDeliveryThrottled(recipient, now)) {
                    due.add(recipient);
                    it.remove();
                }
            }
        }
        synchronized (lastFriendDelivery) {
            lastFriendDelivery.values().removeIf(last -> now < last
                    || now - last >= DistrictLimits.FRIEND_NOTICE_INTERVAL_MS);
        }
        int delivered = 0;
        for (UUID recipient : due) {
            if (deliverIfOnline(recipient) > 0) {
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * 首次登录换键 (FirstLoginActivation, 在它的事务里): 收件人是旧 UUID 的行改到新 UUID。缺表时什么都不做。
     *
     * @return 改了几行
     */
    public int rekey(UUID oldUuid, UUID newUuid) {
        return storeReady ? repo.rekeyNotices(oldUuid, newUuid) : 0;
    }

    /**
     * 库里没有 district_notice 表 (DistrictSystem 开服时查): 从此入队、投递、换键、清过期一律什么都不做, 让业务动作、登录与
     * 定时节拍照常; 功能由 DistrictSystem 降级为只读, 原因写明缺表。
     */
    public void disableStore() {
        storeReady = false;
    }

    /** 有 district_notice 表 (没被 {@link #disableStore} 停用)。 */
    public boolean storeReady() {
        return storeReady;
    }

    /**
     * 同 {@link #enqueue}, 但不给自己发 (22.10): 收件人就是操作人时什么都不做; 收件人为 null (例如冻结地块没有现任户主)
     * 时也什么都不做。只有 plot_bought 例外 (服主点名要给买家), 它直接调 {@link #enqueue}。
     */
    public void enqueueUnlessSelf(Actor actor, @Nullable UUID recipient, DistrictNoticeKind kind, List<String> args,
                                  @Nullable String districtId, @Nullable String plotId) {
        if (recipient == null || recipient.equals(actor.uuid())) {
            return;
        }
        enqueue(recipient, kind, args, districtId, plotId);
    }

    private void flushAwaiting() {
        List<UUID> recipients;
        synchronized (awaitingDelivery) {
            if (awaitingDelivery.isEmpty()) {
                return;
            }
            recipients = new ArrayList<>(awaitingDelivery);
            awaitingDelivery.clear();
        }
        for (UUID recipient : recipients) {
            deliverIfOnline(recipient);
        }
    }

    // ================================================================
    // 投递
    // ================================================================

    /**
     * 提交后的即时投递: 收件人在线、gate 放行就发 (不带头一行)。
     *
     * @return 发了几条
     */
    public int deliverIfOnline(UUID recipient) {
        ServerPlayer player;
        try {
            player = onlinePlayers.apply(recipient);
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] district notices: looking up online player {} failed", recipient, failure);
            return 0;
        }
        return player == null ? 0 : deliverPending(player, false);
    }

    /**
     * 把这名玩家排着的通知全部发出去, 然后删掉这些行。
     *
     * @param fromLogin 从上线路径来的: 先发一行灰色的头 "你不在线期间有 N 条自管区消息："
     * @return 发了几条 (不含头一行)
     */
    public int deliverPending(ServerPlayer player, boolean fromLogin) {
        if (!storeReady || !NoticeDeliveryGates.current().canDeliverNow(player)) {
            return 0;
        }
        UUID uuid = player.getUUID();
        List<NoticeRecord> rows;
        try {
            rows = repo.noticesFor(uuid);
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] district notices: reading the queue of {} failed", uuid, failure);
            return 0;
        }
        if (rows.isEmpty()) {
            return 0;
        }
        List<Component> messages = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        boolean friendNotice = false;
        for (NoticeRecord row : rows) {
            ids.add(row.id());
            DistrictNoticeKind kind = DistrictNoticeKind.fromWire(row.kind());
            if (kind == null || !kind.queued() || row.args() == null || row.args().size() != kind.arity()) {
                LOGGER.warn("[miningdim] dropping district notice row {} for {}: kind '{}' with args {} is not a "
                        + "known queued notice (an older or newer build wrote it)", row.id(), uuid, row.kind(),
                        row.args());
                continue;
            }
            friendNotice |= kind.friendSide();
            messages.add(render(kind, row.args()));
        }
        if (friendNotice) {
            long now = clock.getAsLong();
            synchronized (lastFriendDelivery) {
                lastFriendDelivery.put(uuid, now);
            }
        }
        if (fromLogin && !messages.isEmpty()) {
            player.sendSystemMessage(header(messages.size()));
        }
        for (Component message : messages) {
            player.sendSystemMessage(message);
        }
        try {
            repo.inTransaction(() -> repo.deleteNotices(ids));
        } catch (RuntimeException failure) {
            // 已经发出去了: 删不掉只会让下一次上线重发, 不能反过来让登录或业务动作失败。
            LOGGER.error("[miningdim] district notices: deleting {} delivered notice(s) of {} failed; they will be "
                    + "sent again next time", ids.size(), uuid, failure);
        }
        return messages.size();
    }

    /**
     * 登录确认之后的回调 (22.12, 经 {@link NoticeDeliveryGate#install} 登记): 功能 OFF 时什么都不做; 自己接住一切异常,
     * 不影响别的监听者, 也不影响登录。
     */
    public static void deliverPendingOnLogin(ServerPlayer player) {
        if (!DistrictServices.isRegistered()) {
            return;
        }
        try {
            DistrictServices.context().notices().deliverPending(player, true);
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.error("[miningdim] district notices: login delivery for {} failed",
                    player.getGameProfile().getName(), failure);
        }
    }

    // ================================================================
    // 只发在线的广播
    // ================================================================

    /**
     * "开放购买" (关 → 开) 提交之后的在线广播 (22.10 purchase_opened, P28: 只发在线的, 不入队): 收件人是本区在线、没有地块、
     * 也不是本区冻结地块原户主的住户; 操作人自己不发; 各自先问 gate。同一个区
     * {@link DistrictLimits#PURCHASE_NOTICE_COOLDOWN_MS} 内至多广播一次。
     *
     * @return 发给了几人
     */
    public int broadcastPurchaseOpened(String districtId, @Nullable UUID actorUuid) {
        try {
            Optional<DistrictRecord> found = repo.liveDistrict(districtId);
            if (found.isEmpty() || !found.get().purchaseOpen()) {
                return 0;
            }
            DistrictRecord district = found.get();
            long now = clock.getAsLong();
            synchronized (lastPurchaseBroadcast) {
                Long last = lastPurchaseBroadcast.get(districtId);
                if (last != null && now >= last && now - last < DistrictLimits.PURCHASE_NOTICE_COOLDOWN_MS) {
                    LOGGER.debug("[miningdim] district notices: purchase of {} reopened within the cooldown; not "
                            + "broadcast again", districtId);
                    return 0;
                }
                lastPurchaseBroadcast.put(districtId, now);
            }
            Component message = render(DistrictNoticeKind.PURCHASE_OPENED,
                    List.of(district.displayName(), DistrictTexts.formatCredit(district.unitPrice())));
            NoticeDeliveryGate gate = NoticeDeliveryGates.current();
            int sent = 0;
            for (MemberRecord member : repo.membersOf(district.academyId())) {
                if (member.uuid().equals(actorUuid)) {
                    continue;
                }
                ServerPlayer player = onlinePlayers.apply(member.uuid());
                if (player == null || repo.plotOwnedBy(member.uuid()).isPresent()
                        || repo.frozenPlotsOf(member.uuid()).stream()
                        .anyMatch(plot -> plot.districtId().equals(districtId))
                        || !gate.canDeliverNow(player)) {
                    continue;
                }
                player.sendSystemMessage(message);
                sent++;
            }
            return sent;
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] district notices: broadcasting the purchase opening of {} failed", districtId,
                    failure);
            return 0;
        }
    }

    // ================================================================
    // 保留期
    // ================================================================

    /**
     * 定时节拍里调 (DistrictSystem.sweep): 每小时至多一次, 删掉 30 天前入队的行。从没进过服的人 30 天都没上线, TA 的
     * "你已加入"就过期了; 名单上照样有 TA。
     *
     * @return 删了几行
     */
    public int pruneExpired() {
        if (!storeReady) {
            return 0;
        }
        long now = clock.getAsLong();
        synchronized (this) {
            if (lastPruneAt != null && now >= lastPruneAt
                    && now - lastPruneAt < DistrictLimits.NOTICE_PRUNE_INTERVAL_MS) {
                return 0;
            }
            lastPruneAt = now;
        }
        try {
            return repo.pruneNoticesBefore(now - DistrictLimits.NOTICE_RETENTION_MS);
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] district notices: pruning expired notices failed", failure);
            return 0;
        }
    }

    // ================================================================
    // 渲染
    // ================================================================

    /** 一条通知的聊天消息: 语言键 + 格式化好的参数, 颜色在代码里加。 */
    public static MutableComponent render(DistrictNoticeKind kind, List<String> args) {
        return Component.translatable(kind.key(), args.toArray()).withStyle(kind.color());
    }

    /** 上线补发的灰色头一行。 */
    public static MutableComponent header(int count) {
        return Component.translatable(DistrictNoticeKind.HEADER_KEY, String.valueOf(count))
                .withStyle(ChatFormatting.GRAY);
    }
}
