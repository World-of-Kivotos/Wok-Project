package com.miningdim.job.farmer.granary;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.UUID;

/**
 * 一座粮仓的登记项 (Spoilage_Granary_DesignSpec「物品数据与存档」里的 {@code granaries})。
 *
 * 只落当前已有的功能需要的字段: 房主、档位与「谁在什么时候替谁验收的」、状态与损坏时刻。
 * 成员表、部件公式快照、待领粮单随它们各自的分片加入 —— 没有对应功能的字段不预埋 (预埋了发布后同样不能改)。
 */
public record GranaryRecord(long id, UUID owner, GranaryTier tier, UUID acceptedBy, long acceptedAtHour,
                            Status status, long damagedAtHour) {

    /** 粮仓状态 (损坏那一支的宽限期随宽限分片加入)。 */
    public enum Status {
        /** 还没验收: 不能存入, 不停表。 */
        UNACCEPTED,
        /** 完好: 能存能取, 存满 24 小时起停表。 */
        OK,
        /** 损坏: 只能取, 宽限期内照常停表, 过了宽限照常腐烂。 */
        DAMAGED
    }


    public static GranaryRecord unaccepted(long id, UUID owner) {
        return new GranaryRecord(id, owner, null, null, 0L, Status.UNACCEPTED, 0L);
    }

    /** 验收通过: 档位在此刻锁定, 记下验收人 (验收人不必是房主)。 */
    public GranaryRecord accepted(GranaryTier tier, UUID acceptedBy, long acceptedAtHour) {
        return new GranaryRecord(id, owner, tier, acceptedBy, acceptedAtHour, Status.OK, 0L);
    }

    /** 结构损坏: 记下出事时刻 (宽限从它起算)。 */
    public GranaryRecord damaged(long damagedAtHour) {
        return new GranaryRecord(id, owner, tier, acceptedBy, acceptedAtHour, Status.DAMAGED, damagedAtHour);
    }

    /** 修好了: 回到完好。 */
    public GranaryRecord repaired() {
        return new GranaryRecord(id, owner, tier, acceptedBy, acceptedAtHour, Status.OK, 0L);
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
        return new GranaryRecord(tag.getLong(GranaryConstants.KEY_ID), tag.getUUID(GranaryConstants.KEY_OWNER), tier,
                tag.hasUUID(GranaryConstants.KEY_ACCEPTED_BY) ? tag.getUUID(GranaryConstants.KEY_ACCEPTED_BY) : null,
                tag.getLong(GranaryConstants.KEY_ACCEPTED_AT), status, tag.getLong(GranaryConstants.KEY_DAMAGED_AT));
    }
}
