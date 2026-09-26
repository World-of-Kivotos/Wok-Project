package com.miningdim.menu;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 公共 menu 脚手架基类 (JobFramework_Shared_Foundation_DesignSpec 第六章)。供工程师/塔罗/厨师/结婚复用。
 * 全 1.20.1 写法 (严禁 1.20.4+ custom payload / 1.20.5+ MapCodec)。
 *
 * 本基类解决三个 menu 通病:
 *  1. quickMoveStack (Shift 移物) 的正确实现 —— 防 "Shift 吞物" (移动后未清空原槽) 与移动逻辑漏区段;
 *  2. stillValid 防死循环 —— 经 {@link MenuValidity} 策略统一裁决, 方块 menu 走方块存在 + 距离检查,
 *     非方块 (戒指远程开共享背包) 走自定义 owner/距离谓词, 二者都返回 boolean 不递归;
 *  3. Shift 移物后背后方块实体必须标脏 (V02) —— Forge SlotItemHandler 不覆写 setChanged, slot.setChanged()
 *     落到一个 0 格空容器上什么都不做; 就地改活栈后必须经 slot.set 走 setStackInSlot -> onContentsChanged
 *     -> BE.setChanged, 否则区块不存盘, 卸载/重启后容器回档 (部分移出复制物品, 合并放入吞物)。
 *
 * 子类约定:
 *  - 构造时先 addSlot 容器槽 (容器区), 再 addSlot 玩家背包 27 + 快捷栏 9 (共 36); 记录两个边界索引
 *    {@code containerSlotCount} / 玩家槽起止, 供 quickMoveStack 分区移动。本基类用 {@link #addPlayerInventory}
 *    统一铺玩家 36 槽, 子类只需铺自己的容器槽并传入容器槽数。
 *  - 如需服务端->客户端同步整数 (进度/CD), 经 {@link #addDataSlots(ContainerData)} (原版方法) 同步。
 */
public abstract class AbstractMiningMenu extends AbstractContainerMenu {

    /** 玩家背包标准布局常量 (原版): 主背包 3x9=27 + 快捷栏 9, 槽尺寸 18px。 */
    private static final int PLAYER_INV_ROWS = 3;
    private static final int PLAYER_INV_COLS = 9;
    private static final int HOTBAR_SLOTS = 9;
    private static final int SLOT_PX = 18;

    /** menu 有效性策略 (stillValid 委派, 防死循环)。 */
    private final MenuValidity validity;

    /** 容器区槽数 (索引 [0, containerSlotCount) 为容器槽, 其后为玩家 36 槽)。quickMoveStack 据此分区。 */
    private final int containerSlotCount;

    /**
     * @param type               MenuType (子类传自己的 RegistryObject.get())
     * @param windowId           窗口 id (IForgeMenuType.create 工厂回调传入)
     * @param containerSlotCount 容器区槽数 (子类铺完容器槽后传入; 用于 quickMoveStack 分区)
     * @param validity           有效性策略 (方块或远程; 见 {@link MenuValidity})
     */
    protected AbstractMiningMenu(MenuType<?> type, int windowId, int containerSlotCount, MenuValidity validity) {
        super(type, windowId);
        if (validity == null) {
            throw new IllegalArgumentException("MenuValidity must not be null");
        }
        if (containerSlotCount < 0) {
            throw new IllegalArgumentException("containerSlotCount must be >= 0, got " + containerSlotCount);
        }
        this.validity = validity;
        this.containerSlotCount = containerSlotCount;
    }

    /**
     * 铺玩家背包 36 槽 (主背包 27 + 快捷栏 9), 子类在铺完自己的容器槽后调用一次。
     * 坐标按原版标准布局相对左上角 (originX/originY 为玩家背包区左上角像素)。
     */
    protected final void addPlayerInventory(Container playerInventory, int originX, int originY) {
        // 主背包 3 行 x 9 列 (索引 9-35)。
        for (int row = 0; row < PLAYER_INV_ROWS; row++) {
            for (int col = 0; col < PLAYER_INV_COLS; col++) {
                int index = HOTBAR_SLOTS + col + row * PLAYER_INV_COLS;
                int x = originX + col * SLOT_PX;
                int y = originY + row * SLOT_PX;
                this.addSlot(new Slot(playerInventory, index, x, y));
            }
        }
        // 快捷栏 1 行 x 9 列 (索引 0-8), 在主背包下方再隔一行间距 (原版 +4px 间隙惯例并入 4 行高)。
        int hotbarY = originY + PLAYER_INV_ROWS * SLOT_PX + 4;
        for (int col = 0; col < HOTBAR_SLOTS; col++) {
            this.addSlot(new Slot(playerInventory, col, originX + col * SLOT_PX, hotbarY));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        // 委派策略 (方块: 方块存在 + 距离; 远程: owner/距离谓词)。不引用 this.stillValid 防自递归。
        return validity.isValid(player);
    }

    /**
     * 正确的 Shift 快速移动 (防 Shift 吞物 + 防死循环)。标准箱式实现: 容器区 <-> 玩家区互移, 复制原槽 stack,
     * moveItemStackTo 到目标分区, 移动后据是否清空决定 set(EMPTY) 或 set(残留栈) 写回, 最终返回移动前的副本
     * (空则返回 EMPTY 终止循环)。
     *
     * 分区: 索引 [0, containerSlotCount) = 容器槽; [containerSlotCount, end) = 玩家 36 槽。
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack moved = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stackInSlot = slot.getItem();
        moved = stackInSlot.copy();

        int playerStart = containerSlotCount;
        int playerEnd = this.slots.size(); // 容器槽后全部为玩家 36 槽。

        if (index < playerStart) {
            // 从容器区 -> 玩家区 (反向填充: 先快捷栏后背包随原版习惯; 这里统一正向到玩家全区)。
            if (!this.moveItemStackTo(stackInSlot, playerStart, playerEnd, true)) {
                return ItemStack.EMPTY; // 无法移动: 返回 EMPTY 终止 vanilla 循环。
            }
        } else {
            // 从玩家区 -> 容器区。
            if (!this.moveItemStackTo(stackInSlot, 0, playerStart, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stackInSlot.isEmpty()) {
            slot.set(ItemStack.EMPTY); // 防 Shift 吞物: 全部移走后必须清空原槽。
        } else {
            // 部分移出 (V02): 残留栈是 moveItemStackTo 就地 shrink 过的活栈, 必须经 set 写回让背后 handler
            // 触发 onContentsChanged 标脏 BE; 只调 setChanged 对 SlotItemHandler 是空操作, 区块不存盘即回档复制。
            slot.set(stackInSlot);
        }
        if (stackInSlot.getCount() == moved.getCount()) {
            return ItemStack.EMPTY; // 一个都没移走: 终止循环 (否则 vanilla 无限调用)。
        }
        slot.onTake(player, stackInSlot);
        return moved;
    }

    /**
     * 覆写 vanilla {@link AbstractContainerMenu#moveItemStackTo} (已按本项目锁定的 forge-1.20.1-47.3.0 字节码核实),
     * 本基类 quickMoveStack 与子类 Shift 覆写都经它落槽。与 vanilla 只差 "合并入已有同种栈" 这一阶段的两处:
     *  1. 合并后经 {@code slot.set(slotStack)} 写回 (V02) —— vanilla 只 {@code setCount} + {@code slot.setChanged()},
     *     对 SlotItemHandler 不标脏 BE, 补进容器的物品会在区块卸载后凭空消失;
     *  2. 跳过 {@link Slot#mayPlace} 为 false 的槽 —— vanilla 合并阶段只判同物同 tag, 玩家手里的同种物品会被
     *     并进只取不放的输出槽 (找空槽阶段 vanilla 本来就判 mayPlace, 此处补齐)。
     * 找空槽阶段原样保留 (setByPlayer 本身就走 set)。
     */
    @Override
    protected boolean moveItemStackTo(ItemStack stack, int startIndex, int endIndex, boolean reverseDirection) {
        boolean moved = false;
        int i = reverseDirection ? endIndex - 1 : startIndex;

        if (stack.isStackable()) {
            while (!stack.isEmpty()) {
                if (reverseDirection ? i < startIndex : i >= endIndex) {
                    break;
                }
                Slot slot = this.slots.get(i);
                ItemStack slotStack = slot.getItem();
                if (!slotStack.isEmpty() && ItemStack.isSameItemSameTags(stack, slotStack) && slot.mayPlace(stack)) {
                    int sum = slotStack.getCount() + stack.getCount();
                    int maxSize = Math.min(slot.getMaxStackSize(), stack.getMaxStackSize());
                    if (sum <= maxSize) {
                        stack.setCount(0);
                        slotStack.setCount(sum);
                        slot.set(slotStack);
                        moved = true;
                    } else if (slotStack.getCount() < maxSize) {
                        stack.shrink(maxSize - slotStack.getCount());
                        slotStack.setCount(maxSize);
                        slot.set(slotStack);
                        moved = true;
                    }
                }
                i += reverseDirection ? -1 : 1;
            }
        }

        if (!stack.isEmpty()) {
            i = reverseDirection ? endIndex - 1 : startIndex;
            while (reverseDirection ? i >= startIndex : i < endIndex) {
                Slot slot = this.slots.get(i);
                if (slot.getItem().isEmpty() && slot.mayPlace(stack)) {
                    int amount = Math.min(stack.getCount(), slot.getMaxStackSize());
                    slot.setByPlayer(stack.split(amount));
                    slot.setChanged();
                    moved = true;
                    break;
                }
                i += reverseDirection ? -1 : 1;
            }
        }

        return moved;
    }

    /**
     * 关窗兜底标脏 (V02): 服务端关界面时对背后方块实体补一次 {@link BlockEntity#setChanged()}, 兜住以后新增的、
     * 就地改栈却没经 handler 写回的路径。只调 BE.setChanged, 不走 handler 的 onContentsChanged (后者会中断
     * 进行中的酿造/校准)。这只是兜底, 不能替代 quickMoveStack / moveItemStackTo 里的 set 写回: 换维度时菜单
     * 关闭与旧维度区块卸载的先后没有保证。远程 menu 没有方块, {@link MenuValidity#blockAccess()} 为空操作。
     */
    @Override
    public void removed(Player player) {
        super.removed(player);
        validity.blockAccess().execute((level, pos) -> {
            // 客户端镜像不落盘; 区块已卸载时不为标脏去同步加载区块。
            if (level.isClientSide || !level.isLoaded(pos)) {
                return;
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity != null) {
                blockEntity.setChanged();
            }
        });
    }

    /** 容器区槽数 (子类/测试可读)。 */
    public final int containerSlotCount() {
        return containerSlotCount;
    }
}
