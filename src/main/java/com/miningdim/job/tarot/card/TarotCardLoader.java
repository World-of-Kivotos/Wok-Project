package com.miningdim.job.tarot.card;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.miningdim.job.tarot.TarotArcana;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

/**
 * 塔罗牌效数值表 datapack 加载器 (TarotReader spec 第十一章; 仿 ORE_USE_DATAPACK 先例)。加载
 * data/miningdim/tarot/cards/*.json, 每张牌一份, 解析为 {@link TarotCardData}。
 *
 * reload 失败报错冒泡 (spec C9): 缺 22 张中任意一张、JSON 结构错误、缺必填字段, 都在 apply 期抛
 * IllegalStateException/JsonSyntaxException, 由原版资源重载边界报告 (不静默给默认效果)。
 *
 * 注册: 在 AddReloadListenerEvent (forgeBus) 内 event.addListener(this) (服务端数据包重载)。
 * 取用: {@link #get(TarotArcana)} 返回当前已加载表; 重载前/失败后取用抛出, 暴露装配问题。
 */
public final class TarotCardLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/tarot");
    private static final Gson GSON = new GsonBuilder().create();
    /** 数据目录: data/<ns>/tarot/cards/*.json (SimpleJsonResourceReloadListener 的 directory 参数)。 */
    private static final String DIRECTORY = "tarot/cards";

    private volatile Map<TarotArcana, TarotCardData> cards = new EnumMap<>(TarotArcana.class);

    public TarotCardLoader() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> parsed, ResourceManager manager, ProfilerFiller profiler) {
        Map<TarotArcana, TarotCardData> loaded = new EnumMap<>(TarotArcana.class);
        for (TarotArcana arcana : TarotArcana.values()) {
            ResourceLocation key = arcana.dataKey();
            JsonElement el = parsed.get(key);
            if (el == null) {
                // 缺张即数据包不完整 (spec C9: 不静默给默认, 直接报错冒泡让运维修复)。
                throw new IllegalStateException("Missing tarot card datapack entry: " + key
                        + " (expected data/" + key.getNamespace() + "/" + DIRECTORY + "/"
                        + String.format("%02d_%s.json", arcana.cardId(), arcana.id()) + ")");
            }
            TarotCardData data = TarotCardData.fromJson(GsonHelper.convertToJsonObject(el, "tarot card"));
            loaded.put(arcana, data);
        }
        this.cards = loaded;
        LOGGER.info("[miningdim] loaded {} tarot card effect tables", loaded.size());
    }

    /** 取某牌的效果数值表; 未加载 (重载前/失败) 抛出冒泡 (装配缺陷暴露, 不静默)。 */
    public TarotCardData get(TarotArcana arcana) {
        TarotCardData data = cards.get(arcana);
        if (data == null) {
            throw new IllegalStateException("Tarot card data not loaded for " + arcana
                    + " (datapack reload not completed or failed)");
        }
        return data;
    }

    /** 是否已加载全部 22 张 (供用牌前快速判定; 未加载时 use 应拒绝并提示)。 */
    public boolean isLoaded() {
        return cards.size() == TarotArcana.COUNT;
    }
}
