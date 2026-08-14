package com.miningdim.job.tarot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.miningdim.economy.EconomyServices;
import com.miningdim.job.tarot.card.TarotCardData;
import com.miningdim.job.tarot.pack.PackKind;
import com.miningdim.job.tarot.pack.TarotPackClock;
import com.miningdim.job.tarot.pack.TarotPackSavedData;
import com.miningdim.job.tarot.pack.TarotPackService;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiPayloads;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 塔罗师面板的 job.tarot.* WebUiAction (牌库只读态 + 买卡包写操作)。
 *
 * 服务端权威 (架构铁律 1): 买包的扣款/每日限购/发包全部复用 {@link TarotPackService#buy} 这唯一入口, 与
 * {@code /tarot pack buy} 同一条路径 —— 卡包是信用点主力 sink, 面板另写一套结算等于开一个绕过每日限购的印钞口。
 * 本层只做入参校验 (给出稳定 errorCode) 与 JSON 化, 一步结算都不重写。
 *
 * 落在 {@code com.miningdim.job.tarot} 包内: 牌的身份/品质/绑定三键的读取口 ({@link TarotCardItem}) 与卡包
 * 持久计数 ({@link TarotPackSavedData}) 都是本域内聚的真源, 同包取用即不必为面板放宽任何可见性。
 *
 * 时间一律发 tick 不发墙钟 (同 MinerWebUiActions): 服务端手里只有 game tick, 换算成服务端墙钟再让 MCEF 客户端
 * 拿 Date.now() 去减, 既吃时钟偏移又在 TPS 掉帧时失真。
 *
 * 回执体积: deck 恒 22 行 ({@link TarotArcana#COUNT}), qualities 恒 5 行, packs 恒 3 行 —— 三者都是枚举全集,
 * 编译期定长, 不随玩家数据长大, 故不需要分页 (体积上界 ~6KB, 远低于下行 32767 字符收口)。
 */
public final class TarotWebUiActions {

    /**
     * 本类专用的 Gson: 必须 serializeNulls。
     *
     * deck 行的 cooldownCategory / shinyCooldownTicks 在牌效 datapack 尚未加载完成时是真 null (不是 0, 也不是
     * 空串) —— 默认 Gson 会把值为 null 的成员整键丢掉, 前端拿到的就不是 {@code string | null} 而是 undefined。
     */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /**
     * 单次购买张数的取值域。数值本身不是本层的策略, 是 {@link TarotPackService#buy} 的入口前置条件 (它对域外
     * 直接抛 IllegalArgumentException); 在此重复校验只为把它转成带 errorCode 的业务拒绝 —— 裸 IAE 落进
     * Gateway 的通用兜底后是一条没有 errorCode 的文本, 前端定位不到是哪个输入框被拒。
     */
    private static final int MIN_PACK_COUNT = 1;
    private static final int MAX_PACK_COUNT = 64;

    private TarotWebUiActions() {
    }

    /** 把两条 job.tarot.* action 注册进派发器 (由 {@link TarotSystem#register} 调用一次)。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("job.tarot.state", STATE);
        WebUiServerDispatcher.register("job.tarot.buyPack", BUY_PACK);
    }

    // ============================================================
    // job.tarot.state: {} -> 22 张大阿卡纳持有情况 + 碎片 + 卡包经济位
    // ============================================================

    /**
     * 塔罗师面板只读态。本 action 不推进任何状态: 不占冷却、不动碎片、不改每日计数。
     *
     * 持有量拆成两栏是刻意的, 它们回答的是两个不同问题, 合并任一栏都会误导玩家:
     *  - ownedByQuality/owned 只数<b>绑定在本人名下</b>的牌 —— {@link TarotPlayHandler} 的第一道闸门就是
     *    ownerUUID 校验, 别人的牌拿在手里也打不出, 计进"持有"等于在面板上承诺一个打不出的效果;
     *  - inInventory 数背包里同 cardId 的<b>全部</b>可读牌 (不论绑定) —— 那才是
     *    {@link com.miningdim.job.tarot.pack.PackGachaService} 判"重复牌转碎片"的口径。
     */
    static final WebUiAction STATE = (sender, payload) -> {
        int level = TarotLeveling.level(sender);
        long today = TarotPackClock.currentUtcDayStamp();
        TarotPackSavedData packData = TarotPackSavedData.get(sender.server.overworld());
        int dailyLimit = TarotConfig.DAILY_PACK_LIMIT.get();
        int boughtToday = packData.acquiredToday(sender.getUUID(), today);

        JsonObject result = new JsonObject();
        result.addProperty("level", level);
        // 测试模式改变买包的真实行为 (免费且不计日限), 面板必须能说明白, 否则价格栏与实际扣款对不上。
        result.addProperty("testMode", TarotConfig.TEST_MODE.get());

        result.addProperty("shards", TarotShardExchange.countShards(sender));
        result.addProperty("shardExchangeCost", TarotConfig.SHARD_EXCHANGE_COST.get());
        result.addProperty("duplicateShardRefund", TarotConfig.DUPLICATE_SHARD_REFUND.get());

        JsonArray qualities = new JsonArray();
        for (TarotQuality quality : TarotQuality.values()) {
            JsonObject row = new JsonObject();
            row.addProperty("qualityId", quality.id());
            // 与 TarotCardItem.appendHoverText 同一批 lang 键 (不另起一套命名)。
            row.addProperty("nameKey", "tooltip.miningdim.tarot.quality." + quality.id());
            row.addProperty("tierIndex", quality.tierIndex());
            row.addProperty("requiredLevel", quality.requiredLevel());
            // 用牌等级门的唯一判据 (与 TarotPlayHandler 第二道闸门同一函数), 不在此重算 level >= requiredLevel。
            row.addProperty("usable", TarotLeveling.canUseQuality(sender, quality));
            row.addProperty("rawXp", TarotLeveling.rawXpFor(quality));
            qualities.add(row);
        }
        result.add("qualities", qualities);

        // 满 CD 时长 (ticks)。剩余 CD 发不出去: 见交付报告 blockers —— TarotCooldownManager 只有"校验并占用"的
        // tryUse, 面板一调就把玩家的冷却吃掉, 而它的三张截止 tick 表是私有的, 现阶段没有只读入口。
        JsonObject cooldownTicks = new JsonObject();
        cooldownTicks.addProperty("gcd", TarotConfig.GCD_TICKS.get());
        cooldownTicks.addProperty("utility", TarotConfig.CD_UTILITY_TICKS.get());
        cooldownTicks.addProperty("buff", TarotConfig.CD_BUFF_TICKS.get());
        cooldownTicks.addProperty("combat", TarotConfig.CD_COMBAT_TICKS.get());
        result.add("cooldownTicks", cooldownTicks);

        boolean cardDataLoaded = TarotRuntime.cardLoader().isLoaded();
        result.addProperty("cardDataLoaded", cardDataLoaded);

        int[][] ownedByQuality = new int[TarotArcana.COUNT][TarotQuality.values().length];
        int[] inInventory = new int[TarotArcana.COUNT];
        countCards(sender, ownedByQuality, inInventory);

        JsonArray deck = new JsonArray();
        for (TarotArcana arcana : TarotArcana.values()) {
            JsonObject row = new JsonObject();
            row.addProperty("cardId", arcana.cardId());
            row.addProperty("arcanaId", arcana.id());
            row.addProperty("nameKey", "tooltip.miningdim.tarot.arcana." + arcana.id());

            JsonArray counts = new JsonArray();
            int owned = 0;
            for (TarotQuality quality : TarotQuality.values()) {
                int held = ownedByQuality[arcana.cardId()][quality.ordinal()];
                counts.add(held);
                owned += held;
            }
            row.add("ownedByQuality", counts);
            row.addProperty("owned", owned);
            row.addProperty("inInventory", inInventory[arcana.cardId()]);

            if (cardDataLoaded) {
                TarotCardData data = TarotRuntime.cardLoader().get(arcana);
                // Category 没有公开的 id() getter, 只能按枚举名折算; Locale.ROOT 固定折算规则 (土耳其语环境下
                // 默认 toLowerCase 会把 I 折成 ı, 那会让下发的分档 id 静默变成前端字典里没有的键)。
                row.addProperty("cooldownCategory", data.cooldownCategory().name().toLowerCase(Locale.ROOT));
                row.addProperty("shinyCooldownTicks", data.shinyCooldownTicks());
            } else {
                // 牌效表尚未重载完 (或重载失败): 这两栏无值可发, 发真 null 而不是 0 —— 0 会被画成"零冷却"。
                row.add("cooldownCategory", JsonNull.INSTANCE);
                row.add("shinyCooldownTicks", JsonNull.INSTANCE);
            }
            deck.add(row);
        }
        result.add("deck", deck);

        JsonArray packs = new JsonArray();
        for (PackKind kind : PackKind.values()) {
            Item item = packItem(kind);
            JsonObject row = new JsonObject();
            row.addProperty("packKind", kind.id());
            row.addProperty("itemId", itemId(item));
            row.addProperty("nameKey", item.getDescriptionId());
            row.addProperty("currency", TarotPackService.currency(kind).name());
            row.addProperty("unitPrice", TarotPackService.price(kind));
            packs.add(row);
        }
        result.add("packs", packs);

        result.addProperty("packsBoughtToday", boughtToday);
        result.addProperty("packDailyLimit", dailyLimit);
        result.addProperty("packsRemainingToday", remainingToday(dailyLimit, boughtToday));
        // 高级包保底进度 (连续未出 SSR 的包数); 到阈值时下一个高级包首张保底 SSR。
        result.addProperty("advancedPityStreak", packData.advancedNoSsrStreak(sender.getUUID()));
        result.addProperty("advancedPityThreshold", TarotConfig.PITY_SSR_PACKS.get());
        return GSON.toJson(result);
    };

    // ============================================================
    // job.tarot.buyPack: {kind,count} -> 实际扣款与剩余限购
    // ============================================================

    /**
     * 买卡包 (写操作: 扣款 + 计日限 + 发包)。
     *
     * 全部结算在 {@link TarotPackService#buy} 内按序发生, 本层一步不重写:
     *  1. 每日限购预检 (不过则原样返回 DAILY_LIMIT, 未扣款未发包);
     *  2. {@code IEconomyService.tryCharge} 事务安全扣款 (余额不足返 false 且不扣, 于是不发包);
     *  3. {@link TarotPackSavedData#recordAcquired} 记入当日已购;
     *  4. {@code ItemHandlerHelper.giveItemToPlayer} 逐个发包 (背包满则落地, 不会丢)。
     * 扣款在发包之前且失败即短路, 故"扣了款没拿到包"只可能来自 3/4 步抛异常 —— 见报告 blockers。
     *
     * 经济未接线时提前拒: 真会扣款的那一档 (非测试模式且总价 > 0) 若此时调下去, 拿到的是
     * {@code EconomyServices.economyService()} 的裸 IllegalStateException, 那在 Gateway 通用兜底里没有
     * errorCode, 前端只能显示一句英文。
     */
    static final WebUiAction BUY_PACK = (sender, payload) -> {
        PackKind kind = requirePackKind(payload);
        int count = WebUiPayloads.requiredInt(payload, "count");
        if (count < MIN_PACK_COUNT || count > MAX_PACK_COUNT) {
            throw WebUiPayloads.illegalValue("count", Integer.toString(count),
                    "count 必须在 [" + MIN_PACK_COUNT + "," + MAX_PACK_COUNT + "] 内");
        }

        boolean testMode = TarotConfig.TEST_MODE.get();
        long unitPrice = TarotPackService.price(kind);
        long totalPrice = Math.multiplyExact(unitPrice, (long) count);
        if (!testMode && totalPrice > 0L && !EconomyServices.isRegistered()) {
            throw new WebUiBusinessException(WebUiErrorCodes.ECONOMY_OFFLINE,
                    "经济子系统未就绪, 本次未扣款也未发包", false);
        }

        TarotPackService.PurchaseResult purchase = TarotPackService.buy(sender, kind, count);
        int dailyLimit = TarotConfig.DAILY_PACK_LIMIT.get();
        if (purchase.status() == TarotPackService.PurchaseStatus.DAILY_LIMIT) {
            // 借用 RATE_LIMITED: 每日购包上限没有专属错误码, 见报告 blockers (主控统一加码后此处换掉)。
            throw new WebUiBusinessException(WebUiErrorCodes.RATE_LIMITED,
                    "今日购包已达上限, 剩余 " + purchase.remainingToday() + " 个", false,
                    Map.of("scope", "tarot_pack_daily",
                            "requested", Integer.toString(count),
                            "remainingToday", Integer.toString(purchase.remainingToday()),
                            "dailyLimit", Integer.toString(dailyLimit)));
        }
        if (purchase.status() == TarotPackService.PurchaseStatus.NOT_ENOUGH_CURRENCY) {
            throw new WebUiBusinessException(WebUiErrorCodes.INSUFFICIENT_FUNDS,
                    "余额不足, 本次需要 " + purchase.totalPrice(), false,
                    Map.of("currency", TarotPackService.currency(kind).name(),
                            "totalPrice", Long.toString(purchase.totalPrice()),
                            "packKind", kind.id()));
        }

        Item item = packItem(kind);
        JsonObject result = new JsonObject();
        result.addProperty("packKind", kind.id());
        result.addProperty("itemId", itemId(item));
        result.addProperty("nameKey", item.getDescriptionId());
        result.addProperty("count", purchase.count());
        result.addProperty("currency", TarotPackService.currency(kind).name());
        result.addProperty("unitPrice", unitPrice);
        // 实扣额取 buy 的回执而不是本层的乘积: 测试模式下它是 0 (免费), 两者会分叉。
        result.addProperty("totalPrice", purchase.totalPrice());
        result.addProperty("testMode", testMode);
        result.addProperty("packsBoughtToday", TarotPackSavedData.get(sender.server.overworld())
                .acquiredToday(sender.getUUID(), TarotPackClock.currentUtcDayStamp()));
        result.addProperty("packsRemainingToday", purchase.remainingToday());
        result.addProperty("packDailyLimit", dailyLimit);
        return GSON.toJson(result);
    };

    // ============================================================
    // 取数 helper
    // ============================================================

    /**
     * 一次遍历同时数出两栏 (语义见 {@link #STATE} 的注释): ownedByQuality 只计绑定本人的牌, inInventory 计
     * 背包里同 cardId 的全部可读牌。
     *
     * 扫描范围 = 主背包 + 副手, 与 {@link TarotShardExchange#countShards} 及开包重复判定逐字一致 (末影箱与
     * 盔甲位不在内): 三处口径必须同源, 否则面板说"没有"而开包判"重复"。
     */
    private static void countCards(ServerPlayer player, int[][] ownedByQuality, int[] inInventory) {
        UUID self = player.getUUID();
        accumulateCards(player.getInventory().items, self, ownedByQuality, inInventory);
        accumulateCards(player.getInventory().offhand, self, ownedByQuality, inInventory);
    }

    private static void accumulateCards(List<ItemStack> slots, UUID self,
                                        int[][] ownedByQuality, int[] inInventory) {
        for (ItemStack stack : slots) {
            if (stack.isEmpty() || !(stack.getItem() instanceof TarotCardItem)) {
                continue;
            }
            // 身份三键缺失或越界的脏牌 (创造模式直给的裸牌是真实场景) 降级为不可读, 只读面板不因它报错。
            if (!TarotCardItem.hasReadableCardIdentity(stack)) {
                continue;
            }
            int cardId = TarotCardItem.cardId(stack);
            inInventory[cardId] += stack.getCount();
            if (self.equals(TarotCardItem.owner(stack))) {
                ownedByQuality[cardId][TarotCardItem.quality(stack).ordinal()] += stack.getCount();
            }
        }
    }

    /** 今日还能买几个 (口径与 {@link TarotPackService#buy} 的回执逐字一致: 计数越界时夹到 0)。 */
    private static int remainingToday(int dailyLimit, int boughtToday) {
        return Math.max(0, dailyLimit - boughtToday);
    }

    private static PackKind requirePackKind(JsonObject payload) {
        String raw = WebUiPayloads.requiredString(payload, "kind");
        for (PackKind kind : PackKind.values()) {
            if (kind.id().equals(raw)) {
                return kind;
            }
        }
        throw WebUiPayloads.illegalValue("kind", raw, "kind 必须是 common / advanced / shiny 之一");
    }

    /** 卡包种类 -> 物品 (与 {@code TarotPackItem.create} 内那份映射同表; 那份是私有的, 取不到)。 */
    private static Item packItem(PackKind kind) {
        return switch (kind) {
            case COMMON -> TarotRegistry.PACK_COMMON.get();
            case ADVANCED -> TarotRegistry.PACK_ADVANCED.get();
            case SHINY -> TarotRegistry.PACK_SHINY.get();
        };
    }

    private static String itemId(Item item) {
        return ForgeRegistries.ITEMS.getKey(item).toString();
    }
}
