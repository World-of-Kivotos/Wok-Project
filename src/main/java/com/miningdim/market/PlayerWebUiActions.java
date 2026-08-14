package com.miningdim.market;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.entry.UiPrefs;
import com.miningdim.job.JobId;
import com.miningdim.job.JobProgressJson;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiItemDetailJson;
import com.miningdim.webui.server.WebUiItemJson;
import com.miningdim.webui.server.WebUiPayloads;
import com.miningdim.webui.server.WebUiPermissions;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * 玩家自身数据的 player.* WebUiAction (跳蚤市场顶栏余额与挂单选物, 平板首屏聚合, 物品详情, 账号级界面偏好)。
 * 让游戏内真桥脱离前端 Mock: 这些 action 此前仅 bridge.mock.ts 内存假数据, 进游戏后无服务端实现即落空。
 *
 * 服务端权威 (架构铁律 1, 同 {@link MarketActions}): 取经服务端校验过的 sender (不信前端 uuid), 背包读 sender 自己的
 * {@link Inventory}, 余额经 {@link IEconomyService} 只读账本 (SavedData 仅服务端存在)。坏输入/业务错误自然抛冒泡到
 * {@link WebUiServerDispatcher#dispatchAndRespond} 的 Gateway 统一兜底, 本类严禁 try-catch 生吞 (CLAUDE.md C9)。
 *
 * 前端契约 (webui/src/lib/types.ts):
 *  - player.inventory -&gt; {items:[{slot,itemId,descriptionId,count,displayName?,customModelData?,nameParts?}]}
 *    (InvResp; SellView 选物)。后两个可选字段是 NBT 变体件的差异, 见 {@link com.miningdim.webui.server.WebUiItemJson}
 *  - player.wallet    -&gt; {credit,azure} (Wallet; 顶栏余额)
 *  - player.isOp      -&gt; {isOp} (导航过滤与 OP 徽标; 纯渲染决策, admin.* 仍各自独立校验权限)
 *  - player.itemDetail-&gt; {slot,itemId,descriptionId,count,displayName?,customModelData?,nameParts?,kind,attributes,tags}
 *  - player.profile   -&gt; {playerName,isOp,wallet,jobs[8],todayCreditFaucetGross,todayAzureIn} (首屏一次拉齐)
 *  - player.prefs.get -&gt; {muteToasts,language,theme,brandHue}
 *  - player.prefs.set -&gt; 同形入参, 回执是落盘后的完整偏好
 */
public final class PlayerWebUiActions {

    private static final Gson GSON = new Gson();

    private PlayerWebUiActions() {
    }

    /**
     * 在线名册单次下发上限。名册只服务"点选一个人"这件事, 不是玩家列表功能: 满编公服也就一两百人,
     * 200 条足够, 又把回执钉死在几 KB 量级 —— 列表类 action 自带上限, 不指望派发器的体积收口兜底
     * (那是保命不是设计)。
     */
    private static final int MAX_ROSTER_ENTRIES = 200;

    /** 把 8 个 player.* action 注册进派发器 (由 MarketSubsystem.register 调用)。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("player.inventory", INVENTORY);
        WebUiServerDispatcher.register("player.wallet", WALLET);
        WebUiServerDispatcher.register("player.isOp", IS_OP);
        WebUiServerDispatcher.register("player.itemDetail", ITEM_DETAIL);
        WebUiServerDispatcher.register("player.profile", PROFILE);
        WebUiServerDispatcher.register("player.prefs.get", PREFS_GET);
        WebUiServerDispatcher.register("player.prefs.set", PREFS_SET);
        WebUiServerDispatcher.register("player.roster", ROSTER);
    }

    // ============================================================
    // player.roster: {} -> {players:[{name,uuid}],total,truncated}
    // ============================================================

    /**
     * 在线玩家名册 (名字 + UUID)。
     *
     * 存在的理由是"免输入": marriage.propose 与 admin.economy.balance/set 都按玩家名找人, 而中文输入 (W11)
     * 已推迟 —— 只给一个输入框, 中文 ID 的玩家就永远求不了婚、也调不了自己的账。名册让界面能做成点选。
     * 见 docs/WebUI_ChineseIME_DesignSpec.md 第五章。
     *
     * 不做隐私过滤: 在线名单在原版里本来就是公开的 (Tab 列表就是它), 这里没有新增任何暴露面。
     * 也不剔除调用者自己 —— 剔了就得在服务端定义"自己"这个语义, 而 admin 调账的目标恰恰可以是自己;
     * 前端拿 player.profile 里的名字自行过滤即可。
     */
    static final WebUiAction ROSTER = (sender, payload) -> {
        List<ServerPlayer> online = sender.getServer().getPlayerList().getPlayers();

        JsonArray players = new JsonArray();
        for (ServerPlayer player : online) {
            if (players.size() >= MAX_ROSTER_ENTRIES) {
                break;
            }
            JsonObject row = new JsonObject();
            row.addProperty("name", player.getGameProfile().getName());
            row.addProperty("uuid", player.getUUID().toString());
            players.add(row);
        }

        JsonObject result = new JsonObject();
        result.add("players", players);
        // total 是全量在线人数而不是本次下发条数: 截断时前端要能讲清"还有多少人没显示", 拿 players.size()
        // 反推只会得到上限值本身。
        result.addProperty("total", online.size());
        result.addProperty("truncated", online.size() > players.size());
        return GSON.toJson(result);
    };

    // ============================================================
    // player.inventory: {} -> {items:[{slot,itemId,count,displayName?}]}
    // ============================================================

    /**
     * 发送者主背包非空格位 (供 SellView 选物挂单)。只回主背包 36 槽 (与挂单 place 读 {@code getInventory().getItem(slot)}
     * 同一索引空间), 不含护甲/副手 (那些不是可挂卖货源)。displayName 仅在物品有自定义名 (铁砧改名, 携 NBT) 时附带 ——
     * 即"nbt 摘要": 让玩家在选物面板区分同 id 的改名物品; 普通物品省略该字段, 由前端自身 i18n 出中文名 (专用服务器不加载 lang)。
     */
    static final WebUiAction INVENTORY = (sender, payload) -> {
        Inventory inv = sender.getInventory();
        JsonArray items = new JsonArray();
        for (int slot = 0; slot < inv.items.size(); slot++) {
            ItemStack stack = inv.items.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("slot", slot);
            o.addProperty("itemId", MarketEngine.itemIdOf(stack));
            // 翻译键: 前端拿它经 client.i18n 走客户端 I18n 解出中文名 (专用服务端不加载 lang, 且 itemId 推不出
            // 翻译键 —— 物品是 item.<ns>.<path>、方块是 block.<ns>.<path>)。与 admin.listItems 同字段名。
            o.addProperty("descriptionId", stack.getDescriptionId());
            o.addProperty("count", stack.getCount());
            // nbt 摘要: 仅自定义命名物品附 displayName (铁砧改名是纯字面量, 服务端 getString 就能拿到真名)。
            if (stack.hasCustomHoverName()) {
                o.addProperty("displayName", stack.getHoverName().getString());
            }
            /*
             * 变体信息 (nameParts / customModelData)。与上面的 displayName 不是一回事, 两者都要:
             * displayName 管"玩家用铁砧改了名"(纯字面量), 这里管"物品自己按 NBT 拼出来的名字与贴图"
             * —— 枪匠零件的 195 种变体共用一个 miningdim:gunsmith_part, 不补这一步, 挂单选物界面里
             * 它们是同名同图标的 195 格。名字必须发结构不发字符串, 理由见 WebUiItemJson 类注释。
             */
            WebUiItemJson.appendVariant(o, stack);
            items.add(o);
        }
        JsonObject result = new JsonObject();
        result.add("items", items);
        return GSON.toJson(result);
    };

    // ============================================================
    // player.wallet: {} -> {credit,azure}
    // ============================================================

    /**
     * 发送者双货币余额 (顶栏展示)。经货币门面只读账本 (服务端权威 SavedData): CREDIT 信用点 + AZURE 青辉石。
     * 顶栏只读不动账, 故只调 {@link IEconomyService#creditBalance}/{@link IEconomyService#heartstoneBalance}。
     */
    static final WebUiAction WALLET = (sender, payload) -> {
        IEconomyService economy = EconomyServices.economyService();
        JsonObject result = new JsonObject();
        result.addProperty("credit", economy.creditBalance(sender));
        result.addProperty("azure", economy.heartstoneBalance(sender));
        return GSON.toJson(result);
    };

    // ============================================================
    // player.isOp: {} -> {isOp}
    // ============================================================

    /**
     * 发送者是否 OP。存在的理由是顶栏: 它此前为了一个布尔去拉整份 player.profile (含三次 SQLite), 而导航
     * 过滤与 OP 徽标只需要这一位。
     *
     * 只读不抛: 不许照抄 {@code MarketAdminActions.requireOp} 的 IllegalStateException 写法 —— 那是门禁语义,
     * 非 OP 会走 Gateway 无 errorCode 的通用兜底分支, 前端只能拿到一句裸文本。这里"不是 OP"是正常答案。
     */
    static final WebUiAction IS_OP = (sender, payload) -> {
        JsonObject result = new JsonObject();
        result.addProperty("isOp", WebUiPermissions.isOp(sender));
        return GSON.toJson(result);
    };

    // ============================================================
    // player.itemDetail: {slot} -> {…基础字段, kind, attributes, tags}
    // ============================================================

    /**
     * 单个背包槽位的物品详情。slot 与 player.inventory / market.place 同一索引空间 (主背包 36 槽, 不含护甲副手)。
     *
     * 基础四字段与变体两字段的取法与 {@link #INVENTORY} 逐字一致 (共用
     * {@link WebUiItemJson#appendVariant}) —— 另起一套的后果是 195 种枪匠零件在挂单页有名有图、在详情页
     * 又变回同名同图标。大类与数值行见 {@link WebUiItemDetailJson}。
     */
    static final WebUiAction ITEM_DETAIL = (sender, payload) -> {
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

        JsonObject result = new JsonObject();
        result.addProperty("slot", slot);
        result.addProperty("itemId", MarketEngine.itemIdOf(stack));
        result.addProperty("descriptionId", stack.getDescriptionId());
        result.addProperty("count", stack.getCount());
        if (stack.hasCustomHoverName()) {
            result.addProperty("displayName", stack.getHoverName().getString());
        }
        WebUiItemJson.appendVariant(result, stack);
        WebUiItemDetailJson.appendDetail(result, stack);
        return GSON.toJson(result);
    };

    // ============================================================
    // player.profile: {} -> {playerName,isOp,wallet,jobs,todayCreditFaucetGross,todayAzureIn}
    // ============================================================

    /**
     * 平板首屏聚合。存在的唯一理由是首屏: 不做这条, hub 首页要串行多次 MCEF 往返才能凑齐名字/权限/余额/职业。
     *
     * 性能约束 (必须守住): 本 action 每次打 <b>3 次</b> SQLite (信用点余额 / 青辉石余额 / 一次合并的当日 faucet
     * 计数), 且派发跑在服务器主线程 (C2SWebUiRequest 经 enqueueWork 切主线程)。
     * <b>禁止把 profile 挂上定时轮询</b>; 现有调用点的 world.revision 触发式重载是上限。
     *
     * 职业进度一次 resolve capability 后遍历 8 个职业, 不许对每个职业各调一次 IJobService —— 那会 resolve
     * 八次 capability。
     */
    static final WebUiAction PROFILE = (sender, payload) -> {
        IEconomyService economy = EconomyServices.economyService();
        IMiningPlayerData data = playerData(sender);

        JsonObject result = new JsonObject();
        result.addProperty("playerName", sender.getGameProfile().getName());
        result.addProperty("isOp", WebUiPermissions.isOp(sender));

        JsonObject wallet = new JsonObject();
        wallet.addProperty("credit", economy.creditBalance(sender));
        wallet.addProperty("azure", economy.heartstoneBalance(sender));
        result.add("wallet", wallet);

        // 职业的 per-job 每日额度与下面的 faucet 计数器必须按同一口径判翻日, 否则同一份回执里会出现"额度已
        // 用尽"与"今日产出 0"两句互相打脸。故日戳取自经济子系统的时钟 (todayFaucetGross 内部用的也是它),
        // 本层不另算一套 UTC epochDay; 八个职业共用这一次取值, 不是每职业各取一次。
        long todayStamp = economy.currentDayStamp();

        JsonArray jobs = new JsonArray();
        for (JobId job : JobId.values()) {
            jobs.add(JobProgressJson.of(job, data.jobProgress(job), todayStamp));
        }
        result.add("jobs", jobs);

        // 两个计数器一次 SELECT 取回 (同表同 kind, 只是 counter_key 不同)。
        long[] today = economy.todayFaucetGross(sender, List.of(
                EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY,
                EconomyConstants.AZURE_DAILY_FAUCET_KEY));
        // 两栏口径刻意不对称, 字段名就是唯一提示: 信用点那栏是衰减主闸打折"之前"的毛额 (账本记的就是 rawAmount),
        // 青辉石走硬截断故天然是实发额。前端文案必须逐栏写明, 严禁笼统合成一句"今日入账"。
        result.addProperty("todayCreditFaucetGross", today[0]);
        result.addProperty("todayAzureIn", today[1]);
        return GSON.toJson(result);
    };

    // ============================================================
    // player.prefs.get / player.prefs.set: 账号级界面偏好 (整份读写)
    // ============================================================

    /** 当前账号级界面偏好。落 capability 随 player.dat 走, 故换机器 / 清浏览器缓存都不丢。 */
    static final WebUiAction PREFS_GET = (sender, payload) ->
            GSON.toJson(prefsJson(playerData(sender).uiPrefs()));

    /**
     * 整份覆盖界面偏好, 回执是落盘后的完整偏好。
     *
     * 不做部分更新: "给了 null"与"没给"的三态语义会把"清空某项"与"不动某项"混在一起, 而前端本来就持有完整
     * 偏好状态, 整份提交是零成本。
     *
     * 逐字段先校验再构造 (不是 catch 掉 {@link UiPrefs} 构造器的异常再解析异常文本): 只有先校验才知道是哪个
     * 字段被拒, 才能把 field 与 value 填进回执的 params 供前端定位到具体那个控件。
     */
    static final WebUiAction PREFS_SET = (sender, payload) -> {
        boolean muteToasts = WebUiPayloads.requiredBoolean(payload, "muteToasts");

        String language = WebUiPayloads.requiredString(payload, "language");
        if (!UiPrefs.isValidLanguage(language)) {
            throw WebUiPayloads.illegalValue("language", language,
                    "language 必须是 MC 语言码形态 (小写字母/数字/下划线, 1-16 位)");
        }
        String theme = WebUiPayloads.requiredString(payload, "theme");
        if (!UiPrefs.isValidTheme(theme)) {
            throw WebUiPayloads.illegalValue("theme", theme,
                    "theme 只能是 " + UiPrefs.THEME_DARK + " 或 " + UiPrefs.THEME_LIGHT);
        }
        int brandHue = WebUiPayloads.requiredInt(payload, "brandHue");
        if (!UiPrefs.isValidBrandHue(brandHue)) {
            throw WebUiPayloads.illegalValue("brandHue", Integer.toString(brandHue),
                    "brandHue 必须落在 [" + UiPrefs.BRAND_HUE_MIN + "," + UiPrefs.BRAND_HUE_MAX + "]");
        }

        UiPrefs prefs = new UiPrefs(muteToasts, language, theme, brandHue);
        playerData(sender).setUiPrefs(prefs);
        // 回发落盘值而不是 {ok:true}: 前端据此对齐本地状态; 服务端日后若收窄某项取值域, 前端能立刻看到被改成什么。
        return GSON.toJson(prefsJson(prefs));
    };

    // ============================================================
    // 取数 helper
    // ============================================================

    /**
     * 取发送者的玩家 capability。
     *
     * capability 缺失不给 errorCode: 那是 Provider 没挂上的环境故障 / 不可能状态, 不是玩家能理解也无法应对的
     * 业务拒绝。让 IllegalStateException 自然冒泡到 Gateway 的通用兜底, 带堆栈进 WARN 日志 —— 与
     * JobServiceImpl 的同类处理一致, 也守住"errorCode 表只收真正的业务拒绝"这条纪律。
     */
    private static IMiningPlayerData playerData(ServerPlayer sender) {
        return MiningCapabilities.get(sender).orElseThrow(() -> new IllegalStateException(
                "玩家 " + sender.getGameProfile().getName() + " 未挂载矿山玩家数据 capability"));
    }

    private static JsonObject prefsJson(UiPrefs prefs) {
        JsonObject json = new JsonObject();
        json.addProperty("muteToasts", prefs.muteToasts());
        json.addProperty("language", prefs.language());
        json.addProperty("theme", prefs.theme());
        json.addProperty("brandHue", prefs.brandHue());
        return json;
    }
}
