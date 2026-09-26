package com.miningdim.title;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiPayloads;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import com.miningdim.webui.server.WebUiTextJson;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * G 面板"我的称号"页签的 title.* WebUiAction (Title_System_DesignSpec 第七章、13.8), 由 {@link TitleSystem#register} 注册。
 *
 * <ul>
 *   <li>{@code title.list} (只读, 可进批): 全部数据包称号 (含未拥有的, 带获取说明)、拥有状态、当前佩戴、七档色板,
 *       以及本人的赞助与专属称号状态 (资格、记录、下次可修改时间、自助提交开关);</li>
 *   <li>{@code title.equip}: 佩戴或卸下 ({@code titleId: null});</li>
 *   <li>{@code title.customPreview}: 专属称号草稿的权威校验, 通过时回渲染好的徽记与可复制给管理员的参数;</li>
 *   <li>{@code title.customSet}: 玩家自助提交; 自助提交关闭 (默认) 时明确拒绝。</li>
 * </ul>
 *
 * 称号文字一律经 {@link WebUiTextJson} 发成"片段 + 颜色 + 粗体", 页面照着画, 与聊天里看到的逐字相同 (渐变档在服务端
 * 已经逐字上色, 前端不再算第二遍)。全部可空字段显式序列化为 JSON null。
 */
public final class TitleWebUiActions {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /** 草稿文字的长度上限 (UTF-16 码元)。只为挡住超长输入, 真正的长度规则由校验器按码点报告。 */
    private static final int MAX_DRAFT_TEXT_CHARS = 256;
    /** 草稿色标条数上限。多于 3 个由校验器报 COLOR_COUNT, 这里只挡住离谱的数组。 */
    private static final int MAX_DRAFT_COLORS = 8;
    /** 单个色标原文的长度上限; 校验器会把写错的色标原样回显进不合格项。 */
    private static final int MAX_DRAFT_COLOR_CHARS = 32;

    /** 管理员代设置命令的前缀 (13.7), 玩家复制后发给管理员。 */
    private static final String ADMIN_SET_COMMAND = "/mtitle custom admin set ";

    private TitleWebUiActions() {
    }

    /** 把四条 title.* action 注册进派发器。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("title.list", LIST);
        WebUiServerDispatcher.register("title.equip", EQUIP);
        WebUiServerDispatcher.register("title.customPreview", CUSTOM_PREVIEW);
        WebUiServerDispatcher.register("title.customSet", CUSTOM_SET);
    }

    static final WebUiAction LIST = (sender, payload) -> {
        ITitleService titles = TitleServices.titleService();
        UUID uuid = sender.getUUID();
        Set<ResourceLocation> owned = titles.owned(uuid);
        ResourceLocation equipped = titles.equipped(uuid).orElse(null);

        JsonArray rows = new JsonArray();
        for (TitleDefinition definition : titles.definitions()) {
            JsonObject row = new JsonObject();
            row.addProperty("titleId", definition.id().toString());
            // definitions() 只含数据包称号, 它们必有稀有度 (专属称号不在其中, 走 custom 块)。
            row.addProperty("rarity", definition.rarity().id());
            row.addProperty("sort", definition.sort());
            row.addProperty("owned", owned.contains(definition.id()));
            row.addProperty("equipped", definition.id().equals(equipped));
            row.add("badge", WebUiTextJson.segments(TitleRenderer.renderBadge(definition)));
            Component description = definition.description();
            row.add("description", description == null ? JsonNull.INSTANCE : WebUiTextJson.segments(description));
            rows.add(row);
        }

        JsonObject result = new JsonObject();
        result.add("equipped", equipped == null ? JsonNull.INSTANCE : new JsonPrimitive(equipped.toString()));
        result.add("palette", palette());
        result.add("titles", rows);
        result.add("custom", customState(titles, uuid, owned, equipped));
        return GSON.toJson(result);
    };

    static final WebUiAction EQUIP = (sender, payload) -> {
        ResourceLocation titleId = optionalTitleId(payload);
        EquipResult outcome = TitleServices.titleService().equip(sender, titleId);
        switch (outcome) {
            case EQUIPPED, UNEQUIPPED -> {
                JsonObject result = new JsonObject();
                result.add("equipped", titleId == null ? JsonNull.INSTANCE : new JsonPrimitive(titleId.toString()));
                return GSON.toJson(result);
            }
            case NOT_OWNED -> throw new WebUiBusinessException(WebUiErrorCodes.TITLE_NOT_OWNED,
                    "你还没有这个称号: " + titleId, false, Map.of("titleId", String.valueOf(titleId)));
            case UNKNOWN_TITLE -> throw new WebUiBusinessException(WebUiErrorCodes.TITLE_UNKNOWN,
                    "称号不存在或已下架: " + titleId, false, Map.of("titleId", String.valueOf(titleId)));
            default -> throw new IllegalStateException("unhandled equip result " + outcome);
        }
    };

    static final WebUiAction CUSTOM_PREVIEW = (sender, payload) -> {
        CustomTitleDraft draft = parseDraft(payload);
        ITitleService titles = TitleServices.titleService();
        CustomTitleResult outcome = titles.previewCustomTitle(sender.getUUID(), draft);
        JsonObject result = new JsonObject();
        result.addProperty("selfServiceEnabled", titles.customSelfServiceEnabled());
        switch (outcome.status()) {
            case VALID -> {
                CustomTitleStyle style = outcome.style();
                String spec = spec(style);
                result.addProperty("status", outcome.status().name());
                result.add("violations", new JsonArray());
                result.add("badge", customBadge(sender.getUUID(), style));
                result.addProperty("spec", spec);
                result.addProperty("adminCommand", ADMIN_SET_COMMAND + sender.getGameProfile().getName() + " " + spec);
                result.addProperty("nextEditAt", outcome.nextEditAt());
            }
            case INVALID -> {
                result.addProperty("status", outcome.status().name());
                result.add("violations", violations(outcome.violations()));
                result.add("badge", JsonNull.INSTANCE);
                result.add("spec", JsonNull.INSTANCE);
                result.add("adminCommand", JsonNull.INSTANCE);
                result.addProperty("nextEditAt", outcome.nextEditAt());
            }
            case NOT_SPONSOR -> throw notSponsor();
            default -> throw new IllegalStateException("unexpected preview result " + outcome.status());
        }
        return GSON.toJson(result);
    };

    static final WebUiAction CUSTOM_SET = (sender, payload) -> {
        CustomTitleDraft draft = parseDraft(payload);
        CustomTitleResult outcome = TitleServices.titleService().setCustomTitle(sender, draft);
        switch (outcome.status()) {
            case APPLIED -> {
                JsonObject result = new JsonObject();
                result.addProperty("status", outcome.status().name());
                result.addProperty("titleId", CustomTitle.idOf(sender.getUUID()).toString());
                result.add("badge", customBadge(sender.getUUID(), outcome.style()));
                result.addProperty("nextEditAt", outcome.nextEditAt());
                return GSON.toJson(result);
            }
            case NOT_SPONSOR -> throw notSponsor();
            case SELF_SERVICE_DISABLED -> throw new WebUiBusinessException(
                    WebUiErrorCodes.CUSTOM_TITLE_SELF_SERVICE_DISABLED,
                    "专属称号目前由管理员设置, 请把预览满意的参数发给管理员", false);
            case LOCKED -> throw new WebUiBusinessException(WebUiErrorCodes.CUSTOM_TITLE_LOCKED,
                    "专属称号已被管理员锁定", false);
            case ON_COOLDOWN -> throw new WebUiBusinessException(WebUiErrorCodes.CUSTOM_TITLE_ON_COOLDOWN,
                    "专属称号修改冷却中", false, Map.of("nextEditAt", Long.toString(outcome.nextEditAt())));
            case INVALID -> throw new WebUiBusinessException(WebUiErrorCodes.CUSTOM_TITLE_INVALID,
                    "专属称号没有通过校验", false, Map.of(
                    "count", Integer.toString(outcome.violations().size()),
                    "rules", outcome.violations().stream().map(violation -> violation.rule().name())
                            .collect(Collectors.joining(","))));
            default -> throw new IllegalStateException("unexpected set result " + outcome.status());
        }
    };

    /** 七档色板 (真源 {@link TierPalette}): 页面的"套用档位配色"按钮用它, 不在前端另抄一份色值。 */
    private static JsonArray palette() {
        JsonArray tiers = new JsonArray();
        for (TierPalette tier : TierPalette.values()) {
            JsonObject row = new JsonObject();
            row.addProperty("rarity", tier.id());
            row.add("stops", hexColors(tier.stops()));
            row.addProperty("bold", tier.bold());
            tiers.add(row);
        }
        return tiers;
    }

    /**
     * 本人的赞助与专属称号状态。只给本人看, 所以可以带锁定状态; 执行锁定的管理员不点名 (与 custom info 同口径)。
     * 自助提交关闭时 nextEditAt 照发真值, 由页面决定不展示 (玩家本来就不能自己改)。
     */
    private static JsonObject customState(ITitleService titles, UUID uuid, Set<ResourceLocation> owned,
                                          @Nullable ResourceLocation equipped) {
        CustomTitleInfo info = titles.customTitleInfo(uuid);
        ResourceLocation customId = CustomTitle.idOf(uuid);
        JsonObject state = new JsonObject();
        state.addProperty("titleId", customId.toString());
        state.addProperty("selfServiceEnabled", titles.customSelfServiceEnabled());
        SponsorStatus sponsor = info.sponsor();
        if (sponsor == null) {
            state.add("sponsor", JsonNull.INSTANCE);
        } else {
            JsonObject row = new JsonObject();
            row.addProperty("permanent", sponsor.permanent());
            row.addProperty("expiresAt", sponsor.expiresAt());
            row.addProperty("active", info.sponsorActive());
            state.add("sponsor", row);
        }
        CustomTitle custom = info.custom();
        if (custom == null) {
            state.add("record", JsonNull.INSTANCE);
        } else {
            CustomTitleStyle style = custom.style();
            JsonObject row = new JsonObject();
            row.addProperty("text", style.text());
            row.add("colors", hexColors(style.colors().stream().mapToInt(Integer::intValue).toArray()));
            row.addProperty("bold", style.bold());
            row.addProperty("locked", custom.locked());
            row.add("badge", customBadge(uuid, style));
            state.add("record", row);
        }
        state.addProperty("owned", owned.contains(customId));
        state.addProperty("equipped", customId.equals(equipped));
        state.addProperty("nextEditAt", info.nextEditAt());
        return state;
    }

    private static JsonArray customBadge(UUID player, CustomTitleStyle style) {
        return WebUiTextJson.segments(TitleRenderer.renderBadge(TitleDefinition.custom(CustomTitle.idOf(player),
                style)));
    }

    /** 命令参数形式 {@code <颜色> <粗体> <文字>} (13.7), 与 /mtitle custom preview 的写法相同。 */
    private static String spec(CustomTitleStyle style) {
        return style.colorsText() + " " + style.bold() + " " + style.text();
    }

    private static JsonArray violations(List<CustomTitleViolation> violations) {
        JsonArray rows = new JsonArray();
        for (CustomTitleViolation violation : violations) {
            JsonObject row = new JsonObject();
            row.addProperty("rule", violation.rule().name());
            JsonArray args = new JsonArray();
            violation.args().forEach(args::add);
            row.add("args", args);
            rows.add(row);
        }
        return rows;
    }

    private static JsonArray hexColors(int[] colors) {
        JsonArray out = new JsonArray();
        for (int color : colors) {
            out.add(String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF));
        }
        return out;
    }

    /** {@code titleId} 必须出现: 字符串为要佩戴的称号, JSON null 为卸下。缺键与写不成资源 id 的都按入参非法拒绝。 */
    @Nullable
    private static ResourceLocation optionalTitleId(JsonObject payload) {
        JsonElement raw = payload.get("titleId");
        if (raw == null) {
            throw new WebUiBusinessException(WebUiErrorCodes.INVALID_REQUEST, "缺少必填字段 titleId", false,
                    Map.of("field", "titleId"));
        }
        if (raw.isJsonNull()) {
            return null;
        }
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()) {
            throw WebUiPayloads.wrongType("titleId", "字符串或 null");
        }
        String text = raw.getAsString();
        return Optional.ofNullable(ResourceLocation.tryParse(text))
                .orElseThrow(() -> WebUiPayloads.illegalValue("titleId", text, "称号 id 写法不对: " + text));
    }

    /** 草稿入参 {@code {colors: string[], bold: boolean, text: string}}; 内容是否合格交给校验器逐条报告。 */
    private static CustomTitleDraft parseDraft(JsonObject payload) {
        String text = WebUiPayloads.requiredString(payload, "text");
        if (text.length() > MAX_DRAFT_TEXT_CHARS) {
            throw WebUiPayloads.illegalValue("text", text, "称号文字过长");
        }
        boolean bold = WebUiPayloads.requiredBoolean(payload, "bold");
        JsonElement rawColors = WebUiPayloads.requiredField(payload, "colors");
        if (!rawColors.isJsonArray()) {
            throw WebUiPayloads.wrongType("colors", "字符串数组");
        }
        JsonArray colorArray = rawColors.getAsJsonArray();
        if (colorArray.size() > MAX_DRAFT_COLORS) {
            throw WebUiPayloads.illegalValue("colors", Integer.toString(colorArray.size()), "色标太多");
        }
        List<String> colors = new ArrayList<>(colorArray.size());
        for (JsonElement element : colorArray) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw WebUiPayloads.wrongType("colors", "字符串数组");
            }
            String color = element.getAsString();
            if (color.length() > MAX_DRAFT_COLOR_CHARS) {
                throw WebUiPayloads.illegalValue("colors", color, "色标写法过长");
            }
            colors.add(color);
        }
        return new CustomTitleDraft(text, colors, bold);
    }

    private static WebUiBusinessException notSponsor() {
        return new WebUiBusinessException(WebUiErrorCodes.CUSTOM_TITLE_NOT_SPONSOR,
                "没有有效的赞助资格, 不能使用专属称号", false);
    }
}
