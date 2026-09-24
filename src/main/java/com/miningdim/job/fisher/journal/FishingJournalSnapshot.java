package com.miningdim.job.fisher.journal;

import com.miningdim.job.fisher.size.FishRecord;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图鉴快照。收藏表示曾持有, 不表示亲手钓获, 也不产生职业经验; 亲手钓获的次数与最大个体单独放在 records 里,
 * 只来自成功钓获路径的体型结算。
 */
public record FishingJournalSnapshot(List<FishingJournalEntry> entries, Set<ResourceLocation> collected,
                                     Map<ResourceLocation, FishRecord> records) {
    public FishingJournalSnapshot {
        entries = List.copyOf(entries);
        collected = Set.copyOf(collected);
        records = Map.copyOf(records);
    }

    public FishingJournalSnapshot(List<FishingJournalEntry> entries, Set<ResourceLocation> collected) {
        this(entries, collected, Map.of());
    }
}
