package com.miningdim.job.farmer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 农夫 mod 耕地放置计数 + 当日卖菜株数持久层 (FarmingXP_Mod_DesignSpec 表A 方块上限 + 第八节收购曲线计数)。
 * 挂在 overworld 的 DimensionDataStorage, 文件名 {@value #DATA_NAME}。
 *
 * 口径裁决 (spec 留 PENDING): 按玩家 UUID 全局计数 (跨维度、跨地块统一计单人已放置的 mod 耕地总数),
 * 放置 +1 / 破坏 -1。理由见 {@link FarmlandPlacementGuard} 类注释。计数与 JobProgress 解耦单独持久化
 * (它不是经验状态, 而是世界放置事实; 死亡/重生不影响已放在世界里的耕地)。
 *
 * 当日卖菜株数 ({@link DailySell}): 仅记当日已售出 mod 小麦株数 (UTC 翻日清), 供 {@link FarmerWheatBuyback}
 * 收购曲线定位边际单价档 (第几株 -> 衰减比例)。本层不再记 "当日已发信用点": 每日信用点 faucet 软上限已收敛进
 * 货币层 (playerId, faucetKey) 统一计数器 ({@link com.miningdim.economy.IEconomyService#grantDaily}), 农夫
 * 私有每日信用点并行计数已删除 (审查 Major: 各 faucet 各算私有上限与经济文档 8.5 全服统一软上限相悖)。
 *
 * 线程: 仅服务端主线程读写 (方块放置/破坏事件均主线程)。任何写后立即 setDirty() 否则不落盘。
 * 1.20.1 computeIfAbsent 三参签名 (load, create, name); SavedData.Factory 是 1.20.2+ 不可用。
 */
public final class FarmerSavedData extends SavedData {

    /** DimensionDataStorage 数据文件名。 */
    public static final String DATA_NAME = "miningdim_farmer";

    private static final String K_COUNTS = "placedCounts";
    private static final String K_UUID = "uuid";
    private static final String K_COUNT = "count";

    private static final String K_SOLD = "wheatSold";
    private static final String K_SOLD_DAY = "wheatSoldDay";

    /**
     * 单玩家当日卖菜记录: 株数 + UTC 日戳合一条 (翻日整条丢弃, 杜绝孤儿日戳累积)。可变记录 (主线程顺序写,
     * 无需不可变拷贝)。仅株数: 已发信用点的每日上限已收敛进货币层 faucet 计数器, 本层不再并行记 (审查 Major)。
     */
    private static final class DailySell {
        long dayStamp;
        int soldCount;

        DailySell(long dayStamp) {
            this.dayStamp = dayStamp;
        }
    }

    /** 玩家 UUID -> 当前全局已放置 mod 耕地数。 */
    private final Map<UUID, Integer> placedCounts = new HashMap<>();

    /** 玩家 UUID -> 当日卖菜记录 (株数 + 日戳; 翻日整条重建, 第八节收购曲线计数)。 */
    private final Map<UUID, DailySell> wheatSold = new HashMap<>();

    public FarmerSavedData() {
    }

    /** 取/建 overworld 的农夫持久数据。务必传 overworld 的 ServerLevel。 */
    public static FarmerSavedData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
                FarmerSavedData::load, FarmerSavedData::new, DATA_NAME);
    }

    /** 玩家当前已放置 mod 耕地数 (无记录返回 0)。 */
    public int placedCount(UUID playerId) {
        return placedCounts.getOrDefault(playerId, 0);
    }

    /** 放置一块: 计数 +1, 标脏, 返回新计数。 */
    public int increment(UUID playerId) {
        int next = placedCount(playerId) + 1;
        placedCounts.put(playerId, next);
        setDirty();
        return next;
    }

    /** 破坏一块: 计数 -1 (不低于 0), 标脏, 返回新计数。 */
    public int decrement(UUID playerId) {
        int next = Math.max(0, placedCount(playerId) - 1);
        if (next == 0) {
            placedCounts.remove(playerId); // 计数归零即清条目, 避免离场玩家长期占表。
        } else {
            placedCounts.put(playerId, next);
        }
        setDirty();
        return next;
    }

    /**
     * 当日已售出小麦株数 (经济收购衰减计数), UTC 翻日自动清零 (与经验软上限共用 UTC epochDay 口径)。
     *
     * @param playerId   玩家 UUID
     * @param todayStamp 当前 UTC 日戳 (epochDay; 由调用方传入, 与 JobServiceImpl.currentUtcDayStamp 同口径)
     * @return 翻日校正后的当日已售株数
     */
    public int wheatSoldToday(UUID playerId, long todayStamp) {
        DailySell record = currentRecord(playerId, todayStamp);
        return record == null ? 0 : record.soldCount;
    }

    /**
     * 记账一次卖菜: 累加当日已售株数 (UTC 翻日先整条重建)。供收购曲线定位边际单价档。
     *
     * @param playerId   玩家 UUID
     * @param soldDelta  本次卖出株数 (>=1)
     * @param todayStamp 当前 UTC 日戳
     */
    public void recordWheatSale(UUID playerId, int soldDelta, long todayStamp) {
        if (soldDelta < 1) {
            throw new IllegalArgumentException("soldDelta must be >= 1, got " + soldDelta);
        }
        DailySell record = wheatSold.computeIfAbsent(playerId, k -> new DailySell(todayStamp));
        if (record.dayStamp != todayStamp) {
            // 翻日: 整条重置 (株数清零, 不留孤儿残值)。
            record.dayStamp = todayStamp;
            record.soldCount = 0;
        }
        record.soldCount += soldDelta;
        setDirty();
    }

    /**
     * 取该玩家当日有效记录 (翻日则丢弃旧记录并清条目, 杜绝孤儿日戳累积)。
     * 返回 null 表示当日尚无卖菜记录。读路径若发生翻日丢弃即标脏 (落盘移除旧条目)。
     */
    private DailySell currentRecord(UUID playerId, long todayStamp) {
        DailySell record = wheatSold.get(playerId);
        if (record == null) {
            return null;
        }
        if (record.dayStamp != todayStamp) {
            wheatSold.remove(playerId); // 翻日整条清, 与 placedCounts 归零清条目同纪律, 防内存随玩家基数线性滞留。
            setDirty();
            return null;
        }
        return record;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Integer> e : placedCounts.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(K_UUID, e.getKey());
            entry.putInt(K_COUNT, e.getValue());
            list.add(entry);
        }
        tag.put(K_COUNTS, list);

        ListTag sold = new ListTag();
        for (Map.Entry<UUID, DailySell> e : wheatSold.entrySet()) {
            DailySell record = e.getValue();
            CompoundTag entry = new CompoundTag();
            entry.putUUID(K_UUID, e.getKey());
            entry.putInt(K_COUNT, record.soldCount);
            entry.putLong(K_SOLD_DAY, record.dayStamp);
            sold.add(entry);
        }
        tag.put(K_SOLD, sold);
        return tag;
    }

    public static FarmerSavedData load(CompoundTag tag) {
        FarmerSavedData data = new FarmerSavedData();
        if (tag.contains(K_COUNTS)) {
            ListTag list = tag.getList(K_COUNTS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                if (entry.hasUUID(K_UUID)) {
                    data.placedCounts.put(entry.getUUID(K_UUID), entry.getInt(K_COUNT));
                }
            }
        }
        if (tag.contains(K_SOLD)) {
            ListTag sold = tag.getList(K_SOLD, Tag.TAG_COMPOUND);
            for (int i = 0; i < sold.size(); i++) {
                CompoundTag entry = sold.getCompound(i);
                if (entry.hasUUID(K_UUID)) {
                    DailySell record = new DailySell(entry.getLong(K_SOLD_DAY));
                    record.soldCount = entry.getInt(K_COUNT);
                    data.wheatSold.put(entry.getUUID(K_UUID), record);
                }
            }
        }
        return data;
    }
}
