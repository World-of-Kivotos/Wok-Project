package com.miningdim.job.miner;

import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningServices;
import com.miningdim.trap.TrapRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 陷阱探测服务端权威查询 (Miner_Job_DesignSpec 第三章)。在玩家所在实例的探测球 (半径 {@link MinerSkills#trapScanRadius})
 * 内查 {@link TrapRegistry} (协议级伪装后陷阱身份只存注册表, 世界里已是无法区分的真矿石), 按
 * {@link #includeLethal} 过滤致死类 (L5-7 仅非致死崩塌/假矿; L8+ 含致死 TNT/岩浆袋)。
 *
 * 改查注册表缘由 (协议级伪装): 静态陷阱不再是可扫的 TrapOreBlock —— 区块加载即被换成真原版矿石, 陷阱身份迁入
 * {@link TrapRegistry}。故探测改扫注册表 (registry.nearby 按 chunk 收候选, 本类做精确球面 + 致死 + 上限过滤),
 * 而非逐格读世界方块。
 *
 * 揭示态 (连锁交互用): 实际下发给玩家的命中位标记为该玩家"已揭示" ({@link TrapRegistry#markRevealed}); 被致死过滤
 * 隐藏的命中不算揭示。连锁挖矿据此对已揭示陷阱跳过 (方块保留) 而非触发。
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
     * 服务端权威扫描: 确认玩家在矿洞 region 内后, 在探测球内收集 (按等级过滤致死类后的) 静态陷阱坐标, 并把实际
     * 下发的命中位标记为该玩家已揭示 (连锁交互据此跳过已知陷阱)。
     *
     * @return 球内确有静态陷阱的世界坐标列表 (个数 <= TRAP_SCAN_MAX_RESULTS)
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
        return scanWorld(player.serverLevel(), player.blockPosition(), radius, includeLethal(level), player.getUUID());
    }

    /**
     * 纯查询核心 (无揭示副作用): 从注册表收探测球内 (按 lethalAllowed 过滤致死类) 的静态陷阱坐标。不依赖实例/玩家等级门控,
     * 便于直接以任意 ServerLevel + 注册表内容测试。
     *
     * @param level         承载 {@link TrapRegistry} 的服务端世界
     * @param center        探测球心 (玩家位置)
     * @param radius        探测半径 (> 0)
     * @param lethalAllowed 是否下发致死类 (L8+ true; L5-7 false)
     * @return 球内静态陷阱坐标 (<= TRAP_SCAN_MAX_RESULTS)
     */
    public static List<BlockPos> scanWorld(ServerLevel level, BlockPos center, int radius, boolean lethalAllowed) {
        return scanWorld(level, center, radius, lethalAllowed, null);
    }

    /**
     * 查询核心 + 可选揭示: 与四参版同, 但 revealTo 非空时把实际下发的每个命中位标记为该玩家已揭示 (被致死过滤/
     * 超上限而未下发的不揭示)。四参版以 revealTo=null 复用本方法 (纯查询)。
     *
     * @param revealTo 揭示归属玩家 UUID; null 表示纯查询不揭示
     */
    public static List<BlockPos> scanWorld(ServerLevel level, BlockPos center, int radius, boolean lethalAllowed,
                                           UUID revealTo) {
        if (radius <= 0) {
            return List.of();
        }
        TrapRegistry registry = TrapRegistry.get(level);
        int r2 = radius * radius;
        List<BlockPos> hits = new ArrayList<>();
        for (TrapRegistry.Entry entry : registry.nearby(center, radius)) {
            BlockPos pos = entry.pos();
            long dx = pos.getX() - center.getX();
            long dy = pos.getY() - center.getY();
            long dz = pos.getZ() - center.getZ();
            if (dx * dx + dy * dy + dz * dz > r2) {
                continue; // chunk 粗筛后的精确球面过滤 (立方 chunk 范围内的球外角落剔除)。
            }
            // L5-7 只下发非致死 (崩塌/假矿); L8+ 含致死 (TNT/岩浆袋)。
            if (entry.kind().trapType().lethal() && !lethalAllowed) {
                continue;
            }
            hits.add(pos);
            if (revealTo != null) {
                registry.markRevealed(revealTo, pos); // 只揭示实际下发的命中 (致死过滤隐藏的不算)。
            }
            if (hits.size() >= MinerConstants.TRAP_SCAN_MAX_RESULTS) {
                return hits;
            }
        }
        return hits;
    }
}
