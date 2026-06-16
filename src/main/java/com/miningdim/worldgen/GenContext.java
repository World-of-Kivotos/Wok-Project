package com.miningdim.worldgen;

import com.miningdim.core.Difficulty;
import com.miningdim.core.RegionBox;
import com.miningdim.core.SeedUtil;

/**
 * 离线三阶段管线 (Skeleton -> NoiseCarving -> ConnectivityFix) 共享的不可变上下文 (设计文档 7.3.3)。
 * 全程使用 region 本地坐标系 ([0,size))。各阶段从这里取派生 seed, 保证 D3 确定性:
 * 同一 instanceSeed 下各阶段拿到的 stageSeed 恒定, 随机推进顺序可复现 (7.6)。
 *
 * spawnAnchor 是 ConnectivityFix 标记主连通分量的 BFS 起点 (本地坐标扁平下标)。本阶段实现里
 * 该锚点由骨架阶段在 Easy 区主干上选定 (6.3: 出生点默认置于 Easy 区), 而非 SpawnSystem ——
 * worldgen 子系统不依赖 spawn 子系统实现 (模块化铁律 2); 第九/十一章 SpawnService 在体素就绪后
 * 另行在主分量内精选玩家落点, 与此处的连通锚点是两件事 (连通锚点只为 BFS 提供主分量种子)。
 */
public final class GenContext {

    /** stageId 常量: 派生各阶段 seed 用 (7.6.1 stageSeed = hash(instanceSeed, stageId))。 */
    public static final int STAGE_SKELETON = 1;
    public static final int STAGE_CARVE = 2;
    public static final int STAGE_CONNECTIVITY = 3;

    private final long instanceSeed;
    private final Difficulty difficulty;
    private final RegionBox box;

    public GenContext(long instanceSeed, Difficulty difficulty, RegionBox box) {
        this.instanceSeed = instanceSeed;
        this.difficulty = difficulty;
        this.box = box;
    }

    public long instanceSeed() {
        return instanceSeed;
    }

    public Difficulty difficulty() {
        return difficulty;
    }

    public RegionBox box() {
        return box;
    }

    /**
     * 派生某阶段的确定性子种子 (7.6.1)。stageId 取本类 STAGE_* 常量。
     * 复用 SeedUtil.hash 的 SplitMix64 finalizer, 把 stageId 编码进 featureId 维度,
     * x/z 维度取 0 (阶段级 seed 与坐标无关)。
     */
    public long stageSeed(int stageId) {
        return SeedUtil.hash(instanceSeed, 0, 0, stageId);
    }
}
