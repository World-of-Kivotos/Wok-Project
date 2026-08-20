package com.miningdim.power.storage;

import com.miningdim.power.PowerLitDisplay;
import com.miningdim.power.PowerRegistry;
import com.miningdim.power.grid.VoltageAwareEnergyStorage;
import com.miningdim.power.grid.VoltageClass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 三级储电的控制器。储电是把开环发电系统闭合起来的枢纽：发电是连续的、消费是脉冲的
 * （造弹按小时结算、护甲按战斗触发），两者时间尺度不匹配，中间必须有缓冲。
 *
 * 整数溢出铁律：内部余额一律用 long。单个储电的容量被配置上限压在 int 安全区内，但
 * "把网络里所有储电加起来" 这类统计极易越界（三个三级储电就是 26.5 亿，已超 int 上限），
 * 因此凡是跨端点求和的调用方必须拿 {@link #storedFeLong()} 而不是 capability 的 int 值。
 *
 * 本方块只持有自己的那一份余额；相邻同档储电由 {@link PowerCellGroupManager} 聚合成
 * {@link PowerCellGroup}，容量、速率与界面读数一律按整组结算，一次读写再由组均摊回各成员。
 * 拆掉一块就带走它自己那份：三张储电战利品表用 {@code minecraft:copy_nbt} 把 storedFe 拷进掉落物的
 * {@code BlockEntityTag}，放回去时由原版 BlockItem 回写，因此 NBT 格式不必为分组改动。
 */
public final class PowerCellBlockEntity extends BlockEntity implements MenuProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/power");
    private static final String K_STORED = "storedFe";

    private final PowerCellSpec spec;
    private final CellStorage energy = new CellStorage();
    private final LazyOptional<VoltageAwareEnergyStorage> energyCap = LazyOptional.of(() -> energy);

    private long storedFe;
    /** 分组索引里查不到本坐标时只告警一次；四个 capability 读写口都在每 tick 的结算路径上，不去重就是刷屏。 */
    private boolean reportedMissingGroup;
    private int receivedThisTick;
    private int extractedThisTick;
    private int lastReceivedFe;
    private int lastExtractedFe;
    /** LIT 熄灭前的剩余宽限, 见 {@link PowerLitDisplay}: 进出流量断续时不让贴图每 tick 翻。 */
    private int litGraceTicks;

    public PowerCellBlockEntity(BlockPos pos, BlockState state) {
        super(PowerRegistry.POWER_CELL_BE.get(), pos, state);
        this.spec = PowerCellBlock.specOf(state.getBlock());
    }

    public PowerCellSpec spec() {
        return spec;
    }

    public PowerCellSpec.Runtime runtime() {
        return spec.runtime();
    }

    /** 跨端点求和必须用这个而不是 capability 的 int 读数，否则多储电统计会溢出。 */
    public long storedFeLong() {
        return storedFe;
    }

    public int storedFe() {
        return saturate(storedFe);
    }

    public int capacityFe() {
        return runtime().capacityFe();
    }

    public int lastReceivedFe() {
        return lastReceivedFe;
    }

    public int lastExtractedFe() {
        return lastExtractedFe;
    }

    /**
     * 本方块所属组；服务端上分组索引由 onLoad/setRemoved 维护。查不到返回 null 而不是抛异常。
     *
     * 理由是本方法挂在电网每 tick 的结算路径上（{@link CellStorage} 的四个口全都经过它），而"方块实体
     * 已经能被解析、onLoad 却还没跑"是 Forge 真实存在的窗口：从磁盘加载的区块走 addFreshBlockEntities，
     * onLoad 由 Level.tickBlockEntities 开头统一补，tick 中途加载的还要再推迟一整 tick。在那个窗口里抛
     * 异常就是拿一次区块加载时序换服务端崩溃。与线缆子系统对齐：查不到网就按"尚未接入"读，不炸。
     */
    @Nullable
    private PowerCellGroup group(ServerLevel serverLevel) {
        PowerCellGroup group = PowerCellGroupManager.get(serverLevel).groupAt(worldPosition);
        if (group == null) {
            if (!reportedMissingGroup) {
                reportedMissingGroup = true;
                LOGGER.warn("power cell at {} in {} is not registered in any group; reading it as a standalone cell",
                        worldPosition, serverLevel.dimension().location());
            }
            return null;
        }
        reportedMissingGroup = false;
        return group;
    }

    /** 聚合读数是服务端权威：客户端没有分组索引，界面值经菜单数据槽单向下发。 */
    private ServerLevel requireServerLevel() {
        if (level instanceof ServerLevel serverLevel) {
            return serverLevel;
        }
        throw new IllegalStateException("power cell group readings are server authoritative, queried at "
                + worldPosition);
    }

    /** 尚未入组时全部聚合读数退化成"成员数为 1 的组"，即本方块自身的数值。 */
    public int groupSize() {
        PowerCellGroup group = group(requireServerLevel());
        return group == null ? 1 : group.size();
    }

    public long groupStoredFe() {
        ServerLevel serverLevel = requireServerLevel();
        PowerCellGroup group = group(serverLevel);
        return group == null ? storedFe : group.storedFe(serverLevel);
    }

    public long groupCapacityFe() {
        ServerLevel serverLevel = requireServerLevel();
        PowerCellGroup group = group(serverLevel);
        return group == null ? runtime().capacityFe() : group.capacityFe();
    }

    public long groupLastReceivedFe() {
        ServerLevel serverLevel = requireServerLevel();
        PowerCellGroup group = group(serverLevel);
        return group == null ? lastReceivedFe : group.lastReceivedFe(serverLevel);
    }

    public long groupLastExtractedFe() {
        ServerLevel serverLevel = requireServerLevel();
        PowerCellGroup group = group(serverLevel);
        return group == null ? lastExtractedFe : group.lastExtractedFe(serverLevel);
    }

    public long groupTransferFePerTick() {
        ServerLevel serverLevel = requireServerLevel();
        PowerCellGroup group = group(serverLevel);
        return group == null ? runtime().transferFePerTick() : group.transferFePerTick();
    }

    /** 每 tick 把进出量结算成上一 tick 的快照，界面据此显示实时功率而不是累计值。 */
    public void serverTick() {
        if (!(level instanceof ServerLevel)) {
            return;
        }
        if (lastReceivedFe != receivedThisTick || lastExtractedFe != extractedThisTick) {
            lastReceivedFe = receivedThisTick;
            lastExtractedFe = extractedThisTick;
        }
        if (lastReceivedFe > 0 || lastExtractedFe > 0) {
            litGraceTicks = PowerLitDisplay.GRACE_TICKS;
        } else if (litGraceTicks > 0) {
            litGraceTicks--;
        }
        PowerLitDisplay.apply(level, worldPosition, getBlockState(), PowerCellBlock.LIT, litGraceTicks > 0);
        receivedThisTick = 0;
        extractedThisTick = 0;
    }

    /** 玩家手动给随身装备充电用的抽取口, 走整组账本但不吃电网的每 tick 速率额度, 理由见 PowerCellGroup。 */
    public int extractForCharging(int maxExtract, boolean simulate) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return 0;
        }
        PowerCellGroup group = group(serverLevel);
        return group == null ? 0 : group.extractForCharging(serverLevel, maxExtract, simulate);
    }

    /** 组把一次读写均摊到成员后落到这里；越界即组的分摊算错了，必须当场炸而不是悄悄改数。 */
    void addShare(long amount) {
        long capacity = runtime().capacityFe();
        if (amount <= 0L || storedFe + amount > capacity) {
            throw new IllegalArgumentException("invalid power cell share " + amount + " at " + worldPosition
                    + "; stored=" + storedFe + ", capacity=" + capacity);
        }
        storedFe += amount;
        receivedThisTick = Math.addExact(receivedThisTick, Math.toIntExact(amount));
        setChanged();
    }

    void removeShare(long amount) {
        if (amount <= 0L || amount > storedFe) {
            throw new IllegalArgumentException("invalid power cell draw " + amount + " at " + worldPosition
                    + "; stored=" + storedFe);
        }
        storedFe -= amount;
        extractedThisTick = Math.addExact(extractedThisTick, Math.toIntExact(amount));
        setChanged();
    }

    /**
     * 对外暴露 Forge 的 int 版 IEnergyStorage 时的饱和截断。单块容量压在 int 安全区内，但组容量是线性
     * 叠加的（三块未来储电 26.5 亿已越界），这里的饱和是真正会被走到的路径，不是防御性写法。
     */
    private static int saturate(long value) {
        return (int) Math.max(0L, Math.min(value, Integer.MAX_VALUE));
    }

    /**
     * 组容量越过 int 上限后，余额与容量若各自独立饱和截断，两者会同时钉在 Integer.MAX_VALUE：第三方按
     * Forge 的事实约定算 {@code max - stored} 得 0，会判定这组储电已满而停止推电，尽管组里还有数亿 FE
     * 的空间。int 窗口装不下真实值时无法同时忠实表达存量与余量，故按比例整体缩放，并守住两个端点的语义
     * ——还剩一点电就不许读成空，还剩一点空间就不许读成满，否则收发两个方向会各自被卡死一个。
     *
     * <p>缩放走 double：真实容量上限是 64 块未来储电共 566 亿 FE，与 Integer.MAX_VALUE 相乘会溢出 long，
     * 而 double 的 53 位尾数足以精确表示这两个量级，缩放本身也只服务于展示。
     */
    private static int scaleStoredToIntWindow(long stored, long capacity) {
        if (capacity <= Integer.MAX_VALUE) {
            return saturate(stored);
        }
        if (stored <= 0L) {
            return 0;
        }
        if (stored >= capacity) {
            return Integer.MAX_VALUE;
        }
        long scaled = Math.round((double) stored / (double) capacity * Integer.MAX_VALUE);
        return (int) Math.min(Integer.MAX_VALUE - 1L, Math.max(1L, scaled));
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.ENERGY) {
            return energyCap.cast();
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        energyCap.invalidate();
    }

    /** 并组/退组只在放置与破坏时增量发生，读写路径里绝不 flood-fill（与线缆网同范式）。 */
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            PowerCellGroupManager.get(serverLevel).addCell(worldPosition, spec);
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            PowerCellGroupManager.get(serverLevel).removeCell(worldPosition);
        }
        super.setRemoved();
    }

    /** 保存路径不抛异常，理由同前期发电机：任何观察者都可能在任意时机触发一次保存。 */
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        // 空储电刻意不写这个键: 战利品表用 copy_nbt 把它拷进掉落物, 恒写会让每一块挖下来的空储电都带上
        // BlockEntityTag 而无法与合成品堆叠。copy_nbt 源路径缺失时一个字节都不写, 缺键时 load 按 0 读。
        if (storedFe > 0L) {
            tag.putLong(K_STORED, storedFe);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        long raw = tag.contains(K_STORED, Tag.TAG_LONG) ? tag.getLong(K_STORED) : 0L;
        long clamped = Math.max(0L, Math.min(raw, runtime().capacityFe()));
        if (clamped != raw) {
            LOGGER.warn("power cell NBT out of range at {}; clamped stored {} -> {}",
                    worldPosition, raw, clamped);
        }
        storedFe = clamped;
    }

    @Override
    public Component getDisplayName() {
        return getBlockState().getBlock().getName();
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory playerInventory, Player player) {
        return new PowerCellMenu(windowId, playerInventory, worldPosition);
    }

    /** 储电是双向端点：既收也发，这正是它与发电机（只出）和机器（只进）的区别。 */
    private final class CellStorage implements VoltageAwareEnergyStorage {

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            if (!(level instanceof ServerLevel serverLevel)) {
                // 搬电只发生在服务端；客户端副本既没有分组索引也没有权威余额。
                return 0;
            }
            PowerCellGroup group = group(serverLevel);
            // 尚未入组时读数可以退化成单块, 搬电绝不可以: 组的每 tick 速率额度正是端点去重的落点,
            // 绕开它直接对本方块收发, 等于把"贴几张面就把速率乘几遍"的刷电漏洞放回来。
            return group == null ? 0 : group.receive(serverLevel, maxReceive, simulate);
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            if (!(level instanceof ServerLevel serverLevel)) {
                return 0;
            }
            PowerCellGroup group = group(serverLevel);
            return group == null ? 0 : group.extractForNetwork(serverLevel, maxExtract, simulate);
        }

        @Override
        public int getEnergyStored() {
            if (!(level instanceof ServerLevel serverLevel)) {
                return saturate(storedFe);
            }
            PowerCellGroup group = group(serverLevel);
            if (group == null) {
                return saturate(storedFe);
            }
            return scaleStoredToIntWindow(group.storedFe(serverLevel), group.capacityFe());
        }

        @Override
        public int getMaxEnergyStored() {
            if (!(level instanceof ServerLevel serverLevel)) {
                return runtime().capacityFe();
            }
            PowerCellGroup group = group(serverLevel);
            return group == null ? runtime().capacityFe() : saturate(group.capacityFe());
        }

        @Override
        public boolean canExtract() {
            return true;
        }

        @Override
        public boolean canReceive() {
            return true;
        }

        @Override
        public VoltageClass outputVoltage() {
            return spec.voltageClass();
        }

        @Override
        public void reportOvervoltage(VoltageClass networkLimit) {
            LOGGER.warn("power cell at {} received an overvoltage report against network limit {}",
                    worldPosition, networkLimit);
        }
    }
}
