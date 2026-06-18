package com.miningdim.job.farmer;

import com.miningdim.economy.Currency;
import com.miningdim.job.farmer.item.FarmerItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;

/**
 * NPC 小麦动态收购结算 (FarmingXP_Mod_DesignSpec 第八节方案4)。把玩家库存里的 mod 小麦按动态收购价兑成信用点。
 *
 * 接线状态 (审查 Major): 本服务的调用入口 (NPC 收购方块 / 菜单 / 命令) 与经济子系统对 {@link FarmerEconomyHooks}
 * 的 bind 均属集成阶段接线, 当前未落地 (见 foundationGaps)。故 {@link #sell} 在经济未接线时 ({@link
 * FarmerEconomyHooks#isBound()} false) 不扣物品、不发币, 返回 {@link SellResult#offline()} ——
 * 与 chef.ChefEconomyHooks.tryChargeTableUse 的 "!isBound() 放行" 同范式, 避免半实现路径在运行期抛
 * IllegalStateException。已绑定后才走真实收购结算。
 *
 * 流程 (经济已接线时, 仿 economy.AbuseGuard.checkAndChargeReset "先查后扣" 纪律杜绝双花/白发):
 *  1. 数玩家库存里的 mod 小麦株数, 取 min(库存, 请求量) = 实际可卖量;
 *  2. 按当日已售株数沿 {@link FarmerWheatBuyback} 收购曲线逐株求和 = 总价 (跨 softCap 边界连续);
 *  3. 先扣物品 (clearOrCountMatchingItems 返回实际移除数), 再按 "实际移除数 + 翻日校正后的当日已发信用点"
 *     钳进每日 faucet 软上限算本批应发额, 最后发币。先扣后发使发币量严格锚定已离手的小麦, 杜绝 "得币未失麦"
 *     (反通胀纪律: 宁可崩溃窗口内少发, 绝不多发/重复发); 已发信用点持久化 (非用收购曲线反推) 解除 cap 与
 *     softCap 处累计 gross 的隐式耦合 (审查 Minor)。
 *
 * 经济衰减与经验衰减独立 (第八节): 本服务只动信用点与卖菜计数, 不碰 JobProgress 的经验/dailyXp。
 */
public final class FarmerWheatSellService {

    private FarmerWheatSellService() {
    }

    /** 卖菜结算结果。{@code economyOffline} 为 true 表示经济服务未接线, 本次未扣物品也未发币。 */
    public record SellResult(int soldCount, long creditsGranted, boolean economyOffline) {

        /** 经济未接线: 不扣不发的空结果 (economyOffline=true)。 */
        public static SellResult offline() {
            return new SellResult(0, 0L, true);
        }
    }

    /**
     * 卖出最多 requestedAmount 株 mod 小麦, 返回实际卖出株数与发放信用点。
     *
     * @param player          卖家 (服务端)
     * @param requestedAmount 请求卖出株数 (>=1)
     * @return 实际卖出与发放结果; 经济未接线返回 {@link SellResult#offline()}; 库存无 mod 小麦返回 (0,0,false)
     */
    public static SellResult sell(ServerPlayer player, int requestedAmount) {
        if (requestedAmount < 1) {
            throw new IllegalArgumentException("requestedAmount must be >= 1, got " + requestedAmount);
        }
        if (!FarmerEconomyHooks.isBound()) {
            // 经济子系统未接线: 不扣物品、不发币 (集成阶段 bind 后才结算)。与 ChefEconomyHooks !isBound() 放行同纪律,
            // 不在运行期抛 IllegalStateException 让半实现路径炸服。
            return SellResult.offline();
        }
        int owned = countWheat(player);
        int toSell = Math.min(owned, requestedAmount);
        if (toSell <= 0) {
            return new SellResult(0, 0L, false);
        }

        long today = FarmerClock.currentUtcDayStamp();
        ServerLevel overworld = player.server.overworld();
        FarmerSavedData data = FarmerSavedData.get(overworld);
        int alreadySold = data.wheatSoldToday(player.getUUID(), today);

        // 先扣物品 (实际移除数), 发币量严格锚定已离手小麦 (先扣后发, 杜绝得币未失麦/重复发)。
        int removed = chargeWheat(player, toSell);
        if (removed <= 0) {
            return new SellResult(0, 0L, false);
        }

        long gross = FarmerWheatBuyback.totalBuyPrice(alreadySold, removed, FarmerConstants.WHEAT_BASE_PRICE);

        // 并入每日信用点 faucet 软上限 (spec 第八节): 用持久化的 "当日已发信用点" 算剩余额度 (不靠收购曲线反推),
        // 超出部分截断。注意: 当前为农夫私有 per-player 上限, 非全服统一 faucet 软上限 (地基 IEconomyService 无
        // grantDaily 发放侧每日计数 API, 见 foundationGaps); 待地基补 grantDaily 后改走全服统一 dailyKey。
        long alreadyCredited = data.wheatCreditedToday(player.getUUID(), today);
        long remainingCap = Math.max(0L, FarmerConstants.WHEAT_SELL_DAILY_CREDIT_CAP - alreadyCredited);
        long credits = Math.min(gross, remainingCap);

        data.recordWheatSale(player.getUUID(), removed, credits, today);
        if (credits > 0L) {
            FarmerEconomyHooks.service().grant(player, Currency.CREDIT, credits);
        }
        return new SellResult(removed, credits, false);
    }

    /** 玩家库存中 mod 小麦总数。 */
    private static int countWheat(ServerPlayer player) {
        return player.getInventory().clearOrCountMatchingItems(
                stack -> stack.is(FarmerItems.FARMER_WHEAT.get()), 0, new SimpleContainer(0));
    }

    /** 从玩家库存扣除 amount 个 mod 小麦, 返回实际扣除数。 */
    private static int chargeWheat(ServerPlayer player, int amount) {
        return player.getInventory().clearOrCountMatchingItems(
                stack -> stack.is(FarmerItems.FARMER_WHEAT.get()), amount, new SimpleContainer(0));
    }
}
