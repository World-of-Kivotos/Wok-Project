package com.miningdim.job.miner;

import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.ore.OreType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 矿物探测服务端权威查询 (Miner_Job_DesignSpec 第三章)。经 {@link MiningServices#instanceManager()} 取玩家
 * 所在实例 (确认在矿洞 region 内), 再在 {@link ServerPlayer#serverLevel()} 上对探测球内逐 BlockPos 读真实
 * 方块态, 经 {@link OreType#fromBlock} 把命中的矿石方块还原为矿种, 只下发球内确有目标矿的坐标 (防 X 光)。
 *
 * 改扫真实世界缘由 (经济文档第十章 H): 旧实现读 {@link com.miningdim.ore.OreSystem#cachedPlacement} 死体素表,
 * 该缓存仅在离线铺矿 placementFor 被调用时填充, 而维度已改用 minecraft:noise 生成 + 原版 ore feature, 无任何
 * 生产调用方填表 -> cachedPlacement 恒空 -> scan 永远空返, 探矿技能事实失效。改读真实世界后, L3/L6/L8 探矿
 * 里程碑恢复 (原版 ore feature 生成的铁/煤/钻/金/残骸/绿宝石矿块均可被还原命中)。
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
     * 一次探测的完整结果: 命中的矿种 + 该矿种在球内的全部坐标。
     *
     * {@code ore} 为 null 且 {@code positions} 为空表示本次无命中 —— 球内无可探矿、不在矿洞 region、半径为 0
     * 三者在此不作区分 (它们对玩家是同一句"没探到", 也不该让调用方在裁决链之外重建一份判据)。
     */
    public record ScanHit(OreType ore, List<BlockPos> positions) {
    }

    /** 无命中的唯一实例 (三条短路路径共用, 避免各处各造一个空结果)。 */
    private static final ScanHit NO_HIT = new ScanHit(null, List.of());

    /**
     * 服务端权威扫描: 确认玩家在矿洞 region 内后, 在玩家所在 ServerLevel 的探测球 (半径
     * {@link MinerSkills#oreScanRadius}) 内, 收集第一个有命中的可探矿种的全部坐标 (单矿种一次)。
     * 无实例 (不在矿洞 region) / 半径为 0 / 球内无任何可探矿 -> 返回空列表 (不下发全图)。
     *
     * @return 球内确有目标矿的世界坐标列表 (个数 <= ORE_SCAN_MAX_RESULTS)
     */
    public static List<BlockPos> scan(ServerPlayer player, int level) {
        return scanDetailed(player, level).positions();
    }

    /**
     * 同 {@link #scan}, 但连"命中的是哪个矿种"一起返回。
     *
     * 拆出本方法而不是给 {@link #scan} 换返回类型: 键位路径 (MinerActions) 只要坐标, 而面板路径要把矿种显示成
     * 物品名与图标 —— {@link #scanWorldDetailed} 内部本来就知道是哪个矿种, 只是旧签名把它丢了。全部筛选
     * (等级门 allowedOres / 矿洞门 regionAt / 半径门 oreScanRadius / 单矿种 preferenceOrder / 硬顶
     * ORE_SCAN_MAX_RESULTS) 仍只有这一份实现, 两条路径共用, 不存在"面板版放宽了某一条"的可能。
     */
    public static ScanHit scanDetailed(ServerPlayer player, int level) {
        Set<OreType> allowed = allowedOres(level);
        if (allowed.isEmpty()) {
            return NO_HIT;
        }
        /*
         * 维度门必须排在 region 门之前: RegionBox.contains 只比 X/Z (Y 与维度都不参与), 而 EASY 区盒是
         * X∈[0,256)、Z∈[0,256) —— 世界出生点通常就落在这个范围里。少了这一道, 玩家站在主世界或下界的
         * 出生点附近就能过 region 门, 而随后扫的是 player.serverLevel() 即他当前所在的维度, 于是探矿
         * 变成任意维度的透视器 (L8 在下界可直接读出远古残骸坐标)。
         *
         * 判据与 MinerActions.inMiningRegion 逐字一致 —— 矿工模块其余每一处 region 守卫都是先判维度再判
         * 区盒, 唯独探矿这条曾漏掉。
         */
        if (!player.level().dimension().equals(MiningConstants.MINING_LEVEL)) {
            return NO_HIT;
        }
        InstanceState instance = MiningServices.instanceManager()
                .regionAt(player.getBlockX(), player.getBlockZ());
        if (instance == null) {
            return NO_HIT; // 不在矿洞 region 内, 不探。
        }
        int radius = MinerSkills.oreScanRadius(level);
        if (radius <= 0) {
            return NO_HIT;
        }
        return scanWorldDetailed(player.serverLevel(), player.blockPosition(), radius, allowed);
    }

    /**
     * 对探测球内真实世界方块逐格扫描, 按矿种优先序取第一个有命中的矿种并收其全部坐标 (单矿种一次)。
     * 与 {@link #scan} 解耦的纯世界读取核心: 不依赖实例/玩家等级门控, 仅需 level + 球心 + 半径 + 可探集合,
     * 便于直接以真实 ServerLevel 测试 (探矿复活的回归锚点)。
     *
     * @param level   要读取方块态的服务端世界
     * @param center  探测球心 (玩家位置)
     * @param radius  探测半径 (> 0)
     * @param allowed 可探矿种集合 (非空; 空集语义在上游 allowedOres 已短路)
     * @return 球内第一个有命中矿种的全部坐标 (<= ORE_SCAN_MAX_RESULTS); 无命中返回空表
     */
    public static List<BlockPos> scanWorld(ServerLevel level, BlockPos center, int radius, Set<OreType> allowed) {
        return scanWorldDetailed(level, center, radius, allowed).positions();
    }

    /** 同 {@link #scanWorld}, 但连命中的矿种一起返回 (筛选实现的唯一落点)。 */
    public static ScanHit scanWorldDetailed(ServerLevel level, BlockPos center, int radius, Set<OreType> allowed) {
        if (allowed.isEmpty() || radius <= 0) {
            return NO_HIT;
        }
        int r2 = radius * radius;
        // 单矿种语义: 按可探集合优先序 (铁>煤>钻>金>残骸) 逐个试, 取第一个球内确有命中的矿种, 收其全部坐标。
        for (OreType target : preferenceOrder()) {
            if (!allowed.contains(target)) {
                continue;
            }
            List<BlockPos> hits = collectWithinSphere(level, center, radius, r2, target);
            if (!hits.isEmpty()) {
                return new ScanHit(target, hits);
            }
        }
        return NO_HIT;
    }

    /**
     * 球内逐格读真实世界方块态收集某矿种命中坐标 (服务端权威, 只收确有命中的)。
     * 仅收已加载区块内的命中 (未加载区块 isLoaded=false 跳过, 不强制加载、不臆造数据)。
     */
    private static List<BlockPos> collectWithinSphere(ServerLevel level, BlockPos center,
                                                      int radius, int r2, OreType target) {
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
                    Block block = level.getBlockState(cursor).getBlock();
                    if (OreType.fromBlock(block) == target) {
                        hits.add(cursor.immutable());
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
