package com.miningdim.market;

import com.miningdim.freshness.FreshnessNested;
import com.miningdim.market.store.ListingRow;
import com.miningdim.market.store.MarketDao;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 腐烂挂单的巡检 (规格「消费点改造 · 市场」): **腐烂满 30 天的挂单自动关闭并写日志**。
 *
 * 为什么不自动退回卖家: {@link MarketEngine#cancel} 要求卖家本人在线且背包放得下, 而市场模块没有物品待领机制 ——
 * 替玩家"暂存"等于凭空造出一个只有本模块认识的取货口 (婚姻模块的离婚清算待领队列是该模块私有的, 不通用)。
 * 所以这里只关单 + 写日志 + 通知**在线**的卖家; 东西在托管期间烂掉这件事本身就是「市场不是冰箱」这条规则的代价
 * (规格反漏洞 #17)。
 *
 * 巡检本身的设计约束:
 * <ul>
 *   <li>**低频** ({@link #INTERVAL_TICKS}): 这是"30 天"尺度的收尾工作, 每 tick 跑一次纯属浪费主线程;</li>
 *   <li>**每轮有上限** ({@link #MAX_ROWS_PER_SWEEP}) 且**游标轮转**: 每一行都要反序列化 NBT 并下钻嵌套容器,
 *       一次扫完全服会把主线程按住; 游标保证每一行最终都会被看到 (扫到结尾就回到开头);</li>
 *   <li>**不改表**: 自动关闭复用条件 UPDATE 的纪律, 新状态值 {@link #STATUS_EXPIRED} 与卖家自己撤单的
 *       {@code CANCELLED} 区分开 —— 一个是被系统收走的, 一个是本人撤的, 对账时要能分辨。</li>
 * </ul>
 */
public final class MarketRottenSweep {

    private MarketRottenSweep() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/market");

    /** 巡检间隔 (tick): 12000 tick = 10 分钟。 */
    public static final long INTERVAL_TICKS = 12000L;

    /** 每轮最多看多少行 (游标轮转, 一行都不会被永久跳过)。 */
    public static final int MAX_ROWS_PER_SWEEP = 200;

    /** 自动关闭的挂单状态 (与卖家自己撤单的 CANCELLED 区分)。 */
    public static final String STATUS_EXPIRED = "EXPIRED";

    /** 上一趟扫到哪了 (进程内游标; 服务端重启后从 0 重新开始, 只是多扫一遍, 无副作用)。 */
    private static int cursor;
    private static long lastSweepTick = Long.MIN_VALUE;

    /** 一条待查的挂单 (只带巡检与通知要用到的字段)。 */
    public record ListingCandidate(long id, String itemId, UUID sellerUuid, byte[] itemNbt) {

        static ListingCandidate of(ListingRow row) {
            return new ListingCandidate(row.id(), row.itemId(), row.sellerUuid(), row.itemNbt());
        }
    }

    /** 一条已腐烂的挂单: 除了 id 还带上「烂了多久」, 供自动关闭与登录通知两处各自判断。 */
    public record RottenListing(long id, String itemId, UUID sellerUuid, long rottenMinutes) {
    }

    /** 一批挂单里已腐烂的那些 (托管 NBT 读不出来的行跳过 —— 那是 ESCROW_UNRESOLVABLE 的范畴, 不是新鲜度问题)。 */
    public static List<RottenListing> rottenAmong(List<ListingCandidate> rows, long nowMinute) {
        List<RottenListing> out = new ArrayList<>();
        for (ListingCandidate row : rows) {
            ItemStack escrow = MarketEngine.deserializeStack(row.itemNbt());
            if (escrow.isEmpty()) {
                continue;
            }
            long rottenSince = FreshnessNested.rottenSinceMinute(escrow, nowMinute);
            if (rottenSince == Long.MIN_VALUE) {
                continue;
            }
            out.add(new RottenListing(row.id(), row.itemId(), row.sellerUuid(), nowMinute - rottenSince));
        }
        return out;
    }

    /** 某个卖家自己 ACTIVE 挂单里现在已腐烂的那些 (登录通知用; 只查他一个人的挂单, 不扫全服)。 */
    public static List<RottenListing> rottenOfSeller(MarketDao dao, UUID seller, long nowMinute) {
        List<ListingCandidate> mine = new ArrayList<>();
        for (ListingRow row : dao.listingsBySeller(seller, "ACTIVE")) {
            mine.add(ListingCandidate.of(row));
        }
        return rottenAmong(mine, nowMinute);
    }

    /**
     * 巡检一趟 (由 {@code MarketSubsystem} 的 tick 钩子按 {@link #INTERVAL_TICKS} 调; 用例可直接调它,
     * 不必真的等到 12000 tick)。
     *
     * @param gameTime  服务端游戏刻 (调用方传 {@code server.getTickCount()})
     * @param nowMinute 当下分钟数 (显式传入, 用例才能构造"烂了 40 天"的场景)
     * @return 这一趟关掉了几单
     */
    public static int tick(MarketDao dao, MinecraftServer server, long gameTime, long nowMinute) {
        if (dao == null) {
            return 0;
        }
        if (lastSweepTick != Long.MIN_VALUE && gameTime - lastSweepTick < INTERVAL_TICKS) {
            return 0;
        }
        lastSweepTick = gameTime;

        List<ListingCandidate> page = new ArrayList<>();
        for (ListingRow row : dao.queryActive(null, "created_at", cursor, MAX_ROWS_PER_SWEEP)) {
            page.add(ListingCandidate.of(row));
        }
        if (page.isEmpty()) {
            // 扫到结尾: 游标回到开头, 下一轮从头来 (轮转, 不永久跳过任何一行)。
            cursor = 0;
            return 0;
        }
        cursor += page.size();

        int closed = 0;
        for (RottenListing rotten : rottenAmong(page, nowMinute)) {
            if (rotten.rottenMinutes() < MarketFreshness.AUTO_CLOSE_ROTTEN_MINUTES) {
                continue;
            }
            if (!dao.markExpired(rotten.id())) {
                continue; // 已被并发买走/撤单: 不是本次要处理的行
            }
            closed++;
            LOGGER.info("[miningdim] market: auto-closed listing {} ({}) after {} minutes rotten; "
                            + "escrowed items are not returned to the seller by design (see the spoilage spec)",
                    rotten.id(), rotten.itemId(), rotten.rottenMinutes());
            ServerPlayer seller = server == null ? null : server.getPlayerList().getPlayer(rotten.sellerUuid());
            if (seller != null) {
                // 在线的卖家当场知道; 离线的只有日志 —— 市场没有待领队列, 也不该假装有。
                seller.displayClientMessage(Component.translatable(
                        "message.miningdim.market.listing_expired",
                        rotten.itemId(), rotten.rottenMinutes() / (24L * 60L)), false);
            }
        }
        return closed;
    }

    /** 仅供用例复位巡检节流与游标 (生产代码不调)。 */
    static void resetForTest() {
        cursor = 0;
        lastSweepTick = Long.MIN_VALUE;
    }
}
