package com.miningdim.title;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.ResourceLocationException;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 加载 {@code data/<ns>/titles/**.json} 的称号定义, 在 AddReloadListenerEvent 注册, 支持 /reload 热重载。
 *
 * 校验口径 (Title_System_DesignSpec 第三章): 未知 rarity、渐变色标不足 2 个或多于 3 个、颜色格式非法、
 * 缺少 text, 一律跳过该条并在日志告警, 不影响其余定义。刻意不整包抛错: 一个第三方数据包里的笔误不该让
 * 服务端连同全部内置称号一起加载失败。JSON 语法错误的文件在更早的 vanilla scanDirectory 阶段就已被
 * LOGGER.error 并丢弃, 到不了这里。
 */
public final class TitleDefinitionLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/title");
    private static final Gson GSON = new GsonBuilder().create();
    static final String DIRECTORY = "titles";

    /** 只接受 #RRGGBB: 颜色名 / 3 位简写都拒收, 同一个色板里不允许出现两种写法。 */
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final int MIN_GRADIENT_STOPS = 2;

    private final TitleDefinitions target;

    public TitleDefinitionLoader(TitleDefinitions target) {
        super(GSON, DIRECTORY);
        this.target = target;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> parsed,
                         ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, TitleDefinition> loaded = parseAll(parsed);
        target.install(loaded);
        LOGGER.info("[miningdim] loaded {} title definition(s), skipped {}",
                loaded.size(), parsed.size() - loaded.size());
    }

    /** 逐条解析; 不合格的条目告警后跳过, 结果按 id 排序以保证日志与遍历顺序稳定。 */
    static Map<ResourceLocation, TitleDefinition> parseAll(Map<ResourceLocation, JsonElement> parsed) {
        Map<ResourceLocation, TitleDefinition> loaded = new LinkedHashMap<>();
        List<Map.Entry<ResourceLocation, JsonElement>> entries = new ArrayList<>(parsed.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries) {
            try {
                loaded.put(entry.getKey(), parse(entry.getKey(), entry.getValue()));
            } catch (JsonParseException | IllegalArgumentException | ResourceLocationException invalid) {
                // GsonHelper 的类型错误是 JsonSyntaxException (JsonParseException 子类); Component 里写坏的
                // font 之类资源路径会抛 ResourceLocationException。逐条隔离是本加载器的约定。
                LOGGER.warn("[miningdim] skipping title definition {}: {}", entry.getKey(), invalid.getMessage());
            }
        }
        return loaded;
    }

    /** 解析单条定义; 任何不合格都抛 {@link JsonParseException} 并带上原因。 */
    static TitleDefinition parse(ResourceLocation id, JsonElement json) {
        JsonObject root = GsonHelper.convertToJsonObject(json, "title definition");

        if (!root.has("text")) {
            throw new JsonParseException("missing required field 'text'");
        }
        Component text = Component.Serializer.fromJson(root.get("text"));
        if (text == null) {
            throw new JsonParseException("field 'text' is not a valid text component");
        }

        String rarityId = GsonHelper.getAsString(root, "rarity");
        TierPalette rarity = TierPalette.byId(rarityId)
                .orElseThrow(() -> new JsonParseException("unknown rarity '" + rarityId + "'"));

        List<Integer> colorOverride = List.of();
        Boolean boldOverride = null;
        if (root.has("style")) {
            JsonObject style = GsonHelper.getAsJsonObject(root, "style");
            colorOverride = parseStyleColors(style);
            if (style.has("bold")) {
                boldOverride = GsonHelper.getAsBoolean(style, "bold");
            }
        }

        Component description = null;
        if (root.has("description")) {
            description = Component.Serializer.fromJson(root.get("description"));
        }

        int sort = GsonHelper.getAsInt(root, "sort", rarity.defaultSort());
        return new TitleDefinition(id, text, rarity, colorOverride, boldOverride, description, sort);
    }

    private static List<Integer> parseStyleColors(JsonObject style) {
        boolean hasColor = style.has("color");
        boolean hasGradient = style.has("gradient");
        if (hasColor && hasGradient) {
            throw new JsonParseException("style may declare either 'color' or 'gradient', not both");
        }
        if (hasColor) {
            return List.of(parseColor(GsonHelper.getAsString(style, "color")));
        }
        if (!hasGradient) {
            return List.of();
        }
        JsonArray gradient = GsonHelper.getAsJsonArray(style, "gradient");
        if (gradient.size() < MIN_GRADIENT_STOPS || gradient.size() > TierPalette.MAX_GRADIENT_STOPS) {
            throw new JsonParseException("gradient needs " + MIN_GRADIENT_STOPS + ".."
                    + TierPalette.MAX_GRADIENT_STOPS + " color stops, got " + gradient.size());
        }
        List<Integer> stops = new ArrayList<>(gradient.size());
        for (JsonElement stop : gradient) {
            stops.add(parseColor(GsonHelper.convertToString(stop, "gradient stop")));
        }
        return stops;
    }

    private static int parseColor(@Nullable String raw) {
        if (raw == null || !HEX_COLOR.matcher(raw).matches()) {
            throw new JsonParseException("invalid color '" + raw + "', expected #RRGGBB");
        }
        return Integer.parseInt(raw.substring(1), 16);
    }
}
