package com.miningdim.persistence;

import com.miningdim.core.Difficulty;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * 实例注册表与全局计数器的持久层 (设计文档 12.5 第一层)。挂在矿山维度的 DimensionDataStorage,
 * 数据文件名 {@value #DATA_NAME}。承载: 实例注册表 Map&lt;Long,InstanceState&gt;、持久自增主键
 * nextInstanceId、确定性种子源 globalSeed、累计重置代数 resetGeneration、region 占用网格位图。
 *
 * 线程纪律 (D8/12.4): 本类只在服务端主线程读写; 任何字段变更后必须 setDirty(), 否则不落盘
 * (12.5 标 Major 漏点)。InstanceManager 是唯一合法的写入者, 经它的主线程串行通道访问本类。
 *
 * 与 core.InstanceState 的分工: 单实例字段的 NBT 编解码由 InstanceState.save()/load() 负责
 * (12.1 持久化列), 本类只负责"多实例集合 + 全局计数 + 网格位图"这一层的聚合编解码, 不重复实现单实例字段。
 */
public final class MiningSavedData extends SavedData {

    /** DimensionDataStorage 中的数据文件名 (12.5)。 */
    public static final String DATA_NAME = "miningdim_instances";

    private static final String K_NEXT_ID = "nextInstanceId";
    private static final String K_GLOBAL_SEED = "globalSeed";
    private static final String K_HAS_GLOBAL_SEED = "hasGlobalSeed";
    private static final String K_RESET_GEN = "resetGeneration";
    private static final String K_INSTANCES = "instances";
    private static final String K_REGION_OCCUPANCY = "regionOccupancy";
    private static final String K_REGION_FRONTIER_X = "regionFrontierX";
    private static final String K_HAS_REGION_FRONTIER = "hasRegionFrontier";
    private static final String K_FIXED_INSTANCE_PREFIX = "fixedInstance_";
    private static final String K_RETIRED_REGIONS = "retiredRegions";
    private static final String K_RETIRED_ORIGIN_X = "originX";
    private static final String K_RETIRED_ORIGIN_Z = "originZ";
    private static final String K_RETIRED_CURSOR = "clearedChunks";

    /** 持久自增主键, 从 1 起 (0 保留为"无效/未分配"语义)。绝不复用已销毁 id (12.4)。 */
    private long nextInstanceId = 1L;

    /** 全局确定性种子, 矿山维度首次创建时确定并持久化, 全程不变 (12.4)。 */
    private long globalSeed;

    /** globalSeed 是否已初始化; 首次访问由 InstanceManager 用存档主 seed 派生注入。 */
    private boolean hasGlobalSeed;

    /**
     * 全 mod 累计重置代数计数器 (NEW_SEED 重置时 +1, 供 ResetService 派生新种子)。
     * 注: 12.5 实例字段表未把 per-instance resetGeneration 列入持久列, 故重置代数以全局计数器形式
     * 持久化于本层; 由 IInstanceManager.deriveNextResetSeed 消费经 SeedUtil.deriveSeed 派生。
     * SAME_SEED 重置不动此值。
     */
    private int resetGeneration;

    /** 实例注册表: instanceId -> 运行时状态。运行态由 InstanceState 自身持有, 本 Map 是其集合容器。 */
    private final Map<Long, InstanceState> instances = new HashMap<>();

    /**
     * region 网格占用位图 (12.5/12.8): 第 i 位为 1 表示第 i 个网格槽已被占用。
     * 槽编号与几何映射由 instance.RegionGrid 单一权威定义, 本类只存裸字节, 不解释语义。
     */
    private byte[] regionOccupancy = new byte[0];

    /** 滑动 region 分配游标的世界 X 原点 (13.4/D3); 未初始化前 hasRegionFrontier=false。 */
    private int regionFrontierX;

    /** regionFrontierX 是否已确定初始值。 */
    private boolean hasRegionFrontier;

    /**
     * 三固定难度实例的持久 id (F088 认领问题: region 一滑走, ensureFixedInstances 就不能再靠
     * regionBox.equals(固定几何) 认领固定实例, 每次重启会再造三个新实例; 改由本表按难度直接查 id)。
     */
    private final Map<Difficulty, Long> fixedInstanceIds = new HashMap<>();

    /**
     * 已退役 region 的待回收队列 (滑动重置的磁盘代价回收)。
     *
     * 滑动 region 方案每次重置把实例挪到一块从未生成过的新坐标, 旧坐标那 16x16 个区块原样留在 .mca 里 ——
     * 不回收就是每次重置泄漏一整块 region 的磁盘。本队列由 InstanceManager.slideRegion 在换几何时登记,
     * 由 RetiredRegionGc 分批清除后出队。
     *
     * 带 cursor 而非只记坐标: 一块 region 有 256 个区块, GC 分多个 tick 清, 停服重启后必须能接着清而不是从头
     * 再来 (从头再来对已清的区块是白发一遍 IO), 也不能就此漏掉未清的那部分。
     */
    private final java.util.List<RetiredRegion> retiredRegions = new java.util.ArrayList<>();

    /** 一块待回收的退役 region: 原点 + 已清区块数游标。 */
    public static final class RetiredRegion {
        private final int originX;
        private final int originZ;
        private int clearedChunks;

        RetiredRegion(int originX, int originZ, int clearedChunks) {
            this.originX = originX;
            this.originZ = originZ;
            this.clearedChunks = clearedChunks;
        }

        public int originX() {
            return originX;
        }

        public int originZ() {
            return originZ;
        }

        public int clearedChunks() {
            return clearedChunks;
        }
    }

    public MiningSavedData() {
    }

    /**
     * 取/建本维度的实例持久数据。1.20.1 的 computeIfAbsent 签名为
     * (Function&lt;CompoundTag,T&gt; load, Supplier&lt;T&gt; create, String name) —— SavedData.Factory
     * 是 1.20.2+ 才引入, 本目标版本不可用 (已校验)。必须传矿山维度的 ServerLevel, 数据随该维度存档落盘 (12.5)。
     */
    public static MiningSavedData get(ServerLevel miningLevel) {
        return miningLevel.getDataStorage().computeIfAbsent(
                MiningSavedData::load, MiningSavedData::new, DATA_NAME);
    }

    // ---- 全局计数器 (12.4) ----

    /**
     * 分配下一个 instanceId: 读当前值、自增、标脏。绝不复用已销毁 id (12.4)。
     * 仅主线程调用 (由 InstanceManager 串行)。
     */
    public long allocateInstanceId() {
        long id = nextInstanceId;
        nextInstanceId = id + 1L;
        setDirty();
        return id;
    }

    /** 当前 nextInstanceId 只读视图 (调试/重建校验用, 不自增)。 */
    public long peekNextInstanceId() {
        return nextInstanceId;
    }

    public boolean hasGlobalSeed() {
        return hasGlobalSeed;
    }

    public long globalSeed() {
        return globalSeed;
    }

    /** 首次初始化 globalSeed (矿山维度创建时); 已初始化则忽略, 保证全程不变 (12.4)。 */
    public void initGlobalSeedIfAbsent(long seed) {
        if (!hasGlobalSeed) {
            this.globalSeed = seed;
            this.hasGlobalSeed = true;
            setDirty();
        }
    }

    public int resetGeneration() {
        return resetGeneration;
    }

    /** 累计重置代数 +1 并返回新值 (NEW_SEED 重置专用, SAME_SEED 不调用)。 */
    public int incrementResetGeneration() {
        resetGeneration += 1;
        setDirty();
        return resetGeneration;
    }

    // ---- 实例注册表 ----

    public InstanceState getInstance(long instanceId) {
        return instances.get(instanceId);
    }

    /** 登记一个实例 (createInstance 末尾调用)。 */
    public void putInstance(InstanceState state) {
        instances.put(state.instanceId(), state);
        setDirty();
    }

    /** 移除一个实例 (GC 销毁/运维清理)。 */
    public InstanceState removeInstance(long instanceId) {
        InstanceState removed = instances.remove(instanceId);
        if (removed != null) {
            setDirty();
        }
        return removed;
    }

    /** 实例注册表的直接视图 (InstanceManager 重建/遍历用; 修改后须调用方自行 setDirty)。 */
    public Map<Long, InstanceState> instances() {
        return instances;
    }

    public int instanceCount() {
        return instances.size();
    }

    // ---- region 占用位图 ----

    public byte[] regionOccupancy() {
        return regionOccupancy;
    }

    /** 覆写占用位图 (RegionGrid claim/free 后同步落盘)。 */
    public void setRegionOccupancy(byte[] bytes) {
        this.regionOccupancy = bytes;
        setDirty();
    }

    // ---- 滑动 region 分配游标 (13.4/D3) ----

    public boolean hasRegionFrontier() {
        return hasRegionFrontier;
    }

    public int regionFrontierX() {
        return regionFrontierX;
    }

    /** 首次确定滑动游标起点 (= 初始三块 region 的最右边界 + SLIDE_SEPARATION_BLOCKS); 已初始化则忽略。 */
    public void initRegionFrontierIfAbsent(int worldX) {
        if (!hasRegionFrontier) {
            this.regionFrontierX = worldX;
            this.hasRegionFrontier = true;
            setDirty();
        }
    }

    /**
     * 取下一块从未使用过的 region 世界 X 原点并把游标推进 (regionSizeX + separationBlocks)。
     * 游标未初始化, 或推进后越过 MiningConstants.MAX_REGION_WORLD_X, 一律抛 IllegalStateException 暴露,
     * 严禁绕回复用旧坐标 (旧坐标的区块文件还在磁盘上)。
     */
    public int allocateRegionOriginX(int regionSizeX, int separationBlocks) {
        if (!hasRegionFrontier) {
            throw new IllegalStateException(
                    "MiningSavedData.allocateRegionOriginX: regionFrontierX not initialized, call initRegionFrontierIfAbsent first");
        }
        int originX = regionFrontierX;
        long advanced = (long) regionFrontierX + regionSizeX + separationBlocks;
        if (advanced > MiningConstants.MAX_REGION_WORLD_X) {
            throw new IllegalStateException(
                    "MiningSavedData.allocateRegionOriginX: frontier " + advanced
                            + " exceeds MAX_REGION_WORLD_X=" + MiningConstants.MAX_REGION_WORLD_X
                            + "; refusing to reuse old region coordinates");
        }
        regionFrontierX = (int) advanced;
        setDirty();
        return originX;
    }

    // ---- 退役 region 待回收队列 ----

    /**
     * 登记一块退役 region 待回收。同坐标重复登记直接忽略 —— 坐标只会被 allocateRegionOriginX 发一次,
     * 重复登记只可能来自调用方缺陷, 静默叠加会让 GC 对同一块区域清两遍 (第二遍全是空操作但白发 IO)。
     */
    public void retireRegion(int originX, int originZ) {
        for (RetiredRegion existing : retiredRegions) {
            if (existing.originX == originX && existing.originZ == originZ) {
                return;
            }
        }
        retiredRegions.add(new RetiredRegion(originX, originZ, 0));
        setDirty();
    }

    /** 待回收队列的只读视图 (GC 遍历用)。 */
    public java.util.List<RetiredRegion> retiredRegions() {
        return java.util.Collections.unmodifiableList(retiredRegions);
    }

    /** 推进某块退役 region 的已清游标。 */
    public void advanceRetiredCursor(RetiredRegion region, int clearedChunks) {
        if (clearedChunks < region.clearedChunks) {
            throw new IllegalArgumentException("retired region cursor must not go backwards: "
                    + region.clearedChunks + " -> " + clearedChunks);
        }
        region.clearedChunks = clearedChunks;
        setDirty();
    }

    /** 清完出队。 */
    public void dropRetiredRegion(RetiredRegion region) {
        if (retiredRegions.remove(region)) {
            setDirty();
        }
    }

    // ---- 三固定难度实例的持久 id (13.4/D3) ----

    /** 该难度当前登记的固定实例 id; 尚未登记 (启动重建首次运行) 返回 empty。 */
    public OptionalLong fixedInstanceId(Difficulty difficulty) {
        Long id = fixedInstanceIds.get(difficulty);
        return id == null ? OptionalLong.empty() : OptionalLong.of(id);
    }

    public void setFixedInstanceId(Difficulty difficulty, long instanceId) {
        fixedInstanceIds.put(difficulty, instanceId);
        setDirty();
    }

    // ---- 持久化 ----

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong(K_NEXT_ID, nextInstanceId);
        tag.putBoolean(K_HAS_GLOBAL_SEED, hasGlobalSeed);
        tag.putLong(K_GLOBAL_SEED, globalSeed);
        tag.putInt(K_RESET_GEN, resetGeneration);

        ListTag list = new ListTag();
        for (InstanceState st : instances.values()) {
            list.add(st.save());
        }
        tag.put(K_INSTANCES, list);

        tag.putByteArray(K_REGION_OCCUPANCY, regionOccupancy);

        tag.putBoolean(K_HAS_REGION_FRONTIER, hasRegionFrontier);
        tag.putInt(K_REGION_FRONTIER_X, regionFrontierX);

        ListTag retired = new ListTag();
        for (RetiredRegion region : retiredRegions) {
            CompoundTag entry = new CompoundTag();
            entry.putInt(K_RETIRED_ORIGIN_X, region.originX);
            entry.putInt(K_RETIRED_ORIGIN_Z, region.originZ);
            entry.putInt(K_RETIRED_CURSOR, region.clearedChunks);
            retired.add(entry);
        }
        tag.put(K_RETIRED_REGIONS, retired);

        for (Map.Entry<Difficulty, Long> entry : fixedInstanceIds.entrySet()) {
            tag.putLong(K_FIXED_INSTANCE_PREFIX + entry.getKey().configName(), entry.getValue());
        }
        return tag;
    }

    /** 反序列化 (SavedData.Factory 第二参数)。版本号 MODID 仅作存在性占位, 无迁移逻辑时不读。 */
    public static MiningSavedData load(CompoundTag tag) {
        MiningSavedData data = new MiningSavedData();
        // nextInstanceId 缺省回退到 1 (空存档/旧版本), 不用 ?? 掩盖业务空值: 此处是 NBT 默认值语义而非业务回退。
        data.nextInstanceId = tag.contains(K_NEXT_ID) ? tag.getLong(K_NEXT_ID) : 1L;
        data.hasGlobalSeed = tag.getBoolean(K_HAS_GLOBAL_SEED);
        data.globalSeed = tag.getLong(K_GLOBAL_SEED);
        data.resetGeneration = tag.getInt(K_RESET_GEN);

        ListTag list = tag.getList(K_INSTANCES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            InstanceState st = InstanceState.load(list.getCompound(i));
            data.instances.put(st.instanceId(), st);
        }

        data.regionOccupancy = tag.getByteArray(K_REGION_OCCUPANCY);

        data.hasRegionFrontier = tag.getBoolean(K_HAS_REGION_FRONTIER);
        data.regionFrontierX = tag.getInt(K_REGION_FRONTIER_X);

        ListTag retired = tag.getList(K_RETIRED_REGIONS, Tag.TAG_COMPOUND);
        for (int i = 0; i < retired.size(); i++) {
            CompoundTag entry = retired.getCompound(i);
            data.retiredRegions.add(new RetiredRegion(
                    entry.getInt(K_RETIRED_ORIGIN_X),
                    entry.getInt(K_RETIRED_ORIGIN_Z),
                    entry.getInt(K_RETIRED_CURSOR)));
        }

        for (Difficulty difficulty : Difficulty.values()) {
            String key = K_FIXED_INSTANCE_PREFIX + difficulty.configName();
            if (tag.contains(key)) {
                data.fixedInstanceIds.put(difficulty, tag.getLong(key));
            }
        }
        return data;
    }

    @Override
    public String toString() {
        return "MiningSavedData[" + MiningConstants.MODID
                + " nextId=" + nextInstanceId
                + " globalSeed=" + (hasGlobalSeed ? Long.toHexString(globalSeed) : "<unset>")
                + " resetGen=" + resetGeneration
                + " instances=" + instances.size() + "]";
    }
}
