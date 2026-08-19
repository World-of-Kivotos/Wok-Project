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
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
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
import com.miningdim.power.machine.MachineEnergyStorage;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RangedWrapper;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 军火台方块实体 (Munitions_Job_DesignSpec 五/六/九/十章)。被动产线 (类农夫塞料->时间戳追算产->缓冲满停产->人手回收)。
 * 持:
 *  - ownerUUID + locked (上锁访问控制; 仿工程师生产台);
 *  - 料槽 底火 (slot 0) / 弹壳 (slot 1) / 弹头 (slot 2) / 发射药 (slot 3) + 输出缓冲展示槽 (slot 4);
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
 * 反漏斗 (仿工程师): getCapability 只暴露料槽 [0,3) 的只写包装 (InsertOnlyRangedWrapper), 输出缓冲槽不对漏斗
 * 暴露, 保证 "必须人手取" 的结算前提。RangedWrapper 本身同时代理 insert 与 extract, 不覆写 extract 时漏斗能把
 * 底火/弹壳/弹头/发射药反抽走, 产线静默停摆; InsertOnlyRangedWrapper 只覆写 extractItem 恒返空。
 */
public final class MunitionsBenchBlockEntity extends BlockEntity implements MenuProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/munitions/bench");

    /** 槽位: 0=底火, 1=弹壳, 2=弹头, 3=发射药, 4=输出缓冲展示 (四件套见 MunitionsConfig recipe 组)。 */
    public static final int SLOT_PRIMER = 0;
    public static final int SLOT_CASING = 1;
    public static final int SLOT_BULLET_HEAD = 2;
    public static final int SLOT_PROPELLANT = 3;
    public static final int SLOT_OUTPUT = 4;
    private static final int SLOT_COUNT = 5;
    /** 反漏斗只暴露料槽 [0,4) (四件套), 输出槽不暴露。 */
    private static final int INPUT_SLOT_END = SLOT_OUTPUT;
    /** 旧档 4 槽布局 (F015 迁移用): legacy 0/1 无对应料槽, legacy 2=发射药, legacy 3=输出。 */
    private static final int LEGACY_FOUR_SLOT_COUNT = 4;

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

    /**
     * 4->5 槽迁移 (F015) 待掉落队列: 旧档 legacy slot 0/1 (类型无关) 与非发射药的 legacy slot 2 内容无处安放,
     * 排队等首个 serverTick 用 Block.popResource 吐在方块位置, 不静默销毁。load() 期间 level 恒为 null 无法当场
     * popResource, 故须持久化跨重载保持 (迁移后、首次 tick 前关服不能真丢)。
     */
    private final List<ItemStack> pendingLegacyDrops = new ArrayList<>();

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

    /**
     * 反漏斗暴露: 任何 side 只给料槽 [0,3) 的 InsertOnlyRangedWrapper, 输出缓冲槽不暴露 (必须人手取)。
     * RangedWrapper 本身同时代理 insert 与 extract, 不覆写 extract 时漏斗能把底火/弹壳/弹头/发射药反抽走,
     * 产线静默停摆 (F051)。
     */
    private final LazyOptional<IItemHandler> inputOnlyHandler =
            LazyOptional.of(() -> new InsertOnlyRangedWrapper(inventory, SLOT_PRIMER, INPUT_SLOT_END));

    /** 军械台的内部 FE 缓冲。电网只 push 进来, 产线结算时从这里扣。 */
    private final MachineEnergyStorage energy =
            new MachineEnergyStorage(MunitionsConfig.BENCH_ENERGY_CAPACITY::get, this::setChanged);
    private final LazyOptional<MachineEnergyStorage> energyHandler = LazyOptional.of(() -> energy);

    /**
     * 只写料槽包装 (F051): 只覆写 {@link #extractItem}, insert 侧仍走基类的范围校验与 isItemValid。
     */
    private static final class InsertOnlyRangedWrapper extends RangedWrapper {
        InsertOnlyRangedWrapper(IItemHandlerModifiable inventory, int minSlot, int maxSlot) {
            super(inventory, minSlot, maxSlot);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }
    }

    private final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case DATA_SELECTED_CALIBER -> selectedCaliber == null ? -1 : selectedCaliber.index();
                case DATA_BUFFERED_ROUNDS -> bufferedRounds;
                case DATA_BUFFER_CAP -> bufferCap();
                case DATA_LOCKED -> locked ? 1 : 0;
                case DATA_REFINE_UNLOCKED -> refineUnlockedForOwnerCache ? 1 : 0;
                // 以秒过线 (审查 M-9): vanilla ClientboundContainerSetDataPacket 的 value 是 int16, 默认配置
                // L1 一批 = 57600 ticks 直发即符号回绕 (进度条恒空); 秒粒度上限 32767s=9.1h 足够, Menu 侧 x20 还原。
                case DATA_PRODUCTION_PROGRESS_TICKS -> productionProgressTicks() / 20;
                case DATA_PRODUCTION_REQUIRED_TICKS -> productionRequiredTicks() / 20;
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
        // 归属收敛 (审查 M-3, 同 tryStartCraft/cancelCraft/toggleContinuousCrafting): 路人点一下就会把
        // lastSettleTick 推到当前, 抹掉台主离线攒下的全部流逝时间 (五章离线补产唯一依据), 且会用路人等级污染
        // ownerLevelCache; 选口径须收敛到台主。canAccess 仍保留给开 GUI/取物用, 这里不能借用。
        if (!isOwner(player)) {
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
        // 归属收敛 (审查 M-3): 开工限台主 —— 产量等级/工费/经验三者天然同源于 owner, 消除
        // "点击者等级算产量、工费扣台主" 的跨账号等级门绕过面; 访客 (含未锁台) 只能看不能开。
        if (level == null || !isOwner(player) || craftingActive || selectedCaliber == null) {
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
        // 手动路径完全绕开 MunitionsProduction.settle(), 若不在这里单独设闸, 它就是电力限制的逃逸口。
        if (!energy.hasAtLeast(MunitionsProduction.feCostPerBatch(level0))) {
            return false;
        }

        // 原子结算 (审查 M-2): 开工帧只校验不扣料, 材料留在槽内, 完成帧与工费同帧一次性结算
        // ("扣不动则料不扣" 契约); 取消/中断因此天然零损失, 不需要退料路径。
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
        // 同 tryStartCraft: 取消限台主, 防路人作废台主批次 (materials 虽已零损失, 干预权仍收归 owner)。
        if (!isOwner(player) || !craftingActive) {
            return false;
        }
        clearActiveCraft();
        setMachineActive(false);
        setChanged();
        return true;
    }

    public boolean toggleContinuousCrafting(ServerPlayer player) {
        if (!isOwner(player)) {
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
        // F015 老档补料: load() 期间 level 恒为 null (BlockEntity.loadStatic 先 load 再 setLevel), 4->5 槽迁移
        // 时无法当场 popResource, 只能先记队列再等首个 tick 冲掉; 必须在 ownerUUID==null 早退之前处理, 否则无主台
        // 永远不还料。
        if (!pendingLegacyDrops.isEmpty()) {
            for (ItemStack drop : pendingLegacyDrops) {
                Block.popResource(level, worldPosition, drop);
            }
            pendingLegacyDrops.clear();
            setChanged();
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
        /*
         * 已移除的玩家实体等同离线, 一律不结算。
         *
         * 玩家死亡时旧 ServerPlayer 会被标记 RemovalReason.KILLED, Forge 随即 invalidate 它身上的
         * capability, 但在重生换上新实例之前它仍能从 PlayerList 里取到。此时读职业进度会命中
         * JobServiceImpl.require 的"capability 缺失"不变量并抛出 —— 那条抛出是对的 (活人身上取不到
         * capability 确实是故障), 错在这里把一个已经死掉的实体当活人交了过去。
         * 2026-08-18 真服连环崩服的根因即此: 台主一死, 军火台每 tick 抛一次, 看门狗补刀, MCSM 拉起再崩。
         * 不结算不丢产量: lastSettleTick 原样保留, 玩家重生后按经过的时间一次性补算。
         */
        if (owner.isRemoved()) {
            return;
        }
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
                primers, casings, bulletHeads, propellant, energy.getEnergyStored());
        // 复核 (major, F049 同源): active 必须与 settle 的判定同源 (含它的时间门), 不能只看料/缓冲/口径三道
        // 静态门 —— 台主持续在线时 serverTick 每 tick 都追算一次, elapsed 恒为 1 tick, 单 tick 换算的理论发数
        // 几乎恒不够一整批; 静态门此时恒真, 会把机器点成常亮/持续焊接音却一发都出不来。result.produced() 是
        // settle 本次是否真出弹的唯一真源, 直接复用, 不再另算一套可能与它不一致的判据。
        boolean active = result.produced();
        setMachineActive(active);
        playWeldSoundIfActive(now, active);

        if (!result.produced()) {
            lastSettleTick = now;
            setChanged();
            return;
        }

        // Charge before advancing lastSettleTick so a failed fee keeps the production window.
        // 复核 (major, F049 同源): 扣费失败不撤销本 tick 已点亮的 active/音效状态, 也不重置 nextWeldSoundTick。
        // 本 tick 的产出条件本就成立 (料/缓冲/时间三门都过了), 只是差信用点, 不是"没在产"; lastSettleTick 未推进
        // 故下一 tick elapsed 继续累积、result.produced() 大概率仍为 true, 若这里强制拉黑再在下一 tick 重新点亮,
        // 就是每 tick 一次熄灭再点亮 —— 音效节流被清零后每 tick 都重新达标, 造成 20 次/秒的音效轰炸 + 方块状态
        // 每 tick 两次 (主+副半块) 的更新包风暴。保留当前 active/节流状态即可: setPartActive 本身按值变判据去重
        // (已是 true 就不会重复发包), 音效则继续走 WELD_SOUND_MIN_INTERVAL 的正常节奏, 不因反复欠费而失效。
        if (!tryChargeWorkFee(owner, result.workFeeCredits())) {
            setChanged();
            return;
        }

        // 工费扣成功, 本段流逝已兑现为产出: 推进时间戳 (产出受料/缓冲夹断时不重复累积已兑现的流逝)。
        lastSettleTick = now;

        // 扣电与扣料同帧: settle 已按 energy.getEnergyStored() 夹过批数, 这里必然扣得动。
        energy.consume(result.feConsumed());

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
        // 产出落账后的持续指示灯 (与逐 tick 的 result.produced() 不同源): 反映"还有没有下一批的余粮" ——
        // 料/缓冲/口径三道静态门, 供玩家判断台子是否还需要补料, 对应 F049 原始诉求 ("出弹却熄火")。
        setMachineActive(canAccumulateProduction());
        setChanged();
    }

    /**
     * 双模式互斥 (审查 M-1): 返回 true 表示本帧由手动制作接管 (开工中), false 表示空闲 —— 调用方
     * {@link #settleForOwner} 据此放行被动挂机结算 (五章离线补产语义, benchSettle* GameTest 的契约)。
     * 旧实现所有路径恒返 true, 把整段被动结算短路成死代码, 离线补产灭失且 2 个 required GameTest 红。
     */
    private boolean settleManualCraft(ServerPlayer owner) {
        if (level == null) {
            return true;
        }
        if (!craftingActive || craftingCaliber == null) {
            return false;
        }
        int level0 = effectiveOwnerLevel(MunitionsLevels.munitionsLevel(owner));
        this.ownerLevelCache = level0;
        this.refineUnlockedForOwnerCache = MunitionsLevels.isRefineUnlocked(level0);

        long now = level.getGameTime();
        setMachineActive(true);
        playWeldSoundIfActive(now, true);
        int required = productionRequiredTicks();
        if (required <= 0 || now - craftingStartTick < required) {
            return true;
        }

        // 连续模式仅在本批成功落账后续批 (审查 M-2): 工费/材料失败即停机, 不空转不烧料 (作者语义: 没材料就停)。
        if (finishActiveCraft(owner) && continuousCrafting) {
            tryStartCraft(owner);
        }
        setChanged();
        return true;
    }

    /**
     * 完成帧原子结算 (审查 M-2): 查料 -> 查扣工费 -> 扣料 -> 产出, 任一环失败本批作废且材料分文不动
     * (与被动路径 munitions-01 同一 "扣不动则料不扣" 纪律)。返回 false 时调用方不得连续续批。
     */
    private boolean finishActiveCraft(ServerPlayer owner) {
        if (craftingCaliber == null) {
            clearActiveCraft();
            setMachineActive(false);
            return false;
        }
        // 材料在制作期间留在槽内 (开工帧只校验), 完成帧复查: 中途被取走则本批作废, 零损失。
        if (!hasBatchMaterials()) {
            clearActiveCraft();
            setMachineActive(false);
            return false;
        }
        int rounds = MunitionsProduction.roundsPerBatch(craftingCaliber, craftingOwnerLevel);
        // 电力与工费同帧先查后扣: 开工帧只校验不锁电, 制作期间电可能被别处抽走, 完成帧必须复查。
        int feCost = MunitionsProduction.feCostPerBatch(craftingOwnerLevel);
        if (!energy.hasAtLeast(feCost)) {
            clearActiveCraft();
            setMachineActive(false);
            return false;
        }
        long workFee = MunitionsProduction.workFee(rounds);
        if (!tryChargeWorkFee(owner, workFee)) {
            clearActiveCraft();
            setMachineActive(false);
            return false;
        }
        energy.consume(feCost);

        consume(SLOT_PRIMER, MunitionsConfig.RECIPE_PRIMER_COST.get());
        consume(SLOT_CASING, MunitionsConfig.RECIPE_CASING_COST.get());
        consume(SLOT_BULLET_HEAD, MunitionsConfig.RECIPE_BULLET_HEAD_COST.get());
        consume(SLOT_PROPELLANT, MunitionsConfig.RECIPE_PROPELLANT_COST.get());

        bufferedRounds += rounds;
        bufferedCaliber = craftingCaliber;
        MunitionsLevels.grantRawXp(owner, MunitionsProduction.produceXp(rounds));
        clearActiveCraft();
        refreshOutputStack();
        setMachineActive(false);
        return true;
    }

    private void clearActiveCraft() {
        craftingActive = false;
        craftingCaliber = null;
        craftingStartTick = 0L;
        nextWeldSoundTick = 0L;
        // 双模式衔接 (审查 M-1): 制作期间被动结算被短路, 若不推进时间戳, 回到空闲后被动路径会把
        // 制作耗时当挂机流逝再结算一遍 (双重产出)。制作段的时间已兑现为手动批产物, 此处一并翻页。
        if (level != null) {
            lastSettleTick = level.getGameTime();
            settleInitialized = true;
        }
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

    /**
     * 机器是否 "仍有余粮" (供 settleForOwner 产出落账后判定要不要继续点灯; 与逐 tick 的 active 不是同一件事)。
     * 复核 (major, F049): 这是一道粗粒度静态门 (选中口径 + 攒够一批材料 + 缓冲还有空间), 刻意不含
     * {@link MunitionsProduction#settle} 的时间门 —— 台主持续在线时 serverTick 每 tick 都追算一次, elapsed
     * 恒为 1 tick, 时间门几乎永远不够一整批; 若拿这三道静态门驱动逐 tick 的 "在产" 显示, 会把机器点成常亮却
     * 一发都出不来。故逐 tick 的 active 已改用 {@link MunitionsProduction.Result#produced()} (settle 本次是否
     * 真出弹的唯一真源), 本方法只在产出落账之后调用一次, 表达 "这批产完了, 还要不要继续亮着等下一批"。
     */
    private boolean canAccumulateProduction() {
        if (craftingActive) {
            return craftingCaliber != null;
        }
        return selectedCaliber != null && hasBatchMaterials()
                && bufferCap() - bufferedRounds >= MunitionsProduction.roundsPerBatch(selectedCaliber, ownerLevelCache);
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
        // yieldFactor 由 config defineInRange 下界 0.01 保证 > 0 (单一真源), 不再垫不可达的 0.0001 兜底
        // (那层 Math.max 若真触发会把所需时长放大万倍, 属掩错而非防御)。
        long rifleRoundsNeeded = (long) Math.ceil(perBatchRounds / caliber.yieldFactor());
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
     * NBT 存盘安全上限 (F001): ItemStack.save 用 putByte("Count", (byte) count) 存 Count, 128 起符号回绕
     * (480 存成 -32, 重载后该槽变空)。这与物品自报的 getMaxStackSize 是两条独立约束, 取更严的一条。
     */
    private static final int PERSISTENCE_SAFE_MAX_COUNT = 127;

    /**
     * 把缓冲发数物化成可视的 TACZ 弹 ItemStack 放进输出展示槽 (主人在线访问帧调; ModList isLoaded 守卫)。
     * TACZ 未加载 (dev) 时 materialize 返回 EMPTY, 输出槽留空 (产能逻辑照常累积 bufferedRounds, 真造弹在正式服验)。
     * TACZ builder 不钳上限 (javap 核实), 上限只能由本方法自己保证: 按 min(bufferedRounds, 物品自报上限, NBT
     * 存盘安全上限) 分栈, 单槽只展示一栈, 余量继续留在 bufferedRounds (权威), 由后续 refresh/取走后补。
     */
    private void refreshOutputStack() {
        if (bufferedCaliber == null || bufferedRounds <= 0) {
            setOutputStack(ItemStack.EMPTY);
            return;
        }
        ItemStack ammo = MunitionsAmmoFactory.materialize(bufferedCaliber, bufferedRounds);
        if (ammo.isEmpty()) {
            setOutputStack(ItemStack.EMPTY);
            return;
        }
        int itemMax = ammo.getMaxStackSize();
        if (itemMax > PERSISTENCE_SAFE_MAX_COUNT) {
            LOGGER.warn("[miningdim] munitions ammo {} reports getMaxStackSize()={} exceeding NBT-safe {} "
                            + "(check the gun pack's ammo json stack_size); clamping output stack",
                    ForgeRegistries.ITEMS.getKey(ammo.getItem()), itemMax, PERSISTENCE_SAFE_MAX_COUNT);
            itemMax = PERSISTENCE_SAFE_MAX_COUNT;
        }
        int stackCount = Math.min(bufferedRounds, itemMax);
        ammo.setCount(stackCount);
        setOutputStack(ammo);
    }

    /**
     * 唯一写 SLOT_OUTPUT 的入口 (F001): 落槽前断言不变量, 违反直接抛出而非静默钳制 —— 这是自家不变量,
     * 一旦触发说明上游分栈逻辑本身出了错, 不能被这里的兜底掩盖。
     */
    private void setOutputStack(ItemStack stack) {
        if (!stack.isEmpty() && stack.getCount() > stack.getMaxStackSize()) {
            throw new IllegalStateException("munitions bench output stack exceeds max stack size at "
                    + worldPosition + ": item=" + ForgeRegistries.ITEMS.getKey(stack.getItem())
                    + " count=" + stack.getCount() + " max=" + stack.getMaxStackSize());
        }
        inventory.setStackInSlot(SLOT_OUTPUT, stack);
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
        // 超扣不静默钳零 (审查 minor: Math.max 吞错): 取走量按计量口径不应超过权威缓冲, 超了说明计量或并发
        // 出了新 bug, 留取证日志再钳, 不给错误销毁证据。
        if (takenCount > bufferedRounds) {
            LOGGER.warn("[miningdim] munitions output over-take: taken={} > buffered={} at {} by {}",
                    takenCount, bufferedRounds, worldPosition, player.getGameProfile().getName());
            takenCount = bufferedRounds;
        }
        bufferedRounds -= takenCount;
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
            // F001 老档兜底: 缓冲打满时 settleForOwner 走 !result.produced() 早退, 不刷输出槽; load() 也不刷。
            // 于是老档里被 byte 截断成空的输出槽永不重建, 玩家几千发弹卡死在 bufferedRounds 里取不出。
            if (bufferedRounds > 0 && inventory.getStackInSlot(SLOT_OUTPUT).isEmpty()) {
                refreshOutputStack();
            }
        }
    }

    // ---- 反漏斗 capability ----

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return inputOnlyHandler.cast();
        }
        if (cap == ForgeCapabilities.ENERGY) {
            return energyHandler.cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        inputOnlyHandler.invalidate();
        energyHandler.invalidate();
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
    private static final String K_ENERGY = "energy";
    private static final String K_BUFFERED_CAL = "BufferedCaliber";
    private static final String K_LAST_SETTLE = "LastSettleTick";
    private static final String K_SETTLE_INIT = "SettleInitialized";
    private static final String K_OWNER_LEVEL = "OwnerLevelCache";
    private static final String K_CRAFTING_ACTIVE = "CraftingActive";
    private static final String K_CONTINUOUS = "ContinuousCrafting";
    private static final String K_CRAFTING_CALIBER = "CraftingCaliber";
    private static final String K_CRAFTING_LEVEL = "CraftingOwnerLevel";
    private static final String K_CRAFTING_START = "CraftingStartTick";
    private static final String K_PENDING_DROPS = "PendingLegacyDrops";
    private static final String K_INV_SIZE = "Size";

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
        CompoundTag energyTag = new CompoundTag();
        energy.save(energyTag);
        tag.put(K_ENERGY, energyTag);
        tag.putInt(K_BUFFERED_CAL, bufferedCaliber == null ? -1 : bufferedCaliber.index());
        tag.putLong(K_LAST_SETTLE, lastSettleTick);
        tag.putBoolean(K_SETTLE_INIT, settleInitialized);
        tag.putInt(K_OWNER_LEVEL, ownerLevelCache);
        tag.putBoolean(K_CRAFTING_ACTIVE, craftingActive);
        tag.putBoolean(K_CONTINUOUS, continuousCrafting);
        tag.putInt(K_CRAFTING_CALIBER, craftingCaliber == null ? -1 : craftingCaliber.index());
        tag.putInt(K_CRAFTING_LEVEL, craftingOwnerLevel);
        tag.putLong(K_CRAFTING_START, craftingStartTick);
        if (!pendingLegacyDrops.isEmpty()) {
            ListTag drops = new ListTag();
            for (ItemStack stack : pendingLegacyDrops) {
                CompoundTag stackTag = new CompoundTag();
                stack.save(stackTag);
                drops.add(stackTag);
            }
            tag.put(K_PENDING_DROPS, drops);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        ownerUUID = tag.hasUUID(K_OWNER) ? tag.getUUID(K_OWNER) : null;
        locked = tag.getBoolean(K_LOCKED);
        if (tag.contains(K_ENERGY, Tag.TAG_COMPOUND)) {
            energy.load(tag.getCompound(K_ENERGY));
        }
        pendingLegacyDrops.clear();
        ListTag drops = tag.getList(K_PENDING_DROPS, Tag.TAG_COMPOUND);
        for (int i = 0; i < drops.size(); i++) {
            pendingLegacyDrops.add(ItemStack.of(drops.getCompound(i)));
        }
        if (tag.contains(K_INV)) {
            CompoundTag invTag = tag.getCompound(K_INV);
            // 没有 Size 的 Inv 复合标签不可能由 ItemStackHandler.serializeNBT 写出, 形状不明无法安全迁移。
            // 复核 (blocker): 这里曾经 throw —— BlockEntity.loadStatic 对 load() 抛出的处理是把整个 BE 丢弃
            // (catch Throwable 后 return null, forge-1.20.1-47.4.20 sources 核实), 不是"拒绝这条 Inv", 后果
            // 是台主/缓冲/四格料全部清零, 比放过一段形状不明的 Inv 更糟。改为只丢弃这一段损坏的 Inv 数据
            // (库存清空, owner/buffer/selectedCaliber 等其余字段照常还原), 记错误日志, 不让局部数据损坏级联成
            // 整台消失。
            if (!invTag.contains(K_INV_SIZE, Tag.TAG_INT)) {
                LOGGER.error("[miningdim] munitions bench inventory tag is missing its serialized size at {}; "
                        + "discarding the corrupted inventory only (owner/buffer state preserved)", worldPosition);
            } else {
                int savedSlots = invTag.getInt(K_INV_SIZE);
                inventory.deserializeNBT(invTag);
                migrateInventoryShape(savedSlots);
            }
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

    /**
     * 库存形状迁移分派 (F015): 当前形状照旧处理, 旧 4 槽形状走专门迁移, 其余尺寸一律进待掉落队列 + 重置形状
     * (复核 blocker: 曾经在这里 throw 来"拒绝而非静默抹平", 但 load() 里的抛出会被 BlockEntity.loadStatic 吞掉、
     * 丢弃整台 BE, 比"抹平库存"更狠; 现在不静默销毁, 但也不再拿抛异常当拒绝手段)。
     */
    private void migrateInventoryShape(int savedSlots) {
        if (savedSlots == SLOT_COUNT) {
            clearInvalidInputSlots();
            return;
        }
        if (savedSlots == LEGACY_FOUR_SLOT_COUNT) {
            migrateLegacyFourSlot();
            return;
        }
        // 复核 (blocker, 与 K_INV_SIZE 缺失分支同一根因): 未知槽数曾经 throw, 而 load() 里的抛出会被
        // BlockEntity.loadStatic 吞掉、丢弃整台 BE (owner/缓冲/四格料全没), 比"抹平库存"更狠。inventory 此时已被
        // ItemStackHandler.deserializeNBT 按 savedSlots resize 并填好内容, 原样进待掉落队列 (不静默销毁), 再把
        // 库存重置到当前 5 槽形状, 只记错误日志。
        LOGGER.error("[miningdim] munitions bench inventory has unsupported size {} at {} (expected {} or legacy "
                        + "{}); queuing its contents for drop and resetting the inventory shape",
                savedSlots, worldPosition, SLOT_COUNT, LEGACY_FOUR_SLOT_COUNT);
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot).copy();
            if (!stack.isEmpty()) {
                pendingLegacyDrops.add(stack);
            }
        }
        inventory.setSize(SLOT_COUNT);
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            inventory.setStackInSlot(slot, ItemStack.EMPTY);
        }
    }

    /**
     * 旧 4 槽布局迁移 (F015): legacy 2/3 语义已知 (发射药/输出), 原样回填; legacy 0/1 在旧布局里无对应料槽,
     * 类型未知, 与非发射药的 legacy 2 一起进待掉落队列 (由 serverTick 首帧 popResource 吐出), 不再静默销毁。
     * 调用时 inventory 已被 ItemStackHandler.deserializeNBT 按存档 Size 自行 resize 到 4 槽 (load() 里
     * inventory.deserializeNBT(invTag) 已执行), 故直接从 inventory 读取即可, 无需另建临时 handler。
     */
    private void migrateLegacyFourSlot() {
        ItemStack legacySlot0 = inventory.getStackInSlot(0).copy();
        ItemStack legacySlot1 = inventory.getStackInSlot(1).copy();
        ItemStack legacyPropellant = inventory.getStackInSlot(2).copy();
        ItemStack legacyOutput = inventory.getStackInSlot(3).copy();

        inventory.setSize(SLOT_COUNT);
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            inventory.setStackInSlot(slot, ItemStack.EMPTY);
        }

        int queuedDrops = 0;
        if (!legacySlot0.isEmpty()) {
            pendingLegacyDrops.add(legacySlot0);
            queuedDrops++;
        }
        if (!legacySlot1.isEmpty()) {
            pendingLegacyDrops.add(legacySlot1);
            queuedDrops++;
        }
        boolean restoredPropellant = legacyPropellant.is(ModMunitionsItems.PROPELLANT.get());
        if (restoredPropellant) {
            inventory.setStackInSlot(SLOT_PROPELLANT, legacyPropellant);
        } else if (!legacyPropellant.isEmpty()) {
            pendingLegacyDrops.add(legacyPropellant);
            queuedDrops++;
        }
        if (!legacyOutput.isEmpty()) {
            queuedDrops += settleLegacyOutputStack(legacyOutput);
        }

        LOGGER.info("[miningdim] munitions bench migrated legacy 4-slot inventory at {}: propellant restored={}, "
                + "{} stack(s) queued for drop", worldPosition, restoredPropellant, queuedDrops);
    }

    /**
     * 旧档输出槽落新槽前钳上限 (复核 blocker, 与 F015 同一条链): 若把 legacyOutput 原样喂给 setOutputStack,
     * 其不变量断言在 count 超过物品自报上限时会抛出, 而 load() 路径里的抛出会被 BlockEntity.loadStatic 吞掉、
     * 丢弃整台 BE —— F001 修复前的旧档输出槽正是不受 TACZ 钳制的弹栈, count 很容易超过枪包 json 里的
     * stack_size (12g=36/308=48)。按 min(count, 物品自报上限, NBT 存盘安全上限) 落一栈进输出槽, 超出部分按
     * 同一上限分块进待掉落队列 (不静默销毁), 这条迁移路径不再有机会把不变量断言抛到 load() 里。
     *
     * @return 因超栈被排入待掉落队列的份数 (供调用方汇总日志)
     */
    private int settleLegacyOutputStack(ItemStack legacyOutput) {
        int cap = Math.min(legacyOutput.getMaxStackSize(), PERSISTENCE_SAFE_MAX_COUNT);
        int settled = Math.min(legacyOutput.getCount(), cap);
        if (settled > 0) {
            ItemStack head = legacyOutput.copy();
            head.setCount(settled);
            setOutputStack(head);
        }
        int overflow = legacyOutput.getCount() - settled;
        int queued = 0;
        while (overflow > 0) {
            int chunk = Math.min(overflow, cap);
            ItemStack drop = legacyOutput.copy();
            drop.setCount(chunk);
            pendingLegacyDrops.add(drop);
            overflow -= chunk;
            queued++;
        }
        return queued;
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
