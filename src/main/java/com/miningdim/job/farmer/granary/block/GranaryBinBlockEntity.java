package com.miningdim.job.farmer.granary.block;

import com.miningdim.job.farmer.granary.GranaryConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;

import java.util.UUID;

/**
 * 储粮囤方块实体 (Spoilage_Granary_DesignSpec「部件」「保护与防复制」「物品数据与存档」)。
 *
 * 本片只落**容器与归属**这一层: 27 格 (与木桶一致)、谁放下就归谁、不对外开放物品接口 (漏斗/Create 都插不进来)。
 * 停表结算 (EntryHours / CreditedHours / BinState / Access) 与粮仓结构判定随它们各自的分片加入 ——
 * 这些字段一旦发布就不能改, 所以没有对应功能之前不预埋。
 */
public final class GranaryBinBlockEntity extends BlockEntity implements WorldlyContainer {

    private static final String KEY_SLOTS = "Slots";
    private static final String KEY_INV = "Inv";
    private static final String KEY_OWNER = "Owner";
    private static final String KEY_SEQ = "Seq";

    private NonNullList<ItemStack> items;
    private UUID owner;
    private int seq;

    public GranaryBinBlockEntity(BlockPos pos, BlockState state) {
        super(GranaryBlocks.BIN_ENTITY.get(), pos, state);
        this.items = NonNullList.withSize(GranaryConfig.binSlots(), ItemStack.EMPTY);
    }

    // ============================================================
    // 归属
    // ============================================================

    /** 囤主 (没登记过返回 null)。 */
    public UUID owner() {
        return owner;
    }

    /** 入册序号 (同一座粮仓里按它决定启用顺序)。 */
    public int seq() {
        return seq;
    }

    public void assignOwner(UUID owner, int seq) {
        this.owner = owner;
        this.seq = seq;
        setChanged();
    }

    // ============================================================
    // 容器 (不对外开放物品接口: 漏斗与 Create 都拿不到面)
    // ============================================================

    @Override
    public int getContainerSize() {
        return items.size();
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack removed = ContainerHelper.removeItem(items, slot, amount);
        if (!removed.isEmpty()) {
            setChanged();
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return level != null && level.getBlockEntity(worldPosition) == this
                && player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D,
                worldPosition.getZ() + 0.5D) <= 64.0D;
    }

    @Override
    public void clearContent() {
        items.clear();
        setChanged();
    }

    /** 不接任何一面: 粮仓里的东西只能经本模块的界面进出。 */
    @Override
    public int[] getSlotsForFace(Direction side) {
        return new int[0];
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
        return false;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return false;
    }

    // ============================================================
    // 存档
    // ============================================================

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt(KEY_SLOTS, items.size());
        CompoundTag inventory = new CompoundTag();
        ContainerHelper.saveAllItems(inventory, items);
        tag.put(KEY_INV, inventory);
        if (owner != null) {
            tag.putUUID(KEY_OWNER, owner);
        }
        tag.putInt(KEY_SEQ, seq);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        int size = tag.contains(KEY_SLOTS, Tag.TAG_INT) ? tag.getInt(KEY_SLOTS) : GranaryConfig.binSlots();
        if (size <= 0 || size > 512) {
            throw new IllegalArgumentException("Invalid granary bin slot count: " + size);
        }
        // 格数写进每个囤自己的存档: 管理员以后调配置只影响新放的囤, 已经放下的按存档里的格数走。
        items = NonNullList.withSize(size, ItemStack.EMPTY);
        if (tag.contains(KEY_INV, Tag.TAG_COMPOUND)) {
            ContainerHelper.loadAllItems(tag.getCompound(KEY_INV), items);
        }
        owner = tag.hasUUID(KEY_OWNER) ? tag.getUUID(KEY_OWNER) : null;
        seq = tag.getInt(KEY_SEQ);
    }
}
