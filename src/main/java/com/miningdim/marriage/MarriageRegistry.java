package com.miningdim.marriage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 婚姻关系注册表与全局计数器的持久层 (结婚系统 spec 第九章; 仿 {@link com.miningdim.persistence.MiningSavedData} +
 * {@link com.miningdim.job.brewer.BrewBuffStore})。挂在 overworld 的 DimensionDataStorage, 文件名
 * {@value #DATA_NAME}。承载: 关系注册表 {@code Map<marriageId, MarriageState>} + 玩家反查索引
 * {@code Map<UUID, marriageId>} + 持久自增主键 nextMarriageId。
 *
 * 线程纪律 (spec 第四章): 仅服务端主线程读写; 任何结构性变更 (createMarriage / dissolve) 后必须 setDirty(),
 * 否则不落盘。byPlayer 反查索引与 marriages 主表强一致, 由 create/dissolve 同步维护 (不独立持久化, 加载时由
 * marriages 重建, 杜绝索引漂移 —— 仿 InstanceManager 重建 privateIndex)。
 */
public final class MarriageRegistry extends SavedData {

    /** DimensionDataStorage 数据文件名。 */
    public static final String DATA_NAME = "miningdim_marriages";

    private static final String K_NEXT_ID = "nextMarriageId";
    private static final String K_MARRIAGES = "marriages";

    /** 关系注册表: marriageId -> 关系状态。 */
    private final Map<Long, MarriageState> marriages = new HashMap<>();

    /** 玩家反查索引: playerUUID -> marriageId (强一致于 marriages, 由 create/dissolve 维护, 加载时重建)。 */
    private final Map<UUID, Long> byPlayer = new HashMap<>();

    /** 持久自增主键, 从 1 起 (0 保留为 NO_MARRIAGE 之外的未分配语义)。绝不复用已解除的 id。 */
    private long nextMarriageId = 1L;

    public MarriageRegistry() {
    }

    /** 取/建 overworld 的婚姻持久数据。务必传 overworld 的 ServerLevel (1.20.1 三参 computeIfAbsent)。 */
    public static MarriageRegistry get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
                MarriageRegistry::load, MarriageRegistry::new, DATA_NAME);
    }

    // ---- 查询 ----

    /** 某玩家当前所在的婚姻关系; 未婚返回 null (反查索引命中即取主表)。 */
    public MarriageState forPlayer(UUID player) {
        Long id = byPlayer.get(player);
        if (id == null) {
            return null;
        }
        MarriageState st = marriages.get(id);
        if (st == null || !st.involves(player)) {
            // 索引指向已解除/不一致的关系: 清陈旧索引项, 视为未婚 (不静默掩盖, 但不抛 —— 自愈)。
            byPlayer.remove(player);
            return null;
        }
        return st;
    }

    /** 某 marriageId 的关系; 不存在返回 null。 */
    public MarriageState byId(long marriageId) {
        return marriages.get(marriageId);
    }

    public int marriageCount() {
        return marriages.size();
    }

    // ---- 结构性变更 (主线程串行) ----

    /**
     * 登记一段新婚姻 (典礼成功后唯一入口; spec 第三章)。校验双方均未婚 —— 任一方已婚则抛, 杜绝重婚 (典礼前置
     * 校验在 {@link MarriageEngine}, 此处是最后防线)。派生 id、建关系、写双向索引、setDirty。
     *
     * @return 新建的关系状态
     */
    public MarriageState createMarriage(UUID a, UUID b, long nowTick) {
        if (a.equals(b)) {
            throw new IllegalArgumentException("a player cannot marry themselves: " + a);
        }
        if (byPlayer.containsKey(a)) {
            throw new IllegalStateException("player already married: " + a);
        }
        if (byPlayer.containsKey(b)) {
            throw new IllegalStateException("player already married: " + b);
        }
        long id = nextMarriageId;
        nextMarriageId = id + 1L;
        MarriageState st = new MarriageState(id, a, b, nowTick);
        marriages.put(id, st);
        byPlayer.put(a, id);
        byPlayer.put(b, id);
        setDirty();
        return st;
    }

    /**
     * 解除一段婚姻 (离婚; spec 第六章, 阶段 5)。从主表移除并清双方反查索引; 返回被移除的关系 (供调用方做清算/
     * 转历史表)。不存在返回 null。本期 (阶段 1) 仅提供解除原语, 离婚冷却/清算/escrow 由阶段 5 接入。
     */
    public MarriageState dissolve(long marriageId) {
        MarriageState st = marriages.remove(marriageId);
        if (st == null) {
            return null;
        }
        byPlayer.remove(st.partnerA());
        byPlayer.remove(st.partnerB());
        setDirty();
        return st;
    }

    // ---- 持久化 ----

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong(K_NEXT_ID, nextMarriageId);
        ListTag list = new ListTag();
        for (MarriageState st : marriages.values()) {
            list.add(st.save());
        }
        tag.put(K_MARRIAGES, list);
        return tag;
    }

    /** 反序列化: 还原主表后由主表重建 byPlayer 反查索引 (杜绝索引漂移)。 */
    public static MarriageRegistry load(CompoundTag tag) {
        MarriageRegistry data = new MarriageRegistry();
        data.nextMarriageId = tag.contains(K_NEXT_ID) ? tag.getLong(K_NEXT_ID) : 1L;
        ListTag list = tag.getList(K_MARRIAGES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            MarriageState st = MarriageState.load(list.getCompound(i));
            data.marriages.put(st.marriageId(), st);
            data.byPlayer.put(st.partnerA(), st.marriageId());
            data.byPlayer.put(st.partnerB(), st.marriageId());
        }
        return data;
    }
}
