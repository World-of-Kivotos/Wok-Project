package com.miningdim.job.fisher.journal;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 按存档和玩家 UUID 保存收藏事实，死亡或更换维度不清空，也不复用 Tide 的图鉴解锁数据。 */
public final class FishingJournalSavedData extends SavedData {
    public static final String DATA_NAME = "miningdim_fishing_journal";
    private final Map<UUID, Set<ResourceLocation>> collections = new HashMap<>();

    public static FishingJournalSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                FishingJournalSavedData::load, FishingJournalSavedData::new, DATA_NAME);
    }

    public boolean collect(UUID playerId, ResourceLocation itemId) {
        boolean added = collections.computeIfAbsent(playerId, ignored -> new HashSet<>()).add(itemId);
        if (added) {
            setDirty();
        }
        return added;
    }

    public Set<ResourceLocation> collected(UUID playerId) {
        // 没有记录即尚未收录；返回快照，调用方不能绕过 setDirty 修改收藏。
        Set<ResourceLocation> known = collections.get(playerId);
        return known == null ? Set.of() : Set.copyOf(known);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("Version", 1);
        ListTag players = new ListTag();
        collections.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag player = new CompoundTag();
            player.putUUID("Player", entry.getKey());
            ListTag fish = new ListTag();
            entry.getValue().stream().sorted().forEach(id -> fish.add(StringTag.valueOf(id.toString())));
            player.put("Fish", fish);
            players.add(player);
        });
        tag.put("Players", players);
        return tag;
    }

    public static FishingJournalSavedData load(CompoundTag tag) {
        if (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1
                || !tag.contains("Players", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Invalid fishing journal save header");
        }
        FishingJournalSavedData data = new FishingJournalSavedData();
        ListTag players = requireList(tag, "Players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag player = players.getCompound(i);
            if (!player.hasUUID("Player")) {
                throw new IllegalArgumentException("Fishing journal player UUID is missing");
            }
            Set<ResourceLocation> known = new HashSet<>();
            ListTag fish = requireList(player, "Fish", Tag.TAG_STRING);
            for (int j = 0; j < fish.size(); j++) {
                known.add(new ResourceLocation(fish.getString(j)));
            }
            if (data.collections.putIfAbsent(player.getUUID("Player"), known) != null) {
                throw new IllegalArgumentException("Duplicate fishing journal player: " + player.getUUID("Player"));
            }
        }
        return data;
    }

    private static ListTag requireList(CompoundTag tag, String key, int elementType) {
        if (!(tag.get(key) instanceof ListTag list)
                || (!list.isEmpty() && list.getElementType() != elementType)) {
            throw new IllegalArgumentException("Invalid fishing journal list: " + key);
        }
        return list;
    }
}
