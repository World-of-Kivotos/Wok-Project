package com.miningdim.core;

import net.minecraft.nbt.CompoundTag;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单个矿山实例的运行时权威视图 (设计文档 12.1)。InstanceManager 单例持有, 其持久副本由 SavedData 落盘。
 *
 * 线程纪律 (D8/12.4): 字段的写入 (playerSet 增删、genState/active 翻转、tick 标记) 只在服务端主线程发生;
 * 工作线程的离线生成回调经 server.execute 串行回主线程后才改 genState。volatile/并发集合是对
 * "读侧可能跨线程" (如调试快照、日志、生成线程读 seed/regionBox 等不可变量) 的防御, 不替代主线程串行写。
 * difficulty / instanceId 在创建后不可变, 为 final。seed / regionBox 会在滑动重置 (13.4/D3) 时被主线程
 * 经 relocate() 改写一次, 故为 volatile —— 任何把 regionBox 缓存进长生命周期对象的子系统必须在实例重置后
 * (经 IInstanceResetListener) 重建缓存, 否则读到陈旧几何。
 */
public final class InstanceState {

    /** 持久自增主键, 全 mod 唯一, 不复用 (12.4)。 */
    private final long instanceId;

    /** 实例确定性种子 (SeedUtil.deriveSeed 产出); 滑动重置时随 regionBox 一并改写, 见 relocate()。 */
    private volatile long seed;

    /** 难度档, 决定矿物/陷阱/压力参数。 */
    private final Difficulty difficulty;

    /** 该实例独占的 region 包围盒 (区块对齐, 实例间留缓冲带); 滑动重置时改写, 见 relocate()。 */
    private volatile RegionBox regionBox;

    /** 私有实例归属键 (玩家 UUID 或队伍 id 的 UUID 形式); 共享实例为 null。 */
    private final UUID ownerKey;

    /** 是否共享实例。 */
    private final boolean shared;

    /** 创建时的 server game time。 */
    private final long createdTick;

    /** 当前在场玩家集合 (并发安全); refCount == size()。 */
    private final Set<UUID> playerSet = ConcurrentHashMap.newKeySet();

    /** 当前实例内存活的、由本 mod 压力系统显式生成的 mob (第十章硬上限计数)。 */
    private final Set<UUID> liveMobs = ConcurrentHashMap.newKeySet();

    /** 离线生成/重置状态; 跨线程读, 主线程写。 */
    private volatile GenState genState;

    /** 最近一次 refCount 归零的 tick; 非空 (有人在场) 时为 -1 (12.6 GC)。 */
    private volatile long lastEmptyTick;

    /** playerSet 非空即 active, 控制是否 tick 压力/陷阱 (12.7)。 */
    private volatile boolean active;

    public InstanceState(long instanceId, long seed, Difficulty difficulty, RegionBox regionBox,
                         UUID ownerKey, boolean shared, long createdTick, GenState genState) {
        this.instanceId = instanceId;
        this.seed = seed;
        this.difficulty = difficulty;
        this.regionBox = regionBox;
        this.ownerKey = ownerKey;
        this.shared = shared;
        this.createdTick = createdTick;
        this.genState = genState;
        this.lastEmptyTick = -1L;
        this.active = false;
    }

    // ---- 不可变字段访问 ----

    public long instanceId() {
        return instanceId;
    }

    public long seed() {
        return seed;
    }

    public Difficulty difficulty() {
        return difficulty;
    }

    public RegionBox regionBox() {
        return regionBox;
    }

    /**
     * 滑动重置: 把实例整块搬到一块从未生成过的新 region 并换种子 (13.4/D3)。
     * 仅主线程, 仅 InstanceManager.slideRegion 调用。
     * @throws IllegalArgumentException newRegionBox 为 null
     */
    public void relocate(RegionBox newRegionBox, long newSeed) {
        if (newRegionBox == null) {
            throw new IllegalArgumentException("InstanceState.relocate: newRegionBox must not be null");
        }
        this.regionBox = newRegionBox;
        this.seed = newSeed;
    }

    /** 私有实例归属键; 共享实例为 null。 */
    public UUID ownerKey() {
        return ownerKey;
    }

    public boolean shared() {
        return shared;
    }

    public long createdTick() {
        return createdTick;
    }

    // ---- 可变运行态 (主线程写) ----

    public GenState genState() {
        return genState;
    }

    public void setGenState(GenState genState) {
        this.genState = genState;
    }

    public long lastEmptyTick() {
        return lastEmptyTick;
    }

    public void setLastEmptyTick(long tick) {
        this.lastEmptyTick = tick;
    }

    public boolean active() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /** 当前在场玩家集合 (并发安全视图, 可直接增删)。 */
    public Set<UUID> playerSet() {
        return playerSet;
    }

    /** 本 mod 压力系统生成的存活 mob 集合 (第十章硬上限计数)。 */
    public Set<UUID> liveMobs() {
        return liveMobs;
    }

    /** 引用计数 = 在场玩家数 (派生, 不独立维护, 12.6 杜绝漂移)。 */
    public int refCount() {
        return playerSet.size();
    }

    // ---- 持久化 (供 SavedData; 持久化字段对齐 12.1/12.5) ----

    private static final String K_ID = "instanceId";
    private static final String K_SEED = "seed";
    private static final String K_DIFFICULTY = "difficulty";
    private static final String K_OX = "regionOriginX";
    private static final String K_OY = "regionOriginY";
    private static final String K_OZ = "regionOriginZ";
    private static final String K_SX = "regionSizeX";
    private static final String K_SY = "regionSizeY";
    private static final String K_SZ = "regionSizeZ";
    private static final String K_OWNER_MOST = "ownerKeyMost";
    private static final String K_OWNER_LEAST = "ownerKeyLeast";
    private static final String K_HAS_OWNER = "hasOwner";
    private static final String K_SHARED = "shared";
    private static final String K_CREATED = "createdTick";
    private static final String K_LAST_EMPTY = "lastEmptyTick";
    private static final String K_GEN_STATE = "genState";
    private static final String K_PLAYERS = "playerSet";

    /**
     * 序列化为 CompoundTag。playerSet 持久化 (12.1 标"是"), 重启后启动重建期会被重置为空、
     * refCount 归零 (12.8); 但落盘保留以便调试与崩溃前现场审计。liveMobs 不持久化 (重启后实体已卸载)。
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong(K_ID, instanceId);
        tag.putLong(K_SEED, seed);
        tag.putInt(K_DIFFICULTY, difficulty.id());
        tag.putInt(K_OX, regionBox.originX());
        tag.putInt(K_OY, regionBox.originY());
        tag.putInt(K_OZ, regionBox.originZ());
        tag.putInt(K_SX, regionBox.sizeX());
        tag.putInt(K_SY, regionBox.sizeY());
        tag.putInt(K_SZ, regionBox.sizeZ());
        tag.putBoolean(K_HAS_OWNER, ownerKey != null);
        if (ownerKey != null) {
            tag.putLong(K_OWNER_MOST, ownerKey.getMostSignificantBits());
            tag.putLong(K_OWNER_LEAST, ownerKey.getLeastSignificantBits());
        }
        tag.putBoolean(K_SHARED, shared);
        tag.putLong(K_CREATED, createdTick);
        tag.putLong(K_LAST_EMPTY, lastEmptyTick);
        tag.putString(K_GEN_STATE, genState.name());

        net.minecraft.nbt.ListTag players = new net.minecraft.nbt.ListTag();
        for (UUID id : playerSet) {
            CompoundTag p = new CompoundTag();
            p.putLong("most", id.getMostSignificantBits());
            p.putLong("least", id.getLeastSignificantBits());
            players.add(p);
        }
        tag.put(K_PLAYERS, players);
        return tag;
    }

    /**
     * 从 CompoundTag 还原。genState 遇未知名按 FAILED 兜底 (枚举超集演进的前向兼容)。
     * lastEmptyTick / active 不在此重置, 由启动重建逻辑 (12.8) 决定置空与否。
     */
    public static InstanceState load(CompoundTag tag) {
        long id = tag.getLong(K_ID);
        long seed = tag.getLong(K_SEED);
        Difficulty difficulty = Difficulty.byId(tag.getInt(K_DIFFICULTY));
        // F088 存档迁移: 旧存档写的 regionBox 是 384 高 (originY=-64, sizeY=384), 而世界只有 REGION_HEIGHT(192)
        // 高; originY/sizeY 一律归一到当前几何常量, X/Z 的 origin/size 原样保留 (不是业务兜底, 是几何对齐迁移)。
        RegionBox box = new RegionBox(
                tag.getInt(K_OX), MiningConstants.REGION_MIN_Y, tag.getInt(K_OZ),
                tag.getInt(K_SX), MiningConstants.REGION_HEIGHT, tag.getInt(K_SZ));
        UUID owner = null;
        if (tag.getBoolean(K_HAS_OWNER)) {
            owner = new UUID(tag.getLong(K_OWNER_MOST), tag.getLong(K_OWNER_LEAST));
        }
        boolean shared = tag.getBoolean(K_SHARED);
        long created = tag.getLong(K_CREATED);

        GenState state;
        try {
            state = GenState.valueOf(tag.getString(K_GEN_STATE));
        } catch (IllegalArgumentException unknownEnum) {
            state = GenState.FAILED;
        }

        InstanceState st = new InstanceState(id, seed, difficulty, box, owner, shared, created, state);
        st.lastEmptyTick = tag.getLong(K_LAST_EMPTY);

        net.minecraft.nbt.ListTag players = tag.getList(K_PLAYERS, net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag p = players.getCompound(i);
            st.playerSet.add(new UUID(p.getLong("most"), p.getLong("least")));
        }
        st.active = !st.playerSet.isEmpty();
        return st;
    }

    /** 只读快照里偶尔需要不可变 playerSet 拷贝 (调试用)。 */
    public Set<UUID> playerSetSnapshot() {
        return Collections.unmodifiableSet(Set.copyOf(playerSet));
    }
}
