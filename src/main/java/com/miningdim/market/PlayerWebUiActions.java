package com.miningdim.market;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 玩家自身数据的两个 player.* WebUiAction (跳蚤市场前端顶栏余额 + 挂单选物)。让游戏内真桥脱离前端 Mock:
 * 之前 player.inventory / player.wallet 仅 bridge.mock.ts 内存假数据, 进游戏后无服务端实现即落空。
 *
 * 服务端权威 (架构铁律 1, 同 {@link MarketActions}): 取经服务端校验过的 sender (不信前端 uuid), 背包读 sender 自己的
 * {@link Inventory}, 余额经 {@link IEconomyService} 只读账本 (SavedData 仅服务端存在)。坏输入/业务错误自然抛冒泡到
 * {@link WebUiServerDispatcher#dispatchAndRespond} 的 Gateway 统一兜底, 本类严禁 try-catch 生吞 (CLAUDE.md C9)。
 *
 * 前端契约 (World-of-Kivotos_GameUI/src/types.ts):
 *  - player.inventory -&gt; {items:[{slot,itemId,count,displayName?}]} (InvResp; SellView 选物)
 *  - player.wallet    -&gt; {credit,azure} (Wallet; 顶栏余额)
 */
public final class PlayerWebUiActions {

    private static final Gson GSON = new Gson();

    private PlayerWebUiActions() {
    }

    /** 把 2 个 player.* action 注册进派发器 (由 MarketSubsystem.register 调用)。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("player.inventory", INVENTORY);
        WebUiServerDispatcher.register("player.wallet", WALLET);
    }

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
            // nbt 摘要: 仅自定义命名物品附 displayName (改名携 NBT, 是有意义的 nbt 摘要), 让前端区分同 id 的不同实例。
            if (stack.hasCustomHoverName()) {
                o.addProperty("displayName", stack.getHoverName().getString());
            }
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
}
