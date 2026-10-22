package com.miningdim.job.farmer.granary;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * 用例收尾: 把粮仓账本清成原样。
 *
 * <p>粮仓账本是**存档级、全服共享**的数据 ({@link GranarySavedData}: 主世界的 SavedData), 而 GameTest 的
 * 世界目录跨轮保留 —— 一个批次留下的粮仓会跟着存档活到下一批、甚至下一轮。后果不是"脏数据"这么温和:
 * 错峰自查是按 {@code floorMod(tick + 坐标, 6000)} 轮值的, 同余的两座里必有一座永远排不上, 于是
 * {@code theSelfCheckPicksTheGranaryWhoseHashMatchesTheTick} 会找不到"只轮到自己"的 tick;
 * 额度与归属的用例也会在"这个坐标怎么已经有人占了"上翻车。</p>
 *
 * <p>所以每个粮仓相关的测试类都在 {@code @AfterBatch} 里调这里一次 —— 各写一套清理逻辑迟早漏一项
 * (囤 / 囤状态 / 账台记录各有各的键), 清错了还会把别的批次的粮仓顺手删掉。这里只做"全清": 一个批次
 * 结束后账本里不该剩下任何粮仓。</p>
 */
public final class GranaryTestLedger {

    private GranaryTestLedger() {
    }

    /** 清空账本里所有粮仓 (连同它们名下的囤与状态)。只给用例用。 */
    public static void clear(ServerLevel level) {
        GranarySavedData data = GranarySavedData.get(level);
        for (GranaryRecord record : List.copyOf(data.granaries())) {
            for (GranarySavedData.PosKey key : List.copyOf(data.binsOf(record.id()))) {
                data.releaseBin(key.dimension(), BlockPos.of(key.packedPos()));
            }
            data.removeGranary(record.id());
        }
    }
}
