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

        PendingCast(UUID owner, ResourceKey<Level> originDimension, long resolveTick,
                    Consumer<ServerPlayer> resolution) {
            this.owner = owner;
            this.originDimension = originDimension;
            this.resolveTick = resolveTick;
            this.resolution = resolution;
        }
    }

    private final Map<UUID, PendingCast> pending = new HashMap<>();

    /** Starts one cast unless this player is already presenting another card. */
    public boolean begin(ServerPlayer player, int delayTicks, Consumer<ServerPlayer> resolution) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(resolution, "resolution");
        UUID owner = player.getUUID();
        if (pending.containsKey(owner)) {
            return false;
        }
        long now = player.getServer().getTickCount();
        pending.put(owner, new PendingCast(owner, player.level().dimension(),
                now + Math.max(0, delayTicks), resolution));
        return true;
    }

    public boolean isCasting(UUID owner) {
        return pending.containsKey(owner);
    }

    public int pendingCount() {
        return pending.size();
    }

    public void cancel(UUID owner) {
        pending.remove(owner);
    }

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
            if (player == null || player.isRemoved() || !player.isAlive()
                    || !player.level().dimension().equals(cast.originDimension)) {
                continue;
            }
            due.add(() -> cast.resolution.accept(player));
        }
        due.forEach(Runnable::run);
    }
}
