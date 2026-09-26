package com.miningdim.job.munitions.menu;

import com.miningdim.job.munitions.ModMunitionsBlocks;
import com.miningdim.job.munitions.ModMunitionsMenus;
import com.miningdim.job.munitions.MunitionsCaliber;
import com.miningdim.job.munitions.block.MunitionsBenchBlock;
import com.miningdim.job.munitions.block.MunitionsBenchBlockEntity;
import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

/**
 * 军火台容器 (Munitions_Job_DesignSpec 五/十章)。继承共享 {@link AbstractMiningMenu} (正确 quickMoveStack +
 * stillValid 已由基类经 {@link MenuValidity#ofBlock} 实现)。
 *
 * 槽位: 料槽 底火/弹壳/弹头/发射药 (可放可取, isItemValid 限料种) + 输出缓冲槽 (只取不放, 取出经
 * {@link MunitionsBenchBlockEntity#onOutputTaken} 回收缓冲计数) + 玩家 36 槽。选中口径/缓冲发数/缓冲上限/锁/提炼
 * 解锁/内部电池存量与容量 经 {@link ContainerData} 同步 (服务端用 BE 实时 dataAccess; 客户端用 SimpleContainerData)。
 * 槽位坐标见 SLOT_*_X/Y 常量: 界面三种风格共用同一套坐标, 界面代码直接引用这些常量画槽位框。
 *
 * 打开即触发一次离线追算结算 (主人在线时一次性补产; 见 {@link MunitionsBenchBlockEntity#onAccess})。
 *
 * 按钮路由 (clickMenuButton; 走原版通道, 不新开网络包):
 *  - [0, {@link #CALIBER_BUTTON_LIMIT}): 选口径 caliberIndex (服务端权威重校等级门; 口径扩档预留到 100 个);
 *  - 100: 切锁 (仅主人; 界面不发, 上锁走方块 Shift+右键);
 *  - 101 / 102: 开工 / 取消 (仅主人);
 *  - 103: 切换单次/连续; 104 / 105: 设为单次 / 设为连续 (幂等, 界面分段开关用; 均仅主人)。
 *
 * 按钮 id 一律落在 [0, 127] (V05): 原版 ServerboundContainerButtonClickPacket 用 writeByte/readByte 收发 buttonId
 * (javap 核实), 大于 127 的 id 在专用服和局域网访客那里被截成有符号字节 (旧值 210 -> -46), 手动开工整条链路失效;
 * 单机集成服走本地通道不经编解码, 测不出来。
 */
public final class MunitionsBenchMenu extends AbstractMiningMenu {

    private static final int CONTAINER_SLOTS = 5;

    /** 口径按钮保留区间上界 (不含): 口径序号直接当按钮 id, 功能按钮从这里往后排。 */
    public static final int CALIBER_BUTTON_LIMIT = 100;
    /** 原版按钮包单字节可无损往返的最大 id。 */
    public static final int MAX_BUTTON_ID = Byte.MAX_VALUE;

    public static final int BUTTON_TOGGLE_LOCK = 100;
    public static final int BUTTON_START_CRAFT = 101;
    public static final int BUTTON_CANCEL_CRAFT = 102;
    public static final int BUTTON_TOGGLE_CONTINUOUS = 103;
    /**
     * 单次 / 连续 的幂等"设为"按钮 (界面的分段开关用)。切换按钮 103 在同步值回来之前连点会被翻回去,
     * 设为按钮重复发送无害。接在 100-103 之后, 与口径区间 [0, {@link #CALIBER_BUTTON_LIMIT}) 不相交。
     */
    public static final int BUTTON_SET_SINGLE = 104;
    public static final int BUTTON_SET_CONTINUOUS = 105;

    static {
        // 类加载期区间断言 (V05): 口径扩档不得挤进功能按钮区, 功能按钮不得越过按钮包的单字节上限。
        if (MunitionsCaliber.values().length > CALIBER_BUTTON_LIMIT) {
            throw new IllegalStateException("munitions caliber count " + MunitionsCaliber.values().length
                    + " overflows the caliber button range [0, " + CALIBER_BUTTON_LIMIT + ")");
        }
        for (int id : new int[] {BUTTON_TOGGLE_LOCK, BUTTON_START_CRAFT, BUTTON_CANCEL_CRAFT,
                BUTTON_TOGGLE_CONTINUOUS, BUTTON_SET_SINGLE, BUTTON_SET_CONTINUOUS}) {
            if (id < CALIBER_BUTTON_LIMIT || id > MAX_BUTTON_ID) {
                throw new IllegalStateException("munitions bench button id " + id + " must stay within ["
                        + CALIBER_BUTTON_LIMIT + ", " + MAX_BUTTON_ID + "]");
            }
        }
    }

    /** 槽位坐标 (GUI 像素, 槽内 16x16 左上角)。 */
    public static final int SLOT_PRIMER_X = 282;
    public static final int SLOT_PRIMER_Y = 45;
    public static final int SLOT_CASING_X = 324;
    public static final int SLOT_CASING_Y = 45;
    public static final int SLOT_BULLET_HEAD_X = 282;
    public static final int SLOT_BULLET_HEAD_Y = 76;
    public static final int SLOT_PROPELLANT_X = 324;
    public static final int SLOT_PROPELLANT_Y = 76;
    public static final int SLOT_OUTPUT_X = 303;
    public static final int SLOT_OUTPUT_Y = 162;
    public static final int PLAYER_INVENTORY_X = 100;
    public static final int PLAYER_INVENTORY_Y = 148;

    private final MunitionsBenchBlockEntity blockEntity;
    private final ContainerData data;

    public MunitionsBenchMenu(int windowId, Inventory inv, BlockPos pos) {
        super(ModMunitionsMenus.MUNITIONS_BENCH.get(), windowId, CONTAINER_SLOTS,
                MenuValidity.ofBlock(ContainerLevelAccess.create(inv.player.level(), pos), blockAt(inv, pos)));
        this.blockEntity = inv.player.level().getBlockEntity(pos) instanceof MunitionsBenchBlockEntity be
                ? be : null;

        if (blockEntity != null) {
            // 打开即结算 (主人在线一次性补产)。仅服务端 (客户端无权威 BE 逻辑)。
            if (!inv.player.level().isClientSide && inv.player instanceof ServerPlayer serverPlayer) {
                blockEntity.onAccess(serverPlayer);
            }
            addSlot(new SlotItemHandler(blockEntity.inventory(),
                    MunitionsBenchBlockEntity.SLOT_PRIMER, SLOT_PRIMER_X, SLOT_PRIMER_Y));
            addSlot(new SlotItemHandler(blockEntity.inventory(),
                    MunitionsBenchBlockEntity.SLOT_CASING, SLOT_CASING_X, SLOT_CASING_Y));
            addSlot(new SlotItemHandler(blockEntity.inventory(),
                    MunitionsBenchBlockEntity.SLOT_BULLET_HEAD, SLOT_BULLET_HEAD_X, SLOT_BULLET_HEAD_Y));
            addSlot(new SlotItemHandler(blockEntity.inventory(),
                    MunitionsBenchBlockEntity.SLOT_PROPELLANT, SLOT_PROPELLANT_X, SLOT_PROPELLANT_Y));
            addSlot(new OutputSlot(blockEntity, MunitionsBenchBlockEntity.SLOT_OUTPUT, SLOT_OUTPUT_X, SLOT_OUTPUT_Y));
            this.data = inv.player.level().isClientSide
                    ? new SimpleContainerData(MunitionsBenchBlockEntity.DATA_COUNT())
                    : blockEntity.dataAccess();
        } else {
            this.data = new SimpleContainerData(MunitionsBenchBlockEntity.DATA_COUNT());
        }
        addDataSlots(this.data);
        addPlayerInventory(inv, PLAYER_INVENTORY_X, PLAYER_INVENTORY_Y);
    }

    /** 取 pos 处方块作 stillValid 校验目标; 非军火台时退回军火台方块占位 (块不匹配判 false 关闭界面)。 */
    private static net.minecraft.world.level.block.Block blockAt(Inventory inv, BlockPos pos) {
        net.minecraft.world.level.block.Block block = inv.player.level().getBlockState(pos).getBlock();
        return block instanceof MunitionsBenchBlock
                ? block : ModMunitionsBlocks.MUNITIONS_BENCH.get();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (!(player instanceof ServerPlayer serverPlayer) || blockEntity == null) {
            return false;
        }
        if (id >= 0 && id < MunitionsCaliber.values().length) {
            return blockEntity.trySelectCaliber(MunitionsCaliber.byIndex(id), serverPlayer);
        }
        if (id == BUTTON_TOGGLE_LOCK) {
            if (blockEntity.isOwner(player)) {
                blockEntity.toggleLocked();
                return true;
            }
            return false;
        }
        if (id == BUTTON_START_CRAFT) {
            return blockEntity.tryStartCraft(serverPlayer);
        }
        if (id == BUTTON_CANCEL_CRAFT) {
            return blockEntity.cancelCraft(serverPlayer);
        }
        if (id == BUTTON_TOGGLE_CONTINUOUS) {
            return blockEntity.toggleContinuousCrafting(serverPlayer);
        }
        if (id == BUTTON_SET_SINGLE || id == BUTTON_SET_CONTINUOUS) {
            return blockEntity.setContinuousCrafting(serverPlayer, id == BUTTON_SET_CONTINUOUS);
        }
        return false;
    }

    /**
     * Shift 移物覆写 (审查 M-7/M-8):
     *  1. 玩家区 -> 容器区的目标区间排除输出槽 —— vanilla moveItemStackTo 的合并分支只判同物同 tag 不调
     *     mayPlace, 玩家背包里的同种弹药会被并进输出槽, 随后 refreshOutputStack 按缓冲重物化把并入的弹覆盖销毁;
     *  2. 输出槽 -> 玩家区的取弹量以移动前后槽内差值精确结算 —— moveItemStackTo 直改源栈不经 Slot.remove,
     *     OutputSlot 的 remove 计量对 Shift 路径不可见。结算放在槽状态落定之后, refreshOutputStack 重物化
     *     的剩余弹不会被本方法的清槽逻辑抹掉。
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stackInSlot = slot.getItem();
        ItemStack moved = stackInSlot.copy();
        int playerStart = CONTAINER_SLOTS;
        int playerEnd = this.slots.size();
        int takenFromOutput = 0;

        if (index < playerStart) {
            int before = stackInSlot.getCount();
            if (!this.moveItemStackTo(stackInSlot, playerStart, playerEnd, true)) {
                return ItemStack.EMPTY;
            }
            if (index == MunitionsBenchBlockEntity.SLOT_OUTPUT) {
                takenFromOutput = before - stackInSlot.getCount();
            }
        } else {
            if (!this.moveItemStackTo(stackInSlot, 0, MunitionsBenchBlockEntity.SLOT_OUTPUT, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stackInSlot.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            // 部分移出必须经 handler 回写 (与公共基类同一修复): SlotItemHandler 的 setChanged 落在空占位容器上,
            // 什么都不做; moveItemStackTo 又是直改活栈, 只调 setChanged 时 BE 不标脏, 区块卸载/关服跳过存盘,
            // 重载后料槽回到原数, 玩家手里却多出这批料。set 走 setStackInSlot -> onContentsChanged -> BE.setChanged。
            slot.set(stackInSlot);
        }
        if (takenFromOutput > 0 && blockEntity != null && player instanceof ServerPlayer serverPlayer) {
            blockEntity.onOutputTaken(serverPlayer, takenFromOutput);
        }
        if (stackInSlot.getCount() == moved.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stackInSlot);
        return moved;
    }

    /**
     * 输出缓冲槽: 只取不放; 取出时回收缓冲计数 (谁产谁得经验已在产出帧入主人)。
     * 取弹计量按离槽路径分三条入口累计, 统一在 {@link #onTake} 交给 BE (审查 M-8 / V01), 每个 menu 实例独立计量
     * —— 替换旧的 getItem 快照机制 (vanilla 每 tick broadcastChanges 会虚调 getItem, 把第二名观看者的快照钉在
     * 历史最大值, 多人同开交错取弹时按旧快照差值超额扣缓冲):
     *  - {@link #remove}: 鼠标路径 (PICKUP 左/右键、THROW、PICKUP_ALL 都经 safeTake/tryRemove 落到这里);
     *  - {@link #onSwapCraft}: 数字键 1-9 / 副手键 F 的 SWAP 路径。原版 doClick 的 SWAP 分支不调 remove, 而是
     *    Inventory.setItem 把活栈整个塞给玩家 -> onSwapCraft(count) -> setByPlayer(EMPTY) -> onTake (javap 核实
     *    1.20.1 字节码顺序), 漏计时缓冲不扣而输出槽已空, 重开界面 onAccess 按原缓冲重新物化一整栈 = 无限复制;
     *  - Shift 路径不经 remove 也不经 onSwapCraft, 由外层 quickMoveStack 差值结算, 此处消费 0 不双计。
     * CLONE (创造中键) 只复制不离槽, 不涉及缓冲。
     */
    private static final class OutputSlot extends SlotItemHandler {
        private final MunitionsBenchBlockEntity be;
        private int pendingTaken = 0;

        OutputSlot(MunitionsBenchBlockEntity be, int index, int x, int y) {
            super(be.inventory(), index, x, y);
            this.be = be;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public ItemStack remove(int amount) {
            ItemStack removed = super.remove(amount);
            pendingTaken += removed.getCount();
            return removed;
        }

        @Override
        protected void onSwapCraft(int count) {
            // SWAP 整栈换出: 原版在 setByPlayer(EMPTY) 与 onTake 之前以换出数量调用, 正好接上 onTake 的结算。
            pendingTaken += count;
        }

        @Override
        public void onTake(Player player, ItemStack stack) {
            int taken = pendingTaken;
            pendingTaken = 0;
            if (taken > 0 && player instanceof ServerPlayer serverPlayer) {
                be.onOutputTaken(serverPlayer, taken);
            }
            super.onTake(player, stack);
        }
    }

    // ---- 客户端读同步值 (Screen 渲染用) ----

    public int selectedCaliberIndex() {
        return data.get(MunitionsBenchBlockEntity.DATA_SELECTED_CALIBER);
    }

    /** 缓冲发数 (两个 15 位半字拼回, 配置上限 1000 万也不会在 int16 过线时回绕)。 */
    public int bufferedRounds() {
        return MunitionsBenchBlockEntity.unpackHalves15(
                data.get(MunitionsBenchBlockEntity.DATA_BUFFERED_ROUNDS),
                data.get(MunitionsBenchBlockEntity.DATA_BUFFERED_ROUNDS_HI));
    }

    /** 缓冲上限 (同 {@link #bufferedRounds()} 的编码)。 */
    public int bufferCap() {
        return MunitionsBenchBlockEntity.unpackHalves15(
                data.get(MunitionsBenchBlockEntity.DATA_BUFFER_CAP),
                data.get(MunitionsBenchBlockEntity.DATA_BUFFER_CAP_HI));
    }

    public boolean isLocked() {
        return data.get(MunitionsBenchBlockEntity.DATA_LOCKED) != 0;
    }

    public boolean isRefineUnlocked() {
        return data.get(MunitionsBenchBlockEntity.DATA_REFINE_UNLOCKED) != 0;
    }

    public int productionProgressTicks() {
        // 服务端按秒过线 (int16 规避, 见 BE dataAccess), 此处 x20 还原 ticks; 秒粒度对进度条视觉无感。
        return data.get(MunitionsBenchBlockEntity.DATA_PRODUCTION_PROGRESS_TICKS) * 20;
    }

    public int productionRequiredTicks() {
        return data.get(MunitionsBenchBlockEntity.DATA_PRODUCTION_REQUIRED_TICKS) * 20;
    }

    public int effectiveMunitionsLevel() {
        return Math.max(1, Math.min(10, data.get(MunitionsBenchBlockEntity.DATA_EFFECTIVE_LEVEL)));
    }

    public boolean isCraftingActive() {
        return data.get(MunitionsBenchBlockEntity.DATA_CRAFTING_ACTIVE) != 0;
    }

    public boolean isContinuousCrafting() {
        return data.get(MunitionsBenchBlockEntity.DATA_CONTINUOUS_CRAFTING) != 0;
    }

    /**
     * 内部电池存量 (FE)。按 kFE 过线, 读到的是向下取整到
     * {@link MunitionsBenchBlockEntity#ENERGY_SYNC_UNIT_FE} 倍数的值。
     */
    public long storedEnergyFe() {
        return MunitionsBenchBlockEntity.unpackKfeToFe(
                data.get(MunitionsBenchBlockEntity.DATA_ENERGY_KFE_LO),
                data.get(MunitionsBenchBlockEntity.DATA_ENERGY_KFE_HI));
    }

    /** 内部电池容量 (FE), 粒度同 {@link #storedEnergyFe()}。 */
    public long energyCapacityFe() {
        return MunitionsBenchBlockEntity.unpackKfeToFe(
                data.get(MunitionsBenchBlockEntity.DATA_ENERGY_CAPACITY_KFE_LO),
                data.get(MunitionsBenchBlockEntity.DATA_ENERGY_CAPACITY_KFE_HI));
    }
}
