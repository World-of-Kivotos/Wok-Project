package com.miningdim.job.miner;

import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningServices;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.miner.network.MinerHighlightS2C;
import com.miningdim.job.miner.network.MinerNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Optional;

/**
 * 矿工主动技能 / 开关的服务端权威编排 (Miner_Job_DesignSpec 第三/四/七章)。由 {@link com.miningdim.job.miner.network.MinerToggleC2S}
 * 的服务端 handler 委派, 在主线程执行。所有 CD/充能/等级解锁/探测结果一律服务端重算 (客户端无权)。
 *
 * 编排 (持状态走 {@link MinerSystem#stateOf}):
 *  - 探矿/陷阱探测: 校验等级解锁 + CD -> 服务端查询 -> 下发高亮 S2C -> 起 CD (脉冲熄灭客户端按 expireTick 处理)。
 *  - 连锁/自动入包/自动熔炼: 翻 per-player 开关位。
 *  - 隧道挖: 校验解锁 + CD -> ChainMiningEngine 沿朝向掘 3x3 一段 -> 起 CD。
 *  - 脱险归途: 起读条 (移动/受伤打断, 由 tick/LivingHurt 校验); 读条满 -> 复用回退态送回 + 清矿山态 + 起长 CD。
 *  - 声东击西: 起 spawnFreeze 降压窗口 + 起长 CD (复用 spawnFreeze 机制压后方刷怪数秒)。
 */
public final class MinerActions {

    private MinerActions() {
    }

    /** 网络 handler 入口: 按技能种类分派 (主线程)。 */
    public static void handleToggle(ServerPlayer player, MinerSkill skill) {
        MinerSystem sys = MinerSystem.get();
        MinerChargeState state = sys.stateOf(player);
        int level = sys.minerLevel(player);
        long now = serverTick(player);

        switch (skill) {
            case ORE_SCAN -> tryOreScan(player, state, level, now);
            case TRAP_SCAN -> tryTrapScan(player, state, level, now);
            case TUNNEL -> tryTunnel(sys, player, state, level, now);
            case EVACUATE -> tryEvacuate(player, state, level, now);
            case DECOY -> tryDecoy(player, state, level, now);
            case CHAIN -> toggleSimple(player, state, level, skill, MinerSkills.chainUnlocked(level));
            case AUTO_COLLECT -> toggleSimple(player, state, level, skill, MinerSkills.autoCollectUnlocked(level));
            case AUTO_SMELT -> toggleSimple(player, state, level, skill, MinerSkills.autoSmeltBaseUnlocked(level));
        }
    }

    // ---- 探测类 ----

    private static void tryOreScan(ServerPlayer player, MinerChargeState state, int level, long now) {
        if (!MinerSkills.oreScanUnlocked(level)) {
            notLearned(player);
            return;
        }
        if (!state.cooldownReady(MinerSkill.ORE_SCAN, now)) {
            onCooldown(player, state.cooldownReadyAt(MinerSkill.ORE_SCAN) - now);
            return;
        }
        List<BlockPos> hits = OreScanService.scan(player, level);
        long expire = now + MinerConstants.SCAN_PULSE_TICKS;
        MinerNetwork.sendHighlight(player, new MinerHighlightS2C(MinerHighlightS2C.KIND_ORE, expire, hits));
        state.startCooldown(MinerSkill.ORE_SCAN, now, MinerSkills.oreScanCooldownTicks(level));
        player.sendSystemMessage(Component.translatable("message.miningdim.miner.ore_scan", hits.size()));
    }

    private static void tryTrapScan(ServerPlayer player, MinerChargeState state, int level, long now) {
        if (!MinerSkills.trapScanUnlocked(level)) {
            notLearned(player);
            return;
        }
        if (!state.cooldownReady(MinerSkill.TRAP_SCAN, now)) {
            onCooldown(player, state.cooldownReadyAt(MinerSkill.TRAP_SCAN) - now);
            return;
        }
        List<BlockPos> hits = TrapScanService.scan(player, level);
        long expire = now + MinerConstants.SCAN_PULSE_TICKS;
        MinerNetwork.sendHighlight(player, new MinerHighlightS2C(MinerHighlightS2C.KIND_TRAP, expire, hits));
        state.startCooldown(MinerSkill.TRAP_SCAN, now, MinerSkills.trapScanCooldownTicks(level));
        player.sendSystemMessage(Component.translatable("message.miningdim.miner.trap_scan", hits.size()));
    }

    // ---- 速挖类: 隧道挖 ----

    private static void tryTunnel(MinerSystem sys, ServerPlayer player, MinerChargeState state, int level, long now) {
        if (!MinerSkills.tunnelUnlocked(level)) {
            notLearned(player);
            return;
        }
        if (!state.cooldownReady(MinerSkill.TUNNEL, now)) {
            onCooldown(player, state.cooldownReadyAt(MinerSkill.TUNNEL) - now);
            return;
        }
        if (!inMiningRegion(player)) {
            player.sendSystemMessage(Component.translatable("message.miningdim.miner.not_in_mine"));
            return;
        }
        if (!(player.level() instanceof ServerLevel sl)) {
            return;
        }
        Direction facing = player.getDirection();
        BlockPos origin = player.blockPosition();
        int broken = sys.chainEngine().tunnelBreak(player, origin, sl, facing,
                (pos, block, drops) -> tunnelProduce(player, sl, level, state, pos, block, drops));
        state.startCooldown(MinerSkill.TUNNEL, now, MinerSkills.tunnelCooldownTicks(level));
        player.sendSystemMessage(Component.translatable("message.miningdim.miner.tunnel", broken));
    }

    private static void tunnelProduce(ServerPlayer player, ServerLevel level, int minerLevel, MinerChargeState state,
                                      BlockPos pos, net.minecraft.world.level.block.Block block,
                                      List<net.minecraft.world.item.ItemStack> drops) {
        boolean autoCollect = state.toggled(MinerSkill.AUTO_COLLECT) && MinerSkills.autoCollectUnlocked(minerLevel);
        boolean autoSmelt = state.toggled(MinerSkill.AUTO_SMELT) && MinerSkills.autoSmeltBaseUnlocked(minerLevel);
        // 唯一物化 (destroyBlock 已 dropBlock=false): 入包或破坏点 spawn, 不双发。
        if (autoCollect) {
            AutoCollectSmelt.collect(player, minerLevel, ChainMiningEngine.copyDrops(drops), true, autoSmelt);
        } else {
            ChainMiningEngine.spawnDropsAt(level, pos, drops);
        }
        // 隧道连带产出与连锁同走唯一经济计数回放 chokepoint (反通胀第一道硬约束); 实际入账接线缺口见
        // MinerSystem.replayEconomyOreCount 注释与 notes。
        MinerSystem.get().replayEconomyOreCount(player, block, drops);
    }

    // ---- 生存类: 脱险归途 ----

    private static void tryEvacuate(ServerPlayer player, MinerChargeState state, int level, long now) {
        if (!MinerSkills.evacuateUnlocked(level)) {
            notLearned(player);
            return;
        }
        if (state.evacuating()) {
            state.cancelEvacuateChannel(); // 再按一次取消读条 (开关语义)。
            player.sendSystemMessage(Component.translatable("message.miningdim.miner.evacuate_cancel"));
            return;
        }
        if (!state.cooldownReady(MinerSkill.EVACUATE, now)) {
            onCooldown(player, state.cooldownReadyAt(MinerSkill.EVACUATE) - now);
            return;
        }
        if (!inMiningRegion(player)) {
            player.sendSystemMessage(Component.translatable("message.miningdim.miner.not_in_mine"));
            return;
        }
        state.beginEvacuateChannel(now, player.getX(), player.getY(), player.getZ());
        player.sendSystemMessage(Component.translatable("message.miningdim.miner.evacuate_channel"));
    }

    /**
     * 推进脱险读条 (MinerSystem 每 tick 调用): 移动 (偏离起点超阈值) 即打断; 读条满则执行撤离。
     * 受伤打断由 LivingHurt 路径调用 {@link #interruptEvacuateOnHurt(ServerPlayer)}。
     */
    public static void advanceEvacuateChannel(ServerPlayer player, MinerChargeState state, long now) {
        if (!state.evacuating()) {
            return;
        }
        double moveSq = state.channelMoveDistSq(player.getX(), player.getY(), player.getZ());
        if (moveSq > MinerConstants.EVACUATE_MOVE_BREAK_DIST_SQ) {
            state.cancelEvacuateChannel();
            player.sendSystemMessage(Component.translatable("message.miningdim.miner.evacuate_interrupt"));
            return;
        }
        long elapsed = now - state.evacuateChannelStartTick();
        if (elapsed >= MinerConstants.EVACUATE_CHANNEL_TICKS) {
            executeEvacuate(player, state, now);
        }
    }

    /** 受伤打断脱险读条 (供 MinerSystem 的 LivingHurt 路径调用; 受伤即打断, 不进 CD)。 */
    public static void interruptEvacuateOnHurt(ServerPlayer player) {
        interruptEvacuateOnHurt(player, MinerSystem.get().stateOf(player));
    }

    /**
     * 受伤打断脱险读条的核心 (取 state 入参, 不依赖 {@link MinerSystem} 单例): 仅在读条中时取消 + 提示。
     * 拆出 state 入参重载是为可单测 (GameTest 直接构造 state, 不需子系统已注册)。受伤即打断守第七章护栏。
     */
    static void interruptEvacuateOnHurt(ServerPlayer player, MinerChargeState state) {
        if (state.evacuating()) {
            state.cancelEvacuateChannel();
            player.sendSystemMessage(Component.translatable("message.miningdim.miner.evacuate_interrupt"));
        }
    }

    /** 读条满: 复用回退态送回进入前现场 + 清矿山态 + 实例 refCount-- + 起长 CD。 */
    private static void executeEvacuate(ServerPlayer player, MinerChargeState state, long now) {
        state.cancelEvacuateChannel();
        int level = MinerSystem.get().minerLevel(player);

        Optional<IMiningPlayerData> dataOpt = MiningCapabilities.get(player);
        if (dataOpt.isEmpty()) {
            return; // 无矿山 capability (极端时序): 无回退态可用, 放弃 (不臆造落点)。
        }
        IMiningPlayerData data = dataOpt.get();
        long instanceId = data.currentInstanceId();

        MinecraftServer server = player.server;
        ServerLevel dest = data.hasFallback() ? server.getLevel(data.prevDimension()) : server.overworld();
        if (dest == null) {
            dest = server.overworld();
        }
        BlockPos back = data.hasFallback() ? data.prevPos() : dest.getSharedSpawnPos();

        player.teleportTo(dest, back.getX() + 0.5, back.getY(), back.getZ() + 0.5,
                player.getYRot(), player.getXRot());

        // 实例离开汇聚点 (12.6): refCount-- 经 core 门面 (不 import InstanceManager 实现)。
        if (instanceId != IMiningPlayerData.NO_INSTANCE) {
            InstanceState inst = MiningServices.instanceManager().byId(instanceId).orElse(null);
            if (inst != null) {
                MiningServices.instanceManager().onPlayerLeave(instanceId, player);
            }
        }
        data.clearMiningState();

        state.startCooldown(MinerSkill.EVACUATE, now, MinerSkills.evacuateCooldownTicks(level));
        player.sendSystemMessage(Component.translatable("message.miningdim.miner.evacuate_done"));
    }

    // ---- 生存类: 声东击西 (降压窗口) ----

    private static void tryDecoy(ServerPlayer player, MinerChargeState state, int level, long now) {
        if (!MinerSkills.decoyUnlocked(level)) {
            notLearned(player);
            return;
        }
        if (!state.cooldownReady(MinerSkill.DECOY, now)) {
            onCooldown(player, state.cooldownReadyAt(MinerSkill.DECOY) - now);
            return;
        }
        if (!inMiningRegion(player)) {
            player.sendSystemMessage(Component.translatable("message.miningdim.miner.not_in_mine"));
            return;
        }
        // 复用 spawnFreeze 机制压后方刷怪数秒 (entry capability 的 spawnFreezeUntil; MobPressureSystem 据此不刷怪)。
        Optional<IMiningPlayerData> dataOpt = MiningCapabilities.get(player);
        if (dataOpt.isPresent()) {
            long gameTime = serverTick(player);
            dataOpt.get().setSpawnFreezeUntil(gameTime + MinerConstants.DECOY_SPAWN_FREEZE_TICKS);
        }
        state.startCooldown(MinerSkill.DECOY, now, MinerSkills.decoyCooldownTicks(level));
        player.sendSystemMessage(Component.translatable("message.miningdim.miner.decoy"));
    }

    // ---- 开关 ----

    private static void toggleSimple(ServerPlayer player, MinerChargeState state, int level,
                                     MinerSkill skill, boolean unlocked) {
        if (!unlocked) {
            notLearned(player);
            return;
        }
        boolean on = state.flipToggle(skill);
        player.sendSystemMessage(Component.translatable(
                on ? "message.miningdim.miner.toggle_on" : "message.miningdim.miner.toggle_off",
                Component.translatable("skill.miningdim.miner." + skill.name().toLowerCase())));
    }

    // ---- 工具 ----

    private static long serverTick(ServerPlayer player) {
        return player.serverLevel().getGameTime();
    }

    private static boolean inMiningRegion(ServerPlayer player) {
        Level level = player.level();
        if (!level.dimension().equals(com.miningdim.core.MiningConstants.MINING_LEVEL)) {
            return false;
        }
        return MiningServices.instanceManager().regionAt(player.getBlockX(), player.getBlockZ()) != null;
    }

    private static void notLearned(ServerPlayer player) {
        player.sendSystemMessage(Component.translatable("message.miningdim.miner.not_learned"));
    }

    private static void onCooldown(ServerPlayer player, long ticksLeft) {
        long seconds = Math.max(1L, ticksLeft / MinerConstants.TICKS_PER_SECOND);
        player.sendSystemMessage(Component.translatable("message.miningdim.miner.on_cooldown", seconds));
    }
}
