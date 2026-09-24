package com.miningdim.job.fisher.journal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miningdim.job.fisher.FishingDataGate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 服务器数据包是图鉴目录的唯一来源；缺失的可选 MOD 不进入完成度分母。 */
public final class FishingJournalCatalog extends SimpleJsonResourceReloadListener {
    public static final int MAX_ENTRIES = 512;
    public static final int MAX_TEXT_KEY = 128;
    public static final FishingJournalCatalog INSTANCE = new FishingJournalCatalog();
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/fishing");

    private Map<ResourceLocation, FishingJournalEntry> entries = Map.of();

    FishingJournalCatalog() {
        super(new Gson(), "fishing/journal");
    }

    public List<FishingJournalEntry> entries() {
        return List.copyOf(entries.values());
    }

    public boolean contains(ResourceLocation itemId) {
        return entries.containsKey(itemId);
    }

    public void clear() {
        entries = Map.of();
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> parsed,
                         ResourceManager resources, ProfilerFiller profiler) {
        Map<ResourceLocation, FishingJournalEntry> loaded = new LinkedHashMap<>();
        parsed.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(source -> {
            JsonObject root = GsonHelper.convertToJsonObject(source.getValue(), source.getKey().toString());
            if (!FishingDataGate.isAvailable(root)) {
                return;
            }
            for (JsonElement element : GsonHelper.getAsJsonArray(root, "entries")) {
                FishingJournalEntry entry = parseEntry(GsonHelper.convertToJsonObject(element, "entry"));
                if (!BuiltInRegistries.ITEM.containsKey(entry.itemId())
                        || BuiltInRegistries.ITEM.get(entry.itemId()) == Items.AIR) {
                    throw new IllegalArgumentException("Unknown journal item in " + source.getKey() + ": " + entry.itemId());
                }
                if (loaded.putIfAbsent(entry.itemId(), entry) != null) {
                    throw new IllegalArgumentException("Duplicate journal item in " + source.getKey() + ": " + entry.itemId());
                }
                if (loaded.size() > MAX_ENTRIES) {
                    throw new IllegalArgumentException("Fishing journal exceeds " + MAX_ENTRIES + " entries");
                }
            }
        });
        entries = java.util.Collections.unmodifiableMap(loaded);
        LOGGER.info("Loaded {} fishing journal entries", entries.size());
    }

    static FishingJournalEntry parseEntry(JsonObject json) {
        ResourceLocation itemId = new ResourceLocation(GsonHelper.getAsString(json, "item"));
        String category = GsonHelper.getAsString(json, "category");
        if (!category.matches("[a-z0-9_]{1,32}")) {
            throw new IllegalArgumentException("Invalid fishing journal category: " + category);
        }
        return new FishingJournalEntry(itemId, category, textKey(json, "description"),
                textKey(json, "habitat"), textKey(json, "conditions"));
    }

    private static String textKey(JsonObject json, String field) {
        String value = GsonHelper.getAsString(json, field);
        if (value.isBlank() || value.length() > MAX_TEXT_KEY) {
            throw new IllegalArgumentException("Invalid fishing journal translation key: " + field);
        }
        return value;
    }
}
