package com.miningdim.job.miner;

import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningServices;
import com.miningdim.trap.StaticTrapKind;
import com.miningdim.trap.block.TrapOreBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 陷阱探测服务端权威查询 (Miner_Job_DesignSpec 第三章)。在玩家所在实例的探测球 (半径 {@link MinerSkills#trapScanRadius})
 * 内逐格扫真实世界方块, 识别 {@link TrapOreBlock} 并读其 {@link TrapOreBlock#KIND} 还原静态陷阱种类, 按
 * {@link #includeLethal} 过滤致死类 (L5-7 仅非致死崩塌/假矿; L8+ 含致死 TNT/岩浆袋)。
 *
 * 改扫真实世界缘由 (方案 C, 同 {@link OreScanService}): 静态陷阱已从"离线体素表 (StaticTrapGenerator)"迁移为
 * vanilla-noise datapack {@code minecraft:ore} feature 布点的真实方块 (TrapOreBlock)。旧实现读
 * {@code TrapSystem.staticPlacement} 死表恒空 -> 探测恒返空 -> 陷阱探测技能事实失效 (审计 Critical)。改读真实世界后,
 * feature 生成的静态陷阱块均可被命中还原, 与矿脉抗性 (触发效果走 isTrapSource 环境伤) 一并闭合该 Critical。
 *
 * 与矿物探测拆两个技能 (禁一次激活同给): 本类只查陷阱, {@link OreScanService} 只查矿。无实例 (不在矿洞 region) /
 * 半径 0 / 球内无陷阱 -> 空列表。结果个数受 {@link MinerConstants#TRAP_SCAN_MAX_RESULTS} 硬顶。
 */
public final class TrapScanService {

    private TrapScanService() {
    }

    /** L8 起含致死陷阱; L5-7 仅非致死。未解锁 (< L5) 返回 false (调用方据 scan 空列表短路)。 */
    public static boolean includeLethal(int level) {
        return level >= MinerConstants.TRAP_SCAN_LETHAL_LEVEL;
    }

    /**
     * 服务端权威扫描: 确认玩家在矿洞 region 内后, 在探测球内收集 (按等级过滤致死类后的) 静态陷阱坐标。
     *
     * @return 球内确有静态陷阱块的世界坐标列表 (个数 <= TRAP_SCAN_MAX_RESULTS)
     */
    public static List<BlockPos> scan(ServerPlayer player, int level) {
        List<BlockPos> empty = List.of();
        if (level < MinerConstants.TRAP_SCAN_UNLOCK_LEVEL) {
            return empty;
        }
        InstanceState instance = MiningServices.instanceManager()
                .regionAt(player.getBlockX(), player.getBlockZ());
        if (instance == null) {
            return empty; // 不在矿洞 region 内, 不探。
        }
        int radius = MinerSkills.trapScanRadius(level);
        if (radius <= 0) {
            return empty;
        }
        return scanWorld(player.serverLevel(), player.blockPosition(), radius, includeLethal(level));
    }

    /**
     * 对探测球内真实世界方块逐格扫描, 收集 {@link TrapOreBlock} 坐标 (按 lethalAllowed 过滤致死种类)。
     * 与 {@link #scan} 解耦的纯世界读取核心: 不依赖实例/玩家等级门控, 仅需 level + 球心 + 半径 + 是否含致死,
     * 便于直接以真实 ServerLevel 测试 (陷阱探测复活的回归锚点)。仅收已加载区块内的命中 (未加载跳过, 不臆造)。
     *
     * @param level         要读取方块态的服务端世界
     * @param center        探测球心 (玩家位置)
     * @param radius        探测半径 (> 0)
     * @param lethalAllowed 是否下发致死类 (L8+ true; L5-7 false)
     * @return 球内静态陷阱块坐标 (<= TRAP_SCAN_MAX_RESULTS)
     */
    public static List<BlockPos> scanWorld(ServerLevel level, BlockPos center, int radius, boolean lethalAllowed) {
        if (radius <= 0) {
            return List.of();
        }
        int r2 = radius * radius;
        List<BlockPos> hits = new ArrayList<>();
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
                    BlockState state = level.getBlockState(cursor);
                    if (!(state.getBlock() instanceof TrapOreBlock)) {
                        continue;
                    }
                    StaticTrapKind kind = state.getValue(TrapOreBlock.KIND);
                    // L5-7 只下发非致死 (崩塌/假矿); L8+ 含致死 (TNT/岩浆袋)。
                    if (kind.trapType().lethal() && !lethalAllowed) {
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
