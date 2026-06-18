package com.miningdim.job.miner;

import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningServices;
import com.miningdim.ore.OrePlacement;
import com.miningdim.ore.OreSystem;
import com.miningdim.ore.OreType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 矿物探测服务端权威查询 (Miner_Job_DesignSpec 第三章)。经 {@link MiningServices#instanceManager()} 取玩家
 * 所在实例, 经 {@link OreSystem#cachedPlacement(long)} + {@link OrePlacement#oreAt} 扫探测球内单矿种,
 * 只收集球内确有该矿的坐标 (防 X 光: 服务端只下发确有坐标, 不下发全图)。
 *
 * 单次探测固定扫一个 "可探矿种集合" 里 player 当前指定/默认的目标 (本实现按里程碑可探集合的优先序自动选一个有结果
 * 的矿种, 满足 "单矿种一次"); 矿/陷阱拆两个技能, 本类只管矿。结果坐标个数受 {@link MinerConstants#ORE_SCAN_MAX_RESULTS}
 * 硬顶。脉冲熄灭由客户端按 expireTick 处理。
 */
public final class OreScanService {

    private OreScanService() {
    }

    /**
     * 按矿工等级开放的可探矿种 (里程碑): L3 铁/煤; L6 +钻; L8 +金/残骸。
     * 未达探矿解锁级 (L3) 返回空集 (调用方据此短路)。
     */
    public static Set<OreType> allowedOres(int level) {
        EnumSet<OreType> set = EnumSet.noneOf(OreType.class);
        if (level < MinerConstants.ORE_SCAN_UNLOCK_LEVEL) {
            return set;
        }
        set.add(OreType.IRON);
        set.add(OreType.COAL);
        if (level >= MinerConstants.ORE_SCAN_DIAMOND_LEVEL) {
            set.add(OreType.DIAMOND);
        }
        if (level >= MinerConstants.ORE_SCAN_GOLD_DEBRIS_LEVEL) {
            set.add(OreType.GOLD);
            set.add(OreType.ANCIENT_DEBRIS);
        }
        return set;
    }

    /**
     * 服务端权威扫描: 在玩家所在实例的铺矿表中, 半径 {@link MinerSkills#oreScanRadius} 球内收集第一个有命中的
     * 可探矿种的全部坐标 (单矿种一次)。无实例 / 未铺表 / 球内无任何可探矿 -> 返回空列表 (不下发全图)。
     *
     * @return 球内确有目标矿的世界坐标列表 (个数 <= ORE_SCAN_MAX_RESULTS)
     */
    public static List<BlockPos> scan(ServerPlayer player, int level) {
        List<BlockPos> empty = List.of();
        Set<OreType> allowed = allowedOres(level);
        if (allowed.isEmpty()) {
            return empty;
        }
        InstanceState instance = MiningServices.instanceManager()
                .regionAt(player.getBlockX(), player.getBlockZ());
        if (instance == null) {
            return empty;
        }
        OrePlacement placement = OreSystem.get().cachedPlacement(instance.instanceId());
        if (placement == null) {
            return empty; // 铺矿表未预热 (生成尚未完成): 无可靠数据, 不下发。
        }

        int radius = MinerSkills.oreScanRadius(level);
        if (radius <= 0) {
            return empty;
        }
        BlockPos center = player.blockPosition();
        int r2 = radius * radius;

        // 单矿种语义: 按可探集合优先序 (铁>煤>钻>金>残骸) 逐个试, 取第一个球内确有命中的矿种, 收其全部坐标。
        for (OreType target : preferenceOrder()) {
            if (!allowed.contains(target)) {
                continue;
            }
            List<BlockPos> hits = collectWithinSphere(placement, center, radius, r2, target);
            if (!hits.isEmpty()) {
                return hits;
            }
        }
        return empty;
    }

    /** 球内逐体素查表收集某矿种命中坐标 (服务端权威, 只收确有命中的)。 */
    private static List<BlockPos> collectWithinSphere(OrePlacement placement, BlockPos center,
                                                      int radius, int r2, OreType target) {
        List<BlockPos> hits = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r2) {
                        continue;
                    }
                    int wx = center.getX() + dx;
                    int wy = center.getY() + dy;
                    int wz = center.getZ() + dz;
                    OreType ore = placement.oreAt(wx, wy, wz);
                    if (ore == target) {
                        hits.add(new BlockPos(wx, wy, wz));
                        if (hits.size() >= MinerConstants.ORE_SCAN_MAX_RESULTS) {
                            return hits;
                        }
                    }
                }
            }
        }
        return hits;
    }

    /** 单矿种探测的优先序 (常见矿优先, 高价矿其次)。 */
    private static OreType[] preferenceOrder() {
        return new OreType[]{OreType.IRON, OreType.COAL, OreType.DIAMOND, OreType.GOLD, OreType.ANCIENT_DEBRIS};
    }
}
