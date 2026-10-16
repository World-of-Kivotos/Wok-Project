package com.miningdim.job.farmer.granary;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 粮仓的归属账本 (Spoilage_Granary_DesignSpec「额度怎么记账」「物品数据与存档」)。
 *
 * 这一版只落**按坐标记账**这条主线: 储粮囤与账台的归属索引, 以及由它派生的每人占用计数 ——
 * 额度的判定与提示都读派生计数, 不另存一份总数 (两份数会对不上)。规矩照抄耕地那一套 (F025):
 * 归属按 (维度, 打包坐标) 存, 方块被移除才归还; 谁的等级降了、额度超了, 由上层按派生计数处置。
 *
 * 设计文档里 GranarySavedData 的其余字段 (粮仓档位/成员/待领粮单/levelCache/trustDefaults/audit) 随
 * 它们各自的功能分片加入; 现在没有的功能就不预埋空字段 —— 预埋的字段一旦发布同样不能改, 反而更贵。
 */
public final class GranarySavedData extends SavedData {

    private static final int VERSION = 1;
    private static final String KEY_VERSION = "Version";
    private static final String KEY_BINS = "Bins";
    private static final String KEY_CENTERS = "Centers";
    private static final String KEY_DIMENSION = "Dim";
    private static final String KEY_POS = "Pos";
    private static final String KEY_OWNER = "Owner";
    private static final String KEY_SEQ = "Seq";

    private record PosKey(ResourceLocation dimension, long packedPos) {
    }

    private final Map<PosKey, UUID> binOwners = new HashMap<>();
    private final Map<PosKey, Integer> binSeq = new HashMap<>();
    private final Map<PosKey, UUID> centerOwners = new HashMap<>();
    private final Map<UUID, Integer> binCounts = new HashMap<>();
    private final Map<UUID, Integer> centerCounts = new HashMap<>();

    public static GranarySavedData get(ServerLevel overworld) {
        return overworld.getServer().overworld().getDataStorage()
                .computeIfAbsent(GranarySavedData::load, GranarySavedData::new, GranaryConstants.DATA_NAME);
    }

    // ============================================================
    // 读
    // ============================================================

    /** 这个坐标的储粮囤归谁 (没有记录返回 null)。 */
    public UUID ownerOf(ResourceLocation dimension, BlockPos pos) {
        return binOwners.get(new PosKey(dimension, pos.asLong()));
    }

    /** 入册序号 (同一座粮仓里按它决定启用顺序); 没有记录返回 0。 */
    public int seqOf(ResourceLocation dimension, BlockPos pos) {
        return binSeq.getOrDefault(new PosKey(dimension, pos.asLong()), 0);
    }

    /** 某人当前占用的储粮囤数 (全服合计, 派生计数)。 */
    public int binCount(UUID owner) {
        return binCounts.getOrDefault(owner, 0);
    }

    /** 某人当前占用的账台数。 */
    public int centerCount(UUID owner) {
        return centerCounts.getOrDefault(owner, 0);
    }

    // ============================================================
    // 写
    // ============================================================

    /**
     * 登记一个储粮囤的归属。
     *
     * @return 这个坐标原本是别人/别人的记录时返回 false (调用方不该把它当新占用来计数)
     */
    public boolean claimBin(ResourceLocation dimension, BlockPos pos, UUID owner, int seq) {
        PosKey key = new PosKey(dimension, pos.asLong());
        UUID previous = binOwners.putIfAbsent(key, owner);
        if (previous != null) {
            return previous.equals(owner);
        }
        binSeq.put(key, seq);
        binCounts.merge(owner, 1, Integer::sum);
        setDirty();
        return true;
    }

    /** 移除登记并归还额度; 没有记录返回 null。 */
    public UUID releaseBin(ResourceLocation dimension, BlockPos pos) {
        PosKey key = new PosKey(dimension, pos.asLong());
        UUID owner = binOwners.remove(key);
        if (owner == null) {
            return null;
        }
        binSeq.remove(key);
        decrement(binCounts, owner);
        setDirty();
        return owner;
    }

    /** 登记一座账台。 */
    public boolean claimCenter(ResourceLocation dimension, BlockPos pos, UUID owner) {
        PosKey key = new PosKey(dimension, pos.asLong());
        UUID previous = centerOwners.putIfAbsent(key, owner);
        if (previous != null) {
            return previous.equals(owner);
        }
        centerCounts.merge(owner, 1, Integer::sum);
        setDirty();
        return true;
    }

    /** 移除账台登记。 */
    public UUID releaseCenter(ResourceLocation dimension, BlockPos pos) {
        PosKey key = new PosKey(dimension, pos.asLong());
        UUID owner = centerOwners.remove(key);
        if (owner == null) {
            return null;
        }
        decrement(centerCounts, owner);
        setDirty();
        return owner;
    }

    /** 派生计数减一, 归零即清条目 (防内存随玩家基数线性滞留)。 */
    private static void decrement(Map<UUID, Integer> counts, UUID owner) {
        int next = counts.getOrDefault(owner, 0) - 1;
        if (next <= 0) {
            counts.remove(owner);
        } else {
            counts.put(owner, next);
        }
    }

    // ============================================================
    // 存档
    // ============================================================

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt(KEY_VERSION, VERSION);
        ListTag bins = new ListTag();
        binOwners.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(java.util.Comparator
                        .comparing((PosKey key) -> key.dimension().toString())
                        .thenComparingLong(PosKey::packedPos)))
                .forEach(entry -> {
                    CompoundTag bin = new CompoundTag();
                    bin.putString(KEY_DIMENSION, entry.getKey().dimension().toString());
                    bin.putLong(KEY_POS, entry.getKey().packedPos());
                    bin.putUUID(KEY_OWNER, entry.getValue());
                    bin.putInt(KEY_SEQ, binSeq.getOrDefault(entry.getKey(), 0));
                    bins.add(bin);
                });
        tag.put(KEY_BINS, bins);

        ListTag centers = new ListTag();
        centerOwners.forEach((key, owner) -> {
            CompoundTag center = new CompoundTag();
            center.putString(KEY_DIMENSION, key.dimension().toString());
            center.putLong(KEY_POS, key.packedPos());
            center.putUUID(KEY_OWNER, owner);
            centers.add(center);
        });
        tag.put(KEY_CENTERS, centers);
        return tag;
    }

    public static GranarySavedData load(CompoundTag tag) {
        if (!tag.contains(KEY_VERSION, Tag.TAG_INT) || tag.getInt(KEY_VERSION) != VERSION) {
            throw new IllegalArgumentException("Invalid granary save header");
        }
        GranarySavedData data = new GranarySavedData();
        ListTag bins = requireList(tag, KEY_BINS, Tag.TAG_COMPOUND);
        for (int i = 0; i < bins.size(); i++) {
            CompoundTag bin = bins.getCompound(i);
            PosKey key = keyOf(bin);
            if (!bin.hasUUID(KEY_OWNER)) {
                throw new IllegalArgumentException("Granary bin owner is missing");
            }
            UUID owner = bin.getUUID(KEY_OWNER);
            if (data.binOwners.putIfAbsent(key, owner) != null) {
                throw new IllegalArgumentException("Duplicate granary bin at " + key.dimension() + " " + key.packedPos());
            }
            data.binSeq.put(key, bin.getInt(KEY_SEQ));
            data.binCounts.merge(owner, 1, Integer::sum);
        }
        ListTag centers = requireList(tag, KEY_CENTERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < centers.size(); i++) {
            CompoundTag center = centers.getCompound(i);
            PosKey key = keyOf(center);
            if (!center.hasUUID(KEY_OWNER)) {
                throw new IllegalArgumentException("Granary center owner is missing");
            }
            UUID owner = center.getUUID(KEY_OWNER);
            if (data.centerOwners.putIfAbsent(key, owner) != null) {
                throw new IllegalArgumentException("Duplicate granary center");
            }
            data.centerCounts.merge(owner, 1, Integer::sum);
        }
        return data;
    }

    private static PosKey keyOf(CompoundTag entry) {
        if (!entry.contains(KEY_DIMENSION, Tag.TAG_STRING) || !entry.contains(KEY_POS, Tag.TAG_LONG)) {
            throw new IllegalArgumentException("Granary entry is missing its position");
        }
        return new PosKey(new ResourceLocation(entry.getString(KEY_DIMENSION)), entry.getLong(KEY_POS));
    }

    private static ListTag requireList(CompoundTag tag, String key, int elementType) {
        if (!(tag.get(key) instanceof ListTag list)
                || (!list.isEmpty() && list.getElementType() != elementType)) {
            throw new IllegalArgumentException("Invalid granary list: " + key);
        }
        return list;
    }
}
