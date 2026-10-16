package com.miningdim.job.farmer.granary;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 粮仓的归属账本 (Spoilage_Granary_DesignSpec「额度怎么记账」「容量怎么算」「物品数据与存档」)。
 *
 * 落三件事: 储粮囤与账台的**归属索引**、每座粮仓的**登记项** (房主/档位/验收人/状态)、以及每个囤的
 * **启用状态**。所有人的囤数都是派生计数, 不另存一份总数 (两份数会对不上)。规矩照抄耕地那一套 (F025):
 * 归属按 (维度, 打包坐标) 存, 方块被移除才归还; 谁的等级降了、额度超了, 由上层按派生计数处置。
 *
 * 设计文档里 GranarySavedData 的其余字段 (成员表/待领粮单/levelCache/trustDefaults/audit) 随它们各自的功能分片
 * 加入; 现在没有的功能就不预埋空字段 —— 预埋的字段一旦发布同样不能改, 反而更贵。
 */
public final class GranarySavedData extends SavedData {

    /** 归属索引的键: 维度 + 打包坐标。 */
    public record PosKey(ResourceLocation dimension, long packedPos) {

        public PosKey(ResourceLocation dimension, BlockPos pos) {
            this(dimension, pos.asLong());
        }

        public BlockPos pos() {
            return BlockPos.of(packedPos);
        }
    }

    private final Map<PosKey, UUID> binOwners = new HashMap<>();
    private final Map<PosKey, Integer> binSeq = new HashMap<>();
    private final Map<PosKey, UUID> centerOwners = new HashMap<>();
    private final Map<UUID, Integer> binCounts = new HashMap<>();
    private final Map<UUID, Integer> centerCounts = new HashMap<>();
    /** 粮仓登记项: gid → 房主、档位、验收人、状态。 */
    private final Map<Long, GranaryRecord> granaries = new HashMap<>();
    /** 每个储粮囤属于哪座粮仓 (0 = 还没连上任何粮仓)。 */
    private final Map<PosKey, Long> binGranary = new HashMap<>();
    /** 每个储粮囤的启用状态 (启用 / 待启用 / 停用); 没有记录 = 待启用。 */
    private final Map<PosKey, GranaryBinState> binStates = new HashMap<>();
    /** 每座账台开的是哪座粮仓。 */
    private final Map<PosKey, Long> centerGranary = new HashMap<>();
    private long nextGid = 1L;
    private int nextSeq = 1;

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
    // 粮仓登记项与囤的归属 / 启用状态
    // ============================================================

    /** 取一个新的粮仓编号。 */
    public long nextGranaryId() {
        long id = nextGid++;
        setDirty();
        return id;
    }

    /**
     * 取一个新的入册序号。
     *
     * 序号是**全服单调递增**的, 不是「某人第几个囤」—— 启用顺序按它排, 多人合建时才能在同一个序列里
     * 分出先后 (同一座粮仓里不同人的序号不能撞车)。
     */
    public int nextSeq() {
        int seq = nextSeq++;
        setDirty();
        return seq;
    }

    public GranaryRecord granary(long gid) {
        return granaries.get(gid);
    }

    public void putGranary(GranaryRecord record) {
        granaries.put(record.id(), record);
        setDirty();
    }

    /**
     * 解散一座粮仓 (账台没了 / 被拆): 登记项丢掉, 里面所有囤的连线与启用状态一并清掉。
     *
     * 囤本身**不动** —— 归属还是各囤主的, 只是重新变成「没连上任何粮仓」的散囤 (只能取不能存)。
     */
    public void removeGranary(long gid) {
        if (granaries.remove(gid) == null) {
            return;
        }
        for (PosKey key : binsOf(gid)) {
            clearBinLinks(key);
        }
        centerGranary.entrySet().removeIf(entry -> entry.getValue() == gid);
        setDirty();
    }

    /** 这座账台开的是哪座粮仓 (0 = 还没开号); 账本是权威, 方块实体上那份只是给界面看的镜像。 */
    public long centerGranary(ResourceLocation dimension, BlockPos pos) {
        return centerGranary.getOrDefault(new PosKey(dimension, pos.asLong()), 0L);
    }

    /** 某座粮仓里的储粮囤 (按入册序号从早到晚)。 */
    public List<PosKey> binsOf(long gid) {
        List<PosKey> result = new ArrayList<>();
        binGranary.forEach((key, value) -> {
            if (value != null && value == gid && binOwners.containsKey(key)) {
                result.add(key);
            }
        });
        result.sort(Comparator.comparingInt(key -> binSeq.getOrDefault(key, 0)));
        return result;
    }

    /** 这个囤连到哪座粮仓 (0 = 没连上)。 */
    public long granaryOfBin(ResourceLocation dimension, BlockPos pos) {
        return binGranary.getOrDefault(new PosKey(dimension, pos.asLong()), 0L);
    }

    public void linkBin(ResourceLocation dimension, BlockPos pos, long gid) {
        binGranary.put(new PosKey(dimension, pos.asLong()), gid);
        setDirty();
    }

    /**
     * 这个囤的启用状态 (没记过的一律按**待启用**算 —— 新放的囤要等下一次结构判定才可能转成启用)。
     */
    public GranaryBinState binState(ResourceLocation dimension, BlockPos pos) {
        return binStates.getOrDefault(new PosKey(dimension, pos.asLong()), GranaryBinState.PENDING);
    }

    /** 只能取不能存 (待启用与停用都是)。 */
    public boolean isBinInactive(ResourceLocation dimension, BlockPos pos) {
        return !binState(dimension, pos).acceptsDeposits();
    }

    public void setBinState(ResourceLocation dimension, BlockPos pos, GranaryBinState state) {
        PosKey key = new PosKey(dimension, pos.asLong());
        if (binStates.get(key) == state) {
            return;
        }
        binStates.put(key, state);
        setDirty();
    }

    /** 拆掉 / 打包一个囤时, 把它的粮仓连线与启用状态一并清掉。 */
    private void clearBinLinks(PosKey key) {
        binGranary.remove(key);
        binStates.remove(key);
    }

    /**
     * 按入册先后把「撑不住的囤」排出启用名单 (Spoilage_Granary_DesignSpec「容量怎么算」)。
     *
     * 每次结构判定之后调一次: 前 enabled 个启用, 后面的排不上 —— 这里区分两种排不上,
     * **原来启用的转「停用」**(东西照常腐烂, 但要走宽限期), **从来没启用的保持「待启用」**
     * (新放进来的, 一进来就只是不能存)。部件补回来时排上的又转回启用。
     *
     * 只管「部件撑不撑得住」这一条轴: 超额、未入册、未验收这些轴由调用方在传 supportedBins 之前扣掉。
     */
    public GranaryEnablement.Decision applyEnablement(long gid, int supportedBins) {
        List<PosKey> bins = binsOf(gid);
        GranaryEnablement.Decision decision = GranaryEnablement.decide(bins.size(), supportedBins);
        for (int index = 0; index < bins.size(); index++) {
            PosKey key = bins.get(index);
            GranaryBinState state;
            if (decision.isActive(index)) {
                state = GranaryBinState.ACTIVE;
            } else {
                state = binState(key.dimension(), key.pos()) == GranaryBinState.ACTIVE
                        ? GranaryBinState.STOPPED
                        : GranaryBinState.PENDING;
            }
            setBinState(key.dimension(), key.pos(), state);
        }
        return decision;
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
        clearBinLinks(key);
        decrement(binCounts, owner);
        setDirty();
        return owner;
    }

    /** 登记一座账台, 并记下它开的是哪座粮仓。 */
    public boolean claimCenter(ResourceLocation dimension, BlockPos pos, UUID owner, long gid) {
        PosKey key = new PosKey(dimension, pos.asLong());
        UUID previous = centerOwners.putIfAbsent(key, owner);
        if (previous != null) {
            return previous.equals(owner);
        }
        centerCounts.merge(owner, 1, Integer::sum);
        centerGranary.put(key, gid);
        setDirty();
        return true;
    }

    /**
     * 移除账台登记。
     *
     * @return 这座账台开的粮仓编号 (0 = 没开过号); 调用方拿它去解散粮仓 —— 账本是权威, 不看方块实体还在不在
     */
    public long releaseCenter(ResourceLocation dimension, BlockPos pos) {
        PosKey key = new PosKey(dimension, pos.asLong());
        Long gid = centerGranary.remove(key);
        UUID owner = centerOwners.remove(key);
        if (owner == null) {
            return 0L;
        }
        decrement(centerCounts, owner);
        setDirty();
        return gid == null ? 0L : gid;
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
        tag.putInt(GranaryConstants.KEY_VERSION, GranaryConstants.SAVE_VERSION);
        ListTag bins = new ListTag();
        binOwners.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator
                        .comparing((PosKey key) -> key.dimension().toString())
                        .thenComparingLong(PosKey::packedPos)))
                .forEach(entry -> {
                    CompoundTag bin = new CompoundTag();
                    bin.putString(GranaryConstants.KEY_DIMENSION, entry.getKey().dimension().toString());
                    bin.putLong(GranaryConstants.KEY_POS, entry.getKey().packedPos());
                    bin.putUUID(GranaryConstants.KEY_OWNER, entry.getValue());
                    bin.putInt(GranaryConstants.KEY_SEQ, binSeq.getOrDefault(entry.getKey(), 0));
                    bins.add(bin);
                });
        tag.put(GranaryConstants.KEY_BINS, bins);

        ListTag centers = new ListTag();
        centerOwners.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator
                        .comparing((PosKey key) -> key.dimension().toString())
                        .thenComparingLong(PosKey::packedPos)))
                .forEach(entry -> {
                    CompoundTag center = new CompoundTag();
                    center.putString(GranaryConstants.KEY_DIMENSION, entry.getKey().dimension().toString());
                    center.putLong(GranaryConstants.KEY_POS, entry.getKey().packedPos());
                    center.putUUID(GranaryConstants.KEY_OWNER, entry.getValue());
                    center.putLong(GranaryConstants.KEY_GID, centerGranary.getOrDefault(entry.getKey(), 0L));
                    centers.add(center);
                });
        tag.put(GranaryConstants.KEY_CENTERS, centers);

        ListTag granaryList = new ListTag();
        granaries.values().stream().sorted(Comparator.comparingLong(GranaryRecord::id))
                .forEach(record -> granaryList.add(record.save()));
        tag.put(GranaryConstants.KEY_GRANARIES, granaryList);

        ListTag links = new ListTag();
        binGranary.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .forEach(entry -> {
                    CompoundTag link = new CompoundTag();
                    link.putString(GranaryConstants.KEY_DIMENSION, entry.getKey().dimension().toString());
                    link.putLong(GranaryConstants.KEY_POS, entry.getKey().packedPos());
                    link.putLong(GranaryConstants.KEY_GID, entry.getValue());
                    links.add(link);
                });
        tag.put(GranaryConstants.KEY_BIN_GRANARY, links);

        ListTag states = new ListTag();
        binStates.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator
                        .comparing((PosKey key) -> key.dimension().toString())
                        .thenComparingLong(PosKey::packedPos)))
                .forEach(entry -> {
                    CompoundTag state = new CompoundTag();
                    state.putString(GranaryConstants.KEY_DIMENSION, entry.getKey().dimension().toString());
                    state.putLong(GranaryConstants.KEY_POS, entry.getKey().packedPos());
                    state.putString(GranaryConstants.KEY_STATE, entry.getValue().name());
                    states.add(state);
                });
        tag.put(GranaryConstants.KEY_BIN_STATES, states);
        tag.putLong(GranaryConstants.KEY_NEXT_GID, nextGid);
        tag.putInt(GranaryConstants.KEY_NEXT_SEQ, nextSeq);
        return tag;
    }

    public static GranarySavedData load(CompoundTag tag) {
        if (!tag.contains(GranaryConstants.KEY_VERSION, Tag.TAG_INT) || tag.getInt(GranaryConstants.KEY_VERSION) != GranaryConstants.SAVE_VERSION) {
            throw new IllegalArgumentException("Invalid granary save header");
        }
        GranarySavedData data = new GranarySavedData();
        ListTag bins = requireList(tag, GranaryConstants.KEY_BINS, Tag.TAG_COMPOUND);
        int highestSeq = 0;
        for (int i = 0; i < bins.size(); i++) {
            CompoundTag bin = bins.getCompound(i);
            PosKey key = keyOf(bin);
            if (!bin.hasUUID(GranaryConstants.KEY_OWNER)) {
                throw new IllegalArgumentException("Granary bin owner is missing");
            }
            UUID owner = bin.getUUID(GranaryConstants.KEY_OWNER);
            if (data.binOwners.putIfAbsent(key, owner) != null) {
                throw new IllegalArgumentException("Duplicate granary bin at " + key.dimension() + " " + key.packedPos());
            }
            data.binSeq.put(key, bin.getInt(GranaryConstants.KEY_SEQ));
            highestSeq = Math.max(highestSeq, bin.getInt(GranaryConstants.KEY_SEQ));
            data.binCounts.merge(owner, 1, Integer::sum);
        }
        ListTag centers = requireList(tag, GranaryConstants.KEY_CENTERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < centers.size(); i++) {
            CompoundTag center = centers.getCompound(i);
            PosKey key = keyOf(center);
            if (!center.hasUUID(GranaryConstants.KEY_OWNER)) {
                throw new IllegalArgumentException("Granary center owner is missing");
            }
            UUID owner = center.getUUID(GranaryConstants.KEY_OWNER);
            if (data.centerOwners.putIfAbsent(key, owner) != null) {
                throw new IllegalArgumentException("Duplicate granary center");
            }
            data.centerCounts.merge(owner, 1, Integer::sum);
            long gid = center.contains(GranaryConstants.KEY_GID, Tag.TAG_LONG)
                    ? center.getLong(GranaryConstants.KEY_GID) : 0L;
            if (gid != 0L) {
                data.centerGranary.put(key, gid);
            }
        }
        ListTag granaryList = requireList(tag, GranaryConstants.KEY_GRANARIES, Tag.TAG_COMPOUND);
        long highestId = 0L;
        for (int i = 0; i < granaryList.size(); i++) {
            GranaryRecord record = GranaryRecord.load(granaryList.getCompound(i));
            if (data.granaries.putIfAbsent(record.id(), record) != null) {
                throw new IllegalArgumentException("Duplicate granary id: " + record.id());
            }
            highestId = Math.max(highestId, record.id());
        }
        // 连线与启用状态是**派生索引**: 指向不存在的囤或粮仓时直接丢掉 (自愈), 不让存档读不进来 ——
        // 归属记录才是账, 其余都从它推出来。
        ListTag links = requireList(tag, GranaryConstants.KEY_BIN_GRANARY, Tag.TAG_COMPOUND);
        for (int i = 0; i < links.size(); i++) {
            CompoundTag link = links.getCompound(i);
            PosKey key = keyOf(link);
            long gid = link.contains(GranaryConstants.KEY_GID, Tag.TAG_LONG) ? link.getLong(GranaryConstants.KEY_GID) : 0L;
            if (gid != 0L && data.granaries.containsKey(gid) && data.binOwners.containsKey(key)) {
                data.binGranary.put(key, gid);
            }
        }
        ListTag states = requireList(tag, GranaryConstants.KEY_BIN_STATES, Tag.TAG_COMPOUND);
        for (int i = 0; i < states.size(); i++) {
            CompoundTag state = states.getCompound(i);
            PosKey key = keyOf(state);
            GranaryBinState parsed = parseState(state.getString(GranaryConstants.KEY_STATE));
            if (data.binOwners.containsKey(key)) {
                data.binStates.put(key, parsed);
            }
        }
        // 编号水位宁可往高取: NextGid 落后于已用编号时 (回档或手工改档) 复用编号会把两座粮仓并成一座。
        long storedNext = tag.contains(GranaryConstants.KEY_NEXT_GID, Tag.TAG_LONG) ? tag.getLong(GranaryConstants.KEY_NEXT_GID) : 0L;
        data.nextGid = Math.max(Math.max(storedNext, highestId + 1), 1L);
        // 入册序号同理: 落后了就让先入册的囤排到后入册的后面, 启用顺序会反过来。
        int storedSeq = tag.contains(GranaryConstants.KEY_NEXT_SEQ, Tag.TAG_INT)
                ? tag.getInt(GranaryConstants.KEY_NEXT_SEQ) : 0;
        data.nextSeq = Math.max(Math.max(storedSeq, highestSeq + 1), 1);
        return data;
    }

    private static PosKey keyOf(CompoundTag entry) {
        if (!entry.contains(GranaryConstants.KEY_DIMENSION, Tag.TAG_STRING)
                || !entry.contains(GranaryConstants.KEY_POS, Tag.TAG_LONG)) {
            throw new IllegalArgumentException("Granary entry is missing its position");
        }
        return new PosKey(new ResourceLocation(entry.getString(GranaryConstants.KEY_DIMENSION)),
                entry.getLong(GranaryConstants.KEY_POS));
    }

    /** 启用状态存的是枚举名; 认不出来的名字是坏档, 直接报错而不是悄悄按待启用放过去。 */
    private static GranaryBinState parseState(String name) {
        for (GranaryBinState candidate : GranaryBinState.values()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Invalid granary bin state: " + name);
    }

    private static ListTag requireList(CompoundTag tag, String key, int elementType) {
        if (!(tag.get(key) instanceof ListTag list)
                || (!list.isEmpty() && list.getElementType() != elementType)) {
            throw new IllegalArgumentException("Invalid granary list: " + key);
        }
        return list;
    }
}
