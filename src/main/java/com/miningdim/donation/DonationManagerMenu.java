package com.miningdim.donation;

import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.Item;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 管理界面: 内部仓库 54 格 (6x9, 与原版大箱子同布局, 客户端复用 generic_54 贴图) + 玩家背包。
 * 只有箱主/协管/OP 能打开; 流水、累计与协管名单在客户端另开一页查看 (数据走本模块的网络包)。
 *
 * 记账方式: 每次点击 (含 Shift 点击、数字键交换、双击收集、Q 丢出) 前后各对仓库取一次按物品汇总的快照,
 * 差值即这一下点击造成的放入/取出, 记在点击者名下。点击在服务端主线程上是原子的, 其间不会插进漏斗或
 * 别人的操作, 所以归属是精确的; 也不必逐个追踪原版 moveItemStackTo 里那些直接改栈数量的写法。
 *
 * 权限在服务端每次点击时重查: 被撤销协管的玩家即使界面还没来得及关, 点击也会被整体忽略 (客户端预测会被
 * 随后的同步纠正); {@link #stillValid} 同样带权限, 下一 tick 界面就会被关掉。
 */
public final class DonationManagerMenu extends AbstractMiningMenu {

    public static final int STORAGE_SLOTS = DonationStorage.SLOTS;

    private static final int GRID_X = 8;
    private static final int GRID_Y = 18;
    private static final int SLOT_PX = 18;
    private static final int COLUMNS = 9;
    private static final int PLAYER_INV_X = 8;
    /** 与原版 6 行 ChestMenu 一致: 103 + (6 - 4) * 18。 */
    private static final int PLAYER_INV_Y = 139;

    @Nullable
    private final DonationBoxBlockEntity blockEntity;
    private final DonationSession session = new DonationSession();

    /** 服务端构造: 槽位直接绑定方块实体的仓库。 */
    public DonationManagerMenu(int windowId, Inventory playerInventory, DonationBoxBlockEntity blockEntity) {
        super(DonationRegistry.MANAGER_MENU.get(), windowId, STORAGE_SLOTS,
                MenuValidity.ofBlock(ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos()),
                        blockEntity.getBlockState().getBlock()));
        this.blockEntity = blockEntity;
        layout(blockEntity.storage(), playerInventory);
    }

    /**
     * 客户端构造: 槽位绑定一个本地空仓库, 内容由原版容器同步填充。客户端方块实体里本来就没有仓库数据
     * (区块包只同步箱主身份), 绑它反而会让两个界面实例共享同一份客户端缓存。
     */
    public DonationManagerMenu(int windowId, Inventory playerInventory, BlockPos pos) {
        super(DonationRegistry.MANAGER_MENU.get(), windowId, STORAGE_SLOTS,
                MenuValidity.ofBlock(ContainerLevelAccess.create(playerInventory.player.level(), pos),
                        playerInventory.player.level().getBlockState(pos).getBlock()));
        this.blockEntity = null;
        layout(new DonationStorage(() -> {
        }), playerInventory);
    }

    private void layout(IItemHandler storage, Inventory playerInventory) {
        for (int row = 0; row < STORAGE_SLOTS / COLUMNS; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                addSlot(new SlotItemHandler(storage, col + row * COLUMNS, GRID_X + col * SLOT_PX, GRID_Y + row * SLOT_PX));
            }
        }
        addPlayerInventory(playerInventory, PLAYER_INV_X, PLAYER_INV_Y);
    }

    /** 本界面是否绑定在给定方块实体上 (撤权时找出需要关闭的界面)。 */
    boolean isBoundTo(DonationBoxBlockEntity candidate) {
        return blockEntity == candidate;
    }

    @Nullable
    DonationBoxBlockEntity blockEntity() {
        return blockEntity;
    }

    private boolean permitted(Player player) {
        return blockEntity != null && !blockEntity.isRemoved() && blockEntity.canManage(player);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (blockEntity == null) {
            super.clicked(slotId, button, clickType, player);
            return;
        }
        if (!permitted(player)) {
            return;
        }
        Map<Item, Integer> before = blockEntity.storage().countByItem();
        super.clicked(slotId, button, clickType, player);
        Map<Item, Integer> after = blockEntity.storage().countByItem();
        if (recordDifference(before, after)) {
            blockEntity.setChanged();
        }
    }

    /** 按物品求差并记入会话; 返回仓库是否有变化。 */
    private boolean recordDifference(Map<Item, Integer> before, Map<Item, Integer> after) {
        Set<Item> items = new HashSet<>(before.keySet());
        items.addAll(after.keySet());
        boolean changed = false;
        for (Item item : items) {
            int delta = after.getOrDefault(item, 0) - before.getOrDefault(item, 0);
            if (delta > 0) {
                session.deposit(item, delta);
                changed = true;
            } else if (delta < 0) {
                session.withdraw(item, -delta);
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public boolean stillValid(Player player) {
        return super.stillValid(player) && (blockEntity == null || permitted(player));
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (blockEntity != null) {
            blockEntity.commitSession(player, session);
        }
    }
}
