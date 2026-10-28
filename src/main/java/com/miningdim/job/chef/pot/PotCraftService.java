package com.miningdim.job.chef.pot;

import com.miningdim.economy.EconomyOperationDomain;
import com.miningdim.core.auth.PlayerLoginGate;
import com.miningdim.economy.EconomyOperationStatus;
import com.miningdim.economy.EconomyServices;
import com.miningdim.job.chef.*;
import com.miningdim.job.chef.fryer.FryerNetwork;
import com.miningdim.job.chef.fryer.FryerPlayerData;
import com.miningdim.job.chef.recipe.ChefDishCatalog;
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
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import vectorwing.farmersdelight.common.block.entity.container.CookingPotMenu;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;
import vectorwing.farmersdelight.common.registry.ModRecipeTypes;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Single-serving production on FD's original pot and recipe inventory. */
public final class PotCraftService {
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Owned by one block entity, including while native loot serializes a removed pot. */
    public static final class PotState {
        boolean hydrated, loading, internal, failed;
        int batch;
        boolean dirty;
        boolean previewing;
        long revision;
        final List<ItemStack> pending = new ArrayList<>();
        Round round;
    }
    private static final class Round {
        UUID owner, operation;
        long id, started, lastInput, minOffset = Long.MAX_VALUE;
        int seed, sequence, received, latePackets;
        boolean charged, gold, slow, bottleSent;
        ItemStack dish, container;
        ResourceLocation dishId;
        ItemStack[] held = new ItemStack[6];
        ZhangshaoKernel.State game;
        int[] replay;
        Round() { Arrays.fill(held, ItemStack.EMPTY); }
    }
    public record Status(boolean busy, int progress, long cost) {}

    private PotCraftService() {}
    private static PotState state(CookingPotBlockEntity pot) { return ((PotBridge) pot).wok$productionState(); }
    private static ItemStackHandler raw(CookingPotBlockEntity pot) { return ((PotBridge) pot).wok$rawInventory(); }
    public static boolean internal(CookingPotBlockEntity pot) { return !pot.isRemoved() && state(pot).internal; }
    public static boolean locked(CookingPotBlockEntity pot) { return pot.isRemoved() || state(pot).round != null || state(pot).failed; }
    public static boolean canServe(CookingPotBlockEntity pot) { return !locked(pot); }
    public static Status status(CookingPotBlockEntity pot) {
        if (pot.isRemoved()) return new Status(true, 0, ChefConfig.seasoningCreditCost());
        ensureHydrated(pot);
        PotState s = state(pot);
        Round r = s.round;
        return new Status(r != null || !s.pending.isEmpty(), r == null || r.game == null ? 0 : r.game.P / 1000, ChefConfig.seasoningCreditCost());
    }
    public static @Nullable ZhangshaoKernel.State sessionState(CookingPotBlockEntity pot) {
        Round r = state(pot).round; return r == null || r.game == null ? null : r.game.copy();
    }
    public static long roundId(CookingPotBlockEntity pot) {
        Round r = state(pot).round; return r == null ? 0L : r.id;
    }

    public static void onLoad(CookingPotBlockEntity pot, CompoundTag tag) {
        PotState s = state(pot);
        s.loading = true;
        try {
            if (tag.contains("FryerRevision") && (!tag.contains("FryerRevision", Tag.TAG_LONG) || tag.getLong("FryerRevision") < 0))
                throw new IllegalArgumentException("Invalid chef pot checkpoint revision");
            s.revision = tag.getLong("FryerRevision");
            s.round = null;
            if (tag.contains("WokPotRound", Tag.TAG_COMPOUND)) {
                CompoundTag txn = tag.getCompound("WokPotRound");
                if (!txn.hasUUID("Owner") || !txn.hasUUID("Operation") || !txn.contains("Held", Tag.TAG_LIST)
                        || !txn.contains("Charged", Tag.TAG_BYTE))
                    throw new IllegalArgumentException("Invalid chef pot transaction at " + pot.getBlockPos());
                Round r = new Round();
                r.owner = txn.getUUID("Owner"); r.operation = txn.getUUID("Operation"); r.charged = txn.getBoolean("Charged");
                ListTag held = txn.getList("Held", Tag.TAG_COMPOUND);
                if (held.size() != 6) throw new IllegalArgumentException("Incomplete chef pot reservations");
                boolean reserved = false;
                for (int i = 0; i < 6; i++) {
                    CompoundTag item = held.getCompound(i);
                    r.held[i] = ItemStack.of(item);
                    if (!item.isEmpty() && !item.equals(ItemStack.EMPTY.save(new CompoundTag())) && r.held[i].isEmpty())
                        throw new IllegalArgumentException("Invalid chef pot reserved item");
                    if (r.held[i].getCount() > r.held[i].getMaxStackSize()) throw new IllegalArgumentException("Oversized chef pot reserved item");
                    reserved |= !r.held[i].isEmpty();
                }
                if (!reserved) throw new IllegalArgumentException("Empty chef pot transaction");
                s.round = r;
            }
            s.pending.clear();
            if (tag.contains("WokPotPending", Tag.TAG_LIST)) {
                ListTag pending = tag.getList("WokPotPending", Tag.TAG_COMPOUND);
                if (pending.size() > 8) throw new IllegalArgumentException("Too many pending chef pot dishes");
                for (Tag entry : pending) {
                    ItemStack item = ItemStack.of((CompoundTag) entry);
                    if (item.isEmpty()) throw new IllegalArgumentException("Invalid pending chef pot dish");
                    s.pending.add(item);
                }
            }
            s.hydrated = false;
        } finally { s.loading = false; }
    }
    public static void onSave(CookingPotBlockEntity pot, CompoundTag tag) {
        PotState s = state(pot);
        tag.putLong("FryerRevision", s.revision);
        if (!s.pending.isEmpty()) {
            ListTag pending = new ListTag();
            for (ItemStack item : s.pending) pending.add(item.save(new CompoundTag()));
            tag.put("WokPotPending", pending);
            tag.put("Container", ((PotBridge) pot).wok$mealContainer().save(new CompoundTag()));
            if (!tag.contains("Inventory", Tag.TAG_COMPOUND)) tag.put("Inventory", new ItemStackHandler(9).serializeNBT());
        }
        Round r = s.round;
        if (r == null || s.previewing) return;
        CompoundTag txn = new CompoundTag();
        txn.putUUID("Owner", r.owner); txn.putUUID("Operation", r.operation); txn.putBoolean("Charged", r.charged);
        ListTag held = new ListTag();
        for (ItemStack stack : r.held) held.add(stack.isEmpty() ? new CompoundTag() : stack.save(new CompoundTag()));
        txn.put("Held", held); tag.put("WokPotRound", txn);
    }
    public static void ensureHydrated(CookingPotBlockEntity pot) {
        if (pot.isRemoved()) return;
        PotState s = state(pot);
        if (s.hydrated || s.loading || !(pot.getLevel() instanceof ServerLevel level)) return;
        s.loading = s.internal = true;
        try {
            CompoundTag snapshot = FryerPlayerData.get(level).checkpoint(level, pot.getBlockPos());
            if (snapshot != null && snapshot.getLong("FryerRevision") >= s.revision) { pot.load(snapshot); s.loading = true; }
            s.hydrated = true;
            if (s.round != null) recover(pot, s.round);
            else checkpoint(pot);
        } finally { s.loading = s.internal = false; }
    }
    public static void beginBatch(CookingPotBlockEntity pot) { ensureHydrated(pot); state(pot).batch++; }
    public static void endBatch(CookingPotBlockEntity pot) {
        PotState s = state(pot);
        if (s.batch == 0) return;
        if (--s.batch == 0 && s.dirty) { s.dirty = false; checkpoint(pot, false); }
    }
    public static void changed(CookingPotBlockEntity pot) {
        if (pot.isRemoved()) return;
        PotState s = state(pot);
        if (s.loading || !(pot.getLevel() instanceof ServerLevel)) return;
        ensureHydrated(pot);
        if (s.internal) return;
        if (s.batch > 0) s.dirty = true; else checkpoint(pot, false);
    }
    private static void checkpoint(CookingPotBlockEntity pot) { checkpoint(pot, true); }
    private static void checkpoint(CookingPotBlockEntity pot, boolean sync) {
        if (pot.isRemoved()) return;
        PotState s = state(pot);
        if (!(pot.getLevel() instanceof ServerLevel level) || s.failed) return;
        boolean done = false;
        try {
            FryerPlayerData data = FryerPlayerData.get(level);
            s.revision = data.checkpoint(level, pot.getBlockPos(), pot.saveWithoutMetadata());
            if (sync) data.sync();
            pot.setChanged(); done = true;
        } finally { if (!done) s.failed = true; }
    }
    public static void placed(CookingPotBlockEntity pot) {
        if (!(pot.getLevel() instanceof ServerLevel)) return;
        state(pot).hydrated = true;
        checkpoint(pot);
    }
    public static void tombstone(ServerLevel level, BlockPos pos) {
        CompoundTag empty = new CompoundTag();
        empty.put("Inventory", new ItemStackHandler(9).serializeNBT());
        empty.put("Container", ItemStack.EMPTY.save(new CompoundTag()));
        empty.putBoolean("FryerRemoved", true);
        FryerPlayerData data = FryerPlayerData.get(level);
        data.checkpoint(level, pos, empty); data.sync();
    }
    private static void reject(ServerPlayer player, String reason) { player.displayClientMessage(Component.literal(reason), true); }
    private static boolean controls(ServerPlayer player, CookingPotBlockEntity pot) {
        BlockPos pos = pot.getBlockPos();
        return PlayerLoginGate.allows(player) && player.level() == pot.getLevel() && !player.isRemoved() && player.isAlive()
                && pot.getLevel().getBlockEntity(pos) == pot
                && player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= 64
                && player.containerMenu instanceof CookingPotMenu menu && menu.blockEntity == pot && menu.stillValid(player);
    }
    private static @Nullable CookingPotRecipe recipe(CookingPotBlockEntity pot) {
        RecipeWrapper input = new RecipeWrapper(raw(pot));
        return pot.getLevel().getRecipeManager().getAllRecipesFor(ModRecipeTypes.COOKING.get()).stream()
                .filter(r -> r.matches(input, pot.getLevel()))
                .sorted(java.util.Comparator.comparing(r -> r.getId().toString())).findFirst().orElse(null);
    }
    public static boolean start(ServerPlayer player, CookingPotBlockEntity pot) {
        ensureHydrated(pot);
        PotState s = state(pot);
        if (!controls(player, pot)) { reject(player, "请站在厨锅旁打开原锅菜单"); return false; }
        if (s.failed) { reject(player, "厨锅存档失败，事务已锁定"); return false; }
        if (s.round != null || !s.pending.isEmpty()) { reject(player, "这口锅还有待盛出的菜"); return false; }
        if (!pot.isHeated()) { reject(player, "厨锅尚未加热"); return false; }
        ItemStackHandler inv = raw(pot);
        if (!inv.getStackInSlot(6).isEmpty() || !inv.getStackInSlot(8).isEmpty()) { reject(player, "请先取走锅中的菜"); return false; }
        CookingPotRecipe recipe = recipe(pot);
        if (recipe == null) { reject(player, "原料不符合厨锅菜谱"); return false; }
        ItemStack dish = recipe.assemble(new RecipeWrapper(inv), pot.getLevel().registryAccess());
        ResourceLocation dishId = ForgeRegistries.ITEMS.getKey(dish.getItem());
        ChefDishCatalog.Dish metadata = ChefDishCatalog.find(dishId);
        if (dish.isEmpty() || metadata == null || metadata.station() != 0) { reject(player, "这道菜尚未开放正式掌勺"); return false; }
        long now = System.currentTimeMillis(); FryerPlayerData data = FryerPlayerData.get((ServerLevel) pot.getLevel());
        if (!data.available(player.getUUID(), now)) { reject(player, "手累了，歇一下，或先结束另一锅"); return false; }
        long cost = EconomyServices.isRegistered() ? ChefConfig.seasoningCreditCost() : 0L;
        if (cost > 0 && EconomyServices.economyService().creditBalance(player) < cost) { reject(player, "信用点不足"); return false; }
        Round r = new Round(); r.owner = player.getUUID(); r.operation = UUID.randomUUID(); r.id = RANDOM.nextLong();
        r.dish = dish.copy(); r.dishId = dishId; r.container = recipe.getOutputContainer().copy(); r.charged = cost > 0;
        ZhangshaoConfig.Options options = new ZhangshaoConfig.Options();
        options.station = 0; options.star = metadata.star(); options.motion = metadata.motion();
        options.flips = metadata.flips(); options.beats = metadata.beats(); options.drink = metadata.drink();
        options.level = ChefExperience.level(player); options.mastery = data.mastery(r.owner, dishId);
        options.goldBlocked = data.goldBlocked(r.owner, now);
        int[] params = new ZhangshaoConfig().buildParams(options);
        r.seed = RANDOM.nextInt(); r.game = ZhangshaoKernel.init(new ZhangshaoKernel.Params(params), r.seed);
        int replayLimit = r.game.p.CAP;
        for (int[] beats : r.game.segBeats) replayLimit += beats[beats.length - 1] + r.game.p.goodW + r.game.p.beatTail + 1;
        r.replay = new int[replayLimit]; r.gold = params[15] > 0 && RANDOM.nextInt(params[15]) < params[16];
        s.internal = true;
        try { for (int i = 0; i < 6; i++) if (!inv.getStackInSlot(i).isEmpty()) r.held[i] = inv.extractItem(i, 1, false); }
        finally { s.internal = false; }
        s.round = r; data.begin(r.owner, pot, r.operation, now);
        if (r.charged) data.enqueueRefund(r.operation, r.owner);
        checkpoint(pot);
        if (cost > 0) {
            EconomyOperationStatus payment;
            try {
                payment = EconomyServices.economyService().tryChargeBundle(
                        EconomyOperationDomain.CHEF_FRYER, player, r.operation, cost, 0L);
            } catch (RuntimeException failure) {
                recover(pot, r);
                throw failure;
            }
            if (payment != EconomyOperationStatus.CHARGED) {
                recover(pot, r); data.cancelStart(r.owner, now); reject(player, "信用点不足"); return false;
            }
        }
        ((PotBridge) pot).wok$setCookTime(0);
        checkpoint(pot);
        r.started = r.lastInput = System.nanoTime();
        FryerNetwork.session(player, pot.getBlockPos(), r.id, r.seed, r.game.p.block(), r.dish.copy());
        return true;
    }

    public static void acceptInput(ServerPlayer player, CookingPotBlockEntity pot, long round, int sequence,
                                   int firstTick, byte[] inputs, int clientHash, boolean finalPacket) {
        PotState s = state(pot); Round r = s.round;
        if (s.failed || r == null || r.id != round || !r.owner.equals(player.getUUID())) return;
        if (!controls(player, pot)) { abort(player, pot, round, "离开厨锅"); return; }
        if (System.nanoTime() - r.lastInput > 5_000_000_000L) { abort(player, pot, round, "输入中断"); return; }
        if (sequence != r.sequence || firstTick != r.received || inputs.length < 1 || inputs.length > 40
                || r.received + inputs.length > r.replay.length) { invalid(player, pot, "输入序号或长度不合法"); return; }
        for (byte input : inputs) if ((input & 0xfc) != 0) { invalid(player, pot, "输入保留位不合法"); return; }
        long elapsed = (System.nanoTime() - r.started) / 1_000_000L;
        if ((r.received + inputs.length) * 50L > elapsed - 400L) { invalid(player, pot, "输入快于真实时间"); return; }
        long offset = elapsed - 1000L - (r.received + inputs.length) * 50L;
        r.minOffset = Math.min(r.minOffset, offset);
        if (offset - r.minOffset > 2000L) r.latePackets++; else r.latePackets = 0;
        if (!r.slow && (r.latePackets >= 3 || finalPacket && offset - r.minOffset > 2000L)) {
            r.slow = true; FryerPlayerData.get((ServerLevel) pot.getLevel()).addSuspicion(r.owner, 20, System.currentTimeMillis());
        }
        r.sequence++; r.lastInput = System.nanoTime();
        for (byte input : inputs) {
            if (r.game.end != ZhangshaoKernel.RUNNING) break;
            r.replay[r.received++] = input; ZhangshaoKernel.step(r.game, input);
        }
        if (!r.bottleSent && r.game.bKind != ZhangshaoKernel.KIND_NONE && r.game.bState != ZhangshaoKernel.B_WAITING) {
            FryerNetwork.bottle(player, r.id, r.gold); r.bottleSent = true;
        }
        if (ZhangshaoKernel.hash(r.game) != clientHash) { invalid(player, pot, "输入回放校验失败"); return; }
        if (finalPacket && r.game.end == ZhangshaoKernel.RUNNING) {
            while (r.game.end == ZhangshaoKernel.RUNNING) { r.replay[r.received++] = 0; ZhangshaoKernel.step(r.game, 0); }
        }
        if (r.game.end != ZhangshaoKernel.RUNNING) settle(pot, player, "出锅");
    }
    private static void invalid(ServerPlayer player, CookingPotBlockEntity pot, String reason) {
        Round r = state(pot).round;
        FryerPlayerData.get((ServerLevel) pot.getLevel()).addSuspicion(r.owner, 20, System.currentTimeMillis());
        abort(player, pot, r.id, reason);
    }
    public static void abort(ServerPlayer player, CookingPotBlockEntity pot, long round, String reason) {
        Round r = state(pot).round;
        if (r != null && r.id == round && r.owner.equals(player.getUUID())) {
            ZhangshaoKernel.abandon(r.game); settle(pot, player, reason + "，这锅只能算家常菜");
        }
    }
    public static boolean tick(CookingPotBlockEntity pot) {
        ensureHydrated(pot);
        PotState s = state(pot);
        if (s.failed) return true;
        Round r = s.round;
        flushPending(pot);
        if (r == null) return false;
        ServerPlayer player = ((ServerLevel) pot.getLevel()).getServer().getPlayerList().getPlayer(r.owner);
        if (player == null || !controls(player, pot) || !pot.isHeated() || System.nanoTime() - r.lastInput > 5_000_000_000L) {
            ZhangshaoKernel.abandon(r.game); settle(pot, player, "撂勺，这锅只能算家常菜");
        }
        return true;
    }
    public static void beforeRemoval(CookingPotBlockEntity pot) {
        if (!(pot.getLevel() instanceof ServerLevel) || pot.isRemoved()) return;
        ensureHydrated(pot);
        if (state(pot).failed) throw new IllegalStateException("Chef pot journal failed at " + pot.getBlockPos());
        Round r = state(pot).round;
        if (r != null) { ZhangshaoKernel.abandon(r.game); settle(pot, null, "厨锅拆除"); }
    }
    public static void beforePackaging(CookingPotBlockEntity pot) {
        if (pot.getLevel() instanceof ServerLevel && !state(pot).previewing) beforeRemoval(pot);
    }
    public static CompoundTag previewMeal(CookingPotBlockEntity pot, CompoundTag tag) {
        if (pot.isRemoved()) return tag;
        ensureHydrated(pot);
        PotState s = state(pot);
        boolean previous = s.previewing;
        s.previewing = true;
        try { return pot.writeMeal(tag); }
        finally { s.previewing = previous; }
    }
    public static void removed(CookingPotBlockEntity pot) {
        PotState s = state(pot);
        if (s.round != null && pot.getLevel() instanceof ServerLevel level) {
            FryerPlayerData.get(level).release(s.round.owner, pot, System.currentTimeMillis());
        }
    }

    private static void settle(CookingPotBlockEntity pot, @Nullable ServerPlayer player, String message) {
        PotState s = state(pot); Round r = s.round;
        if (r == null) return;
        if (s.failed) throw new IllegalStateException("Chef pot journal failed at " + pot.getBlockPos());
        FryerPlayerData data = FryerPlayerData.get((ServerLevel) pot.getLevel());
        ZhangshaoKernel.Result result = ZhangshaoKernel.result(r.game, new ZhangshaoKernel.Ext(
                r.gold ? 1 : 0, r.slow ? 1 : 0,
                ZhangshaoKernel.detectMechanical(r.replay, r.received, r.game.p),
                data.suspect(r.owner, System.currentTimeMillis())));
        ItemStack product = r.dish.copy();
        if (result.quality >= 0) {
            ChefQuality quality = ChefQuality.byTier(result.quality);
            if (quality == ChefQuality.RADIANT && product.getCount() > 1) {
                ItemStack rest = product.copy(); rest.shrink(1); product.setCount(1);
                stamp(rest, ChefQuality.EXTRAORDINARY, r.owner, r.id, result);
                s.pending.add(rest);
            }
            stamp(product, quality, r.owner, r.id, result);
            data.record(r.owner, r.dishId, result.quality, result.score, System.currentTimeMillis());
        }
        s.internal = true;
        try {
            ItemStackHandler inv = raw(pot);
            if (!inv.getStackInSlot(6).isEmpty()) throw new IllegalStateException("Chef pot meal slot changed during session");
            inv.setStackInSlot(6, product);
            ((PotBridge) pot).wok$setMealContainer(r.container.copy());
            for (ItemStack ingredient : r.held) if (!ingredient.isEmpty()) {
                ItemStack remainder = ingredient.hasCraftingRemainingItem() ? ingredient.getCraftingRemainingItem()
                        : CookingPotBlockEntity.INGREDIENT_REMAINDER_OVERRIDES.containsKey(ingredient.getItem())
                        ? CookingPotBlockEntity.INGREDIENT_REMAINDER_OVERRIDES.get(ingredient.getItem()).getDefaultInstance()
                        : ItemStack.EMPTY;
                for (int i = 0; i < 6 && !remainder.isEmpty(); i++) remainder = inv.insertItem(i, remainder, false);
                if (!remainder.isEmpty()) net.minecraft.world.level.block.Block.popResource(pot.getLevel(), pot.getBlockPos(), remainder);
            }
            s.round = null;
            data.release(r.owner, pot, System.currentTimeMillis());
            if (r.charged) data.enqueueCompletion(r.operation, r.owner);
            checkpoint(pot);
        } finally { s.internal = false; }
        data.retryRefunds();
        if (player != null) {
            if (result.quality >= 0) {
                ChefQuality quality = ChefQuality.byTier(result.quality);
                long xp = (long) ChefConfig.rawXp(quality) * (1000 + 150 * (r.game.p.star - 1)) / 1000;
                ChefExperience.awardSeasoningComplete(player, xp);
                ChefEvents.fireDish(player, quality, quality);
            }
            FryerNetwork.result(player, r.id, result, message);
        }
    }
    private static void stamp(ItemStack product, ChefQuality quality, UUID owner, long roundId, ZhangshaoKernel.Result result) {
        List<ChefEffectInstance> effects = new ArrayList<>();
        for (int negative : result.negatives) {
            ChefEffectType type = switch (negative) {
                case ZhangshaoKernel.NEG_UNDERDONE -> ChefEffectType.UNDERDONE;
                case ZhangshaoKernel.NEG_SCORCHED -> ChefEffectType.SCORCHED;
                case ZhangshaoKernel.NEG_NAUSEA -> ChefEffectType.NAUSEA;
                default -> throw new IllegalArgumentException("Unknown chef pot negative " + negative);
            };
            int magnitude = switch (type) {
                case UNDERDONE -> switch (quality) {
                    case LOW -> ChefConfig.UNDERDONE_CHANCE_LOW.get();
                    case MEDIUM -> ChefConfig.UNDERDONE_CHANCE_MEDIUM.get();
                    case HIGH -> ChefConfig.UNDERDONE_CHANCE_HIGH.get();
                    default -> throw new IllegalStateException("Underdone on exceptional dish");
                };
                case SCORCHED -> switch (quality) {
                    case LOW -> ChefConfig.SCORCHED_PCT_LOW.get();
                    case MEDIUM -> ChefConfig.SCORCHED_PCT_MEDIUM.get();
                    case HIGH -> ChefConfig.SCORCHED_PCT_HIGH.get();
                    default -> throw new IllegalStateException("Scorched on exceptional dish");
                };
                case NAUSEA -> quality == ChefQuality.LOW ? 2 : 1;
                default -> throw new IllegalStateException("Unexpected pot negative effect");
            };
            effects.add(new ChefEffectInstance(type, magnitude));
        }
        ChefQualityNbt.stamp(product, quality, effects);
        ChefQualityNbt.setOperator(product, owner);
        product.getOrCreateTag().getCompound("MiningChef").putLong("roundId", roundId);
    }
    private static void flushPending(CookingPotBlockEntity pot) {
        PotState s = state(pot);
        if (s.pending.isEmpty() || !raw(pot).getStackInSlot(6).isEmpty()) return;
        s.internal = true;
        try {
            raw(pot).setStackInSlot(6, s.pending.remove(0));
            checkpoint(pot);
        } finally { s.internal = false; }
    }
    private static void recover(CookingPotBlockEntity pot, Round r) {
        PotState s = state(pot); FryerPlayerData data = FryerPlayerData.get((ServerLevel) pot.getLevel());
        s.internal = true;
        try {
            ItemStackHandler inv = raw(pot);
            for (int i = 0; i < 6; i++) {
                ItemStack item = r.held[i];
                if (item.isEmpty()) continue;
                ItemStack rest = inv.insertItem(i, item, false);
                for (int j = 0; j < 6 && !rest.isEmpty(); j++) rest = inv.insertItem(j, rest, false);
                if (!rest.isEmpty()) net.minecraft.world.level.block.Block.popResource(pot.getLevel(), pot.getBlockPos(), rest);
            }
            s.round = null;
            data.release(r.owner, pot, System.currentTimeMillis());
            if (r.charged) data.enqueueRefund(r.operation, r.owner);
            checkpoint(pot);
        } finally { s.internal = false; }
        data.retryRefunds();
    }
}
