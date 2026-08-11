package com.miningdim.economy;

import com.miningdim.economy.EconomyConstants.HighValueOre;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;

import java.util.UUID;
import java.util.function.Function;

/**
 * 货币门面实现 (经济文档第九章 + 框架 spec 第三章)。所有玩家货币读写经 {@link EconomyLedger}
 * (UUID 键, 服务端权威, 落统一 SQLite), 衰减/翻日复用 {@link AbuseGuard} 的纯函数与 UTC 时钟
 * (单一真值, 不另起一套)。
 *
 * 由 {@link EconomySystem} 在 ServerStartedEvent 在统一库连接上建账本后构造, 注入
 * {@link EconomyServices} 定位器 (job 包定位器范式; 不碰 core.MiningServices, 见类注释)。
 *
 * 异常纪律: 非法金额/入账溢出由 {@link PlayerWallet} 抛 {@link EconomyException} 自然冒泡, 本类不生吞;
 * 余额不足由 {@link #tryCharge} 返 false (事务安全)。数据库故障同样自然冒泡, 严禁降级到内存副本 ——
 * 留一个可写的第二份账本等于让"钱与资产不一致"重新出现。
 *
 * 线程: 全部服务端主线程调用 (职业事件回调 / 命令 / 网络 handler 均主线程)。账本同主线程单连接访问。
 */
public final class EconomyService implements IEconomyService {

    private final EconomyLedger ledger;
    private final AbuseGuard abuseGuard;
    private final Function<UUID, PlayerAbuseState> stateResolver;

    /**
     * @param ledger        全服账本 (统一 SQLite, 由 EconomySystem 在服务端启动期建在共享连接上)
     * @param abuseGuard    复用经济子系统已有的衰减纯函数 + UTC 翻日时钟 (不重复实现 0.97/0.25 与翻日口径)
     * @param stateResolver 以玩家 UUID 取 {@link PlayerAbuseState} (由 {@link EconomySystem} 提供唯一所有的态表入口;
     *                      供 {@link #recordMinedOreDrops} 计入当日矿物计数与 {@link #isAfkFrozen} 读冻结态, 与
     *                      {@link EconomySystem#onBlockBreak} 共用同一态实例, 不另起一套玩家态)
     */
    public EconomyService(EconomyLedger ledger, AbuseGuard abuseGuard,
                          Function<UUID, PlayerAbuseState> stateResolver) {
        if (ledger == null) {
            throw new IllegalArgumentException("EconomyLedger must not be null");
        }
        if (abuseGuard == null) {
            throw new IllegalArgumentException("AbuseGuard must not be null");
        }
        if (stateResolver == null) {
            throw new IllegalArgumentException("PlayerAbuseState resolver must not be null");
        }
        this.ledger = ledger;
        this.abuseGuard = abuseGuard;
        this.stateResolver = stateResolver;
    }

    @Override
    public long creditBalance(ServerPlayer player) {
        return ledger.balance(player.getUUID(), Currency.CREDIT);
    }

    @Override
    public long heartstoneBalance(ServerPlayer player) {
        return ledger.balance(player.getUUID(), Currency.AZURE);
    }

    @Override
    public boolean tryCharge(ServerPlayer player, Currency currency, long amount) {
        return ledger.tryDebit(player.getUUID(), currency, amount);
    }

    @Override
    public EconomyOperationStatus tryChargeBundle(EconomyOperationDomain domain, ServerPlayer player,
                                                   UUID operationId, long creditAmount, long azureAmount) {
        return ledger.tryChargeBundle(domain, player.getUUID(), operationId, creditAmount, azureAmount);
    }

    @Override
    public EconomyOperationStatus operationStatus(EconomyOperationDomain domain, UUID playerId, UUID operationId) {
        return ledger.operationStatus(domain, playerId, operationId);
    }

    @Override
    public EconomyOperationStatus completeBundle(EconomyOperationDomain domain, UUID playerId, UUID operationId) {
        return ledger.completeBundle(domain, playerId, operationId);
    }

    @Override
    public EconomyOperationStatus refundBundle(EconomyOperationDomain domain, UUID playerId, UUID operationId) {
        return ledger.refundBundle(domain, playerId, operationId);
    }

    @Override
    public void grant(ServerPlayer player, Currency currency, long amount) {
        ledger.credit(player.getUUID(), currency, amount);
    }

    @Override
    public void grantBundle(ServerPlayer player, long creditAmount, long azureAmount) {
        ledger.creditBundle(player.getUUID(), creditAmount, azureAmount);
    }

    @Override
    public boolean tryChargeDaily(ServerPlayer player, Currency currency, long amount,
                                  String dailyKey, long dailyCap) {
        // 与职业经验软上限共用 UTC epochDay 时钟 (经济文档 0.3-78), 由 AbuseGuard 提供单一翻日口径。
        long today = abuseGuard.currentPlayerDayStamp();
        return ledger.tryChargeDaily(player.getUUID(), currency, amount, dailyKey, dailyCap, today);
    }

    @Override
    public long settleOreSale(ServerPlayer player, HighValueOre ore, int countSoFar, double basePrice) {
        // 第十一章决策 3: 两层串联。(1) 毛值 = 逐矿 steering 单价 (buyPrice: per-ore 0.97 衰减至 1% 地板, 引导撞主闸前
        // 优先卖高价矿); (2) 毛值再经 grantDaily 并入全服统一衰减主闸 (0.6/60000/1%), 与农夫卖菜共享同一每人每日天花板。
        // 此前直接 ledger.credit 绕过主闸 = 卖矿不受全服日衰减约束的印钞口 (红队 Critical), 现并入主闸闭合。
        double unit = abuseGuard.buyPrice(ore, countSoFar, basePrice);
        long gross = (long) Math.floor(unit);
        if (gross <= 0L) {
            // buyPrice 地板 1% 后毛值不足 1 信用点 (理论上高价矿三种 base 在 1% 地板下单块均 >= 1 不触发; 仍按
            // FarmerWheatSellService 同纪律早返, 不裸调 grantDaily —— 其契约 rawCredit>0 否则抛 ILLEGAL_AMOUNT)。
            return 0L;
        }
        // 经主闸衰减入账并返回净额 (深档可能 < gross, 随累计毛收入推进逐档递减; 几何主项前 10 档 ≈ 14.9 万为正常落点,
        // 其后 1% 地板留极薄线性尾巴 (每 60000 毛 +600, 不收敛、靠巡查兜底); 这是收入封顶的预期语义)。
        // 铜 P2P 单人 cap (第十一章决策 5) 是 follow-up: 依赖尚不存在的跳蚤/交易层 + 铜未进 HighValueOre 枚举, 本轮不实现。
        return grantDaily(player, gross, EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY,
                EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER);
    }

    @Override
    public int recordMinedOreDrops(ServerPlayer player, Block block, int producedCount) {
        // 与 EconomySystem.onBlockBreak 单块计数共用同一玩家态与同一隐藏软上限 (方案 B 按产出物个数)。
        PlayerAbuseState state = stateResolver.apply(player.getUUID());
        int total = abuseGuard.recordMinedOreDrops(state, block, producedCount);

        // 第十一章决策 3 (连锁产出也走主闸封顶): 连锁/隧道连带产出的高价矿同样真发钱, 与单块挖矿 (onBlockBreak ->
        // settleOreSale) 共用同一衰减主闸出口, 保证"连锁清矿只是更快撞向正常落点 (几何主项 ≈ 14.9 万), 深档线性尾巴
        // 靠巡查兜底"(反通胀北极星)。total > 0 即本块是
        // 高价矿且未被 AFK 冻结 (非高价矿 / 冻结返回 -1, 自然短路); 本批 producedCount 个产出物占据当日计数
        // [total-producedCount+1, total] 区间, 逐个按其边际 countSoFar 经 settleOreSale (内部 grantDaily) 入主闸,
        // 与单块发钱口径完全一致 (一颗产出物发一次)。当前高价矿物理排除连锁 (MinerConstants.CHAIN_HARD_EXCLUDE),
        // 故 total 恒 = -1 本路径不发钱; 此接线是契约层单一出口, 防将来连锁白名单含高价矿时漏发/绕过封顶。
        if (total > 0 && abuseGuard.classify(block) != null) {
            HighValueOre ore = abuseGuard.classify(block);
            double basePrice = ShopPriceTable.oreBasePrice(ore);
            int firstCount = total - producedCount + 1;
            for (int n = firstCount; n <= total; n++) {
                settleOreSale(player, ore, n, basePrice);
            }
        }
        return total;
    }

    @Override
    public long grantDaily(ServerPlayer player, long rawCredit, String faucetKey, long dailyCap) {
        if (rawCredit <= 0L) {
            throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT,
                    "grantDaily rawCredit must be > 0, got " + rawCredit);
        }
        if (dailyCap <= 0L) {
            throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT,
                    "grantDaily dailyCap must be > 0, got " + dailyCap);
        }
        // 取本次入账前当日累计原始信用点 n0 (并把本次 rawCredit 累加进 faucet 计数器, UTC 翻日清零)。
        long today = abuseGuard.currentPlayerDayStamp();
        long before = ledger.recordFaucetGrant(player.getUUID(), faucetKey, rawCredit, today);
        // 按全服每人每日统一衰减主闸逐档积分算精确实发额 (第十一章决策 2: 0.6 衰减 / 60000 档 / 1% 地板; 几何主项前 10 档
        // ≈ 14.9 万为正常落点, 深档 1% 地板留极薄线性尾巴, 不收敛、靠巡查兜底)。
        // 取精确 double 而非 floor 版: 把小数交账本 carry 跨笔累进 (creditFaucetWithCarry), 修复深档小额逐笔 floor 归零。
        double exact = abuseGuard.faucetCreditAfterDecayExact(before, rawCredit, dailyCap);
        long effective = ledger.creditFaucetWithCarry(player.getUUID(), faucetKey, exact, today);
        if (effective > 0L) {
            // faucet 是最大货币注入口, grant 内部 Math.addExact 防溢出击穿 M0 (经济文档 7.3)。
            ledger.credit(player.getUUID(), Currency.CREDIT, effective);
        }
        return effective;
    }

    @Override
    public long grantAzureDaily(ServerPlayer player, long amount, long dailyCap) {
        if (amount <= 0L) {
            throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT,
                    "grantAzureDaily amount must be > 0, got " + amount);
        }
        if (dailyCap <= 0L) {
            throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT,
                    "grantAzureDaily dailyCap must be > 0, got " + dailyCap);
        }
        // 与信用点 faucet 共用同一 UTC epochDay 翻日时钟 (单一翻日口径); 硬截断逻辑落账本 (creditAzureDaily)。
        long today = abuseGuard.currentPlayerDayStamp();
        return ledger.creditAzureDaily(player.getUUID(), EconomyConstants.AZURE_DAILY_FAUCET_KEY,
                amount, dailyCap, today);
    }

    @Override
    public boolean isAfkFrozen(ServerPlayer player) {
        // 只读冻结态, 不触发评估 (评估由经济子系统降频 tick 主导, 见 AbuseGuard.evaluateAfk)。
        return stateResolver.apply(player.getUUID()).afkFrozen();
    }
}
