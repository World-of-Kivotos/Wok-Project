package com.kivotos.armorer.shield;

import com.kivotos.armorer.ArmorerMod;
import com.kivotos.armorer.ArmorerConfig;
import com.kivotos.armorer.ArmorerSounds;
import com.kivotos.armorer.shield.item.PlasmaShieldItem;
import com.kivotos.armorer.shield.network.PlasmaShieldNetwork;
import com.kivotos.armorer.shield.network.PlasmaShieldHitS2C;
import com.kivotos.armorer.shield.network.PlasmaShieldSyncS2C;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-authoritative damage absorption, thermal state, recharge, movement and HUD synchronization. */
public final class PlasmaShieldHandler {

    public static final UUID MOVEMENT_ID = UUID.fromString("728f81b6-a1f5-4f89-bf77-b61f03af8bbc");
    public static final TagKey<DamageType> BYPASSES_PLASMA_SHIELD = TagKey.create(
            Registries.DAMAGE_TYPE,
            new ResourceLocation(ArmorerMod.MODID, "bypasses_plasma_shield"));

    private static final int SYNC_HEARTBEAT_TICKS = 40;
    private static final double HEAT_EPSILON = 1.0E-7D;

    private final Map<UUID, SentSnapshot> sentSnapshots = new HashMap<>();
    private final Map<UUID, SettlementAnchor> settlementAnchors = new HashMap<>();
    private final PlasmaShieldSoundCadence soundCadence = new PlasmaShieldSoundCadence();

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public void onLivingHurt(LivingHurtEvent event) {
        // isFinite 必须显式判: NaN 与所有值比较均为 false, 单靠 amount <= 0 拦不住。放行后护盾会把
        // NaN 当成一次成功吸收并最终 setAmount(0), 使佩戴者完全免疫异常伤害, 还掩盖了上游的数据错误。
        // 非有限伤害一律不经护盾, 原样交回原版链路暴露问题。
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !Float.isFinite(event.getAmount())
                || event.getAmount() <= 0.0F
                || event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                || event.getSource().is(BYPASSES_PLASMA_SHIELD)) {
            return;
        }

        ItemStack stack = player.getItemBySlot(EquipmentSlot.CHEST);
        if (!(stack.getItem() instanceof PlasmaShieldItem item)) {
            return;
        }
        PlasmaShieldConfig.Stats stats = ArmorerConfig.PLASMA_SHIELD.stats(item.shieldVariant());
        Settlement settlement = settleToNow(player, stack, stats, false);
        maybePlaySteamVent(player, stack, item.shieldVariant(), settlement);
        PlasmaShieldState before = settlement.state();
        PlasmaShieldState.HitResult result = PlasmaShieldState.absorb(before, stats, event.getAmount());
        PlasmaShieldState.write(stack, result.state());
        event.setAmount((float) result.remainingDamage());

        boolean justOverheated = !before.overheated() && result.state().overheated();
        if (result.absorbedDamage() > 0.0D) {
            boolean cadenceAllowsHit = soundCadence.shouldEmitHit(
                    player.getUUID(), stack, player.level().getGameTime());
            if (cadenceAllowsHit || justOverheated) {
                PlasmaShieldHitS2C feedback = PlasmaShieldHitS2C.forHit(
                        player.getId(), item.shieldVariant(), result.absorbedDamage(), justOverheated);
                PlasmaShieldNetwork.sendHit(player, feedback);
                if (!justOverheated) {
                    playHitSound(player, item.shieldVariant(), feedback.strength());
                }
            }
        }

        if (justOverheated) {
            soundCadence.onOverheated(player.getUUID(), stack, player.level().getGameTime());
            playOverheatSound(player, item.shieldVariant());
        }

        // Heat-state transitions must be visible immediately; ordinary high-rate hits are coalesced on the 5-tick loop.
        if (before.overheated() != result.state().overheated()) {
            sendSnapshot(player, true);
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide) {
            return;
        }
        synchronizeMovement(event.player);
        if (!(event.player instanceof ServerPlayer player)) {
            return;
        }

        ItemStack stack = player.getItemBySlot(EquipmentSlot.CHEST);
        if (stack.getItem() instanceof PlasmaShieldItem item) {
            PlasmaShieldConfig.Stats stats = ArmorerConfig.PLASMA_SHIELD.stats(item.shieldVariant());
            Settlement settlement = settleToNow(player, stack, stats, true);
            if (settlement.advanced()) {
                maybePlaySteamVent(player, stack, item.shieldVariant(), settlement);
                sendSnapshot(player, false);
            }
            return;
        }
        settlementAnchors.remove(player.getUUID());
        soundCadence.clear(player.getUUID());
    }

    @SubscribeEvent
    public void onEquipmentChanged(LivingEquipmentChangeEvent event) {
        if (event.getSlot() != EquipmentSlot.CHEST
                || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack current = player.getItemBySlot(EquipmentSlot.CHEST);
        SettlementAnchor anchor = settlementAnchors.get(player.getUUID());
        // Forge compares equipment NBT against a copy from the previous tick. Shield-state writes therefore fire
        // this event too; only a different live stack reference represents a real chest-equipment replacement.
        if (anchor != null && anchor.stack() == current) {
            return;
        }
        soundCadence.clear(player.getUUID());
        synchronizeMovement(player);
        resetSettlementAnchor(player);
        sendSnapshot(player, true);
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            soundCadence.clear(player.getUUID());
            synchronizeMovement(player);
            resetSettlementAnchor(player);
            sendSnapshot(player, true);
        }
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            soundCadence.clear(player.getUUID());
            resetSettlementAnchor(player);
            sendSnapshot(player, true);
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            soundCadence.clear(player.getUUID());
            synchronizeMovement(player);
            resetSettlementAnchor(player);
            sendSnapshot(player, true);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        sentSnapshots.remove(event.getEntity().getUUID());
        settlementAnchors.remove(event.getEntity().getUUID());
        soundCadence.clear(event.getEntity().getUUID());
        removeMovement(event.getEntity());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        sentSnapshots.clear();
        settlementAnchors.clear();
        soundCadence.clearAll();
    }

    /**
     * Advances only time that really elapsed since this exact chest stack was last observed. Damage calls this
     * before absorption, so a hit on a periodic-settlement tick cannot spend that same interval a second time.
     */
    private Settlement settleToNow(ServerPlayer player,
                                   ItemStack stack,
                                   PlasmaShieldConfig.Stats stats,
                                   boolean requireConfiguredInterval) {
        PlasmaShieldState before = PlasmaShieldState.initialize(stack, stats);
        UUID playerId = player.getUUID();
        long now = player.level().getGameTime();
        SettlementAnchor previous = settlementAnchors.get(playerId);
        if (previous == null || previous.stack() != stack || now < previous.gameTime()) {
            soundCadence.clear(playerId);
            settlementAnchors.put(playerId, new SettlementAnchor(stack, now));
            return new Settlement(before, false, false, false);
        }

        long elapsed = now - previous.gameTime();
        if (elapsed <= 0L
                || (requireConfiguredInterval
                && elapsed < ArmorerConfig.PLASMA_SHIELD.stateTickInterval())) {
            return new Settlement(before, false, false, false);
        }

        int elapsedTicks = (int) Math.min(elapsed, Integer.MAX_VALUE);
        PlasmaShieldState after = PlasmaShieldState.tick(before, stats, elapsedTicks);
        if (!after.equals(before)) {
            PlasmaShieldState.write(stack, after);
        }
        settlementAnchors.put(playerId, new SettlementAnchor(stack, now));
        return new Settlement(
                after,
                true,
                after.heat() < before.heat() - HEAT_EPSILON,
                before.overheated());
    }

    private void maybePlaySteamVent(ServerPlayer player,
                                    ItemStack stack,
                                    PlasmaShieldVariant variant,
                                    Settlement settlement) {
        if (settlement.cooled()
                && settlement.emergencyCooling()
                && soundCadence.shouldPlayVent(player.getUUID(), stack, player.level().getGameTime())) {
            playSteamVentSound(player, variant);
        }
    }

    private static void playHitSound(ServerPlayer player, PlasmaShieldVariant variant, float strength) {
        player.level().playSound(
                null,
                player.getX(), player.getY() + 1.0D, player.getZ(),
                ArmorerSounds.PLASMA_SHIELD_HIT.get(),
                SoundSource.PLAYERS,
                0.70F + 0.30F * strength,
                chassisPitch(variant) * (0.97F + 0.05F * strength));
    }

    private static void playOverheatSound(ServerPlayer player, PlasmaShieldVariant variant) {
        player.level().playSound(
                null,
                player.getX(), player.getY() + 1.0D, player.getZ(),
                ArmorerSounds.PLASMA_SHIELD_OVERHEAT.get(),
                SoundSource.PLAYERS,
                1.10F,
                chassisPitch(variant));
    }

    private static void playSteamVentSound(ServerPlayer player, PlasmaShieldVariant variant) {
        player.level().playSound(
                null,
                player.getX(), player.getY() + 1.0D, player.getZ(),
                ArmorerSounds.PLASMA_SHIELD_STEAM_VENT.get(),
                SoundSource.PLAYERS,
                0.90F,
                chassisPitch(variant));
    }

    private static float chassisPitch(PlasmaShieldVariant variant) {
        return switch (variant.series()) {
            case NANO -> 1.06F;
            case STANDARD -> 1.0F;
            case QUANTUM -> 0.94F;
        };
    }

    private void resetSettlementAnchor(ServerPlayer player) {
        ItemStack stack = player.getItemBySlot(EquipmentSlot.CHEST);
        if (stack.getItem() instanceof PlasmaShieldItem) {
            settlementAnchors.put(player.getUUID(), new SettlementAnchor(stack, player.level().getGameTime()));
        } else {
            settlementAnchors.remove(player.getUUID());
        }
    }

    /** Idempotently applies the configured quantum-series movement penalty and removes stale variants. */
    public static void synchronizeMovement(Player player) {
        PlasmaShieldItem item = PlasmaShieldItem.equippedBy(player);
        double amount = item == null
                ? 0.0D
                : ArmorerConfig.PLASMA_SHIELD.stats(item.shieldVariant()).movementModifier();
        AttributeInstance movement = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (movement == null) {
            return;
        }
        AttributeModifier current = movement.getModifier(MOVEMENT_ID);
        if (amount == 0.0D) {
            if (current != null) {
                movement.removeModifier(MOVEMENT_ID);
            }
            return;
        }
        if (current != null
                && current.getAmount() == amount
                && current.getOperation() == AttributeModifier.Operation.MULTIPLY_TOTAL) {
            return;
        }
        if (current != null) {
            movement.removeModifier(MOVEMENT_ID);
        }
        movement.addTransientModifier(new AttributeModifier(
                MOVEMENT_ID,
                "plasma shield mobility",
                amount,
                AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    private static void removeMovement(Player player) {
        AttributeInstance movement = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (movement != null && movement.getModifier(MOVEMENT_ID) != null) {
            movement.removeModifier(MOVEMENT_ID);
        }
    }

    private void sendSnapshot(ServerPlayer player, boolean force) {
        PlasmaShieldSyncS2C message = createSnapshot(player);
        long gameTime = player.level().getGameTime();
        SentSnapshot previous = sentSnapshots.get(player.getUUID());
        if (!force && previous != null
                && previous.message().equals(message)
                && (!message.active() || gameTime - previous.gameTime() < SYNC_HEARTBEAT_TICKS)) {
            return;
        }
        if (PlasmaShieldNetwork.send(player, message)) {
            sentSnapshots.put(player.getUUID(), new SentSnapshot(message, gameTime));
        }
    }

    public static PlasmaShieldSyncS2C createSnapshot(Player player) {
        ItemStack stack = player.getItemBySlot(EquipmentSlot.CHEST);
        if (!(stack.getItem() instanceof PlasmaShieldItem item)) {
            return PlasmaShieldSyncS2C.inactive();
        }
        PlasmaShieldConfig.Stats stats = ArmorerConfig.PLASMA_SHIELD.stats(item.shieldVariant());
        PlasmaShieldState state = PlasmaShieldState.read(stack, stats);
        return new PlasmaShieldSyncS2C(
                true,
                item.shieldVariant().id(),
                (float) state.shield(),
                (float) stats.capacity(),
                (float) state.totalEnergy(),
                (float) stats.maxTotalEnergy(),
                (float) state.heat(),
                (float) stats.maxHeat(),
                state.overheated(),
                state.rechargeDelayTicks()).sanitized();
    }

    private record SentSnapshot(PlasmaShieldSyncS2C message, long gameTime) {
    }

    private record SettlementAnchor(ItemStack stack, long gameTime) {
    }

    private record Settlement(PlasmaShieldState state,
                              boolean advanced,
                              boolean cooled,
                              boolean emergencyCooling) {
    }
}

