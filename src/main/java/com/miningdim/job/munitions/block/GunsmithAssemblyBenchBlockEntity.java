package com.miningdim.job.munitions.block;

import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyServices;
import com.miningdim.job.munitions.ModMunitionsBlockEntities;
import com.miningdim.job.munitions.ModMunitionsSounds;
import com.miningdim.job.munitions.MunitionsConfig;
import com.miningdim.job.munitions.MunitionsLevels;
import com.miningdim.job.munitions.gunsmith.GunsmithAssemblyRecipe;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprint;
import com.miningdim.job.munitions.gunsmith.GunsmithGunFactory;
import com.miningdim.job.munitions.gunsmith.GunsmithGunDurability;
import com.miningdim.job.munitions.gunsmith.GunsmithGunStats;
import com.miningdim.job.munitions.gunsmith.GunsmithPlatform;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.job.munitions.menu.GunsmithAssemblyMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

public final class GunsmithAssemblyBenchBlockEntity extends BlockEntity implements MenuProvider {

    public static final int SLOT_BLUEPRINT = 0;
    public static final int SLOT_PART_BASE = 1;
    public static final int SLOT_OUTPUT = SLOT_PART_BASE + GunsmithPressPart.values().length;
    public static final int SLOT_COUNT = SLOT_OUTPUT + 1;
    public static final int ASSEMBLY_DURATION_TICKS = 160;

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/gunsmith-assembly");
    private static final int LEGACY_RIFLE_PART_COUNT = 6;
    private static final int LEGACY_RIFLE_SLOT_OUTPUT = SLOT_PART_BASE + LEGACY_RIFLE_PART_COUNT;
    private static final int LEGACY_RIFLE_SLOT_COUNT = LEGACY_RIFLE_SLOT_OUTPUT + 1;
    private static final int LEGACY_PRE_RECEIVER_SLOT_OUTPUT = 10;
    private static final int LEGACY_PRE_RECEIVER_SLOT_COUNT = LEGACY_PRE_RECEIVER_SLOT_OUTPUT + 1;
    private static final int LEGACY_PRE_BIPOD_SLOT_OUTPUT = 11;
    private static final int LEGACY_PRE_BIPOD_SLOT_COUNT = LEGACY_PRE_BIPOD_SLOT_OUTPUT + 1;
    private static final int LEGACY_PRE_FIRING_PIN_SLOT_OUTPUT = 12;
    private static final int LEGACY_PRE_FIRING_PIN_SLOT_COUNT = LEGACY_PRE_FIRING_PIN_SLOT_OUTPUT + 1;
    private static final String K_INVENTORY = "Inventory";
    private static final String K_HANDLER_SIZE = "Size";
    private static final String K_PENDING_RESULT = "PendingResult";
    private static final String K_ANIMATION_START = "AnimationStartTick";
    private static final String K_ANIMATION_END = "AnimationEndTick";
    private static final String K_WELD_SOUND = "NextWeldSoundTick";
    /** 同步标签里唯一的键: 台面上摆哪把枪; "" = 不摆。只进 getUpdateTag, 不进存档。 */
    private static final String K_DISPLAY_GUN = "DisplayGun";
    /** TaCZ 枪械 NBT 根上的枪 id 键 (GunItemDataAccessor 的 "GunId"); 这里只按字符串读, 不引 TaCZ 类。 */
    private static final String K_TACZ_GUN_ID = "GunId";

    // 焊接音跟着机械臂程序 (GunsmithArmProgram) 的点焊时刻走, 需要知道动画从哪一 tick 开始。
    private long animationStartTick;
    private long animationEndTick;
    /** 下一声焊接音的绝对 tick; 0 = 不播。 */
    private long nextWeldSoundTick;
    private ItemStack pendingResult = ItemStack.EMPTY;
    private boolean pendingBlockedReported;
    /**
     * 仅客户端: 看到 ACTIVE 由假变真的那一 tick, 即机械臂程序的起点; 0 = 没看到翻转 (区块加载时已在装配中)。
     * 开始/结束时刻不同步到客户端, 但方块状态的更新包本身就是开工信号, 在这里记下它比在渲染器首帧才开始计时准:
     * 玩家开工时没看着组装台 (转头、从 Web UI 开工) 也不会让手臂落后, 到时刻被服务端切回待机时才不会从程序中途瞬移。
     */
    private long clientProgramStartTick;
    /**
     * 仅服务端: 最近一次推给客户端的展示枪 ("" = 不摆枪); null = 本次加载后还没推过, 下一次检查必推。
     * 只用来去重, 区块包带的是 getUpdateTag() 当场算的值, 不经过这里。每次 load() 都清回 null (见 load())。
     */
    @Nullable
    private String syncedDisplayGun;
    /** 仅客户端: 服务端经更新标签推来的展示枪 id; null = 台面不摆枪。 */
    @Nullable
    private ResourceLocation clientDisplayGunId;

    private final ItemStackHandler inventory = new ItemStackHandler(SLOT_COUNT) {
        @Override
        protected void onContentsChanged(int slot) {
            if (slot == SLOT_OUTPUT) {
                pendingBlockedReported = false;
            }
            setChanged();
            // 客户端菜单同步写槽同样会走到这里 (load() 的反序列化走 onLoad, 不走这里), 服务端守卫在方法里。
            syncDisplayIfChanged();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot == SLOT_BLUEPRINT) {
                return GunsmithAssemblyRecipe.isBlueprint(stack)
                        || GunsmithGunDurability.isManagedGun(stack);
            }
            if (slot >= SLOT_PART_BASE && slot < SLOT_OUTPUT) {
                ItemStack inputStack = getStackInSlot(SLOT_BLUEPRINT);
                GunsmithPressPart part = partForSlot(slot);
                if (GunsmithAssemblyRecipe.isBlueprint(inputStack)) {
                    GunsmithBlueprint blueprint = GunsmithAssemblyRecipe.blueprint(inputStack);
                    return blueprint.requiredParts().contains(part)
                            && GunsmithAssemblyRecipe.matchesPart(stack, part, blueprint.platform());
                }
                // 容器谓词跑在玩家点击链上, 只能走不抛的 tryManaged: 读不出来的枪一律判"不可放入" (审查 2)。
                GunsmithGunDurability.Managed managed = GunsmithGunDurability.tryManaged(inputStack);
                if (managed != null) {
                    return part == GunsmithGunDurability.repairPart(
                                    managed.stats().blueprint().platform())
                            && GunsmithGunDurability.isRepairReplacement(managed.stats(), stack);
                }
                return false;
            }
            return false;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }
    };

    public GunsmithAssemblyBenchBlockEntity(BlockPos pos, BlockState state) {
        super(ModMunitionsBlockEntities.GUNSMITH_ASSEMBLY_BENCH.get(), pos, state);
    }

    public static int slotForPart(GunsmithPressPart part) {
        return SLOT_PART_BASE + part.index();
    }

    private static GunsmithPressPart partForSlot(int slot) {
        int index = slot - SLOT_PART_BASE;
        GunsmithPressPart[] parts = GunsmithPressPart.values();
        if (index < 0 || index >= parts.length) {
            throw new IllegalArgumentException("slot is not a gunsmith part slot: " + slot);
        }
        return parts[index];
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public boolean isPartSlotVisible(GunsmithPressPart part) {
        Objects.requireNonNull(part, "part");
        ItemStack inputStack = inventory.getStackInSlot(SLOT_BLUEPRINT);
        if (GunsmithAssemblyRecipe.isBlueprint(inputStack)) {
            return GunsmithAssemblyRecipe.blueprint(inputStack).requiredParts().contains(part);
        }
        GunsmithGunDurability.Managed managed = GunsmithGunDurability.tryManaged(inputStack);
        if (managed != null) {
            return GunsmithGunDurability.repairPart(managed.stats().blueprint().platform()) == part;
        }
        return false;
    }

    public boolean tryStartAssembly(ServerPlayer player) {
        if (GunsmithGunDurability.isManagedGun(inventory.getStackInSlot(SLOT_BLUEPRINT))) {
            return tryStartRepair(player, ASSEMBLY_DURATION_TICKS);
        }
        return tryStartAssembly(player, GunsmithGunFactory::materialize, ASSEMBLY_DURATION_TICKS);
    }

    boolean tryStartRepair(ServerPlayer player, int durationTicks) {
        Objects.requireNonNull(player, "player");
        if (durationTicks <= 0) {
            throw new IllegalArgumentException("durationTicks must be positive");
        }
        if (!MunitionsConfig.GUNSMITH_ENABLED.get()) {
            player.displayClientMessage(Component.translatable("message.miningdim.gunsmith.disabled"), true);
            return false;
        }
        // 维修与装配是同一台机器上的两条产线, 各自都得有等级门和工费 sink。少了这两道, 1 级号
        // (甚至没有军火商职业的号) 就能开免费修枪铺, 把装配侧的 L5 门和 5000 CP 销毁一起架空 (审查 24)。
        int unlockLevel = MunitionsConfig.repairUnlockLevel();
        if (MunitionsLevels.munitionsLevel(player) < unlockLevel) {
            player.displayClientMessage(Component.translatable(
                    "message.miningdim.gunsmith_repair.level_locked", unlockLevel), true);
            return false;
        }
        if (isAnimating() || !pendingResult.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("message.miningdim.gunsmith_assembly_bench.busy"), true);
            return false;
        }
        if (!inventory.getStackInSlot(SLOT_OUTPUT).isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("message.miningdim.gunsmith_assembly_bench.output_blocked"), true);
            return false;
        }
        ItemStack gun = inventory.getStackInSlot(SLOT_BLUEPRINT);
        GunsmithGunDurability.Managed managed = GunsmithGunDurability.tryManaged(gun);
        if (managed == null) {
            player.displayClientMessage(
                    Component.translatable("message.miningdim.gunsmith_repair.missing_gun"), true);
            return false;
        }

        GunsmithGunDurability.RepairPreview preview = GunsmithGunDurability.repairPreview(managed);
        if (!preview.available()) {
            player.displayClientMessage(Component.translatable(switch (preview.status()) {
                case FULL -> "message.miningdim.gunsmith_repair.full";
                case INSUFFICIENT_WEAR -> "message.miningdim.gunsmith_repair.insufficient_wear";
                case EXHAUSTED -> "message.miningdim.gunsmith_repair.exhausted";
                case AVAILABLE -> throw new IllegalStateException("available repair reached rejection branch");
            }), true);
            return false;
        }

        GunsmithPressPart repairPart = preview.requiredPart();
        ItemStack replacement = inventory.getStackInSlot(slotForPart(repairPart));
        if (!GunsmithAssemblyRecipe.matchesPart(replacement, repairPart,
                managed.stats().blueprint().platform())) {
            player.displayClientMessage(Component.translatable(
                    "message.miningdim.gunsmith_repair.missing_part",
                    Component.translatable(repairPart.labelKey())), true);
            return false;
        }
        // 槽位谓词只在放入那一刻校验, 换枪后留在槽里的旧件仍会走到这里, 故权威侧必须再判一次降级 (审查 30)。
        if (!GunsmithGunDurability.isRepairReplacement(managed.stats(), replacement)) {
            player.displayClientMessage(Component.translatable(
                    "message.miningdim.gunsmith_repair.part_downgrade",
                    Component.translatable(repairPart.labelKey())), true);
            return false;
        }

        ItemStack result = gun.copyWithCount(1);
        GunsmithGunDurability.RepairResult repaired = GunsmithGunDurability.repair(result);
        if (!repaired.repaired()) {
            throw new IllegalStateException("Validated gunsmith repair did not produce a repaired gun");
        }
        long fee = MunitionsConfig.repairWorkFeeCredits();
        if (!tryChargeWorkFee(player, fee)) {
            player.displayClientMessage(
                    Component.translatable("message.miningdim.gunsmith.work_fee_unaffordable", fee), true);
            return false;
        }
        inventory.extractItem(SLOT_BLUEPRINT, 1, false);
        inventory.extractItem(slotForPart(repairPart), 1, false);
        pendingResult = result;
        syncDisplayIfChanged();
        beginAnimation(durationTicks);
        player.closeContainer();
        player.displayClientMessage(Component.translatable(
                "message.miningdim.gunsmith_repair.started",
                repaired.after().maximum()), true);
        return true;
    }

    boolean tryStartAssembly(ServerPlayer player, ItemStack baseGun, int durationTicks) {
        Objects.requireNonNull(baseGun, "baseGun");
        return tryStartAssembly(player, (blueprintStack, parts) -> baseGun, durationTicks);
    }

    private boolean tryStartAssembly(ServerPlayer player,
                                     BiFunction<ItemStack, Map<GunsmithPressPart, ItemStack>, ItemStack> gunFactory,
                                     int durationTicks) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(gunFactory, "gunFactory");
        if (durationTicks <= 0) {
            throw new IllegalArgumentException("durationTicks must be positive");
        }
        if (!MunitionsConfig.GUNSMITH_ENABLED.get()) {
            player.displayClientMessage(Component.translatable("message.miningdim.gunsmith.disabled"), true);
            return false;
        }
        if (!MunitionsLevels.isAssemblyUnlocked(MunitionsLevels.munitionsLevel(player))) {
            player.displayClientMessage(Component.translatable("message.miningdim.gunsmith_assembly_bench.level_locked",
                    MunitionsConfig.ASSEMBLY_UNLOCK_LEVEL.get()), true);
            return false;
        }
        if (isAnimating() || !pendingResult.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("message.miningdim.gunsmith_assembly_bench.busy"), true);
            return false;
        }
        if (!inventory.getStackInSlot(SLOT_OUTPUT).isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("message.miningdim.gunsmith_assembly_bench.output_blocked"), true);
            return false;
        }
        ItemStack blueprintStack = inventory.getStackInSlot(SLOT_BLUEPRINT);
        if (!GunsmithAssemblyRecipe.isBlueprint(blueprintStack)) {
            player.displayClientMessage(
                    Component.translatable("message.miningdim.gunsmith_assembly_bench.missing_blueprint"), true);
            return false;
        }
        GunsmithBlueprint blueprint = GunsmithAssemblyRecipe.blueprint(blueprintStack);
        GunsmithPlatform platform = blueprint.platform();

        EnumMap<GunsmithPressPart, ItemStack> parts = snapshotParts();
        for (GunsmithPressPart part : blueprint.requiredParts()) {
            if (!GunsmithAssemblyRecipe.matchesPart(parts.get(part), part, platform)) {
                player.displayClientMessage(Component.translatable(
                        "message.miningdim.gunsmith_assembly_bench.missing_part",
                        Component.translatable(part.labelKey())), true);
                return false;
            }
        }
        ItemStack baseGun = Objects.requireNonNull(gunFactory.apply(blueprintStack, parts),
                "gunFactory returned null for " + blueprint.gunId());
        if (baseGun.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("message.miningdim.gunsmith_blueprint.tacz_missing"), true);
            return false;
        }

        ItemStack result = GunsmithAssemblyRecipe.assemble(baseGun, blueprintStack, parts);
        long fee = MunitionsConfig.ASSEMBLY_WORK_FEE_CREDITS.get();
        if (!tryChargeWorkFee(player, fee)) {
            player.displayClientMessage(
                    Component.translatable("message.miningdim.gunsmith.work_fee_unaffordable", fee), true);
            return false;
        }
        for (GunsmithPressPart part : blueprint.requiredParts()) {
            inventory.extractItem(slotForPart(part), 1, false);
        }
        pendingResult = result;
        syncDisplayIfChanged();
        beginAnimation(durationTicks);
        player.closeContainer();
        player.displayClientMessage(
                Component.translatable("message.miningdim.gunsmith_assembly_bench.started"), true);
        return true;
    }

    /** 工费 sink: 经 {@link EconomyServices} 定位器先查后扣; 经济未注入或 cost<=0 放行 (不阻塞核心循环)。 */
    private boolean tryChargeWorkFee(ServerPlayer player, long cost) {
        if (cost <= 0L || !EconomyServices.isRegistered()) {
            return true;
        }
        return EconomyServices.economyService().tryCharge(player, Currency.CREDIT, cost);
    }

    private EnumMap<GunsmithPressPart, ItemStack> snapshotParts() {
        EnumMap<GunsmithPressPart, ItemStack> parts = new EnumMap<>(GunsmithPressPart.class);
        for (GunsmithPressPart part : GunsmithPressPart.values()) {
            parts.put(part, inventory.getStackInSlot(slotForPart(part)).copyWithCount(1));
        }
        return parts;
    }

    private void beginAnimation(int durationTicks) {
        if (level == null || level.isClientSide) {
            throw new IllegalStateException("assembly can only start on the logical server");
        }
        long now = level.getGameTime();
        animationStartTick = now;
        animationEndTick = now + durationTicks;
        // 不在开工瞬间播: 客户端机械臂此时还在去取件, 声音要等它真的在枪上点焊时才响。
        nextWeldSoundTick = weldSoundTickAfter(now - 1L);
        setActiveState(true);
        setChanged();
    }

    /** 程序里晚于 after 的下一次点焊 (绝对 tick); 程序没有点焊时为 0。落在动画结束之后的自然不会播到。 */
    private long weldSoundTickAfter(long after) {
        long offset = GunsmithArmProgram.nextWeldTickAfter(after - animationStartTick);
        return offset < 0L ? 0L : animationStartTick + offset;
    }

    public void serverTick() {
        if (level == null || level.isClientSide) {
            return;
        }
        if (animationEndTick == 0L) {
            if (getBlockState().getValue(GunsmithAssemblyBenchBlock.ACTIVE)) {
                setActiveState(false);
            }
            finishPendingResult();
            return;
        }

        long now = level.getGameTime();
        if (now >= animationEndTick) {
            animationStartTick = 0L;
            animationEndTick = 0L;
            nextWeldSoundTick = 0L;
            setActiveState(false);
            finishPendingResult();
            setChanged();
            return;
        }
        setActiveState(true);
        if (nextWeldSoundTick > 0L && now >= nextWeldSoundTick) {
            playWeldSound();
            nextWeldSoundTick = weldSoundTickAfter(now);
        }
    }

    private void finishPendingResult() {
        if (pendingResult.isEmpty()) {
            return;
        }
        if (!inventory.getStackInSlot(SLOT_OUTPUT).isEmpty()) {
            if (!pendingBlockedReported) {
                LOGGER.error("Assembly output blocked at {} while a pending result exists", worldPosition);
                pendingBlockedReported = true;
            }
            return;
        }
        inventory.setStackInSlot(SLOT_OUTPUT, pendingResult);
        pendingResult = ItemStack.EMPTY;
        pendingBlockedReported = false;
        setChanged();
        syncDisplayIfChanged();
    }

    public boolean isAnimating() {
        if (level == null) {
            return animationEndTick > 0L;
        }
        if (level.isClientSide) {
            return getBlockState().getValue(GunsmithAssemblyBenchBlock.ACTIVE);
        }
        return animationEndTick > level.getGameTime();
    }

    /** 客户端机械臂程序的起点 (游戏 tick); 0 = 未知, 渲染器退回到首帧计时。 */
    public long clientProgramStartTick() {
        return clientProgramStartTick;
    }

    /**
     * 台面上该摆哪把枪 (服务端权威): pendingResult (装配/维修进行中, 或完工但成品槽被占) → 成品槽 →
     * 图纸槽里待修的托管枪 → 图纸槽里的图纸 → 不摆。按格子占用定优先级, 占着的那格读不出来就不摆, 不往下退。
     *
     * 客户端的背包只经菜单同步且早已过期, 渲染器读的是同步过去的 {@link #clientDisplayGunId()}。
     * 只推 id 不推整把枪: 零件与耐久 NBT 不外发给围观玩家, 展示用的 TaCZ 枪由客户端按 id 自己造。
     * 本方法跑在玩家点击链上 (onContentsChanged), 容器谓词挡不住 setStackInSlot 与旧存档里的怪东西,
     * 所以一律走不抛的解析入口, 读不出来就当没有枪 (审查 2 同一条崩溃链)。
     */
    @Nullable
    public ResourceLocation displayGunId() {
        if (!pendingResult.isEmpty()) {
            return gunDisplayId(pendingResult);
        }
        ItemStack output = inventory.getStackInSlot(SLOT_OUTPUT);
        if (!output.isEmpty()) {
            return gunDisplayId(output);
        }
        ItemStack input = inventory.getStackInSlot(SLOT_BLUEPRINT);
        if (GunsmithGunDurability.isManagedGun(input)) {
            return gunDisplayId(input);
        }
        if (GunsmithAssemblyRecipe.isBlueprint(input)) {
            // 只按图纸推算成品 id (旧 M4 模板 → miningdim:m4a1_gunsmith), 绝不为了展示去物化一把真枪。
            return GunsmithAssemblyRecipe.assembledGunId(input);
        }
        return null;
    }

    /**
     * 一把枪在台面上的展示 id, 不碰 TaCZ 类: 先认 TaCZ 自己的 GunId (TaCZ 按它挑模型, 枪匠三连发等
     * miningdim 枪 id 也都挂着 TaCZ 的 display), 没有再退到枪匠 NBT 里的 gunId (GameTest 拿铁锄当基础枪, 只有后者)。
     */
    @Nullable
    private static ResourceLocation gunDisplayId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(K_TACZ_GUN_ID, Tag.TAG_STRING)) {
            ResourceLocation taczGunId = parseGunId(tag.getString(K_TACZ_GUN_ID));
            if (taczGunId != null) {
                return taczGunId;
            }
        }
        GunsmithGunStats stats = GunsmithGunStats.tryFrom(stack);
        return stats == null ? null : stats.gunId();
    }

    /** "" 不能交给 tryParse: 它会解成 minecraft: 这个合法但空路径的 id。 */
    @Nullable
    private static ResourceLocation parseGunId(String encoded) {
        return encoded.isEmpty() ? null : ResourceLocation.tryParse(encoded);
    }

    private static String encodeGunId(@Nullable ResourceLocation gunId) {
        return gunId == null ? "" : gunId.toString();
    }

    /** 客户端渲染器读的展示枪 id, 来自服务端的更新标签; null = 台面不摆枪。 */
    @Nullable
    public ResourceLocation clientDisplayGunId() {
        return clientDisplayGunId;
    }

    /**
     * 展示枪变了才发一次方块更新: UPDATE_CLIENTS 只把这一格标脏, 本 tick 末合并成一个方块实体数据包,
     * 包体取发送那一刻的 getUpdateTag(), 所以同一次点击里的中间态 (先抽走零件、后写 pendingResult) 不会各发一包。
     * 仅服务端: 客户端菜单同步写槽也会触发 onContentsChanged, 还没挂进世界的实例没有 level, 都在这里挡掉。
     * load() 不会走到这里 (背包反序列化走 onLoad), 但它并不总在 level 为 null 时发生: 区块读盘时是, 而
     * /data merge block 是在带 level 的活实例上原地 load() 再由原版自己发更新, 所以 load() 末尾要清掉去重值。
     */
    private void syncDisplayIfChanged() {
        if (level == null || level.isClientSide) {
            return;
        }
        String displayGun = encodeGunId(displayGunId());
        if (displayGun.equals(syncedDisplayGun)) {
            return;
        }
        syncedDisplayGun = displayGun;
        BlockState state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
    }

    /** 仅供 GameTest 断言"展示枪变了确实推出去了": 最近一次推送的值, "" = 不摆枪, null = 本次加载后还没推过。 */
    @Nullable
    String syncedDisplayGunForTest() {
        return syncedDisplayGun;
    }

    // 客户端收到方块更新时 LevelChunk.setBlockState 会把新状态交给已有的方块实体, 这是客户端唯一能准确看到开工时刻的地方。
    @Override
    @SuppressWarnings("deprecation")
    public void setBlockState(BlockState state) {
        boolean wasActive = isActive(getBlockState());
        super.setBlockState(state);
        if (level == null || !level.isClientSide) {
            return;
        }
        boolean active = isActive(state);
        if (active && !wasActive) {
            clientProgramStartTick = level.getGameTime();
        } else if (!active) {
            clientProgramStartTick = 0L;
        }
    }

    private static boolean isActive(BlockState state) {
        return state.hasProperty(GunsmithAssemblyBenchBlock.ACTIVE) && state.getValue(GunsmithAssemblyBenchBlock.ACTIVE);
    }

    private void setActiveState(boolean active) {
        if (level == null) {
            return;
        }
        BlockState state = getBlockState();
        if (state.getBlock() instanceof GunsmithAssemblyBenchBlock
                && GunsmithAssemblyBenchBlock.isMain(state)) {
            GunsmithAssemblyBenchBlock.setStructureActive(level, worldPosition, state, active);
        }
    }

    private void playWeldSound() {
        if (level == null) {
            return;
        }
        float pitch = 0.94F + level.random.nextFloat() * 0.14F;
        level.playSound(null, worldPosition, ModMunitionsSounds.MUNITIONS_BENCH_WELD.get(),
                SoundSource.BLOCKS, 0.34F, pitch);
    }

    public List<ItemStack> dropContents() {
        List<ItemStack> drops = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack extracted = inventory.extractItem(slot, Integer.MAX_VALUE, false);
            if (!extracted.isEmpty()) {
                drops.add(extracted);
            }
        }
        if (!pendingResult.isEmpty()) {
            drops.add(pendingResult);
            pendingResult = ItemStack.EMPTY;
        }
        setChanged();
        syncDisplayIfChanged();
        return drops;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.miningdim.gunsmith_assembly_bench");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory playerInventory, Player player) {
        return new GunsmithAssemblyMenu(windowId, playerInventory, worldPosition);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put(K_INVENTORY, inventory.serializeNBT());
        if (!pendingResult.isEmpty()) {
            tag.put(K_PENDING_RESULT, pendingResult.save(new CompoundTag()));
        }
        tag.putLong(K_ANIMATION_START, animationStartTick);
        tag.putLong(K_ANIMATION_END, animationEndTick);
        tag.putLong(K_WELD_SOUND, nextWeldSoundTick);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        // Missing inventory/pending keys are the initial state for benches placed before the assembly UI existed.
        if (tag.contains(K_INVENTORY, Tag.TAG_COMPOUND)) {
            loadInventory(tag.getCompound(K_INVENTORY));
        }
        pendingResult = tag.contains(K_PENDING_RESULT, Tag.TAG_COMPOUND)
                ? ItemStack.of(tag.getCompound(K_PENDING_RESULT))
                : ItemStack.EMPTY;
        animationEndTick = tag.getLong(K_ANIMATION_END);
        // 保留焊接音节拍, 避免重载后首个 serverTick 补播一声离拍焊接音。(审查 m-4)
        nextWeldSoundTick = tag.getLong(K_WELD_SOUND);
        if (tag.contains(K_ANIMATION_START, Tag.TAG_LONG)) {
            animationStartTick = tag.getLong(K_ANIMATION_START);
        } else if (animationEndTick > 0L) {
            // 旧存档没有开始时刻: 装配与维修都是固定时长, 由结束时刻倒推; 旧的定时节拍对齐到程序里不早于它的那次点焊。
            animationStartTick = animationEndTick - ASSEMBLY_DURATION_TICKS;
            nextWeldSoundTick = weldSoundTickAfter(Math.max(nextWeldSoundTick, animationStartTick) - 1L);
        } else {
            animationStartTick = 0L;
        }
        pendingBlockedReported = false;
        // 原地 load() (如 /data merge block) 之后客户端拿到的是新内容, 旧去重值作废: 否则换回同一把枪时会被当成"没变"吞掉。
        // 只清字段, 不碰 level, 也不在这里发包 (读盘时没有 level; 原地 load 的调用方自己会发更新)。
        syncedDisplayGun = null;
    }

    private void loadInventory(CompoundTag serializedInventory) {
        if (!serializedInventory.contains(K_HANDLER_SIZE, Tag.TAG_INT)) {
            throw new IllegalStateException("Gunsmith assembly inventory is missing its serialized size at "
                    + worldPosition);
        }
        int serializedSize = serializedInventory.getInt(K_HANDLER_SIZE);
        if (serializedSize == SLOT_COUNT) {
            inventory.deserializeNBT(serializedInventory);
            return;
        }
        if (serializedSize == LEGACY_PRE_FIRING_PIN_SLOT_COUNT) {
            migratePreFiringPinInventory(serializedInventory);
            LOGGER.info("Migrated pre-firing-pin gunsmith assembly inventory at {} from {} to {} slots",
                    worldPosition, LEGACY_PRE_FIRING_PIN_SLOT_COUNT, SLOT_COUNT);
            return;
        }
        if (serializedSize == LEGACY_PRE_BIPOD_SLOT_COUNT) {
            migratePreBipodInventory(serializedInventory);
            LOGGER.info("Migrated pre-bipod gunsmith assembly inventory at {} from {} to {} slots",
                    worldPosition, LEGACY_PRE_BIPOD_SLOT_COUNT, SLOT_COUNT);
            return;
        }
        if (serializedSize == LEGACY_PRE_RECEIVER_SLOT_COUNT) {
            migratePreReceiverInventory(serializedInventory);
            LOGGER.info("Migrated pre-receiver gunsmith assembly inventory at {} from {} to {} slots",
                    worldPosition, LEGACY_PRE_RECEIVER_SLOT_COUNT, SLOT_COUNT);
            return;
        }
        // Saves from the rifle-only assembly bench used six part slots and stored output in slot 7.
        if (serializedSize == LEGACY_RIFLE_SLOT_COUNT) {
            migrateLegacyRifleInventory(serializedInventory);
            LOGGER.info("Migrated rifle-only gunsmith assembly inventory at {} from {} to {} slots",
                    worldPosition, LEGACY_RIFLE_SLOT_COUNT, SLOT_COUNT);
            return;
        }
        throw new IllegalStateException("Unsupported gunsmith assembly inventory size " + serializedSize
                + " at " + worldPosition + "; expected " + SLOT_COUNT + ", legacy "
                + LEGACY_PRE_FIRING_PIN_SLOT_COUNT + ", legacy " + LEGACY_PRE_BIPOD_SLOT_COUNT
                + ", legacy " + LEGACY_PRE_RECEIVER_SLOT_COUNT
                + " or legacy " + LEGACY_RIFLE_SLOT_COUNT);
    }

    private void migratePreFiringPinInventory(CompoundTag serializedInventory) {
        ItemStackHandler legacyInventory = new ItemStackHandler(LEGACY_PRE_FIRING_PIN_SLOT_COUNT);
        legacyInventory.deserializeNBT(serializedInventory);

        ItemStackHandler migratedInventory = new ItemStackHandler(SLOT_COUNT);
        for (int slot = SLOT_BLUEPRINT; slot < LEGACY_PRE_FIRING_PIN_SLOT_OUTPUT; slot++) {
            migratedInventory.setStackInSlot(slot, legacyInventory.getStackInSlot(slot).copy());
        }
        migratedInventory.setStackInSlot(SLOT_OUTPUT,
                legacyInventory.getStackInSlot(LEGACY_PRE_FIRING_PIN_SLOT_OUTPUT).copy());
        inventory.deserializeNBT(migratedInventory.serializeNBT());
    }

    private void migratePreBipodInventory(CompoundTag serializedInventory) {
        ItemStackHandler legacyInventory = new ItemStackHandler(LEGACY_PRE_BIPOD_SLOT_COUNT);
        legacyInventory.deserializeNBT(serializedInventory);

        ItemStackHandler migratedInventory = new ItemStackHandler(SLOT_COUNT);
        for (int slot = SLOT_BLUEPRINT; slot < LEGACY_PRE_BIPOD_SLOT_OUTPUT; slot++) {
            migratedInventory.setStackInSlot(slot, legacyInventory.getStackInSlot(slot).copy());
        }
        migratedInventory.setStackInSlot(SLOT_OUTPUT,
                legacyInventory.getStackInSlot(LEGACY_PRE_BIPOD_SLOT_OUTPUT).copy());
        inventory.deserializeNBT(migratedInventory.serializeNBT());
    }

    private void migratePreReceiverInventory(CompoundTag serializedInventory) {
        ItemStackHandler legacyInventory = new ItemStackHandler(LEGACY_PRE_RECEIVER_SLOT_COUNT);
        legacyInventory.deserializeNBT(serializedInventory);

        ItemStackHandler migratedInventory = new ItemStackHandler(SLOT_COUNT);
        for (int slot = SLOT_BLUEPRINT; slot < LEGACY_PRE_RECEIVER_SLOT_OUTPUT; slot++) {
            migratedInventory.setStackInSlot(slot, legacyInventory.getStackInSlot(slot).copy());
        }
        migratedInventory.setStackInSlot(SLOT_OUTPUT,
                legacyInventory.getStackInSlot(LEGACY_PRE_RECEIVER_SLOT_OUTPUT).copy());
        inventory.deserializeNBT(migratedInventory.serializeNBT());
    }

    private void migrateLegacyRifleInventory(CompoundTag serializedInventory) {
        ItemStackHandler legacyInventory = new ItemStackHandler(LEGACY_RIFLE_SLOT_COUNT);
        legacyInventory.deserializeNBT(serializedInventory);

        ItemStackHandler migratedInventory = new ItemStackHandler(SLOT_COUNT);
        for (int slot = SLOT_BLUEPRINT; slot < LEGACY_RIFLE_SLOT_OUTPUT; slot++) {
            migratedInventory.setStackInSlot(slot, legacyInventory.getStackInSlot(slot).copy());
        }
        migratedInventory.setStackInSlot(SLOT_OUTPUT,
                legacyInventory.getStackInSlot(LEGACY_RIFLE_SLOT_OUTPUT).copy());
        inventory.deserializeNBT(migratedInventory.serializeNBT());
    }

    /**
     * 区块包与 {@link #getUpdatePacket()} 共用的同步标签, 只有展示枪一个键: 背包、pendingResult、动画时刻一概不发。
     * 不摆枪时写 "" 而不是省掉键 —— 空标签在区块包和方块实体数据包里都会被压成 null 丢弃, 客户端就收不到"枪没了"。
     */
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString(K_DISPLAY_GUN, encodeGunId(displayGunId()));
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // 两条客户端入口都只读展示枪。默认实现整段走 load(), 会拿这份只有 DisplayGun 的标签把 pendingResult
    // 与动画时刻清成零值; 反过来把整份存档当同步标签发, 又会在客户端重跑存档迁移并覆盖打开着的菜单正在用的背包。
    @Override
    public void handleUpdateTag(CompoundTag tag) {
        readDisplayGun(tag);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        // 包里的空标签已被压成 null; 本方块实体的标签从不为空, 真收到 null 也按"不摆枪"清掉, 不能像默认实现那样忽略。
        readDisplayGun(packet.getTag());
    }

    private void readDisplayGun(@Nullable CompoundTag tag) {
        clientDisplayGunId = tag != null && tag.contains(K_DISPLAY_GUN, Tag.TAG_STRING)
                ? parseGunId(tag.getString(K_DISPLAY_GUN))
                : null;
    }

    @Override
    public AABB getRenderBoundingBox() {
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof GunsmithAssemblyBenchBlock)) {
            return super.getRenderBoundingBox();
        }
        DirectionBounds bounds = DirectionBounds.from(worldPosition, state.getValue(GunsmithAssemblyBenchBlock.FACING));
        return new AABB(bounds.minX, worldPosition.getY(), bounds.minZ,
                bounds.maxX + 1.0D, worldPosition.getY() + 2.25D, bounds.maxZ + 1.0D);
    }

    private record DirectionBounds(int minX, int maxX, int minZ, int maxZ) {
        private static DirectionBounds from(BlockPos mainPos, net.minecraft.core.Direction facing) {
            int minX = mainPos.getX();
            int maxX = mainPos.getX();
            int minZ = mainPos.getZ();
            int maxZ = mainPos.getZ();
            for (GunsmithAssemblyBenchBlock.Part part : GunsmithAssemblyBenchBlock.Part.values()) {
                BlockPos partPos = GunsmithAssemblyBenchBlock.partPos(mainPos, facing, part);
                minX = Math.min(minX, partPos.getX());
                maxX = Math.max(maxX, partPos.getX());
                minZ = Math.min(minZ, partPos.getZ());
                maxZ = Math.max(maxZ, partPos.getZ());
            }
            return new DirectionBounds(minX, maxX, minZ, maxZ);
        }
    }
}
