package com.miningdim.donation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 捐赠箱方块实体: 内部仓库 + 箱主/协管名单 + 存取流水。
 *
 * 权限模型 (全部在服务端判定, 客户端只拿到箱主身份用于挖掘进度显示):
 *  - 箱主: 放置者。可管理仓库、增删协管、拆除箱子。非玩家放置 (指令、机器的假玩家) 没有箱主。
 *  - 协管: 箱主在游戏内指定, 至多 {@link #MAX_CO_ADMINS} 人。可管理仓库、查流水; 不能拆箱、不能改协管名单。
 *  - OP (权限等级 &gt;= 2): 等同箱主, 且可以用指令转让箱主 (换自管区管理时用)。
 *  - 其他人: 只能打开捐赠界面往里放, 看不到也取不走仓库。
 *
 * 只进不出: 能力 (capability) 只暴露 {@link DonationAutomationHandler} 这个抽取恒空的视图, 且底面与空面
 * (信息显示模组的读法) 不暴露; 本类不实现原版 Container, 原版漏斗的非能力路径也够不到仓库。
 */
public final class DonationBoxBlockEntity extends BlockEntity implements Clearable {

    /** 协管人数上限。 */
    public static final int MAX_CO_ADMINS = 8;

    /** OP 判定门槛, 与原版 /op 默认等级下"可用管理指令"的门槛一致。 */
    public static final int OPERATOR_PERMISSION_LEVEL = 2;

    private final DonationStorage storage = new DonationStorage(this::setChanged);
    private final DonationLedger ledger = new DonationLedger();
    private final Map<UUID, String> coAdmins = new LinkedHashMap<>();
    @Nullable
    private UUID ownerId;
    private String ownerName = "";

    /** 正在拆箱的玩家: onDestroyedByPlayer 放行前登记, 同一调用栈里的 onRemove 取走写审计。不存盘。 */
    @Nullable
    private DonationAudit.Actor remover;
    /** 被移除时写出的审计行; 移除后仍留在这个对象上供核对。不存盘。 */
    private List<String> removalAudit = List.of();

    private final DonationAutomationHandler automationHandler = new DonationAutomationHandler(this);
    private LazyOptional<IItemHandler> automationCap = LazyOptional.of(() -> automationHandler);

    public DonationBoxBlockEntity(BlockPos pos, BlockState state) {
        super(DonationRegistry.DONATION_BOX_BE.get(), pos, state);
    }

    public DonationStorage storage() {
        return storage;
    }

    public DonationLedger ledger() {
        return ledger;
    }

    // ============================================================
    // 权限
    // ============================================================

    public static boolean isOperator(Player player) {
        return player.hasPermissions(OPERATOR_PERMISSION_LEVEL);
    }

    @Nullable
    public UUID ownerId() {
        return ownerId;
    }

    public String ownerName() {
        return ownerName;
    }

    public boolean isOwner(Player player) {
        return ownerId != null && ownerId.equals(player.getUUID());
    }

    public boolean isCoAdmin(UUID playerId) {
        return coAdmins.containsKey(playerId);
    }

    /** 协管名单 (UUID -> 添加时的玩家名), 按添加顺序。 */
    public Map<UUID, String> coAdmins() {
        return Collections.unmodifiableMap(coAdmins);
    }

    /** 能否打开管理界面、存取仓库、查流水。 */
    public boolean canManage(Player player) {
        return isOwner(player) || isCoAdmin(player.getUUID()) || isOperator(player);
    }

    /** 能否增删协管。 */
    public boolean canEditCoAdmins(Player player) {
        return isOwner(player) || isOperator(player);
    }

    /** 能否拆除箱子。协管不能拆: 拆箱等于把整个仓库倒在地上, 这个权力只给箱主与 OP。 */
    public boolean canBreak(Player player) {
        return isOwner(player) || isOperator(player);
    }

    // ============================================================
    // 箱主与协管的变更
    // ============================================================

    /** 放置时记录放置者 (id 为 null 表示非玩家放置, 没有箱主)。 */
    void assignPlacer(@Nullable UUID id, String name) {
        this.ownerId = id;
        this.ownerName = id == null ? "" : name;
        if (id != null) {
            coAdmins.remove(id);
        }
        setChanged();
        syncToClients();
    }

    /**
     * 转让箱主 (OP 指令), 协管名单整体清空。换管理就是要让旧班子交出权限: 协管是原箱主任命的, 若转让后
     * 照旧保留, 原箱主只要事先把自己的小号加成协管, 交接之后仍能整仓取物。原箱主同样不自动降为协管;
     * 需要留用的人由新箱主重新添加。
     *
     * @return 被清掉的协管 (UUID -> 添加时的名字), 供调用方写审计; 按添加顺序
     */
    public Map<UUID, String> transferOwner(UUID newOwnerId, String newOwnerName) {
        Map<UUID, String> cleared = new LinkedHashMap<>(coAdmins);
        this.ownerId = newOwnerId;
        this.ownerName = newOwnerName;
        coAdmins.clear();
        setChanged();
        syncToClients();
        closeUnauthorizedViewers();
        return cleared;
    }

    public enum CoAdminChange {
        ADDED,
        REMOVED,
        ALREADY_PRESENT,
        NOT_PRESENT,
        IS_OWNER,
        LIMIT_REACHED;

        public boolean changed() {
            return this == ADDED || this == REMOVED;
        }
    }

    public CoAdminChange addCoAdmin(UUID playerId, String playerName) {
        if (playerId.equals(ownerId)) {
            return CoAdminChange.IS_OWNER;
        }
        if (coAdmins.containsKey(playerId)) {
            return CoAdminChange.ALREADY_PRESENT;
        }
        if (coAdmins.size() >= MAX_CO_ADMINS) {
            return CoAdminChange.LIMIT_REACHED;
        }
        coAdmins.put(playerId, playerName);
        setChanged();
        return CoAdminChange.ADDED;
    }

    /** 移除协管; 被移除者若正开着管理界面, 立即关掉 (不能继续取物)。 */
    public CoAdminChange removeCoAdmin(UUID playerId) {
        if (coAdmins.remove(playerId) == null) {
            return CoAdminChange.NOT_PRESENT;
        }
        setChanged();
        closeUnauthorizedViewers();
        return CoAdminChange.REMOVED;
    }

    /** 按名字 (忽略大小写) 在协管名单里找人; 用于移除已改名或离线的协管。 */
    public Optional<UUID> findCoAdminByName(String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<UUID, String> e : coAdmins.entrySet()) {
            if (e.getValue().toLowerCase(Locale.ROOT).equals(wanted)) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * 关闭所有已无权限者正开着的管理界面。被关的界面会走一次 removed, 其会话照常落账
     * (撤权之前已经取走的东西仍然要记账)。
     */
    void closeUnauthorizedViewers() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        for (ServerPlayer player : List.copyOf(serverLevel.getServer().getPlayerList().getPlayers())) {
            if (player.containerMenu instanceof DonationManagerMenu menu && menu.isBoundTo(this)
                    && !canManage(player)) {
                player.closeContainer();
            }
        }
    }

    // ============================================================
    // 存取与流水
    // ============================================================

    /** 捐赠界面投入格的转入: 过白名单后尽量堆进仓库, 返回放不下的余量。 */
    ItemStack acceptDonation(ItemStack stack) {
        if (isRemoved() || !DonationWhitelist.accepts(stack)) {
            return stack;
        }
        return ItemHandlerHelper.insertItemStacked(storage, stack, false);
    }

    void onAutomationInserted(Item item, int count) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        if (id == null) {
            return;
        }
        ledger.addAutomation(id, count, level == null ? 0L : level.getGameTime());
        setChanged();
    }

    /** 落账一次界面会话 (放入/取出按物品聚合), 每条同时写服务端日志。 */
    void commitSession(Player player, DonationSession session) {
        if (session.isEmpty()) {
            return;
        }
        List<DonationLedger.Entry> added = ledger.recordSession(player.getUUID(), player.getGameProfile().getName(),
                session.deposits(), session.withdrawals(), System.currentTimeMillis());
        session.clear();
        for (DonationLedger.Entry entry : added) {
            DonationAudit.entry(level, worldPosition, entry);
        }
        setChanged();
    }

    /** 服务端 tick: 自动化聚合窗口到期即落账。 */
    void serverTick() {
        if (level != null) {
            flushAutomationIfDue(level.getGameTime());
        }
    }

    /** 若自动化窗口在 gameTime 时已到期则落账, 返回是否落账 (测试直接驱动同一路径)。 */
    boolean flushAutomationIfDue(long gameTime) {
        if (!ledger.automationDue(gameTime)) {
            return false;
        }
        flushAutomation();
        return true;
    }

    private void flushAutomation() {
        List<DonationLedger.Entry> added = ledger.flushAutomation(System.currentTimeMillis());
        for (DonationLedger.Entry entry : added) {
            DonationAudit.entry(level, worldPosition, entry);
        }
        if (!added.isEmpty()) {
            setChanged();
        }
    }

    /**
     * 方块被移除时调用: 先把未到期的自动化输入落账 (它们确实进过仓库), 再清空仓库并交出全部物品供散落。
     */
    List<ItemStack> drainForRemoval() {
        flushAutomation();
        return storage.drainAll();
    }

    /** 登记 (或以 null 清除) 正在拆箱的玩家。 */
    void markRemover(@Nullable Player player) {
        remover = player == null ? null : new DonationAudit.Actor(player.getUUID(), player.getGameProfile().getName());
    }

    /** 取走登记的拆箱玩家; 非玩家移除时为 null。 */
    @Nullable
    DonationAudit.Actor takeRemover() {
        DonationAudit.Actor taken = remover;
        remover = null;
        return taken;
    }

    void recordRemovalAudit(List<String> lines) {
        removalAudit = List.copyOf(lines);
    }

    /** 本方块实体被移除时写出的审计行 (汇总 + 逐物品明细); 尚未移除时为空。 */
    public List<String> removalAudit() {
        return removalAudit;
    }

    /**
     * 原版 {@link Clearable}: /clone ... move、/setblock 与 /fill 的 replace 模式、结构放置在替换方块之前调用,
     * 语义与原版容器一致 —— 直接清空, 不散落。
     *
     * 必须实现: /clone ... move 先对源方块实体取快照, 再 tryClear 源、把源换成屏障, 目标从快照读回。不实现时
     * tryClear 什么都不做, 换屏障触发 onRemove 把整仓散落在源位置, 目标又带着整仓, 物品翻倍。
     * 未到期的自动化输入一并丢弃 (move 的目标会从快照继续落账, 这里再落一次就记重), 只在审计汇总行里记件数。
     */
    @Override
    public void clearContent() {
        List<ItemStack> cleared = storage.drainAll();
        int pending = ledger.discardPendingAutomation();
        if (!cleared.isEmpty() || pending > 0) {
            DonationAudit.cleared(level, worldPosition, ownerDescription(), cleared, pending);
        }
        setChanged();
    }

    // ============================================================
    // 界面
    // ============================================================

    MenuProvider donorMenuProvider() {
        return new SimpleMenuProvider((windowId, inventory, player) -> new DonationDonorMenu(windowId, inventory, this),
                Component.translatable("container.miningdim.donation_box.donor"));
    }

    MenuProvider managerMenuProvider() {
        return new SimpleMenuProvider((windowId, inventory, player) -> new DonationManagerMenu(windowId, inventory, this),
                Component.translatable("container.miningdim.donation_box.manager"));
    }

    // ============================================================
    // 能力: 只进不出
    // ============================================================

    /**
     * 物品能力只对顶面与四个侧面暴露只进不出的视图。底面不给 (不让下方漏斗抽取); 空面 (side == null) 也不给:
     * 那是 Jade、The One Probe 这类信息显示模组的读法, 它们逐格 getStackInSlot 后把仓库清单发给任何看向箱子的
     * 玩家, 等于让捐赠者看到仓库。漏斗、管道、机械臂查询时都带具体的面, 输入不受影响。
     */
    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return side == null || side == Direction.DOWN ? LazyOptional.empty() : automationCap.cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        automationCap.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        automationCap = LazyOptional.of(() -> automationHandler);
    }

    // ============================================================
    // 持久化
    // ============================================================

    private static final String K_STORAGE = "Storage";
    private static final String K_LEDGER = "Ledger";
    private static final String K_OWNER_ID = "OwnerId";
    private static final String K_OWNER_NAME = "OwnerName";
    private static final String K_CO_ADMINS = "CoAdmins";
    private static final String K_ID = "Id";
    private static final String K_NAME = "Name";

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put(K_STORAGE, storage.serializeNBT());
        tag.put(K_LEDGER, ledger.save());
        writeOwner(tag);
        ListTag list = new ListTag();
        for (Map.Entry<UUID, String> e : coAdmins.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putUUID(K_ID, e.getKey());
            row.putString(K_NAME, e.getValue());
            list.add(row);
        }
        tag.put(K_CO_ADMINS, list);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains(K_STORAGE, Tag.TAG_COMPOUND)) {
            storage.deserializeNBT(tag.getCompound(K_STORAGE));
        }
        ledger.load(tag.getCompound(K_LEDGER));
        readOwner(tag);
        coAdmins.clear();
        ListTag list = tag.getList(K_CO_ADMINS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && coAdmins.size() < MAX_CO_ADMINS; i++) {
            CompoundTag row = list.getCompound(i);
            if (row.hasUUID(K_ID) && !row.getUUID(K_ID).equals(ownerId)) {
                coAdmins.put(row.getUUID(K_ID), row.getString(K_NAME));
            }
        }
    }

    private void writeOwner(CompoundTag tag) {
        if (ownerId != null) {
            tag.putUUID(K_OWNER_ID, ownerId);
            tag.putString(K_OWNER_NAME, ownerName);
        }
    }

    private void readOwner(CompoundTag tag) {
        if (tag.hasUUID(K_OWNER_ID)) {
            ownerId = tag.getUUID(K_OWNER_ID);
            ownerName = tag.getString(K_OWNER_NAME);
        } else {
            ownerId = null;
            ownerName = "";
        }
    }

    /**
     * 同步给客户端的只有箱主身份: 客户端据此让非箱主的挖掘进度保持为 0 (不出现"挖得动"的假象)。
     * 仓库内容、协管名单与流水一律不进区块包 —— 捐赠者的客户端不该拿到这些数据。
     */
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        writeOwner(tag);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        readOwner(tag);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) {
            readOwner(tag);
        }
    }

    /**
     * 物品上携带的 BlockEntityTag 只在 OP 放置时生效: 普通玩家放不出"预装仓库/预设协管"的箱子。
     * (箱子物品本身又在拒收标签里, 带 NBT 的箱子物品也进不了任何捐赠箱。)
     */
    @Override
    public boolean onlyOpCanSetNbt() {
        return true;
    }

    /**
     * 不把服务端数据写进物品。原版服务端只在一处调用本方法: 创造模式 Ctrl+鼠标中键选取方块后,
     * ServerGamePacketListenerImpl.handleSetCreativeModeSlot 看到物品带坐标的 BlockEntityTag, 就用服务端
     * 方块实体覆盖它 (且不校验距离)。默认实现会写进整仓内容、流水与协管名单: OP 把这个物品放下就复制出一整仓,
     * 任何创造模式玩家还能伪造坐标远程读出已加载箱子的仓库。这里刻意不写, 物品保留客户端发来的那份
     * (客户端方块实体只有箱主身份, 见 {@link #getUpdateTag})。
     */
    @Override
    public void saveToItem(ItemStack stack) {
        // 刻意不写: 见上。箱子作为物品不携带内容物。
    }

    private void syncToClients() {
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /** 审计日志用的箱主描述: 名字(UUID), 无箱主时为 "-"。 */
    String ownerDescription() {
        return ownerId == null ? "-" : DonationAudit.describe(ownerId, ownerName);
    }

    /** 协管名字列表 (显示用)。 */
    List<String> coAdminNames() {
        return new ArrayList<>(coAdmins.values());
    }
}
