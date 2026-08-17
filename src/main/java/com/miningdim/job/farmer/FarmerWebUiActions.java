package com.miningdim.job.farmer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.farmer.block.FarmerBlocks;
import com.miningdim.job.farmer.item.FarmerItems;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiPayloads;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;

/**
 * 农夫面板的 job.farmer.* WebUiAction (收购曲线只读态 + 卖菜写操作)。
 *
 * K5 红线: 卖菜的全部结算复用 {@link FarmerWheatSellService#sell} 这唯一入口 —— 先扣物后发钱、收购曲线逐株
 * 求和、经 {@code IEconomyService.grantDaily} 过全服 faucet 衰减主闸, 一步都不许在本层重算。面板与 /farmer sell
 * 走同一条路径, 才不会出现"从平板卖比用命令卖多赚"这种洗钱口。
 *
 * 前端契约 (webui/src/lib/types.ts):
 *  - job.farmer.state -&gt; {level,crop,soldToday,dailySoftCap,basePrice,priceFloorRatio,nextUnitPrice,farmlandTiers[5]}
 *  - job.farmer.sell  -&gt; {soldCount,credited,soldToday,nextUnitPrice}
 */
public final class FarmerWebUiActions {

    private static final Gson GSON = new Gson();

    private FarmerWebUiActions() {
    }

    /** 把两条 job.farmer.* action 注册进派发器 (由 {@link FarmerSystem#register} 调用一次)。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("job.farmer.state", STATE);
        WebUiServerDispatcher.register("job.farmer.sell", SELL);
    }

    // ============================================================
    // job.farmer.state: {} -> 收购曲线定位 + 五档耕地表
    // ============================================================

    /**
     * 农夫面板只读态。
     *
     * crop 是单个对象而不是恒长 1 的数组: 全服只有一种可卖收获物 ({@link FarmerWheatSellService} 只认
     * {@link FarmerItems#FARMER_WHEAT}), 发数组会诱导前端做出一个根本不存在的多品类选择器。
     *
     * 日戳走 {@link FarmerClock} 而不是经济子系统的 currentDayStamp: 当日已售株数是农夫私有持久计数, 它的翻日
     * 判据在 {@link FarmerWheatSellService#sell} 里就是 FarmerClock, 本处必须同源, 否则面板显示的 soldToday
     * 会与下一次结算实际用的档位对不上。
     */
    static final WebUiAction STATE = (sender, payload) -> {
        int level = JobServices.jobService().level(sender, JobId.FARMER);
        int soldToday = soldToday(sender);

        JsonObject result = new JsonObject();
        result.addProperty("level", level);

        Item wheat = FarmerItems.FARMER_WHEAT.get();
        JsonObject crop = new JsonObject();
        crop.addProperty("itemId", itemId(wheat));
        crop.addProperty("descriptionId", wheat.getDescriptionId());
        result.add("crop", crop);

        result.addProperty("soldToday", soldToday);
        // 株量纲 (2160 株), 不是 CP 量纲的 DAILY_CREDIT_FAUCET_CAP —— 两条独立曲线, 混用即数值错位。
        result.addProperty("dailySoftCap", FarmerConstants.WHEAT_DAILY_SOFTCAP);
        result.addProperty("basePrice", FarmerConstants.WHEAT_BASE_PRICE);
        result.addProperty("priceFloorRatio", FarmerConstants.WHEAT_PRICE_FLOOR_RATIO);
        result.addProperty("nextUnitPrice", nextUnitPrice(soldToday));

        JsonArray tiers = new JsonArray();
        for (FarmerCropTable.Row row : FarmerCropTable.rows()) {
            JsonObject tier = new JsonObject();
            tier.addProperty("tierId", row.tier().id());
            tier.addProperty("nameKey", FarmerBlocks.farmland(row.tier()).get().getDescriptionId());
            tier.addProperty("unlockLevel", row.unlockLevel());
            tier.addProperty("unlocked", row.tier().isUnlockedAt(level));
            tier.addProperty("growthMinutes", row.growthMinutes());
            tier.addProperty("yieldPerHarvest", row.yieldMultiplier());
            tier.addProperty("wheatPerHour", row.farmerWheatPerHour());
            tiers.add(tier);
        }
        result.add("farmlandTiers", tiers);
        return GSON.toJson(result);
    };

    // ============================================================
    // job.farmer.sell: {count} -> 实际卖出与实发信用点
    // ============================================================

    /**
     * 卖出最多 count 株农夫小麦 (写操作)。
     *
     * count 在本层先校验下界再交给服务: 直接把 0 或负数丢进去只会拿到 {@link IllegalArgumentException},
     * 那在 Gateway 通用兜底里是一条没有 errorCode 的裸文本, 前端无法定位到是哪个输入框被拒。
     *
     * {@code soldCount > 0} 而 {@code credited == 0} 是合法结果 (收购曲线跌到地板后单株单价下取整为 0,
     * 物品照扣发币为 0), 故不在此转成失败 —— 那会让玩家以为东西没卖出去, 而背包里的小麦确实已经没了。
     */
    static final WebUiAction SELL = (sender, payload) -> {
        int count = WebUiPayloads.requiredInt(payload, "count");
        if (count < 1) {
            throw WebUiPayloads.illegalValue("count", Integer.toString(count), "count 必须 >= 1");
        }

        FarmerWheatSellService.SellResult sold = FarmerWheatSellService.sell(sender, count);
        if (sold.economyOffline()) {
            throw new WebUiBusinessException(WebUiErrorCodes.ECONOMY_OFFLINE,
                    "经济子系统未就绪, 本次未扣物也未发币", false);
        }
        // 等级门必须排在 NOTHING_TO_SELL 之前判: belowMastery 的 soldCount 也是 0 (拒绝时零副作用, 一株不扣),
        // 落到下面那条会告诉玩家"背包里没有可卖的农夫小麦" —— 而他背包里明明有, 只是等级不够。
        if (sold.belowMastery()) {
            throw new WebUiBusinessException(WebUiErrorCodes.SELL_LEVEL_TOO_LOW,
                    "农夫精通等级不足, 本次未扣物也未发币", false,
                    Map.of("job", JobId.FARMER.name(),
                            "requiredLevel", Integer.toString(FarmerConstants.SELL_MIN_MASTERY_LEVEL),
                            "currentLevel", Integer.toString(JobServices.jobService().level(sender, JobId.FARMER))));
        }
        if (sold.soldCount() <= 0) {
            throw new WebUiBusinessException(WebUiErrorCodes.NOTHING_TO_SELL,
                    "背包里没有可卖的农夫小麦", false,
                    Map.of("itemId", itemId(FarmerItems.FARMER_WHEAT.get())));
        }

        // sell 内部已 recordWheatSale, 故此处重读即结算后的最新值 (再算一次单价就是下一株的价)。
        int soldToday = soldToday(sender);
        JsonObject result = new JsonObject();
        result.addProperty("soldCount", sold.soldCount());
        result.addProperty("credited", sold.creditsGranted());
        result.addProperty("soldToday", soldToday);
        result.addProperty("nextUnitPrice", nextUnitPrice(soldToday));
        return GSON.toJson(result);
    };

    // ============================================================
    // 取数 helper
    // ============================================================

    /** 当日已售株数 (UTC 翻日, 与卖菜结算同一口径)。 */
    private static int soldToday(ServerPlayer sender) {
        return FarmerSavedData.get(sender.server.overworld())
                .wheatSoldToday(sender.getUUID(), FarmerClock.currentUtcDayStamp());
    }

    /** 下一株的收购单价 (已含曲线衰减)。countSoFar 含本株, 故传 soldToday + 1。 */
    private static long nextUnitPrice(int soldToday) {
        return FarmerWheatBuyback.wheatBuyPrice(soldToday + 1, FarmerConstants.WHEAT_BASE_PRICE);
    }

    private static String itemId(Item item) {
        return ForgeRegistries.ITEMS.getKey(item).toString();
    }
}
