package com.miningdim.job.fisher.soup;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.chef.ChefEffectInstance;
import com.miningdim.job.chef.ChefEffectType;
import com.miningdim.job.chef.ChefQualityNbt;
import com.miningdim.job.chef.ChefWindowEffectState;
import com.miningdim.job.fisher.ore.OreFishType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BooleanSupplier;

/** Runtime state and server-side effect settlement for ore-fish soups. */
public final class OreSoupEffects {
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/job/fisher/soup");
    private static final String STATE_TAG = "MiningOreFishSoup";
    private static final String TYPE_TAG = "type";
    private static final String EXPIRES_AT_TAG = "expiresAt";
    private static final String NIGHT_VISION_UNTIL_TAG = "nightVisionUntil";
    private static final int BASE_DURATION_TICKS = 300 * 20;
    private static final int MAX_DURATION_TICKS = 1500 * 20;
    private static final int NIGHT_VISION_TICKS = 400;
    private static final ThreadLocal<MiningDurabilityAttempt> MINING_DURABILITY_ATTEMPT = new ThreadLocal<>();

    private OreSoupEffects() {
    }

    public static void register(IEventBus modBus, IEventBus forgeBus) {
        forgeBus.addListener((TickEvent.PlayerTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer player) {
                onPlayerTick(player);
            }
        });
        forgeBus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                syncClientState(player, activeInMining(player) ? activeType(player) : null);
            }
        });
        forgeBus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                // 离线时药水时长暂停而世界时钟继续，先撤掉汤夜视，重登后按剩余汤时间授予。
                removeOwnedNightVision(player, player.serverLevel().getGameTime());
            }
        });
    }

    public static void applyConsumedSoup(ServerPlayer player, OreFishType type, ItemStack consumedStack) {
        if (isSpoiled(consumedStack)) {
            return;
        }
        long now = player.serverLevel().getGameTime();
        removeOwnedNightVision(player, now);
        CompoundTag state = new CompoundTag();
        state.putString(TYPE_TAG, type.id());
        state.putLong(EXPIRES_AT_TAG, now + durationTicks(consumedStack));
        player.getPersistentData().put(STATE_TAG, state);
        syncClientState(player, type);
        if (player.level().dimension().equals(MiningConstants.MINING_LEVEL)) {
            refreshNightVision(player, now);
        }
    }

    public static OreFishType activeType(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(STATE_TAG, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag state = data.getCompound(STATE_TAG);
        if (!state.contains(TYPE_TAG, Tag.TAG_STRING) || !state.contains(EXPIRES_AT_TAG, Tag.TAG_LONG)) {
            LOGGER.warn("Removing malformed ore soup state for player {}", player.getUUID());
            data.remove(STATE_TAG);
            return null;
        }
        if (state.getLong(EXPIRES_AT_TAG) <= player.serverLevel().getGameTime()) {
            clearExpiredState(player);
            return null;
        }
        String id = state.getString(TYPE_TAG);
        for (OreFishType type : OreFishType.values()) {
            if (type.id().equals(id)) {
                return type;
            }
        }
        LOGGER.warn("Removing ore soup state with unknown type '{}' for player {}", id, player.getUUID());
        data.remove(STATE_TAG);
        return null;
    }

    public static boolean activeInMining(ServerPlayer player) {
        return player.level().dimension().equals(MiningConstants.MINING_LEVEL) && activeType(player) != null;
    }

    public static int miningSpeedBonusPercent(ServerPlayer player) {
        OreFishType type = activeInMining(player) ? activeType(player) : null;
        return miningSpeedBonusPercent(type);
    }

    public static int miningSpeedBonusPercent(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            return miningSpeedBonusPercent(serverPlayer);
        }
        if (!player.level().dimension().equals(MiningConstants.MINING_LEVEL)) {
            return 0;
        }
        return miningSpeedBonusPercent(typeFromSyncedState(((OreSoupPlayerStateAccess) player).miningdim$getOreSoupState()));
    }

    public static int exhaustionReductionPerMille(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || !activeInMining(serverPlayer)) {
            return 0;
        }
        OreFishType type = activeType(serverPlayer);
        int soupReduction = type == OreFishType.IRON || type == OreFishType.DARK_GOLD ? 250 : 0;
        int chefReduction = ChefWindowEffectState.magnitudeOf(serverPlayer, ChefEffectType.ENDURANCE);
        return Math.max(soupReduction, chefReduction);
    }

    public static float reduceExhaustion(Player player, float exhaustion) {
        int reduction = exhaustionReductionPerMille(player);
        return reduction == 0 ? exhaustion : exhaustion * (1000 - reduction) / 1000.0F;
    }

    public static boolean withMiningDurabilityAttempt(ItemStack stack, Player player, BooleanSupplier action) {
        if (!(player instanceof ServerPlayer serverPlayer) || !activeInMining(serverPlayer)
                || !hasDurabilityReduction(activeType(serverPlayer))) {
            return action.getAsBoolean();
        }
        MINING_DURABILITY_ATTEMPT.set(new MiningDurabilityAttempt(stack, serverPlayer));
        try {
            return action.getAsBoolean();
        } finally {
            MINING_DURABILITY_ATTEMPT.remove();
        }
    }

    public static int reduceMiningDamageAfterUnbreaking(ItemStack stack, int damageAfterUnbreaking) {
        MiningDurabilityAttempt attempt = MINING_DURABILITY_ATTEMPT.get();
        if (attempt == null || attempt.stack != stack || damageAfterUnbreaking != stack.getDamageValue() + 1) {
            return damageAfterUnbreaking;
        }
        int reduction = durabilityReductionPerMille(activeType(attempt.player));
        return attempt.player.getRandom().nextInt(1000) < reduction ? stack.getDamageValue() : damageAfterUnbreaking;
    }

    public static void onPlayerTick(ServerPlayer player) {
        long now = player.serverLevel().getGameTime();
        OreFishType type = activeType(player);
        if (!player.level().dimension().equals(MiningConstants.MINING_LEVEL) || type == null) {
            removeOwnedNightVision(player, now);
            syncClientState(player, null);
            return;
        }
        syncClientState(player, type);
        if ((now + player.getId()) % 80 == 0) {
            refreshNightVision(player, now);
        }
    }

    private static int durationTicks(ItemStack stack) {
        int multiplier = ChefQualityNbt.readEffects(stack).stream()
                .filter(effect -> effect.type() == ChefEffectType.AMPLIFY)
                .mapToInt(ChefEffectInstance::magnitude)
                .max()
                .orElse(100);
        return Math.min(MAX_DURATION_TICKS, BASE_DURATION_TICKS * multiplier / 100);
    }

    private static boolean isSpoiled(ItemStack stack) {
        return ChefQualityNbt.readEffects(stack).stream().anyMatch(effect -> effect.type() == ChefEffectType.SPOILED);
    }

    private static boolean hasDurabilityReduction(OreFishType type) {
        return type == OreFishType.EMERALD || type == OreFishType.DARK_GOLD;
    }

    private static int durabilityReductionPerMille(OreFishType type) {
        return type == OreFishType.EMERALD ? 200 : type == OreFishType.DARK_GOLD ? 250 : 0;
    }

    private static int miningSpeedBonusPercent(OreFishType type) {
        return type == OreFishType.DIAMOND ? 15 : type == OreFishType.DARK_GOLD ? 20 : 0;
    }

    private static OreFishType typeFromSyncedState(int state) {
        int ordinal = state - 1;
        return ordinal >= 0 && ordinal < OreFishType.values().length ? OreFishType.values()[ordinal] : null;
    }

    private static void syncClientState(ServerPlayer player, OreFishType type) {
        ((OreSoupPlayerStateAccess) player).miningdim$setOreSoupState(type == null ? 0 : type.ordinal() + 1);
    }

    private static void refreshNightVision(ServerPlayer player, long now) {
        OreFishType type = activeType(player);
        if (type != OreFishType.GOLD && type != OreFishType.DARK_GOLD) {
            removeOwnedNightVision(player, now);
            return;
        }
        CompoundTag state = player.getPersistentData().getCompound(STATE_TAG);
        MobEffectInstance current = player.getEffect(MobEffects.NIGHT_VISION);
        long ownedUntil = state.getLong(NIGHT_VISION_UNTIL_TAG);
        int expected = (int) (ownedUntil - now);
        if (current == null) {
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, NIGHT_VISION_TICKS, 0, false, false, true));
            state.putLong(NIGHT_VISION_UNTIL_TAG, now + NIGHT_VISION_TICKS);
            return;
        }
        if (ownedUntil != 0L && Math.abs(current.getDuration() - expected) <= 3 && expected <= 320) {
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, NIGHT_VISION_TICKS, 0, false, false, true));
            state.putLong(NIGHT_VISION_UNTIL_TAG, now + NIGHT_VISION_TICKS);
        } else if (ownedUntil != 0L && Math.abs(current.getDuration() - expected) > 3) {
            state.remove(NIGHT_VISION_UNTIL_TAG);
        }
    }

    private static void clearExpiredState(ServerPlayer player) {
        long now = player.serverLevel().getGameTime();
        removeOwnedNightVision(player, now);
        player.getPersistentData().remove(STATE_TAG);
        syncClientState(player, null);
    }

    private static void removeOwnedNightVision(ServerPlayer player, long now) {
        if (!player.getPersistentData().contains(STATE_TAG, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag state = player.getPersistentData().getCompound(STATE_TAG);
        long ownedUntil = state.getLong(NIGHT_VISION_UNTIL_TAG);
        MobEffectInstance current = player.getEffect(MobEffects.NIGHT_VISION);
        if (current != null && ownedUntil != 0L && Math.abs(current.getDuration() - (ownedUntil - now)) <= 3) {
            player.removeEffect(MobEffects.NIGHT_VISION);
        }
        state.remove(NIGHT_VISION_UNTIL_TAG);
    }

    private record MiningDurabilityAttempt(ItemStack stack, ServerPlayer player) {
    }
}
