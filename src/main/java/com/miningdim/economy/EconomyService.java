package com.miningdim.economy;

import com.miningdim.economy.EconomyConstants.HighValueOre;
import net.minecraft.server.level.ServerPlayer;

/**
 * 货币门面实现 (经济文档第九章 + 框架 spec 第三章)。所有玩家货币读写经 {@link EconomyWalletData}
 * (UUID 键 SavedData, 服务端权威), 衰减/翻日复用 {@link AbuseGuard} 的纯函数与 UTC 时钟 (单一真值, 不另起一套)。
 *
 * 由 {@link EconomySystem} 在 ServerStartedEvent 取矿山维度 ServerLevel 建账本后构造, 注入
 * {@link EconomyServices} 定位器 (job 包定位器范式; 不碰 core.MiningServices, 见类注释)。
 *
 * 异常纪律: 非法金额/入账溢出由 {@link PlayerWallet} 抛 {@link EconomyException} 自然冒泡, 本类不生吞;
 * 余额不足由 {@link #tryCharge} 返 false (事务安全)。
 *
 * 线程: 全部服务端主线程调用 (职业事件回调 / 命令 / 网络 handler 均主线程)。账本 SavedData 同主线程访问。
 */
public final class EconomyService implements IEconomyService {

    private final EconomyWalletData ledger;
    private final AbuseGuard abuseGuard;

    /**
     * @param ledger     全服账本 (矿山维度 SavedData, 由 EconomySystem 在服务端启动期取得)
     * @param abuseGuard 复用经济子系统已有的衰减纯函数 + UTC 翻日时钟 (不重复实现 0.97/0.25 与翻日口径)
     */
    public EconomyService(EconomyWalletData ledger, AbuseGuard abuseGuard) {
        if (ledger == null) {
            throw new IllegalArgumentException("EconomyWalletData ledger must not be null");
        }
        if (abuseGuard == null) {
            throw new IllegalArgumentException("AbuseGuard must not be null");
        }
        this.ledger = ledger;
        this.abuseGuard = abuseGuard;
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
    public void grant(ServerPlayer player, Currency currency, long amount) {
        ledger.credit(player.getUUID(), currency, amount);
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
        // 衰减口径复用 18.3 纯函数 (0.97^(n-cap) 至 25% 地板); 向下取整成整数信用点入账。
        double unit = abuseGuard.buyPrice(ore, countSoFar, basePrice);
        long credits = (long) Math.floor(unit);
        if (credits > 0L) {
            // 卖矿是最大 faucet, grant 内部 Math.addExact 防溢出击穿 M0 (经济文档 7.3)。
            ledger.credit(player.getUUID(), Currency.CREDIT, credits);
        }
        return credits;
    }
}
