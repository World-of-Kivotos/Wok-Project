package com.miningdim.trap;

import com.miningdim.core.MiningConstants;
import com.miningdim.trap.block.TrapOreBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 静态陷阱触发器 (方案 C): 监听 {@link BlockEvent.BreakEvent}, 玩家挖到 {@link TrapOreBlock} 时按其
 * {@link TrapOreBlock#KIND} 触发对应效果。取代已废弃的离线体素布点 —— 陷阱现由 datapack {@code minecraft:ore}
 * feature 在真实世界石层散布 (同 ore_emerald), 本触发器是它们唯一的运行期出口。
 *
 * 伤害来源纪律 (与矿脉抗性联动, 无需专属 DamageSource): 四类效果一律用 {@link com.miningdim.job.miner.MinerSurvival#isTrapSource}
 * 已识别的原版环境伤类型 —— TNT/假矿走非玩家 {@link DamageTypes#EXPLOSION} (排除 PLAYER_EXPLOSION, 守 PvP 红线),
 * 岩浆袋走 {@link DamageTypes#LAVA}, 崩塌走 {@link DamageTypes#FALLING_BLOCK}。故矿脉抗性减伤 (L5+, 矿洞内) 自动覆盖
 * 静态陷阱, 一并闭合审计 Critical 的"探测恒空 + 减伤只覆盖动态"。{@link #damageTypeFor} 暴露该映射供回归测试锁死。
 *
 * 反应窗口 (fuse/预警) 经 {@link TrapSystem#scheduleDelayed} 延迟到矿洞 tick 主线程落地 (与动态陷阱共用同一延迟队列),
 * 世界写不在事件线程直接做。爆炸不会递归触发相邻 TrapOreBlock (原版 Explosion 走独立破坏路径, 不 post BreakEvent)。
 */
public final class StaticTrapTrigger {

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        BlockState state = event.getState();
        if (!(state.getBlock() instanceof TrapOreBlock)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return; // 客户端预测破坏: 不触发 (服务端权威)。
        }
        if (!level.dimension().equals(MiningConstants.MINING_LEVEL)) {
            return; // 陷阱只在矿洞维度生成; 防御性守卫 (异常放置不触发)。
        }
        StaticTrapKind kind = state.getValue(TrapOreBlock.KIND);
        BlockPos pos = event.getPos().immutable();
        // 反应窗口后在矿洞主线程落地 (TR-1: fuse/预警给玩家逃离窗口); 立即 (window=0) 也走延迟队列, 下一 tick 落地。
        long dueTick = level.getGameTime() + kind.trapType().reactionWindowTicks();
        TrapSystem.get().scheduleDelayed(dueTick, () -> fire(level, pos, kind));
    }

    /** 反应窗口到点后落地陷阱效果 (主线程执行, 由 TrapSystem 延迟队列驱动)。 */
    private static void fire(ServerLevel level, BlockPos pos, StaticTrapKind kind) {
        switch (kind) {
            case TNT_VEIN, FAKE_ORE -> explode(level, pos, kind.trapType().damage());
            case LAVA_POCKET -> revealLava(level, pos);
            case COLLAPSING_TUNNEL -> collapseCeiling(level, pos);
        }
    }

    /** 非玩家爆炸 (source null -> DamageTypes.EXPLOSION, 非 PLAYER_EXPLOSION): power = TrapType.damage。 */
    private static void explode(ServerLevel level, BlockPos pos, float power) {
        level.explode(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, power,
                Level.ExplosionInteraction.BLOCK);
    }

    /** 原位喷出一格岩浆 (体积 <= 2x2x2 取 1 格, 9.4); 接触走原版 LAVA 伤害。挖后该格已成空气, 仅当仍为空气时放。 */
    private static void revealLava(ServerLevel level, BlockPos pos) {
        if (level.isLoaded(pos) && level.getBlockState(pos).isAir()) {
            level.setBlock(pos, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /**
     * 头顶承重方块坍塌落砂 (FALLING_BLOCK): 从触发点上方 1-5 格找第一块实心非流体方块作坍塌源, 清空并生成会砸下的
     * 砂砾 FallingBlock (单块伤害 + 累计封顶, 同动态局部坍塌口径)。无承重顶则放弃 (开阔处不坍塌, 无害)。
     */
    private static void collapseCeiling(ServerLevel level, BlockPos pos) {
        BlockPos source = null;
        for (int dy = 1; dy <= 5; dy++) {
            BlockPos c = pos.above(dy);
            if (!level.isLoaded(c)) {
                break;
            }
            BlockState bs = level.getBlockState(c);
            if (!bs.isAir() && bs.getFluidState().isEmpty()) {
                source = c;
                break;
            }
        }
        if (source == null) {
            return;
        }
        level.setBlock(source, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        FallingBlockEntity fb = FallingBlockEntity.fall(level, source, Blocks.GRAVEL.defaultBlockState());
        fb.setHurtsEntities(TrapType.COLLAPSING_TUNNEL.damage(), (int) TrapParams.COLLAPSE_DAMAGE_CAP);
    }

    /**
     * 各静态陷阱种类触发时实际造成伤害的原版 DamageType (回归锚: 断言全部落在 {@code MinerSurvival.isTrapSource} 集合内,
     * 从而矿脉抗性必然覆盖; 若未来改触发效果引入战斗向来源, 此映射改动即被测试拦下)。
     */
    public static ResourceKey<DamageType> damageTypeFor(StaticTrapKind kind) {
        return switch (kind) {
            case TNT_VEIN, FAKE_ORE -> DamageTypes.EXPLOSION;
            case LAVA_POCKET -> DamageTypes.LAVA;
            case COLLAPSING_TUNNEL -> DamageTypes.FALLING_BLOCK;
        };
    }
}
