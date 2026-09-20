package com.miningdim.job.fisher.journal;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Set;

/** 收藏表示曾持有，不表示亲手钓获，也不产生职业经验。 */
public record FishingJournalSnapshot(List<FishingJournalEntry> entries, Set<ResourceLocation> collected) {
    public FishingJournalSnapshot {
        entries = List.copyOf(entries);
        collected = Set.copyOf(collected);
    }
}
