package com.miningdim.job.engineer.block;

import com.miningdim.job.engineer.EngineerConfig;
import com.miningdim.job.engineer.EngineerLevels;
import com.miningdim.job.engineer.ModEngineerBlockEntities;
import com.miningdim.job.engineer.NanoCalibration;
import com.miningdim.job.engineer.NanoNbt;
import com.miningdim.job.engineer.NanoProduction;
import com.miningdim.job.engineer.NanoTier;
import com.miningdim.job.engineer.menu.ProductionTableMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RangedWrapper;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 生产台方块实体 (MillenniumEngineer_Mod_DesignSpec 四 / 九 / 10.4)。持:
 *  - ownerUUID + locked (9.1 上锁; 访问控制);
 *  - 输入矿石槽 (slot 0) + 输出板槽 (slot 1) (4.1);
 *  - 选中目标档 selectedTier (clickMenuButton 选, 服务端重校三道门);
 *  - {@link NanoCalibration} QTE 时序状态 (4.2);
 *  - {@link ContainerData} 同步 进度/品质/游标/绿区/锁/机器档 给开 GUI 者 (10.5 int-only)。
 *
 * 反漏斗 (9.1): {@link #getCapability} 对任何 side 只暴露 "输入只写" 包装, 输出板槽不对漏斗暴露
 * (RangedWrapper 限定 [0,1) 即仅输入槽), 保证 "必须人手取" 的结算前提。
 *
 * 10.4 BE 无玩家上下文: tick 内不读玩家等级; 等级判定收敛到交互帧 (Menu.clickMenuButton 由 ServerPlayer 读)。
 * serverTick 只推进 QTE 游标 + (校准完成后) 结算产物到输出槽。生产经验在玩家手取输出时结算 (Menu.onTake)。
 */
public final class ProductionTableBlockEntity extends BlockEntity implements MenuProvider {

    /** 槽位: 0=输入矿石, 1=输出护甲板。 */
    public static final int SLOT_INPUT = 0;
    public static final int SLOT_OUTPUT = 1;
    private static final int SLOT_COUNT = 2;

    /** ContainerData 索引 (开 GUI 者实时同步; int-only)。 */
    public static final int DATA_PROGRESS = 0;
    public static final int DATA_QUALITY = 1;
    public static final int DATA_CURSOR = 2;
    public static final int DATA_GREEN = 3;
    public static final int DATA_LOCKED = 4;
    public static final int DATA_MACHINE_TIER = 5;
    public static final int DATA_SELECTED_TIER = 6;
    private static final int DATA_COUNT = 7;

    /** ContainerData 槽数 (Menu 客户端侧建同尺寸 SimpleContainerData 用)。 */
    public static int DATA_COUNT() {
        return DATA_COUNT;
    }

    @Nullable
    private UUID ownerUUID;
    private boolean locked;

    /** 当前选中目标档; null = 未选 (不生产)。 */
    @Nullable
    private NanoTier selectedTier;

    private final NanoCalibration calibration = new NanoCalibration();

    private final ItemStackHandler inventory = new ItemStackHandler(SLOT_COUNT) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            // 输入槽变动 (换矿/取空) 时中断进行中校准, 避免投机改矿。
            if (slot == SLOT_INPUT && calibration.isActive()) {
                calibration.reset();
                selectedTier = null;
            }
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            // 输出槽不接受外部放入 (只由 BE 写); 输入槽接受任意 (矿种由选档时校验)。
            return slot == SLOT_INPUT;
        }
    };

    /** 反漏斗暴露: 任何 side 只给输入槽只写包装 (RangedWrapper 限定 [0,1)), 输出槽不暴露 (9.1)。 */
    private final LazyOptional<IItemHandler> inputOnlyHandler =
            LazyOptional.of(() -> new RangedWrapper(inventory, SLOT_INPUT, SLOT_INPUT + 1));

    private final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case DATA_PROGRESS -> calibration.progress();
                case DATA_QUALITY -> calibration.qualityHits();
                case DATA_CURSOR -> calibration.cursor();
                case DATA_GREEN -> calibration.greenStart();
                case DATA_LOCKED -> locked ? 1 : 0;
                case DATA_MACHINE_TIER -> machineTier().index();
                case DATA_SELECTED_TIER -> selectedTier == null ? -1 : selectedTier.index();
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            // 客户端不写权威状态 (服务端权威); ContainerData.set 仅 vanilla 同步用, 留空。
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    };

    public ProductionTableBlockEntity(BlockPos pos, BlockState state) {
        super(ModEngineerBlockEntities.PRODUCTION_TABLE.get(), pos, state);
    }

    public NanoTier machineTier() {
        if (getBlockState().getBlock() instanceof ProductionTableBlock table) {
            return table.machineTier();
        }
        throw new IllegalStateException(
                "ProductionTableBlockEntity attached to non-table block at " + worldPosition);
    }

    public ContainerData dataAccess() {
        return dataAccess;
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public NanoCalibration calibration() {
        return calibration;
    }

    // ---- 上锁 / 归属 (9.1) ----

    public void setOwner(UUID owner) {
        this.ownerUUID = owner;
        setChanged();
    }

    public boolean isOwner(Player player) {
        return ownerUUID != null && ownerUUID.equals(player.getUUID());
    }

    /** 切锁 (仅主人调用前置已校验), 返回切换后的锁状态。 */
    public boolean toggleLocked() {
        this.locked = !this.locked;
        setChanged();
        return this.locked;
    }

    public boolean isLocked() {
        return locked;
    }

    /** 是否允许该玩家访问 (开 GUI / 取物): 未锁恒允许; 锁定时仅主人或 OP (9.1)。 */
    public boolean canAccess(ServerPlayer player) {
        if (!locked) {
            return true;
        }
        return isOwner(player) || player.hasPermissions(2);
    }

    // ---- 选档 (服务端权威重校三道门; 4.1) ----

    /**
     * 处理选档 (Menu.clickMenuButton 委派, 服务端权威)。三道门全过才接受 + 开始一轮校准; 否则拒绝 (不改状态)。
     * 客户端置灰仅提示, 此处不信客户端 (C5): 即便发来已置灰档位也重校。
     *
     * @return 选档是否被接受
     */
    public boolean trySelectTier(NanoTier target, ServerPlayer player) {
        if (!canAccess(player)) {
            return false; // 锁定且非主人/OP: 拒绝。
        }
        // 门一: 矿石档。输入矿种允许的最高档 >= target。
        NanoTier maxByOre = NanoTier.maxTierForOre(inventory.getStackInSlot(SLOT_INPUT));
        if (!target.allowedByOre(maxByOre)) {
            return false;
        }
        // 门二: 工程师等级。
        if (!EngineerLevels.isTierUnlocked(EngineerLevels.engineerLevel(player), target)) {
            return false;
        }
        // 门三: 机器档。target.index <= machineTier.index。
        if (target.index() > machineTier().index()) {
            return false;
        }
        // 矿石足量 (单板消耗)。
        if (inventory.getStackInSlot(SLOT_INPUT).getCount() < target.oreCost()) {
            return false;
        }
        // 输出槽必须能容纳 (空或同物且未满); 简化: 仅当输出空时允许开新一轮, 防混档堆叠。
        if (!inventory.getStackInSlot(SLOT_OUTPUT).isEmpty()) {
            return false;
        }

        this.selectedTier = target;
        if (level != null) {
            calibration.begin(level.getRandom());
        }
        setChanged();
        return true;
    }

    /** 处理一次校准点击 (Menu.clickMenuButton 委派; 服务端判窗口内才算命中)。完成则结算产物。 */
    public void onCalibrationClick(ServerPlayer player) {
        if (selectedTier == null || !calibration.isActive() || !canAccess(player)) {
            return;
        }
        boolean done = calibration.onClick();
        if (done) {
            finishProduction(player);
        }
        setChanged();
    }

    /** 校准完成: 重校矿石足量后扣矿、产板 (盖生产者章)、退残骸, 重置 QTE。 */
    private void finishProduction(ServerPlayer player) {
        NanoTier tier = selectedTier;
        ItemStack ore = inventory.getStackInSlot(SLOT_INPUT);
        // 完成帧重校矿石足量 (中途换矿已被 onContentsChanged 中断, 此为双保险)。
        if (tier == null || ore.getCount() < tier.oreCost()) {
            calibration.reset();
            selectedTier = null;
            return;
        }
        // 本轮品质命中数: reset 前快照, 写入板供取出结算经验 + 修甲掷特效还原品质 (4.2/7.4 品质杠杆)。
        int qualityHits = calibration.qualityHits();
        NanoProduction.Result result = NanoProduction.resolve(tier, qualityHits,
                level == null ? net.minecraft.util.RandomSource.create() : level.getRandom());

        // 先收尾本轮校准, 再改输入槽 —— 否则 setStackInSlot 触发的 onContentsChanged 会因 calibration 仍 active
        // 而再次 reset (无害但混淆); 先 reset 使 onContentsChanged 走 "已结束" 短路。
        calibration.reset();
        selectedTier = null;

        ore.shrink(result.oreConsumed());
        inventory.setStackInSlot(SLOT_INPUT, ore.isEmpty() ? ItemStack.EMPTY : ore);

        // 闪耀失败残骸返还: 优先退到输入槽 (空槽新建 / 同为下界合金锭则叠加); 槽被异物占用的时序边界改掉落不静默吞。
        if (result.debrisRefund() > 0) {
            refundDebris(result.debrisRefund());
        }
        if (result.platesProduced() > 0) {
            ItemStack plates = NanoProduction.makePlate(tier, result.platesProduced(), player.getUUID(), qualityHits);
            inventory.setStackInSlot(SLOT_OUTPUT, plates);
        }
    }

    private void refundDebris(int amount) {
        ItemStack input = inventory.getStackInSlot(SLOT_INPUT);
        if (input.isEmpty()) {
            inventory.setStackInSlot(SLOT_INPUT, new ItemStack(Items.NETHERITE_INGOT, amount));
            return;
        }
        if (input.is(Items.NETHERITE_INGOT)) {
            input.grow(amount);
            inventory.setStackInSlot(SLOT_INPUT, input);
            return;
        }
        // 输入槽被别的物占用 (选档门已确保闪耀必为下界合金锭, 此为完成帧与输入变动间的时序边界): 不静默吞下界
        // 合金残骸, 改为掉落到方块上方, 让玩家可拾回 (下界合金高代价, 宁可显形也不丢失)。
        if (level != null && !level.isClientSide) {
            net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(
                    level,
                    worldPosition.getX() + 0.5,
                    worldPosition.getY() + 1.0,
                    worldPosition.getZ() + 0.5,
                    new ItemStack(Items.NETHERITE_INGOT, amount));
            level.addFreshEntity(drop);
        }
    }

    /**
     * 玩家手取输出板时结算生产经验 (7.4 / 9.3): 取出者 UUID == 板 producerUUID 且 productionXpPending,
     * 给取出者工程师经验; 取走即清 pending (防塞回再取重复刷)。由 {@link ProductionTableMenu} 的输出槽 onTake 调。
     */
    public void onOutputTaken(ServerPlayer player, ItemStack taken) {
        if (taken.isEmpty()) {
            return;
        }
        if (!NanoNbt.isProductionXpPending(taken)) {
            return; // 已结算过 (塞回再取) 或非待结算板。
        }
        boolean match = NanoNbt.producer(taken).map(p -> p.equals(player.getUUID())).orElse(false);
        if (match) {
            // 取出者即生产者: 按板档结算生产原始经验 (经框架衰减入账)。品质杠杆 (4.2/7.4): 品质越高该板
            // 携带的原始经验越高 (xpMult = 1 + coef*qualityHits), 与产量 +1、特效概率并列三条品质结算之一。
            int qualityHits = NanoNbt.qualityHits(taken);
            double xpMult = 1.0 + EngineerConfig.PRODUCTION_XP_QUALITY_COEF.get() * qualityHits;
            for (NanoTier tier : NanoTier.values()) {
                if (taken.is(com.miningdim.job.engineer.ModEngineerItems.plate(tier).get())) {
                    long raw = (long) Math.floor(tier.rawXp() * xpMult) * taken.getCount();
                    EngineerLevels.grantRawXp(player, raw);
                    break;
                }
            }
        }
        // 取走即清 (无论是否匹配, 防塞回刷)。
        NanoNbt.clearProductionXpPending(taken);
    }

    // ---- tick (10.4 无玩家上下文; 只推进 QTE 游标) ----

    public void serverTick() {
        if (level != null && calibration.isActive()) {
            calibration.serverTick(level.getRandom());
        }
    }

    // ---- 反漏斗 capability (9.1) ----

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return inputOnlyHandler.cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        inputOnlyHandler.invalidate();
    }

    // ---- MenuProvider ----

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.miningdim.production_table_" + machineTier().name().toLowerCase());
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory inv, Player player) {
        return new ProductionTableMenu(windowId, inv, worldPosition);
    }

    // ---- 持久化 ----

    private static final String K_OWNER = "Owner";
    private static final String K_LOCKED = "Locked";
    private static final String K_INV = "Inv";
    private static final String K_SELECTED = "SelectedTier";
    private static final String K_CALIBRATION = "Calibration";

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (ownerUUID != null) {
            tag.putUUID(K_OWNER, ownerUUID);
        }
        tag.putBoolean(K_LOCKED, locked);
        tag.put(K_INV, inventory.serializeNBT());
        tag.putInt(K_SELECTED, selectedTier == null ? -1 : selectedTier.index());
        tag.put(K_CALIBRATION, calibration.serializeNBT());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        ownerUUID = tag.hasUUID(K_OWNER) ? tag.getUUID(K_OWNER) : null;
        locked = tag.getBoolean(K_LOCKED);
        if (tag.contains(K_INV)) {
            inventory.deserializeNBT(tag.getCompound(K_INV));
        }
        int sel = tag.contains(K_SELECTED) ? tag.getInt(K_SELECTED) : -1;
        selectedTier = sel < 0 ? null : NanoTier.byIndex(sel);
        if (tag.contains(K_CALIBRATION)) {
            calibration.deserializeNBT(tag.getCompound(K_CALIBRATION));
        }
    }
}
