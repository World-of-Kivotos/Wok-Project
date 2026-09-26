package com.miningdim.job.fisher.size;

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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 体型档案目录, 数据包路径 {@code data/<namespace>/fishing/sizes/*.json}。只在服务端使用: 物品上只留档位,
 * 客户端显示不需要档案。
 *
 * 与图鉴目录同一套纪律: 可选 MOD 门控走 {@link FishingDataGate}; 物品不存在、重复或数值非法时整次拒绝发布,
 * 保留此前完整目录。没有档案的物品永远不做体型结算。
 */
public final class FishSizeCatalog extends SimpleJsonResourceReloadListener {
    public static final int MAX_ENTRIES = 512;
    public static final FishSizeCatalog INSTANCE = new FishSizeCatalog();
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/fishing");

    private volatile Map<ResourceLocation, FishSizeProfile> profiles = Map.of();

    public FishSizeCatalog() {
        super(new Gson(), "fishing/sizes");
    }

    public FishSizeProfile profile(ResourceLocation itemId) {
        return profiles.get(itemId);
    }

    public FishSizeProfile profile(ItemStack stack) {
        return stack.isEmpty() ? null : profiles.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    public Map<ResourceLocation, FishSizeProfile> profiles() {
        return profiles;
    }

    public void clear() {
        profiles = Map.of();
    }

    @Override
    public void apply(Map<ResourceLocation, JsonElement> parsed, ResourceManager resources, ProfilerFiller profiler) {
        Map<ResourceLocation, FishSizeProfile> loaded = new LinkedHashMap<>();
        parsed.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(source -> {
            JsonObject root = GsonHelper.convertToJsonObject(source.getValue(), source.getKey().toString());
            if (!FishingDataGate.isAvailable(root)) {
                return;
            }
            for (JsonElement element : GsonHelper.getAsJsonArray(root, "entries")) {
                FishSizeProfile profile = parseEntry(GsonHelper.convertToJsonObject(element, "entry"));
                if (!BuiltInRegistries.ITEM.containsKey(profile.itemId())
                        || BuiltInRegistries.ITEM.get(profile.itemId()) == Items.AIR) {
                    throw new IllegalArgumentException("Unknown fish size item in " + source.getKey() + ": " + profile.itemId());
                }
                if (loaded.putIfAbsent(profile.itemId(), profile) != null) {
                    throw new IllegalArgumentException("Duplicate fish size item in " + source.getKey() + ": " + profile.itemId());
                }
                if (loaded.size() > MAX_ENTRIES) {
                    throw new IllegalArgumentException("Fish size catalog exceeds " + MAX_ENTRIES + " entries");
                }
            }
        });
        profiles = Collections.unmodifiableMap(loaded);
        LOGGER.info("Loaded {} fish size profiles", profiles.size());
    }

    static FishSizeProfile parseEntry(JsonObject json) {
        return new FishSizeProfile(new ResourceLocation(GsonHelper.getAsString(json, "item")),
                GsonHelper.getAsDouble(json, "min_cm"),
                GsonHelper.getAsDouble(json, "common_cm"),
                GsonHelper.getAsDouble(json, "max_cm"),
                GsonHelper.getAsDouble(json, "a"),
                GsonHelper.getAsDouble(json, "b"));
    }
}
