package com.miningdim.job.farmer.granary;

import com.miningdim.freshness.FreshnessClockData;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 储粮囤的 27 格界面 (Spoilage_Granary_DesignSpec「界面」: 「储粮囤本身右键打开是 27 格界面加开放设置页」)。
 *
 * 三件事在这里落:
 * <ol>
 *   <li><b>存取权限是逐槽判的</b>: 囤主的、名单上「只存」的能放不能拿, 「可存可取」的能放能拿, 名单之外
 *       (包括房主) 两样都不行 —— 判定每次现算 ({@link GranaryAccess}), 界面开着的时候名单变了立刻生效,
 *       不会因为「开界面那一刻有权限」就一路放行;</li>
 *   <li><b>每到整点先结算</b>: 界面开着跨过整点时把这个囤结算到当前小时 (规格「菜单开着每到整点先结算」),
 *       靠 {@code lastSettleHour} 只在小时变了之后跑一次, 不是每 tick 都算;</li>
 *   <li>槽的读写都走方块实体 ({@link GranaryBinBlockEntity} 自己维护每格的入仓时刻与停表账), 所以经界面
 *       放进去的东西和「一键存入」放进去的完全一样: 盖章、记入仓时刻、开始停表。</li>
 * </ol>
 */
public final class GranaryBinMenu extends AbstractMiningMenu {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/granary");

    /** 容器槽数 (与囤的格数一致)。 */
    public static final int CONTAINER_SLOTS = GranaryConstants.BIN_SLOTS;

    // 27 格 9x3 网格 + 玩家背包 (与原版潜影盒同一套坐标, 底图也用它的)。
    private static final int GRID_COLS = 9;
    private static final int GRID_ROWS = 3;
    private static final int GRID_X = 8;
    private static final int GRID_Y = 18;
    private static final int SLOT_PX = 18;
    private static final int PLAYER_INV_X = 8;
    private static final int PLAYER_INV_Y = 84;

    private final GranaryBinBlockEntity blockEntity;
    private final Player viewer;
    /** 上一次结算到的小时 (0 = 还没结算过); 只在小时变了之后真结算。 */
    private long lastSettleHour;
    private boolean trackingClick;

    /** 服务端构造 (方块实体自己实现 MenuProvider, 直接传引用)。 */
    public GranaryBinMenu(int windowId, Inventory playerInv, GranaryBinBlockEntity be) {
        super(GranaryMenus.BIN.get(), windowId, be.getContainerSize(),
                MenuValidity.ofBlock(
                        ContainerLevelAccess.create(be.getLevel(), be.getBlockPos()),
                        be.getBlockState().getBlock()));
        this.blockEntity = be;
        this.viewer = playerInv.player;
        addBinSlots(be);
        addPlayerInventory(playerInv, PLAYER_INV_X, PLAYER_INV_Y);
    }

    /** 客户端构造 (blockMenuType 的 extraData 读 BlockPos 后调; 世界里取不到方块实体时建一个临时的)。 */
    public GranaryBinMenu(int windowId, Inventory playerInv, BlockPos pos) {
        super(GranaryMenus.BIN.get(), windowId, CONTAINER_SLOTS,
                MenuValidity.ofBlock(
                        ContainerLevelAccess.create(playerInv.player.level(), pos),
                        playerInv.player.level().getBlockState(pos).getBlock()));
        this.blockEntity = clientBlockEntity(playerInv, pos);
        this.viewer = playerInv.player;
        addBinSlots(blockEntity);
        addPlayerInventory(playerInv, PLAYER_INV_X, PLAYER_INV_Y);
    }

    private void addBinSlots(GranaryBinBlockEntity be) {
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int index = row * GRID_COLS + col;
                int x = GRID_X + col * SLOT_PX;
                int y = GRID_Y + row * SLOT_PX;
                this.addSlot(new Slot(be, index, x, y) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        ServerLevel level = serverLevel();
                        if (level == null || !GranaryAdmission.accepts(level, blockEntity, viewer, stack,
                                FreshnessClockData.nowMinute(level))) {
                            return false;
                        }
                        ItemStack existing = blockEntity.getItem(index);
                        if (!existing.isEmpty() && ItemStack.isSameItemSameTags(existing, stack)) {
                            return blockEntity.entryHour(index) == FreshnessClockData.nowMinute(level) / 60L;
                        }
                        return true;
                    }

                    @Override
                    public void set(ItemStack stack) {
                        boolean publicDonation = !mayStore();
                        boolean publicTake = !mayTake();
                        ItemStack before = getItem().copy();
                        super.set(stack);
                        if (!trackingClick) {
                            recordSlotChange(publicDonation, publicTake, before, getItem());
                        }
                    }

                    @Override
                    public boolean mayPickup(Player player) {
                        return mayTake() || publicTakeAllows();
                    }

                    @Override
                    public void onTake(Player player, ItemStack stack) {
                        super.onTake(player, stack);
                        if (!trackingClick) {
                            recordTaken(!mayTake(), stack);
                        }
                    }
                });
            }
        }
    }

    /** 这个界面开的是哪个囤 (开放名单那条路要用它; 客户端侧拿到的是视图方块实体)。 */
    public GranaryBinBlockEntity bin() {
        return blockEntity;
    }

    /** 这位玩家现在能不能往这个囤里放 (现算: 名单改了立刻生效)。 */
    public boolean mayStore() {
        return GranaryAccess.mayStore(blockEntity.owner(), blockEntity.access(), viewer.getUUID())
                || GranaryAdmission.isOperator(viewer);
    }

    /** 这位玩家现在能不能从这个囤里拿。 */
    public boolean mayTake() {
        return GranaryAccess.mayTake(blockEntity.owner(), blockEntity.access(), viewer.getUUID())
                || GranaryAdmission.isOperator(viewer);
    }

    /** 官仓的公开取用: 这个人今天还能不能从这个囤里拿。 */
    private boolean publicTakeAllows() {
        ServerLevel level = serverLevel();
        if (level == null || !(viewer instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        return GranaryPublicService.mayTake(level, blockEntity, serverPlayer,
                GranaryPublicService.nowMinute(level));
    }

    /**
     * 记账: 流水 (谁存了哪个囤多少件) + 公开捐粮的一组配额。
     *
     * 流水与配额都只在**服务端**记 —— 客户端手里的方块实体没有账本, 也没有真挂钟。
     */
    private void recordStored(boolean publicDonation, ItemStack stack, int count) {
        ServerLevel level = serverLevel();
        if (level == null || !(viewer instanceof ServerPlayer serverPlayer) || stack.isEmpty()) {
            return;
        }
        if (publicDonation) {
            blockEntity.recordPublicDonation(serverPlayer.getUUID(),
                    GranaryPublic.dayOf(GranaryPublicService.nowMinute(level)), 1);
        }
        GranaryJournal.record(level, blockEntity.getBlockPos(), serverPlayer.getUUID(),
                publicDonation ? GranaryJournal.Kind.DONATE : GranaryJournal.Kind.STORE, stack, count);
        if (GranaryAdmission.isOperator(serverPlayer)) {
            LOGGER.info("granary bin at {} in {} operator {} stored {} of {}",
                    blockEntity.getBlockPos(), level.dimension().location(), serverPlayer.getGameProfile().getName(),
                    count, stack.getHoverName().getString());
        }
    }

    /** 记账: 流水 + 公开取用的一组配额。 */
    private void recordTaken(boolean publicTake, ItemStack stack) {
        ServerLevel level = serverLevel();
        if (level == null || !(viewer instanceof ServerPlayer serverPlayer) || stack.isEmpty()) {
            return;
        }
        if (publicTake) {
            blockEntity.recordPublicTake(serverPlayer.getUUID(),
                    GranaryPublic.dayOf(GranaryPublicService.nowMinute(level)), 1);
        }
        GranaryJournal.record(level, blockEntity.getBlockPos(), serverPlayer.getUUID(),
                publicTake ? GranaryJournal.Kind.PUBLIC_TAKE : GranaryJournal.Kind.TAKE,
                stack, stack.getCount());
        if (GranaryAdmission.isOperator(serverPlayer)) {
            LOGGER.info("granary bin at {} in {} operator {} took {} of {}",
                    blockEntity.getBlockPos(), level.dimension().location(), serverPlayer.getGameProfile().getName(),
                    stack.getCount(), stack.getHoverName().getString());
        }
    }

    private ServerLevel serverLevel() {
        return viewer.level() instanceof ServerLevel level ? level : null;
    }

    /**
     * 有效性与「跨整点先结算」。
     *
     * 服务端每个 tick 都会问一次 stillValid —— 正好拿来当结算的时机: 只有小时真的变了才动账,
     * 所以既满足规格的「每到整点先结算」, 又不会每 tick 扫一遍 27 格。
     */
    @Override
    public boolean stillValid(Player player) {
        boolean valid = super.stillValid(player);
        if (valid && !player.level().isClientSide && player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            long nowHour = GranaryClock.nowHour(serverLevel);
            if (nowHour != lastSettleHour) {
                lastSettleHour = nowHour;
                blockEntity.settleAll(nowHour);
            }
        }
        return valid;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        ServerLevel level = serverLevel();
        if (level == null) {
            super.clicked(slotId, button, clickType, player);
            return;
        }
        observeBin(level, player);
        long nowMinute = FreshnessClockData.nowMinute(level);
        boolean publicDonation = GranaryAdmission.context(level, blockEntity, player, nowMinute).publicDonor();
        boolean publicTake = !mayTake();
        ItemStack[] before = new ItemStack[CONTAINER_SLOTS];
        for (int slot = 0; slot < CONTAINER_SLOTS; slot++) {
            before[slot] = blockEntity.getItem(slot).copy();
        }
        trackingClick = true;
        try {
            super.clicked(slotId, button, clickType, player);
        } finally {
            trackingClick = false;
        }
        for (int slot = 0; slot < CONTAINER_SLOTS; slot++) {
            Slot binSlot = slots.get(slot);
            recordSlotChange(publicDonation, publicTake, before[slot], binSlot.getItem());
        }
    }

    private void observeBin(ServerLevel level, Player player) {
        long nowMinute = FreshnessClockData.nowMinute(level);
        long nowHour = nowMinute / 60L;
        lastSettleHour = nowHour;
        blockEntity.settleAll(nowHour);
        List<ItemStack> returns = blockEntity.settleFreshness(nowMinute);
        for (ItemStack stack : returns) {
            if (!stack.isEmpty() && !player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
    }

    private void recordSlotChange(boolean publicDonation, boolean publicTake,
                                  ItemStack before, ItemStack after) {
        if (ItemStack.isSameItemSameTags(before, after)) {
            int difference = after.getCount() - before.getCount();
            if (difference > 0) {
                recordStored(publicDonation, after.copyWithCount(difference), difference);
            } else if (difference < 0) {
                recordTaken(publicTake, before.copyWithCount(-difference));
            }
            return;
        }
        if (!before.isEmpty()) {
            recordTaken(publicTake, before);
        }
        if (!after.isEmpty()) {
            recordStored(publicDonation, after, after.getCount());
        }
    }

    public GranaryBinBlockEntity blockEntity() {
        return blockEntity;
    }

    /** 这份菜单是为谁开的 (槽的放行判定按他现算)。 */
    public Player viewer() {
        return viewer;
    }

    /** 客户端侧从世界取方块实体; 取不到时建一个临时的 (槽空, 不影响渲染)。 */
    private static GranaryBinBlockEntity clientBlockEntity(Inventory inv, BlockPos pos) {
        if (inv.player.level().getBlockEntity(pos) instanceof GranaryBinBlockEntity be) {
            return be;
        }
        return new GranaryBinBlockEntity(pos, inv.player.level().getBlockState(pos));
    }
}
