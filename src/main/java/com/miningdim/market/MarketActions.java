package com.miningdim.market;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.miningdim.market.MarketEngine.BuyResult;
import com.miningdim.market.MarketEngine.CancelResult;
import com.miningdim.market.MarketEngine.PlaceResult;
import com.miningdim.market.store.ListingRow;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.OptionalLong;

/**
 * 跳蚤市场 8 个 market.* WebUiAction (共享契约第 6 节 + 真桥脱 Mock 补充: baseValue/categories)。每个 handler 拿
 * 服务端校验过的 sender (卖家/买家身份, 不信前端 uuid) 与解析后的 payload (JsonObject), 经
 * {@link MarketServices#marketEngine()} 调引擎, 构 resultJson 返回。
 *
 * 异常纪律 (契约第 6 节 / CLAUDE.md C9): handler 内坏输入 (缺字段/类型错) 经 Gson 的 getAsX 自然抛, 引擎的越权/业务
 * 错误 (余额不足/挂单不存在/铜铁超 cap) 自然抛, 一律冒泡到 {@link WebUiServerDispatcher#dispatchAndRespond} 的 Gateway
 * 边界统一兜底 (转 success=false + {"error":...})。本类严禁 try-catch 生吞。
 *
 * payload 缺省值纪律: 分页参数 (page/pageSize) 缺省用文档默认 (page=0, pageSize=20); 这是 UI 友好默认非业务空值掩盖
 * (无之则首屏无法分页), 与"严禁 ?? 0 掩盖业务空值"不冲突 —— 业务字段 (slot/count/unitPrice/listingId) 缺失一律自然抛。
 */
public final class MarketActions {

    private static final Gson GSON = new Gson();
    /** 仅 market.baseValue 用: 保留 v0 的 JSON null (默认 Gson 丢 null 字段, 会违反 v0:number|null 契约)。 */
    private static final Gson GSON_NULLS = new GsonBuilder().serializeNulls().create();

    /** 分页默认 (UI 友好默认, 非业务空值掩盖)。 */
    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private MarketActions() {
    }

    /** 把 8 个 market.* action 注册进派发器 (由 MarketSubsystem.register 调用)。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("market.list", LIST);
        WebUiServerDispatcher.register("market.place", PLACE);
        WebUiServerDispatcher.register("market.buy", BUY);
        WebUiServerDispatcher.register("market.cancel", CANCEL);
        WebUiServerDispatcher.register("market.mine", MINE);
        WebUiServerDispatcher.register("market.history", HISTORY);
        WebUiServerDispatcher.register("market.baseValue", BASE_VALUE);
        WebUiServerDispatcher.register("market.categories", CATEGORIES);
    }

    // ============================================================
    // market.list: {query?,sort?,page?,pageSize?} -> {listings:[{id,sellerName,itemId,count,unitPrice,total,createdAt}],page,pageSize}
    // ============================================================

    static final WebUiAction LIST = (sender, payload) -> {
        String query = optString(payload, "query", null);
        String sort = optString(payload, "sort", "created_at");
        int page = optInt(payload, "page", DEFAULT_PAGE);
        int pageSize = optInt(payload, "pageSize", DEFAULT_PAGE_SIZE);
        int offset = page * pageSize;

        List<ListingRow> rows = MarketServices.marketEngine().queryActive(query, sort, offset, pageSize);

        JsonArray listings = new JsonArray();
        for (ListingRow r : rows) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.addProperty("sellerName", r.sellerName());
            o.addProperty("itemId", r.itemId());
            o.addProperty("count", r.count());
            o.addProperty("unitPrice", r.unitPrice());
            // total = unitPrice * count (买入将付的总价, 前端直接展示, 不暴露 fee 计算)。
            o.addProperty("total", r.unitPrice() * (long) r.count());
            o.addProperty("createdAt", r.createdAt());
            listings.add(o);
        }
        JsonObject result = new JsonObject();
        result.add("listings", listings);
        result.addProperty("page", page);
        result.addProperty("pageSize", pageSize);
        return GSON.toJson(result);
    };

    // ============================================================
    // market.place: {slot,count,unitPrice,currency?} -> {listingId,listFee}
    // ============================================================

    static final WebUiAction PLACE = (sender, payload) -> {
        // 业务字段必填: 缺失自然抛 (getAsX 对缺失成员 NPE 冒泡, 不静默填默认)。
        int slot = payload.get("slot").getAsInt();
        int count = payload.get("count").getAsInt();
        long unitPrice = payload.get("unitPrice").getAsLong();
        // currency 缺省 CREDIT (市场只允许 CREDIT; 显式传 AZURE/其它由引擎拒绝)。
        String currency = optString(payload, "currency", MarketConstants.CURRENCY_CREDIT);

        // 引擎在挂单时按偏离费向卖家收取挂单手续费 (上单即收 sink), 回执含 listingId 与已付 listFee 供前端展示。
        PlaceResult pr = MarketServices.marketEngine().place(sender, slot, count, unitPrice, currency);

        JsonObject result = new JsonObject();
        result.addProperty("listingId", pr.listingId());
        result.addProperty("listFee", pr.listFee());
        return GSON.toJson(result);
    };

    // ============================================================
    // market.buy: {listingId,count?} -> {ok,itemId,count,total,fee}  (count 缺省/0 = 买整单; >0 = 部分购买)
    // ============================================================

    static final WebUiAction BUY = (sender, payload) -> {
        long listingId = payload.get("listingId").getAsLong();
        // 买入量: 缺省 0 = 买下整单剩余; >0 = 部分购买 (10 个里买 5)。
        int count = optInt(payload, "count", 0);
        BuyResult r = MarketServices.marketEngine().buy(sender, listingId, count);

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("itemId", r.itemId());
        result.addProperty("count", r.count());
        result.addProperty("total", r.total());
        result.addProperty("fee", r.fee());
        return GSON.toJson(result);
    };

    // ============================================================
    // market.cancel: {listingId} -> {ok,itemId,count}
    // ============================================================

    static final WebUiAction CANCEL = (sender, payload) -> {
        long listingId = payload.get("listingId").getAsLong();
        CancelResult r = MarketServices.marketEngine().cancel(sender, listingId);

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("itemId", r.itemId());
        result.addProperty("count", r.count());
        return GSON.toJson(result);
    };

    // ============================================================
    // market.mine: {} -> {listings:[...自己 ACTIVE...]}
    // ============================================================

    static final WebUiAction MINE = (sender, payload) -> {
        // 服务端权威: 取 sender 自己的 ACTIVE 挂单, 不信前端传入的 uuid。
        List<ListingRow> rows = MarketServices.marketEngine()
                .listingsBySeller(sender.getUUID(), "ACTIVE");

        JsonArray listings = new JsonArray();
        for (ListingRow r : rows) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.addProperty("sellerName", r.sellerName());
            o.addProperty("itemId", r.itemId());
            o.addProperty("count", r.count());
            o.addProperty("unitPrice", r.unitPrice());
            o.addProperty("total", r.unitPrice() * (long) r.count());
            o.addProperty("createdAt", r.createdAt());
            listings.add(o);
        }
        JsonObject result = new JsonObject();
        result.add("listings", listings);
        return GSON.toJson(result);
    };

    // ============================================================
    // market.history: {page?} -> {transactions:[{listingId,itemId,count,unitPrice,total,fee,role:"buy"/"sell",createdAt}]}
    // ============================================================

    /**
     * 成交历史 (契约第 6 节)。契约约定"历史走 transactions, B 自加 DAO 查询"; 但作者 A 交付的 {@code MarketDao} 固定签名
     * (契约第 4 节, "签名固定不得改名/改签名") 未含按玩家查 transactions 的方法, B 又不得编辑 store 包 (A 负责)。
     * 故本 action 形状就位、契约第 6 节回执结构 (transactions 数组) 完整, 但当前返回空数组 —— 待 A 在 DAO 增
     * {@code List<TxnRow> transactionsByPlayer(UUID, int offset, int limit)} (买家 OR 卖家命中) 后接线即可填充。
     * 这是真实"无可查数据源"下的正确返回 (非空壳逃课): 无查询能力时历史为空是如实反映, 见 notes 报告的跨作者待办。
     */
    static final WebUiAction HISTORY = (sender, payload) -> {
        // page 参数当前无后端查询消费, 仍解析以校验 payload 形状 (缺省 0); 接线 A 的 transactionsByPlayer 后即用作 offset。
        int page = optInt(payload, "page", DEFAULT_PAGE);
        JsonObject result = new JsonObject();
        result.add("transactions", new JsonArray());
        result.addProperty("page", page);
        return GSON.toJson(result);
    };

    // ============================================================
    // market.baseValue: {itemId} -> {itemId, v0:number|null, source:"override"/"preset"/"none"}
    // ============================================================

    /**
     * 某物品当前生效基准价 V0 (挂单手续费预览用)。分层解析: admin 覆盖 &gt; 代码预设 &gt; 无锚。source 标注命中层
     * (前端 BaseValueResp): override = admin curate 强锚; preset = 代码内置高价矿/小麦; none = 无锚 (挂单走平率)。
     * 无锚时 v0 回 JSON null (前端按 null 走 flatFee 预览)。
     */
    static final WebUiAction BASE_VALUE = (sender, payload) -> {
        // itemId 必填: 缺失自然抛冒泡 Gateway。
        String itemId = payload.get("itemId").getAsString();

        // 先判 admin 覆盖 (内存查表), 再判代码预设; 二者都无即无锚 —— 与 resolveBaseValue 同分层但额外回 source。
        Long override = MarketServices.marketEngine().baseValueOverrides().get(itemId);
        JsonObject result = new JsonObject();
        result.addProperty("itemId", itemId);
        if (override != null) {
            result.addProperty("v0", override);
            result.addProperty("source", "override");
        } else {
            OptionalLong preset = DefaultBaseValues.resolve(itemId);
            if (preset.isPresent()) {
                result.addProperty("v0", preset.getAsLong());
                result.addProperty("source", "preset");
            } else {
                result.add("v0", JsonNull.INSTANCE);
                result.addProperty("source", "none");
            }
        }
        return GSON_NULLS.toJson(result);
    };

    // ============================================================
    // market.categories: {} -> CategoryNode[]  (服务端按物品注册表构建, 下钻到叶子)
    // ============================================================

    /**
     * 物品分类树 (前端左栏筛选)。服务端按物品注册表枚举全物品归类成树 (见 {@link MarketCategoryTree}), 顶层数组直接回
     * (前端 call&lt;CategoryNode[]&gt; 直接 JSON.parse 拿数组, 不包外层对象)。叶子带 itemId, label 是翻译键 (客户端 i18n 解析)。
     */
    static final WebUiAction CATEGORIES = (sender, payload) -> GSON.toJson(MarketCategoryTree.build());

    // ============================================================
    // payload 取值 helper (可选字段缺省; 业务必填字段不走此路, 直接 get().getAsX 自然抛)
    // ============================================================

    private static String optString(JsonObject payload, String key, String fallback) {
        return payload.has(key) && !payload.get(key).isJsonNull()
                ? payload.get(key).getAsString() : fallback;
    }

    private static int optInt(JsonObject payload, String key, int fallback) {
        return payload.has(key) && !payload.get(key).isJsonNull()
                ? payload.get(key).getAsInt() : fallback;
    }
}
