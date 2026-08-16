package com.miningdim.job.brewer.cellar;

import com.miningdim.job.brewer.BrewerConstants;
import com.miningdim.job.brewer.BrewerItems;
import com.miningdim.job.brewer.WineNbt;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 酒窖箱方块实体 (酿酒师 阶段 4)。9 酒槽 + 1 干小麦燃料槽 ({@link ItemStackHandler}); 服务端 tick 按现实挂钟差
 * 懒结算各酒槽陈酿 (年份增长 / 满月加成 / 干小麦保鲜 / 断粮衰退至变质), 取出即冻结 (酒不在槽里就不结算它)。
 *
 * 现实挂钟权威 (与经济衰减闸 UTC 时间观同源): {@code lastSettleEpochMillis} 持久化, 每
 * {@link BrewerConstants#CELLAR_SETTLE_INTERVAL_TICKS} 唤醒一次取 {@code System.currentTimeMillis()} 差结算;
 * 离线 / 区块卸载期间不 tick, 重新加载时一次性补齐这段挂钟差 (酒窖本就该你不在也熟)。
 *
 * 不挂 getCapability 物品能力 (与调味台同: 反挂机, 漏斗 / 机器无法注入抽取酒或燃料), 故陈酿成果与燃料续添均须
 * 人手开箱操作。燃料槽单槽容量顶到 {@link BrewerConstants#FUEL_SLOT_CAPACITY} (F027 二段修复), 使人手一次
 * 顶满即可覆盖满窖停在闪耀主线门槛年份时一个结算步的满额应耗 —— 抬高的是"一次能扛多久", 不是自动化程度。
 */
public final class WineCellarBlockEntity extends BlockEntity implements MenuProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/brewer");

    /** 酒槽数 (索引 [0, CELLAR_WINE_SLOTS))。 */
    public static final int WINE_SLOTS = BrewerConstants.CELLAR_WINE_SLOTS;
    /** 燃料槽索引 (干小麦; 在酒槽之后)。 */
    public static final int FUEL_SLOT = WINE_SLOTS;
    /** 总槽数 (酒槽 + 1 燃料槽)。 */
    public static final int TOTAL_SLOTS = WINE_SLOTS + 1;

    /** 未结算哨兵: lastSettle 尚未初始化 (新建 / 旧档无此键时, 首次 tick 设为当前挂钟, 不补结算)。 */
    private static final long UNSET = 0L;

    private final ItemStackHandler inventory = new ItemStackHandler(TOTAL_SLOTS) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
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
            // F027 二段修复: 燃料槽顶到 FUEL_SLOT_CAPACITY (与 BrewerItems.DRIED_WHEAT 的 stacksTo 配套,
            // Forge ItemStackHandler.getStackLimit 取两者较小值, 缺一实际生效上限仍卡在默认 64); 酒槽维持
            // 默认 (由 WineItem 自身 stacksTo(16) 及同 NBT 才可堆叠的天然分栈约束, 不需要单独放宽)。
            return slot == FUEL_SLOT ? BrewerConstants.FUEL_SLOT_CAPACITY : super.getSlotLimit(slot);
        }
    };

    /** 上次结算的现实挂钟毫秒 (持久化; UNSET=未初始化)。 */
    private long lastSettleEpochMillis = UNSET;

    /** 结余的小数燃料债 (持久化): 单位耗量远小于一次唤醒应耗, 故按小数累加、跨整数才扣整数干小麦 (见 CellarSettle)。 */
    private double fuelDebt = 0.0D;

    public WineCellarBlockEntity(BlockPos pos, BlockState state) {
        super(WineCellarRegistry.WINE_CELLAR_BE.get(), pos, state);
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    // ---- 服务端 tick: 节流唤醒, 按现实挂钟差懒结算陈酿 ----

    public void serverTick() {
        if (level == null || level.isClientSide) {
            return;
        }
        if (level.getGameTime() % BrewerConstants.CELLAR_SETTLE_INTERVAL_TICKS != 0L) {
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
            states.add(new CellarSettle.BottleState(WineNbt.readVintage(stack), WineNbt.isSpoiled(stack)));
        }
        if (states.isEmpty()) {
            return; // 无酒可陈: 不动燃料 (空窖不烧粮)。
        }

        int fuelAvailable = inventory.getStackInSlot(FUEL_SLOT).getCount();
        CellarSettle.Result result = CellarSettle.settle(states, elapsedMillis, fuelAvailable, moonPhase, fuelDebt);
        fuelDebt = result.fuelDebt();

        // 回写各瓶新年份 / 变质到对应酒槽。setStackInSlot 触发 onContentsChanged (NBT 原地改不触发改变钩子,
        // 显式回写以与工程师生产台同范式, 确保槽变更被同步)。
        List<CellarSettle.BottleState> out = result.bottles();
        for (int i = 0; i < slotIndices.size(); i++) {
            int slot = slotIndices.get(i);
            ItemStack stack = inventory.getStackInSlot(slot);
            CellarSettle.BottleState ns = out.get(i);
            WineNbt.setVintage(stack, ns.vintage());
            WineNbt.setSpoiled(stack, ns.spoiled());
            inventory.setStackInSlot(slot, stack);
        }

        // 扣燃料 (结算耗量不会超过 fuelAvailable, 纯函数已封顶在燃料池内)。
        if (result.fuelConsumed() > 0) {
            ItemStack fuel = inventory.getStackInSlot(FUEL_SLOT);
            fuel.shrink(result.fuelConsumed());
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
        return new WineCellarMenu(windowId, inv, this);
    }

    // ---- 持久化 (inventory + 上次结算挂钟) ----

    private static final String K_INV = "Inventory";
    private static final String K_LAST_SETTLE = "LastSettleEpochMillis";
    private static final String K_FUEL_DEBT = "FuelDebt";

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put(K_INV, inventory.serializeNBT());
        tag.putLong(K_LAST_SETTLE, lastSettleEpochMillis);
        tag.putDouble(K_FUEL_DEBT, fuelDebt);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains(K_INV)) {
            inventory.deserializeNBT(tag.getCompound(K_INV));
        }
        // 旧档无此键 -> UNSET, 首次 tick 锚定当前挂钟 (不把缺键误算成 1970 起的巨量陈酿)。
        lastSettleEpochMillis = tag.contains(K_LAST_SETTLE) ? tag.getLong(K_LAST_SETTLE) : UNSET;

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
}
