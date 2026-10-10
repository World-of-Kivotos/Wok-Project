package com.miningdim.job.munitions;

import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyServices;
import com.miningdim.job.munitions.block.MunitionsBenchBlock;
import com.miningdim.job.munitions.gunsmith.GunsmithAssemblyRecipe;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprint;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprintItem;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithTaczBridge;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 军火商系统采购 (Munitions_Job_DesignSpec 6.4): 按军火商职业等级解锁, 用信用点向系统购买军火台六档、枪匠冲压机、
 * 枪匠装配台与枪匠图纸。修掉 TaCZ 配方旁路之后这是生存玩家拿到这四类物品的唯一途径 (此前它们只在创造页签)。
 *
 * <h2>等级门从哪来</h2>
 * 不新开配置键, 一律由既有配置推导, 免得两处各写一份后漂移:
 * <ul>
 *   <li>军火台: 按 {@link MunitionsBenchBlock#effectiveLevelFor} 的语义 —— 单台有效等级 = min(台主等级, 台档上限),
 *       所以一档台只有在玩家等级已经超过"低一档台的上限"时才比低一档多出产能。采购等级 = 低一档台的有效等级上限 + 1
 *       (最低一档从 L1 起)。默认六档推出来是 中级 L1 / 高级 L5 / 极品 L7 / 超凡 L9 / 闪耀 L10 / 旧全档台 L10。
 *       中级台 (上限 L4) 因此成了 L1 的入门台: M-5 把旧注册名恢复成全档之后, 已经没有上限 L2 的低级台了, 若仍按
 *       方块上声明的 unlockLevel (旧全档台 = 1) 卖, L1 就能买到一台陪玩家升到 L10 的台子, 后面五档全成摆设;</li>
 *   <li>冲压机: 品质解锁等级里最低的那一档 (能冲出至少一种零件的等级);</li>
 *   <li>装配台: min(装配解锁等级, 维修解锁等级) —— 装配台同时是维修入口, 维修按设计比装配早一级开放 (十章第 9 条),
 *       只按装配等级卖会让 L4 的修枪服务没有自己的台子可用;</li>
 *   <li>图纸: max(装配解锁等级, 图纸弹药口径的解锁等级) —— 装配不了或造不出这发弹时买图纸没有意义。</li>
 * </ul>
 *
 * <h2>拥有数上限</h2>
 * 军火台的拥有数 = {@link MunitionsSavedData} 的已放置计数 (与放置门控 {@code MunitionsSystem.onBenchPlace} 同一份) +
 * 背包 (主背包与副手) 里还没放下的军火台物品数, 达到 {@link MunitionsLevels#tableCount} 即拒绝。买不能绕过台数上限:
 * 放置门控本就会拦住超额的那一台; 这里再把背包里的算进去, 是为了不让玩家花钱买一台注定放不下的台子。放进箱子里的
 * 台子数不到 —— 那一台照样过不了放置门控, 这里只是少拦一次白花钱。
 *
 * <h2>扣费与发货</h2>
 * 先做全部校验 (含背包空位), 再经 {@code IEconomyService.tryCharge} 扣信用点 (销毁型 sink), 最后发货。背包满时
 * <b>拒绝</b>而不是掉在脚下: 平板购买时玩家可能正站在岩浆边或矿洞深处, 一张三十多万的图纸掉在脚下丢了没人能赔。
 * 发货失败 (理论上不会发生: 空位在同一主线程帧里刚查过) 一律原额退回并记 error 日志。
 *
 * <h2>防重入</h2>
 * 每次购买由客户端带一个 purchaseId (UUID)。同一玩家同一 purchaseId 只成交一次: 重复到达的请求原样回放第一次的回执
 * (replayed=true), 既不扣费也不发货 —— 前端在超时后重试时沿用同一个 id, 于是"第一次其实成功了"的情形不会二次扣费。
 * 派发器按 requestId 的去重挡不住这种重试 (重试是新的 requestId)。回执只记成功的那些, 每人保留最近
 * {@value #RECEIPTS_PER_PLAYER} 条, 进程内存, 不落盘 —— 它要防的是几秒内的重试, 不是跨重启的账务。
 *
 * <h2>图纸能否重复购买</h2>
 * 装配只消耗部件不消耗图纸 ({@code GunsmithAssemblyBenchBlockEntity.tryStartAssembly} 只 extract 部件槽), 一张图纸
 * 可以一直用。所以背包里已有同一张图纸 (含旧 M4 装配模板) 时拒绝再买, 防手滑重复花钱; 背包里没有 (弄丢了、放进
 * 箱子了) 时照常可买 —— 系统没有全局持有记录, 也不该让弄丢图纸的玩家永远买不回来。
 *
 * 线程: 全部在服务端主线程 (WebUI 派发器经 enqueueWork 切回主线程)。
 */
public final class MunitionsShop {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/munitions_shop");

    /** 每名玩家保留的成功回执条数 (防重入回放窗口)。 */
    static final int RECEIPTS_PER_PLAYER = 16;

    /** 图纸条目 id 的前缀 (后接 {@link GunsmithBlueprint#templateId()}), 与台子的注册名空间隔开。 */
    public static final String BLUEPRINT_ENTRY_PREFIX = "blueprint/";

    private static final List<Entry> ENTRIES = buildEntries();

    private static final ReceiptBook RECEIPTS = new ReceiptBook();

    private static GunPackProbe gunPackProbe = MunitionsShop::gunIndexedInTacz;

    private static Delivery delivery = MunitionsShop::deliverToInventory;

    private MunitionsShop() {
    }

    // ============================================================
    // 目录
    // ============================================================

    /** 目录顺序恒定: 军火台按采购等级从低到高 (旧全档台垫底), 然后冲压机、装配台, 最后是图纸 (枚举声明序)。 */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static Optional<Entry> find(String entryId) {
        Objects.requireNonNull(entryId, "entryId");
        for (Entry entry : ENTRIES) {
            if (entry.id().equals(entryId)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    private static List<Entry> buildEntries() {
        List<Entry> entries = new ArrayList<>();
        entries.add(bench(ModMunitionsBlocks.MUNITIONS_BENCH_MEDIUM, ModMunitionsItems.MUNITIONS_BENCH_MEDIUM_ITEM,
                MunitionsConfig.SHOP_BENCH_MEDIUM_PRICE));
        entries.add(bench(ModMunitionsBlocks.MUNITIONS_BENCH_HIGH, ModMunitionsItems.MUNITIONS_BENCH_HIGH_ITEM,
                MunitionsConfig.SHOP_BENCH_HIGH_PRICE));
        entries.add(bench(ModMunitionsBlocks.MUNITIONS_BENCH_SUPERIOR, ModMunitionsItems.MUNITIONS_BENCH_SUPERIOR_ITEM,
                MunitionsConfig.SHOP_BENCH_SUPERIOR_PRICE));
        entries.add(bench(ModMunitionsBlocks.MUNITIONS_BENCH_TRANSCENDENT,
                ModMunitionsItems.MUNITIONS_BENCH_TRANSCENDENT_ITEM, MunitionsConfig.SHOP_BENCH_TRANSCENDENT_PRICE));
        entries.add(bench(ModMunitionsBlocks.MUNITIONS_BENCH_RADIANT, ModMunitionsItems.MUNITIONS_BENCH_RADIANT_ITEM,
                MunitionsConfig.SHOP_BENCH_RADIANT_PRICE));
        entries.add(bench(ModMunitionsBlocks.MUNITIONS_BENCH, ModMunitionsItems.MUNITIONS_BENCH_ITEM,
                MunitionsConfig.SHOP_BENCH_LEGACY_PRICE));
        entries.add(new Entry(ModMunitionsItems.GUNSMITH_PRESS_ITEM.getId().getPath(), Kind.PRESS, null,
                ModMunitionsItems.GUNSMITH_PRESS_ITEM, null, MunitionsConfig.SHOP_GUNSMITH_PRESS_PRICE));
        entries.add(new Entry(ModMunitionsItems.GUNSMITH_ASSEMBLY_BENCH_ITEM.getId().getPath(), Kind.ASSEMBLY, null,
                ModMunitionsItems.GUNSMITH_ASSEMBLY_BENCH_ITEM, null, MunitionsConfig.SHOP_GUNSMITH_ASSEMBLY_BENCH_PRICE));
        for (GunsmithBlueprint blueprint : GunsmithBlueprint.values()) {
            entries.add(new Entry(BLUEPRINT_ENTRY_PREFIX + blueprint.templateId(), Kind.BLUEPRINT, null,
                    ModMunitionsItems.GUNSMITH_BLUEPRINT, blueprint, MunitionsConfig.shopBlueprintPrice(blueprint)));
        }
        return List.copyOf(entries);
    }

    private static Entry bench(RegistryObject<Block> block, RegistryObject<Item> item,
                               ForgeConfigSpec.IntValue price) {
        return new Entry(block.getId().getPath(), Kind.BENCH, block, item, null, price);
    }

    // ============================================================
    // 等级门
    // ============================================================

    /**
     * 军火台一档的采购等级 = 有效等级上限比它低的各档里最高的那个上限 + 1 (没有更低档时为 L1)。
     * 与台上声明的 unlockLevel 的差异及理由见类注释。
     */
    static int benchPurchaseLevel(MunitionsBenchBlock target) {
        int ceilingBelow = 0;
        for (RegistryObject<Block> candidate : ModMunitionsBlocks.ALL_BENCHES) {
            if (candidate.get() instanceof MunitionsBenchBlock other
                    && other.maxEffectiveLevel() < target.maxEffectiveLevel()) {
                ceilingBelow = Math.max(ceilingBelow, other.maxEffectiveLevel());
            }
        }
        return MunitionsLevels.clampLevel(ceilingBelow + 1);
    }

    /** 冲压机: 品质解锁等级的最低档 (能冲出至少一种零件的等级)。 */
    static int pressPurchaseLevel() {
        int lowest = MunitionsLevels.MAX_LEVEL;
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            lowest = Math.min(lowest, MunitionsLevels.partQualityUnlockLevel(quality));
        }
        return MunitionsLevels.clampLevel(lowest);
    }

    /** 装配台: 装配与维修两条产线共用这台机器, 取两者解锁等级的较低者。 */
    static int assemblyPurchaseLevel() {
        return MunitionsLevels.clampLevel(Math.min(MunitionsConfig.ASSEMBLY_UNLOCK_LEVEL.get(),
                MunitionsConfig.REPAIR_UNLOCK_LEVEL.get()));
    }

    /** 图纸: max(装配解锁等级, 该图纸弹药口径的解锁等级)。 */
    static int blueprintPurchaseLevel(GunsmithBlueprint blueprint) {
        return MunitionsLevels.clampLevel(Math.max(MunitionsConfig.ASSEMBLY_UNLOCK_LEVEL.get(),
                blueprint.ammoCaliber().unlockLevel()));
    }

    // ============================================================
    // 判定 (目录与购买共用同一个函数, 灰按钮与拒绝理由不会说两套话)
    // ============================================================

    /**
     * 这一条现在能不能买; 不能买时给出第一条拦住它的原因。判定顺序即前端展示的优先级:
     * 不可售 &gt; 等级 &gt; 台数上限 / 已持有 &gt; 经济未就绪 &gt; 余额 &gt; 背包空位。
     */
    public static Verdict evaluate(ServerPlayer player, Entry entry, Snapshot snapshot) {
        Unavailable unavailable = unavailability(entry, snapshot);
        if (unavailable != null) {
            return new Verdict(Reason.UNAVAILABLE, unavailable);
        }
        if (snapshot.level() < entry.requiredLevel()) {
            return Verdict.of(Reason.LEVEL_LOCKED);
        }
        if (entry.kind() == Kind.BENCH && snapshot.benchesOwned() >= snapshot.benchCap()) {
            return Verdict.of(Reason.CAP_REACHED);
        }
        GunsmithBlueprint blueprint = entry.blueprint();
        if (blueprint != null && holdsBlueprint(player, blueprint)) {
            return Verdict.of(Reason.ALREADY_OWNED);
        }
        long price = entry.price();
        if (price > 0L && !snapshot.economyOnline()) {
            return Verdict.of(Reason.ECONOMY_OFFLINE);
        }
        if (price > 0L && snapshot.balance() < price) {
            return Verdict.of(Reason.INSUFFICIENT_FUNDS);
        }
        if (findSlot(player.getInventory(), entry.createStack()) < 0) {
            return Verdict.of(Reason.INVENTORY_FULL);
        }
        return Verdict.PURCHASABLE;
    }

    /**
     * 与玩家无关的"系统此刻卖不卖": 枪匠链总开关关着时冲压机/装配台/图纸不卖 (买到手也用不了);
     * 图纸所绑的枪不在 TaCZ 索引里 (缺第三方枪包) 时该图纸不卖。军火台不受枪匠开关影响。
     */
    @Nullable
    public static Unavailable unavailability(Entry entry, Snapshot snapshot) {
        if (entry.kind() != Kind.BENCH && !snapshot.gunsmithEnabled()) {
            return Unavailable.GUNSMITH_DISABLED;
        }
        GunsmithBlueprint blueprint = entry.blueprint();
        if (blueprint != null && !gunPackProbe.isGunAvailable(blueprint.gunId())) {
            return Unavailable.GUN_PACK_MISSING;
        }
        return null;
    }

    /** 余额够不够 (免费条目恒够; 经济未就绪时收费条目恒不够)。 */
    public static boolean affordable(Entry entry, Snapshot snapshot) {
        long price = entry.price();
        return price <= 0L || (snapshot.economyOnline() && snapshot.balance() >= price);
    }

    /** 主背包或副手里是否已有这张图纸 (旧 M4 装配模板按 M4A1 图纸算)。 */
    public static boolean holdsBlueprint(ServerPlayer player, GunsmithBlueprint blueprint) {
        Inventory inventory = player.getInventory();
        return containsBlueprint(inventory.items, blueprint) || containsBlueprint(inventory.offhand, blueprint);
    }

    private static boolean containsBlueprint(List<ItemStack> slots, GunsmithBlueprint blueprint) {
        for (ItemStack stack : slots) {
            // isBlueprint 对 NBT 损坏的图纸回 false (不抛), 这种图纸本来也上不了装配台, 不算持有。
            if (GunsmithAssemblyRecipe.isBlueprint(stack) && GunsmithAssemblyRecipe.blueprint(stack) == blueprint) {
                return true;
            }
        }
        return false;
    }

    /** 主背包与副手里未放置的军火台物品数 (六档合计, 含旧全档台)。 */
    static int countHeldBenches(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        return countBenches(inventory.items) + countBenches(inventory.offhand);
    }

    private static int countBenches(List<ItemStack> slots) {
        int total = 0;
        for (ItemStack stack : slots) {
            if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof MunitionsBenchBlock) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 主背包里能放下这件货的格子: 先找能合并的同物品同 NBT 堆, 再找空格; 放不下返回 -1。
     * 只看主背包 36 格, 不往副手或护甲位塞 —— 与原版拾取的落点一致。
     */
    static int findSlot(Inventory inventory, ItemStack goods) {
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack existing = inventory.items.get(slot);
            if (!existing.isEmpty() && ItemStack.isSameItemSameTags(existing, goods)
                    && existing.getCount() + goods.getCount()
                    <= Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize())) {
                return slot;
            }
        }
        return inventory.getFreeSlot();
    }

    // ============================================================
    // 购买
    // ============================================================

    /**
     * 买一件。判定与 {@link #evaluate} 同一个函数; 通过后扣费、发货、记回执。
     *
     * @param purchaseId 客户端给的幂等键: 同一玩家同一 id 只成交一次, 重复到达回放第一次的回执
     */
    public static Purchase purchase(ServerPlayer player, Entry entry, UUID purchaseId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(purchaseId, "purchaseId");

        Receipt previous = RECEIPTS.find(player.getUUID(), purchaseId);
        if (previous != null) {
            // 同一个 id 换了条目再来: 不是重试而是客户端乱用幂等键, 拒绝而不是按旧回执糊过去。
            return previous.entryId().equals(entry.id())
                    ? Purchase.replayed(previous)
                    : Purchase.conflict(previous);
        }

        Snapshot snapshot = Snapshot.of(player);
        Verdict verdict = evaluate(player, entry, snapshot);
        if (!verdict.purchasable()) {
            return Purchase.rejected(verdict, snapshot);
        }

        long price = entry.price();
        // 余额在 evaluate 里查过, 但扣费本身才是权威: tryCharge 先校验后扣, 余额不足回 false 且一分不动。
        if (price > 0L && !EconomyServices.economyService().tryCharge(player, Currency.CREDIT, price)) {
            return Purchase.rejected(Verdict.of(Reason.INSUFFICIENT_FUNDS), Snapshot.of(player));
        }

        ItemStack goods = entry.createStack();
        boolean delivered;
        try {
            delivered = delivery.deliver(player, goods.copy());
        } catch (RuntimeException failure) {
            refund(player, entry, price, purchaseId, failure);
            throw failure;
        }
        if (!delivered) {
            refund(player, entry, price, purchaseId, null);
            return Purchase.rejected(Verdict.of(Reason.INVENTORY_FULL), Snapshot.of(player));
        }

        Long balanceAfter = EconomyServices.isRegistered()
                ? EconomyServices.economyService().creditBalance(player)
                : null;
        Receipt receipt = new Receipt(purchaseId, entry.id(), price, balanceAfter);
        RECEIPTS.remember(player.getUUID(), receipt);
        // 系统采购是信用点 sink 的一个出口: 全服没有按用途分账的 sink 表, 这一行日志就是它的账目 (前缀稳定, 可 grep 汇总)。
        LOGGER.info("[munitions-shop] sink {} CP: {} ({}) bought {} at munitions L{}, purchaseId {}, balance after {}",
                price, player.getGameProfile().getName(), player.getUUID(), entry.id(), snapshot.level(), purchaseId,
                balanceAfter == null ? "n/a" : balanceAfter);
        return Purchase.completed(receipt);
    }

    /**
     * 发货失败的原额退款。退款走 grant 而不是 grantDaily: 这不是 faucet 收入, 不能占玩家当日的主闸额度。
     * 退款自身失败 (理论上只有余额溢出) 会自然冒泡, 日志里已有扣费那一笔可供人工对账。
     */
    private static void refund(ServerPlayer player, Entry entry, long price, UUID purchaseId,
                               @Nullable Throwable cause) {
        if (price > 0L) {
            EconomyServices.economyService().grant(player, Currency.CREDIT, price);
        }
        LOGGER.error("[munitions-shop] delivery of {} to {} ({}) failed after charging {} CP; refunded in full,"
                        + " purchaseId {}", entry.id(), player.getGameProfile().getName(), player.getUUID(), price,
                purchaseId, cause);
    }

    /** 默认发货: 放进 {@link #findSlot} 给出的格子; 放不下返回 false (调用方退款)。 */
    private static boolean deliverToInventory(ServerPlayer player, ItemStack goods) {
        Inventory inventory = player.getInventory();
        int slot = findSlot(inventory, goods);
        if (slot < 0) {
            return false;
        }
        ItemStack existing = inventory.items.get(slot);
        if (existing.isEmpty()) {
            inventory.setItem(slot, goods);
        } else {
            existing.grow(goods.getCount());
        }
        inventory.setChanged();
        return true;
    }

    // ============================================================
    // 缺第三方枪包的探测
    // ============================================================

    /**
     * 默认探测: TaCZ 已加载且枪械索引里有这把枪。TaCZ 未加载 (dev/GameTest) 时直接回 false, 不触碰
     * {@link GunsmithTaczBridge} 里引用 com.tacz.* 的方法体 (compileOnly 铁律)。
     */
    private static boolean gunIndexedInTacz(ResourceLocation gunId) {
        return MunitionsAmmoFactory.isTaczLoaded() && GunsmithTaczBridge.isGunIndexed(gunId);
    }

    /** GameTest 专用: 换掉枪包探测 (dev 不加载 TaCZ, 默认探测恒 false, 图纸一张都买不了)。 */
    static void overrideGunPackProbeForTest(GunPackProbe probe) {
        gunPackProbe = Objects.requireNonNull(probe, "probe");
    }

    static void resetGunPackProbeForTest() {
        gunPackProbe = MunitionsShop::gunIndexedInTacz;
    }

    /** GameTest 专用: 换掉发货 (用来造"扣费之后发货失败"这条正常路径到不了的分支)。 */
    static void overrideDeliveryForTest(Delivery override) {
        delivery = Objects.requireNonNull(override, "override");
    }

    static void resetDeliveryForTest() {
        delivery = MunitionsShop::deliverToInventory;
    }

    @FunctionalInterface
    interface GunPackProbe {
        boolean isGunAvailable(ResourceLocation gunId);
    }

    @FunctionalInterface
    interface Delivery {
        boolean deliver(ServerPlayer player, ItemStack goods);
    }

    // ============================================================
    // 值类型
    // ============================================================

    public enum Kind {
        BENCH("bench"),
        PRESS("press"),
        ASSEMBLY("assembly"),
        BLUEPRINT("blueprint");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        /** 对外稳定 id (前端按它分组)。 */
        public String id() {
            return id;
        }
    }

    /** 买不了的原因 (判定顺序见 {@link #evaluate})。WebUI 层把它映射成稳定错误码。 */
    public enum Reason {
        UNAVAILABLE,
        LEVEL_LOCKED,
        CAP_REACHED,
        ALREADY_OWNED,
        ECONOMY_OFFLINE,
        INSUFFICIENT_FUNDS,
        INVENTORY_FULL
    }

    /** 系统不卖的细分原因。 */
    public enum Unavailable {
        GUNSMITH_DISABLED("gunsmith_disabled"),
        GUN_PACK_MISSING("gun_pack_missing");

        private final String id;

        Unavailable(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    /** 判定结果; reason 为 null 即可买。unavailable 只在 reason=UNAVAILABLE 时非 null。 */
    public record Verdict(@Nullable Reason reason, @Nullable Unavailable unavailable) {

        static final Verdict PURCHASABLE = new Verdict(null, null);

        static Verdict of(Reason reason) {
            return new Verdict(reason, null);
        }

        public boolean purchasable() {
            return reason == null;
        }
    }

    /**
     * 一名玩家此刻与采购相关的全部状态, 一次取齐: 目录要对二十多条逐条判定, 每条各查一遍存档与钱包是白费。
     *
     * @param balance 信用点余额; economyOnline=false 时恒 0 且不可信
     */
    public record Snapshot(int level, int benchCap, int benchesPlaced, int benchesHeld,
                           boolean economyOnline, long balance, boolean gunsmithEnabled) {

        public static Snapshot of(ServerPlayer player) {
            int level = MunitionsLevels.munitionsLevel(player);
            boolean economyOnline = EconomyServices.isRegistered();
            return new Snapshot(
                    level,
                    MunitionsLevels.tableCount(level),
                    MunitionsSavedData.get(player.server.overworld()).benchCount(player.getUUID()),
                    countHeldBenches(player),
                    economyOnline,
                    economyOnline ? EconomyServices.economyService().creditBalance(player) : 0L,
                    MunitionsConfig.GUNSMITH_ENABLED.get());
        }

        /** 拥有数 = 已放置 + 背包里未放置的 (拥有数上限按这个数算)。 */
        public int benchesOwned() {
            return benchesPlaced + benchesHeld;
        }
    }

    /**
     * 一次成交的回执 (回放用)。
     *
     * @param price        实扣信用点 (免费条目为 0)
     * @param balanceAfter 扣费后的余额; 经济未就绪 (只可能出现在免费条目上) 时为 null
     */
    public record Receipt(UUID purchaseId, String entryId, long price, @Nullable Long balanceAfter) {
    }

    public enum PurchaseStatus {
        COMPLETED,
        REPLAYED,
        REJECTED,
        PURCHASE_ID_CONFLICT
    }

    /**
     * 购买结果。COMPLETED/REPLAYED 带 receipt; REJECTED 带 verdict 与判定当时的 snapshot (拒绝文案要用其中的数);
     * PURCHASE_ID_CONFLICT 带那个 id 原本成交的 receipt。
     */
    public record Purchase(PurchaseStatus status, @Nullable Receipt receipt, @Nullable Verdict verdict,
                           @Nullable Snapshot snapshot) {

        static Purchase completed(Receipt receipt) {
            return new Purchase(PurchaseStatus.COMPLETED, receipt, null, null);
        }

        static Purchase replayed(Receipt receipt) {
            return new Purchase(PurchaseStatus.REPLAYED, receipt, null, null);
        }

        static Purchase rejected(Verdict verdict, Snapshot snapshot) {
            return new Purchase(PurchaseStatus.REJECTED, null, verdict, snapshot);
        }

        static Purchase conflict(Receipt original) {
            return new Purchase(PurchaseStatus.PURCHASE_ID_CONFLICT, original, null, null);
        }
    }

    /** 可购条目。 */
    public static final class Entry {

        private final String id;
        private final Kind kind;
        @Nullable
        private final RegistryObject<Block> benchBlock;
        private final RegistryObject<Item> item;
        @Nullable
        private final GunsmithBlueprint blueprint;
        private final ForgeConfigSpec.IntValue price;

        private Entry(String id, Kind kind, @Nullable RegistryObject<Block> benchBlock, RegistryObject<Item> item,
                      @Nullable GunsmithBlueprint blueprint, ForgeConfigSpec.IntValue price) {
            this.id = id;
            this.kind = kind;
            this.benchBlock = benchBlock;
            this.item = item;
            this.blueprint = blueprint;
            this.price = price;
        }

        /** 稳定 id: 台子为方块注册名 (如 munitions_bench_medium), 图纸为 "blueprint/" + templateId。 */
        public String id() {
            return id;
        }

        public Kind kind() {
            return kind;
        }

        @Nullable
        public GunsmithBlueprint blueprint() {
            return blueprint;
        }

        /** 军火台条目的方块 (其余条目为 null)。 */
        @Nullable
        public MunitionsBenchBlock bench() {
            if (benchBlock == null) {
                return null;
            }
            if (!(benchBlock.get() instanceof MunitionsBenchBlock bench)) {
                throw new IllegalStateException("Shop bench entry " + id + " is not a munitions bench block");
            }
            return bench;
        }

        public Item item() {
            return item.get();
        }

        public String itemId() {
            return ForgeRegistries.ITEMS.getKey(item()).toString();
        }

        /** 翻译键: 台子为方块键; 图纸为套壳键 (带一个 %s, 实参是枪名键), 与物品栏里的名字同一拼法。 */
        public String nameKey() {
            return kind == Kind.BLUEPRINT ? "item.miningdim.gunsmith_blueprint.name" : item().getDescriptionId();
        }

        /** 当前配置下的售价 (实时读, 运营改完 toml 下一次调用就生效)。 */
        public long price() {
            return price.get();
        }

        /** 采购等级 (推导规则见类注释)。 */
        public int requiredLevel() {
            return switch (kind) {
                case BENCH -> benchPurchaseLevel(Objects.requireNonNull(bench(), "bench"));
                case PRESS -> pressPurchaseLevel();
                case ASSEMBLY -> assemblyPurchaseLevel();
                case BLUEPRINT -> blueprintPurchaseLevel(Objects.requireNonNull(blueprint, "blueprint"));
            };
        }

        /** 发给玩家的那一件 (每次新建, 调用方可随意改动)。 */
        public ItemStack createStack() {
            if (blueprint != null) {
                return GunsmithBlueprintItem.createStack(item(), blueprint);
            }
            return new ItemStack(item());
        }
    }

    /** 每名玩家最近的成功回执 (LRU)。只在主线程读写, 仍加锁: 登出清理之类的旁路将来接进来时不必再回头补。 */
    private static final class ReceiptBook {

        private final Map<UUID, LinkedHashMap<UUID, Receipt>> byPlayer = new HashMap<>();

        @Nullable
        synchronized Receipt find(UUID playerId, UUID purchaseId) {
            LinkedHashMap<UUID, Receipt> receipts = byPlayer.get(playerId);
            return receipts == null ? null : receipts.get(purchaseId);
        }

        synchronized void remember(UUID playerId, Receipt receipt) {
            LinkedHashMap<UUID, Receipt> receipts = byPlayer.computeIfAbsent(playerId, id -> new LinkedHashMap<>());
            receipts.put(receipt.purchaseId(), receipt);
            Iterator<UUID> oldest = receipts.keySet().iterator();
            while (receipts.size() > RECEIPTS_PER_PLAYER && oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
    }
}
