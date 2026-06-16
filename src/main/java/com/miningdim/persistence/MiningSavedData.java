package com.miningdim.persistence;

import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

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

    /** 持久自增主键, 从 1 起 (0 保留为"无效/未分配"语义)。绝不复用已销毁 id (12.4)。 */
    private long nextInstanceId = 1L;

    /** 全局确定性种子, 矿山维度首次创建时确定并持久化, 全程不变 (12.4)。 */
    private long globalSeed;

    /** globalSeed 是否已初始化; 首次访问由 InstanceManager 用存档主 seed 派生注入。 */
    private boolean hasGlobalSeed;

    /**
     * 全 mod 累计重置代数计数器 (NEW_SEED 重置时 +1, 供 ResetService 派生新种子)。
     * 注: 12.5 实例字段表未把 per-instance resetGeneration 列入持久列, 故重置代数以全局计数器形式
     * 持久化于本层; ResetService 取此值经 SeedUtil.deriveSeed 派生。SAME_SEED 重置不动此值。
     */
    private int resetGeneration;

    /** 实例注册表: instanceId -> 运行时状态。运行态由 InstanceState 自身持有, 本 Map 是其集合容器。 */
    private final Map<Long, InstanceState> instances = new HashMap<>();

    /**
     * region 网格占用位图 (12.5/12.8): 第 i 位为 1 表示第 i 个网格槽已被占用。
     * 槽编号与几何映射由 instance.RegionGrid 单一权威定义, 本类只存裸字节, 不解释语义。
     */
    private byte[] regionOccupancy = new byte[0];

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
