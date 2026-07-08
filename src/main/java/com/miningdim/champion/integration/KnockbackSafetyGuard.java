package com.miningdim.champion.integration;

import com.miningdim.champion.SafeLandingRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * 位移安全守卫的【世界适配层】(ChampionStarAffix spec 9.3 / 红线 6)。所有产生位移/击退/击飞/换位的词条效果在
 * 服务端权威结算前, 必须经本守卫预测【末端落点】(而非瞬间落点) 是否安全: 落点柱向下连续无岩浆/火/岩浆块, 且水平
 * 距任何岩浆/虚空边缘 &gt;= {@link SafeLandingRules#EDGE_MARGIN} 格; 末端为 hazard 时把击退分量沿起点方向 clamp
 * 到最近安全格, 全程无安全格则仅结算伤害、取消位移。
 *
 * <p>职责边界 (关键约束):
 * <ul>
 *   <li>纯落点数学 (扫描深度 / 边缘 margin / clamp 步进) 由 {@link SafeLandingRules} 权威裁定, 本类不复写;
 *       本类只把 {@link ServerLevel} 适配成 {@link SafeLandingRules.ColumnProbe} 探针, 再把裁定结果映射为
 *       {@link Decision}。这样"世界读方式"与"落点算法"解耦, A 侧改算法不牵动本适配层。</li>
 *   <li>本类【只读世界】, 不做任何方块/实体写操作。消费方 (各词条 handler) 拿到 {@link Decision} 后的实际位移写
 *       操作, 必须经 {@code server.execute} 回主线程串行执行; 严禁任何效果直接 {@code setDeltaMovement}/{@code push}
 *       绕过本守卫 (spec 9.3 原文: "禁止任何效果直接 setDeltaMovement/push 绕闸, 写操作经 server.execute 回主线程")。</li>
 * </ul>
 */
public final class KnockbackSafetyGuard {

    private KnockbackSafetyGuard() {
    }

    /** 落点裁决类型。 */
    public enum Outcome {
        /** 末端落点本身安全, 位移原样放行。 */
        SAFE,
        /** 末端不安全但沿起点方向存在安全中间格, 位移被夹断到该格。 */
        CLAMPED,
        /** 起点到末端全程无安全格, 位移取消 (消费方仅结算伤害不位移)。 */
        DENIED
    }

    /**
     * 单次位移裁决结论。
     *
     * @param outcome 裁决类型
     * @param landing 最终安全落点; {@link Outcome#DENIED} 时恒为 {@code null} (无落点可给)
     */
    public record Decision(Outcome outcome, BlockPos landing) {
    }

    /**
     * 单点落点裁决 (换位/瞬移的目标格无"起点方向"可夹, 故只有安全/否两态): 目标格经
     * {@link SafeLandingRules#isSafeLanding} 判安全则返回 {@link Outcome#SAFE} 且落点即目标, 否则
     * {@link Outcome#DENIED}。本方法永不返回 {@link Outcome#CLAMPED} (无 clamp 起点)。
     *
     * @param level  服务端世界 (只读)
     * @param target 候选落点格
     * @return 裁决结论
     */
    public static Decision evaluateLanding(ServerLevel level, BlockPos target) {
        SafeLandingRules.ColumnProbe probe = probeOf(level);
        if (SafeLandingRules.isSafeLanding(probe, target.getX(), target.getY(), target.getZ())) {
            return new Decision(Outcome.SAFE, target);
        }
        return new Decision(Outcome.DENIED, null);
    }

    /**
     * 位移末端裁决 + 回退: 末端安全 -&gt; {@link Outcome#SAFE}; 末端为 hazard 但沿 from-&gt;end 方向存在安全格 -&gt;
     * {@link Outcome#CLAMPED} 落到该格; 全程无安全格 (clamp 返回 {@code null}) -&gt; {@link Outcome#DENIED} 取消位移。
     * clamp 的分量步进与边缘裁定完全交由 {@link SafeLandingRules#clampTowardOrigin}, 本方法只做世界适配与结果映射。
     *
     * @param level       服务端世界 (只读)
     * @param from        位移起点 (玩家/怪当前位置, 连续坐标)
     * @param proposedEnd 效果计算出的候选末端 (连续坐标)
     * @return 裁决结论
     */
    public static Decision clampDisplacement(ServerLevel level, Vec3 from, Vec3 proposedEnd) {
        SafeLandingRules.ColumnProbe probe = probeOf(level);
        BlockPos end = BlockPos.containing(proposedEnd);
        if (SafeLandingRules.isSafeLanding(probe, end.getX(), end.getY(), end.getZ())) {
            return new Decision(Outcome.SAFE, end);
        }
        int[] clamped = SafeLandingRules.clampTowardOrigin(probe,
                from.x, from.y, from.z, proposedEnd.x, proposedEnd.y, proposedEnd.z);
        if (clamped == null) {
            return new Decision(Outcome.DENIED, null);
        }
        return new Decision(Outcome.CLAMPED, new BlockPos(clamped[0], clamped[1], clamped[2]));
    }

    /**
     * 把服务端世界适配成落点探针 (spec 9.3 探针语义, 只读):
     * <ul>
     *   <li>hazard = 该格流体属 {@link FluidTags#LAVA} (该 tag 同时含 lava 源与 flowing_lava), 或方块为
     *       {@link Blocks#FIRE}/{@link Blocks#SOUL_FIRE}/{@link Blocks#MAGMA_BLOCK}, 或属 {@link BlockTags#FIRE};</li>
     *   <li>footing = {@code state.isFaceSturdy(level, pos, UP)} 且非 hazard (岩浆块/火面虽可 sturdy 也不作落脚);</li>
     *   <li>minY = {@link ServerLevel#getMinBuildHeight()} (虚空向下扫描的维度下界)。</li>
     * </ul>
     * 探针捕获 {@code level} 只读、逐格现读方块 (位移事件低频, 不缓存以保证与世界最新态一致)。
     *
     * @param level 服务端世界
     * @return 绑定该世界的只读探针
     */
    /**
     * hazard 方块态判定 (状态级, 探针与 GameTest 共用): 流体属 {@link FluidTags#LAVA} (含源与流动), 或方块为
     * {@link Blocks#FIRE}/{@link Blocks#SOUL_FIRE}/{@link Blocks#MAGMA_BLOCK}, 或属 {@link BlockTags#FIRE}。
     * 抽成状态级是因为灵魂火这类带 canSurvive 放置约束的方块无法稳定摆进 1x1 GameTest 模板 (放置即弹掉),
     * 分类分支必须能脱离世界放置被直接断言。
     *
     * @param state 待判方块态
     * @return 是否 hazard
     */
    public static boolean isHazardState(BlockState state) {
        FluidState fluid = state.getFluidState();
        if (fluid.is(FluidTags.LAVA)) {
            return true;
        }
        return state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(BlockTags.FIRE);
    }

    public static SafeLandingRules.ColumnProbe probeOf(ServerLevel level) {
        return new SafeLandingRules.ColumnProbe() {
            @Override
            public boolean isHazard(int x, int y, int z) {
                return isHazardState(level.getBlockState(new BlockPos(x, y, z)));
            }

            @Override
            public boolean isFooting(int x, int y, int z) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState state = level.getBlockState(pos);
                // hazard 优先 (岩浆块可 isFaceSturdy=true 但绝不作落脚面); 单次取态复用两判, 免得每个扫描格重复
                // getBlockState (审查修复: 规则1 逐格 isFooting+isHazard 双问, 探针内再各自取态曾放大到 3 次/格)。
                return !isHazardState(state) && state.isFaceSturdy(level, pos, Direction.UP);
            }

            @Override
            public int minY() {
                return level.getMinBuildHeight();
            }
        };
    }
}
