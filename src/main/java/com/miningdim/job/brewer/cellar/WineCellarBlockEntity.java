package com.miningdim.job.brewer.cellar;

import com.miningdim.job.brewer.BrewerConstants;
import com.miningdim.job.brewer.BrewerItems;
import com.miningdim.job.brewer.WineNbt;
import com.miningdim.job.brewer.WineType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 酒窖箱方块实体 (酿酒师 阶段 4)。12 酒槽 + 1 干小麦燃料槽 ({@link ItemStackHandler}); 服务端 tick 按现实挂钟差
 * 懒结算各酒槽陈酿 (年份增长 / 满月加成 / 干小麦保鲜 / 断粮衰退至变质), 取出即冻结 (酒不在槽里就不结算它)。
 *
 * 现实挂钟权威 (与经济衰减闸 UTC 时间观同源): {@code lastSettleEpochMillis} 持久化, 每
 * {@link BrewerConstants#CELLAR_SETTLE_INTERVAL_TICKS} 唤醒一次取 {@code System.currentTimeMillis()} 差结算;
 * 离线 / 区块卸载期间不 tick, 重新加载时一次性补齐这段挂钟差 (酒窖本就该你不在也熟)。
 *
 * 不挂 getCapability 物品能力 (与调味台同: 反挂机, 漏斗 / 机器无法注入抽取酒或燃料), 故陈酿成果与燃料续添均须
 * 人手开箱操作。燃料交互槽与整数储备共同计入容量。
 */
public final class WineCellarBlockEntity extends BlockEntity implements MenuProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/brewer");

    /** 酒槽数 (索引 [0, CELLAR_WINE_SLOTS))。 */
    public static final int WINE_SLOTS = BrewerConstants.CELLAR_WINE_SLOTS;
    /** 燃料槽索引 (干小麦; 在酒槽之后)。 */
    public static final int FUEL_SLOT = WINE_SLOTS;
    /** 总槽数 (酒槽 + 1 燃料槽)。 */
    public static final int TOTAL_SLOTS = WINE_SLOTS + 1;
    private static final int LEGACY_FUEL_SLOT = 9;

    /** 未结算哨兵: lastSettle 尚未初始化 (新建 / 旧档无此键时, 首次 tick 设为当前挂钟, 不补结算)。 */
    private static final long UNSET = 0L;
    private final NonNullList<ItemStack> displayBottles = NonNullList.withSize(WINE_SLOTS, ItemStack.EMPTY);

    private final ItemStackHandler inventory = new ItemStackHandler(TOTAL_SLOTS) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            if (slot < WINE_SLOTS && level != null && !level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot == FUEL_SLOT) {
                return stack.is(BrewerItems.DRIED_WHEAT.get());
            }
            // 酒槽: 仅接受带酒章的酒 (品质已盖)。
            return WineNbt.isWine(stack);
        }

        @Override
        public int getSlotLimit(int slot) {
            return slot == FUEL_SLOT ? Math.min(64, BrewerConstants.FUEL_SLOT_CAPACITY - fuelReserve) : super.getSlotLimit(slot);
        }

    };

    /** 上次结算的现实挂钟毫秒 (持久化; UNSET=未初始化)。 */
    private long lastSettleEpochMillis = UNSET;

    /** 结余的小数燃料债 (持久化): 单位耗量远小于一次唤醒应耗, 故按小数累加、跨整数才扣整数干小麦 (见 CellarSettle)。 */
    private double fuelDebt = 0.0D;
    private int fuelReserve;
    @Nullable
    private CompoundTag lockedSave;

    public boolean isLocked() {
        return lockedSave != null;
    }

    public WineCellarBlockEntity(BlockPos pos, BlockState state) {
        super(WineCellarRegistry.WINE_CELLAR_BE.get(), pos, state);
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public int fuelTotal() {
        return fuelReserve + inventory.getStackInSlot(FUEL_SLOT).getCount();
    }

    public void depositFuel() {
        if (isLocked()) return;
        ItemStack stack = inventory.getStackInSlot(FUEL_SLOT);
        fuelReserve += stack.getCount();
        inventory.setStackInSlot(FUEL_SLOT, ItemStack.EMPTY);
        setChanged();
    }

    public void withdrawFuel() {
        if (isLocked()) return;
        ItemStack stack = inventory.getStackInSlot(FUEL_SLOT);
        int count = Math.min(fuelReserve, 64 - stack.getCount());
        if (count == 0) return;
        fuelReserve -= count;
        if (stack.isEmpty()) stack = new ItemStack(BrewerItems.DRIED_WHEAT.get(), count);
        else stack.grow(count);
        inventory.setStackInSlot(FUEL_SLOT, stack);
        setChanged();
    }

    public int drainFuelReserve() {
        if (isLocked()) return 0;
        int count = fuelReserve;
        fuelReserve = 0;
        setChanged();
        return count;
    }

    // ---- 服务端 tick: 节流唤醒, 按现实挂钟差懒结算陈酿 ----

    public void serverTick() {
        if (level == null || level.isClientSide) {
            return;
        }
        if (level.getGameTime() % BrewerConstants.CELLAR_SETTLE_INTERVAL_TICKS != 0L) {
            return;
        }

        settleNow();
    }

    /** 玩家操作前先结清旧库存，防止新酒或新补燃料被追溯到上次结算时刻。 */
    public void settleNow() {
        if (isLocked() || level == null || level.isClientSide) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastSettleEpochMillis == UNSET) {
            // 新建 / 刚加载且无记录: 锚定当前挂钟, 本次不补结算 (避免把"放下前"的时间误算进陈酿)。
            lastSettleEpochMillis = now;
            setChanged();
            return;
        }

        long elapsed = now - lastSettleEpochMillis;
        if (elapsed <= 0L) {
            return; // 挂钟未前进 (理论不至于回退; 回退则不结算, 等挂钟追上)。
        }

        settleElapsed(elapsed, level.getMoonPhase());
        lastSettleEpochMillis = now;
        setChanged();
    }

    /**
     * 把一段现实挂钟差结算进各酒槽 (空酒槽不参与 —— 取出即冻结)。收集在槽酒瓶状态交 {@link CellarSettle} 纯函数,
     * 按返回写回各瓶 NBT 年份 / 变质, 并从燃料槽扣对应干小麦。包级可见便于 GameTest 直接驱动同代码路径。
     */
    void settleElapsed(long elapsedMillis, int moonPhase) {
        // 收集非空酒槽 (记录原槽索引以便回写); 空槽冻结不参与。
        List<Integer> slotIndices = new ArrayList<>();
        List<CellarSettle.BottleState> states = new ArrayList<>();
        for (int slot = 0; slot < WINE_SLOTS; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty() || !WineNbt.isWine(stack)) {
                continue;
            }
            slotIndices.add(slot);
            states.add(new CellarSettle.BottleState(WineNbt.readVintage(stack), WineNbt.isSpoiled(stack), stack.getCount()));
        }
        if (states.isEmpty()) {
            return; // 无酒可陈: 不动燃料 (空窖不烧粮)。
        }

        int fuelAvailable = fuelTotal();
        CellarSettle.Result result = CellarSettle.settle(states, elapsedMillis, fuelAvailable, moonPhase, fuelDebt);
        fuelDebt = result.fuelDebt();

        // 年份与变质写在原物品栈中，菜单照常同步；架上展示只取酒种。
        List<CellarSettle.BottleState> out = result.bottles();
        for (int i = 0; i < slotIndices.size(); i++) {
            int slot = slotIndices.get(i);
            ItemStack stack = inventory.getStackInSlot(slot);
            CellarSettle.BottleState ns = out.get(i);
            WineNbt.setVintage(stack, ns.vintage());
            WineNbt.setSpoiled(stack, ns.spoiled());
        }

        // 扣燃料 (结算耗量不会超过 fuelAvailable, 纯函数已封顶在燃料池内)。
        if (result.fuelConsumed() > 0) {
            int fromReserve = Math.min(fuelReserve, result.fuelConsumed());
            fuelReserve -= fromReserve;
            ItemStack fuel = inventory.getStackInSlot(FUEL_SLOT);
            fuel.shrink(result.fuelConsumed() - fromReserve);
            inventory.setStackInSlot(FUEL_SLOT, fuel.isEmpty() ? ItemStack.EMPTY : fuel);
        }
    }

    // ---- MenuProvider ----

    @Override
    public Component getDisplayName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory inv, Player player) {
        if (isLocked()) return null;
        settleNow();
        return new WineCellarMenu(windowId, inv, this);
    }

    // ---- 持久化 (inventory + 上次结算挂钟) ----

    private static final String K_INV = "Inventory";
    private static final String K_LAST_SETTLE = "LastSettleEpochMillis";
    private static final String K_FUEL_DEBT = "FuelDebt";
    private static final String K_FUEL_RESERVE = "FuelReserve";

    @Override
    protected void saveAdditional(CompoundTag tag) {
        if (lockedSave != null) {
            tag.merge(lockedSave);
            return;
        }
        super.saveAdditional(tag);
        tag.put(K_INV, inventory.serializeNBT());
        tag.putLong(K_LAST_SETTLE, lastSettleEpochMillis);
        tag.putDouble(K_FUEL_DEBT, fuelDebt);
        tag.putInt(K_FUEL_RESERVE, fuelReserve);
    }

    @Override
    public void load(CompoundTag tag) {
        CompoundTag savedInventory = tag.getCompound(K_INV);
        if (tag.contains(K_FUEL_RESERVE) && !tag.contains(K_FUEL_RESERVE, Tag.TAG_INT)) {
            lockSave(tag, "FuelReserve must be an integer");
            return;
        }
        // 九酒槽版本由 Forge 写入 Size=10，最后一格是干小麦。只迁移该版本的库存。
        if (!tag.contains(K_FUEL_RESERVE) && savedInventory.contains("Size", Tag.TAG_INT)
                && savedInventory.getInt("Size") == LEGACY_FUEL_SLOT + 1) {
            savedInventory = savedInventory.copy();
            ListTag entries = savedInventory.getList("Items", Tag.TAG_COMPOUND);
            if (!savedInventory.contains("Items", Tag.TAG_LIST)
                    || entries.size() != ((ListTag) savedInventory.get("Items")).size()) {
                lockSave(tag, "invalid legacy inventory contents");
                return;
            }
            boolean[] occupied = new boolean[LEGACY_FUEL_SLOT + 1];
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag entry = entries.getCompound(i);
                int slot = entry.getInt("Slot");
                if (!entry.contains("Slot", Tag.TAG_INT) || slot < 0 || slot > LEGACY_FUEL_SLOT
                        || occupied[slot]) {
                    lockSave(tag, "invalid legacy inventory slot");
                    return;
                }
                occupied[slot] = true;
                if (slot == LEGACY_FUEL_SLOT) {
                    entry.putInt("Slot", FUEL_SLOT);
                }
            }
            savedInventory.putInt("Size", TOTAL_SLOTS);
        } else if (savedInventory.contains("Size") && savedInventory.getInt("Size") != TOTAL_SLOTS) {
            lockSave(tag, "unexpected Inventory.Size");
            return;
        }
        super.load(tag);
        inventory.deserializeNBT(savedInventory);
        // 旧档没有整数储备，缺键表示旧版初始状态。
        int reserve = tag.getInt(K_FUEL_RESERVE);
        ItemStack legacyFuel = inventory.getStackInSlot(FUEL_SLOT);
        if (!legacyFuel.isEmpty() && !legacyFuel.is(BrewerItems.DRIED_WHEAT.get())) {
            lockSave(tag, "unexpected fuel slot item");
            return;
        }
        if (tag.contains(K_FUEL_RESERVE) && legacyFuel.getCount() > 64) {
            lockSave(tag, "fuel slot exceeds 64");
            return;
        }
        if (reserve < 0 || (long) reserve + legacyFuel.getCount() > BrewerConstants.FUEL_SLOT_CAPACITY) {
            lockSave(tag, "fuel total outside supported range");
            return;
        }
        fuelReserve = reserve;
        if (!tag.contains(K_FUEL_RESERVE) && legacyFuel.getCount() > 64) {
            fuelReserve = legacyFuel.getCount() - 64;
            legacyFuel.setCount(64);
        }
        // 旧档无此键 -> UNSET, 首次 tick 锚定当前挂钟 (不把缺键误算成 1970 起的巨量陈酿)。
        lastSettleEpochMillis = isLocked() ? UNSET : tag.getLong(K_LAST_SETTLE);
        lockedSave = null;

        // F027 修复后的结算算法保证 debt 恒落在 [0,1) (见 CellarSettle 类 javadoc 不变式)。旧档里 >=1 的债是
        // 本缺陷 (燃料债无上限累加) 累积出的产物: 强行让玩家偿还一笔本不该存在的欠款只会把缺陷后果延续下去,
        // 故不采信, 直接归零并留痕方便排查异常存档。
        double loadedFuelDebt = tag.getDouble(K_FUEL_DEBT); // 缺键默认 0.0。
        if (loadedFuelDebt >= 0.0D && loadedFuelDebt < 1.0D) {
            fuelDebt = loadedFuelDebt;
        } else {
            fuelDebt = 0.0D;
            LOGGER.warn("[miningdim] wine cellar at {} had out-of-invariant fuel debt {} (pre-F027 artifact); discarded to 0.0",
                    worldPosition, loadedFuelDebt);
        }
    }

    private void lockSave(CompoundTag original, String reason) {
        lockedSave = original.copy();
        LOGGER.error("Wine cellar at {} locked ({}). Original block entity NBT retained for repair.",
                worldPosition, reason);
    }

    @Override
    public AABB getRenderBoundingBox() {
        AABB main = new AABB(worldPosition);
        BlockState state = getBlockState();
        if (state.getValue(WineCellarBlock.PART) != WineCellarBlock.Part.MAIN) return main;
        return main.minmax(new AABB(WineCellarBlock.partPos(worldPosition, state.getValue(WineCellarBlock.FACING),
                WineCellarBlock.Part.SIDE)));
    }

    public ItemStack displayedBottle(int slot) {
        return displayBottles.get(slot);
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag display = new CompoundTag();
        if (!isLocked()) {
            for (int slot = 0; slot < WINE_SLOTS; slot++) {
                ItemStack stack = inventory.getStackInSlot(slot);
                WineType wine = BrewerItems.typeOf(stack);
                if (wine != null && WineNbt.isWine(stack)) display.putString(Integer.toString(slot), wine.id());
            }
        }
        CompoundTag update = new CompoundTag();
        update.put("DisplayBottles", display);
        return update;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        CompoundTag display = tag.getCompound("DisplayBottles");
        for (int slot = 0; slot < WINE_SLOTS; slot++) {
            WineType wine = WineType.fromId(display.getString(Integer.toString(slot)));
            displayBottles.set(slot, wine == null ? ItemStack.EMPTY : new ItemStack(BrewerItems.itemFor(wine)));
        }
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) handleUpdateTag(tag);
    }

    /** 管理指令或结构替换可以跳过挖掘检查。原始内容随世界存入原版指令存储，供管理员恢复。 */
    void archiveRemovedSave(ServerLevel level) {
        if (lockedSave == null) return;
        ResourceLocation key = new ResourceLocation("miningdim", "locked_wine_cellar/" + UUID.randomUUID());
        level.getServer().getCommandStorage().set(key, lockedSave.copy());
        LOGGER.error("Removed locked wine cellar in {} at {}. Recovery NBT: /data get storage {}",
                level.dimension().location(), worldPosition, key);
    }
}
