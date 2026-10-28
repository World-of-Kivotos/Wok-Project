package com.miningdim.freshness;

import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.entity.npc.VillagerTrades.ItemListing;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.village.VillagerTradesEvent;
import net.minecraftforge.event.village.WandererTradesEvent;

import java.util.List;
import java.util.Map;

/**
 * 村民货 (Spoilage_Granary_DesignSpec「村民」: 「收作物不拦; 卖出的食物在 VillagerTradesEvent、
 * WandererTradesEvent 里给食物报价的产物加『村民货』标记; 已有的村民在进世界时补一次;
 * **到手时**按剩 6 小时盖章」)。
 *
 * 分两步走是必须的:
 * <ol>
 *   <li><b>报价那一刻只打标记</b> ({@code MiningFresh.v}) —— 报价是开服时登记的, 那时就盖章的话,
 *       玩家几小时后才买的东西一到手就已经烂了; 标记还会参与「除新鲜度外是否相同」的比较, 所以村民货
 *       永远不会和普通货合并 (反漏洞 #54: 界面里 Shift 一点把 6 小时绕过去);</li>
 *   <li><b>到手那一刻按剩 6 小时盖章</b> —— 交给结算那一条路 ({@link FreshnessSettle#settleSlot}),
 *       背包扫描、登录、开容器、右键、掉落物全都过它, 所以从哪个口袋到手都一样。</li>
 * </ol>
 *
 * 「收作物不拦」是规格明写的: 村民买什么新鲜度的作物都收, 本模块不插手村民的收购。
 */
public final class FreshnessVillagerGoods {

    private FreshnessVillagerGoods() {
    }

    // ============================================================
    // 报价: 只打标记
    // ============================================================

    /** 新登记的村民报价 (含职业与等级分级)。 */
    public static void onVillagerTrades(VillagerTradesEvent event) {
        for (Map.Entry<Integer, List<ItemListing>> entry : event.getTrades().entrySet()) {
            markListings(entry.getValue());
        }
    }

    /** 流浪商人的报价 (普通与稀有)。 */
    public static void onWandererTrades(WandererTradesEvent event) {
        markListings(event.getGenericTrades());
        markListings(event.getRareTrades());
    }

    /**
     * 把一串报价模板换成「生成出来就带村民货标记」的包装。
     *
     * 事件给的是**模板** (`ItemListing`, 每位村民生成报价时才调用), 不是已经生成好的报价, 所以这里包一层;
     * 直接把模板换成结果的话, 所有村民会共用同一份产物栈。
     */
    private static void markListings(List<ItemListing> listings) {
        for (int i = 0; i < listings.size(); i++) {
            ItemListing listing = listings.get(i);
            if (!(listing instanceof MarkingListing)) {
                listings.set(i, new MarkingListing(listing));
            }
        }
    }

    /** 生成报价时给易腐产物打标记的包装。 */
    private record MarkingListing(ItemListing delegate) implements ItemListing {

        @Override
        public MerchantOffer getOffer(net.minecraft.world.entity.Entity trader,
                                      net.minecraft.util.RandomSource random) {
            MerchantOffer offer = delegate.getOffer(trader, random);
            if (offer != null) {
                markResult(offer);
            }
            return offer;
        }
    }

    /**
     * 已有的村民与流浪商人进世界时补一次标记。
     *
     * 报价是存档里的老数据 (本模块装上之前就存在), 事件不会再放一遍, 所以要在它们进世界时扫一次。
     * 标记是幂等的 (同一个标记写两遍没有区别)。
     */
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof Villager villager) {
            markOffers(villager);
        } else if (event.getEntity() instanceof WanderingTrader trader) {
            markOffers(trader);
        }
    }

    /** 把一位商人的全部报价扫一遍。 */
    public static int markOffers(Merchant merchant) {
        int marked = 0;
        for (MerchantOffer offer : merchant.getOffers()) {
            if (markResult(offer)) {
                marked++;
            }
        }
        return marked;
    }

    /**
     * 给一条报价的产物打村民货标记。
     *
     * @return 这次有没有新打上 (产物不腐烂、或者早就标记过都返回 false)
     */
    public static boolean markResult(MerchantOffer offer) {
        ItemStack result = offer.getResult();
        if (result.isEmpty() || !FreshnessCategories.spoils(result)
                || FreshnessData.isVillagerGoods(result)) {
            return false;
        }
        FreshnessData.markVillagerGoodsFlag(result);
        return true;
    }

    // ============================================================
    // 到手: 按剩 6 小时盖章
    // ============================================================

    /**
     * 到手那一刻的换算 (由 {@link FreshnessSettle#settleSlot} 每次结算时调用)。
     *
     * 只在「村民货」且**剩余还多于 6 小时**时压到 6 小时 —— 于是它是幂等的: 压过之后剩余只会继续变少,
     * 第二次结算就不会再压 (不能每次结算都重置成 6 小时, 那这堆东西永远不会烂)。
     *
     * @return 这次有没有真压过
     */
    public static boolean applyOnHandOver(ItemStack stack, long nowMinute) {
        if (stack.isEmpty() || !FreshnessData.isVillagerGoods(stack)) {
            return false;
        }
        int shelfLifeHours = FreshnessCategories.shelfLifeHours(stack);
        if (shelfLifeHours <= 0) {
            return false;
        }
        long remaining = FreshnessData.remainingMinutes(stack, shelfLifeHours, nowMinute);
        long sixHours = (long) FreshnessConstants.VILLAGER_GOODS_HOURS * 60L;
        if (remaining <= sixHours) {
            return false;
        }
        FreshnessData.markVillagerGoods(stack, nowMinute, shelfLifeHours);
        return true;
    }
}
