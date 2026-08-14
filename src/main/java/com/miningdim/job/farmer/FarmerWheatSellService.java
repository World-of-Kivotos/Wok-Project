package com.miningdim.job.farmer;

import com.miningdim.economy.EconomyServices;
import com.miningdim.job.farmer.item.FarmerItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * NPC 小麦动态收购结算 (FarmingXP_Mod_DesignSpec 第八节方案4)。把玩家库存里的 mod 小麦按动态收购价兑成信用点。
 *
 * 触发点 (审查 Critical 1): 由 {@link FarmerSystem} 自注册的 /farmer sell &lt;amount&gt; 命令调用本服务
 * (包内闭合, 不改共享 JobCommands)。卖菜是整条经济龙头, 此前全库无任何调用方故卖菜功能形同不存在; 命令接通后可达。
 *
 * 经济接线 (审查 Critical 2 / Minor): 经济门面经 {@link EconomyServices#economyService()} 服务定位器取用
 * (与矿工 settleOreSale 同一货币层入口), 不再走农夫私有 *EconomyHooks static bind seam ——
 * 该 seam 全库无 bind 调用方故 service 恒 null, 是过期判断 ("economy 无实现/无定位器") 驱动的死代码, 已删除。
 * 经济未就绪时由 {@link EconomyServices#isRegistered()} 判定, 不扣物品不发币返回 {@link SellResult#offline()}。
 *
 * 流程 (经济已就绪时, 仿 economy.AbuseGuard.checkAndChargeReset "先查后扣" 纪律杜绝双花/白发):
 *  1. 数玩家库存里的 mod 小麦株数, 取 min(库存, 请求量) = 实际可卖量;
 *  2. 先扣物品 (clearOrCountMatchingItems 返回实际移除数), 发币量严格锚定已离手小麦 (先扣后发, 杜绝得币未失麦);
 *  3. 按当日已售株数沿 {@link FarmerWheatBuyback} 收购曲线逐株求和 = 本批毛收 gross (跨 softCap 边界连续);
 *  4. gross 作 rawCredit 经 {@link com.miningdim.economy.IEconomyService#grantDaily} 入账: 传全服共享
 *     {@link FarmerConstants#WHEAT_SELL_FAUCET_KEY} 与 {@link FarmerConstants#DAILY_CREDIT_FAUCET_CAP}
 *     (二者均转引经济全局常量, 第十一章决策 4), 由货币层按全服统一衰减主闸逐档衰减 (0.6 衰减 / 60000 档 / 1% 地板,
 *     渐近 15 万; 与矿工卖矿同档同键) 后落账本; 返回实发额。
 *
 * 两条曲线分工 (第八节明示独立): 收购曲线 (FarmerWheatBuyback, 按当日卖出株数) 是边际单价递减; 全服每日信用点
 * faucet 软上限 (grantDaily, 按当日累计入账信用点) 是货币注入天花板。前者农夫私有持久 (本服务记 wheatSoldToday),
 * 后者由货币层 (playerId, faucetKey) 计数器统一管 (此处不再持农夫私有每日信用点计数, 见审查 Major)。
 *
 * 经济衰减与经验衰减独立 (第八节): 本服务只动信用点与卖菜计数, 不碰 JobProgress 的经验/dailyXp。
 */
public final class FarmerWheatSellService {

    private FarmerWheatSellService() {
    }

    /** 卖菜结算结果。{@code economyOffline} 为 true 表示经济服务未注册, 本次未扣物品也未发币。 */
    public record SellResult(int soldCount, long creditsGranted, boolean economyOffline) {

        /** 经济未注册: 不扣不发的空结果 (economyOffline=true)。 */
        public static SellResult offline() {
            return new SellResult(0, 0L, true);
        }
    }

    /**
     * 卖出最多 requestedAmount 株 mod 小麦, 返回实际卖出株数与发放信用点。
     *
     * @param player          卖家 (服务端)
     * @param requestedAmount 请求卖出株数 (>=1)
     * @return 实际卖出与发放结果; 经济未注册返回 {@link SellResult#offline()}; 库存无 mod 小麦返回 (0,0,false)
     */
    public static SellResult sell(ServerPlayer player, int requestedAmount) {
        if (requestedAmount < 1) {
            throw new IllegalArgumentException("requestedAmount must be >= 1, got " + requestedAmount);
        }
        if (!EconomyServices.isRegistered()) {
            // 经济子系统未注册: 不扣物品、不发币 (经济未就绪不阻塞核心循环, 与 chef tryChargeTableUse 未就绪放行同纪律)。
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

        // 本批毛收 = 收购曲线逐株求和 (跨 softCap 连续, 边际单价递减)。
        long gross = FarmerWheatBuyback.totalBuyPrice(alreadySold, removed, FarmerConstants.WHEAT_BASE_PRICE);

        // 收购曲线深度超 softCap 后单株单价 floor(base*0.25) 可下取整到 0 (base=1 时), 使 gross=0; 此时不调
        // grantDaily (其契约 rawCredit>0 否则抛 ILLEGAL_AMOUNT)。物品已扣 (锚定离手小麦), 本批发币 0 是收购曲线
        // 已衰减到无货币注入的正常结果 (非吞异常: gross<=0 是边际收益归零的预期, 非装配缺陷)。
        // 这一支照旧记当日株数: 曲线已到底, 但"卖出去了"这件事本身仍要计入当日深度。
        if (gross <= 0L) {
            data.recordWheatSale(player.getUUID(), removed, today);
            return new SellResult(removed, 0L, false);
        }

        // 入账经全服每人每日统一信用点衰减主闸 (grantDaily 内部 0.6 衰减 / 60000 档 / 1% 地板, 第十一章决策 2/4,
        // 与矿工卖矿共享同一 faucetKey 命名空间 -> 同一天花板)。返回衰减后实发额。
        long credits;
        try {
            credits = EconomyServices.economyService().grantDaily(
                    player, gross, FarmerConstants.WHEAT_SELL_FAUCET_KEY, FarmerConstants.DAILY_CREDIT_FAUCET_CAP);
        } catch (RuntimeException payoutFailed) {
            /*
             * 这不是吞异常 —— 异常原样重抛, 只是在它冒泡之前把已经发生的那半步副作用撤掉。
             *
             * 小麦是内存里的背包操作, 没法跟着 SQLite 事务一起回滚; 而 grantDaily 抛出时 (库被锁 / 磁盘满)
             * 物品已经离手了。不还的话玩家净损失一批作物, 且 Gateway 在 handler 之前就把 requestId 烧进了
             * 防重放窗口 —— 他连原样重试都做不到 (换新 requestId 重试则会再扣一批)。
             *
             * 当日计数刻意留到发币成功之后才记 (见下一行), 所以这条失败路径上没有第二处副作用要撤 ——
             * 否则还得给 FarmerSavedData 开一个反向接口, 而那个方法明确拒绝负数增量。
             */
            refundWheat(player, removed);
            throw payoutFailed;
        }
        data.recordWheatSale(player.getUUID(), removed, today);
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

    /**
     * 把已扣的小麦还给玩家 (发币失败时的补偿)。
     *
     * 背包放不下的部分掉在脚边而不是静默蒸发: 补偿的意义就是玩家一株不少, 放不下就该看得见东西掉出来。
     * 按最大堆叠拆批 —— {@code add} 一次只处理一个 ItemStack, 数量超过堆叠上限时会只放进去一摞。
     */
    private static void refundWheat(ServerPlayer player, int amount) {
        Item wheat = FarmerItems.FARMER_WHEAT.get();
        int remaining = amount;
        while (remaining > 0) {
            int batch = Math.min(remaining, wheat.getMaxStackSize());
            ItemStack stack = new ItemStack(wheat, batch);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            remaining -= batch;
        }
    }
}
