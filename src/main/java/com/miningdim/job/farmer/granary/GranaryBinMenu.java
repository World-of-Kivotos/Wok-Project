package com.miningdim.job.farmer.granary;

import com.miningdim.freshness.FreshnessData;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

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
                        // 开服迁移的老货不能进仓 (规格「开服迁移」): 它们的停表额度已经记满, 进仓再停表等于白送额度。
                        // 一并放在这里, 是为了让界面与「一键存入」给出同一个答案 (那条路在 GranaryDeposit 里跳过并点名)。
                        return mayStore() && !FreshnessData.isMigrated(stack);
                    }

                    @Override
                    public boolean mayPickup(Player player) {
                        return mayTake();
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
        return GranaryAccess.mayStore(blockEntity.owner(), blockEntity.access(), viewer.getUUID());
    }

    /** 这位玩家现在能不能从这个囤里拿。 */
    public boolean mayTake() {
        return GranaryAccess.mayTake(blockEntity.owner(), blockEntity.access(), viewer.getUUID());
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
