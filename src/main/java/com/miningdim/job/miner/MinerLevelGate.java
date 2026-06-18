package com.miningdim.job.miner;

import com.miningdim.core.Difficulty;

/**
 * 难度门控裁决 (Miner_Job_DesignSpec 第八章): L4 开 Medium、L8 开 Hard、L1-3 仅 Easy。
 *
 * 取代 {@link com.miningdim.entry.EntryGateway#gateCheck} 现读 {@code player.experienceLevel} 的逻辑
 * (MEDIUM=10/HARD=25)。集成阶段 EntryGateway 改为委派本类读矿工等级 (见 foundationGaps: 该 import 与改写
 * 属 entry 子系统, 不在本任务可写范围)。纯函数, 无副作用, 便于断言。
 */
public final class MinerLevelGate {

    private MinerLevelGate() {
    }

    /** 进入指定难度所需的最低矿工等级 (Easy=1, Medium=4, Hard=8)。 */
    public static int minLevelFor(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> MinerConstants.MIN_LEVEL;
            case MEDIUM -> MinerConstants.MEDIUM_MIN_MINER_LEVEL;
            case HARD -> MinerConstants.HARD_MIN_MINER_LEVEL;
        };
    }

    /** 给定矿工等级能否进入指定难度 (等级 >= 门槛即可)。 */
    public static boolean canEnter(int minerLevel, Difficulty difficulty) {
        return minerLevel >= minLevelFor(difficulty);
    }
}
