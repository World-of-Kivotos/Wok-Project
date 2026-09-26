package com.miningdim.achievement.shop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.miningdim.achievement.AchievementServices;
import net.minecraft.ResourceLocationException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加载 {@code data/<ns>/achievement_point_shop/**.json} 的成就点商店商品 (Achievement_System_DesignSpec 8.3), 在
 * AddReloadListenerEvent 注册, 支持 /reload 热重载; 结果整表装进 {@link AchievementServices#installPointShop}。
 *
 * 写坏的条目告警后跳过, 不影响其余商品: 一件商品的笔误不该让整个成就点商店下架。首批商品等 P3 美术 (第十四章待定项 3),
 * 模组本身不带任何商品文件。
 */
public final class PointShopLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/achievement");
    private static final Gson GSON = new GsonBuilder().create();

    /** 数据包目录名。 */
    public static final String DIRECTORY = "achievement_point_shop";

    public PointShopLoader() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> parsed, ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, PointShopGoods> loaded = parseAll(parsed);
        AchievementServices.installPointShop(PointShopCatalog.of(loaded.values()));
        LOGGER.info("[miningdim] loaded {} achievement point shop goods, skipped {}", loaded.size(),
                parsed.size() - loaded.size());
    }

    /** 逐条解析; 不合格的条目告警后跳过, 结果按 id 排序以保证日志与遍历顺序稳定。 */
    public static Map<ResourceLocation, PointShopGoods> parseAll(Map<ResourceLocation, JsonElement> parsed) {
        Map<ResourceLocation, PointShopGoods> loaded = new LinkedHashMap<>();
        List<Map.Entry<ResourceLocation, JsonElement>> entries = new ArrayList<>(parsed.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries) {
            try {
                loaded.put(entry.getKey(), PointShopGoods.fromJson(entry.getKey(), entry.getValue()));
            } catch (JsonParseException | IllegalArgumentException | ResourceLocationException invalid) {
                LOGGER.warn("[miningdim] skipping achievement point shop goods {}: {}", entry.getKey(),
                        invalid.getMessage());
            }
        }
        return loaded;
    }
}
