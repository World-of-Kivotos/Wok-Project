package com.miningdim.job.farmer.granary;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 一座粮仓的登记项 (Spoilage_Granary_DesignSpec「物品数据与存档」里的 {@code granaries})。
 *
 * 落当前已有的功能需要的字段: 房主、档位与「谁在什么时候替谁验收的」、状态与损坏时刻、**成员表**。
 * 部件公式快照与待领粮单随它们各自的分片加入 —— 没有对应功能的字段不预埋 (预埋了发布后同样不能改)。
 *
 * 成员表只说「谁入册了」: 房主**隐含**是成员, 不写进集合 (写进去就会出现两份真相); 能不能存东西另看
 * 囤主的成员身份与囤的启用状态 (见 {@link GranaryInspection})。
 */
public record GranaryRecord(long id, UUID owner, GranaryTier tier, UUID acceptedBy, long acceptedAtHour,
                            Status status, long damagedAtHour, Set<UUID> members) {

    /** 粮仓状态 (损坏那一支的宽限期随宽限分片加入)。 */
    public enum Status {
        /** 还没验收: 不能存入, 不停表。 */
        UNACCEPTED,
        /** 完好: 能存能取, 存满 24 小时起停表。 */
        OK,
        /** 损坏: 只能取, 宽限期内照常停表, 过了宽限照常腐烂。 */
        DAMAGED;

        /** 语言键。 */
        public String translationKey() {
            return "granary.status." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** 紧凑构造: 成员表按加入顺序记 (成员页要与入册顺序一致, 也便于读数), 并且永远不含房主。 */
    public GranaryRecord {
        Set<UUID> copy = new LinkedHashSet<>();
        if (members != null) {
            for (UUID member : members) {
                if (member != null && !member.equals(owner)) {
                    copy.add(member);
                }
            }
        }
        members = java.util.Collections.unmodifiableSet(copy);
    }

    public static GranaryRecord unaccepted(long id, UUID owner) {
        return new GranaryRecord(id, owner, null, null, 0L, Status.UNACCEPTED, 0L, Set.of());
    }

    /** 验收通过: 档位在此刻锁定, 记下验收人 (验收人不必是房主)。 */
    public GranaryRecord accepted(GranaryTier tier, UUID acceptedBy, long acceptedAtHour) {
        return new GranaryRecord(id, owner, tier, acceptedBy, acceptedAtHour, Status.OK, 0L, members);
    }

    /** 结构损坏: 记下出事时刻 (宽限从它起算)。 */
    public GranaryRecord damaged(long damagedAtHour) {
        return new GranaryRecord(id, owner, tier, acceptedBy, acceptedAtHour, Status.DAMAGED, damagedAtHour,
                members);
    }

    /** 修好了: 回到完好。 */
    public GranaryRecord repaired() {
        return new GranaryRecord(id, owner, tier, acceptedBy, acceptedAtHour, Status.OK, 0L, members);
    }

    /** 入册一位成员 (已经在里面就原样返回)。 */
    public GranaryRecord withMember(UUID member) {
        if (isMember(member)) {
            return this;
        }
        Set<UUID> next = new LinkedHashSet<>(members);
        next.add(member);
        return new GranaryRecord(id, owner, tier, acceptedBy, acceptedAtHour, status, damagedAtHour, next);
    }

    /** 移出一位成员 (房主移不掉自己: 他要么转让要么拆掉, 那是另外两条路)。 */
    public GranaryRecord withoutMember(UUID member) {
        if (!members.contains(member)) {
            return this;
        }
        Set<UUID> next = new LinkedHashSet<>(members);
        next.remove(member);
        return new GranaryRecord(id, owner, tier, acceptedBy, acceptedAtHour, status, damagedAtHour, next);
    }

    /** 是不是成员 (房主隐含是成员, 不算在成员表里)。 */
    public boolean isMember(UUID player) {
        return player != null && (player.equals(owner) || members.contains(player));
    }

    /** 是否已验收过 (档位锁定)。 */
    public boolean accepted() {
        return tier != null;
    }

    /** 能不能存东西 (完好)。 */
    public boolean acceptsDeposits() {
        return status == Status.OK;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong(GranaryConstants.KEY_ID, id);
        tag.putUUID(GranaryConstants.KEY_OWNER, owner);
        if (tier != null) {
            tag.putString(GranaryConstants.KEY_TIER, tier.id());
        }
        if (acceptedBy != null) {
            tag.putUUID(GranaryConstants.KEY_ACCEPTED_BY, acceptedBy);
        }
        tag.putLong(GranaryConstants.KEY_ACCEPTED_AT, acceptedAtHour);
        tag.putString(GranaryConstants.KEY_STATUS, status.name());
        tag.putLong(GranaryConstants.KEY_DAMAGED_AT, damagedAtHour);
        ListTag memberList = new ListTag();
        for (UUID member : members) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(GranaryConstants.KEY_MEMBER, member);
            memberList.add(entry);
        }
        tag.put(GranaryConstants.KEY_MEMBERS, memberList);
        return tag;
    }

    public static GranaryRecord load(CompoundTag tag) {
        if (!tag.contains(GranaryConstants.KEY_ID, Tag.TAG_LONG) || !tag.hasUUID(GranaryConstants.KEY_OWNER)) {
            throw new IllegalArgumentException("Invalid granary record header");
        }
        GranaryTier tier = null;
        if (tag.contains(GranaryConstants.KEY_TIER, Tag.TAG_STRING)) {
            String id = tag.getString(GranaryConstants.KEY_TIER);
            for (GranaryTier candidate : GranaryTier.values()) {
                if (candidate.id().equals(id)) {
                    tier = candidate;
                }
            }
            if (tier == null) {
                throw new IllegalArgumentException("Unknown granary tier: " + id);
            }
        }
        Status status;
        try {
            status = Status.valueOf(tag.getString(GranaryConstants.KEY_STATUS));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid granary status: " + tag.getString(GranaryConstants.KEY_STATUS));
        }
        Set<UUID> members = new LinkedHashSet<>();
        if (tag.contains(GranaryConstants.KEY_MEMBERS, Tag.TAG_LIST)) {
            ListTag memberList = tag.getList(GranaryConstants.KEY_MEMBERS, Tag.TAG_COMPOUND);
            for (int index = 0; index < memberList.size(); index++) {
                CompoundTag entry = memberList.getCompound(index);
                if (!entry.hasUUID(GranaryConstants.KEY_MEMBER)) {
                    throw new IllegalArgumentException("Invalid granary member entry");
                }
                members.add(entry.getUUID(GranaryConstants.KEY_MEMBER));
            }
        }
        return new GranaryRecord(tag.getLong(GranaryConstants.KEY_ID), tag.getUUID(GranaryConstants.KEY_OWNER), tier,
                tag.hasUUID(GranaryConstants.KEY_ACCEPTED_BY) ? tag.getUUID(GranaryConstants.KEY_ACCEPTED_BY) : null,
                tag.getLong(GranaryConstants.KEY_ACCEPTED_AT), status, tag.getLong(GranaryConstants.KEY_DAMAGED_AT),
                members);
    }
}
