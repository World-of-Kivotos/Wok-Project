package com.miningdim.job.munitions.block;

import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyServices;
import com.miningdim.job.munitions.ModMunitionsBlockEntities;
import com.miningdim.job.munitions.ModMunitionsItems;
import com.miningdim.job.munitions.ModMunitionsSounds;
import com.miningdim.job.munitions.MunitionsConfig;
import com.miningdim.job.munitions.MunitionsLevels;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprint;
import com.miningdim.job.munitions.gunsmith.GunsmithPartItem;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPartRarity;
import com.miningdim.job.munitions.gunsmith.GunsmithPartVariant;
import com.miningdim.job.munitions.gunsmith.GunsmithPlatform;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.job.munitions.menu.GunsmithPressMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class GunsmithPressBlockEntity extends BlockEntity implements MenuProvider {

    public static final int SLOT_GUN_PARTS = 0;
    public static final int SLOT_ALLOY = 1;
    public static final int SLOT_POLYMER = 2;
    public static final int SLOT_OUTPUT = 3;
    public static final int SLOT_COUNT = 4;

    public static final int DATA_SELECTED_PLATFORM = 0;
    public static final int DATA_SELECTED_PART = 1;
    public static final int DATA_SELECTED_QUALITY = 2;
    public static final int DATA_PROGRESS_TICKS = 3;
    public static final int DATA_REQUIRED_TICKS = 4;
    public static final int DATA_ACTIVE = 5;
    public static final int DATA_SELECTED_VARIANT = 6;
    public static final int DATA_COUNT = 7;
    private static final int HYDRAULIC_SOUND_INTERVAL = 34;

    /**
     * 势力/特殊组件的等级门文案 (审查 29)。lang 归属方尚未收录该键, 故经 translatableWithFallback 兜底,
     * 保证键落地前玩家看到的是可读句子而不是裸键; 界面侧的锁定提示复用同一对常量, 防两处文案漂移。
     */
    public static final String RARITY_LOCKED_KEY = "message.miningdim.gunsmith_press.rarity_locked";
    public static final String RARITY_LOCKED_FALLBACK = "该组件型号需要军火商 %s 级。";

    /** 平台没有任何图纸时的拒绝文案 (参数: 平台名)。 */
    public static final String PLATFORM_NO_BLUEPRINT_KEY = "message.miningdim.gunsmith_press.platform_no_blueprint";

    /**
     * 至少被一张图纸引用的平台, 以 {@link GunsmithBlueprint} 实际登记为准 (裁决 24)。BULLPUP/MACHINE_GUN 这类
     * 没有图纸的平台, 冲出来的组件装不上任何枪, 工费照收就是给玩家挖坑; 以后给它们补了图纸会自动放行。
     */
    private static final Set<GunsmithPlatform> BLUEPRINT_PLATFORMS = blueprintPlatforms();

    private GunsmithPlatform selectedPlatform = GunsmithPlatform.AR;
    private GunsmithPressPart selectedPart = GunsmithPressPart.CORE;
    private GunsmithPartQuality selectedQuality = GunsmithPartQuality.COMMON;
    private GunsmithPartVariant selectedVariant = GunsmithPartVariant.BASE;
    private long activeStartTick;
    private long activeUntilTick;
    private long nextHydraulicSoundTick;

    /**
     * 本轮开工时实扣的三料 (V09), 下标即料槽号 (料槽恰是输出槽之前的 0..2, 故长度取 SLOT_OUTPUT)。
     * 冲压途中被拆时按原样退还, 不给成品 (否则拆机即可跳过工期), 工费不退; 收工即清空; 随存档持久化,
     * 重启后被拆照样能退。
     */
    private final NonNullList<ItemStack> pressedMaterials = NonNullList.withSize(SLOT_OUTPUT, ItemStack.EMPTY);

    private final ItemStackHandler inventory = new ItemStackHandler(SLOT_COUNT) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot != SLOT_OUTPUT && stack.is(requiredMaterial(slot));
        }
    };

    // 三料槽各只认对应材料 (审查 PRESS-MAT-01): 否则任意廉价物 (圆石) 冒充零件/合金/板材冲出真枪械零件, 架空物料 sink。
    // WIP 临时复用原版物品, 后续换专用材料时改此一处 + 同步 GunsmithPressGameTests。
    static Item requiredMaterial(int slot) {
        return switch (slot) {
            case SLOT_GUN_PARTS -> Items.IRON_INGOT;
            case SLOT_ALLOY -> Items.COPPER_INGOT;
            case SLOT_POLYMER -> Items.SLIME_BLOCK;
            default -> throw new IllegalArgumentException("slot is not a gunsmith press input slot: " + slot);
        };
    }

    private final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case DATA_SELECTED_PLATFORM -> selectedPlatform.index();
                case DATA_SELECTED_PART -> selectedPart.index();
                case DATA_SELECTED_QUALITY -> selectedQuality.index();
                case DATA_SELECTED_VARIANT -> selectedVariant.index();
                case DATA_PROGRESS_TICKS -> productionProgressTicks();
                case DATA_REQUIRED_TICKS -> productionRequiredTicks();
                case DATA_ACTIVE -> isPressing() ? 1 : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            // 服务端权威: 客户端只通过按钮请求修改。
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    };

    public GunsmithPressBlockEntity(BlockPos pos, BlockState state) {
        super(ModMunitionsBlockEntities.GUNSMITH_PRESS.get(), pos, state);
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public ContainerData dataAccess() {
        return dataAccess;
    }

    public GunsmithPlatform selectedPlatform() {
        return selectedPlatform;
    }

    public GunsmithPressPart selectedPart() {
        return selectedPart;
    }

    public GunsmithPartQuality selectedQuality() {
        return selectedQuality;
    }

    public GunsmithPartVariant selectedVariant() {
        return selectedVariant;
    }

    public boolean trySelectPlatform(int index) {
        if (isPressing()) {
            return false;
        }
        this.selectedPlatform = GunsmithPlatform.byIndex(index);
        normalizeSelectedPart();
        normalizeSelectedVariant();
        setChanged();
        return true;
    }

    /**
     * 玩家入口 (菜单按钮) 的选平台: 先过图纸门再落到 {@link #trySelectPlatform(int)} (裁决 24)。
     * 裸的 trySelectPlatform / tryStartPreview 仍是不带图纸门的机制层 (GunsmithPressGameTests 借它们覆盖无图纸
     * 平台的部件表与料量), 生产代码只经菜单走这两个带门入口。
     */
    public boolean trySelectPlatform(int index, ServerPlayer player) {
        GunsmithPlatform platform = GunsmithPlatform.byIndex(index);
        if (!platformHasBlueprint(platform)) {
            rejectPlatformWithoutBlueprint(player, platform);
            return false;
        }
        return trySelectPlatform(index);
    }

    /** 玩家入口的开工: 选中态可能来自旧存档里的无图纸平台, 开工帧再拦一次, 拒绝帧零扣费零扣料 (裁决 24)。 */
    public boolean tryStartPress(ServerPlayer player) {
        if (!isPressing() && !platformHasBlueprint(selectedPlatform)) {
            rejectPlatformWithoutBlueprint(player, selectedPlatform);
            return false;
        }
        return tryStartPreview(player);
    }

    public static boolean platformHasBlueprint(GunsmithPlatform platform) {
        return BLUEPRINT_PLATFORMS.contains(platform);
    }

    private static Set<GunsmithPlatform> blueprintPlatforms() {
        EnumSet<GunsmithPlatform> platforms = EnumSet.noneOf(GunsmithPlatform.class);
        for (GunsmithBlueprint blueprint : GunsmithBlueprint.values()) {
            platforms.add(blueprint.platform());
        }
        return Collections.unmodifiableSet(platforms);
    }

    private static void rejectPlatformWithoutBlueprint(ServerPlayer player, GunsmithPlatform platform) {
        player.displayClientMessage(Component.translatable(PLATFORM_NO_BLUEPRINT_KEY,
                Component.translatable(platform.labelKey())), true);
    }

    public boolean trySelectPart(int compactIndex) {
        if (isPressing()) {
            return false;
        }
        int row = 0;
        for (GunsmithPressPart part : selectedPlatform.supportedParts()) {
            if (row++ == compactIndex) {
                if (!selectedPlatform.supports(part)) {
                    return false;
                }
                this.selectedPart = part;
                normalizeSelectedVariant();
                setChanged();
                return true;
            }
        }
        return false;
    }

    public boolean trySelectQuality(int index, ServerPlayer player) {
        if (isPressing()) {
            return false;
        }
        GunsmithPartQuality quality = GunsmithPartQuality.byIndex(index);
        int level = MunitionsLevels.munitionsLevel(player);
        if (!MunitionsLevels.isPartQualityUnlocked(level, quality)) {
            player.displayClientMessage(Component.translatable("message.miningdim.gunsmith_press.quality_locked",
                    MunitionsLevels.partQualityUnlockLevel(quality)), true);
            return false;
        }
        this.selectedQuality = quality;
        setChanged();
        return true;
    }

    public boolean trySelectVariant(int index) {
        if (isPressing()) {
            return false;
        }
        GunsmithPartVariant variant = GunsmithPartVariant.byIndex(index);
        if (!variant.supports(selectedPlatform, selectedPart)) {
            return false;
        }
        selectedVariant = variant;
        setChanged();
        return true;
    }

    public boolean tryStartPreview(ServerPlayer player) {
        if (isPressing()) {
            player.displayClientMessage(Component.translatable("message.miningdim.gunsmith_press.busy"), true);
            return false;
        }
        if (!selectedPlatform.supports(selectedPart)) {
            player.displayClientMessage(
                    Component.translatable("message.miningdim.gunsmith_press.unsupported_part"), true);
            return false;
        }
        if (!inventory.getStackInSlot(SLOT_OUTPUT).isEmpty()) {
            player.displayClientMessage(Component.translatable("message.miningdim.gunsmith_press.output_blocked"), true);
            return false;
        }
        if (!hasRequiredMaterials()) {
            player.displayClientMessage(Component.translatable("message.miningdim.gunsmith_press.missing_materials"), true);
            return false;
        }
        int level = MunitionsLevels.munitionsLevel(player);
        if (!MunitionsLevels.isPartQualityUnlocked(level, selectedQuality)) {
            player.displayClientMessage(Component.translatable("message.miningdim.gunsmith_press.quality_locked",
                    MunitionsLevels.partQualityUnlockLevel(selectedQuality)), true);
            return false;
        }
        // 势力/特殊组件不能与基础组件同门同价 (审查 29): 等级门按型号稀有度收, 工费按稀有度加价。
        // 选中态不设门 (trySelectVariant 拿不到玩家), 服务端在此做唯一权威判定, 界面只做提前提示。
        GunsmithPartRarity rarity = selectedVariant.rarity();
        int rarityUnlockLevel = MunitionsConfig.rarityUnlockLevel(rarity);
        if (level < rarityUnlockLevel) {
            player.displayClientMessage(
                    Component.translatableWithFallback(RARITY_LOCKED_KEY, RARITY_LOCKED_FALLBACK, rarityUnlockLevel),
                    true);
            return false;
        }
        long fee = MunitionsConfig.pressWorkFeeCredits(selectedQuality, rarity);
        if (!tryChargeWorkFee(player, fee)) {
            player.displayClientMessage(
                    Component.translatable("message.miningdim.gunsmith.work_fee_unaffordable", fee), true);
            return false;
        }
        consumeRequiredMaterials();
        startPressRun();
        player.displayClientMessage(Component.translatable("message.miningdim.gunsmith_press.started"), true);
        return true;
    }

    public void serverTick() {
        if (level == null || level.isClientSide) {
            return;
        }
        long now = level.getGameTime();
        if (activeUntilTick <= 0L) {
            if (getBlockState().hasProperty(GunsmithPressBlock.ACTIVE)
                    && getBlockState().getValue(GunsmithPressBlock.ACTIVE)) {
                setActiveState(false);
            }
            return;
        }
        if (now >= activeUntilTick) {
            finishPressRun();
            return;
        }
        if (now >= nextHydraulicSoundTick) {
            playHydraulicSound();
            nextHydraulicSoundTick = now + HYDRAULIC_SOUND_INTERVAL;
        }
    }

    private void startPressRun() {
        if (level == null || level.isClientSide) {
            return;
        }
        long now = level.getGameTime();
        activeStartTick = now;
        activeUntilTick = now + productionRequiredTicks();
        nextHydraulicSoundTick = now + HYDRAULIC_SOUND_INTERVAL;
        setActiveState(true);
        playHydraulicSound();
        setChanged();
    }

    private void finishPressRun() {
        if (level == null || level.isClientSide) {
            return;
        }
        if (inventory.getStackInSlot(SLOT_OUTPUT).isEmpty()) {
            inventory.setStackInSlot(SLOT_OUTPUT, GunsmithPartItem.createRolledStack(
                    ModMunitionsItems.GUNSMITH_PART.get(), selectedPlatform, selectedPart, selectedQuality,
                    selectedVariant, level.random));
        }
        clearPressedMaterials();
        activeStartTick = 0L;
        activeUntilTick = 0L;
        nextHydraulicSoundTick = 0L;
        setActiveState(false);
        setChanged();
    }

    public int productionRequiredTicks() {
        return selectedQuality.requiredTicks();
    }

    public int productionProgressTicks() {
        if (level == null || !isPressing() || activeStartTick <= 0L) {
            return 0;
        }
        long elapsed = Math.max(0L, level.getGameTime() - activeStartTick);
        return (int) Math.min(productionRequiredTicks(), elapsed);
    }

    public boolean isPressing() {
        if (level == null) {
            return activeUntilTick > 0L;
        }
        return activeUntilTick > level.getGameTime();
    }

    private boolean hasRequiredMaterials() {
        return hasMaterial(SLOT_GUN_PARTS, requiredGunParts())
                && hasMaterial(SLOT_ALLOY, requiredAlloy())
                && hasMaterial(SLOT_POLYMER, requiredPolymer());
    }

    private boolean hasMaterial(int slot, int amount) {
        if (amount <= 0) {
            return true;
        }
        ItemStack stack = inventory.getStackInSlot(slot);
        return stack.is(requiredMaterial(slot)) && stack.getCount() >= amount;
    }

    private void consumeRequiredMaterials() {
        consume(SLOT_GUN_PARTS, requiredGunParts());
        consume(SLOT_ALLOY, requiredAlloy());
        consume(SLOT_POLYMER, requiredPolymer());
    }

    // 料量只按部件与品质算, 不再按型号稀有度加价 (审查 29 的定价缺口改由信用点工费与等级门承担):
    // 料槽是单槽 ItemStackHandler, 上限 64, 而 hasMaterial 要求单槽一次凑够; 传奇品质的 x10 倍率已把
    // 最贵的单料推到 60 贴着上限 (枪管合金原先是 70, 直接顶穿, 传奇枪管永远冲不出来, V10 已压回 6x10),
    // 再乘稀有度倍率会让高稀有度组件直接冲不出来, 变成隐性封禁而不是定价。
    private int requiredGunParts() {
        return requiredAmount(selectedPart, selectedQuality, SLOT_GUN_PARTS);
    }

    private int requiredAlloy() {
        return requiredAmount(selectedPart, selectedQuality, SLOT_ALLOY);
    }

    private int requiredPolymer() {
        return requiredAmount(selectedPart, selectedQuality, SLOT_POLYMER);
    }

    /** 某部件某品质在某料槽的单次需求; GameTest 据此遍历全部组合核对不超过单槽上限 (V10)。 */
    static int requiredAmount(GunsmithPressPart part, GunsmithPartQuality quality, int slot) {
        int unitCost = switch (slot) {
            case SLOT_GUN_PARTS -> part.partsCost();
            case SLOT_ALLOY -> part.alloyCost();
            case SLOT_POLYMER -> part.polymerCost();
            default -> throw new IllegalArgumentException("slot is not a gunsmith press input slot: " + slot);
        };
        return unitCost * quality.materialMultiplier();
    }

    /** 工费 sink: 经 {@link EconomyServices} 定位器先查后扣; 经济未注入或 cost<=0 放行 (不阻塞核心循环)。 */
    private boolean tryChargeWorkFee(ServerPlayer player, long cost) {
        if (cost <= 0L || !EconomyServices.isRegistered()) {
            return true;
        }
        return EconomyServices.economyService().tryCharge(player, Currency.CREDIT, cost);
    }

    private void consume(int slot, int amount) {
        if (amount <= 0) {
            pressedMaterials.set(slot, ItemStack.EMPTY);
            return;
        }
        ItemStack stack = inventory.getStackInSlot(slot);
        pressedMaterials.set(slot, stack.copyWithCount(amount));
        stack.shrink(amount);
        inventory.setStackInSlot(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
    }

    private void clearPressedMaterials() {
        Collections.fill(pressedMaterials, ItemStack.EMPTY);
    }

    /**
     * 方块被拆时的掉落清单 (V09, 对照装配台 onRemove): 4 个槽原样掉出; 本轮还没收工就退还开工时实扣的材料,
     * 不给成品, 工费不退。取出即清空, 同一 tick 里仍开着的菜单掏不出第二份。
     */
    public List<ItemStack> dropContents() {
        List<ItemStack> drops = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                drops.add(stack.copy());
                inventory.setStackInSlot(slot, ItemStack.EMPTY);
            }
        }
        for (ItemStack refund : pressedMaterials) {
            if (!refund.isEmpty()) {
                drops.add(refund.copy());
            }
        }
        clearPressedMaterials();
        activeStartTick = 0L;
        activeUntilTick = 0L;
        nextHydraulicSoundTick = 0L;
        setChanged();
        return drops;
    }

    private void setActiveState(boolean active) {
        if (level == null) {
            return;
        }
        BlockState state = getBlockState();
        if (state.hasProperty(GunsmithPressBlock.ACTIVE)
                && state.getValue(GunsmithPressBlock.ACTIVE) != active) {
            level.setBlock(worldPosition, state.setValue(GunsmithPressBlock.ACTIVE, active), Block.UPDATE_ALL);
        }
    }

    private void playHydraulicSound() {
        if (level == null) {
            return;
        }
        float pitch = 0.92F + level.random.nextFloat() * 0.12F;
        level.playSound(null, worldPosition, ModMunitionsSounds.GUNSMITH_PRESS_HYDRAULIC.get(),
                SoundSource.BLOCKS, 0.56F, pitch);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.miningdim.gunsmith_press");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory inv, Player player) {
        return new GunsmithPressMenu(windowId, inv, worldPosition);
    }

    private static final String K_INV = "Inv";
    private static final String K_PLATFORM = "SelectedPlatform";
    private static final String K_PART = "SelectedPart";
    private static final String K_QUALITY = "SelectedQuality";
    private static final String K_VARIANT = "SelectedVariant";
    private static final String K_ACTIVE_START = "ActiveStartTick";
    private static final String K_ACTIVE_UNTIL = "ActiveUntilTick";
    private static final String K_PRESSED_MATERIALS = "PressedMaterials";

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put(K_INV, inventory.serializeNBT());
        tag.put(K_PRESSED_MATERIALS, ContainerHelper.saveAllItems(new CompoundTag(), pressedMaterials));
        tag.putString(K_PLATFORM, selectedPlatform.id());
        tag.putString(K_PART, selectedPart.id());
        tag.putString(K_QUALITY, selectedQuality.id());
        tag.putString(K_VARIANT, selectedVariant.id());
        tag.putLong(K_ACTIVE_START, activeStartTick);
        tag.putLong(K_ACTIVE_UNTIL, activeUntilTick);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains(K_INV)) {
            inventory.deserializeNBT(tag.getCompound(K_INV));
        }
        // 旧存档没有这一段: 升级前已开工的那一轮无从得知实扣多少, 只能按"无可退"处理。
        clearPressedMaterials();
        if (tag.contains(K_PRESSED_MATERIALS)) {
            ContainerHelper.loadAllItems(tag.getCompound(K_PRESSED_MATERIALS), pressedMaterials);
        }
        selectedPlatform = tag.contains(K_PLATFORM)
                ? GunsmithPlatform.byId(tag.getString(K_PLATFORM)) : GunsmithPlatform.AR;
        selectedPart = tag.contains(K_PART)
                ? GunsmithPressPart.byId(tag.getString(K_PART)) : GunsmithPressPart.CORE;
        selectedQuality = tag.contains(K_QUALITY)
                ? GunsmithPartQuality.byId(tag.getString(K_QUALITY)) : GunsmithPartQuality.COMMON;
        selectedVariant = tag.contains(K_VARIANT)
                ? GunsmithPartVariant.byId(tag.getString(K_VARIANT)) : GunsmithPartVariant.BASE;
        normalizeSelectedPart();
        normalizeSelectedVariant();
        activeStartTick = tag.getLong(K_ACTIVE_START);
        activeUntilTick = tag.getLong(K_ACTIVE_UNTIL);
    }

    private void normalizeSelectedPart() {
        if (selectedPlatform.supports(selectedPart)) {
            return;
        }
        for (GunsmithPressPart part : selectedPlatform.supportedParts()) {
            selectedPart = part;
            return;
        }
        throw new IllegalStateException("Gunsmith platform has no supported parts: " + selectedPlatform.id());
    }

    private void normalizeSelectedVariant() {
        if (!selectedVariant.supports(selectedPlatform, selectedPart)) {
            selectedVariant = GunsmithPartVariant.BASE;
        }
    }
}
