package com.miningdim.job.farmer;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 农夫 mod 耕地归属索引 + 当日卖菜株数持久层 (FarmingXP_Mod_DesignSpec 表A 方块上限 + 第八节收购曲线计数)。
 * 挂在 overworld 的 DimensionDataStorage, 文件名 {@value #DATA_NAME}。
 *
 * 口径裁决 (F025 修复): 按 (维度, 坐标) 记每块 mod 耕地的放置者 UUID ({@link #farmlandOwners}), 玩家已放置数
 * ({@link #placedCounts}) 是该归属索引的运行期派生投影, 不再单独持久化、也不再由破坏者直接自增自减。回收点
 * 唯一在 {@link com.miningdim.job.farmer.block.FarmerFarmlandBlock#onRemove} (覆盖玩家破坏/爆炸/活塞/指令/
 * 级联更新全部路径, 而非仅 BreakEvent 覆盖的玩家手动破坏), 从物理移除的方块反查归属再回收, 杜绝"甲玩家放置、
 * 乙玩家或非玩家事件破坏"造成的配额永久冻结、以及小号互破刷计数的绕过面。
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

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/job/farmer");

    /** DimensionDataStorage 数据文件名。 */
    public static final String DATA_NAME = "miningdim_farmer";

    private static final String K_OWNERS = "farmlandOwners";
    private static final String K_COUNTS_LEGACY = "placedCounts";
    private static final String K_LEGACY_OVERFLOW = "legacyOverflow";
    private static final String K_DIM = "dim";
    private static final String K_POS = "pos";
    private static final String K_UUID = "uuid";
    private static final String K_COUNT = "count";

    private static final String K_SOLD = "wheatSold";
    private static final String K_SOLD_DAY = "wheatSoldDay";

    /** 耕地归属索引的 key: (维度, 打包坐标)。耕地可放在任意维度, 只用 BlockPos 会跨维度撞键。 */
    private record FarmlandKey(ResourceLocation dimension, long packedPos) {
    }

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

    /** (维度, 坐标) -> 放置者 UUID。归属记录的唯一真源, 回收权威点见类 javadoc。 */
    private final Map<FarmlandKey, UUID> farmlandOwners = new HashMap<>();

    /** 玩家 UUID -> 当前全局已放置 mod 耕地数; 由 {@link #farmlandOwners} 派生的运行期计数索引, 不单独持久化。 */
    private final Map<UUID, Integer> placedCounts = new HashMap<>();

    /**
     * 玩家 UUID -> 迁移前遗留、未纳入 {@link #farmlandOwners} 坐标索引的历史占用数 (F025 迁移口径复核修正)。
     * 旧存档只留过总数没留坐标, 无法反查回 farmlandOwners、也就永远无法通过 releaseFarmland 自然回收 ——
     * 直接丢弃这部分数值等于让老玩家在硬封顶 {@link com.miningdim.job.farmer.FarmlandPlacementGuard} 之外
     * 白得一份不可回收的额外配额 (复核 Major, 复现 F025 本身的经济危害)。故如实保留原值计入
     * {@link #placedCount(UUID)}, 不再自动衰减、也不主动豁免 —— 是否给老玩家一次性宽限是运营口径决策,
     * 代码不替主控拍板, 只提供机械清零动作, 唯一清除入口是 {@link #clearLegacyOverflow(UUID)} (命令层见
     * FarmerSystem /farmer admin recount/legacy)。持久化于独立 tag, 不按坐标 (单独一张玩家 -> 数量表)。
     */
    private final Map<UUID, Integer> legacyOverflow = new HashMap<>();

    /** 玩家 UUID -> 当日卖菜记录 (株数 + 日戳; 翻日整条重建, 第八节收购曲线计数)。 */
    private final Map<UUID, DailySell> wheatSold = new HashMap<>();

    public FarmerSavedData() {
    }

    /** 取/建 overworld 的农夫持久数据。务必传 overworld 的 ServerLevel。 */
    public static FarmerSavedData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
                FarmerSavedData::load, FarmerSavedData::new, DATA_NAME);
    }

    /**
     * 玩家当前占用的 mod 耕地放置配额 (无记录返回 0)。= 归属索引派生计数 {@link #placedCounts} +
     * 迁移遗留占用 {@link #legacyOverflow} (后者未清零前同样计入硬封顶, 复核 Major)。
     */
    public int placedCount(UUID playerId) {
        return placedCounts.getOrDefault(playerId, 0) + legacyOverflow.getOrDefault(playerId, 0);
    }

    /** 玩家当前的迁移遗留占用数 (无记录返回 0)。供 /farmer admin legacy 对账查询展示, 不参与其它用途。 */
    public int legacyOverflow(UUID playerId) {
        return legacyOverflow.getOrDefault(playerId, 0);
    }

    /**
     * 清除一名玩家的迁移遗留占用 (唯一清除入口, 命令层见 FarmerSystem /farmer admin recount)。
     * 是否清除、何时清除由运营对账后判断 (例如已核实玩家对应的旧耕地已拆除), 本方法只做机械清零,
     * 不内置任何自动豁免规则或数值。
     *
     * @return 清除前的遗留占用数 (0 表示该玩家本无遗留占用, 调用方据此可提示"无需处理", 而非误报成功)
     */
    public int clearLegacyOverflow(UUID playerId) {
        Integer removed = legacyOverflow.remove(playerId);
        if (removed == null) {
            return 0;
        }
        setDirty();
        return removed;
    }

    /** 某坐标当前的耕地放置者 (无记录返回 null: 旧存档遗留、指令生成等合法常态)。 */
    public UUID ownerOf(ResourceLocation dimension, BlockPos pos) {
        return farmlandOwners.get(new FarmlandKey(dimension, pos.asLong()));
    }

    /**
     * 登记一块耕地的放置归属。owner 为 null 直接抛异常 (异常必须痛, 不允许静默跳过)。
     * 若该坐标已有旧记录 (孤儿: /setblock 覆盖、世界编辑等非本方块生命周期路径写入), 先把旧 owner 的
     * 派生计数减 1 再写新记录并给新 owner 加 1, 使索引在下一次正常事件流中自愈, 不遗留计数漂移。
     */
    public void claimFarmland(ResourceLocation dimension, BlockPos pos, UUID owner) {
        if (owner == null) {
            throw new IllegalArgumentException("owner must not be null");
        }
        FarmlandKey key = new FarmlandKey(dimension, pos.asLong());
        UUID previousOwner = farmlandOwners.put(key, owner);
        if (previousOwner != null) {
            bumpCount(previousOwner, -1);
        }
        bumpCount(owner, 1);
        setDirty();
    }

    /**
     * 移除一块耕地的放置归属并回收其派生计数。无记录返回 null 且不改任何计数、不抛异常
     * (旧存档遗留耕地、指令生成的耕地均无归属记录, 是合法常态)。
     */
    public UUID releaseFarmland(ResourceLocation dimension, BlockPos pos) {
        UUID owner = farmlandOwners.remove(new FarmlandKey(dimension, pos.asLong()));
        if (owner == null) {
            return null;
        }
        bumpCount(owner, -1);
        setDirty();
        return owner;
    }

    /**
     * 维护 {@link #placedCounts} 派生计数 (钳零, 归零即清条目, 避免离场玩家长期占表)。
     * 必须只读写 {@link #placedCounts} 本身, 不能经 {@link #placedCount(UUID)} (后者会叠加
     * {@link #legacyOverflow}, 在此处读回来再写回 placedCounts 会把遗留占用重复计入两张表)。
     */
    private void bumpCount(UUID playerId, int delta) {
        int next = Math.max(0, placedCounts.getOrDefault(playerId, 0) + delta);
        if (next == 0) {
            placedCounts.remove(playerId);
        } else {
            placedCounts.put(playerId, next);
        }
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
        ListTag owners = new ListTag();
        for (Map.Entry<FarmlandKey, UUID> e : farmlandOwners.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString(K_DIM, e.getKey().dimension().toString());
            entry.putLong(K_POS, e.getKey().packedPos());
            entry.putUUID(K_UUID, e.getValue());
            owners.add(entry);
        }
        tag.put(K_OWNERS, owners);

        ListTag legacy = new ListTag();
        for (Map.Entry<UUID, Integer> e : legacyOverflow.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(K_UUID, e.getKey());
            entry.putInt(K_COUNT, e.getValue());
            legacy.add(entry);
        }
        tag.put(K_LEGACY_OVERFLOW, legacy);

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
        if (tag.contains(K_OWNERS)) {
            ListTag list = tag.getList(K_OWNERS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                if (entry.hasUUID(K_UUID) && entry.contains(K_DIM) && entry.contains(K_POS)) {
                    FarmlandKey key = new FarmlandKey(
                            new ResourceLocation(entry.getString(K_DIM)), entry.getLong(K_POS));
                    UUID owner = entry.getUUID(K_UUID);
                    data.farmlandOwners.put(key, owner);
                    data.bumpCount(owner, 1);
                }
            }
        }
        if (tag.contains(K_LEGACY_OVERFLOW)) {
            // 常规路径: 已跑过一次迁移 (下方分支) 或本就是新存档, legacyOverflow 已是本类自己持久化的字段。
            ListTag overflow = tag.getList(K_LEGACY_OVERFLOW, Tag.TAG_COMPOUND);
            for (int i = 0; i < overflow.size(); i++) {
                CompoundTag entry = overflow.getCompound(i);
                if (entry.hasUUID(K_UUID)) {
                    data.legacyOverflow.put(entry.getUUID(K_UUID), entry.getInt(K_COUNT));
                }
            }
        } else if (tag.contains(K_COUNTS_LEGACY)) {
            // 一次性迁移 (复核 Major 修正): 只在从"修复前"存档首次升级时命中 (存档一旦重新保存过, 会写出
            // K_LEGACY_OVERFLOW, 此分支此后不再命中)。旧 placedCounts 只留总数没留坐标, 无法核对回归属索引,
            // 但数值本身如实保留计入 legacyOverflow (不再丢弃) —— 丢弃会让老玩家在硬封顶之外白得一份不可
            // 回收的额外配额, 复现 F025 本身的危害。是否给老玩家一次性宽限、要不要清除这份遗留占用是运营口径
            // 决策, 不在此处替主控拍板, 唯一清除入口是 {@link #clearLegacyOverflow}。
            ListTag legacy = tag.getList(K_COUNTS_LEGACY, Tag.TAG_COMPOUND);
            int migratedPlayers = 0;
            long migratedBlocks = 0;
            for (int i = 0; i < legacy.size(); i++) {
                CompoundTag entry = legacy.getCompound(i);
                if (entry.hasUUID(K_UUID)) {
                    int count = entry.getInt(K_COUNT);
                    if (count > 0) {
                        data.legacyOverflow.put(entry.getUUID(K_UUID), count);
                        migratedPlayers++;
                        migratedBlocks += count;
                    }
                }
            }
            if (migratedPlayers > 0) {
                LOGGER.info("[miningdim] migrated legacy farmland placedCounts into legacyOverflow on load: "
                                + "{} player(s), {} block(s) total; counted against the placement cap until an op "
                                + "reconciles via /farmer admin recount (F025 migration policy correction)",
                        migratedPlayers, migratedBlocks);
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
