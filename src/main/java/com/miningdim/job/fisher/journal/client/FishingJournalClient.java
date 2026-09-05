package com.miningdim.job.fisher.journal.client;

import com.miningdim.job.fisher.journal.FishingJournalSnapshot;
import net.minecraft.client.Minecraft;

/** Client-only handoff for the server-authoritative fishing journal snapshot. */
public final class FishingJournalClient {

    private FishingJournalClient() {
    }

    /**
     * Updates an open journal in place. A non-opening sync deliberately has no retained client cache, so it cannot
     * surface stale data after a world change.
     */
    public static void accept(FishingJournalSnapshot snapshot, boolean open) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof FishingJournalScreen screen) {
            screen.updateSnapshot(snapshot);
            return;
        }
        if (open) {
            minecraft.setScreen(new FishingJournalScreen(snapshot));
        }
    }
}
