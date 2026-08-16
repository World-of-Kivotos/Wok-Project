package com.miningdim.market;

import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.market.store.ListingRow;
import com.miningdim.market.store.MarketDao;
import com.miningdim.market.store.SoldOrListedSplit;
import com.miningdim.market.store.TxnRow;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 跳蚤市场交易引擎 (服务端纯逻辑, 共享契约第 5 节)。货币层无 P2P 入口 (反洗钱, 经济文档 0.3-46), 本引擎是经济文档
 * 0.3-45 预留的"收手续费 + 落流水 + 偏离校验的 P2P 交易通道": 强制 CREDIT 计价、托管物品、收手续费 (sink) 后才回调
 * 货币层原子接口 ({@link IEconomyService#tryCharge}/{@link IEconomyService#grant}) 做余额变更。
 *
 * 服务端权威 (架构铁律 1): 卖家/买家身份取 action 的 sender (ServerPlayer), 不信前端 uuid。坏输入/越权 (currency
 * 非 CREDIT / 槽位空 / 数量不足 / 买自己挂单 / 余额不足 / 背包满) 一律自然抛 IllegalArgumentException/IllegalStateException
 * 冒泡到 {@link com.miningdim.webui.server.WebUiServerDispatcher} 的 Gateway 边界, 引擎内不 try-catch 生吞 (CLAUDE.md C9)。
 * 例外走带稳定错误码的 {@link WebUiBusinessException}, 现有两条: 标的白名单拒绝 ({@link MarketTradeWhitelist}),
 * 因为面板要在按钮上提前把同一条规则显示出来, 需要一个前端能认的码而不是一句裸文本 (先例: CaseOpeningService);
 * 以及托管物不可解析拒绝 ({@link com.miningdim.webui.server.WebUiErrorCodes#ESCROW_UNRESOLVABLE}), 因为它是
 * "卖家可挽回" (装回所属 mod 后能撤单) 而非纯坏输入, 前端需要一个稳定码而不是裸文本来区分对待。
 *
 * 成交原子性 (契约第 4 节 + 对 A 实交付的适配): A 的 {@link MarketDao} 刻意不暴露 Connection (业务层只经接口操作),
 * 故 markSold + insertTxn 走 SQLite autocommit 各自原子 (DAO 单方法内部原子), MC 服务端单线程单写者下顺序执行无并发,
 * 行为等价于显式事务 (markSold 条件 UPDATE 失败即退款抛, 无脏流水)。真正跨方法显式事务待 A 增原子 DAO 方法 (见 notes)。
 * 引擎内无 try-catch (NBT 序列化的 IO 异常包成 UncheckedIOException 自然冒泡; SQLite 异常经 A 的 MarketStoreException 冒泡)。
 *
 * 托管模型 (契约第 3 节): 挂单即从卖家库存移出物品、序列化整 ItemStack 的 NBT 存进 listings 行 (item_nbt);
 * 撤单/未售时该行是物品唯一所在, 买入时反序列化交付买家。故引擎是物品的临时托管方, 不存在物品在挂单期既在库存又在 DB。
 *
 * 线程: 全部服务端主线程调用 (MC 服务端逻辑单线程, 单写者契合 SQLite 单连接, 契约第 2 节)。
 */
public final class MarketEngine {

    private final MarketDao dao;
    private final MinecraftServer server;
    private final BaseValueResolver baseValues;

    /**
     * @param dao    作者 A 的 SQLite DAO 实现 (持单一服务端 Connection; 契约第 4 节签名)
     * @param server 服务端实例 (用于按 UUID 解析卖家是否在线: server.getPlayerList().getPlayer(uuid))
     */
    public MarketEngine(MarketDao dao, MinecraftServer server) {
        if (dao == null) {
            throw new IllegalArgumentException("MarketDao must not be null");
        }
        if (server == null) {
            throw new IllegalArgumentException("MinecraftServer must not be null");
        }
        this.dao = dao;
        this.server = server;
        this.baseValues = new BaseValueResolver(dao);
    }

    // ============================================================
    // 挂单 (place): 校验 -> 铜铁 cap -> 托管扣库存 -> 序列化 NBT -> insertListing
    // ============================================================

    /**
     * 挂单 (契约第 5 节)。从卖家指定槽位托管 count 个物品到 DB (ACTIVE), 返回新 listing id。
     *
     * @param inventorySlot 卖家主背包槽位 (服务端读 seller.getInventory().getItem(slot), 不信前端物品快照)
     * @param count         挂单数量 (必须 &gt; 0 且 &lt;= 该 stack 现有数量)
     * @param unitPrice     单价信用点 (必须 &gt; 0)
     * @param currency      计价货币 (必须 "CREDIT"; AZURE/其它一律拒绝, 因 AZURE 不可转移, 契约第 1 节)
     * @return 新挂单 listing id
     */
    public PlaceResult place(ServerPlayer seller, int inventorySlot, int count, long unitPrice, String currency) {
        // 计价货币: 市场只允许 CREDIT (AZURE 不可转移, 货币层据 isTransferable 拒绝转移; 此处从挂单源头堵死)。
        if (!MarketConstants.CURRENCY_CREDIT.equals(currency)) {
            throw new IllegalArgumentException(
                    "market listings must be priced in CREDIT (AZURE is non-transferable), got currency=" + currency);
        }
        if (unitPrice <= 0L) {
            throw new IllegalArgumentException("unitPrice must be > 0, got " + unitPrice);
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be > 0, got " + count);
        }

        Inventory inv = seller.getInventory();
        ItemStack stack = inv.getItem(inventorySlot);
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("inventory slot " + inventorySlot + " is empty, nothing to list");
        }
        if (stack.getCount() < count) {
            throw new IllegalArgumentException(
                    "slot holds " + stack.getCount() + " items but listing requested " + count);
        }

        String itemId = itemIdOf(stack);

        // 标的白名单 (塔罗牌只有最低品质 R 可挂): 判定与 market.tradable 的只读预判共用 MarketTradeWhitelist.judge,
        // 这是"前端灰掉的按钮"与"命令行硬挂"落在同一行代码上的唯一保证。位置刻意在此 —— stack 尚含完整 NBT
        // (品质活在 NBT 里, 靠 itemId 判不出), 且手续费未扣、库存未 shrink, 拒绝时状态干净。
        MarketTradeWhitelist.Verdict verdict = MarketTradeWhitelist.judge(stack);
        if (!verdict.tradable()) {
            throw new WebUiBusinessException(WebUiErrorCodes.ITEM_NOT_TRADABLE, verdict.reason(), false,
                    Map.of("itemId", itemId, "rule", verdict.rule()));
        }

        // 铜/铁每日 P2P 量上限 (定价台账"铜 P2P 单人 cap"): 仅对铜铁标的生效。口径 = 今日该卖家这些 item 的
        // (当前 ACTIVE 挂单 count 之和 + 今日已 SOLD count 之和), 加本次 count 超 cap 即拒挂 (从源头限量, 不在成交时事后扣)。
        if (MarketConstants.COPPER_IRON_ITEM_IDS.contains(itemId)) {
            int already = copperIronUsedToday(seller.getUUID());
            if (already + count > MarketConstants.COPPER_IRON_DAILY_P2P_CAP) {
                throw new IllegalStateException(
                        "今日铜/铁 P2P 挂单量已达上限 (cap=" + MarketConstants.COPPER_IRON_DAILY_P2P_CAP
                                + ", 已计=" + already + ", 本次=" + count + ")");
            }
        }

        // 挂单手续费 (偏离费, 上单即收 sink, 撤单/未售不退; 经济文档"偏离校验"通道)。V0 解析: 内置预设 -> 无锚退平率
        // (admin 覆盖层 / 市场中位数兜底见后续 commit)。先查后扣 (tryCharge 余额不足返 false -> 抛, 此时未托管未动库存)。
        OptionalLong baseValue = baseValues.resolve(itemId);
        long listFee = MarketFee.listingFee(baseValue, unitPrice, count);
        IEconomyService economy = EconomyServices.economyService();
        if (!economy.tryCharge(seller, Currency.CREDIT, listFee)) {
            throw new IllegalStateException("信用点不足以支付挂单手续费 (需 " + listFee + ", 挂单未创建)");
        }
        // listFee 蒸发 = sink (不 grant 给任何人, 反通胀)。

        /*
         * 托管: 序列化"单位物品 ItemStack(item, count)"的 NBT (整 stack 含 NBT, 但 count 收紧为挂单量),
         * 落库成功之后才从卖家库存精确扣 count 个。
         *
         * 扣库存必须排在 insertListing 之后: 挂单期物品的唯一所在就是 listings 那一行, 先扣再落库的话,
         * insertListing 抛 (磁盘满 / 库被锁) 时物品既不在背包也不在库里 —— 连同已扣的手续费一起蒸发,
         * 而玩家手上没有任何凭据。反过来先落库后扣, 最坏情况是"库里有行、背包还剩着", 那是可查可对账的。
         *
         * 序列化仍排在最前 (它失败则什么都没发生)。手续费那一段仍有独立的窗口: tryCharge 已扣而
         * insertListing 抛时费收不回 —— 彻底消除要把扣费与落库裹进同一个跨存储事务, 属经济层改动,
         * 见 PR 描述的遗留说明。本次先把损失面从"物品+手续费"收窄到"手续费"。
         */
        ItemStack escrow = stack.copy();
        escrow.setCount(count);
        byte[] nbt = serializeStack(escrow);

        long listingId = dao.insertListing(seller.getUUID(), seller.getName().getString(),
                itemId, nbt, count, unitPrice, MarketConstants.CURRENCY_CREDIT, System.currentTimeMillis());
        stack.shrink(count);
        return new PlaceResult(listingId, listFee);
    }

    // ============================================================
    // 买入 (buy): 校验 -> 容量预检 -> 扣款 -> 事务(markSold+insertTxn) -> 交付 -> 卖家结算
    // ============================================================

    /**
     * 买入 (契约第 5 节)。支持部分购买: requestedCount &lt;= 0 买下整单剩余; &gt; 0 买 1..剩余 中指定量, 不足整单时拆分托管
     * (交付买走部分, 余量留挂单继续 ACTIVE)。扣买家 total=单价×买入量, 卖家实收全额 total (手续费已在挂单时 place 收过,
     * 买入不二次收费; BuyResult.fee=0)。卖家在线即时入账、离线落 pending_payout 待登录结算。
     *
     * @param requestedCount 买入数量; &lt;= 0 表示买下整单剩余
     * @return 成交回执 (供 action 构 resultJson; count = 实际买入量)
     */
    public BuyResult buy(ServerPlayer buyer, long listingId, int requestedCount) {
        ListingRow row = dao.findListing(listingId);
        if (row == null || !"ACTIVE".equals(row.status())) {
            throw new IllegalStateException("挂单不存在或已售 (listingId=" + listingId + ")");
        }
        if (row.sellerUuid().equals(buyer.getUUID())) {
            throw new IllegalArgumentException("不能买自己的挂单 (listingId=" + listingId + ")");
        }

        // 买入量: <=0 取整单剩余 (买全部); >0 校验落在 1..剩余。
        int buyCount = requestedCount <= 0 ? row.count() : requestedCount;
        if (buyCount < 1 || buyCount > row.count()) {
            throw new IllegalArgumentException(
                    "买入量越界 (请求 " + requestedCount + ", 挂单剩余 " + row.count() + ", listingId=" + listingId + ")");
        }

        long total = Math.multiplyExact(row.unitPrice(), (long) buyCount);
        // 手续费已在挂单时 (place) 向卖家收过 (上单即收 sink); 买入不再二次收费, 卖家实收全额 total, 流水 fee 记 0。

        // 托管物品反序列化 (含整单 count); 交付件 = 买走的 buyCount 个。先做背包容量预检 (不够则抛, 此时未扣款)。
        ItemStack escrow = deserializeStack(row.itemNbt());
        // 可解析性守卫: 位置必须在 copy()/setCount() 之前 —— 1.20.1 的 defaulted 注册表把无法解析的 id 兜成
        // minecraft:air (isEmpty() 为 true), 而空栈的 copy() 直接返回全局 ItemStack.EMPTY 单例, 之后的裸
        // setCount 会污染这个共享单例。此刻尚未扣款、未改挂单状态, 拒绝时状态干净 (同 place 白名单守卫 :112-114 的理由)。
        if (escrow.isEmpty()) {
            throw new WebUiBusinessException(WebUiErrorCodes.ESCROW_UNRESOLVABLE,
                    "该挂单托管的物品当前无法识别 (可能是所属 mod 已被卸载或物品已变更), 暂时无法购买", false,
                    Map.of("listingId", Long.toString(listingId), "itemId", row.itemId()));
        }
        ItemStack delivered = escrow.copy();
        delivered.setCount(buyCount);
        if (!canInsert(buyer.getInventory(), delivered)) {
            throw new IllegalStateException("背包空间不足 (listingId=" + listingId + ")");
        }

        // 买家扣款、挂单状态、流水、卖家收款四件事必须同生共死。合库后钱包与市场表同库同连接, 因此可以
        // 真正裹进一个事务: 崩溃只会落在"整笔没发生"或"整笔发生"上, 不会再出现"钱扣了单没成"或"单成了
        // 卖家没收到钱"。此前它们各自走 autocommit, 靠单线程顺序执行近似原子, 中途异常只能靠反向 grant 补偿。
        IEconomyService economy = EconomyServices.economyService();
        economy.inTransaction(() -> {
            // 扣款 (sink 安全扣费): 余额不足返 false 即抛, 事务回滚, 什么都没发生。
            if (!economy.tryCharge(buyer, Currency.CREDIT, total)) {
                throw new IllegalStateException("余额不足 (需 " + total + ", listingId=" + listingId + ")");
            }
            // 买下整单 -> markSold; 部分买入 -> 拆分托管 (余量 = 整单 - 买入, 同步更新 count 与 item_nbt 的 count, 留 ACTIVE)。
            // 条件 UPDATE WHERE status=ACTIVE 返 false = 已非 ACTIVE (并发被抢, 单线程下防御) -> 抛, 事务回滚连扣款一并撤销。
            boolean committed;
            if (buyCount == row.count()) {
                committed = dao.markSold(listingId);
            } else {
                ItemStack remaining = escrow.copy();
                remaining.setCount(row.count() - buyCount);
                committed = dao.reduceListing(listingId, row.count() - buyCount, serializeStack(remaining));
            }
            if (!committed) {
                throw new IllegalStateException("挂单不存在或已售 (并发被抢, listingId=" + listingId + ")");
            }
            dao.insertTxn(listingId, buyer.getUUID(), row.sellerUuid(), row.itemId(),
                    buyCount, row.unitPrice(), total, 0L, System.currentTimeMillis());
            // 卖家结算: 在线即时 grant 全额 total; 离线落 pending_payout 待登录结算 (契约第 5 节)。
            // 手续费已在挂单时收过 (sink), 此处卖家实收 total。
            ServerPlayer onlineSeller = server.getPlayerList().getPlayer(row.sellerUuid());
            if (onlineSeller != null) {
                economy.grant(onlineSeller, Currency.CREDIT, total);
            } else {
                dao.insertPendingPayout(row.sellerUuid(), total,
                        MarketConstants.CURRENCY_CREDIT, System.currentTimeMillis());
            }
            return null;
        });

        // 交付放在提交之后: 背包是第三个存储 (玩家 NBT), 无法并入本事务。放在提交前意味着事务一旦回滚, 物品
        // 已经白给; 放在提交后, 最坏情况是"钱货两清但物品没进包"这一已知缺口 (需邮箱式领取才能真正闭合)。
        // 容量已在扣款前预检, 此处必成功; add 返回剩余应为空。
        boolean added = buyer.getInventory().add(delivered);
        if (!added || !delivered.isEmpty()) {
            // 预检与实际插入不一致是引擎不变量被破坏 (canInsert 漏算): 落地不丢失, 但暴露为状态错。
            buyer.drop(delivered, false);
        }

        return new BuyResult(row.itemId(), buyCount, total, 0L);
    }

    // ============================================================
    // 撤单 (cancel): 先验空间再 markCancelled, 退回物品
    // ============================================================

    /**
     * 撤单 (契约第 5 节)。仅卖家本人可撤其 ACTIVE 挂单; 先验买家(卖家本人)背包能容纳退回物品再 markCancelled
     * (背包满则抛且不 markCancelled, 保证物品仍在 DB 托管, 不凭空消失)。
     *
     * @return 撤单回执 (item_id + count)
     */
    public CancelResult cancel(ServerPlayer seller, long listingId) {
        ListingRow row = dao.findListing(listingId);
        if (row == null || !"ACTIVE".equals(row.status())) {
            throw new IllegalStateException("挂单不存在或已售 (listingId=" + listingId + ")");
        }
        if (!row.sellerUuid().equals(seller.getUUID())) {
            throw new IllegalArgumentException("只能撤自己的挂单 (listingId=" + listingId + ")");
        }

        ItemStack item = deserializeStack(row.itemNbt());
        // 可解析性守卫 (同 buy 的理由: 位置在 canInsert/copy 之前, 此刻未 markCancelled, 状态干净)。
        // message 给卖家指明可退路径: 挂单行与托管 NBT 原样留在库里没有丢失, 装回该物品所属的 mod 后即可正常撤回;
        // 在那之前这单既买不走也撤不掉 —— 这是唯一不销毁也不凭空补偿的处置 (补偿策略未拍板, 本次不做)。
        if (item.isEmpty()) {
            throw new WebUiBusinessException(WebUiErrorCodes.ESCROW_UNRESOLVABLE,
                    "该挂单托管的物品当前无法识别 (可能是所属 mod 已被卸载或物品已变更), 挂单与托管数据原样保留, "
                            + "装回该物品所属的 mod 后即可正常撤回, 暂时无法撤单", false,
                    Map.of("listingId", Long.toString(listingId), "itemId", row.itemId()));
        }
        // 先验空间: 背包满则抛, 不 markCancelled (物品仍在 DB 托管, 待背包腾空后再撤, 不丢失)。
        if (!canInsert(seller.getInventory(), item)) {
            throw new IllegalStateException("背包空间不足无法撤单退回 (listingId=" + listingId + ")");
        }
        // 空间够: 先标 CANCELLED (WHERE status=ACTIVE AND seller 守卫), 再退回物品。
        boolean cancelled = dao.markCancelled(listingId, seller.getUUID());
        if (!cancelled) {
            // 并发被抢/状态已变 (单线程下不该发生): 防御性抛, 不退物品 (避免标失败却退了 = 物品复制)。
            throw new IllegalStateException("挂单不存在或已售 (撤单失败, listingId=" + listingId + ")");
        }
        ItemStack refund = item.copy();
        boolean added = seller.getInventory().add(refund);
        if (!added || !refund.isEmpty()) {
            seller.drop(refund, false);
        }
        return new CancelResult(row.itemId(), row.count());
    }

    // ============================================================
    // 登录结算 (settlePendingOnLogin): drain 待结信用点累加 grant
    // ============================================================

    /**
     * 卖家登录结算 (契约第 5 节)。把该卖家离线期所有待结 pending_payout 累加为一笔 grant (CREDIT)。
     *
     * 取删与入账必须同事务。此前 drainPendingPayout 自己提交了 SELECT + DELETE, 之后才 grant ——
     * 崩溃落在两步之间, 卖家的离线收入就【永久消失且记录全无】(行已物理删除, 无从追溯少了多少)。
     * 这比开箱白嫖更不可挽回, 因为白嫖至少还留着资产行。
     */
    public void settlePendingOnLogin(ServerPlayer seller) {
        IEconomyService economy = EconomyServices.economyService();
        economy.inTransaction(() -> {
            List<long[]> pending = dao.drainPendingPayout(seller.getUUID());
            if (pending.isEmpty()) {
                return null;
            }
            long sum = 0L;
            for (long[] entry : pending) {
                // entry[0] = amount (契约第 4 节 drainPendingPayout 返回 [amount])。累加防溢出。
                sum = Math.addExact(sum, entry[0]);
            }
            if (sum > 0L) {
                economy.grant(seller, Currency.CREDIT, sum);
            }
            return null;
        });
    }

    // ============================================================
    // 查询 (供 action 包装 DTO)
    // ============================================================

    /** 活跃挂单查询 (契约第 6 节 market.list)。透传 DAO 的分页/排序/过滤。 */
    public List<ListingRow> queryActive(String itemFilterOrNull, String sortKey, int offset, int limit) {
        return dao.queryActive(itemFilterOrNull, sortKey, offset, limit);
    }

    /** 某卖家的挂单 (契约第 6 节 market.mine; statusOrNull=null 取全部状态, "ACTIVE" 取在售)。 */
    public List<ListingRow> listingsBySeller(UUID seller, String statusOrNull) {
        return dao.listingsBySeller(seller, statusOrNull);
    }

    /** 该玩家参与的成交流水 (买/卖任一侧), 按时间倒序分页 (market.history)。 */
    public List<TxnRow> transactionsByPlayer(UUID player, int offset, int limit) {
        return dao.transactionsByPlayer(player, offset, limit);
    }

    /** 该玩家参与的成交流水总条数 (market.history 的 total, 供前端算总页数)。 */
    public int transactionsCountByPlayer(UUID player) {
        return dao.transactionsCountByPlayer(player);
    }

    /**
     * 今日该卖家已占用的铜铁 P2P 额度 (当前 ACTIVE 挂单量 + 今日已售出量)。
     *
     * {@link #place} 的 cap 判定与 market.p2pCap 的面板展示物理共用本方法。分成两处各写一遍的后果是面板显示
     * "还剩 300"而挂单被拒 —— 两边只要有一处漏跟了窗口口径 (今日起点/统计范围) 就会错位, 而这种错位在
     * 玩家眼里是"系统在骗人"。
     */
    public int copperIronUsedToday(UUID seller) {
        return copperIronUsageToday(seller).total();
    }

    /**
     * 同 {@link #copperIronUsedToday} 的额度占用, 但分成"在挂中 (撤单前不释放)"与"今日已成交 (次日零点归零)"两段。
     *
     * 面板需要这个拆分才能给出成立的承诺: {@link #startOfTomorrowEpochMillis} 只让成交那一段翻篇, ACTIVE 挂单
     * 那一段不看 created_at, 到点一件不掉。拿总量去配"明天 00:00 重置"的文案, 对挂着货不撤单的玩家就是假话。
     */
    public SoldOrListedSplit copperIronUsageToday(UUID seller) {
        return dao.soldOrListedSplitToday(
                seller, MarketConstants.COPPER_IRON_ITEM_IDS, startOfTodayEpochMillis());
    }

    /**
     * 该卖家的待结货款合计 (只读 peek, 不发放不清空)。累加用 {@link Math#addExact}, 与
     * {@link #settlePendingOnLogin} 的求和口径逐字一致 —— 面板显示的数与登录时真正到账的数必须是同一个算法。
     */
    public long pendingPayoutTotal(UUID seller) {
        long sum = 0L;
        for (long[] entry : dao.peekPendingPayout(seller)) {
            sum = Math.addExact(sum, entry[0]);
        }
        return sum;
    }

    /** 该卖家的待结条目数 (只读 peek), 供面板显示"共 N 笔离线成交"。 */
    public int pendingPayoutCount(UUID seller) {
        return dao.peekPendingPayout(seller).size();
    }

    // ============================================================
    // 基准价值 V0 admin curate (OP 门控在 action 层; 引擎只做 dao 读写与解析)
    // ============================================================

    /** 写入 admin 手写 V0 覆盖 (覆盖优先于代码预设; v0 须 &gt;= MIN_ANCHOR_VALUE)。 */
    public void setBaseValueOverride(String itemId, long v0, UUID by) {
        if (itemId == null || itemId.isEmpty()) {
            throw new IllegalArgumentException("itemId must not be empty");
        }
        if (v0 < MarketConstants.MIN_ANCHOR_VALUE) {
            throw new IllegalArgumentException(
                    "base value V0 must be >= " + MarketConstants.MIN_ANCHOR_VALUE + ", got " + v0);
        }
        dao.upsertBaseValue(itemId, v0, by.toString(), System.currentTimeMillis());
    }

    /** 解析某物品当前生效的 V0 (admin 覆盖 &gt; 代码预设 &gt; 空), 供 admin 面板展示当前锚。 */
    public OptionalLong resolveBaseValue(String itemId) {
        return baseValues.resolve(itemId);
    }

    /**
     * 一次取回"当前生效 V0 + 它出自哪一层" (market.baseValue 的展示与 market.feePreview 的算费共用本方法)。
     *
     * 值只认 {@link BaseValueResolver#resolve} 这一份分层实现 —— 此前 feePreview 与 baseValue 各自手抄了一遍
     * "先查 admin 覆盖再查代码预设", 于是 {@link BaseValueResolver} 类注释里写明待加的第三层 (市场成交中位数)
     * 一落地, {@link #place} 立刻吃新锚而预览还在旧两层里打转: 玩家看"预计 800"、实扣 3400, 而这笔费上单即收、
     * 撤单不退。第二次 {@code dao.getBaseValue} 点查只用来给 source 贴标签, 不参与取值。
     *
     * 加第三层时本方法是唯一要改的地方: 给它一个新的 source 标签即可, 两个 action 自动跟上。
     */
    public BaseValueLookup lookupBaseValue(String itemId) {
        OptionalLong v0 = baseValues.resolve(itemId);
        if (v0.isEmpty()) {
            return new BaseValueLookup(v0, "none");
        }
        return new BaseValueLookup(v0, dao.getBaseValue(itemId) != null ? "override" : "preset");
    }

    /** 全部 admin 覆盖 (item_id -&gt; v0), 供 admin 面板批量标注哪些已 curate。 */
    public java.util.Map<String, Long> baseValueOverrides() {
        return dao.allBaseValues();
    }

    // ============================================================
    // ItemStack NBT 序列化 (契约第 5 节: ItemStack.save -> NbtIo.write/read -> ItemStack.of)
    // ============================================================

    /**
     * 序列化整 ItemStack (含 count 与 NBT) 为字节 (契约第 5 节)。1.20.1: ItemStack.save(CompoundTag) 写出含
     * id/Count/tag 的复合标签, 再经 NbtIo.write 写进 DataOutputStream over ByteArrayOutputStream 得 byte[]。
     * IO 异常在内存流上实际不发生 (无磁盘), 包成 UncheckedIOException 自然冒泡 (不吞, 资源边界外不静默)。
     */
    static byte[] serializeStack(ItemStack stack) {
        CompoundTag tag = new CompoundTag();
        stack.save(tag);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(bos)) {
            NbtIo.write(tag, dos);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to serialize ItemStack NBT for market escrow", e);
        }
        return bos.toByteArray();
    }

    /**
     * 反序列化字节为 ItemStack (契约第 5 节)。NbtIo.read 还原复合标签, ItemStack.of(tag) 还原物品 (含 count/NBT)。
     * 1.20.1 的 NbtIo.read(DataInputStream) 内部限深 (NbtAccounter); 此处用无界 read 还原我方写入的标签 (来源可信, 我方序列化)。
     */
    static ItemStack deserializeStack(byte[] bytes) {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
            CompoundTag tag = NbtIo.read(dis, NbtAccounter.UNLIMITED);
            return ItemStack.of(tag);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to deserialize ItemStack NBT from market escrow", e);
        }
    }

    /** 物品的注册 id 字符串 (如 minecraft:iron_ingot), 供 listings.item_id 与铜铁集合匹配。 */
    static String itemIdOf(ItemStack stack) {
        return net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
    }

    // ============================================================
    // 背包容量预检 (买入/撤单交付前模拟, 不够不动钱不标状态)
    // ============================================================

    /**
     * 模拟把 stack 全量插入背包是否可行 (不修改背包)。遍历主背包 36 槽: 先累加可叠加进现有同类未满 stack 的容量,
     * 再加空槽 * maxStackSize 容量; 总可纳容量 >= 待插入数量即返 true。
     *
     * 仅模拟主背包 (Inventory.items 的 36 槽, 与 add 的落点一致); 不含护甲/副手 (那些不接受任意物品堆叠)。
     */
    static boolean canInsert(Inventory inv, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        int needed = stack.getCount();
        int maxStack = Math.min(inv.getMaxStackSize(), stack.getMaxStackSize());
        long capacity = 0L;
        List<ItemStack> main = inv.items;
        for (ItemStack slot : main) {
            if (slot.isEmpty()) {
                capacity += maxStack;
            } else if (ItemStack.isSameItemSameTags(slot, stack) && slot.getCount() < maxStack) {
                capacity += (maxStack - slot.getCount());
            }
            if (capacity >= needed) {
                return true;
            }
        }
        return capacity >= needed;
    }

    // ============================================================
    // 今日起点 (铜铁日 cap 的当日窗口起算, 服务端本地日界)
    // ============================================================

    /**
     * 今日 0 点的 epoch 毫秒 (铜铁日 cap 的当日窗口起点)。用系统默认时区的当日零点 —— 与 listings/transactions 的
     * created_at (System.currentTimeMillis) 同量纲, DAO 的 soldOrListedCountToday 以 created_at >= 本值过滤当日。
     */
    static long startOfTodayEpochMillis() {
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        return java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /**
     * 明日 0 点的 epoch 毫秒 (铜铁日 cap 的窗口右端, market.p2pCap 的额度重置时刻)。
     *
     * 刻意不写 startOfTodayEpochMillis() + 86400000: 夏令时切换那天的一天不是 24 小时, 加常数会让重置时刻
     * 偏一小时, 而本值必须与 {@link #startOfTodayEpochMillis} 的下一次取值严格对齐 (面板倒计时归零的那一刻,
     * 正是 cap 判定换窗口的那一刻)。
     */
    static long startOfTomorrowEpochMillis() {
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        return java.time.LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    // ============================================================
    // 回执 record (供 action 构 resultJson, 契约第 6 节)
    // ============================================================

    /**
     * 基准价 V0 的一次解析结果 (值 + 命中层), 见 {@link #lookupBaseValue}。
     *
     * @param v0     当前生效的基准价; 无锚时 {@link OptionalLong#empty()} (挂单走平率费)
     * @param source 命中层的稳定标签, 前端 BaseValueResp 按它分支: {@code "override"} = admin curate 强锚,
     *               {@code "preset"} = 代码内置预设, {@code "none"} = 无锚
     */
    public record BaseValueLookup(OptionalLong v0, String source) {
    }

    /** 挂单回执 (新挂单 id + 上单即收的挂单手续费 listFee, 供 action 回前端展示"已付手续费")。 */
    public record PlaceResult(long listingId, long listFee) {
    }

    /** 买入成交回执 (契约第 6 节 market.buy: itemId/count/total/fee; fee 现恒 0, 费已挪到挂单时收)。 */
    public record BuyResult(String itemId, int count, long total, long fee) {
    }

    /** 撤单回执 (契约第 6 节 market.cancel: itemId/count)。 */
    public record CancelResult(String itemId, int count) {
    }
}
