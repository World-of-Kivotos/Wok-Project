package com.miningdim.job.farmer.granary.block;

import com.miningdim.job.farmer.granary.GranaryAccess;
import com.miningdim.job.farmer.granary.GranaryConfig;
import com.miningdim.job.farmer.granary.GranaryConstants;
import com.miningdim.job.farmer.granary.GranarySettle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;
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
    private static final String KEY_ENTRY_HOURS = "EntryHours";
    private static final String KEY_CREDITED_HOURS = "CreditedHours";
    private static final String KEY_STOPPING = "Stopping";

    private NonNullList<ItemStack> items;
    private UUID owner;
    private int seq;
    /** 每格的原始入仓时刻 (小时序号): 中途结算不改它, 只有「放进去」那一刻才写。 */
    private long[] entryHours;
    /** 每格本次已经记过的停表小时。 */
    private int[] creditedHours;
    /** 是否正在停表: 要在一座已验收、完好的粮仓里且整个在屋里才算 (本片先由验收分片置位)。 */
    private boolean stopping;
    /** 开放名单 (空 = 私有); 谁放下这个囤谁才能改它。 */
    private List<GranaryAccess.Trustee> access = new ArrayList<>();

    public GranaryBinBlockEntity(BlockPos pos, BlockState state) {
        super(GranaryBlocks.BIN_ENTITY.get(), pos, state);
        this.items = NonNullList.withSize(GranaryConfig.binSlots(), ItemStack.EMPTY);
        this.entryHours = new long[GranaryConfig.binSlots()];
        this.creditedHours = new int[GranaryConfig.binSlots()];
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
    // 开放名单 (每个囤单独设; 空 = 私有)
    // ============================================================

    /** 这个囤的开放名单 (空 = 私有)。返回的是副本, 改它不会动到囤。 */
    public List<GranaryAccess.Trustee> access() {
        return List.copyOf(access);
    }

    /** 整份替换 (读档、套用默认名单、清空都用它)。 */
    public void setAccess(List<GranaryAccess.Trustee> list) {
        this.access = new ArrayList<>(list);
        setChanged();
    }

    /** 加一个人 / 改他的权限; 结果码交给命令回执。 */
    public GranaryAccess.Status addTrustee(UUID player, GranaryAccess.Permission permission) {
        GranaryAccess.Status status = GranaryAccess.add(access, owner, player, permission);
        if (status != GranaryAccess.Status.IS_OWNER && status != GranaryAccess.Status.FULL) {
            setChanged();
        }
        return status;
    }

    /** 移出一个人; 不在名单上返回 false。 */
    public boolean removeTrustee(UUID player) {
        boolean removed = GranaryAccess.remove(access, player);
        if (removed) {
            setChanged();
        }
        return removed;
    }

    // ============================================================
    // 停表 (账目在格子上: 原始入仓时刻 E 与本次已计停表 C)
    // ============================================================

    /** 是否正在停表 (验收并启用之后为真)。 */
    public boolean stopping() {
        return stopping;
    }

    public void setStopping(boolean stopping) {
        this.stopping = stopping;
        setChanged();
    }

    /** 某一格的原始入仓时刻; 0 表示这一格从来没存过东西。 */
    public long entryHour(int slot) {
        return entryHours[slot];
    }

    /** 某一格本次已记的停表小时。 */
    public int creditedHours(int slot) {
        return creditedHours[slot];
    }

    /** 东西放进这一格: 记下入仓时刻, 本趟已计停表清零 (存一夜就取不算停表)。 */
    public void markStored(int slot, long nowHour) {
        entryHours[slot] = nowHour;
        creditedHours[slot] = 0;
        setChanged();
    }

    /** 东西离开这一格: 账目跟着清掉。 */
    public void markTaken(int slot) {
        entryHours[slot] = 0L;
        creditedHours[slot] = 0;
        setChanged();
    }

    /**
     * 把这一格结算到截止时刻: 按「连续存满 24 小时才停表、按存放小时 − 1 记」给物品记停表额度。
     * 不在停表状态时什么都不做 (没验收、损坏过宽限期、没入册的囤都走这条路)。
     *
     * @return 本趟记上的小时数 (0 表示这一趟没记)
     */
    public int settleSlot(int slot, long deadlineHour) {
        ItemStack stack = items.get(slot);
        if (!stopping || stack.isEmpty() || entryHours[slot] == 0L) {
            return 0;
        }
        GranarySettle.Result result = GranarySettle.settle(
                stack, entryHours[slot], creditedHours[slot], deadlineHour);
        creditedHours[slot] = result.creditedHours();
        if (result.grantedHours() > 0) {
            setChanged();
        }
        return result.grantedHours();
    }

    /** 把整个囤结算到截止时刻 (打开界面、停用或损坏之前都要先跑这一趟)。 */
    public int settleAll(long deadlineHour) {
        int granted = 0;
        for (int slot = 0; slot < items.size(); slot++) {
            granted += settleSlot(slot, deadlineHour);
        }
        return granted;
    }

    /**
     * 重新起算: 换一个新的入仓锚点, 本趟已计归零。
     *
     * 用在「损坏过了宽限、后来才修好」这条路上 (规格: 修好后重新存满 24 小时起恢复停表) ——
     * 已经把停表额度记在物品上的部分照旧留着, 但**过宽限那段时间不再补记**。
     */
    public void restartClock(long nowHour) {
        for (int slot = 0; slot < items.size(); slot++) {
            if (!items.get(slot).isEmpty() && entryHours[slot] != 0L) {
                entryHours[slot] = nowHour;
                creditedHours[slot] = 0;
            }
        }
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
        tag.putLongArray(KEY_ENTRY_HOURS, entryHours.clone());
        tag.putIntArray(KEY_CREDITED_HOURS, creditedHours.clone());
        tag.putBoolean(KEY_STOPPING, stopping);
        tag.put(GranaryConstants.KEY_ACCESS, GranaryAccess.save(access));
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
        entryHours = readLongs(tag, KEY_ENTRY_HOURS, size);
        creditedHours = readInts(tag, KEY_CREDITED_HOURS, size);
        stopping = tag.getBoolean(KEY_STOPPING);
        // 名单是后加的字段: 老档没有就是私有 (按空名单读), 内容存在时照旧严格校验。
        access = tag.get(GranaryConstants.KEY_ACCESS) instanceof ListTag list
                ? new ArrayList<>(GranaryAccess.load(list)) : new ArrayList<>();
    }

    /** 格账长度必须与格数一致: 对不上说明存档被改过, 如实报错而不是静默截断。 */
    private static long[] readLongs(CompoundTag tag, String key, int size) {
        if (!tag.contains(key, Tag.TAG_LONG_ARRAY)) {
            return new long[size];
        }
        long[] loaded = tag.getLongArray(key);
        if (loaded.length != size) {
            throw new IllegalArgumentException("Invalid granary bin " + key + " size: " + loaded.length);
        }
        return loaded;
    }

    private static int[] readInts(CompoundTag tag, String key, int size) {
        if (!tag.contains(key, Tag.TAG_INT_ARRAY)) {
            return new int[size];
        }
        int[] loaded = tag.getIntArray(key);
        if (loaded.length != size) {
            throw new IllegalArgumentException("Invalid granary bin " + key + " size: " + loaded.length);
        }
        return loaded;
    }
}
