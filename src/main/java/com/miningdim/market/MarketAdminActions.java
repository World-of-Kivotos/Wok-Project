package com.miningdim.market;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

/**
 * 基准价值 V0 admin curate 动作 (OP 门控, 偏离费锚的人工 curate 入口)。游戏内 OP 经 WebUI 面板枚举全注册物品、
 * 逐个设 V0 覆盖 (写 base_values 表, 优先于代码预设)。
 *
 * 权限 (用户决策: 游戏内 OP 改): 每个动作先过 {@link #requireOp} 校验 sender.hasPermissions(2) (原版 OP/gamemaster 级),
 * 非 OP 自然抛 IllegalStateException, 经 {@link WebUiServerDispatcher#dispatchAndRespond} Gateway 转 success=false。
 * 服务端权威: 操作者身份取 sender (不信前端 uuid)。
 *
 * 中文名解析在客户端: 服务端只回 descriptionId (翻译键, 如 item.minecraft.diamond); 把键解析成"钻石"需 zh_cn.json,
 * 而专用服务器不加载 lang -> 由客户端 (MCEF 宿主所在客户端 JVM) 经 i18n 桥解析后填面板 (见 webui client 侧)。
 */
public final class MarketAdminActions {

    private static final Gson GSON = new Gson();

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private MarketAdminActions() {
    }

    /** 注册 admin.* curate 动作 (由 MarketSubsystem.register 调用)。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("admin.setBaseValue", SET_BASE_VALUE);
        WebUiServerDispatcher.register("admin.listItems", LIST_ITEMS);
    }

    // ============================================================
    // admin.setBaseValue: {itemId, v0} -> {ok, itemId, v0}  (OP 门控)
    // ============================================================

    static final WebUiAction SET_BASE_VALUE = (sender, payload) -> {
        requireOp(sender);
        // 业务字段必填: 缺失自然抛冒泡到 Gateway。
        String itemId = payload.get("itemId").getAsString();
        long v0 = payload.get("v0").getAsLong();
        // v0 下界/合法性由引擎 setBaseValueOverride 校验 (>= MIN_ANCHOR_VALUE), 越界自然抛。
        MarketServices.marketEngine().setBaseValueOverride(itemId, v0, sender.getUUID());

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("itemId", itemId);
        result.addProperty("v0", v0);
        return GSON.toJson(result);
    };

    // ============================================================
    // admin.listItems: {query?,page?,pageSize?} -> {items:[{itemId,descriptionId,v0,source}],page,pageSize,total} (OP 门控)
    // ============================================================

    static final WebUiAction LIST_ITEMS = (sender, payload) -> {
        requireOp(sender);
        String query = optString(payload, "query", "").toLowerCase(Locale.ROOT);
        int page = Math.max(0, optInt(payload, "page", DEFAULT_PAGE));
        int pageSize = clamp(optInt(payload, "pageSize", DEFAULT_PAGE_SIZE), 1, MAX_PAGE_SIZE);

        // 一次性拉全部 admin 覆盖 (内存查表, 避免逐物品点查 DB)。
        Map<String, Long> overrides = MarketServices.marketEngine().baseValueOverrides();

        // 枚举全注册物品 (含所有 mod), 按 query 过滤 item_id 子串, 排序后分页 (确定性翻页)。
        List<ResourceLocation> matched = new ArrayList<>();
        for (ResourceLocation key : ForgeRegistries.ITEMS.getKeys()) {
            if (query.isEmpty() || key.toString().toLowerCase(Locale.ROOT).contains(query)) {
                matched.add(key);
            }
        }
        matched.sort(Comparator.comparing(ResourceLocation::toString));
        int total = matched.size();
        int from = Math.min(page * pageSize, total);
        int to = Math.min(from + pageSize, total);

        JsonArray items = new JsonArray();
        for (ResourceLocation key : matched.subList(from, to)) {
            String itemId = key.toString();
            Item item = ForgeRegistries.ITEMS.getValue(key);
            JsonObject o = new JsonObject();
            o.addProperty("itemId", itemId);
            // descriptionId = 翻译键 (服务端可得); 中文名由客户端解析。
            o.addProperty("descriptionId", item == null ? "" : item.getDescriptionId());
            // 当前生效 V0 与来源: admin 覆盖 > 代码预设 > 无 (无锚物品挂单走平率)。
            Long override = overrides.get(itemId);
            if (override != null) {
                o.addProperty("v0", override);
                o.addProperty("source", "override");
            } else {
                OptionalLong preset = DefaultBaseValues.resolve(itemId);
                if (preset.isPresent()) {
                    o.addProperty("v0", preset.getAsLong());
                    o.addProperty("source", "preset");
                } else {
                    o.add("v0", JsonNull.INSTANCE);
                    o.addProperty("source", "none");
                }
            }
            items.add(o);
        }

        JsonObject result = new JsonObject();
        result.add("items", items);
        result.addProperty("page", page);
        result.addProperty("pageSize", pageSize);
        result.addProperty("total", total);
        return GSON.toJson(result);
    };

    // ============================================================
    // 权限 + payload helper
    // ============================================================

    /**
     * OP 门控: 非 OP 自然抛, 经 Gateway 转 success=false。服务端权威, 不信前端。
     * 用 {@code PlayerList.isOp(GameProfile)} (确定的公开 API) 而非 {@code hasPermissions(int)}
     * (其在 ServerPlayer 上的语义跨版本不一, 避免误判)。
     */
    private static void requireOp(ServerPlayer sender) {
        if (!sender.getServer().getPlayerList().isOp(sender.getGameProfile())) {
            throw new IllegalStateException("需要 OP 权限才能 curate 基准价值");
        }
    }

    private static String optString(JsonObject payload, String key, String fallback) {
        return payload.has(key) && !payload.get(key).isJsonNull() ? payload.get(key).getAsString() : fallback;
    }

    private static int optInt(JsonObject payload, String key, int fallback) {
        return payload.has(key) && !payload.get(key).isJsonNull() ? payload.get(key).getAsInt() : fallback;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
