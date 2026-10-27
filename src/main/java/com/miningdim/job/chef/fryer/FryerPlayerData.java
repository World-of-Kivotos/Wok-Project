package com.miningdim.job.chef.fryer;

import com.miningdim.economy.EconomyOperationDomain;
import com.miningdim.economy.EconomyOperationStatus;
import com.miningdim.economy.EconomyServices;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.DimensionDataStorage;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** 玩家手熟、最佳及闪耀限额保存在主世界，不随死亡重建。 */
public final class FryerPlayerData extends SavedData {
    private static final String DATA_NAME = "miningdim_fryer_players";
    private static final int[] MASTERY = {0, 3, 10, 25, 50, 100};
    private final Map<UUID, PlayerRecord> players = new HashMap<>();
    private final Map<UUID, UUID> pendingRefunds = new HashMap<>();
    private final Map<UUID, UUID> pendingCompletions = new HashMap<>();
    private final Map<StationKey, CompoundTag> checkpoints = new HashMap<>();
    private record ActiveSession(Object identity, UUID operation) {}
    private final Map<UUID, ActiveSession> active = new HashMap<>();
    private ServerLevel storageLevel;
    private record StationKey(ResourceLocation dimension, long pos) {}

    private static final class DishRecord { int completed, best; }
    private static final class PlayerRecord {
        long day, radiantAt;
        int radiantCount, suspect;
        final Map<ResourceLocation, DishRecord> dishes = new HashMap<>();
        int tokens = 6;
        long tokenAt, settledAt;
    }

    public static FryerPlayerData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        Path journalPath = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve(DATA_NAME + ".dat");
        FryerPlayerData data = loadFromStorage(overworld.getDataStorage(), journalPath);
        data.storageLevel = overworld; return data;
    }

    static FryerPlayerData loadFromStorage(DimensionDataStorage storage, Path journalPath) {
        return storage.computeIfAbsent(FryerPlayerData::load, () -> {
            // 原版会把加载异常变成 null；仅在文件确认不存在时才允许创建新档。
            if (!Files.notExists(journalPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Cannot load existing or inaccessible fryer journal " + journalPath);
            }
            return new FryerPlayerData();
        }, DATA_NAME);
    }

    public CompoundTag checkpoint(ServerLevel level, BlockPos pos) {
        CompoundTag snapshot = checkpoints.get(new StationKey(level.dimension().location(), pos.asLong()));
        return snapshot == null ? null : snapshot.copy();
    }
    public void checkpoint(ServerLevel level, BlockPos pos, CompoundTag snapshot) {
        StationKey key = new StationKey(level.dimension().location(), pos.asLong());
        if (!snapshot.equals(checkpoints.get(key))) { checkpoints.put(key, snapshot.copy()); setDirty(); }
    }
    public void clearCheckpoint(ServerLevel level, BlockPos pos) {
        if (checkpoints.remove(new StationKey(level.dimension().location(), pos.asLong())) != null) { setDirty(); sync(); }
    }
    public void sync() {
        if (storageLevel == null) throw new IllegalStateException("Fryer journal is not attached to a server");
        save(storageLevel.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve(DATA_NAME + ".dat").toFile());
    }
    @Override public void save(File file) {
        if (!isDirty()) return;
        FryerJournal.write(file, save(new CompoundTag())); setDirty(false);
    }

    private PlayerRecord player(UUID id, long now) {
        PlayerRecord record = players.computeIfAbsent(id, ignored -> new PlayerRecord());
        // 北京时间 08:00 是 UTC 零点。
        long day = Math.floorDiv(now, 86400000L);
        if (record.day != day) {
            long days = day - record.day;
            if (days > 0) record.suspect = (int) Math.max(0L, record.suspect - Math.min(days, 100L) * 10L);
            record.day = day; record.radiantCount = 0; setDirty();
        }
        return record;
    }

    private DishRecord dish(UUID player, ResourceLocation dish, long now) {
        return player(player, now).dishes.computeIfAbsent(dish, ignored -> new DishRecord());
    }

    public int progress(UUID player, ResourceLocation dish) { return dish(player, dish, System.currentTimeMillis()).completed; }
    public int best(UUID player, ResourceLocation dish) { return dish(player, dish, System.currentTimeMillis()).best; }
    public int mastery(UUID player, ResourceLocation dish) {
        int progress = progress(player, dish), result = 0;
        for (int i = 1; i < MASTERY.length; i++) if (progress >= MASTERY[i]) result = i;
        return result;
    }
    public int suspect(UUID player, long now) { return player(player, now).suspect; }
    public boolean goldBlocked(UUID player, long now) {
        PlayerRecord record = player(player, now);
        return record.suspect >= 40 || record.radiantCount >= 3 || now - record.radiantAt < 1800000L;
    }
    public void addSuspicion(UUID player, int points, long now) {
        PlayerRecord record = player(player, now); record.suspect = Math.min(1000, record.suspect + points); setDirty();
    }

    public boolean available(UUID player, long now) {
        PlayerRecord record = player(player, now);
        refill(record, now);
        return !active.containsKey(player) && now - record.settledAt >= 1000L && record.tokens > 0;
    }
    private static void refill(PlayerRecord record, long now) {
        if (record.tokenAt == 0L) record.tokenAt = now;
        long refill = (now - record.tokenAt) / 5000L;
        if (refill > 0) {
            record.tokens = (int) Math.min(6L, record.tokens + refill); record.tokenAt += refill * 5000L;
        }
    }
    public void begin(UUID player, Object identity, UUID operation, long now) {
        if (!available(player, now)) throw new IllegalStateException("Fryer session already active or throttled");
        player(player, now).tokens--; active.put(player, new ActiveSession(identity, operation));
    }
    public void release(UUID player, Object identity, long now) {
        ActiveSession session = active.get(player);
        if (session != null && session.identity() == identity) active.remove(player);
        player(player, now).settledAt = now;
    }
    public void cancelStart(UUID player, long now) {
        PlayerRecord record = player(player, now); record.tokens++; record.settledAt = 0L;
    }
    public void record(UUID player, ResourceLocation dish, int quality, int score, long now) {
        DishRecord record = dish(player, dish, now);
        if (quality >= 1 && record.completed < 1000000000) record.completed++;
        record.best = Math.max(record.best, score);
        if (quality == 4) {
            PlayerRecord p = player(player, now); p.radiantAt = now; p.radiantCount++;
        }
        setDirty();
    }

    public void enqueueRefund(UUID operation, UUID player) {
        if (pendingCompletions.containsKey(operation)) throw new IllegalStateException("Completed fryer transaction cannot be refunded");
        UUID existing = pendingRefunds.putIfAbsent(operation, player);
        if (existing != null && !existing.equals(player)) throw new IllegalStateException("Fryer refund owner changed");
        setDirty();
    }
    public void enqueueCompletion(UUID operation, UUID player) {
        UUID refundOwner = pendingRefunds.remove(operation);
        if (refundOwner != null && !refundOwner.equals(player)) throw new IllegalStateException("Fryer transaction owner changed");
        UUID existing = pendingCompletions.putIfAbsent(operation, player);
        if (existing != null && !existing.equals(player)) throw new IllegalStateException("Fryer completion owner changed");
        setDirty();
    }
    /** 账本按 operationId 幂等退款，玩家离线也会直接退回其账户。 */
    public void retryRefunds() {
        if (!EconomyServices.isRegistered()) return;
        // 上次检查点写盘失败时，内存队列仍可能较磁盘新；先持久化意图再改变账本终态。
        if (isDirty()) sync();
        boolean changed = false;
        Iterator<Map.Entry<UUID, UUID>> completions = pendingCompletions.entrySet().iterator();
        while (completions.hasNext()) {
            Map.Entry<UUID, UUID> entry = completions.next();
            EconomyOperationStatus result = EconomyServices.economyService().completeBundle(
                    EconomyOperationDomain.CHEF_FRYER, entry.getValue(), entry.getKey());
            if (result != EconomyOperationStatus.COMPLETED) throw new IllegalStateException("Cannot complete persisted fryer transaction " + entry.getKey() + ": " + result);
            completions.remove(); changed = true;
        }
        Iterator<Map.Entry<UUID, UUID>> iterator = pendingRefunds.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, UUID> entry = iterator.next();
            ActiveSession running = active.get(entry.getValue());
            if (running != null && entry.getKey().equals(running.operation())) continue;
            EconomyOperationStatus result = EconomyServices.economyService().refundBundle(
                    EconomyOperationDomain.CHEF_FRYER, entry.getValue(), entry.getKey());
            if (result == EconomyOperationStatus.REFUNDED || result == EconomyOperationStatus.NONE
                    || result == EconomyOperationStatus.COMPLETED) { iterator.remove(); changed = true; }
        }
        if (changed) { setDirty(); sync(); }
    }

    @Override public CompoundTag save(CompoundTag tag) {
        tag.putInt("Version", 2);
        ListTag playerList = new ListTag();
        players.forEach((id, record) -> {
            CompoundTag p = new CompoundTag(); p.putUUID("Player", id);
            p.putLong("Day", record.day); p.putLong("RadiantAt", record.radiantAt);
            p.putInt("RadiantCount", record.radiantCount); p.putInt("Suspect", record.suspect);
            ListTag dishes = new ListTag();
            record.dishes.forEach((dishId, dish) -> {
                CompoundTag d = new CompoundTag(); d.putString("Dish", dishId.toString());
                d.putInt("Completed", dish.completed); d.putInt("Best", dish.best); dishes.add(d);
            });
            p.put("Dishes", dishes); playerList.add(p);
        });
        tag.put("Players", playerList);
        ListTag refunds = new ListTag();
        pendingRefunds.forEach((operation, player) -> {
            CompoundTag refund = new CompoundTag(); refund.putUUID("Operation", operation); refund.putUUID("Player", player); refunds.add(refund);
        });
        tag.put("Refunds", refunds);
        ListTag completions = new ListTag();
        pendingCompletions.forEach((operation, player) -> {
            CompoundTag completion = new CompoundTag(); completion.putUUID("Operation", operation); completion.putUUID("Player", player); completions.add(completion);
        });
        tag.put("Completions", completions);
        ListTag snapshots = new ListTag();
        checkpoints.forEach((key, snapshot) -> {
            CompoundTag checkpoint = new CompoundTag(); checkpoint.putString("Dimension", key.dimension().toString());
            checkpoint.putLong("Pos", key.pos()); checkpoint.put("Snapshot", snapshot.copy()); snapshots.add(checkpoint);
        });
        tag.put("Checkpoints", snapshots); return tag;
    }

    public static FryerPlayerData load(CompoundTag tag) {
        if (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 2) throw new IllegalArgumentException("Invalid fryer player save version");
        FryerPlayerData data = new FryerPlayerData();
        for (Tag entry : list(tag, "Players", 65536)) {
            CompoundTag p = (CompoundTag) entry;
            if (!p.hasUUID("Player")) throw new IllegalArgumentException("Missing fryer player UUID");
            PlayerRecord r = new PlayerRecord();
            r.day = number(p, "Day", Tag.TAG_LONG, 0, Long.MAX_VALUE);
            r.radiantAt = number(p, "RadiantAt", Tag.TAG_LONG, 0, Long.MAX_VALUE);
            r.radiantCount = (int) number(p, "RadiantCount", Tag.TAG_INT, 0, 3);
            r.suspect = (int) number(p, "Suspect", Tag.TAG_INT, 0, 1000);
            for (Tag dish : list(p, "Dishes", 8192)) {
                CompoundTag d = (CompoundTag) dish;
                if (!d.contains("Dish", Tag.TAG_STRING)) throw new IllegalArgumentException("Missing fryer dish ID");
                ResourceLocation id = new ResourceLocation(d.getString("Dish")); DishRecord dr = new DishRecord();
                dr.completed = (int) number(d, "Completed", Tag.TAG_INT, 0, 1000000000);
                dr.best = (int) number(d, "Best", Tag.TAG_INT, 0, 1040);
                if (r.dishes.putIfAbsent(id, dr) != null) throw new IllegalArgumentException("Duplicate fryer dish " + id);
            }
            if (data.players.putIfAbsent(p.getUUID("Player"), r) != null) throw new IllegalArgumentException("Duplicate fryer player");
        }
        for (Tag entry : list(tag, "Refunds", 65536)) {
            CompoundTag r = (CompoundTag) entry;
            if (!r.hasUUID("Operation") || !r.hasUUID("Player")) throw new IllegalArgumentException("Invalid fryer refund");
            if (data.pendingRefunds.putIfAbsent(r.getUUID("Operation"), r.getUUID("Player")) != null) throw new IllegalArgumentException("Duplicate fryer refund");
        }
        for (Tag entry : list(tag, "Completions", 65536)) {
            CompoundTag c = (CompoundTag) entry;
            if (!c.hasUUID("Operation") || !c.hasUUID("Player")) throw new IllegalArgumentException("Invalid fryer completion");
            UUID operation = c.getUUID("Operation");
            if (data.pendingRefunds.containsKey(operation) || data.pendingCompletions.putIfAbsent(operation, c.getUUID("Player")) != null) throw new IllegalArgumentException("Duplicate fryer completion");
        }
        for (Tag entry : list(tag, "Checkpoints", 65536)) {
            CompoundTag c = (CompoundTag) entry;
            if (!c.contains("Dimension", Tag.TAG_STRING) || !c.contains("Pos", Tag.TAG_LONG) || !c.contains("Snapshot", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Invalid fryer checkpoint");
            StationKey key = new StationKey(new ResourceLocation(c.getString("Dimension")), c.getLong("Pos"));
            if (data.checkpoints.putIfAbsent(key, c.getCompound("Snapshot").copy()) != null) throw new IllegalArgumentException("Duplicate fryer checkpoint position");
        }
        return data;
    }

    static ListTag list(CompoundTag tag, String key, int max) {
        if (!(tag.get(key) instanceof ListTag list) || list.size() > max
                || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Invalid fryer list " + key);
        return list;
    }
    static long number(CompoundTag tag, String key, int type, long min, long max) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException("Missing fryer number " + key);
        long result = type == Tag.TAG_LONG ? tag.getLong(key) : tag.getInt(key);
        if (result < min || result > max) throw new IllegalArgumentException("Invalid fryer number " + key);
        return result;
    }
}
