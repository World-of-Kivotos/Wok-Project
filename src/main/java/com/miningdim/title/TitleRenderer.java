package com.miningdim.title;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 称号的显示用 Component, 聊天前缀、Tab 列表、头顶名牌三处共用 (Title_System_DesignSpec 第五章)。
 *
 * <ul>
 *   <li>{@link #badge}: {@code [称号]}, 名牌单独一行与各类提示消息使用;</li>
 *   <li>{@link #prefix}: {@code [称号] }, 拼在显示名前面 (聊天、Tab)。</li>
 * </ul>
 * 两者按称号 id 缓存; 定义集合每次被数据包重载替换 ({@link TitleDefinitions#generation()} 变化) 即整表作废。
 * 显示路径因此只读内存, 不查库也不重复拆字。缓存只在服务端主线程读写, 用普通 HashMap。
 *
 * <p>渐变档的文字解析: 逐字上色要求服务端先拿到称号的纯文本, 而 translate 文本本来是由各客户端按自己的语言
 * 解析的。专用服务端不加载 {@code assets/} 下的语言文件, 所以这里按以下顺序解析:
 * {@link Language#getInstance()} (集成服务端即房主客户端的语言) → 模组自带的 {@value #SERVER_DISPLAY_LANGUAGE}
 * 语言表 (本服玩家群体的语言) → translate 的 fallback → 键名本身。单色档不需要拆字, 直接保留 translate,
 * 仍由各客户端按自己的语言显示。
 */
public final class TitleRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/title");

    /** 专用服务端解析渐变称号文字所用的语言 (本服玩家群体的语言)。 */
    static final String SERVER_DISPLAY_LANGUAGE = "zh_cn";
    private static final String BUNDLED_LANGUAGE_PATH =
            "assets/miningdim/lang/" + SERVER_DISPLAY_LANGUAGE + ".json";
    /** 模组语言表里只取称号模块自己的键。 */
    private static final String TITLE_KEY_PREFIX = "title.";

    private final TitleDefinitions definitions;
    private final Map<ResourceLocation, Rendered> cache = new HashMap<>();
    private int cachedGeneration = -1;

    public TitleRenderer(TitleDefinitions definitions) {
        this.definitions = definitions;
    }

    /** {@code [称号] }; 定义缺失 (被删除或拼错) 返回 null, 调用方据此不显示任何前缀。 */
    @Nullable
    public Component prefix(ResourceLocation titleId) {
        Rendered rendered = rendered(titleId);
        return rendered == null ? null : rendered.prefix();
    }

    /** {@code [称号]}; 定义缺失返回 null。 */
    @Nullable
    public Component badge(ResourceLocation titleId) {
        Rendered rendered = rendered(titleId);
        return rendered == null ? null : rendered.badge();
    }

    @Nullable
    private Rendered rendered(ResourceLocation titleId) {
        int generation = definitions.generation();
        if (generation != cachedGeneration) {
            cache.clear();
            cachedGeneration = generation;
        }
        Rendered cached = cache.get(titleId);
        if (cached != null) {
            return cached;
        }
        TitleDefinition definition = definitions.get(titleId).orElse(null);
        if (definition == null) {
            // 不缓存"缺失": 定义集合一换代就会整表作废, 这里也没有需要防御的高频缺失查询。
            return null;
        }
        Component badge = renderBadge(definition);
        Rendered rendered = new Rendered(badge, Component.empty().append(badge).append(" "));
        cache.put(titleId, rendered);
        return rendered;
    }

    /**
     * 渲染 {@code [称号]}。渐变档把方括号连同文字一起逐字上色 (首尾括号恰为首尾色标);
     * 单色档整段一个样式, 文字保留原 Component (translate 仍由客户端按其语言解析)。
     */
    public static MutableComponent renderBadge(TitleDefinition definition) {
        int[] colors = definition.colors();
        boolean bold = definition.bold();
        if (definition.isGradient()) {
            return TierPalette.gradient("[" + resolveText(definition.text()) + "]", colors, bold);
        }
        Style style = Style.EMPTY.withColor(TextColor.fromRgb(colors[0])).withBold(bold);
        return Component.literal("[").append(definition.text().copy()).append("]").withStyle(style);
    }

    /** 解析称号文字为纯文本, 顺序见类注释。只对"无参数、无子节点的 translate"查表, 其余直接取 getString。 */
    public static String resolveText(Component text) {
        if (text.getContents() instanceof TranslatableContents translatable
                && translatable.getArgs().length == 0
                && text.getSiblings().isEmpty()) {
            String key = translatable.getKey();
            Language language = Language.getInstance();
            if (language.has(key)) {
                return language.getOrDefault(key);
            }
            String bundled = BundledLanguage.TABLE.get(key);
            if (bundled != null) {
                return bundled;
            }
            if (translatable.getFallback() != null) {
                return translatable.getFallback();
            }
            return key;
        }
        return text.getString();
    }

    private record Rendered(Component badge, Component prefix) {
    }

    /** 模组自带语言表里的称号键, 首次用到时从 JAR 读一次 (惰性持有者)。 */
    private static final class BundledLanguage {
        static final Map<String, String> TABLE = load();

        private static Map<String, String> load() {
            Map<String, String> table = new HashMap<>();
            ClassLoader loader = TitleRenderer.class.getClassLoader();
            try (InputStream stream = loader.getResourceAsStream(BUNDLED_LANGUAGE_PATH)) {
                if (stream == null) {
                    LOGGER.warn("[miningdim] bundled language {} not found; gradient titles fall back to keys",
                            BUNDLED_LANGUAGE_PATH);
                    return table;
                }
                try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    JsonObject root = GsonHelper.parse(reader);
                    for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                        if (entry.getKey().startsWith(TITLE_KEY_PREFIX) && entry.getValue().isJsonPrimitive()) {
                            table.put(entry.getKey(), entry.getValue().getAsString());
                        }
                    }
                }
            } catch (Exception failure) {
                // 语言表读不出来只影响渐变称号在专用服务端的文字 (退回键名), 不值得让称号系统整体失效。
                LOGGER.warn("[miningdim] failed to read bundled language {}", BUNDLED_LANGUAGE_PATH, failure);
            }
            return table;
        }
    }
}
