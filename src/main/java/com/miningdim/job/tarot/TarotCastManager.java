package com.miningdim.job.tarot;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Server-authoritative queue for the presentation phase that precedes each tarot effect. */
public final class TarotCastManager {

    private static final class PendingCast {
        final UUID owner;
        final ResourceKey<Level> originDimension;
        final long resolveTick;
        final Consumer<ServerPlayer> resolution;
        final Consumer<ServerPlayer> onDiscard;

        PendingCast(UUID owner, ResourceKey<Level> originDimension, long resolveTick,
                    Consumer<ServerPlayer> resolution, Consumer<ServerPlayer> onDiscard) {
            this.owner = owner;
            this.originDimension = originDimension;
            this.resolveTick = resolveTick;
            this.resolution = resolution;
            this.onDiscard = onDiscard;
        }
    }

    private final Map<UUID, PendingCast> pending = new HashMap<>();

    /**
     * Starts one cast unless this player is already presenting another card.
     *
     * @param onDiscard invoked (once) if the presentation never resolves — the card and its cooldown were
     *                  already committed by the caller before the presentation delay, so whichever guard
     *                  discards this cast (death/dimension-change/logout mid-presentation, F074) must be
     *                  able to hand the player back the card and undo the cooldown.
     */
    public boolean begin(ServerPlayer player, int delayTicks, Consumer<ServerPlayer> resolution,
                          Consumer<ServerPlayer> onDiscard) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(onDiscard, "onDiscard");
        UUID owner = player.getUUID();
        if (pending.containsKey(owner)) {
            return false;
        }
        long now = player.getServer().getTickCount();
        pending.put(owner, new PendingCast(owner, player.level().dimension(),
                now + Math.max(0, delayTicks), resolution, onDiscard));
        return true;
    }

    public boolean isCasting(UUID owner) {
        return pending.containsKey(owner);
    }

    public int pendingCount() {
        return pending.size();
    }

    /** Cancels this player's pending cast, if any, and refunds it exactly once via its {@code onDiscard}. */
    public void cancel(ServerPlayer player) {
        PendingCast cast = pending.remove(player.getUUID());
        if (cast != null) {
            cast.onDiscard.accept(player);
        }
    }

    /**
     * Drops every pending cast without refunding. Only reachable from ServerStopping, where there is no
     * player handle left to hand a refund to; by that point each online player's own logout event has
     * already run {@link #cancel(ServerPlayer)}, so in practice this clears an already-empty map.
     */
    public void clear() {
        pending.clear();
    }

    /** Resolves due casts outside map iteration so effects may safely schedule more work. */
    public void tick(MinecraftServer server) {
        if (pending.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        List<Runnable> due = new ArrayList<>();
        Iterator<PendingCast> iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            PendingCast cast = iterator.next();
            if (now < cast.resolveTick) {
                continue;
            }
            iterator.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(cast.owner);
            if (player == null) {
                // Already offline: no handle left to hand a refund to. The logout event fired cancel()
                // before this player left the player list, so it already refunded this cast there — this
                // is just an unreachable-in-practice backstop, not the primary refund path.
                continue;
            }
            if (player.isRemoved() || !player.isAlive()
                    || !player.level().dimension().equals(cast.originDimension)) {
                due.add(() -> cast.onDiscard.accept(player));
                continue;
            }
            due.add(() -> cast.resolution.accept(player));
        }
        due.forEach(Runnable::run);
    }
}
