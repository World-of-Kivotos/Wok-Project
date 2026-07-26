package com.miningdim.job.tarot.pack;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Persistent pity and daily acquisition counters for the tarot pack system. */
public final class TarotPackSavedData extends SavedData {

    public static final String DATA_NAME = "miningdim_tarot_packs";

    private static final String K_PLAYERS = "players";
    private static final String K_UUID = "uuid";
    private static final String K_PITY = "advancedNoSsr";
    private static final String K_DAY = "dayStamp";
    private static final String K_ACQUIRED = "acquiredToday";

    private final Map<UUID, PlayerState> players = new HashMap<>();

    private static final class PlayerState {
        int advancedNoSsr;
        long dayStamp;
        int acquiredToday;
    }

    public static TarotPackSavedData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
                TarotPackSavedData::load, TarotPackSavedData::new, DATA_NAME);
    }

    public int advancedNoSsrStreak(UUID playerId) {
        PlayerState state = players.get(playerId);
        return state == null ? 0 : state.advancedNoSsr;
    }

    public void setAdvancedNoSsrStreak(UUID playerId, int streak) {
        if (streak < 0) {
            throw new IllegalArgumentException("pity streak must be >= 0");
        }
        PlayerState state = players.computeIfAbsent(playerId, ignored -> new PlayerState());
        if (state.advancedNoSsr != streak) {
            state.advancedNoSsr = streak;
            setDirty();
        }
    }

    public int acquiredToday(UUID playerId, long todayStamp) {
        PlayerState state = players.get(playerId);
        return state == null || state.dayStamp != todayStamp ? 0 : state.acquiredToday;
    }

    public boolean canAcquire(UUID playerId, int count, int dailyCap, long todayStamp) {
        requireCount(count, dailyCap);
        return acquiredToday(playerId, todayStamp) <= dailyCap - count;
    }

    /** Records paid or derived packs after all other transaction checks have succeeded. */
    public void recordAcquired(UUID playerId, int count, int dailyCap, long todayStamp) {
        if (!canAcquire(playerId, count, dailyCap, todayStamp)) {
            throw new IllegalStateException("tarot daily pack limit exceeded");
        }
        PlayerState state = players.computeIfAbsent(playerId, ignored -> new PlayerState());
        if (state.dayStamp != todayStamp) {
            state.dayStamp = todayStamp;
            state.acquiredToday = 0;
        }
        state.acquiredToday += count;
        setDirty();
    }

    /** Atomically consumes one derived-pack allowance on the server thread. */
    public boolean tryRecordDerived(UUID playerId, int dailyCap, long todayStamp) {
        if (!canAcquire(playerId, 1, dailyCap, todayStamp)) {
            return false;
        }
        recordAcquired(playerId, 1, dailyCap, todayStamp);
        return true;
    }

    private static void requireCount(int count, int dailyCap) {
        if (count <= 0) {
            throw new IllegalArgumentException("pack count must be > 0");
        }
        if (dailyCap < 0) {
            throw new IllegalArgumentException("daily pack cap must be >= 0");
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, PlayerState> entry : players.entrySet()) {
            PlayerState state = entry.getValue();
            CompoundTag player = new CompoundTag();
            player.putUUID(K_UUID, entry.getKey());
            player.putInt(K_PITY, state.advancedNoSsr);
            player.putLong(K_DAY, state.dayStamp);
            player.putInt(K_ACQUIRED, state.acquiredToday);
            list.add(player);
        }
        tag.put(K_PLAYERS, list);
        return tag;
    }

    public static TarotPackSavedData load(CompoundTag tag) {
        TarotPackSavedData data = new TarotPackSavedData();
        ListTag list = tag.getList(K_PLAYERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag player = list.getCompound(i);
            if (!player.hasUUID(K_UUID)) {
                continue;
            }
            PlayerState state = new PlayerState();
            state.advancedNoSsr = Math.max(0, player.getInt(K_PITY));
            state.dayStamp = player.getLong(K_DAY);
            state.acquiredToday = Math.max(0, player.getInt(K_ACQUIRED));
            data.players.put(player.getUUID(K_UUID), state);
        }
        return data;
    }
}
