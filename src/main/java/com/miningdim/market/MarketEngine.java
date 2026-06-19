package com.miningdim.market;

import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.market.store.ListingRow;
import com.miningdim.market.store.MarketDao;
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

        // 铜/铁每日 P2P 量上限 (定价台账"铜 P2P 单人 cap"): 仅对铜铁标的生效。口径 = 今日该卖家这些 item 的
        // (当前 ACTIVE 挂单 count 之和 + 今日已 SOLD count 之和), 加本次 count 超 cap 即拒挂 (从源头限量, 不在成交时事后扣)。
        if (MarketConstants.COPPER_IRON_ITEM_IDS.contains(itemId)) {
            int already = dao.soldOrListedCountToday(
                    seller.getUUID(), MarketConstants.COPPER_IRON_ITEM_IDS, startOfTodayEpochMillis());
            if (already + count > MarketConstants.COPPER_IRON_DAILY_P2P_CAP) {
                throw new IllegalStateException(
                        "今日铜/铁 P2P 挂单量已达上限 (cap=" + MarketConstants.COPPER_IRON_DAILY_P2P_CAP
                                + ", 已计=" + already + ", 本次=" + count + ")");
            }
        }

        // 挂单手续费 (偏离费, 上单即收 sink, 撤单/未售不退; 经济文档"偏离校验"通道)。V0 解析: 内置预设 -> 无锚退平率
        // (admin 覆盖层 / 市场中位数兜底见后续 commit)。先查后扣 (tryCharge 余额不足返 false -> 抛, 此时未托管未动库存)。
        OptionalLong baseValue = DefaultBaseValues.resolve(itemId);
        long listFee = MarketFee.listingFee(baseValue, unitPrice, count);
        IEconomyService economy = EconomyServices.economyService();
        if (!economy.tryCharge(seller, Currency.CREDIT, listFee)) {
            throw new IllegalStateException("信用点不足以支付挂单手续费 (需 " + listFee + ", 挂单未创建)");
        }
        // listFee 蒸发 = sink (不 grant 给任何人, 反通胀)。

        // 托管: 序列化"单位物品 ItemStack(item, count)"的 NBT (整 stack 含 NBT, 但 count 收紧为挂单量),
        // 再从卖家库存精确扣 count 个。先序列化后扣 (序列化失败则不扣, 自然冒泡, 不留物品凭空消失)。
        ItemStack escrow = stack.copy();
        escrow.setCount(count);
        byte[] nbt = serializeStack(escrow);
        stack.shrink(count);

        long listingId = dao.insertListing(seller.getUUID(), seller.getName().getString(),
                itemId, nbt, count, unitPrice, MarketConstants.CURRENCY_CREDIT, System.currentTimeMillis());
        return new PlaceResult(listingId, listFee);
    }

    // ============================================================
    // 买入 (buy): 校验 -> 容量预检 -> 扣款 -> 事务(markSold+insertTxn) -> 交付 -> 卖家结算
    // ============================================================

    /**
     * 买入 (契约第 5 节)。扣买家 total, 卖家实收全额 total (手续费已在挂单时 place 收过, 买入不再二次收费; BuyResult.fee=0)。
     * 物品交付买家, 卖家在线即时入账、离线落 pending_payout 待登录结算。
     *
     * @return 成交回执 (供 action 构 resultJson)
     */
    public BuyResult buy(ServerPlayer buyer, long listingId) {
        ListingRow row = dao.findListing(listingId);
        if (row == null || !"ACTIVE".equals(row.status())) {
            throw new IllegalStateException("挂单不存在或已售 (listingId=" + listingId + ")");
        }
        if (row.sellerUuid().equals(buyer.getUUID())) {
            throw new IllegalArgumentException("不能买自己的挂单 (listingId=" + listingId + ")");
        }

        long total = Math.multiplyExact(row.unitPrice(), (long) row.count());
        // 手续费已在挂单时 (place) 向卖家收过 (上单即收 sink); 买入不再二次收费, 卖家实收全额 total, 流水 fee 记 0。

        // 反序列化托管物品, 先做背包容量预检 (不够则抛, 此时未扣款 —— 先验空间再动钱, 杜绝扣了钱却交付不了)。
        ItemStack item = deserializeStack(row.itemNbt());
        if (!canInsert(buyer.getInventory(), item)) {
            throw new IllegalStateException("背包空间不足 (listingId=" + listingId + ")");
        }

        // 扣款 (sink 安全扣费): 余额不足返 false 即抛, 此时未扣 (tryCharge 事务安全, 无双花)。
        IEconomyService economy = EconomyServices.economyService();
        if (!economy.tryCharge(buyer, Currency.CREDIT, total)) {
            throw new IllegalStateException("余额不足 (需 " + total + ", listingId=" + listingId + ")");
        }

        // 成交 markSold + insertTxn (契约第 4/5 节)。作者 A 的 MarketDao 刻意不暴露 Connection (接口注释:
        // "业务层 (B) 只经接口操作, 不直接碰连接"), 故 B 无法在引擎里驱动同一 Connection 的 setAutoCommit/commit/rollback;
        // 二者各自走 SQLite autocommit 原子提交 (契约第 4 节 "DAO 单方法内部各自原子")。MC 服务端逻辑单线程 + 单连接单写者,
        // 二者顺序执行间无并发, 行为与显式事务等价: markSold 先做 (条件 UPDATE WHERE status=ACTIVE), 返 false = 挂单已非
        // ACTIVE -> 退款 + 抛, 此时未写 txn 行 (无脏流水); markSold 成功后再写 txn 行。
        // 偏离说明 (见 notes 报告): 真正跨方法显式事务需 A 提供原子 DAO 方法 (如 markSoldAndRecordTxn) 或暴露事务边界;
        // 当前 markSold 成功后 insertTxn 抛 (单线程下 SQL 异常极罕见) 的极小窗口会留 SOLD 行无对应 txn —— 契约第 5 节
        // "崩溃恢复 deferred" 已接受此 v1 窗口。此处不 try-catch insertTxn (让存储异常自然冒泡到 Gateway, 不吞)。
        boolean sold = dao.markSold(listingId);
        if (!sold) {
            // 已扣款但挂单非 ACTIVE (并发被抢, MC 单线程下防御性): 全额退款 (grant 回 CREDIT) 再抛 —— 钱不蒸发, 物品未交付。
            economy.grant(buyer, Currency.CREDIT, total);
            throw new IllegalStateException("挂单不存在或已售 (并发被抢, 已退款, listingId=" + listingId + ")");
        }
        dao.insertTxn(listingId, buyer.getUUID(), row.sellerUuid(), row.itemId(),
                row.count(), row.unitPrice(), total, 0L, System.currentTimeMillis());

        // 交付物品进买家库存 (容量已预检, 此处必成功; add 返回剩余应为空)。
        ItemStack delivered = item.copy();
        boolean added = buyer.getInventory().add(delivered);
        if (!added || !delivered.isEmpty()) {
            // 预检与实际插入不一致是引擎不变量被破坏 (canInsert 漏算): 落地不丢失, 但暴露为状态错。
            buyer.drop(delivered, false);
        }

        // 卖家结算: 在线即时 grant 全额 total; 离线落 pending_payout 待登录结算 (契约第 5 节)。手续费已在挂单时收过 (sink),
        // 此处卖家实收 total。崩溃原子性: charge 在 markSold 前的极小窗口 v1 接受 (崩溃恢复 deferred)。
        ServerPlayer onlineSeller = server.getPlayerList().getPlayer(row.sellerUuid());
        if (onlineSeller != null) {
            economy.grant(onlineSeller, Currency.CREDIT, total);
        } else {
            dao.insertPendingPayout(row.sellerUuid(), total,
                    MarketConstants.CURRENCY_CREDIT, System.currentTimeMillis());
        }

        return new BuyResult(row.itemId(), row.count(), total, 0L);
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
     * drainPendingPayout 用事务保证取与删原子 (契约第 4 节, A 实现), 故本法取空即落账, 不重复发放。
     */
    public void settlePendingOnLogin(ServerPlayer seller) {
        List<long[]> pending = dao.drainPendingPayout(seller.getUUID());
        if (pending.isEmpty()) {
            return;
        }
        long sum = 0L;
        for (long[] entry : pending) {
            // entry[0] = amount (契约第 4 节 drainPendingPayout 返回 [amount])。累加防溢出。
            sum = Math.addExact(sum, entry[0]);
        }
        if (sum > 0L) {
            EconomyServices.economyService().grant(seller, Currency.CREDIT, sum);
        }
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

    // ============================================================
    // 回执 record (供 action 构 resultJson, 契约第 6 节)
    // ============================================================

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
