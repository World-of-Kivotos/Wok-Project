package com.miningdim.job.miner;

import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.trap.TrapType;
import com.miningdim.trap.WorldHazards;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 陷阱探测服务端权威查询 (Miner_Job_DesignSpec 第三章)。经 {@link WorldHazards#hazardAt} 在半径
 * {@link MinerSkills#trapScanRadius} 球内直接读真实世界方块态, 按 {@link #includeLethal} 过滤致死类
 * (L5 仅非致死 / L8 含致死岩浆袋)。
 *
 * 改扫真实世界缘由 (F033): 旧实现读 TrapSystem 的离线静态陷阱布点表, 该表由离线布点计算生成但那条计算链
 * 全库零调用方 (唯一调用点自身也零调用方), 缓存恒空 -> scan 永远空返, 陷阱探测事实失效。
 *
 * 复核修正 (F033 三次独立复核坐实, 详见 {@link WorldHazards} 类注释): 矿洞 worldgen 数据包本身几乎不产生
 * 天然岩浆/砂砾, 单靠方块态扫描仍近乎恒空。scanWorld 现同时叠加 {@link WorldHazards#activeAt} 登记表 ——
 * {@link com.miningdim.trap.DynamicTrapEngine} 触发动态陷阱 (岩浆喷发/局部坍塌) 的预警窗口内会登记坐标, 使这两类真实存在但转瞬
 * 即逝的危害有机会被探测到; 世界最底部 (y 约 -59..-56) 天然存在的岩浆带同样能被方块态扫描命中。命中率仍受
 * 陷阱触发冷却与扫描 CD 双重稀释, 不是稳定探雷器, 见 WorldHazards 类注释的完整取舍记录。
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
     * 服务端权威扫描: 确认玩家在矿洞维度的实例 region 内后, 在玩家所在 ServerLevel 的探测球内直接读真实
     * 方块态, 经 {@link WorldHazards#hazardAt} 判危害。
     *
     * @return 球内确有危害的世界坐标列表 (个数 <= TRAP_SCAN_MAX_RESULTS)
     */
    public static List<BlockPos> scan(ServerPlayer player, int level) {
        List<BlockPos> empty = List.of();
        if (level < MinerConstants.TRAP_SCAN_UNLOCK_LEVEL) {
            return empty;
        }
        /*
         * 维度门必须排在 region 门之前: RegionBox.contains 只比 X/Z, EASY 区盒 X∈[0,256)、Z∈[0,256)
         * 覆盖了主世界出生点附近 —— 少了这一道, 玩家站在主世界出生点附近就能过 region 门, 而随后扫的是
         * player.serverLevel() 即他当前所在的维度, 于是陷阱探测变成任意维度的透视器。旧实现读的是 region
         * 内的静态表所以没暴露这个漏洞, 改读真实世界后必须补上 (判据与 OreScanService.scanDetailed 逐字一致)。
         */
        if (!player.level().dimension().equals(MiningConstants.MINING_LEVEL)) {
            return empty;
        }
        InstanceState instance = MiningServices.instanceManager()
                .regionAt(player.getBlockX(), player.getBlockZ());
        if (instance == null) {
            return empty;
        }
        int radius = MinerSkills.trapScanRadius(level);
        if (radius <= 0) {
            return empty;
        }
        return scanWorld(player.serverLevel(), player.blockPosition(), radius, includeLethal(level));
    }

    /**
     * 对探测球内真实世界方块逐格扫描收集危害坐标。与 {@link #scan} 解耦的纯世界读取核心: 不依赖实例/玩家
     * 等级门控, 仅需 level + 球心 + 半径 + 致死开关, 便于 GameTest 直接以真实 ServerLevel 断言。
     *
     * 仅收已加载区块内的命中 (未加载区块 level.isLoaded=false 跳过, 不强制加载、不臆造数据), 与
     * OreScanService.collectWithinSphere 同口径。
     *
     * @param level         要读取方块态的服务端世界
     * @param center        探测球心 (玩家位置)
     * @param radius        探测半径 (> 0)
     * @param lethalAllowed L8+ 为 true, 含致死类; L5-7 为 false, 仅非致死
     * @return 球内确有危害的世界坐标列表 (<= TRAP_SCAN_MAX_RESULTS)
     */
    public static List<BlockPos> scanWorld(ServerLevel level, BlockPos center, int radius, boolean lethalAllowed) {
        List<BlockPos> hits = new ArrayList<>();
        int r2 = radius * radius;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r2) {
                        continue;
                    }
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!level.isLoaded(cursor)) {
                        continue; // 未加载区块: 无可靠数据, 跳过 (不下发)。
                    }
                    TrapType hazard = WorldHazards.hazardAt(level.getBlockState(cursor));
                    if (hazard == null) {
                        // 方块态本身不是危害时, 叠加查动态陷阱活跃登记表 (F033 复核修正): 预警窗口内的岩浆喷发/
                        // 局部坍塌此时可能还没落地成方块态 (如坍塌列在 reactionWindow 内仍是完好承重石), 但
                        // DynamicTrapEngine 已在决策成立那一刻登记, 让探测能提前示警而非必须精确撞上落地瞬间。
                        hazard = WorldHazards.activeAt(cursor.immutable());
                    }
                    if (hazard == null) {
                        continue;
                    }
                    if (hazard.lethal() && !lethalAllowed) {
                        continue;
                    }
                    hits.add(cursor.immutable());
                    if (hits.size() >= MinerConstants.TRAP_SCAN_MAX_RESULTS) {
                        return hits;
                    }
                }
            }
        }
        return hits;
    }
}
