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
import com.miningdim.market.store.SoldOrListedSplit;
import com.miningdim.market.store.TxnRow;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiItemJson;
import com.miningdim.webui.server.WebUiPayloads;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 跳蚤市场 13 个 market.* WebUiAction (共享契约第 6 节 + 真桥脱 Mock 补充: baseValue/categories + W2 接线补充:
 * feePreview/p2pCap/pendingPayout/tradable, 以及被填充的 history)。每个 handler 拿
 * 服务端校验过的 sender (卖家/买家身份, 不信前端 uuid) 与解析后的 payload (JsonObject), 经
 * {@link MarketServices#marketEngine()} 调引擎, 构 resultJson 返回。
 *
 * market.categories 与 market.categoryItems 的分工: 前者只回分类骨架 (六个固定顶层 + ores 三个固定子分类, 各带
 * leafCount), 后者按 categoryId 分页取该分类 (含其后代) 的叶子。拆开的原因见 {@link MarketCategoryTree} 类注释
 * (32767 字符下行硬闸 + 注册表规模) —— 骨架恒小, 叶子按需按页取, 两条 action 合起来才顶替原来那条会超限的
 * market.categories。
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
    /**
     * 保留 JSON null 键的序列化器 (默认 Gson 直接丢掉值为 null 的字段, 那会让契约声明为 {@code |null} 的键整个消失,
     * 而前端的 {@code ?? } 兜底照样工作 —— 漂移无声无息)。使用点共四条, 各自靠它保住一个契约键:
     * market.baseValue 与 market.feePreview 的 {@code v0}, market.history 的 {@code counterpartyName},
     * market.tradable 的 {@code reasonCode}/{@code reason}。新增回执含可空键时一并走它, 不要退回 GSON。
     */
    private static final Gson GSON_NULLS = new GsonBuilder().serializeNulls().create();

    /** 分页默认 (UI 友好默认, 非业务空值掩盖)。 */
    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** 单页行数上限 (与 listingJson 里"单页上限 100 行"的既有口径同值)。 */
    private static final int MAX_PAGE_SIZE = 100;

    private MarketActions() {
    }

    /** 把 13 个 market.* action 注册进派发器 (由 MarketSubsystem.register 调用)。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("market.list", LIST);
        WebUiServerDispatcher.register("market.place", PLACE);
        WebUiServerDispatcher.register("market.buy", BUY);
        WebUiServerDispatcher.register("market.cancel", CANCEL);
        WebUiServerDispatcher.register("market.mine", MINE);
        WebUiServerDispatcher.register("market.history", HISTORY);
        WebUiServerDispatcher.register("market.baseValue", BASE_VALUE);
        WebUiServerDispatcher.register("market.categories", CATEGORIES);
        WebUiServerDispatcher.register("market.categoryItems", CATEGORY_ITEMS);
        WebUiServerDispatcher.register("market.feePreview", FEE_PREVIEW);
        WebUiServerDispatcher.register("market.p2pCap", P2P_CAP);
        WebUiServerDispatcher.register("market.pendingPayout", PENDING_PAYOUT);
        WebUiServerDispatcher.register("market.tradable", TRADABLE);
    }

    // ============================================================
    // market.list: {query?,sort?,page?,pageSize?}
    //   -> {listings:[{id,sellerName,itemId,descriptionId,count,unitPrice,total,createdAt,customModelData?,nameParts?}],page,pageSize}
    //   末两个可选字段是 NBT 变体件的差异, 见 WebUiItemJson
    // ============================================================

    static final WebUiAction LIST = (sender, payload) -> {
        String query = optString(payload, "query", null);
        String sort = optString(payload, "sort", "created_at");
        /*
         * 分页钳制, 与 market.history 同一口径 —— 这里比 history 更要紧: 负 pageSize 会让 SQLite 的
         * LIMIT -1 变成不限行, 一次拉回**全服**所有 ACTIVE 挂单, 而每一行还要 NbtIo.read 反序列化托管的
         * 整个 ItemStack, 全程在服务器主线程上。history 至少只能拉到调用者自己的流水。
         */
        int page = Math.max(0, optInt(payload, "page", DEFAULT_PAGE));
        int pageSize = clamp(optInt(payload, "pageSize", DEFAULT_PAGE_SIZE), 1, MAX_PAGE_SIZE);
        int offset = page * pageSize;

        List<ListingRow> rows = MarketServices.marketEngine().queryActive(query, sort, offset, pageSize);

        JsonArray listings = new JsonArray();
        for (ListingRow r : rows) {
            listings.add(listingJson(r));
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
            listings.add(listingJson(r));
        }
        JsonObject result = new JsonObject();
        result.add("listings", listings);
        return GSON.toJson(result);
    };

    /**
     * 一条挂单的 JSON 形状 (market.list 与 market.mine 共用, 二者契约本就同形, 此前是逐字重复的两份)。
     *
     * descriptionId 是翻译键: 前端拿它经 client.i18n 走客户端 I18n 解中文名 —— 专用服务端不加载 lang,
     * 而 itemId 推不出翻译键 (物品是 item.&lt;ns&gt;.&lt;path&gt;、方块是 block.&lt;ns&gt;.&lt;path&gt;)。
     * 物品已从注册表移除时 (卸载 mod 后的历史挂单) 回退为 itemId 本身, 与 MarketCategoryTree 的 label 同纪律。
     */
    private static JsonObject listingJson(ListingRow r) {
        JsonObject o = new JsonObject();
        o.addProperty("id", r.id());
        o.addProperty("sellerName", r.sellerName());
        o.addProperty("itemId", r.itemId());
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(r.itemId()));
        o.addProperty("descriptionId", item == null ? r.itemId() : item.getDescriptionId());
        o.addProperty("count", r.count());
        o.addProperty("unitPrice", r.unitPrice());
        // total = unitPrice * count (买入将付的总价, 前端直接展示, 不暴露 fee 计算)。
        o.addProperty("total", r.unitPrice() * (long) r.count());
        o.addProperty("createdAt", r.createdAt());
        /*
         * 反序列化托管的整个 ItemStack, 只为补"同 id 不同实例"的差异 (nameParts / customModelData)。
         *
         * 上面的 itemId + descriptionId 是 Item 级的, 对靠 NBT 区分变体的物品不够 —— 枪匠零件的 195 种变体
         * 全部注册在同一个 miningdim:gunsmith_part 之下, 不补这一步, 市场里它们是同名同图标的 195 行。
         *
         * 代价是每行一次 NbtIo.read。可接受: 单页上限 100 行, 且这条 action 本来就要走一次 SQLite 查询。
         * 真成为热点时该做的是在 listings 表里加两列冗余字段, 而不是让前端拿不到变体信息。
         */
        ItemStack escrow = MarketEngine.deserializeStack(r.itemNbt());
        WebUiItemJson.appendVariant(o, escrow);
        // 托管物所属 mod 被卸载后, deserializeStack 兜成 EMPTY —— 这类挂单在列表里与正常挂单长得一模一样,
        // 而成交路径会硬拒 (托管不可解析守卫)。多这一个键前端才能提前灰掉按钮, 不必让玩家点了才失败。
        o.addProperty("escrowResolvable", !escrow.isEmpty());
        return o;
    }

    // ============================================================
    // market.history: {page?,pageSize?}
    //   -> {transactions:[{txnId,listingId,role:"buy"/"sell",itemId,descriptionId,count,unitPrice,total,fee,
    //                      counterpartyUuid,counterpartyName|null,createdAt}],page,pageSize,total}
    // ============================================================

    /**
     * 成交历史 (契约第 6 节)。服务端权威取 sender 自己参与的流水 (买家或卖家任一侧命中), 不信前端传入的 uuid。
     *
     * 回执带 total: 没有它前端算不出总页数, 只能拿"这页返回的条数是否等于 pageSize"去猜下一页存不存在,
     * 最后一页正好装满时就会多翻出一页空表 (market.list 至今就是这个毛病, 本条不再重犯)。
     *
     * 流水里没有 NBT 变体字段 (customModelData/nameParts): transactions 表只存 item_id 不存成交物的 NBT,
     * 而 {@link WebUiItemJson#appendVariant} 要的是 ItemStack。故 195 种枪匠零件在流水里同名同图标 —— 这是
     * 数据层缺口, 补它得先给表加 NBT 列, 不是这里少调了一个方法。
     */
    static final WebUiAction HISTORY = (sender, payload) -> {
        // 分页参数钳制: pageSize 传负数时 SQLite 的 LIMIT -1 等于不限行, 一次把该玩家全部流水拉回内存并在
        // 主线程序列化下发 —— 这条 action 无冷却可反复触发。page 同样下钳, 负 offset 在 SQLite 里被当 0 用,
        // 与前端"第 -1 页"的语义无关, 不如在入口就收住。
        int page = Math.max(0, optInt(payload, "page", DEFAULT_PAGE));
        int pageSize = clamp(optInt(payload, "pageSize", DEFAULT_PAGE_SIZE), 1, MAX_PAGE_SIZE);
        int offset = page * pageSize;

        UUID self = sender.getUUID();
        MarketEngine engine = MarketServices.marketEngine();
        List<TxnRow> rows = engine.transactionsByPlayer(self, offset, pageSize);
        int total = engine.transactionsCountByPlayer(self);

        PlayerList playerList = sender.server.getPlayerList();
        JsonArray transactions = new JsonArray();
        for (TxnRow r : rows) {
            transactions.add(transactionJson(r, self, playerList));
        }
        JsonObject result = new JsonObject();
        result.add("transactions", transactions);
        result.addProperty("page", page);
        result.addProperty("pageSize", pageSize);
        result.addProperty("total", total);
        // GSON_NULLS: counterpartyName 在对手方离线时是 JSON null, 默认 Gson 会把键整个丢掉, 违反契约形状。
        return GSON_NULLS.toJson(result);
    };

    /**
     * 一条成交流水的 JSON 形状 (market.history)。
     *
     * role 与对手方由"我"在这一行的哪一侧派生: 同一行流水对买家是 buy、对卖家是 sell, 表里一行同时带双方 UUID,
     * 故不需要两套查询。
     *
     * counterpartyName 只能靠在线解析: transactions 表没有 listings.seller_name 那样的名字快照列, 对手方离线时
     * 服务端手里只有 UUID。此时回 JSON null 而不是编一个"未知玩家"或拿 UUID 冒充名字 —— 前端拿
     * counterpartyUuid 自己降级展示, 至少玩家知道那是个 id 而不是某人真叫这个名。
     */
    private static JsonObject transactionJson(TxnRow r, UUID self, PlayerList playerList) {
        boolean asBuyer = r.buyerUuid().equals(self);
        UUID counterparty = asBuyer ? r.sellerUuid() : r.buyerUuid();
        ServerPlayer online = playerList.getPlayer(counterparty);

        JsonObject o = new JsonObject();
        o.addProperty("txnId", r.id());
        o.addProperty("listingId", r.listingId());
        o.addProperty("role", asBuyer ? "buy" : "sell");
        o.addProperty("itemId", r.itemId());
        // descriptionId 取法与 listingJson 逐字一致 (翻译键由客户端 i18n 解析, 物品已卸载时回退 itemId)。
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(r.itemId()));
        o.addProperty("descriptionId", item == null ? r.itemId() : item.getDescriptionId());
        o.addProperty("count", r.count());
        o.addProperty("unitPrice", r.unitPrice());
        o.addProperty("total", r.total());
        o.addProperty("fee", r.fee());
        o.addProperty("counterpartyUuid", counterparty.toString());
        if (online == null) {
            o.add("counterpartyName", JsonNull.INSTANCE);
        } else {
            o.addProperty("counterpartyName", online.getName().getString());
        }
        o.addProperty("createdAt", r.createdAt());
        return o;
    }

    // ============================================================
    // market.feePreview: {itemId,unitPrice,count} -> {itemId,listFee,ratio,v0:number|null,source}
    // ============================================================

    /**
     * 挂单手续费预览。与 {@link MarketEngine#place} 的实收**同源**: V0 走同一个
     * {@link MarketEngine#lookupBaseValue} (内部即 place 用的 {@link BaseValueResolver}), 费走同一个
     * {@link MarketFee#listingFee} 纯函数, 前端不做任何本地近似公式 (两份公式各自漂移时, 玩家看到的预览和
     * 实扣就会对不上, 而费是上单即收不退的)。
     *
     * 仍非承诺值: V0 可能在预览与提交之间被 admin 改动, 最终以 place 回执的 listFee 为准。
     *
     * unitPrice/count &lt;= 0 抛 INVALID_REQUEST 而不是短路返回 listFee=0 —— 给非法入参编一个真金白银的数字
     * 就是掩盖空值; 表单草稿态由前端在 unitPrice&gt;=1 &amp;&amp; count&gt;=1 之前不发起调用来解决。
     */
    static final WebUiAction FEE_PREVIEW = (sender, payload) -> {
        // 业务必填字段: 缺失自然抛 (与 market.place 同纪律)。
        String itemId = payload.get("itemId").getAsString();
        long unitPrice = payload.get("unitPrice").getAsLong();
        int count = payload.get("count").getAsInt();
        if (unitPrice <= 0L) {
            throw WebUiPayloads.illegalValue("unitPrice", Long.toString(unitPrice), "单价必须 >= 1");
        }
        if (count <= 0) {
            throw WebUiPayloads.illegalValue("count", Integer.toString(count), "数量必须 >= 1");
        }

        // V0 与 source 一次取回, 走的就是 place 算费吃的那份分层实现 (MarketEngine.lookupBaseValue -> BaseValueResolver):
        // 此处再手抄一遍分层, 等于给"预览"和"实收"各留一份会各自漂移的公式, 而这笔费上单即收、撤单不退。
        MarketEngine.BaseValueLookup anchor = MarketServices.marketEngine().lookupBaseValue(itemId);

        long listFee = MarketFee.listingFee(anchor.v0(), unitPrice, count);
        // 分母是玩家心智里的"我挂的总价", 不是偏离费内部的 max(V0,VR)*count。故本比例可 > 1 (极端贱卖时
        // 费超过标价), 前端不得按 0..1 钳死。unitPrice/count 均 >= 1, 不存在除零。
        double ratio = (double) listFee / ((double) unitPrice * (double) count);

        JsonObject result = new JsonObject();
        // 回显 itemId: 前端连打几次预览时, 靠它把异步回执对回当前选中的标的 (防串行)。
        result.addProperty("itemId", itemId);
        result.addProperty("listFee", listFee);
        result.addProperty("ratio", ratio);
        appendAnchor(result, anchor);
        return GSON_NULLS.toJson(result);
    };

    /**
     * 把一次基准价解析写进回执的 v0/source 两个键 (market.baseValue 与 market.feePreview 共用)。
     * 无锚时 v0 是 JSON null 而不是 0 —— 0 会被前端当成"基准价就是 0"去算比例。
     */
    private static void appendAnchor(JsonObject result, MarketEngine.BaseValueLookup anchor) {
        if (anchor.v0().isPresent()) {
            result.addProperty("v0", anchor.v0().getAsLong());
        } else {
            result.add("v0", JsonNull.INSTANCE);
        }
        result.addProperty("source", anchor.source());
    }

    // ============================================================
    // market.p2pCap: {} -> {usedToday,activeHeld,soldToday,capPerDay,remaining,resetsAt,scopeItemIds}
    // ============================================================

    /**
     * 铜/铁每日 P2P 额度 (服务端权威取 sender 自己的用量, 不读 payload)。口径与 {@link MarketEngine#place} 的
     * 挂单前 cap 校验**共用同一个方法** ({@link MarketEngine#copperIronUsageToday}, 单值版 copperIronUsedToday
     * 就是它求和), 不是另算一套数字。
     *
     * usedToday 拆成 activeHeld + soldToday 下发, 因为 resetsAt 只对得起后者: 计数的 listing 侧不看 created_at,
     * ACTIVE 即占额度, 挂着 500 铜锭不撤单的玩家到了次日零点一件也不会释放。只发总量 + resetsAt 的话, 面板等于
     * 承诺了一件不会发生的事, 玩家的合理结论是"系统在骗人"。前端据这两段分句: 在挂中的撤单即释放, 已成交的等归零。
     *
     * 只覆盖铜/铁 6 个 item_id, 不是全品类额度: scopeItemIds 就是为了让面板说得出"仅铜铁受限", 而不是笼统的
     * "今日交易额度" —— 后者会让卖别的东西的玩家以为自己也被限了。
     */
    static final WebUiAction P2P_CAP = (sender, payload) -> {
        SoldOrListedSplit usage = MarketServices.marketEngine().copperIronUsageToday(sender.getUUID());
        int usedToday = usage.total();
        int capPerDay = MarketConstants.COPPER_IRON_DAILY_P2P_CAP;

        JsonObject result = new JsonObject();
        result.addProperty("usedToday", usedToday);
        // 在挂中的量 (撤单即释放, 不随日切归零) 与今日已成交的量 (随 resetsAt 归零) —— 二者之和恒等于 usedToday。
        result.addProperty("activeHeld", usage.activeHeld());
        result.addProperty("soldToday", usage.soldToday());
        result.addProperty("capPerDay", capPerDay);
        // 下钳到 0 是展示钳制不是空值掩盖: cap 被调小时旧数据可能已超, 负余额对玩家无意义; 真实已用量原样在 usedToday 里。
        result.addProperty("remaining", Math.max(0, capPerDay - usedToday));
        // 重置时刻 = 服务器系统默认时区的次日零点 (与 cap 判定吃的当日窗口同一时区口径, 不是 UTC 翻日)。
        // 它只是 soldToday 归零的时刻; activeHeld 不参与翻日, 到点仍原样占着额度 (撤单才释放)。
        result.addProperty("resetsAt", MarketEngine.startOfTomorrowEpochMillis());
        // Set 本身无序, 排序后下发: 面板列出的受限标的顺序不该每次刷新都跳。
        List<String> scope = new ArrayList<>(MarketConstants.COPPER_IRON_ITEM_IDS);
        Collections.sort(scope);
        JsonArray scopeItemIds = new JsonArray();
        for (String id : scope) {
            scopeItemIds.add(id);
        }
        result.add("scopeItemIds", scopeItemIds);
        return GSON.toJson(result);
    };

    // ============================================================
    // market.pendingPayout: {} -> {credit,entryCount}
    // ============================================================

    /**
     * 待结货款 (买家买走时卖家离线, 落 pending_payout 待登录结清)。服务端权威取 sender 自己的待结款。
     *
     * <b>只读, 绝不发放绝不清空</b>: 真实发放只在登录时由 {@link MarketEngine#settlePendingOnLogin} 走
     * drainPendingPayout 完成。本条走的是新增的只读 peek —— 复用 drain 会让玩家开一次收件箱就把货款冲掉,
     * 且行已物理删除, 少了多少都查不出来。
     *
     * 没有"待领取物品"字段: 被卖掉的是什么物品这件事从未被持久化 (pending_payout 表只有金额), 买家的货是
     * 成交时直接进背包的, 从不落表。要给出物品清单得先开 schema 迁移, 不在本轮只读 peek 的边界内。
     */
    static final WebUiAction PENDING_PAYOUT = (sender, payload) -> {
        MarketEngine engine = MarketServices.marketEngine();
        JsonObject result = new JsonObject();
        result.addProperty("credit", engine.pendingPayoutTotal(sender.getUUID()));
        result.addProperty("entryCount", engine.pendingPayoutCount(sender.getUUID()));
        return GSON.toJson(result);
    };

    // ============================================================
    // market.tradable: {slot} -> {slot,itemId,tradable,reasonCode|null,reason|null}
    // ============================================================

    /**
     * 某个背包槽位的物品能否挂上市场 (只读预判, 供页面提前灰掉挂单按钮)。
     *
     * 入参是 slot 不是 itemId: 220 张塔罗牌 x 5 档品质全部注册在同一个 miningdim:tarot_card 之下, 品质只活在
     * NBT 里 —— 只给 itemId, 服务端对塔罗牌永远判不出品质, 那会做出一个"看着接通了、实则规则是假的"接口。
     * slot 与 market.place / player.inventory / player.itemDetail 同一索引空间 (主背包 36 槽)。
     *
     * 判定与 {@link MarketEngine#place} 共用 {@link MarketTradeWhitelist#judge}: 本条回 tradable=false 的那一格,
     * place 也必然拒绝, 反之亦然。此处**不抛**, 把裁决摊平成回执 —— "这件不能挂"是正常答案, 不是调用失败。
     */
    static final WebUiAction TRADABLE = (sender, payload) -> {
        int slot = WebUiPayloads.requiredInt(payload, "slot");
        Inventory inv = sender.getInventory();
        if (slot < 0 || slot >= inv.items.size()) {
            throw new WebUiBusinessException(WebUiErrorCodes.SLOT_OUT_OF_RANGE,
                    "槽位 " + slot + " 不在主背包范围内", false,
                    Map.of("slot", Integer.toString(slot), "size", Integer.toString(inv.items.size())));
        }
        ItemStack stack = inv.items.get(slot);
        if (stack.isEmpty()) {
            throw new WebUiBusinessException(WebUiErrorCodes.SLOT_EMPTY,
                    "槽位 " + slot + " 是空的", false,
                    Map.of("slot", Integer.toString(slot)));
        }

        MarketTradeWhitelist.Verdict verdict = MarketTradeWhitelist.judge(stack);
        JsonObject result = new JsonObject();
        // 回显 slot: 玩家快速换格时, 前端靠它把异步回执对回当前选中格 (防串行)。
        result.addProperty("slot", slot);
        result.addProperty("itemId", MarketEngine.itemIdOf(stack));
        result.addProperty("tradable", verdict.tradable());
        if (verdict.tradable()) {
            result.add("reasonCode", JsonNull.INSTANCE);
            result.add("reason", JsonNull.INSTANCE);
        } else {
            result.addProperty("reasonCode", verdict.reasonCode());
            result.addProperty("reason", verdict.reason());
        }
        return GSON_NULLS.toJson(result);
    };

    // ============================================================
    // market.baseValue: {itemId} -> {itemId, v0:number|null, source:"override"/"preset"/"none"}
    // ============================================================

    /**
     * 某物品当前生效基准价 V0 (挂单手续费预览用)。分层解析走引擎的 {@link MarketEngine#lookupBaseValue}, 与
     * market.feePreview 算费、{@link MarketEngine#place} 实收是同一份实现。source 标注命中层 (前端 BaseValueResp):
     * override = admin curate 强锚; preset = 代码内置高价矿/小麦; none = 无锚 (挂单走平率)。
     * 无锚时 v0 回 JSON null (前端按 null 走 flatFee 预览)。
     */
    static final WebUiAction BASE_VALUE = (sender, payload) -> {
        // itemId 必填: 缺失自然抛冒泡 Gateway。
        String itemId = payload.get("itemId").getAsString();

        JsonObject result = new JsonObject();
        result.addProperty("itemId", itemId);
        appendAnchor(result, MarketServices.marketEngine().lookupBaseValue(itemId));
        return GSON_NULLS.toJson(result);
    };

    // ============================================================
    // market.categories: {} -> CategoryNode[]  (服务端按物品注册表构建骨架, 不含叶子)
    // ============================================================

    /**
     * 分类骨架 (前端左栏筛选的顶层结构)。服务端按物品注册表统计各分类物品数构骨架 (见 {@link MarketCategoryTree}),
     * 顶层数组直接回 (前端 call&lt;CategoryNode[]&gt; 直接 JSON.parse 拿数组, 不包外层对象)。骨架不含叶子 —— 叶子改由
     * market.categoryItems 按分类节点分页取回, 理由见 {@link MarketCategoryTree} 类注释 (32767 字符下行硬闸)。
     */
    static final WebUiAction CATEGORIES = (sender, payload) -> GSON.toJson(MarketCategoryTree.buildSkeleton());

    // ============================================================
    // market.categoryItems: {categoryId,page?,pageSize?} -> {categoryId,items:[{id,label,itemId}],page,pageSize,total}
    // ============================================================

    /**
     * 某分类节点 (含其后代) 的叶子分页 (与 market.categories 配套, 见该 action 注释的分工说明)。categoryId 必须是
     * {@link MarketCategoryTree#buildSkeleton} 输出过的六个顶层或三个 ores 子分类之一, 未知 id 直接拒绝 —— 骨架
     * 已把合法 id 集合亮给前端, 传别的只可能是前端 bug 或探测, 不该静默回空列表。
     *
     * 分页口径与 market.list / market.history 同一套 (DEFAULT_PAGE / DEFAULT_PAGE_SIZE / MAX_PAGE_SIZE + clamp),
     * total 是该分类叶子总数供前端算总页数, 与 {@link #HISTORY} 带 total 同理 (最后一页正好装满时不多翻出一页空表)。
     */
    static final WebUiAction CATEGORY_ITEMS = (sender, payload) -> {
        // categoryId 业务必填: 缺失自然抛 (与 market.place 同纪律)。
        String categoryId = payload.get("categoryId").getAsString();
        if (!MarketCategoryTree.isKnownCategory(categoryId)) {
            throw WebUiPayloads.illegalValue("categoryId", categoryId, "未知的分类 id");
        }
        int page = Math.max(0, optInt(payload, "page", DEFAULT_PAGE));
        int pageSize = clamp(optInt(payload, "pageSize", DEFAULT_PAGE_SIZE), 1, MAX_PAGE_SIZE);
        int offset = page * pageSize;

        MarketCategoryTree.LeafPage leafPage = MarketCategoryTree.leavesOf(categoryId, offset, pageSize);

        JsonObject result = new JsonObject();
        result.addProperty("categoryId", categoryId);
        JsonArray items = new JsonArray();
        for (JsonObject leaf : leafPage.items()) {
            items.add(leaf);
        }
        result.add("items", items);
        result.addProperty("page", page);
        result.addProperty("pageSize", pageSize);
        result.addProperty("total", leafPage.total());
        return GSON.toJson(result);
    };

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

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
