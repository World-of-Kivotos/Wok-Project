package com.miningdim.job.fisher.size;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 玩家个人钓获记录, 存在 {@code getPersistentData()} 的 {@link Player#PERSISTED_NBT_TAG} 子标签里:
 * Forge 在玩家实体重建 (死亡重生、从末地回主世界) 时只复制这个子标签, 所以记录跨死亡保留, 随玩家存档走,
 * 不进全服共享的图鉴 SavedData, 玩家再多也不会让单个全局文件膨胀。
 *
 * 结构: {@value #ROOT}{"<item id>": {n: 次数, mm: 最大个体体长, mg: 该个体体重}}。条目数以体型档案数为上限
 * (最多 512)。读取对坏数据宽容: 单条畸形记录跳过, 不抛异常, 避免一条坏数据让整份记录不可读。
 */
public final class FishingRecords {
    public static final String ROOT = "MiningFishingRecords";
    private static final String COUNT = "n";
    private static final String LENGTH_MM = "mm";
    private static final String WEIGHT_MG = "mg";

    private FishingRecords() {
    }

    public static Map<ResourceLocation, FishRecord> all(Player player) {
        CompoundTag records = recordsTag(player);
        Map<ResourceLocation, FishRecord> result = new LinkedHashMap<>();
        for (String key : records.getAllKeys()) {
            ResourceLocation itemId = ResourceLocation.tryParse(key);
            FishRecord record = itemId == null ? null : parse(records, key);
            if (record != null) {
                result.put(itemId, record);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public static FishRecord get(Player player, ResourceLocation itemId) {
        return parse(recordsTag(player), itemId.toString());
    }

    /**
     * 记一次钓获, 返回这次是否刷新了个人最大个体 (严格更长才算; 首次钓获也算刷新)。
     * count 是这一栈渔获的条数, 次数饱和在 Integer.MAX_VALUE。
     */
    public static boolean record(Player player, ResourceLocation itemId, FishMeasurement measurement, int count) {
        CompoundTag forgeData = player.getPersistentData();
        CompoundTag persisted = forgeData.getCompound(Player.PERSISTED_NBT_TAG);
        CompoundTag records = persisted.getCompound(ROOT);
        String key = itemId.toString();
        FishRecord previous = parse(records, key);
        boolean newBest = previous == null || measurement.lengthMm() > previous.bestLengthMm();
        int total = (int) Math.min(Integer.MAX_VALUE, (previous == null ? 0L : previous.count()) + Math.max(1, count));
        CompoundTag entry = new CompoundTag();
        entry.putInt(COUNT, total);
        entry.putInt(LENGTH_MM, newBest ? measurement.lengthMm() : previous.bestLengthMm());
        entry.putLong(WEIGHT_MG, newBest ? measurement.weightMg() : previous.bestWeightMg());
        records.put(key, entry);
        persisted.put(ROOT, records);
        forgeData.put(Player.PERSISTED_NBT_TAG, persisted);
        return newBest;
    }

    private static CompoundTag recordsTag(Player player) {
        return player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).getCompound(ROOT);
    }

    private static FishRecord parse(CompoundTag records, String key) {
        if (!records.contains(key, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag entry = records.getCompound(key);
        int count = entry.getInt(COUNT);
        int lengthMm = entry.getInt(LENGTH_MM);
        long weightMg = entry.getLong(WEIGHT_MG);
        if (count <= 0 || lengthMm <= 0 || weightMg <= 0L) {
            return null;
        }
        return new FishRecord(count, lengthMm, weightMg);
    }
}
