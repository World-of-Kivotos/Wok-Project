package com.miningdim.market;

import com.miningdim.freshness.FreshnessNested;
import com.miningdim.webui.server.WebUiErrorCodes;

import net.minecraft.world.item.ItemStack;

/**
 * 市场边界上的新鲜度判定 (规格「消费点改造 · 市场」)。
 *
 * 三件事收在这一处, 免得散进 place / buy / list / tradable 四处各写一套阈值:
 * <ul>
 *   <li><b>上架</b>: 物品本身与所有嵌套内容先盖章 (托管期间照常腐烂 —— 不盖章等于把市场当冰箱用),
 *       任意一层剩余不足 {@link #MIN_LIST_MINUTES} 就拒绝; 不足 {@link #WARN_LIST_MINUTES} 只提示;</li>
 *   <li><b>购买</b>: 任意一层已腐烂就拒绝 (挂单保持有效, 卖家自己撤);</li>
 *   <li><b>自动关闭</b>: 腐烂满 {@link #AUTO_CLOSE_ROTTEN_MINUTES} 的挂单由低频巡检关掉 (见
 *       {@link MarketRottenSweep})。</li>
 * </ul>
 *
 * 下钻与判定全部走腐烂底座的 {@link FreshnessNested} / {@code FreshnessCheck}: 市场这边**不碰 NBT 路径**,
 * 嵌套容器清单只在底座那一处维护 (潜影盒、篮子、FD 厨锅物品都在里面)。不腐烂的东西永远不算烂这条口径
 * 也在底座里, 这里只做市场自己的阈值判断。
 */
public final class MarketFreshness {

    private MarketFreshness() {
    }

    /** 上架最低剩余: 不足 6 小时直接拒绝 (摆上去也来不及卖)。 */
    public static final long MIN_LIST_MINUTES = 6L * 60L;

    /** 上架提示线: 不足 12 小时**允许挂**但提示「可能来不及卖出」。 */
    public static final long WARN_LIST_MINUTES = 12L * 60L;

    /** 腐烂满 30 天的挂单自动关闭 (写日志; 不做自动退回卖家, 见规格)。 */
    public static final long AUTO_CLOSE_ROTTEN_MINUTES = 30L * 24L * 60L;

    /**
     * 一件托管物的新鲜度裁决。
     *
     * @param listable    能不能挂 (不腐烂, 且每一层剩余都够 {@link #MIN_LIST_MINUTES})
     * @param rotten      有没有任意一层已经腐烂
     * @param untimed     一层易腐品都没有 (石头、工具这类: 既不腐烂也不提示, 也不占用任何阈值)
     * @param warn        能挂但**可能来不及卖出** (有易腐层, 且最少剩余不足 {@link #WARN_LIST_MINUTES})
     * @param minMinutes  所有层里最少的剩余分钟数; {@code untimed} 为真时是 {@link Long#MAX_VALUE}
     */
    public record Verdict(boolean listable, boolean rotten, boolean untimed, boolean warn, long minMinutes) {

        /**
         * 剩余不足 6 小时 (或已腐烂) 的拒绝理由码; 供前端把「为什么不能挂」说清楚。
         *
         * 直接回错误码本身 (而不是另起一套短名): market.tradable 的 reasonCode 与 place 抛出的 errorCode
         * 是同一个字符串, 前端两张表才不会各说各话。
         */
        public String reasonCode() {
            return rotten ? WebUiErrorCodes.FRESHNESS_ROTTEN : WebUiErrorCodes.FRESHNESS_TOO_SHORT;
        }
    }

    /**
     * 判一件东西 (含所有嵌套层) 能不能挂。
     *
     * 判定顺序刻意是「先烂后短」: 已经烂了的货报「已腐烂」比报「剩余不足」更贴近玩家看到的事实
     * (烂货的剩余是负数)。
     */
    public static Verdict judgeForListing(ItemStack stack, long nowMinute) {
        boolean rotten = FreshnessNested.anyRotten(stack, nowMinute);
        long minMinutes = FreshnessNested.minRemainingMinutes(stack, nowMinute);
        boolean untimed = minMinutes == Long.MAX_VALUE;
        boolean tooShort = !untimed && minMinutes < MIN_LIST_MINUTES;
        boolean warn = !untimed && !rotten && !tooShort && minMinutes < WARN_LIST_MINUTES;
        return new Verdict(!rotten && !tooShort, rotten, untimed, warn, minMinutes);
    }

    /** 一件托管物 (含嵌套层) 现在是否已经腐烂 —— 购买与浏览两侧共用同一条判据。 */
    public static boolean rotten(ItemStack escrow, long nowMinute) {
        return FreshnessNested.anyRotten(escrow, nowMinute);
    }

    /**
     * 一件托管物还要多久**开始**腐烂 (所有层里最早的那一层); 没有易腐层返回 {@link Long#MAX_VALUE}。
     * 前端把「剩余小时」显示给买家, 用的是同一个数。
     */
    public static long remainingMinutes(ItemStack escrow, long nowMinute) {
        return FreshnessNested.minRemainingMinutes(escrow, nowMinute);
    }
}
