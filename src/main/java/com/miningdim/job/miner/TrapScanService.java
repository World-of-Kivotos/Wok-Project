package com.miningdim.job.miner;

import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningServices;
import com.miningdim.trap.StaticTrapPlacement;
import com.miningdim.trap.TrapSystem;
import com.miningdim.trap.TrapType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 陷阱探测服务端权威查询 (Miner_Job_DesignSpec 第三章)。经 {@link TrapSystem#get()}#staticPlacement(id) +
 * {@link StaticTrapPlacement#trapAt} 在半径 {@link MinerSkills#trapScanRadius} 球内收集静态陷阱坐标,
 * 按 {@link #includeLethal} 过滤致死类 (L5 仅非致死 / L8 含致死 TNT/岩浆袋)。
 *
 * 与矿物探测拆两个技能 (禁一次激活同给): 本类只查陷阱, {@link OreScanService} 只查矿。无实例 / 球内无陷阱
 * -> 返回空列表。结果个数受 {@link MinerConstants#TRAP_SCAN_MAX_RESULTS} 硬顶。
 */
public final class TrapScanService {

    private TrapScanService() {
    }

    /** L8 起含致死陷阱; L5-7 仅非致死。未解锁 (< L5) 返回 false (调用方据 scan 空列表短路)。 */
    public static boolean includeLethal(int level) {
        return level >= MinerConstants.TRAP_SCAN_LETHAL_LEVEL;
    }

    /**
     * 服务端权威扫描: 玩家所在实例静态陷阱表的探测球内, 收集 (按等级过滤致死类后的) 陷阱坐标。
     *
     * @return 球内确有陷阱的世界坐标列表 (个数 <= TRAP_SCAN_MAX_RESULTS)
     */
    public static List<BlockPos> scan(ServerPlayer player, int level) {
        List<BlockPos> empty = List.of();
        if (level < MinerConstants.TRAP_SCAN_UNLOCK_LEVEL) {
            return empty;
        }
        InstanceState instance = MiningServices.instanceManager()
                .regionAt(player.getBlockX(), player.getBlockZ());
        if (instance == null) {
            return empty;
        }
        StaticTrapPlacement placement = TrapSystem.get().staticPlacement(instance.instanceId());
        int radius = MinerSkills.trapScanRadius(level);
        if (radius <= 0) {
            return empty;
        }
        boolean lethalAllowed = includeLethal(level);
        BlockPos center = player.blockPosition();
        int r2 = radius * radius;

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
                    TrapType trap = placement.trapAt(wx, wy, wz);
                    if (trap == null) {
                        continue;
                    }
                    // L5-7 只下发非致死 (崩塌/假矿); L8+ 含致死 (TNT/岩浆袋)。
                    if (trap.lethal() && !lethalAllowed) {
                        continue;
                    }
                    hits.add(new BlockPos(wx, wy, wz));
                    if (hits.size() >= MinerConstants.TRAP_SCAN_MAX_RESULTS) {
                        return hits;
                    }
                }
            }
        }
        return hits;
    }
}
