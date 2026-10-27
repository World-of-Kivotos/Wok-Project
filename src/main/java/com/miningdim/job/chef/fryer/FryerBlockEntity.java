package com.miningdim.job.chef.fryer;

import com.miningdim.economy.EconomyOperationDomain;
import com.miningdim.economy.EconomyOperationStatus;
import com.miningdim.economy.EconomyServices;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.chef.*;
import com.miningdim.job.chef.station.FryerStationBlock;
import com.miningdim.job.chef.station.CookingStationBlock;
import com.miningdim.job.chef.zhangshao.ZhangshaoConfig;
import com.miningdim.job.chef.zhangshao.ZhangshaoKernel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** 材料暂存、账本交易及实时输入回放均由此服务端事务持有。 */
public final class FryerBlockEntity extends BlockEntity implements MenuProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/fryer");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ItemStackHandler inventory = new ItemStackHandler(9) {
        @Override public ItemStack getStackInSlot(int slot) { ensureHydrated(); return super.getStackInSlot(slot); }
        @Override public void setStackInSlot(int slot, ItemStack stack) {
            ensureHydrated();
            if (!internal && journalFailed) throw new IllegalStateException("Fryer journal failed; inventory remains locked at " + worldPosition);
            ItemStack before = super.getStackInSlot(slot).copy();
            boolean committed = false;
            try { super.setStackInSlot(slot, stack); committed = true; }
            finally { if (!committed && !internal) restoreUncommittedSlot(slot, before); }
        }
        @Override public boolean isItemValid(int slot, ItemStack stack) {
            return slot != 8 && (slot != 7 || SeasoningTag.isSeasoning(stack));
        }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            ensureHydrated();
            if (!internal && busy()) return stack;
            ItemStack before = super.getStackInSlot(slot).copy();
            boolean committed = false;
            try { ItemStack remainder = super.insertItem(slot, stack, simulate); committed = true; return remainder; }
            finally { if (!committed && !internal && !simulate) restoreUncommittedSlot(slot, before); }
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ensureHydrated();
            if (!internal && (journalFailed || (busy() && slot != 8))) return ItemStack.EMPTY;
            ItemStack before = super.getStackInSlot(slot).copy();
            boolean committed = false;
            try { ItemStack taken = super.extractItem(slot, amount, simulate); committed = true; return taken; }
            finally { if (!committed && !internal && !simulate) restoreUncommittedSlot(slot, before); }
        }
        private void restoreUncommittedSlot(int slot, ItemStack before) {
            // 尚未向调用方交付物品；撤销失败的源/目标变更，避免后续自动保存把未交付数量写入 journal。
            internal = true;
            try { super.setStackInSlot(slot, before); }
            finally { internal = false; }
            if (level instanceof ServerLevel serverLevel) {
                CompoundTag snapshot = new CompoundTag(); saveAdditional(snapshot);
                playerData().checkpoint(serverLevel, worldPosition, snapshot);
            }
        }
        @Override protected void onContentsChanged(int slot) { inventoryChanged(); }
    };
    private final IItemHandler automation = new IItemHandler() {
        @Override public int getSlots() { return 9; }
        @Override public ItemStack getStackInSlot(int slot) { return inventory.getStackInSlot(slot); }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return slot < 7 ? inventory.insertItem(slot, stack, simulate) : stack;
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return slot == 8 ? inventory.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
        }
        @Override public int getSlotLimit(int slot) { return inventory.getSlotLimit(slot); }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return slot < 7 && inventory.isItemValid(slot, stack); }
    };
    private LazyOptional<IItemHandler> capability = LazyOptional.of(() -> automation);
    private boolean internal, recoverLoaded, hydrated, loading, journalFailed;
    private int phase, remaining, finalQuality = -1, lastStar, lastMastery, lastProgress, lastBest, lastLevel = 1;
    private int lastAccuracy, lastPen, lastPerfect, lastBottle;
    private UUID owner, operation;
    private long roundId, startedNanos, lastInputNanos;
    private long minOffset = Long.MAX_VALUE;
    private int seq, received, latePackets;
    private boolean charged, gold, bottleSent, slow;
    private FryerRecipe recipe;
    private ItemStack dish = ItemStack.EMPTY;
    private final ItemStack[] held = new ItemStack[7];
    private final List<ItemStack> bufferedOutputs = new ArrayList<>();
    private final List<ItemStack> returnedItems = new ArrayList<>();
    private ZhangshaoKernel.State state;
    private final int[] replay = new int[400];

    private final ContainerData data = new ContainerData() {
        @Override public int get(int index) {
            return switch (index) {
                case 0 -> phase;
                case 1 -> state == null ? (phase == 1 && recipe != null ? (recipe.cookTime() - remaining) * 1000 / recipe.cookTime() : 0) : state.P / 1000;
                // 菜谱 cookTime 可大于菜单 short 通道，长菜只截展示，不改服务器用时。
                case 2 -> phase == 1 ? Math.min(32767, remaining) : state == null ? 0 : state.p.CAP - state.pt;
                case 3 -> finalQuality;
                case 4 -> recipe == null ? lastStar : recipe.star();
                case 5 -> lastMastery;
                case 6 -> lastBest;
                case 7 -> state == null ? 450 : state.T;
                case 8 -> lastAccuracy;
                case 9 -> lastPen;
                case 10 -> lastPerfect;
                case 11 -> lastBottle;
                case 12 -> Math.min(100, lastProgress);
                case 13 -> level != null && FryerStationBlock.fryerBlockedReason(level, worldPosition) == null ? 1 : 0;
                case 14 -> lastLevel;
                default -> throw new IndexOutOfBoundsException("Fryer data " + index);
            };
        }
        @Override public void set(int index, int value) { throw new UnsupportedOperationException("Server-owned fryer data"); }
        @Override public int getCount() { return 15; }
    };

    public FryerBlockEntity(BlockPos pos, BlockState state) {
        super(FryerRegistry.blockEntityType(), pos, state); Arrays.fill(held, ItemStack.EMPTY);
    }
    public ItemStackHandler inventory() { ensureHydrated(); return inventory; }
    public ContainerData data() { return data; }
    public boolean busy() { return isRemoved() || phase == 1 || phase == 2 || !bufferedOutputs.isEmpty() || recoverLoaded || journalFailed; }
    public boolean canTakeOutput() { return !isRemoved() && !journalFailed && phase != 1 && phase != 2; }
    public long roundId() { return roundId; }
    @Nullable UUID operationId() { return operation; }
    @Nullable ZhangshaoKernel.State sessionState() { return state == null ? null : state.copy(); }
    public boolean isOperator(ServerPlayer player) { return !isRemoved() && owner != null && owner.equals(player.getUUID()) && phase == 2; }
    public @Nullable FryerRecipe previewRecipe() { return level == null ? null : findRecipe(level, inventory); }
    public static @Nullable FryerRecipe findRecipe(Level level, ItemStackHandler inventory) {
        SimpleContainer container = snapshot(inventory);
        return level.getRecipeManager().getAllRecipesFor(FryerRecipeRegistry.recipeType()).stream()
                .filter(r -> r.matches(container, level)).sorted(java.util.Comparator.comparing(r -> r.getId().toString())).findFirst().orElse(null);
    }
    private static SimpleContainer snapshot(ItemStackHandler inventory) {
        SimpleContainer container = new SimpleContainer(9);
        for (int i = 0; i < 9; i++) container.setItem(i, inventory.getStackInSlot(i).copy());
        return container;
    }
    private FryerPlayerData playerData() { return FryerPlayerData.get((ServerLevel) level); }
    private ResourceLocation dishId() { return ForgeRegistries.ITEMS.getKey(dish.getItem()); }

    public static void clearCheckpoint(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) FryerPlayerData.get(serverLevel).clearCheckpoint(serverLevel, pos);
    }
    private void ensureHydrated() {
        if (hydrated || loading || !(level instanceof ServerLevel serverLevel)) return;
        hydrated = true;
        CompoundTag checkpoint = playerData().checkpoint(serverLevel, worldPosition);
        if (checkpoint != null) readSnapshot(checkpoint);
        if (recoverLoaded) recoverInterrupted();
        else checkpoint();
    }
    /** SlotItemHandler 的原地 grow/shrink 由菜单 setChanged 调用此入口。 */
    public void inventoryChanged() {
        setChanged();
        if (internal || loading || !(level instanceof ServerLevel)) return;
        if (journalFailed) throw new IllegalStateException("Fryer journal failed; inventory remains locked at " + worldPosition);
        ensureHydrated(); checkpoint();
    }
    private void checkpoint() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        boolean committed = false;
        try {
            CompoundTag snapshot = new CompoundTag(); saveAdditional(snapshot);
            FryerPlayerData data = playerData(); data.checkpoint(serverLevel, worldPosition, snapshot); data.sync(); committed = true;
        } finally {
            // 写盘异常自然上抛；本进程同时锁住未持久化库存，不能继续取菜或推进这局。
            if (!committed) journalFailed = true;
        }
    }

    private boolean controls(ServerPlayer player, double distance) {
        return !isRemoved() && level.getBlockEntity(worldPosition) == this
                && !player.isRemoved() && player.isAlive() && player.level() == level
                && player.distanceToSqr(worldPosition.getX() + .5, worldPosition.getY() + .5, worldPosition.getZ() + .5) <= distance * distance
                && player.containerMenu instanceof FryerMenu menu && menu.blockEntity() == this;
    }
    private void reject(ServerPlayer player, String text) { player.displayClientMessage(Component.literal(text), true); }

    public boolean start(ServerPlayer player, boolean manual) {
        if (level == null || level.isClientSide || !controls(player, 6)) return false;
        if (journalFailed) { reject(player, "炸锅保存失败，事务已锁定，请联系管理员"); return false; }
        ensureHydrated();
        if (busy()) { reject(player, "炸锅正忙，请先收取出品"); return false; }
        if (!returnedItems.isEmpty()) { reject(player, "请清理原料槽，为余容器腾出空间"); return false; }
        Component blocked = FryerStationBlock.fryerBlockedReason(level, worldPosition);
        if (blocked != null) { player.displayClientMessage(blocked, true); return false; }
        FryerRecipe chosen = previewRecipe();
        if (chosen == null) { reject(player, "原料或容器不足"); return false; }
        if (manual && JobServices.jobService().progress(player, JobId.CHEF) == null) {
            reject(player, "非厨师只能制作家常菜"); return false;
        }
        ItemStack output = inventory.getStackInSlot(8), planned = chosen.result();
        if ((!output.isEmpty() && manual) || (!output.isEmpty() && (!ItemStack.isSameItemSameTags(output, planned)
                || output.getCount() + planned.getCount() > output.getMaxStackSize()))) {
            reject(player, "出品槽空间不足"); return false;
        }
        long now = System.currentTimeMillis(); FryerPlayerData data = playerData();
        if (!data.available(player.getUUID(), now)) { reject(player, "手累了，歇一下，或先结束另一锅"); return false; }
        int[] allocation = chosen.allocation(snapshot(inventory));
        UUID transaction = UUID.randomUUID();
        long cost = manual && EconomyServices.isRegistered() ? ChefConfig.seasoningCreditCost() : 0L;
        if (cost > 0L && EconomyServices.economyService().creditBalance(player) < cost) {
            reject(player, "信用点不足"); return false;
        }
        recipe = chosen; dish = planned; owner = player.getUUID(); operation = transaction; charged = cost > 0L;
        finalQuality = -1; lastStar = chosen.star();
        lastProgress = data.progress(owner, dishId()); lastMastery = data.mastery(owner, dishId());
        lastBest = data.best(owner, dishId()); lastAccuracy = lastPen = lastPerfect = lastBottle = 0;
        if (manual) {
            lastLevel = ChefExperience.level(player);
            ZhangshaoConfig.Options options = new ZhangshaoConfig.Options();
            options.station = ZhangshaoKernel.FRYER; options.star = chosen.star(); options.motion = chosen.motion();
            options.level = lastLevel; options.mastery = lastMastery; options.goldBlocked = data.goldBlocked(owner, now);
            int[] params = new ZhangshaoConfig().buildParams(options);
            int seed = RANDOM.nextInt(); roundId = RANDOM.nextLong();
            state = ZhangshaoKernel.init(new ZhangshaoKernel.Params(params), seed);
            gold = params[15] > 0 && RANDOM.nextInt(params[15]) < params[16];
            // 金瓶概率字段只在服务端使用；会话包不泄露私下抽签结果。
            seq = received = latePackets = 0; minOffset = Long.MAX_VALUE; slow = bottleSent = false;
        }
        internal = true;
        for (int slot = 0; slot < 7; slot++) held[slot] = inventory.extractItem(slot, allocation[slot], false);
        data.begin(owner, this, operation, now); phase = manual ? 2 : 1; remaining = chosen.cookTime();
        internal = false;
        if (charged) data.enqueueRefund(operation, owner);
        // 锅内原料及退款意图先同步提交；账本扣费后即使 chunk 尚未保存仍可恢复。
        checkpoint();
        if (cost > 0L) {
            boolean paymentReturned = false;
            try {
                EconomyOperationStatus payment = EconomyServices.economyService().tryChargeBundle(EconomyOperationDomain.CHEF_FRYER,
                        player, transaction, cost, 0L);
                paymentReturned = true;
                if (payment != EconomyOperationStatus.CHARGED) {
                    UUID failedOwner = owner;
                    recoverInterrupted(); data.cancelStart(failedOwner, now);
                    reject(player, "信用点不足"); return false;
                }
            } finally {
                if (!paymentReturned) {
                    // 账本调用失败时扣款结果可能未知，只走幂等退款恢复，不允许无会话继续产菜。
                    phase = 0; state = null; recoverLoaded = true;
                    data.release(owner, this, System.currentTimeMillis()); checkpoint();
                }
            }
        }
        setLit(true);
        if (manual) {
            startedNanos = System.nanoTime(); lastInputNanos = startedNanos;
            FryerNetwork.session(player, worldPosition, roundId, state.seed, state.p.block(), dish.copy());
        }
        setChanged(); return true;
    }

    public void acceptInput(ServerPlayer player, long round, int sequence, int firstTick, byte[] inputs, int clientHash, boolean finalPacket) {
        if (journalFailed) throw new IllegalStateException("Fryer journal failed; transaction remains locked at " + worldPosition);
        if (!isOperator(player) || round != roundId) return;
        if (!controls(player, 8)) { abort(player, round, "离开炸锅"); return; }
        if (System.nanoTime() - lastInputNanos > 5000000000L) { abort(player, round, "输入中断"); return; }
        if (sequence != seq || firstTick != received || inputs.length < 1 || inputs.length > 40
                || received + inputs.length > replay.length) { invalid(player, "输入序号或长度不合法", 20); return; }
        for (byte input : inputs) if ((input & 0xfc) != 0) { invalid(player, "输入保留位不合法", 20); return; }
        long elapsed = (System.nanoTime() - startedNanos) / 1000000L;
        if ((received + inputs.length) * 50L > elapsed - 1000L + 600L) { invalid(player, "输入快于真实时间", 40); return; }
        long offset = elapsed - 1000L - (received + inputs.length) * 50L;
        minOffset = Math.min(minOffset, offset);
        if (offset - minOffset > 2000L) latePackets++; else latePackets = 0;
        if (!slow && (latePackets >= 3 || (finalPacket && offset - minOffset > 2000L))) {
            slow = true; playerData().addSuspicion(owner, 20, System.currentTimeMillis());
            LOGGER.warn("Fryer slow input player={} round={} delay={}ms", owner, roundId, offset - minOffset);
        }
        seq++; lastInputNanos = System.nanoTime();
        for (byte input : inputs) {
            if (state.end != ZhangshaoKernel.RUNNING) break;
            replay[received++] = input; ZhangshaoKernel.step(state, input);
        }
        if (ZhangshaoKernel.hash(state) != clientHash) LOGGER.warn("Fryer desync player={} round={} seed={} params={} inputs={} serverHash={} clientHash={}",
                owner, roundId, state.seed, Arrays.toString(state.p.block()), Arrays.toString(Arrays.copyOf(replay, received)), ZhangshaoKernel.hash(state), clientHash);
        if (finalPacket && state.end == ZhangshaoKernel.RUNNING) {
            LOGGER.warn("Fryer premature client end player={} round={} tick={}", owner, roundId, received);
            while (state.end == ZhangshaoKernel.RUNNING) { replay[received++] = 0; ZhangshaoKernel.step(state, 0); }
        }
        if (state.end != ZhangshaoKernel.RUNNING) {
            if (!slow && elapsed > 1000L + state.tick * 55L + 2000L) {
                slow = true; playerData().addSuspicion(owner, 20, System.currentTimeMillis());
                LOGGER.warn("Fryer terminal slowdown player={} round={} ticks={} elapsed={}ms", owner, roundId, state.tick, elapsed);
            }
            settleManual(player, "出锅");
        }
        setChanged();
    }
    private void invalid(ServerPlayer player, String reason, int suspicion) {
        playerData().addSuspicion(owner, suspicion, System.currentTimeMillis());
        LOGGER.warn("Fryer invalid input player={} round={} reason={}", owner, roundId, reason);
        abort(player, roundId, reason);
    }
    public void abort(ServerPlayer player, long round, String reason) {
        if (journalFailed) { reject(player, "炸锅保存失败，事务已锁定，请联系管理员"); return; }
        if (isOperator(player) && round == roundId) {
            ZhangshaoKernel.abandon(state); settleManual(player, reason + "，这锅只能算家常菜");
        }
    }

    public void serverTick() {
        if (isRemoved() || journalFailed) return;
        ensureHydrated();
        FryerPlayerData data = playerData(); data.retryRefunds();
        if (recoverLoaded) { recoverInterrupted(); return; }
        flushOutputs();
        flushReturns();
        if (phase == 1) {
            if (FryerStationBlock.fryerBlockedReason(level, worldPosition) == null && --remaining <= 0) settleHome(null, "家常菜出锅");
        } else if (phase == 2) {
            ServerPlayer player = ((ServerLevel) level).getServer().getPlayerList().getPlayer(owner);
            if (player == null || !controls(player, 8)) {
                ZhangshaoKernel.abandon(state); settleManual(player, "撂勺，这锅只能算家常菜"); return;
            }
            long now = System.nanoTime();
            if (now - lastInputNanos > 5000000000L) { abort(player, roundId, "输入中断"); return; }
            if (!bottleSent && state.bKind != ZhangshaoKernel.KIND_NONE
                    && (now - startedNanos) / 1000000L >= 700L + state.bAt * 50L) {
                FryerNetwork.bottle(player, roundId, gold); bottleSent = true;
            }
        }
    }

    private void settleManual(@Nullable ServerPlayer player, String message) {
        int mechanical = ZhangshaoKernel.detectMechanical(replay, received, state.p);
        if (mechanical != 0) playerData().addSuspicion(owner, 20, System.currentTimeMillis());
        ZhangshaoKernel.Result result = ZhangshaoKernel.result(state, new ZhangshaoKernel.Ext(gold ? 1 : 0, slow ? 1 : 0,
                mechanical, playerData().suspect(owner, System.currentTimeMillis())));
        if (result.quality < 0) { settleHome(result, message); return; }
        ChefQuality quality = ChefQuality.byTier(result.quality);
        List<ChefEffectInstance> effects = new ArrayList<>();
        for (int negative : result.negatives) effects.add(negative(negative, quality));
        SeasoningBias seasoningBias = null;
        if (result.bottle == 1 && !inventory.getStackInSlot(7).isEmpty()) {
            seasoningBias = SeasoningTag.biasOf(inventory.getStackInSlot(7));
            effects.add(seasoning(seasoningBias, quality));
        }
        ItemStack product = dish.copy();
        List<ItemStack> products = new ArrayList<>(2);
        if (quality == ChefQuality.RADIANT && product.getCount() > 1) {
            ItemStack rest = product.copy(); rest.shrink(1); product.setCount(1);
            List<ChefEffectInstance> restEffects = seasoningBias == null ? List.of() : List.of(seasoning(seasoningBias, ChefQuality.EXTRAORDINARY));
            stamp(rest, ChefQuality.EXTRAORDINARY, restEffects); products.add(rest);
        }
        stamp(product, quality, effects); products.add(0, product);
        UUID completedOwner = owner, completedOperation = operation;
        boolean paid = charged;
        internal = true;
        if (seasoningBias != null) {
            ItemStack consumed = inventory.extractItem(7, 1, false);
            if (consumed.hasCraftingRemainingItem()) returnItem(consumed.getCraftingRemainingItem(), 7);
        }
        bufferedOutputs.addAll(products); consumeHeld(); flushOutputs();
        long now = System.currentTimeMillis();
        playerData().record(owner, dishId(), result.quality, result.score, now);
        lastProgress = playerData().progress(owner, dishId()); lastMastery = playerData().mastery(owner, dishId());
        lastBest = playerData().best(owner, dishId());
        long xp = (long) ChefConfig.rawXp(quality) * (1000 + 150 * (lastStar - 1)) / 1000;
        finish(result.quality, result);
        persistCompleted(completedOwner, completedOperation, paid);
        if (player != null) {
            ChefExperience.awardSeasoningComplete(player, xp);
            FryerNetwork.result(player, roundId, result, message);
            ChefEvents.fireDish(player, quality, quality);
        }
    }
    private static ChefEffectInstance seasoning(SeasoningBias bias, ChefQuality quality) {
        ChefEffectType type = switch (bias) {
            case SWEET, SOUR -> ChefEffectType.AFTERTASTE_SAT;
            case OILY, SPICY, AROMATIC -> ChefEffectType.AMPLIFY;
            case NONE, SAVORY, COMPLEX -> ChefEffectType.NOURISH_FOOD;
        };
        int magnitude = switch (type) {
            case AMPLIFY -> ChefConfig.amplifyMul(quality);
            case AFTERTASTE_SAT -> ChefConfig.aftertasteSatMul(quality);
            default -> ChefConfig.nourishFoodMul(quality);
        };
        return new ChefEffectInstance(type, magnitude);
    }
    private static ChefEffectInstance negative(int code, ChefQuality quality) {
        return switch (code) {
            case ZhangshaoKernel.NEG_UNDERDONE -> new ChefEffectInstance(ChefEffectType.UNDERDONE, switch (quality) {
                case LOW -> ChefConfig.UNDERDONE_CHANCE_LOW.get(); case MEDIUM -> ChefConfig.UNDERDONE_CHANCE_MEDIUM.get();
                case HIGH -> ChefConfig.UNDERDONE_CHANCE_HIGH.get(); default -> throw new IllegalStateException("Underdone on exceptional dish");
            });
            case ZhangshaoKernel.NEG_SCORCHED -> new ChefEffectInstance(ChefEffectType.SCORCHED, switch (quality) {
                case LOW -> ChefConfig.SCORCHED_PCT_LOW.get(); case MEDIUM -> ChefConfig.SCORCHED_PCT_MEDIUM.get();
                case HIGH -> ChefConfig.SCORCHED_PCT_HIGH.get(); default -> throw new IllegalStateException("Scorched exceptional dish");
            });
            case ZhangshaoKernel.NEG_NAUSEA -> new ChefEffectInstance(ChefEffectType.NAUSEA, quality == ChefQuality.LOW ? 2 : 1);
            default -> throw new IllegalArgumentException("Unknown fryer negative " + code);
        };
    }
    private void stamp(ItemStack product, ChefQuality quality, List<ChefEffectInstance> effects) {
        ChefQualityNbt.stamp(product, quality, effects); ChefQualityNbt.setOperator(product, owner);
        product.getOrCreateTag().getCompound("MiningChef").putLong("roundId", roundId);
    }
    private void settleHome(@Nullable ZhangshaoKernel.Result result, String message) {
        UUID completedOwner = owner, completedOperation = operation;
        boolean paid = charged;
        internal = true;
        bufferedOutputs.add(dish.copy()); consumeHeld(); flushOutputs();
        ServerPlayer player = ((ServerLevel) level).getServer().getPlayerList().getPlayer(owner);
        finish(-1, result);
        persistCompleted(completedOwner, completedOperation, paid);
        if (player != null) FryerNetwork.result(player, roundId, result, message);
    }
    private void persistCompleted(UUID player, UUID transaction, boolean paid) {
        internal = false;
        if (paid) playerData().enqueueCompletion(transaction, player);
        // 完成态、产物、玩家进度与 completion 意图在同一 journal 文件提交后，才终结账本交易。
        checkpoint(); playerData().retryRefunds();
    }
    private void consumeHeld() {
        for (int slot = 0; slot < 7; slot++) {
            ItemStack stack = held[slot];
            if (!stack.isEmpty() && stack.hasCraftingRemainingItem()) {
                for (int i = 0; i < stack.getCount(); i++) returnItem(stack.getCraftingRemainingItem(), slot);
            }
            held[slot] = ItemStack.EMPTY;
        }
    }
    private void finish(int quality, @Nullable ZhangshaoKernel.Result result) {
        playerData().release(owner, this, System.currentTimeMillis()); phase = 3; finalQuality = quality;
        if (result != null) { lastAccuracy = result.accuracy; lastPen = result.pen; lastPerfect = result.perfect; lastBottle = result.bottle; }
        state = null; charged = false; owner = null; operation = null; recipe = null; dish = ItemStack.EMPTY; setLit(false); setChanged();
    }
    private void setLit(boolean lit) {
        BlockState current = level.getBlockState(worldPosition);
        if (current.getBlock() instanceof FryerStationBlock && current.getValue(CookingStationBlock.LIT) != lit) {
            level.setBlock(worldPosition, current.setValue(CookingStationBlock.LIT, lit), Block.UPDATE_ALL);
        }
    }
    private void flushOutputs() {
        boolean wasInternal = internal, changed = false;
        internal = true;
        while (!bufferedOutputs.isEmpty()) {
            ItemStack first = bufferedOutputs.get(0), output = inventory.getStackInSlot(8);
            if (output.isEmpty()) { inventory.setStackInSlot(8, first); bufferedOutputs.remove(0); }
            else if (ItemStack.isSameItemSameTags(output, first) && output.getCount() + first.getCount() <= output.getMaxStackSize()) {
                output.grow(first.getCount()); inventory.setStackInSlot(8, output); bufferedOutputs.remove(0);
            } else break;
            changed = true; setChanged();
        }
        internal = wasInternal;
        if (changed && !internal) checkpoint();
    }
    private void returnItem(ItemStack stack, int preferred) {
        boolean wasInternal = internal;
        internal = true;
        stack = inventory.insertItem(preferred, stack, false);
        for (int i = 0; i < 7 && !stack.isEmpty(); i++) stack = inventory.insertItem(i, stack, false);
        internal = wasInternal;
        if (!stack.isEmpty()) returnedItems.add(stack);
    }
    private void flushReturns() {
        boolean wasInternal = internal, changed = false;
        internal = true;
        for (int index = 0; index < returnedItems.size();) {
            ItemStack stack = returnedItems.get(index);
            int before = stack.getCount();
            for (int slot = 0; slot < 7 && !stack.isEmpty(); slot++) stack = inventory.insertItem(slot, stack, false);
            if (stack.getCount() != before) changed = true;
            if (stack.isEmpty()) returnedItems.remove(index); else { returnedItems.set(index, stack); index++; }
        }
        internal = wasInternal;
        if (changed && !internal) checkpoint();
    }

    public List<ItemStack> breakAndDrop() {
        if (journalFailed) throw new IllegalStateException("Fryer journal failed; cannot drop unpersisted inventory at " + worldPosition);
        ensureHydrated();
        if (phase == 2) { ZhangshaoKernel.abandon(state); settleManual(null, "炸锅被拆除"); }
        else if (phase == 1) settleHome(null, "炸锅被拆除");
        if (recoverLoaded) recoverInterrupted();
        List<ItemStack> drops = new ArrayList<>();
        internal = true;
        for (int i = 0; i < 9; i++) { drops.add(inventory.getStackInSlot(i)); inventory.setStackInSlot(i, ItemStack.EMPTY); }
        drops.addAll(bufferedOutputs); bufferedOutputs.clear();
        drops.addAll(returnedItems); returnedItems.clear(); internal = false;
        // 空快照是拆除 tombstone；旧 chunk 即使恢复了方块，也不能复活已取走/掉落的库存。
        checkpoint(); return drops;
    }
    private void recoverInterrupted() {
        if (charged) playerData().enqueueRefund(operation, owner);
        internal = true;
        for (int slot = 0; slot < 7; slot++) { returnItem(held[slot], slot); held[slot] = ItemStack.EMPTY; }
        playerData().release(owner, this, System.currentTimeMillis());
        phase = 0; recoverLoaded = charged = false; owner = operation = null; dish = ItemStack.EMPTY; recipe = null; state = null; setLit(false); setChanged();
        internal = false; checkpoint(); playerData().retryRefunds();
    }

    @Override public Component getDisplayName() { return Component.literal("炸锅"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) { return new FryerMenu(id, inventory, this); }
    @Override public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) return capability.cast();
        return super.getCapability(cap, side);
    }
    @Override public void invalidateCaps() { super.invalidateCaps(); capability.invalidate(); }
    @Override public void reviveCaps() { super.reviveCaps(); capability = LazyOptional.of(() -> automation); }
    @Override public void setRemoved() {
        if (level instanceof ServerLevel && owner != null) playerData().release(owner, this, System.currentTimeMillis());
        super.setRemoved();
    }

    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag); tag.putInt("FryerVersion", 2); tag.put("Inventory", inventory.serializeNBT());
        ListTag outputs = new ListTag(); for (ItemStack stack : bufferedOutputs) outputs.add(stack.save(new CompoundTag()));
        tag.put("BufferedOutputs", outputs); tag.putBoolean("InProgress", phase == 1 || phase == 2 || recoverLoaded);
        ListTag returns = new ListTag(); for (ItemStack stack : returnedItems) returns.add(stack.save(new CompoundTag()));
        tag.put("ReturnedItems", returns);
        if (phase == 1 || phase == 2 || recoverLoaded) {
            tag.putUUID("Owner", owner); tag.putUUID("Operation", operation); tag.putBoolean("Charged", charged);
            ListTag reserved = new ListTag();
            for (int slot = 0; slot < 7; slot++) {
                CompoundTag item = new CompoundTag(); item.putInt("Slot", slot);
                item.put("Stack", held[slot].isEmpty() ? new CompoundTag() : held[slot].save(new CompoundTag())); reserved.add(item);
            }
            tag.put("Reserved", reserved);
        }
    }
    @Override public void load(CompoundTag tag) {
        super.load(tag); readSnapshot(tag); hydrated = false;
    }
    private void readSnapshot(CompoundTag tag) {
        boolean wasInternal = internal;
        loading = internal = true;
        try { readState(tag); }
        finally { internal = wasInternal; loading = false; }
    }
    private void readState(CompoundTag tag) {
        phase = 0; recoverLoaded = charged = false; owner = operation = null;
        dish = ItemStack.EMPTY; recipe = null; state = null;
        // 放置方块尚未保存过时，无 FryerVersion 即空初始态。
        if (!tag.contains("FryerVersion")) return;
        FryerPlayerData.number(tag, "FryerVersion", Tag.TAG_INT, 2, 2);
        if (!tag.contains("Inventory", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Missing fryer inventory");
        CompoundTag inv = tag.getCompound("Inventory");
        FryerPlayerData.number(inv, "Size", Tag.TAG_INT, 9, 9);
        boolean[] inventorySeen = new boolean[9];
        for (Tag entry : FryerPlayerData.list(inv, "Items", 9)) {
            CompoundTag item = (CompoundTag) entry;
            int slot = (int) FryerPlayerData.number(item, "Slot", Tag.TAG_INT, 0, 8);
            if (inventorySeen[slot]) throw new IllegalArgumentException("Duplicate fryer inventory slot " + slot);
            inventorySeen[slot] = true;
            ItemStack stack = ItemStack.of(item);
            if (stack.isEmpty() || stack.getCount() > stack.getMaxStackSize()) throw new IllegalArgumentException("Invalid fryer inventory stack");
        }
        inventory.deserializeNBT(inv); bufferedOutputs.clear();
        for (Tag entry : FryerPlayerData.list(tag, "BufferedOutputs", 2)) {
            ItemStack stack = ItemStack.of((CompoundTag) entry);
            if (stack.isEmpty() || stack.getCount() > stack.getMaxStackSize()) throw new IllegalArgumentException("Invalid fryer buffered output");
            bufferedOutputs.add(stack);
        }
        returnedItems.clear();
        for (Tag entry : FryerPlayerData.list(tag, "ReturnedItems", 512)) {
            ItemStack stack = ItemStack.of((CompoundTag) entry);
            if (stack.isEmpty() || stack.getCount() > stack.getMaxStackSize()) throw new IllegalArgumentException("Invalid fryer returned item");
            returnedItems.add(stack);
        }
        if (!tag.contains("InProgress", Tag.TAG_BYTE)) throw new IllegalArgumentException("Missing fryer transaction marker");
        recoverLoaded = tag.getBoolean("InProgress"); phase = 0;
        Arrays.fill(held, ItemStack.EMPTY);
        if (recoverLoaded) {
            if (!tag.hasUUID("Owner") || !tag.hasUUID("Operation") || !tag.contains("Charged", Tag.TAG_BYTE)) throw new IllegalArgumentException("Invalid fryer transaction owner");
            owner = tag.getUUID("Owner"); operation = tag.getUUID("Operation"); charged = tag.getBoolean("Charged");
            ListTag reserved = FryerPlayerData.list(tag, "Reserved", 7);
            if (reserved.size() != 7) throw new IllegalArgumentException("Incomplete fryer reservations");
            boolean[] seen = new boolean[7];
            for (Tag entry : reserved) {
                CompoundTag item = (CompoundTag) entry;
                int slot = (int) FryerPlayerData.number(item, "Slot", Tag.TAG_INT, 0, 6);
                if (seen[slot] || !item.contains("Stack", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Invalid fryer reserved slot");
                seen[slot] = true;
                CompoundTag stackTag = item.getCompound("Stack");
                ItemStack stack = stackTag.isEmpty() ? ItemStack.EMPTY : ItemStack.of(stackTag);
                if (!stackTag.isEmpty() && stack.isEmpty()) throw new IllegalArgumentException("Invalid fryer reserved item");
                if (stack.getCount() > stack.getMaxStackSize()) throw new IllegalArgumentException("Invalid fryer reserved stack");
                held[slot] = stack;
            }
        }
    }
}
