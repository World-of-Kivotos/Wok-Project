package com.miningdim.achievement.meta;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
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
 * 加载 {@code data/<ns>/achievement_meta/**.json} 的成就元数据 (Achievement_System_DesignSpec 4.1), 在
 * AddReloadListenerEvent 注册, 支持 /reload 热重载。元数据 id 与进度 id 一一对应 (同一路径)。
 *
 * 写坏的条目告警后跳过, 不影响其余条目: 缺了元数据的进度在一致性校验里会被报出来, 并按"0 点、无称号"处理 ——
 * 宁可少发, 不可错发。
 */
public final class AchievementMetaLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/achievement");
    private static final Gson GSON = new GsonBuilder().create();

    /** 数据包目录名。 */
    public static final String DIRECTORY = "achievement_meta";

    private final AchievementMetas target;

    public AchievementMetaLoader(AchievementMetas target) {
        super(GSON, DIRECTORY);
        this.target = target;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> parsed, ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, AchievementMeta> loaded = parseAll(parsed);
        target.install(loaded);
        LOGGER.info("[miningdim] loaded {} achievement meta file(s), skipped {}", loaded.size(),
                parsed.size() - loaded.size());
    }

    /** 逐条解析; 不合格的条目告警后跳过, 结果按 id 排序以保证日志与遍历顺序稳定。 */
    public static Map<ResourceLocation, AchievementMeta> parseAll(Map<ResourceLocation, JsonElement> parsed) {
        Map<ResourceLocation, AchievementMeta> loaded = new LinkedHashMap<>();
        List<Map.Entry<ResourceLocation, JsonElement>> entries = new ArrayList<>(parsed.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries) {
            try {
                loaded.put(entry.getKey(), AchievementMeta.fromJson(entry.getKey(), entry.getValue()));
            } catch (JsonParseException | IllegalArgumentException invalid) {
                // GsonHelper 的类型错误是 JsonSyntaxException (JsonParseException 子类); 逐条隔离是本加载器的约定。
                LOGGER.warn("[miningdim] skipping achievement meta {}: {}", entry.getKey(), invalid.getMessage());
            }
        }
        return loaded;
    }
}
