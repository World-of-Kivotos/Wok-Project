package com.miningdim.job.miner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

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
     * 从起始块发起连锁 BFS (玩家手动破坏的同矿种相连普通方块)。
     *
     * @param player    挖矿者 (用于工具 / 掉落上下文)
     * @param origin    起始块世界坐标 (玩家刚破坏的块; 不计入 budget, 由原始事件计数)
     * @param level     世界
     * @param budget    充能预算 (本次连锁最多额外破坏的块数)
     * @param sink      逐块产出回放 sink
     * @return 实际连带破坏的块数 (<= budget)
     */
    public int chainBreak(ServerPlayer player, BlockPos origin, ServerLevel level, int budget, OreCountSink sink) {
        if (budget <= 0 || reentrant) {
            return 0;
        }
        Block originBlock = level.getBlockState(origin).getBlock();
        if (!isWhitelisted(originBlock)) {
            return 0; // 起始块非白名单 (如直接挖钻石): 不连锁。
        }

        reentrant = true;
        try {
            return bfsBreak(player, level, origin, originBlock, budget, sink, neighbors6());
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

    // ---- BFS 内核 ----

    private int bfsBreak(ServerPlayer player, ServerLevel level, BlockPos origin, Block originBlock,
                         int budget, OreCountSink sink, Direction[] dirs) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> frontier = new ArrayDeque<>();
        visited.add(origin.immutable());
        frontier.add(origin.immutable());

        int broken = 0;
        while (!frontier.isEmpty() && broken < budget) {
            BlockPos cur = frontier.poll();
            for (Direction d : dirs) {
                BlockPos next = cur.relative(d);
                if (visited.contains(next)) {
                    continue;
                }
                visited.add(next.immutable());
                BlockState state = level.getBlockState(next);
                Block block = state.getBlock();
                // 连锁只在 "与起始块同种且白名单" 上扩散 (清矿脉语义); 遇高价矿/异种停在边界不破坏。
                if (block != originBlock || !isWhitelisted(block) || isHardExcluded(block)) {
                    continue;
                }
                if (breakOne(player, level, next.immutable(), state, sink)) {
                    broken++;
                    frontier.add(next.immutable());
                    if (broken >= budget) {
                        break;
                    }
                }
            }
        }
        return broken;
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

    private static Direction[] neighbors6() {
        return Direction.values();
    }

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
