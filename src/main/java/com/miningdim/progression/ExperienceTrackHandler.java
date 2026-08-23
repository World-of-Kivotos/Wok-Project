package com.miningdim.progression;

import net.minecraft.world.entity.player.Player;

/**
 * Persistence adapter owned by a progression domain such as jobs, account level or a season.
 * The WOK experience module routes grants; the owning domain retains its compatible saved data.
 */
public interface ExperienceTrackHandler {
    ExperienceSnapshot snapshot(Player player);

    long award(Player player, long rawXp);
}
