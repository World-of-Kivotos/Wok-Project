package com.miningdim.trap;

import com.miningdim.core.MiningConstants;
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
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 静态陷阱触发器 (协议级伪装版)。用户裁决后世界里已无可被识破的 trap_ore —— 陷阱身份改由 {@link TrapRegistry}
 * 持有 (伪装矿石坐标 -> {@link StaticTrapKind}), 本触发器监听 {@link BlockEvent.BreakEvent}, 玩家挖到的坐标一旦
 * 命中注册表即视为陷阱, 取消该破坏 (吞掉方块、无掉落)、从注册表移除、经 {@link TrapSystem#scheduleDelayed} 反应窗口
 * 后落地对应效果 (爆炸/岩浆/落石)。
 *
 * 取消事件即闭合经济与经验 (economy-04 语义: 取消的 BreakEvent 不入账): 本器挂 {@link EventPriority#HIGHEST}
 * 先于矿工/经济的默认优先级 handler 跑, 取消后二者 (均未开 receiveCanceled) 自动不再处理该块 —— 挖陷阱不计矿物计数、
 * 不发矿工经验、不触发连锁。
 *
 * 一致性守卫: 注册表命中但该坐标方块已不是伪装矿石族 (幽灵条目, 如被其它机制换成石头) -> 移除条目 + WARN, 不取消、
 * 不触发, 放行正常破坏。
 *
 * 伤害来源纪律 (与矿脉抗性联动, 无需专属 DamageSource): 四类效果一律用 {@link com.miningdim.job.miner.MinerSurvival#isTrapSource}
 * 已识别的原版环境伤类型 —— TNT/假矿走非玩家 {@link DamageTypes#EXPLOSION} (排除 PLAYER_EXPLOSION, 守 PvP 红线),
 * 岩浆袋走 {@link DamageTypes#LAVA}, 崩塌走 {@link DamageTypes#FALLING_BLOCK}。{@link #damageTypeFor} 暴露该映射供回归测试锁死。
 *
 * 反应窗口经 {@link TrapSystem#scheduleDelayed} 延迟到矿洞 tick 主线程落地。{@link Level#destroyBlock} 不会 post
 * BreakEvent (原版), 故取消原事件后自行 destroyBlock 不会递归触发本器; 连锁引擎复用 {@link #detonate} 亦同。
 */
public final class StaticTrapTrigger {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/trap/trigger");

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return; // 客户端预测破坏: 不触发 (服务端权威)。
        }
        if (!level.dimension().equals(MiningConstants.MINING_LEVEL)) {
            return; // 陷阱只在矿洞维度; 防御性守卫 (异常放置不触发)。
        }
        if (tryTriggerTrap(level, event.getPos().immutable(), event.getState())) {
            // 命中并触发: 取消原破坏, 让矿工/经济的默认优先级 handler 不再处理该块 (不计数/不发经验/不连锁)。
            event.setCanceled(true);
        }
    }

    /**
     * 内核 (可直调测试, 不依赖 BreakEvent/维度门): 若 pos 命中注册表且方块仍是伪装矿石 -> 移除条目 + {@link #detonate},
     * 返回 true (调用方取消原破坏事件)。命中但方块已非伪装矿石族 (幽灵条目) -> 移除条目 + WARN, 返回 false (放行正常破坏)。
     * 未命中 -> 返回 false。
     */
    public static boolean tryTriggerTrap(ServerLevel level, BlockPos pos, BlockState state) {
        TrapRegistry registry = TrapRegistry.get(level);
        StaticTrapKind kind = registry.get(pos);
        if (kind == null) {
            return false; // 非陷阱 (真矿石 / 普通方块): 不干预。
        }
        if (!TrapDisguise.isDisguiseOre(state)) {
            // 幽灵条目: 注册表还记着陷阱, 但该坐标方块已不是任何伪装矿石 (被换/被清)。移除条目防误触, 放行正常破坏。
            registry.remove(pos);
            LOGGER.warn("[miningdim] trap registry ghost entry at {} (block {} is not a disguise ore), removed without triggering",
                    pos, state.getBlock());
            return false;
        }
        registry.remove(pos);
        detonate(level, pos, kind);
        return true;
    }

    /**
     * 触发某坐标的静态陷阱: 吞掉方块 (无掉落, {@link Level#destroyBlock} 不 post BreakEvent 故不递归) + 反应窗口后落地效果。
     * 供 BreakEvent 路径 (取消原破坏后自行清块) 与连锁引擎 (未揭示陷阱被连锁触及时) 共用。调用方须已从注册表移除该条目。
     */
    public static void detonate(ServerLevel level, BlockPos pos, StaticTrapKind kind) {
        level.destroyBlock(pos, false);
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
