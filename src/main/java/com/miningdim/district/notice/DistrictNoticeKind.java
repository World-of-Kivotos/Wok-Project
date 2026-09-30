package com.miningdim.district.notice;

import com.miningdim.district.core.RemoveReasonKind;
import net.minecraft.ChatFormatting;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 自管区聊天通知的种类 (设计文档 22.10): wire 值 (存进 district_notice.kind)、语言键
 * {@code district.miningdim.notice.<wire>}、参数个数与颜色。
 *
 * <p>颜色在代码里加, 语言值里不写 §: 对收件人有利或中性的用青色 (加入、任命、买地、解冻、成为朋友、恢复朋友、开放购买);
 * 不利或要 TA 去处理的用金色 (移出、撤销、冻结、收回、管理员代改、不再是朋友、朋友被暂停, 以及"原来的地块还冻结着")。
 * 上线补发的头一行 ({@link #HEADER_KEY}) 用灰色。
 *
 * <p>wire 值一旦发布就不能改名: 库里排着的行按它找语言键。新增一种只是多一个常量, 不用开迁移 (kind 不进 CHECK)。
 */
public enum DistrictNoticeKind {
    RESIDENT_ADDED("resident_added", 2, true),
    RESIDENT_ADDED_FROZEN("resident_added_frozen", 1, false),
    RESIDENT_REMOVED_INACTIVE("resident_removed.inactive", 1, false),
    RESIDENT_REMOVED_VIOLATION("resident_removed.violation", 1, false),
    RESIDENT_REMOVED_SELF_REQUEST("resident_removed.self_request", 1, false),
    RESIDENT_REMOVED_OTHER("resident_removed.other", 1, false),
    PLOT_FROZEN("plot_frozen", 2, false),
    WARDEN_APPOINTED("warden_appointed", 1, true),
    WARDEN_REVOKED("warden_revoked", 1, false),
    PLOT_BOUGHT("plot_bought", 2, true),
    PLOT_UNFROZEN("plot_unfrozen", 1, true),
    PLOT_RECLAIMED_NOW("plot_reclaimed.now", 1, false),
    PLOT_RECLAIMED_EXPIRED("plot_reclaimed.expired", 1, false),
    PLOT_ADMIN_PERMISSION("plot_admin.permission", 3, false),
    PLOT_ADMIN_RESET("plot_admin.reset", 4, false),
    PLOT_ADMIN_FRIEND_ADDED("plot_admin.friend_added", 3, false),
    PLOT_ADMIN_FRIEND_REMOVED("plot_admin.friend_removed", 3, false),
    PLOT_ADMIN_FRIEND_RESTORED("plot_admin.friend_restored", 3, false),
    PLOT_ADMIN_RESIZED("plot_admin.resized", 4, false),
    FRIEND_SUSPENDED("friend_suspended", 2, false),
    FRIEND_ADDED("friend_added", 2, true),
    FRIEND_REMOVED("friend_removed", 2, false),
    FRIEND_RESTORED("friend_restored", 2, true),
    /** 只发在线的, 不入队 (P28)。 */
    PURCHASE_OPENED("purchase_opened", 2, true);

    /** 全部通知键的前缀。 */
    public static final String KEY_PREFIX = "district.miningdim.notice.";

    /** 上线补发时的灰色头一行: "你不在线期间有 {0} 条自管区消息："。 */
    public static final String HEADER_KEY = KEY_PREFIX + "header";

    private final String wire;
    private final int arity;
    private final boolean favourable;

    DistrictNoticeKind(String wire, int arity, boolean favourable) {
        this.wire = wire;
        this.arity = arity;
        this.favourable = favourable;
    }

    public String wire() {
        return wire;
    }

    /** 语言键。 */
    public String key() {
        return KEY_PREFIX + wire;
    }

    /** 参数个数 (语言值里恰好有这么多个占位符, DistrictLangGameTests 核对)。 */
    public int arity() {
        return arity;
    }

    /** 对收件人有利或中性为青色, 否则金色。 */
    public ChatFormatting color() {
        return favourable ? ChatFormatting.AQUA : ChatFormatting.GOLD;
    }

    /** 入队 (离线的上线后补发); 为假的只发在线的。 */
    public boolean queued() {
        return this != PURCHASE_OPENED;
    }

    /**
     * 给朋友那一侧的通知 (friend_added / friend_removed / friend_restored): 任何户主都能对任何玩家 (包括从没进过服的名字)
     * 反复触发, 所以 (22.19): 同一个收件人、同一块地、同一个户主的只留一条合并后的净变化; 队列超出上限时最先删它们;
     * 提交后即时送达有间隔 ({@code DistrictLimits.FRIEND_NOTICE_INTERVAL_MS})。
     */
    public boolean friendSide() {
        return this == FRIEND_ADDED || this == FRIEND_REMOVED || this == FRIEND_RESTORED;
    }

    /** 全部 {@link #friendSide()} 的 wire 值 (仓储按它们排删除的先后)。 */
    public static List<String> friendSideWires() {
        return List.of(FRIEND_ADDED.wire, FRIEND_REMOVED.wire, FRIEND_RESTORED.wire);
    }

    /**
     * 同一个收件人、同一块地、同一个户主还没送达的朋友通知 earlier 之后又来一条 latest: 合并成哪一条 (null = 两条抵消,
     * 什么都不发)。按"收件人最后知道的状态 → 现在的状态"算净变化: 加了又移 = 没发生; 移了又加 = TA 本来就是朋友;
     * 暂停后恢复了又移 = 移出; 加了之后 (被暂停再) 恢复 = 加入。
     */
    @Nullable
    public static DistrictNoticeKind coalesceFriend(DistrictNoticeKind earlier, DistrictNoticeKind latest) {
        return switch (earlier) {
            case FRIEND_ADDED -> latest == FRIEND_REMOVED ? null : FRIEND_ADDED;
            case FRIEND_RESTORED -> latest == FRIEND_REMOVED ? FRIEND_REMOVED : FRIEND_RESTORED;
            case FRIEND_REMOVED -> latest == FRIEND_REMOVED ? FRIEND_REMOVED : null;
            default -> latest;
        };
    }

    /** 被移出的人收到哪一种: 只按原因种类, 原因原文绝不进通知。 */
    public static DistrictNoticeKind removed(RemoveReasonKind reason) {
        return switch (reason) {
            case INACTIVE -> RESIDENT_REMOVED_INACTIVE;
            case VIOLATION -> RESIDENT_REMOVED_VIOLATION;
            case SELF_REQUEST -> RESIDENT_REMOVED_SELF_REQUEST;
            case OTHER -> RESIDENT_REMOVED_OTHER;
        };
    }

    /** 库里的 wire 值; 不认识 (例如回退了构建) 为 null。 */
    @Nullable
    public static DistrictNoticeKind fromWire(@Nullable String raw) {
        for (DistrictNoticeKind kind : values()) {
            if (kind.wire.equals(raw)) {
                return kind;
            }
        }
        return null;
    }
}
