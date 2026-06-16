package com.miningdim.persistence;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * 玩家级矿山数据 (设计文档 12.5 第二层 / 14.6)。承载"进入矿山前的回退状态 + 当前实例 + danger +
 * 每日产矿计数 + 待撤离标记", 用于死亡/换维度/断线重连的回退与恢复。
 *
 * 字段语义与持久化 (12.5 表):
 *   prevDimension / prevPos / prevGameMode  进入矿山前的维度/坐标/游戏模式 (sendBackToFallback 用, 始终复制)
 *   currentInstanceId                       当前所在实例 id; 不在矿山为 -1
 *   danger                                  当前危险值 [0,1] (第十章 D7)
 *   dailyOreCount                           按矿种字符串键累计的每日产矿计数 (18.4 经济软上限/AFK 判定), 翻日清零
 *   pendingEvacuation                       实例已被重置/GC, 登录时立即送回回退点 (13.3 markPendingEvacuation)
 *
 * 本类是纯数据容器 (POJO + NBT 编解码), 不含事件/Provider 逻辑 (那在 PlayerMiningDataProvider /
 * PlayerMiningEvents)。复制规则由 PlayerEvent.Clone 处理方决定 (12.5 复制列)。
 */
public final class PlayerMiningData {

    /** 不在任何矿山实例时的 currentInstanceId 哨兵值 (14.6)。 */
    public static final long NO_INSTANCE = -1L;

    private static final String K_HAS_PREV = "hasPrev";
    private static final String K_PREV_DIM = "prevDimension";
    private static final String K_PREV_X = "prevX";
    private static final String K_PREV_Y = "prevY";
    private static final String K_PREV_Z = "prevZ";
    private static final String K_PREV_GAMEMODE = "prevGameMode";
    private static final String K_CURRENT_INSTANCE = "currentInstanceId";
    private static final String K_DANGER = "danger";
    private static final String K_PENDING_EVAC = "pendingEvacuation";
    private static final String K_DAILY_ORE = "dailyOreCount";
    private static final String K_ORE_NAME = "ore";
    private static final String K_ORE_COUNT = "count";
    private static final String K_DAILY_DAY = "dailyResetDay";

    /** 进入矿山前所在维度键; null 表示从未进入过 (无回退态)。 */
    private ResourceKey<Level> prevDimension;

    /** 进入矿山前坐标 (方块对齐); 仅 prevDimension 非 null 时有效。 */
    private BlockPos prevPos;

    /**
     * 进入矿山前游戏模式 id (GameType.getId(): SURVIVAL=0/CREATIVE=1/ADVENTURE=2/SPECTATOR=3)。
     * 以 int 存储避免本数据类硬依赖 GameType 类型解析顺序; 还原由回退执行方按 id 取 GameType。
     */
    private int prevGameModeId = -1;

    /** 当前实例 id; NO_INSTANCE 表示不在矿山。 */
    private long currentInstanceId = NO_INSTANCE;

    /** 当前危险值, 归一化 [0,1] (第十章)。 */
    private float danger;

    /** 实例已失效, 登录立即送回回退点 (14.6 情况 A)。 */
    private boolean pendingEvacuation;

    /** 每日产矿计数: 矿种注册名 -> 当日累计数 (18.4)。 */
    private final Map<String, Integer> dailyOreCount = new HashMap<>();

    /** 上次每日计数重置所属的"天" (= gameTime / 24000); 翻日时清零 dailyOreCount。 */
    private long dailyResetDay = Long.MIN_VALUE;

    // ---- 回退态 (prev*) ----

    public boolean hasFallback() {
        return prevDimension != null && prevPos != null;
    }

    public ResourceKey<Level> prevDimension() {
        return prevDimension;
    }

    public BlockPos prevPos() {
        return prevPos;
    }

    public int prevGameModeId() {
        return prevGameModeId;
    }

    /** 进入矿山前快照回退态 (14.4 snapshotFallback)。 */
    public void setFallback(ResourceKey<Level> dimension, BlockPos pos, int gameModeId) {
        this.prevDimension = dimension;
        this.prevPos = pos.immutable();
        this.prevGameModeId = gameModeId;
    }

    /** 回退完成后清空, 防止陈旧回退态误用 (14.6 sendBackToFallback 之后)。 */
    public void clearFallback() {
        this.prevDimension = null;
        this.prevPos = null;
        this.prevGameModeId = -1;
    }

    // ---- 当前实例 ----

    public long currentInstanceId() {
        return currentInstanceId;
    }

    public void setCurrentInstanceId(long instanceId) {
        this.currentInstanceId = instanceId;
    }

    public boolean inMiningInstance() {
        return currentInstanceId != NO_INSTANCE;
    }

    // ---- danger ----

    public float danger() {
        return danger;
    }

    public void setDanger(float danger) {
        this.danger = danger;
    }

    // ---- 待撤离 ----

    public boolean pendingEvacuation() {
        return pendingEvacuation;
    }

    public void setPendingEvacuation(boolean pending) {
        this.pendingEvacuation = pending;
    }

    // ---- 每日产矿计数 ----

    /** 当前各矿种每日计数的只读视图。 */
    public Map<String, Integer> dailyOreCount() {
        return dailyOreCount;
    }

    /**
     * 累加某矿种当日计数并返回累加后的值。day 为当前游戏日 (gameTime/24000); 翻日自动清零再计。
     * 计数用于 18.4 经济软上限与 AFK 判定, 是真实业务计数, 不可弱化。
     */
    public int addOreCount(String oreName, int amount, long day) {
        if (day != dailyResetDay) {
            dailyOreCount.clear();
            dailyResetDay = day;
        }
        int next = dailyOreCount.getOrDefault(oreName, 0) + amount;
        dailyOreCount.put(oreName, next);
        return next;
    }

    // ---- NBT 编解码 (供 Provider 的 ICapabilitySerializable) ----

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        boolean hasPrev = hasFallback();
        tag.putBoolean(K_HAS_PREV, hasPrev);
        if (hasPrev) {
            tag.putString(K_PREV_DIM, prevDimension.location().toString());
            tag.putInt(K_PREV_X, prevPos.getX());
            tag.putInt(K_PREV_Y, prevPos.getY());
            tag.putInt(K_PREV_Z, prevPos.getZ());
            tag.putInt(K_PREV_GAMEMODE, prevGameModeId);
        }
        tag.putLong(K_CURRENT_INSTANCE, currentInstanceId);
        tag.putFloat(K_DANGER, danger);
        tag.putBoolean(K_PENDING_EVAC, pendingEvacuation);
        tag.putLong(K_DAILY_DAY, dailyResetDay);

        ListTag ores = new ListTag();
        for (Map.Entry<String, Integer> e : dailyOreCount.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString(K_ORE_NAME, e.getKey());
            entry.putInt(K_ORE_COUNT, e.getValue());
            ores.add(entry);
        }
        tag.put(K_DAILY_ORE, ores);
        return tag;
    }

    public void load(CompoundTag tag) {
        if (tag.getBoolean(K_HAS_PREV)) {
            this.prevDimension = ResourceKey.create(Registries.DIMENSION,
                    new ResourceLocation(tag.getString(K_PREV_DIM)));
            this.prevPos = new BlockPos(tag.getInt(K_PREV_X), tag.getInt(K_PREV_Y), tag.getInt(K_PREV_Z));
            this.prevGameModeId = tag.getInt(K_PREV_GAMEMODE);
        } else {
            this.prevDimension = null;
            this.prevPos = null;
            this.prevGameModeId = -1;
        }
        // 缺省到 NO_INSTANCE 是哨兵默认值语义 (空 NBT/新玩家), 非业务空值掩盖。
        this.currentInstanceId = tag.contains(K_CURRENT_INSTANCE) ? tag.getLong(K_CURRENT_INSTANCE) : NO_INSTANCE;
        this.danger = tag.getFloat(K_DANGER);
        this.pendingEvacuation = tag.getBoolean(K_PENDING_EVAC);
        this.dailyResetDay = tag.contains(K_DAILY_DAY) ? tag.getLong(K_DAILY_DAY) : Long.MIN_VALUE;

        dailyOreCount.clear();
        ListTag ores = tag.getList(K_DAILY_ORE, Tag.TAG_COMPOUND);
        for (int i = 0; i < ores.size(); i++) {
            CompoundTag entry = ores.getCompound(i);
            dailyOreCount.put(entry.getString(K_ORE_NAME), entry.getInt(K_ORE_COUNT));
        }
    }

    /**
     * 从另一份数据深拷贝全部字段 (PlayerEvent.Clone 复制规则: prev* 与 currentInstanceId/danger 均始终复制;
     * wasDeath 时对 currentInstanceId/danger 的特殊处置由调用方在拷贝后按 14.6/D7 覆盖, 本方法只做无条件全量复制)。
     */
    public void copyFrom(PlayerMiningData other) {
        this.prevDimension = other.prevDimension;
        this.prevPos = other.prevPos == null ? null : other.prevPos.immutable();
        this.prevGameModeId = other.prevGameModeId;
        this.currentInstanceId = other.currentInstanceId;
        this.danger = other.danger;
        this.pendingEvacuation = other.pendingEvacuation;
        this.dailyResetDay = other.dailyResetDay;
        this.dailyOreCount.clear();
        this.dailyOreCount.putAll(other.dailyOreCount);
    }
}
