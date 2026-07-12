package com.miningdim.job.miner;

import com.miningdim.trap.StaticTrapKind;
import com.miningdim.trap.StaticTrapTrigger;
import com.miningdim.trap.TrapRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/**
 * 连锁挖矿 BFS 引擎 (Miner_Job_DesignSpec 第四章, 反通胀第一道硬约束)。
 *
 * 硬白名单 (代码级, {@link MinerConstants#CHAIN_WHITELIST}): 仅石/深板岩/凝灰岩/花岗岩/煤/铁/铜 放行连带破坏;
 * 硬排除 ({@link MinerConstants#CHAIN_HARD_EXCLUDE}): 钻石/金/残骸/绿宝石物理排除, 连锁遇高价矿停在边界。
 * 既不在白名单也不在排除名单的方块 (如基岩/空气/液体) 同样不连锁 (默认拒绝, 只白名单内扩散)。
 *
 * 反通胀核心 (第十章第一条): 每块用 {@link ServerLevel#destroyBlock} 破坏前先用 {@link Block#getDrops}
 * (含时运) 算产出物个数, 逐块回放到 {@link OreCountSink} 走经济计数, 严禁 destroyBlock 静默绕过计数。
 * 受充能池预算约束 (budget = 当前充能), 起始块不计入预算 (由触发该块破坏的原始事件负责)。
 *
 * 防重入: 引擎以 {@code reentrant} 标志拦截 destroyBlock 触发的二次 BreakEvent 引发的递归连锁。
 *
 * 隧道挖 (第四章 L9 3x3) 复用同白名单, 见 {@link #tunnelBreak}。
 */
public final class ChainMiningEngine {

    /** 连锁/隧道连带产出物的 sink: 逐个产出物个数回放 (经济计数 / 时运封顶查询 / 物化掉落)。 */
    @FunctionalInterface
    public interface OreCountSink {
        /**
         * 一个连带破坏块的产出回放。sink 负责唯一物化这批掉落 (入包或 spawn), destroyBlock 已不掉落。
         *
         * @param pos         被破坏块的世界坐标 (掉落落点 / 经济定位)
         * @param brokenBlock 被破坏的方块 (用于经济分类计数)
         * @param drops       该块的全部掉落物 (含时运额外掉落, 物化前快照)
         */
        void onChainDrop(BlockPos pos, Block brokenBlock, List<ItemStack> drops);
    }

    /** 防止 destroyBlock 触发的二次 BreakEvent 再次进入连锁 (递归保护)。 */
    private boolean reentrant = false;

    /** 当前是否在连锁回放中 (供 MinerSystem 的 BreakEvent handler 判断跳过自身处理)。 */
    public boolean inChainBreak() {
        return reentrant;
    }

    /**
     * 从起始块发起连锁 (玩家手动破坏的同矿种相连普通方块): 先 {@link #plan} 收候选, 再 {@link #execute} 回放破坏。
     * 预览与实际连锁共用同一 {@link #plan} (单一真源, 保证预览数字与实际连锁永不撒谎)。
     *
     * @param player    挖矿者 (用于工具 / 掉落上下文 / 揭示态归属)
     * @param origin    起始块世界坐标 (玩家刚破坏的块; 不计入 budget, 由原始事件计数)
     * @param level     世界
     * @param budget    充能预算 (本次连锁最多额外破坏的块数)
     * @param sink      逐块产出回放 sink
     * @return 实际连带破坏的块数 (触发的未揭示陷阱不计入, 与现行陷阱语义一致)
     */
    public int chainBreak(ServerPlayer player, BlockPos origin, ServerLevel level, int budget, OreCountSink sink) {
        return execute(player, level, plan(player, level, origin, budget), sink);
    }

    /**
     * 纯 plan: 从起始块 BFS 收集连锁候选坐标 (有序, 不含 origin), 不改世界、不触发陷阱、不消耗充能 —— 预览与
     * {@link #chainBreak} 共用本函数作候选来源。含全部现行规则: 白名单/硬排除/同矿种扩散/充能上限。
     *
     * 陷阱处置 (最高优先级防泄密不变量, 用户裁决):
     *  - 已揭示陷阱位: 跳过 (不入候选、不从其扩散) —— 玩家已探测掌握此情报, 与现行连锁"已揭示跳过"一致, 不泄密。
     *  - 未揭示陷阱位: 与其伪装成的普通矿石完全同等对待 (照常入候选 + 从其继续扩散)。这一点是不可谈判的: 若跳过或不
     *    扩散, "该位是未揭示陷阱"与"该位是同种普通矿"两种世界的 plan 就会不同, 预览沦为免费陷阱探测器, 击穿协议级伪装。
     *    世界里陷阱位本就是真伪装矿石 (身份只在 {@link TrapRegistry}), 故这里对未揭示陷阱不查注册表、按世界方块处理即天然满足。
     */
    public static List<BlockPos> plan(ServerPlayer player, ServerLevel level, BlockPos origin, int budget) {
        List<BlockPos> out = new ArrayList<>();
        if (budget <= 0) {
            return out;
        }
        Block originBlock = level.getBlockState(origin).getBlock();
        if (!isWhitelisted(originBlock)) {
            return out; // 起始块非白名单 (如直接挖钻石): 不连锁。
        }
        TrapRegistry registry = TrapRegistry.get(level);
        UUID uuid = player.getUUID();
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> frontier = new ArrayDeque<>();
        visited.add(origin.immutable());
        frontier.add(origin.immutable());

        while (!frontier.isEmpty() && out.size() < budget) {
            BlockPos cur = frontier.poll();
            for (Direction d : Direction.values()) {
                BlockPos next = cur.relative(d);
                if (visited.contains(next)) {
                    continue;
                }
                visited.add(next.immutable());
                Block block = level.getBlockState(next).getBlock();
                // 连锁只在 "与起始块同种且白名单" 上扩散 (清矿脉语义); 遇高价矿/异种停在边界不破坏。
                if (block != originBlock || !isWhitelisted(block) || isHardExcluded(block)) {
                    continue;
                }
                // 已揭示陷阱: 跳过 (不入候选、不扩散); 未揭示陷阱不查注册表 (按普通矿石处理), 保证两世界 plan 恒等 (防泄密)。
                if (registry.get(next) != null && registry.isRevealed(uuid, next)) {
                    continue;
                }
                BlockPos nextImmutable = next.immutable();
                out.add(nextImmutable);
                frontier.add(nextImmutable);
                if (out.size() >= budget) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 消费 {@link #plan} 的候选列表做实际破坏与陷阱交互 (与预览走同一 plan)。逐候选位:
     *  - 未揭示陷阱: {@link #handleChainTrap} 触发 (移除条目 + 吞块无掉落 + 反应窗口效果), 不计产出、不计入返回 broken;
     *  - 已揭示陷阱 (理论上 plan 已排除, 防御性): 跳过, 不破坏;
     *  - 普通白名单块: {@link #breakOne} 破坏并经 sink 唯一物化产出, broken++。
     * 防重入标志置位期间 destroyBlock 触发的二次 BreakEvent 不再进入连锁。
     *
     * @return 实际破坏的块数 (未揭示陷阱触发不计入, 与现行陷阱语义一致)
     */
    public int execute(ServerPlayer player, ServerLevel level, List<BlockPos> planned, OreCountSink sink) {
        if (planned.isEmpty() || reentrant) {
            return 0;
        }
        reentrant = true;
        try {
            int broken = 0;
            for (BlockPos pos : planned) {
                BlockState state = level.getBlockState(pos);
                // 陷阱交互 (命中注册表): 已揭示跳过 / 未揭示触发, 两者都不进产出、不计入 broken。
                if (handleChainTrap(player, level, pos, state) != TrapInteraction.NOT_TRAP) {
                    continue;
                }
                if (breakOne(player, level, pos, state, sink)) {
                    broken++;
                }
            }
            return broken;
        } finally {
            reentrant = false;
        }
    }

    /**
     * 隧道挖 (L9): 沿玩家水平朝向掘进 {@link MinerConstants#TUNNEL_DEPTH} 段, 每段 3x3 横截面, 仅破坏白名单块。
     * 不受充能池约束 (走 CD), 但同样逐块回放经济计数, 遇高价矿/非白名单块该格跳过 (停在边界)。
     *
     * @return 实际破坏块数
     */
    public int tunnelBreak(ServerPlayer player, BlockPos origin, ServerLevel level,
                           Direction horizontalFacing, OreCountSink sink) {
        if (reentrant) {
            return 0;
        }
        reentrant = true;
        try {
            int broken = 0;
            Direction forward = toHorizontal(horizontalFacing);
            // 横截面两条正交基: 一条水平垂直于前进方向, 一条竖直。
            Direction side = forward.getClockWise();
            for (int depth = 1; depth <= MinerConstants.TUNNEL_DEPTH; depth++) {
                BlockPos center = origin.relative(forward, depth);
                for (int dh = -1; dh <= 1; dh++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos p = center.relative(side, dh).above(dy);
                        BlockState state = level.getBlockState(p);
                        Block block = state.getBlock();
                        if (!isWhitelisted(block) || isHardExcluded(block)) {
                            continue; // 非白名单 / 高价矿: 跳过该格 (停在边界)。
                        }
                        // 伪装陷阱交互 (同连锁): 命中注册表 -> 已揭示跳过 / 未揭示触发, 两者都不计产出。
                        if (handleChainTrap(player, level, p, state) != TrapInteraction.NOT_TRAP) {
                            continue;
                        }
                        if (breakOne(player, level, p, state, sink)) {
                            broken++;
                        }
                    }
                }
            }
            return broken;
        } finally {
            reentrant = false;
        }
    }

    /**
     * 破坏单块: 先用 getDrops (含工具时运) 快照产出 -> destroyBlock(dropBlock=false, 不重复掉落) -> 把产出交 sink。
     *
     * 关键 (防重复产出): destroyBlock 传 dropBlock=false 使原版不再自行 spawn 掉落物; 产出唯一经 {@link OreCountSink}
     * 物化 (自动入包入库存 / 否则 sink 负责 spawn), 保证产出物个数计数口径 (方案 B) 与实际产出严格一致, 不双发。
     * 返回是否成功破坏 (该块非空气且确实被移除时为 true)。
     */
    private boolean breakOne(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state, OreCountSink sink) {
        if (state.isAir()) {
            return false;
        }
        ItemStack tool = player.getMainHandItem();
        List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), player, tool);
        // dropBlock=false: 原版不掉落; 产出由 sink 唯一物化 (杜绝双发与计数漂移)。防重入标志已置位拦截递归。
        boolean removed = level.destroyBlock(pos, false, player);
        if (removed) {
            sink.onChainDrop(pos, state.getBlock(), drops);
        }
        return removed;
    }

    // ---- 伪装陷阱连锁交互 (用户裁决规则) ----

    /** 连锁触及某坐标时对伪装陷阱的处置。 */
    enum TrapInteraction {
        /** 非陷阱: 照常连锁破坏。 */
        NOT_TRAP,
        /** 命中且该玩家已揭示: 跳过, 方块保留原地, 连锁继续吃别的。 */
        SKIP_REVEALED,
        /** 命中且未揭示: 触发陷阱 (移除条目 + 吞块 + 反应窗口效果), 该位不进连锁产出结算。 */
        TRIGGERED
    }

    /**
     * 连锁/隧道回放用 destroyBlock 绕过 BreakEvent, 故必须在此显式接 {@link TrapRegistry} (否则连锁会当普通矿石吃掉
     * 伪装陷阱、既不触发也无预警)。规则 (用户裁决): 命中注册表且该玩家已揭示 -> 跳过 (方块保留); 未揭示 -> 触发陷阱
     * ({@link StaticTrapTrigger#detonate} 吞块无掉落 + 反应窗口效果) 且该位不进连锁产出结算 (调用方 continue 即不走
     * breakOne, 无掉落/无经济计数; 连锁本就不逐块发经验)。命中即 continue, 连锁不从陷阱位继续扩散。
     */
    private static TrapInteraction handleChainTrap(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
        TrapRegistry registry = TrapRegistry.get(level);
        StaticTrapKind kind = registry.get(pos);
        if (kind == null) {
            return TrapInteraction.NOT_TRAP;
        }
        if (registry.isRevealed(player.getUUID(), pos)) {
            return TrapInteraction.SKIP_REVEALED; // 已探测揭示: 玩家已知, 连锁不替其踩雷。
        }
        // 未揭示: 连锁触发陷阱 (先移除条目再引爆, detonate 的 destroyBlock 不 post BreakEvent 故不递归回连锁)。
        registry.remove(pos);
        StaticTrapTrigger.detonate(level, pos, kind);
        return TrapInteraction.TRIGGERED;
    }

    // ---- 白名单 / 排除 (纯函数, 可单测) ----

    /** 是否在硬白名单内 (可连锁的普通方块)。 */
    public static boolean isWhitelisted(Block block) {
        for (Block b : MinerConstants.CHAIN_WHITELIST) {
            if (b == block) {
                return true;
            }
        }
        return false;
    }

    /** 是否在硬排除名单内 (高价矿 + 绿宝石, 连锁物理停在边界)。 */
    public static boolean isHardExcluded(Block block) {
        for (Block b : MinerConstants.CHAIN_HARD_EXCLUDE) {
            if (b == block) {
                return true;
            }
        }
        return false;
    }

    // ---- 工具 ----

    private static Direction toHorizontal(Direction d) {
        return switch (d) {
            case UP, DOWN -> Direction.NORTH; // 朝上下掘进无意义, 退化为正北水平。
            default -> d;
        };
    }

    /** 产出物个数统计 (方案 B 计数口径: 一组掉落物的物品总个数, 含时运额外)。 */
    public static int countDropItems(List<ItemStack> drops) {
        int total = 0;
        for (ItemStack s : drops) {
            total += s.getCount();
        }
        return total;
    }

    /** 把一组产出物 spawn 到破坏点 (自动入包关闭时由 sink 调用, 替代原版掉落; 与 destroyBlock 不再双发)。 */
    public static void spawnDropsAt(ServerLevel level, BlockPos pos, List<ItemStack> drops) {
        for (ItemStack s : drops) {
            if (!s.isEmpty()) {
                Block.popResource(level, pos, s.copy());
            }
        }
    }
}
