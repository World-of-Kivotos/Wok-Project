package com.miningdim.job.munitions.block;

import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyServices;
import com.miningdim.job.munitions.MunitionsAmmoFactory;
import com.miningdim.job.munitions.MunitionsCaliber;
import com.miningdim.job.munitions.MunitionsConfig;
import com.miningdim.job.munitions.MunitionsLevels;
import com.miningdim.job.munitions.MunitionsProduction;
import com.miningdim.job.munitions.ModMunitionsBlockEntities;
import com.miningdim.job.munitions.ModMunitionsItems;
import com.miningdim.job.munitions.ModMunitionsSounds;
import com.miningdim.job.munitions.menu.MunitionsBenchMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
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
 * 军火台方块实体 (Munitions_Job_DesignSpec 五/六/九/十章)。被动产线 (类农夫塞料->时间戳追算产->缓冲满停产->人手回收)。
 * 持:
 *  - ownerUUID + locked (上锁访问控制; 仿工程师生产台);
 *  - 料槽 铜 (slot 0) / 火药 (slot 1) / 发射药 (slot 2) + 输出缓冲展示槽 (slot 3);
 *  - bufferedRounds + bufferedCaliber: 缓冲内已产弹的权威发数与口径 (int 权威, 输出槽的 TACZ 弹 ItemStack 是其
 *    可视物化, 仅在主人在线访问时由 {@link MunitionsAmmoFactory} 物化, tick 不触 TACZ);
 *  - selectedCaliber: 选中口径 (clickMenuButton 选, 服务端校等级门);
 *  - lastSettleTick: 上次结算 game time (离线追算时间戳)。
 *
 * 结算时机 (五章 "玩家回来时一次性补产" + 九章先查后扣 + 七章谁产谁得): 结算 (settleForOwner) 只在主人在线时驱动
 * (tick 帧检测在线主人 / GUI 打开帧)。主人离线时 lastSettleTick 保持不动, 主人回来时按累计流逝 tick 一次性补产。
 * 因结算总在主人在线帧发生, 工费当帧经 IEconomyService.tryCharge 先查后扣 (扣不动则本批作废, 料不扣缓冲不增,
 * 下次再追), 产弹经验当帧经框架 grantXp 谁产谁得入 owner (并入每日软上限) —— 无需缓存待结算量。
 *
 * compileOnly 铁律: tick 路径 (serverTick -> settleForOwner) 全程纯逻辑 (经 MunitionsProduction), 不触 TACZ;
 * 唯一 TACZ 物化点是 {@link #refreshOutputStack} (主人在线访问帧, ModList isLoaded 守卫)。dev GameTest 不进 tick
 * 的 TACZ 路径。
 *
 * 反漏斗 (仿工程师): getCapability 只暴露料槽 [0,3) 的只写包装, 输出缓冲槽不对漏斗暴露 (RangedWrapper), 保证
 * "必须人手取" 的结算前提。
 */
public final class MunitionsBenchBlockEntity extends BlockEntity implements MenuProvider {

    /** 槽位: 0=铜, 1=火药, 2=发射药 (提炼中间品), 3=输出缓冲展示。 */
    public static final int SLOT_PRIMER = 0;
    public static final int SLOT_CASING = 1;
    public static final int SLOT_BULLET_HEAD = 2;
    public static final int SLOT_PROPELLANT = 3;
    public static final int SLOT_OUTPUT = 4;
    private static final int SLOT_COUNT = 5;
    /** 反漏斗只暴露料槽 [0,3) (铜/火药/发射药), 输出槽不暴露。 */
    private static final int INPUT_SLOT_END = SLOT_OUTPUT;

    /** ContainerData 索引 (开 GUI 者实时同步; int-only)。 */
    public static final int DATA_SELECTED_CALIBER = 0;
    public static final int DATA_BUFFERED_ROUNDS = 1;
    public static final int DATA_BUFFER_CAP = 2;
    public static final int DATA_LOCKED = 3;
    public static final int DATA_REFINE_UNLOCKED = 4;
    public static final int DATA_PRODUCTION_PROGRESS_TICKS = 5;
    public static final int DATA_PRODUCTION_REQUIRED_TICKS = 6;
    public static final int DATA_EFFECTIVE_LEVEL = 7;
    public static final int DATA_CRAFTING_ACTIVE = 8;
    public static final int DATA_CONTINUOUS_CRAFTING = 9;
    private static final int DATA_COUNT = 10;
    private static final int WELD_SOUND_MIN_INTERVAL = 26;
    private static final int WELD_SOUND_INTERVAL_SPREAD = 18;

    /** ContainerData 槽数 (Menu 客户端侧建同尺寸 SimpleContainerData 用)。 */
    public static int DATA_COUNT() {
        return DATA_COUNT;
    }

    @Nullable
    private UUID ownerUUID;
    private boolean locked;

    /** 当前选中口径; null = 未选 (不生产)。 */
    @Nullable
    private MunitionsCaliber selectedCaliber;

    private boolean craftingActive;
    private boolean continuousCrafting;
    @Nullable
    private MunitionsCaliber craftingCaliber;
    private int craftingOwnerLevel = 1;
    private long craftingStartTick;

    /** 缓冲内已产弹权威发数 (int; 输出槽 TACZ 弹只是其可视物化)。 */
    private int bufferedRounds;

    /** 缓冲内已产弹的口径 (与 bufferedRounds 配对; 换口径须先清空缓冲)。 */
    @Nullable
    private MunitionsCaliber bufferedCaliber;

    /**
     * 上次结算的 game time (离线追算时间戳)。配 {@link #settleInitialized} 标志判 "是否已锚定首帧": 不能用
     * lastSettleTick 的某个魔数 (如 -1) 当 "未初始化" 哨兵 —— 该字段由 NBT 还原, 远古回拨 (玩家长期离线后回来一次性
     * 补产) 会得到合法的负 game time, 与魔数哨兵冲突会被误判 "未初始化" 而吞掉应补的流逝时间 (五章语义破坏)。
     */
    private long lastSettleTick;
    /** 首帧锚定标志: false = lastSettleTick 尚未锚定 (首次结算只记 now 不补产); 由 NBT 持久化跨重载保持。 */
    private boolean settleInitialized;
    private long nextWeldSoundTick;

    private final ItemStackHandler inventory = new ItemStackHandler(SLOT_COUNT) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return switch (slot) {
                case SLOT_PRIMER -> stack.is(ModMunitionsItems.PRIMER.get());
                case SLOT_CASING -> stack.is(ModMunitionsItems.CASING.get());
                case SLOT_BULLET_HEAD -> stack.is(ModMunitionsItems.BULLET_HEAD.get());
                case SLOT_PROPELLANT -> stack.is(ModMunitionsItems.PROPELLANT.get());
                default -> false; // 输出槽不接受外部放入 (只由 BE 写)。
            };
        }
    };

    /** 反漏斗暴露: 任何 side 只给料槽 [0,3) 只写包装, 输出缓冲槽不暴露 (必须人手取)。 */
    private final LazyOptional<IItemHandler> inputOnlyHandler =
            LazyOptional.of(() -> new RangedWrapper(inventory, SLOT_PRIMER, INPUT_SLOT_END));

    private final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case DATA_SELECTED_CALIBER -> selectedCaliber == null ? -1 : selectedCaliber.index();
                case DATA_BUFFERED_ROUNDS -> bufferedRounds;
                case DATA_BUFFER_CAP -> bufferCap();
                case DATA_LOCKED -> locked ? 1 : 0;
                case DATA_REFINE_UNLOCKED -> refineUnlockedForOwnerCache ? 1 : 0;
                case DATA_PRODUCTION_PROGRESS_TICKS -> productionProgressTicks();
                case DATA_PRODUCTION_REQUIRED_TICKS -> productionRequiredTicks();
                case DATA_EFFECTIVE_LEVEL -> ownerLevelCache;
                case DATA_CRAFTING_ACTIVE -> craftingActive ? 1 : 0;
                case DATA_CONTINUOUS_CRAFTING -> continuousCrafting ? 1 : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            // 客户端不写权威状态 (服务端权威)。
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    };

    /** 缓存 "主人提炼是否解锁" 供 GUI 同步 (访问帧刷新, 避免 ContainerData get 触发玩家查询)。 */
    private boolean refineUnlockedForOwnerCache;

    public MunitionsBenchBlockEntity(BlockPos pos, BlockState state) {
        super(ModMunitionsBlockEntities.MUNITIONS_BENCH.get(), pos, state);
    }

    public ContainerData dataAccess() {
        return dataAccess;
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public int bufferedRounds() {
        return bufferedRounds;
    }

    @Nullable
    public MunitionsCaliber selectedCaliber() {
        return selectedCaliber;
    }

    // ---- 上锁 / 归属 (仿工程师) ----

    public void setOwner(UUID owner) {
        this.ownerUUID = owner;
        setChanged();
    }

    @Nullable
    public UUID owner() {
        return ownerUUID;
    }

    public boolean isOwner(Player player) {
        return ownerUUID != null && ownerUUID.equals(player.getUUID());
    }

    public boolean toggleLocked() {
        this.locked = !this.locked;
        setChanged();
        return this.locked;
    }

    public boolean isLocked() {
        return locked;
    }

    /** 是否允许该玩家访问 (开 GUI / 取物): 未锁恒允许; 锁定时仅主人或 OP。 */
    public boolean canAccess(ServerPlayer player) {
        if (!locked) {
            return true;
        }
        return isOwner(player) || player.hasPermissions(2);
    }

    // ---- 选口径 (服务端权威重校等级门) ----

    /**
     * 处理选口径 (Menu.clickMenuButton 委派, 服务端权威)。等级门过 + 缓冲口径相容才接受。客户端置灰仅提示, 此处
     * 不信客户端 (即便发来已置灰口径也重校)。
     *
     * @return 选口径是否被接受
     */
    public boolean trySelectCaliber(MunitionsCaliber target, ServerPlayer player) {
        if (!canAccess(player)) {
            return false;
        }
        if (craftingActive) {
            return false;
        }
        int level = effectiveOwnerLevel(MunitionsLevels.munitionsLevel(player));
        // 口径等级门 (6.1): 目标口径 unlockLevel <= 玩家等级。
        if (!MunitionsLevels.isCaliberUnlocked(level, target)) {
            return false;
        }
        // 缓冲非空且口径不同: 拒切 (防混口径堆叠; 须先取空缓冲再换口径)。
        if (bufferedRounds > 0 && bufferedCaliber != null && bufferedCaliber != target) {
            return false;
        }
        this.selectedCaliber = target;
        this.ownerLevelCache = level;
        this.refineUnlockedForOwnerCache = MunitionsLevels.isRefineUnlocked(level);
        if (this.level != null) {
            this.settleInitialized = true;
            this.lastSettleTick = this.level.getGameTime();
        }
        setChanged();
        return true;
    }

    // ---- 被动产线 tick (五章; 无玩家上下文, 只纯逻辑累积, 不触 TACZ) ----

    /**
     * 服务端 tick: 只在主人在线 (且在维度内) 时驱动一次结算 —— 把流逝时间换算成产弹累积进缓冲 + 待扣工费/待结算
     * 经验, 并在同帧对在线主人先查后扣工费、谁产谁得给经验。主人离线时不结算 (lastSettleTick 保持, 主人回来时
     * 一次性补产; 五章 "玩家回来/区块加载时按流逝时间一次性补产")。
     *
     * 性能 (五章): 用时间戳追算, 仅当主人在线时按流逝量一次性算, 不每 tick 遍历产线 (每 tick 仅一次轻量在线检查 +
     * 时间戳比较)。
     */
    public boolean tryStartCraft(ServerPlayer player) {
        if (level == null || !canAccess(player) || craftingActive || selectedCaliber == null) {
            return false;
        }
        int level0 = effectiveOwnerLevel(MunitionsLevels.munitionsLevel(player));
        this.ownerLevelCache = level0;
        this.refineUnlockedForOwnerCache = MunitionsLevels.isRefineUnlocked(level0);
        if (!MunitionsLevels.isCaliberUnlocked(level0, selectedCaliber)) {
            selectedCaliber = null;
            setChanged();
            return false;
        }
        int rounds = MunitionsProduction.roundsPerBatch(selectedCaliber, level0);
        if (bufferedRounds > 0 && bufferedCaliber != null && bufferedCaliber != selectedCaliber) {
            return false;
        }
        if (bufferCap() - bufferedRounds < rounds || !hasBatchMaterials()) {
            return false;
        }

        consume(SLOT_PRIMER, MunitionsConfig.RECIPE_PRIMER_COST.get());
        consume(SLOT_CASING, MunitionsConfig.RECIPE_CASING_COST.get());
        consume(SLOT_BULLET_HEAD, MunitionsConfig.RECIPE_BULLET_HEAD_COST.get());
        consume(SLOT_PROPELLANT, MunitionsConfig.RECIPE_PROPELLANT_COST.get());

        craftingActive = true;
        craftingCaliber = selectedCaliber;
        craftingOwnerLevel = level0;
        craftingStartTick = level.getGameTime();
        settleInitialized = true;
        lastSettleTick = craftingStartTick;
        setMachineActive(true);
        setChanged();
        return true;
    }

    public boolean cancelCraft(ServerPlayer player) {
        if (!canAccess(player) || !craftingActive) {
            return false;
        }
        clearActiveCraft();
        setMachineActive(false);
        setChanged();
        return true;
    }

    public boolean toggleContinuousCrafting(ServerPlayer player) {
        if (!canAccess(player)) {
            return false;
        }
        continuousCrafting = !continuousCrafting;
        setChanged();
        return true;
    }

    public void serverTick() {
        if (level == null || level.isClientSide) {
            return;
        }
        if (ownerUUID == null) {
            nextWeldSoundTick = 0L;
            setMachineActive(false);
            return;
        }
        if (!settleInitialized) {
            settleInitialized = true;
            lastSettleTick = level.getGameTime();
            nextWeldSoundTick = 0L;
            setMachineActive(false);
            return;
        }
        ServerPlayer owner = level.getServer() == null ? null
                : level.getServer().getPlayerList().getPlayer(ownerUUID);
        if (owner == null) {
            nextWeldSoundTick = 0L;
            setMachineActive(false);
            return; // 主人离线: 不追算 (时间戳保持, 回来时一次性补)。
        }
        settleForOwner(owner);
    }

    /**
     * 对在线主人做一次离线追算结算: 纯逻辑算应产 -> 先查后扣工费 -> 扣料 -> 入缓冲 -> 谁产谁得给经验 -> 刷输出展示。
     * 在 GUI 打开帧 (Menu 构造) 与 tick 帧共用 (主人在线时都驱动)。
     */
    public void settleForOwner(ServerPlayer owner) {
        if (settleManualCraft(owner)) {
            return;
        }
        if (level == null) {
            return;
        }
        if (selectedCaliber == null) {
            nextWeldSoundTick = 0L;
            setMachineActive(false);
            return;
        }
        long now = level.getGameTime();
        if (!settleInitialized) {
            settleInitialized = true;
            lastSettleTick = now;
            return;
        }
        long elapsed = now - lastSettleTick;
        if (elapsed <= 0L) {
            return;
        }

        int level0 = effectiveOwnerLevel(MunitionsLevels.munitionsLevel(owner));
        // 刷新主人等级缓存使 bufferCap()/GUI 显示与本次产能用同一等级 (单一真源; tick 直驱时也对齐)。
        this.ownerLevelCache = level0;
        this.refineUnlockedForOwnerCache = MunitionsLevels.isRefineUnlocked(level0);
        if (!MunitionsLevels.isCaliberUnlocked(level0, selectedCaliber)) {
            selectedCaliber = null;
            nextWeldSoundTick = 0L;
            setMachineActive(false);
            setChanged();
            return;
        }
        int bufferRemaining = bufferCap() - bufferedRounds;
        int primers = inventory.getStackInSlot(SLOT_PRIMER).getCount();
        int casings = inventory.getStackInSlot(SLOT_CASING).getCount();
        int bulletHeads = inventory.getStackInSlot(SLOT_BULLET_HEAD).getCount();
        int propellant = inventory.getStackInSlot(SLOT_PROPELLANT).getCount();

        // 单台 BE 视作 1 台参与产能 (全局多台 = 多 BE 各自累加; 台数上限由 SavedData 放置门控)。
        MunitionsProduction.Result result = MunitionsProduction.settle(
                selectedCaliber, level0, 1, elapsed, bufferRemaining,
                primers, casings, bulletHeads, propellant);
        boolean active = canAccumulateProduction();
        setMachineActive(active);
        playWeldSoundIfActive(now, active);

        if (!result.produced()) {
            lastSettleTick = now;
            if (!canAccumulateProduction()) {
                nextWeldSoundTick = 0L;
            }
            setChanged();
            return;
        }

        // Charge before advancing lastSettleTick so a failed fee keeps the production window.
        if (!tryChargeWorkFee(owner, result.workFeeCredits())) {
            nextWeldSoundTick = 0L;
            setMachineActive(false);
            setChanged();
            return;
        }

        // 工费扣成功, 本段流逝已兑现为产出: 推进时间戳 (产出受料/缓冲夹断时不重复累积已兑现的流逝)。
        lastSettleTick = now;

        // 扣料 + 入缓冲。
        consume(SLOT_PRIMER, result.primerConsumed());
        consume(SLOT_CASING, result.casingConsumed());
        consume(SLOT_BULLET_HEAD, result.bulletHeadConsumed());
        consume(SLOT_PROPELLANT, result.propellantConsumed());
        bufferedRounds += result.roundsProduced();
        bufferedCaliber = selectedCaliber;

        // 谁产谁得 (七章): 产弹经验入在线主人 (并入框架每日软上限)。
        MunitionsLevels.grantRawXp(owner, result.rawXp());

        refreshOutputStack();
        setMachineActive(canAccumulateProduction());
        setChanged();
    }

    private boolean settleManualCraft(ServerPlayer owner) {
        if (level == null) {
            return true;
        }
        int level0 = effectiveOwnerLevel(MunitionsLevels.munitionsLevel(owner));
        this.ownerLevelCache = level0;
        this.refineUnlockedForOwnerCache = MunitionsLevels.isRefineUnlocked(level0);
        if (!craftingActive || craftingCaliber == null) {
            nextWeldSoundTick = 0L;
            setMachineActive(false);
            return true;
        }

        long now = level.getGameTime();
        setMachineActive(true);
        playWeldSoundIfActive(now, true);
        int required = productionRequiredTicks();
        if (required <= 0 || now - craftingStartTick < required) {
            return true;
        }

        finishActiveCraft(owner);
        if (continuousCrafting) {
            tryStartCraft(owner);
        }
        setChanged();
        return true;
    }

    private void finishActiveCraft(ServerPlayer owner) {
        if (craftingCaliber == null) {
            clearActiveCraft();
            setMachineActive(false);
            return;
        }
        int rounds = MunitionsProduction.roundsPerBatch(craftingCaliber, craftingOwnerLevel);
        long workFee = MunitionsProduction.workFee(rounds);
        if (!tryChargeWorkFee(owner, workFee)) {
            clearActiveCraft();
            setMachineActive(false);
            return;
        }

        bufferedRounds += rounds;
        bufferedCaliber = craftingCaliber;
        MunitionsLevels.grantRawXp(owner, MunitionsProduction.produceXp(rounds));
        clearActiveCraft();
        refreshOutputStack();
        setMachineActive(false);
    }

    private void clearActiveCraft() {
        craftingActive = false;
        craftingCaliber = null;
        craftingStartTick = 0L;
        nextWeldSoundTick = 0L;
    }

    private void playWeldSoundIfActive(long now, boolean active) {
        if (!active || level == null || level.isClientSide) {
            nextWeldSoundTick = 0L;
            return;
        }
        if (nextWeldSoundTick > now) {
            return;
        }
        float pitch = 0.88F + level.random.nextFloat() * 0.24F;
        level.playSound(null,
                worldPosition.getX() + 0.5D,
                worldPosition.getY() + 0.72D,
                worldPosition.getZ() + 0.5D,
                ModMunitionsSounds.MUNITIONS_BENCH_WELD.get(),
                SoundSource.BLOCKS,
                0.38F,
                pitch);
        nextWeldSoundTick = now + WELD_SOUND_MIN_INTERVAL + level.random.nextInt(WELD_SOUND_INTERVAL_SPREAD + 1);
    }

    /** 工费 sink: 经 {@link EconomyServices} 定位器先查后扣; 经济未注入或 cost<=0 放行 (不阻塞核心循环)。 */
    private boolean tryChargeWorkFee(ServerPlayer owner, long cost) {
        if (cost <= 0L || !EconomyServices.isRegistered()) {
            return true;
        }
        return EconomyServices.economyService().tryCharge(owner, Currency.CREDIT, cost);
    }

    private void consume(int slot, int amount) {
        if (amount <= 0) {
            return;
        }
        ItemStack stack = inventory.getStackInSlot(slot);
        stack.shrink(amount);
        inventory.setStackInSlot(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
    }

    /** 该台缓冲上限 (= 单台缓冲 × 1; 单 BE 即一台)。主人离线时按上次缓存等级 (refineUnlockedForOwnerCache 同理刷)。 */
    private int bufferCap() {
        return MunitionsLevels.bufferPerTable(ownerLevelCache);
    }

    private int effectiveOwnerLevel(int ownerLevel) {
        Block block = getBlockState().getBlock();
        if (block instanceof MunitionsBenchBlock bench) {
            return bench.effectiveLevelFor(ownerLevel);
        }
        return MunitionsLevels.clampLevel(ownerLevel);
    }

    private boolean hasBatchMaterials() {
        return inventory.getStackInSlot(SLOT_PRIMER).getCount() >= MunitionsConfig.RECIPE_PRIMER_COST.get()
                && inventory.getStackInSlot(SLOT_CASING).getCount() >= MunitionsConfig.RECIPE_CASING_COST.get()
                && inventory.getStackInSlot(SLOT_BULLET_HEAD).getCount() >= MunitionsConfig.RECIPE_BULLET_HEAD_COST.get()
                && inventory.getStackInSlot(SLOT_PROPELLANT).getCount() >= MunitionsConfig.RECIPE_PROPELLANT_COST.get();
    }

    private boolean canAccumulateProduction() {
        return craftingActive && craftingCaliber != null;
    }

    private int productionRequiredTicks() {
        MunitionsCaliber caliber = craftingActive && craftingCaliber != null ? craftingCaliber : selectedCaliber;
        int level = craftingActive ? craftingOwnerLevel : ownerLevelCache;
        return productionRequiredTicksFor(caliber, level);
    }

    private int productionRequiredTicksFor(@Nullable MunitionsCaliber caliber, int level) {
        if (caliber == null) {
            return 0;
        }
        int ticksPerRound = MunitionsProduction.ticksPerRound(level);
        int perBatchRounds = MunitionsProduction.roundsPerBatch(caliber, level);
        long rifleRoundsNeeded = (long) Math.ceil(perBatchRounds / Math.max(0.0001D, caliber.yieldFactor()));
        long required = Math.max(1L, rifleRoundsNeeded) * (long) ticksPerRound;
        return (int) Math.min(Integer.MAX_VALUE, required);
    }

    private int productionProgressTicks() {
        if (level == null || !craftingActive || craftingCaliber == null) {
            return 0;
        }
        int required = productionRequiredTicks();
        if (required <= 0) {
            return 0;
        }
        long elapsed = Math.max(0L, level.getGameTime() - craftingStartTick);
        return (int) Math.min(required, elapsed);
    }

    /** 缓存主人等级供缓冲上限/GUI 显示 (访问帧刷新; 主人离线时用上次值)。 */
    private void setMachineActive(boolean active) {
        if (level == null || level.isClientSide) {
            return;
        }
        BlockState state = level.getBlockState(worldPosition);
        if (!(state.getBlock() instanceof MunitionsBenchBlock) || !MunitionsBenchBlock.isMain(state)) {
            return;
        }
        setPartActive(worldPosition, state, active);

        BlockPos extensionPos = MunitionsBenchBlock.extensionPos(worldPosition, state);
        BlockState extensionState = level.getBlockState(extensionPos);
        if (extensionState.getBlock() instanceof MunitionsBenchBlock
                && extensionState.getValue(MunitionsBenchBlock.PART) == MunitionsBenchBlock.Part.EXTENSION) {
            setPartActive(extensionPos, extensionState, active);
        }
    }

    private void setPartActive(BlockPos pos, BlockState state, boolean active) {
        if (state.getValue(MunitionsBenchBlock.ACTIVE) != active) {
            level.setBlock(pos, state.setValue(MunitionsBenchBlock.ACTIVE, active), Block.UPDATE_CLIENTS);
        }
    }

    private int ownerLevelCache = 1;

    // ---- TACZ 物化点 (唯一触 com.tacz.*; ModList 守卫; tick 不进) ----

    /**
     * 把缓冲发数物化成可视的 TACZ 弹 ItemStack 放进输出展示槽 (主人在线访问帧调; ModList isLoaded 守卫)。
     * TACZ 未加载 (dev) 时 materialize 返回 EMPTY, 输出槽留空 (产能逻辑照常累积 bufferedRounds, 真造弹在正式服验)。
     * 受 TACZ 弹药 stack_size 上限约束, 单槽只展示一栈 (取走后下次刷新再补; 缓冲发数是权威)。
     */
    private void refreshOutputStack() {
        if (bufferedCaliber == null || bufferedRounds <= 0) {
            inventory.setStackInSlot(SLOT_OUTPUT, ItemStack.EMPTY);
            return;
        }
        ItemStack ammo = MunitionsAmmoFactory.materialize(bufferedCaliber, bufferedRounds);
        // materialize 已按 count 建栈; 若超 TACZ stack_size, TACZ 内部钳到上限, 取走后下次刷新补余量。
        inventory.setStackInSlot(SLOT_OUTPUT, ammo);
    }

    /**
     * 玩家手取输出弹时结算 (谁产谁得已在产出帧入主人, 此处只回收缓冲计数 + 刷新展示)。takenCount 为本次实际取走发数,
     * 从 bufferedRounds 扣减 (取走即出缓冲, 让出空间继续产)。由 {@link MunitionsBenchMenu} 输出槽 onTake 调,
     * 取走量经输出栈快照差值算得 (取出前满栈数量 - 移除后残留数量), 而非基类传入的移除后残留栈 —— Shift 整栈取弹时
     * 残留栈为 EMPTY, 据其 count 结算会令缓冲永不回收 (munitions-output)。
     */
    public void onOutputTaken(ServerPlayer player, int takenCount) {
        if (takenCount <= 0) {
            return; // 本次未取走任何弹 (残留判据改用真实取走量, 不信传入栈的 count)。
        }
        bufferedRounds = Math.max(0, bufferedRounds - takenCount);
        if (bufferedRounds == 0) {
            bufferedCaliber = null;
        }
        refreshOutputStack();
        setChanged();
    }

    /** GUI 打开 / 访问帧: 刷新主人等级缓存 + 驱动一次结算 (主人在线时一次性补产)。 */
    public void onAccess(ServerPlayer player) {
        if (isOwner(player)) {
            this.ownerLevelCache = effectiveOwnerLevel(MunitionsLevels.munitionsLevel(player));
            this.refineUnlockedForOwnerCache = MunitionsLevels.isRefineUnlocked(ownerLevelCache);
            settleForOwner(player);
        }
    }

    // ---- 反漏斗 capability ----

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
        return getBlockState().getBlock().getName();
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory inv, Player player) {
        return new MunitionsBenchMenu(windowId, inv, worldPosition);
    }

    // ---- 持久化 ----

    private static final String K_OWNER = "Owner";
    private static final String K_LOCKED = "Locked";
    private static final String K_INV = "Inv";
    private static final String K_SELECTED = "SelectedCaliber";
    private static final String K_BUFFERED = "BufferedRounds";
    private static final String K_BUFFERED_CAL = "BufferedCaliber";
    private static final String K_LAST_SETTLE = "LastSettleTick";
    private static final String K_SETTLE_INIT = "SettleInitialized";
    private static final String K_OWNER_LEVEL = "OwnerLevelCache";
    private static final String K_CRAFTING_ACTIVE = "CraftingActive";
    private static final String K_CONTINUOUS = "ContinuousCrafting";
    private static final String K_CRAFTING_CALIBER = "CraftingCaliber";
    private static final String K_CRAFTING_LEVEL = "CraftingOwnerLevel";
    private static final String K_CRAFTING_START = "CraftingStartTick";

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (ownerUUID != null) {
            tag.putUUID(K_OWNER, ownerUUID);
        }
        tag.putBoolean(K_LOCKED, locked);
        tag.put(K_INV, inventory.serializeNBT());
        tag.putInt(K_SELECTED, selectedCaliber == null ? -1 : selectedCaliber.index());
        tag.putInt(K_BUFFERED, bufferedRounds);
        tag.putInt(K_BUFFERED_CAL, bufferedCaliber == null ? -1 : bufferedCaliber.index());
        tag.putLong(K_LAST_SETTLE, lastSettleTick);
        tag.putBoolean(K_SETTLE_INIT, settleInitialized);
        tag.putInt(K_OWNER_LEVEL, ownerLevelCache);
        tag.putBoolean(K_CRAFTING_ACTIVE, craftingActive);
        tag.putBoolean(K_CONTINUOUS, continuousCrafting);
        tag.putInt(K_CRAFTING_CALIBER, craftingCaliber == null ? -1 : craftingCaliber.index());
        tag.putInt(K_CRAFTING_LEVEL, craftingOwnerLevel);
        tag.putLong(K_CRAFTING_START, craftingStartTick);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        ownerUUID = tag.hasUUID(K_OWNER) ? tag.getUUID(K_OWNER) : null;
        locked = tag.getBoolean(K_LOCKED);
        if (tag.contains(K_INV)) {
            CompoundTag invTag = tag.getCompound(K_INV);
            int savedSlots = invTag.contains("Size") ? invTag.getInt("Size") : SLOT_COUNT;
            inventory.deserializeNBT(invTag);
            migrateInventoryShape(savedSlots);
        }
        int sel = tag.contains(K_SELECTED) ? tag.getInt(K_SELECTED) : -1;
        selectedCaliber = sel < 0 ? null : MunitionsCaliber.byIndex(sel);
        bufferedRounds = tag.getInt(K_BUFFERED);
        int bufCal = tag.contains(K_BUFFERED_CAL) ? tag.getInt(K_BUFFERED_CAL) : -1;
        bufferedCaliber = bufCal < 0 ? null : MunitionsCaliber.byIndex(bufCal);
        lastSettleTick = tag.getLong(K_LAST_SETTLE);
        // 兼容旧档 (无 SettleInitialized 键): 有 LastSettleTick 键即视作已锚定 (旧档 lastSettleTick 恒为合法 game time)。
        settleInitialized = tag.contains(K_SETTLE_INIT) ? tag.getBoolean(K_SETTLE_INIT) : tag.contains(K_LAST_SETTLE);
        ownerLevelCache = tag.contains(K_OWNER_LEVEL) ? MunitionsLevels.clampLevel(tag.getInt(K_OWNER_LEVEL)) : 1;
        craftingActive = tag.getBoolean(K_CRAFTING_ACTIVE);
        continuousCrafting = tag.getBoolean(K_CONTINUOUS);
        int craftCal = tag.contains(K_CRAFTING_CALIBER) ? tag.getInt(K_CRAFTING_CALIBER) : -1;
        craftingCaliber = craftCal < 0 ? null : MunitionsCaliber.byIndex(craftCal);
        craftingOwnerLevel = tag.contains(K_CRAFTING_LEVEL)
                ? MunitionsLevels.clampLevel(tag.getInt(K_CRAFTING_LEVEL)) : ownerLevelCache;
        craftingStartTick = tag.getLong(K_CRAFTING_START);
        if (craftingActive && craftingCaliber == null) {
            craftingActive = false;
        }
    }

    private void migrateInventoryShape(int savedSlots) {
        if (savedSlots == 4 && inventory.getSlots() == 4) {
            ItemStack legacyPropellant = inventory.getStackInSlot(2).copy();
            ItemStack legacyOutput = inventory.getStackInSlot(3).copy();
            inventory.setSize(SLOT_COUNT);
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                inventory.setStackInSlot(slot, ItemStack.EMPTY);
            }
            if (legacyPropellant.is(ModMunitionsItems.PROPELLANT.get())) {
                inventory.setStackInSlot(SLOT_PROPELLANT, legacyPropellant);
            }
            if (!legacyOutput.isEmpty()) {
                inventory.setStackInSlot(SLOT_OUTPUT, legacyOutput);
            }
            return;
        }
        if (inventory.getSlots() != SLOT_COUNT) {
            inventory.setSize(SLOT_COUNT);
        }
        clearInvalidInputSlots();
    }

    private void clearInvalidInputSlots() {
        for (int slot = 0; slot < INPUT_SLOT_END; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && !inventory.isItemValid(slot, stack)) {
                inventory.setStackInSlot(slot, ItemStack.EMPTY);
            }
        }
    }
}
