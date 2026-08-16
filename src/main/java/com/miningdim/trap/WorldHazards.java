package com.miningdim.trap;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真实世界地形危害判据 (F033 修复的正面替代): 把 9.4 的静态陷阱词表重新锚定到原版
 * minecraft:noise 生成后真实存在的地形危害上, 取代已判废的离线布点表
 * (该表原读一张恒空的离线体素占用表, 现直接读 ServerLevel)。
 *
 * 复核修正 (F033 三次独立复核坐实): 单靠 {@link #hazardAt} 的静态方块态判据在本仓库实际发运的矿洞
 * worldgen 数据包 (data/miningdim/worldgen) 下几乎恒空 —— noise_settings 关流体 (aquifers_enabled=false /
 * default_fluid=air), 四个 biome 的 features 只有 monster_room + 纯 ore_* 特征, 没有 ore_gravel / lake_lava /
 * spring_lava, 玩家也带不进沙砾岩浆 (RulesSystem.placeWhitelist 只放行 scaffolding)。世界里唯二真实存在的危害
 * 来源是: (a) 世界最底部 y<=-56 附近雕刻器留下的原版岩浆带 (hazardAt 本就能读到, 无需额外接线);
 * (b) {@link DynamicTrapEngine} 运行期主动布下的岩浆喷发/局部坍塌 —— 这两类是本 mod 唯一"设计上就该被探测到"
 * 的动态危害, 但它们是转瞬即逝的世界写 (LAVA_BURST_RECYCLE_TICKS=5 tick 的单格岩浆; 局部坍塌一旦落地即变成
 * FallingBlockEntity, 不再是可读的静态方块态), 单靠被动方块态扫描在陷阱探测的评估节流窗口内基本不可能撞上。
 *
 * 修法 (任务书第二条允许路线: 改扫动态陷阱真实落点): 新增本类持有的活跃危害登记表, 由
 * {@link DynamicTrapEngine} 在决策产生危害的那一刻起 (预警粒子/音效开始播的同时) 登记坐标, 到危害真正消失
 * (岩浆回收 / 坍塌列处理完毕) 时注销。{@link com.miningdim.job.miner.TrapScanService#scanWorld} 在球内除查
 * 方块态外, 同时查这张表, 从而能在预警窗口内就把即将成形的危害标记出来 (提前于它真正变成可读方块态的那一刻),
 * 而不是必须精确撞上那个 5 tick 窗口。
 *
 * 权衡与对矿工技能体验的影响 (据实记录, 不回避): 即便加上这条动态登记, 陷阱探测在本维度仍然是一个"多数时候
 * 空手而归, 少数时候能提前示警一次正在发生的动态陷阱"的技能 —— 静态地形本身缺乏危害是 worldgen 数据包的既定
 * 事实 (改 worldgen 加天然岩浆湖/沙砾层不在本分支范围, 需另开 worldgen 分支评审), 本分支只能把"能探测到的
 * 危害"从"数学上恒空"改善为"动态陷阱触发的预警/存续窗口内可探测", 命中率仍受陷阱触发频率 (TrapParams 的
 * 各类冷却) 与扫描 CD (MinerConstants.TRAP_SCAN_CD_TICKS_AT_UNLOCK/AT_MAX) 双重稀释, 不构成"稳定可用的探雷器"。
 *
 * 复用 {@link TrapType} 而不新造枚举: 下游 (陷阱探测的致死等级门, TrapScanService.includeLethal)
 * 只读 {@link TrapType#lethal()}, 复用即可零改动地接入既有的等级过滤链路。
 */
public final class WorldHazards {

    /**
     * 活跃动态危害登记表 (坐标 -> 危害类型)。仅服务端主线程读写 (D8: DynamicTrapEngine 的世界写与
     * TrapScanService 的扫描均经 server tick 主线程), ConcurrentHashMap 只作防御, 不替代主线程串行纪律。
     */
    private static final Map<BlockPos, TrapType> ACTIVE_DYNAMIC_HAZARDS = new ConcurrentHashMap<>();

    private WorldHazards() {
    }

    /** 某方块态对应的陷阱语义; 无危害返回 null。判据固定三条, 不扩。 */
    public static TrapType hazardAt(BlockState state) {
        if (state.getFluidState().is(FluidTags.LAVA)) {
            return TrapType.LAVA_POCKET; // 岩浆源与流动岩浆同判, 致死类
        }
        if (state.is(Blocks.MAGMA_BLOCK)) {
            return TrapType.LAVA_POCKET; // 接触即烧, 与岩浆同档致死
        }
        if (state.getBlock() instanceof FallingBlock) {
            return TrapType.COLLAPSING_TUNNEL; // 砂砾/沙/混凝土粉, 挖动即塌, 非致死
        }
        return null;
    }

    /**
     * 登记一处正在预警/存续的动态危害 (由 {@link DynamicTrapEngine} 在触发决策成立的那一刻调用, 早于
     * 危害真正落地成方块态)。同坐标重复登记直接覆盖 (取最新类型)。
     */
    static void markActive(BlockPos pos, TrapType type) {
        ACTIVE_DYNAMIC_HAZARDS.put(pos.immutable(), type);
    }

    /** 注销一处动态危害 (危害消失: 岩浆回收完毕 / 坍塌列已处理完毕, 无论最终是否真的落了方块)。 */
    static void clearActive(BlockPos pos) {
        ACTIVE_DYNAMIC_HAZARDS.remove(pos);
    }

    /** 查某坐标是否有登记中的活跃动态危害; 无则 null。供 {@link com.miningdim.job.miner.TrapScanService} 扫描叠加。 */
    public static TrapType activeAt(BlockPos pos) {
        return ACTIVE_DYNAMIC_HAZARDS.get(pos);
    }
}
