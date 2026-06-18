package com.miningdim.economy;

import com.miningdim.economy.EconomyConstants.HighValueOre;
import net.minecraft.server.level.ServerPlayer;

/**
 * 货币门面接口 (JobFramework_Shared_Foundation_DesignSpec 第三章 DECIDED 接口 + 经济文档 0.3/1.1/8.1)。
 *
 * 归属 economy 包 (实现手册 "约定 4" 建议: 货币门面落 economy 包而非 core, 避免 core 反向依赖子系统类型;
 * 与 {@link Currency}/{@link EconomyException} 同包内聚)。职业子系统按接口取用, 不 import 经济实现类
 * (模块化铁律 2)。跨职业扣费一律走 {@link #tryCharge} (扣信用点余额), 不再复用 {@code AbuseGuard.chargeItem}
 * (那扣物理物品, 是语义错位; 框架 spec 第三章明确)。塔罗买卡包 / 结婚典礼 / 矿山重置 / 军火商工费均走本接口。
 *
 * 服务端权威 (经济文档 0.3-44): 余额账本是 {@link EconomyWalletData}(SavedData), 仅服务端存在。故全部玩家
 * 形参收紧为 {@link ServerPlayer} (与框架 spec keyMembers 及下游实际调用一致), 从类型上杜绝逻辑客户端侧
 * 误调用权威账本 —— 客户端也持 Player 实例, 若形参放宽到 Player 即开了客户端误用入口。
 *
 * 双货币 (经济文档 一/1.2): {@link Currency#CREDIT} 信用点 (基础货币) / {@link Currency#AZURE} 青辉石
 * (高级货币, 硬绑定不可转移)。本货币层只做"系统扣费 (sink) / 系统入账 (faucet) / 原子余额变更"。
 *
 * 反洗钱边界 (经济文档 0.3-3/0.3-45/0.3-46): 本接口刻意不提供玩家间 P2P 转移方法。信用点"不可自由
 * 赠予/丢传", 玩家间转移只能经收手续费 + 落流水审计的跳蚤/交易通道。该通道属 PostgreSQL 交易层职责
 * (经济文档 0.3-45), 内部强制收手续费 + 落流水 + 偏离校验后, 才回调本层的原子 {@link #tryCharge} /
 * {@link #grant} 做余额变更。若把 P2P 方法落在本 SavedData 余额门面上, 任何实现者都能 from 扣 to 加,
 * 零手续费零流水 —— 这正是文档要堵死的洗钱后门, 故不在此出现。
 *
 * 异常纪律: 余额不足由 {@link #tryCharge} 返 false (事务安全, 不扣); 非法金额 / 入账溢出由实现抛
 * {@link EconomyException} 自然冒泡 (业务层不生吞, 见框架 spec 第三章)。
 */
public interface IEconomyService {

    /** 某玩家信用点余额 (服务端权威)。 */
    long creditBalance(ServerPlayer player);

    /** 某玩家青辉石余额 (服务端权威)。 */
    long heartstoneBalance(ServerPlayer player);

    /**
     * 事务安全扣费 (sink): 余额足则扣并返 true, 不足则不扣返 false (框架 spec 第三章; 先校验后扣杜绝双花)。
     *
     * @param amount 扣费量 (必须 &gt; 0; 非法金额由实现抛 {@link EconomyException} Reason
     *               {@link EconomyException.Reason#ILLEGAL_AMOUNT})
     * @return 余额足扣成功返 true; 余额不足不扣返 false
     */
    boolean tryCharge(ServerPlayer player, Currency currency, long amount);

    /**
     * 系统入账 (faucet: 任务/刷怪/卖矿卖菜 / 职业产出的货币奖励)。
     *
     * 溢出契约 (经济文档 7.3 M0 货币总量统计防脏数据击穿): faucet 是最大货币注入口, long 累加无上界
     * 校验在长期运营/潜在刷钱 bug 下可能回绕为负击穿 M0 统计。故入账后余额若超 {@link Long#MAX_VALUE},
     * 实现必须用 {@link Math#addExact} 检出并抛 {@link EconomyException} Reason
     * {@link EconomyException.Reason#BALANCE_OVERFLOW} 自然冒泡 (与 {@link #tryCharge} 的 amount&lt;=0 抛
     * {@link EconomyException.Reason#ILLEGAL_AMOUNT} 同纪律), 不静默回绕。
     *
     * @param amount 入账量 (必须 &gt; 0; 非法金额抛 {@link EconomyException.Reason#ILLEGAL_AMOUNT})
     */
    void grant(ServerPlayer player, Currency currency, long amount);

    /**
     * 含每日限购计数器的事务扣费 (框架 spec 第三章): 当日经同一 dailyKey 累计扣费未超 dailyCap 且余额足才扣。
     *
     * @param dailyKey 每日限购计数键 (与职业经验软上限共用 UTC 翻日时钟, 经济文档 0.3-78)
     * @param dailyCap 当日该 key 的累计扣费上限
     * @return 扣成功返 true; 余额不足或超每日上限返 false (不扣)
     */
    boolean tryChargeDaily(ServerPlayer player, Currency currency, long amount, String dailyKey, long dailyCap);

    /**
     * 矿物收购 faucet 结算 (经济文档 1.1 最大 faucet + 8.1 ×10 锚价 + 18.3 软上限衰减): 把第 n 块高价矿按
     * {@link AbuseGuard#buyPrice} 衰减后的单价折成信用点入账 (内部 {@link #grant} CREDIT)。
     *
     * 衰减口径与 18.3 完全一致 (复用 AbuseGuard.buyPrice 纯函数, 不另起一套): countSoFar &lt;= 软上限时全价,
     * 超限按 {@code base * 0.97^(n-cap)} 衰减至 25% 地板。这是把"矿石 -&gt; 信用点"龙头真正接到货币层 (此前
     * AbuseGuard.buyPrice 无任何调用者)。
     *
     * @param player     卖矿玩家 (服务端)
     * @param ore        高价矿种 (决定软上限)
     * @param countSoFar 当日已产出该矿的累计数 n (含本块; 与 18.3 dailyOreCount 同口径)
     * @param basePrice  该矿基础收购价 (8.1 ×10 锚价: 钻石 500 / 金锭 120 / 残骸 4500)
     * @return 本块实际入账的信用点 (衰减后单价向下取整; 0 表示衰减后不足 1 信用点)
     */
    long settleOreSale(ServerPlayer player, HighValueOre ore, int countSoFar, double basePrice);
}
