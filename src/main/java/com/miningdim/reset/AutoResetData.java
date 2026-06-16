package com.miningdim.reset;

import com.miningdim.core.Difficulty;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.EnumMap;
import java.util.Map;

/**
 * R6 每难度定时自动重置的持久层 (lastReset 跟踪)。挂在矿山维度的 DimensionDataStorage,
 * 数据文件名 {@value #DATA_NAME}, 与 MiningSavedData 同维度但独立文件 —— reset 子系统自管,
 * 不改 instance/persistence 既有 SavedData (阶段0 已冻结)。
 *
 * 持久化内容: 每个难度上一次自动重置完成的游戏时间 (mining 维度 getGameTime 的 tick 值)。
 * 游戏时间是 vanilla 自身持久化的服务端时钟, 只在服务端运行时推进, 故重启后比对仍给出正确的
 * "运营在线时长"语义 (不把关服停机时间算进刷新间隔)。未记录过 (新存档/首次开服) 的难度返回 -1,
 * 由调度器以"开服当下作为基准"初始化, 避免开服瞬间触发一轮刷新。
 *
 * 线程纪律: 仅服务端主线程读写 (ResetSystem 的 tick / reset 完成回调均在主线程), 任何变更后 setDirty()。
 */
public final class AutoResetData extends SavedData {

    /** DimensionDataStorage 中的数据文件名。 */
    public static final String DATA_NAME = "miningdim_auto_reset";

    /** 从未记录过的难度的哨兵值 (区别于合法 game-time >= 0)。 */
    public static final long NEVER = -1L;

    private static final String K_LAST_RESET_PREFIX = "lastReset_";

    /** 难度 -> 上次自动重置完成时的游戏时间 (tick)。缺省视为 NEVER。 */
    private final Map<Difficulty, Long> lastReset = new EnumMap<>(Difficulty.class);

    public AutoResetData() {
    }

    /**
     * 取/建本维度的自动重置持久数据 (1.20.1 的 computeIfAbsent 三参签名; SavedData.Factory 为 1.20.2+,
     * 本版本不可用, 与 MiningSavedData 同处理)。数据随矿山维度存档落盘。
     */
    public static AutoResetData get(ServerLevel miningLevel) {
        return miningLevel.getDataStorage().computeIfAbsent(
                AutoResetData::load, AutoResetData::new, DATA_NAME);
    }

    /** 某难度上次自动重置完成的游戏时间; 从未记录返回 {@link #NEVER}。 */
    public long lastReset(Difficulty difficulty) {
        return lastReset.getOrDefault(difficulty, NEVER);
    }

    /** 记录某难度本次自动重置完成的游戏时间并落盘。 */
    public void setLastReset(Difficulty difficulty, long gameTime) {
        lastReset.put(difficulty, gameTime);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        for (Difficulty d : Difficulty.values()) {
            Long v = lastReset.get(d);
            if (v != null) {
                tag.putLong(K_LAST_RESET_PREFIX + d.configName(), v);
            }
        }
        return tag;
    }

    public static AutoResetData load(CompoundTag tag) {
        AutoResetData data = new AutoResetData();
        for (Difficulty d : Difficulty.values()) {
            String key = K_LAST_RESET_PREFIX + d.configName();
            if (tag.contains(key)) {
                data.lastReset.put(d, tag.getLong(key));
            }
        }
        return data;
    }
}
