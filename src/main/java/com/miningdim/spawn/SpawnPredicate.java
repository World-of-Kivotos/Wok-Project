package com.miningdim.spawn;

import com.miningdim.core.VoxelOccupancy;

/**
 * 安全站立/出生点谓词 (设计文档 11.2)。本类是"什么是合法落点"的唯一权威 (谓词权威在此),
 * 出生系统预生成 spawn pool 与兜底平台校验复用本谓词, 杜绝多处各写一份导致漂移。
 * (运行期刷怪选点无体素视图, 读真实世界方块, 见 MobPressureSystem.resolveStandableColumn,
 * 与本谓词同语义: 头顶净空 + 脚下固体。)
 *
 * 谓词全部基于离线体素视图 (VoxelOccupancy, air=true), 纯计算, 确定性 (D3)。判定的是体素本地坐标。
 * "无陷阱区"一项依赖陷阱表, 陷阱子系统尚无 core 门面暴露陷阱表, 故由调用方传入 isTrapZone 谓词
 * (无陷阱信息时传恒 false), 不在本类硬编码对陷阱子系统的依赖。
 *
 * 站立点语义 (11.2):
 *   p (脚部体素) 与 p.above 均为空气 (>= headroom 格净空);
 *   p.below 为实心 (可站立);
 *   p 的 3x3x3 邻域无岩浆 —— 体素视图只区分空气/实心, 岩浆区分需读真实方块, 故体素阶段的
 *     "无岩浆"退化为"脚下及邻域为实心而非空腔边缘的合理近似", 真实岩浆复核在 isSafe(ServerLevel)
 *     阶段用 lavaAvoidRadius 读 FluidState 完成 (见 SpawnSystem.isSafe)。
 */
public final class SpawnPredicate {

    private SpawnPredicate() {
    }

    /**
     * 体素本地坐标 (lx, ly, lz) 是否为合法站立点 (11.2 头顶净空 + 脚下固体 + 属体素盒)。
     *
     * @param voxels    体素视图 (air=true)
     * @param lx        本地 X
     * @param ly        本地 Y (脚部所在层)
     * @param lz        本地 Z
     * @param headroom  头顶需空气格数 (config.spawnHeadroomBlocks, 含脚部所在层即 >= 2 时为脚 + 头)
     * @param requireSolidFloor 是否要求脚下固体 (config.spawnRequireSolidFloor)
     * @return 合法站立点则 true
     */
    public static boolean isStandable(VoxelOccupancy voxels, int lx, int ly, int lz,
                                      int headroom, boolean requireSolidFloor) {
        int w = voxels.width();
        int h = voxels.height();
        int d = voxels.depth();

        // 脚部及头顶 headroom 格须全为空气; 头顶不得越出体素盒顶。
        if (lx < 0 || lx >= w || lz < 0 || lz >= d) {
            return false;
        }
        if (ly < 0 || ly + headroom > h) {
            return false;
        }
        for (int dy = 0; dy < headroom; dy++) {
            if (!voxels.isAir(lx, ly + dy, lz)) {
                return false;
            }
        }

        // 脚下须实心 (非空气) 才能站立; 体素盒最底层无下方体素, 视为不可站立 (避免站在 region 底面边界)。
        if (requireSolidFloor) {
            int below = ly - 1;
            if (below < 0) {
                return false;
            }
            if (voxels.isAir(lx, below, lz)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 完整出生点谓词 (11.2 全部条件): 站立点 + 属主连通分量 + 非陷阱区。
     * 矿物可达性 (11.6 到首矿区安全通路) 由 SpawnSystem 在 pool 预生成阶段批量 BFS 校验, 不在本逐点谓词内,
     * 避免每点重复 BFS。
     *
     * @param voxels            体素视图
     * @param lx,ly,lz          体素本地坐标 (脚部)
     * @param headroom          头顶净空格数
     * @param requireSolidFloor 脚下须固体
     * @param mainComponent     主连通分量掩码 (按 voxels.index 下标; null 表示不约束分量)
     * @param trapZone          陷阱区谓词 (本地坐标 -> 是否致死陷阱区; null 表示无陷阱信息)
     * @param avoidTrapZones    是否避开陷阱区 (config.spawnAvoidTrapZones)
     */
    public static boolean isSafeSpawn(VoxelOccupancy voxels, int lx, int ly, int lz,
                                      int headroom, boolean requireSolidFloor,
                                      boolean[] mainComponent, TrapZonePredicate trapZone,
                                      boolean avoidTrapZones) {
        if (!isStandable(voxels, lx, ly, lz, headroom, requireSolidFloor)) {
            return false;
        }
        if (mainComponent != null) {
            int idx = voxels.index(lx, ly, lz);
            if (idx < 0 || idx >= mainComponent.length || !mainComponent[idx]) {
                return false;
            }
        }
        if (avoidTrapZones && trapZone != null && trapZone.isTrapZone(lx, ly, lz)) {
            return false;
        }
        return true;
    }

    /** 陷阱区谓词函数式接口 (本地体素坐标 -> 是否致死陷阱区)。由陷阱子系统未来经 core 门面提供时注入。 */
    @FunctionalInterface
    public interface TrapZonePredicate {
        boolean isTrapZone(int localX, int localY, int localZ);
    }
}
