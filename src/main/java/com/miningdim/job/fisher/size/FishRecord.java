package com.miningdim.job.fisher.size;

/** 某玩家对某鱼种的钓获记录: 亲手钓获次数与迄今体长最大的那一条 (及其体重)。 */
public record FishRecord(int count, int bestLengthMm, long bestWeightMg) {
    public FishRecord {
        if (count <= 0 || bestLengthMm <= 0 || bestWeightMg <= 0L) {
            throw new IllegalArgumentException("Invalid fish record: " + count + ", " + bestLengthMm + ", " + bestWeightMg);
        }
    }
}
