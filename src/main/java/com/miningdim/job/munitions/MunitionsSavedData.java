package com.miningdim.job.munitions;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 军火台放置计数持久层 (Munitions_Job_DesignSpec 5/10.5 台数上限校验)。挂在 overworld 的 DimensionDataStorage,
 * 文件名 {@value #DATA_NAME}。
 *
 * 台数上限按军火商等级 (6.1: L1=1 台 ... L10=6 台), 上限是 "全局每人拥有的军火台总数", 故需按 ownerUUID 全局计数
 * (BE 单存 owner 不足以全局校验跨地块/跨区块的总台数; 与 {@link com.miningdim.job.farmer.FarmerSavedData}
 * placedCount 同范式)。放置 +1 / 破坏 -1。
 *
 * 计数与 JobProgress 解耦单独持久化 (它是世界放置事实, 非经验状态; 死亡/重生不影响已放在世界里的军火台)。
 * 线程: 仅服务端主线程读写。任何写后立即 setDirty() 否则不落盘。1.20.1 computeIfAbsent 三参签名 (load, create,
 * name); SavedData.Factory 是 1.20.2+ 不可用。
 */
public final class MunitionsSavedData extends SavedData {

    /** DimensionDataStorage 数据文件名。 */
    public static final String DATA_NAME = "miningdim_munitions";

    private static final String K_COUNTS = "benchCounts";
    private static final String K_UUID = "uuid";
    private static final String K_COUNT = "count";

    /** 玩家 UUID -> 当前全局已放置军火台数。 */
    private final Map<UUID, Integer> benchCounts = new HashMap<>();

    public MunitionsSavedData() {
    }

    /** 取/建 overworld 的军火商持久数据。务必传 overworld 的 ServerLevel。 */
    public static MunitionsSavedData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
                MunitionsSavedData::load, MunitionsSavedData::new, DATA_NAME);
    }

    /** 玩家当前已放置军火台数 (无记录返回 0)。 */
    public int benchCount(UUID playerId) {
        return benchCounts.getOrDefault(playerId, 0);
    }

    /** 放置一台: 计数 +1, 标脏, 返回新计数。 */
    public int increment(UUID playerId) {
        int next = benchCount(playerId) + 1;
        benchCounts.put(playerId, next);
        setDirty();
        return next;
    }

    /** 破坏一台: 计数 -1 (不低于 0), 标脏, 返回新计数。计数归零即清条目, 避免离场玩家长期占表。 */
    public int decrement(UUID playerId) {
        int next = Math.max(0, benchCount(playerId) - 1);
        if (next == 0) {
            benchCounts.remove(playerId);
        } else {
            benchCounts.put(playerId, next);
        }
        setDirty();
        return next;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Integer> e : benchCounts.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(K_UUID, e.getKey());
            entry.putInt(K_COUNT, e.getValue());
            list.add(entry);
        }
        tag.put(K_COUNTS, list);
        return tag;
    }

    public static MunitionsSavedData load(CompoundTag tag) {
        MunitionsSavedData data = new MunitionsSavedData();
        if (tag.contains(K_COUNTS)) {
            ListTag list = tag.getList(K_COUNTS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                if (entry.hasUUID(K_UUID)) {
                    data.benchCounts.put(entry.getUUID(K_UUID), entry.getInt(K_COUNT));
                }
            }
        }
        return data;
    }
}
