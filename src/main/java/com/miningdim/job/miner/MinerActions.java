package com.miningdim.job.miner;

import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningServices;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.miner.network.MinerChainPreviewS2C;
import com.miningdim.job.miner.network.MinerHighlightS2C;
import com.miningdim.job.miner.network.MinerNetwork;
import com.miningdim.job.miner.network.MinerStatusS2C;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

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
            case CHAIN -> {
                // 连锁已改"按住激活" (走 MinerChainHoldC2S 心跳驱动 heldUntilTick, 见 handleChainHold), 不再是持久开关。
                // 旧式 CHAIN 开关包 (老客户端 / 误发) 在此明确忽略: 不翻任何开关位, 不做兼容旧 toggle 语义 (二选一取忽略,
                // 避免与新按住态并存产生两套连锁激活来源)。
            }
            case AUTO_COLLECT -> toggleSimple(player, state, level, skill, MinerSkills.autoCollectUnlocked(level));
            case AUTO_SMELT -> toggleSimple(player, state, level, skill, MinerSkills.autoSmeltBaseUnlocked(level));
        }
    }

    // ---- 连锁"按住激活" + 服务端权威预览 (FTB Ultimine 式) ----

    /**
     * 连锁按住上报处理 (MinerChainHoldC2S 服务端 handler 委派, 主线程): held=true 把 heldUntilTick 续到
     * now + {@link MinerConstants#CHAIN_HOLD_GRACE_TICKS} (客户端按住期间心跳周期性重发续期); held=false 立即失效。
     * 仅当"按住激活"态发生翻转时立即补发一次 HUD 状态, 使连锁行"激活/待机"秒级反映 (心跳续期不翻转态则不重复推)。
     *
     * 不在此做等级解锁门控: BreakEvent 侧另有 {@link MinerSkills#chainUnlocked} 硬门 (未解锁存了按住态也不会真连锁),
     * 于此静默丢弃反而使服务端与客户端按住态不一致; 故照存, 无副作用。
     */
    public static void handleChainHold(ServerPlayer player, boolean held) {
        MinerSystem sys = MinerSystem.get();
        MinerChargeState state = sys.stateOf(player);
        long now = serverTick(player);
        boolean wasActive = state.chainHeldActive(now);
        if (held) {
            state.setChainHeld(now + MinerConstants.CHAIN_HOLD_GRACE_TICKS);
        } else {
            state.clearChainHeld();
        }
        if (wasActive != state.chainHeldActive(now)) {
            pushStatus(player, state, sys.minerLevel(player), now); // 激活态翻转即秒刷 HUD 连锁行 (激活/待机)。
        }
    }

    /**
     * 连锁预览请求处理 (MinerChainPreviewC2S 服务端 handler 委派, 主线程): 服务端权威校验后跑 {@link ChainMiningEngine#plan}
     * 回一份候选坐标列表 (以列表长度为计数) 下发。客户端无权算连锁范围, 只按本包渲染轮廓与"连锁 N"。
     *
     * 校验 (任一不满足即不下发, 客户端预览槽随短 expire 天然自清): 连锁已解锁 + 正按住激活 (防未按住的伪造请求洗图) +
     * 在矿洞 region 内 + 准星目标块本身可连锁 ({@link ChainMiningEngine#chainable})。plan 对未揭示陷阱按其伪装的普通矿石处理 (最高优先级防泄密不变量),
     * 故预览计数/位置不泄漏任何陷阱位。budget 取当前充能, 预览范围与真连锁一致。
     */
    public static void handleChainPreview(ServerPlayer player, BlockPos target) {
        MinerSystem sys = MinerSystem.get();
        MinerChargeState state = sys.stateOf(player);
        int level = sys.minerLevel(player);
        long now = serverTick(player);
        if (!MinerSkills.chainUnlocked(level) || !state.chainHeldActive(now) || !inMiningRegion(player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel sl)) {
            return;
        }
        BlockState targetState = sl.getBlockState(target);
        if (!ChainMiningEngine.chainable(sl, target, targetState, player.getMainHandItem())) {
            return; // 目标不可连锁 (非镐可采/档位不足/不可破坏/含 BlockEntity): 不下发, 预览槽随 expire 自清。
        }
        List<BlockPos> planned = ChainMiningEngine.plan(player, sl, target, state.currentCharge());
        long expire = now + MinerConstants.CHAIN_PREVIEW_EXPIRE_TICKS;
        MinerNetwork.sendChainPreview(player, new MinerChainPreviewS2C(expire, planned));
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
        pushStatus(player, state, level, now); // 立即补发, HUD 秒显新 CD, 不等下一次节流。
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
        pushStatus(player, state, level, now); // 立即补发, HUD 秒显新 CD, 不等下一次节流。
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
        // 矿脉时运 (方案 B): 在唯一物化前追加额外掉落 (与连锁同口径), 额外产出随之物化并计入当日矿物计数。
        List<net.minecraft.world.item.ItemStack> withFortune =
                MinerFortune.withFortuneExtras(drops, minerLevel, level.getRandom());
        // 唯一物化 (destroyBlock 已 dropBlock=false): 入包或破坏点 spawn, 不双发。
        if (autoCollect) {
            AutoCollectSmelt.collect(player, minerLevel, withFortune, true, autoSmelt);
        } else {
            ChainMiningEngine.spawnDropsAt(level, pos, withFortune);
        }
        // 隧道连带产出与连锁同走唯一经济计数回放 chokepoint (反通胀第一道硬约束, 含时运额外); 经货币门面回放, 见
        // MinerSystem.replayEconomyOreCount。
        MinerSystem.get().replayEconomyOreCount(player, block, withFortune);
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
        pushStatus(player, state, level, serverTick(player)); // 翻转开关后立即补发, HUD 秒显新开关态。
    }

    // ---- 工具 ----

    private static long serverTick(ServerPlayer player) {
        return player.serverLevel().getGameTime();
    }

    /**
     * 立即补发一次状态 HUD 包 (开关翻转 / 探测 CD 起算后调用): 让客户端 overlay 秒级反映变化, 不必等
     * {@link MinerSystem#onServerTick} 的下一次节流窗口。瞬态态本就权威, 只读同步不持久化。
     */
    private static void pushStatus(ServerPlayer player, MinerChargeState state, int level, long now) {
        MinerNetwork.sendStatus(player, MinerStatusS2C.capture(state, level, now));
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
