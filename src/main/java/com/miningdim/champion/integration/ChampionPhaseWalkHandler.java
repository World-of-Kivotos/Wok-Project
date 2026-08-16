package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionDiagnostics;
import com.miningdim.champion.ChampionPhaseWalkPlan;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 冠军【机动词条·灵体移动 PHASE_WALK】(Stage2 批4 波3 压轴; ChampionStarAffix spec 7.3 穿墙型) 效果施加 (集成层)。
 * 灵体移动是传送家族里唯一的【连续过程】位移: 冠军周期性进入灵体态, 逐 tick 无视碰撞直线漂移穿墙抵近目标, 让纯
 * 原版客户端玩家看得见一团"鬼影"穿墙而来; 出态时按 spec 9.4 保底回退链在一个安全格实体化, 绝不塞墙/悬空掉虚空。
 *
 * <p>状态机 (per-冠军, 两相):
 * <ul>
 *   <li>CHARGING: {@link #scanNearbyChampions} 每 1s 门控推进周期 (有存活目标且缰绳内才推进, 见
 *       {@link ChampionPhaseWalkPlan#shouldAdvanceCycle}); 到点进灵体态。</li>
 *   <li>PHASING: {@link #advancePhasing} 每 tick 手动驱动 —— {@code noPhysics=true} 无碰撞, 沿"当前位 → 目标眼位"
 *       单位向量 × {@value ChampionPhaseWalkPlan#DRIVE_STEP} 格 {@code setPos}, 每 tick 重算方向追踪目标, 拖尾 SOUL/
 *       PORTAL 粒子。出态 (先到先出): 时长耗尽 / 距目标 &lt;=1.5 格 / 目标死亡离线 / 漂移点将超缰绳 -&gt; 回退链实体化。</li>
 * </ul>
 * 两时间尺度与 {@code ChampionBlinkHandler} 同构 (每 tick 推进灵体驱动, 每 1s 门控扫描), 故 {@link #onServerTick}
 * 先驱动在册灵体态再做 1s 扫描。
 *
 * <p>攻击 AI 静默 (最小侵入方案): 灵体态是"穿墙抵近的鬼影", 不得结算近战 (否则漂移途经玩家碰撞箱会隔墙打人)。
 * 读 vanilla AI 后选定的最小侵入手法 = 每 tick {@code mob.setTarget(null)} + {@code navigation.stop()}: 清空索敌目标
 * 使 MeleeAttackGoal 的 {@code canContinueToUse} 立即失效 (无目标可 doHurtTarget), 无需新增受击事件监听或改
 * {@code ChampionAttackHandler} 拦截。代价: 灵体态期间其它按 {@code getTarget} 门控的词条循环 (超速/视觉干扰/战术传送
 * 等) 会短暂冻结, 但灵体态至多 4s 且冠军本就"在途", 冻结自限可接受。漂移方向靠状态里存的 {@code targetId} 逐 tick
 * 重新解析目标位置, 不依赖 {@code getTarget}。出态后不主动恢复目标, 由 vanilla 索敌就近重新锁定 (与闪光出态同理)。
 *
 * <p>noPhysics 恒复位 (泄漏 = 怪永久穿墙掉虚空): 任何【活体冠军】出灵体态的路径都经 {@link #materialize} 的
 * {@code finally} 兜底 {@code noPhysics=false} + 清零 deltaMovement/fallDistance。实体消失 (despawn/区块卸载) 时无实体
 * 句柄可复位, 但 {@code noPhysics} 是 Entity 的瞬态字段 (不入 NBT), 重新加载即自愈 false, 故无泄漏。TTL 清扫只回收
 * CHARGING 条目 (灵体态至多 4s &lt;&lt; 5min TTL, 理论不会过期; 显式跳过 PHASING 杜绝经清扫路径漏复位 noPhysics)。
 *
 * <p>本 handler 只持实例态 (per-冠军状态表随实例存活, 由 {@code ChampionSystem#register} 挂 forgeBus), 无静态账本,
 * 故【无需】onServerStopping 清理 (与 {@code ChampionBlinkHandler} 同; 见集成注意点)。自研 capability 检出, 不触任何
 * top.theillusivec4.champions.*; 数值/几何/回退序位纯逻辑下沉 {@link ChampionPhaseWalkPlan} (dev GameTest 真验)。
 */
public final class ChampionPhaseWalkHandler {

    /** 诊断日志 (skill 类前缀 skill-phasewalk): 入态/出态(回退结果)/强制脱离各一行, 经 shouldTrace 门控只追近玩家的怪。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/skill");

    /** 扫描/周期推进周期 (tick): 1s 扫一次近玩家冠军 (与纯逻辑 {@link ChampionPhaseWalkPlan#SCAN_INTERVAL_TICKS} 对齐)。 */
    private static final int SCAN_INTERVAL_TICKS = (int) ChampionPhaseWalkPlan.SCAN_INTERVAL_TICKS;

    /** 强制脱离眩晕时长 (tick = 2s): Slowness V + Glowing 同窗, 给玩家一个高亮可辨的行动窗口 (spec 9.4 强制脱离补偿)。 */
    private static final int FORCED_STUN_TICKS = 40;

    /** Slowness V 的 amplifier (V = 5 级 = amplifier 4)。 */
    private static final int SLOWNESS_V_AMPLIFIER = 4;

    /** per-冠军灵体循环状态; 冠军死亡摘除 + TTL 清扫双保险 (despawn/卸载不发 LivingDeathEvent 的泄漏兜底)。 */
    private final Map<UUID, PhaseState> stateByChampion = new HashMap<>();

    /** 状态 TTL 清扫周期 (tick): 每 60s 扫一次 (低频, 表通常极小)。 */
    private static final int STATE_SWEEP_INTERVAL_TICKS = 1200;

    /**
     * 状态条目 TTL (tick): 5min 未被触达 (未被门控扫描推进) 即回收。丢态语义安全: 回收后视为"循环从零起", 而周期至多
     * 15s、非门控本就冻结不推进 —— 5min 未触达的冠军必远离玩家或长期脱战/超缰绳, 回收不改任何可观测行为, 只兜内存。
     * PHASING 条目至多 4s 存活, 绝不会过期, 清扫显式跳过 (杜绝经清扫漏复位 noPhysics)。
     */
    private static final long STATE_TTL_TICKS = 6000L;

    /**
     * 每 tick 先驱动在册灵体态 (tick 级 setPos 穿墙漂移), 再每 1s 门控扫描推进周期。灵体驱动需 tick 精度 (0.25 格/tick
     * 的连续鬼影必须逐 tick setPos, 客户端才插值出平滑穿墙); 扫描 (创建/推进 CHARGING 态) 是 1s 节流 (与闪光同源)。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        long nowTick = server.overworld().getGameTime();

        // 每 tick: 驱动在册灵体态 (CHARGING 条目在内部跳过, 廉价; 无 state 直接早退)。
        if (!stateByChampion.isEmpty()) {
            advancePhasing(server, nowTick);
        }

        // 1s 扫描: 门控推进周期 + 到点进灵体态。
        if (server.getTickCount() % SCAN_INTERVAL_TICKS == 0) {
            scanNearbyChampions(server, nowTick);
            if (server.getTickCount() % STATE_SWEEP_INTERVAL_TICKS == 0) {
                sweepStaleStates(nowTick);
            }
        }
    }

    /**
     * 每 tick 驱动在册灵体态: 遍历状态表, 仅处理 PHASING 相位条目 (按维度 O(1) 检出实体)。实体消失/死亡/非 Mob ->
     * 摘除 (noPhysics 瞬态, 重载自愈, 无实体句柄可复位); 存活 -> {@link #driveOrExit} 逐 tick 漂移或出态。
     */
    private void advancePhasing(MinecraftServer server, long nowTick) {
        Iterator<Map.Entry<UUID, PhaseState>> it = stateByChampion.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PhaseState> entry = it.next();
            PhaseState state = entry.getValue();
            if (state.phase != Phase.PHASING) {
                continue; // CHARGING: 由 1s 扫描推进, 此处不动。
            }
            LivingEntity entity = resolve(server, state.dimension, entry.getKey());
            if (entity == null || !entity.isAlive() || !(entity instanceof Mob mob)
                    || !(entity.level() instanceof ServerLevel level)) {
                it.remove(); // despawn/卸载/死亡/非 Mob: 无句柄可复位 noPhysics (瞬态字段重载自愈), 摘除。
                continue;
            }
            driveOrExit(mob, level, state, nowTick);
        }
    }

    /**
     * 灵体态单 tick: 先判出态条件 (先到先出), 满足则回退链实体化; 否则沿"当前位 → 目标眼位"漂移一步。出态条件序:
     * 目标死亡离线 (无目标无从算距离/方向, 首判) -&gt; 时长耗尽 -&gt; 距目标 &lt;=1.5 抵近 -&gt; 漂移点将超缰绳。
     */
    private void driveOrExit(Mob mob, ServerLevel level, PhaseState state, long nowTick) {
        Player targetRaw = level.getPlayerByUUID(state.targetId);
        if (!(targetRaw instanceof ServerPlayer target) || !target.isAlive()) {
            materialize(mob, level, state, null, nowTick); // 目标死亡/离线/换维度: 就地回退链实体化。
            return;
        }
        Vec3 mobPos = mob.position();
        Vec3 targetPos = target.position();
        if (nowTick >= state.phaseEndTick) {
            materialize(mob, level, state, target, nowTick); // 穿墙时长耗尽。
            return;
        }
        if (ChampionPhaseWalkPlan.reachedTarget(mobPos.distanceTo(targetPos))) {
            materialize(mob, level, state, target, nowTick); // 已抵近 (<=1.5 格)。
            return;
        }
        // 沿目标眼位方向漂移一步 (每 tick 重算方向追踪目标)。
        Vec3 eye = target.getEyePosition();
        double[] next = ChampionPhaseWalkPlan.driveStep(mobPos.x, mobPos.y, mobPos.z, eye.x, eye.y, eye.z);
        Vec3 nextPos = new Vec3(next[0], next[1], next[2]);
        if (!ChampionPhaseWalkPlan.withinLeash(nextPos.distanceTo(targetPos))) {
            materialize(mob, level, state, target, nowTick); // 漂移点将超缰绳: 不穿出缰绳, 就地实体化。
            return;
        }

        // 驱动: 静默 AI (清目标使近战 goal 失效) + 无碰撞 setPos + 清速度防重力累积, 面向目标, 拖尾鬼影粒子。
        mob.setTarget(null);
        mob.getNavigation().stop();
        mob.setDeltaMovement(Vec3.ZERO);
        mob.setPos(nextPos.x, nextPos.y, nextPos.z);
        mob.fallDistance = 0.0F;
        mob.lookAt(target, 180.0F, 180.0F);
        emitPhaseTrail(level, mob, nextPos);
    }

    /**
     * 每秒扫近玩家冠军 (与 {@code ChampionBlinkHandler} 同范式): 按玩家 AABB 扫 + 自研 capability 检出装配灵体移动
     * 的冠军 (命令召唤 + 自然刷一视同仁), 门控通过者推进周期; 多玩家同看一冠军本轮只结算一次。
     */
    private void scanNearbyChampions(MinecraftServer server, long nowTick) {
        for (ChampionProximityScanner.Sighting sighting : ChampionProximityScanner.sightings(server)) {
            if (!sighting.entity().isAlive()) {
                continue; // 快照按 tick 复用, 同 tick 更早的 handler 可能已致死: 存活性逐条重查。
            }
            applyPhaseWalkScan(sighting.entity(), nowTick);
        }
    }

    /** 对一只实体 (若是装配灵体移动的本工程冠军) 推进周期; 到点进灵体态。 */
    private void applyPhaseWalkScan(LivingEntity entity, long nowTick) {
        MiningChampionData champ = MiningChampions.get(entity).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 非本工程冠军。
        }
        AffixQuality quality = champ.quality(AffixDef.PHASE_WALK);
        if (quality == null) {
            return; // 未装配灵体移动: 不建状态 (防为无关冠军泄漏 state)。
        }

        PhaseState existing = stateByChampion.get(entity.getUUID());
        if (existing != null && existing.phase == Phase.PHASING) {
            return; // 灵体态由每 tick 驱动独占, 扫描不并发推进其周期。
        }

        ServerPlayer target = resolveTarget(entity);
        // 门控: 有存活目标且在缰绳内才推进 (丢目标/超缰绳冻结; 无目标时距离参数不参与, 传 MAX 占位)。
        double distance = target == null ? Double.MAX_VALUE : entity.distanceTo(target);
        if (!ChampionPhaseWalkPlan.shouldAdvanceCycle(target != null, distance)) {
            return; // 冻结不耗周期: 不新建/触达 state, 已有 CHARGING 态原样保留 (elapsed 冻结)。
        }
        if (!(entity instanceof Mob mob) || !(entity.level() instanceof ServerLevel level)) {
            return; // 冠军 capability 只挂 Mob, 且扫描来自 ServerLevel; 防御性早退。
        }

        PhaseState state = stateByChampion.computeIfAbsent(entity.getUUID(),
                k -> new PhaseState(level.dimension()));
        state.lastTouchedTick = nowTick;
        state.elapsedCycleTicks = ChampionPhaseWalkPlan.advanceCycle(state.elapsedCycleTicks);
        if (!ChampionPhaseWalkPlan.cycleReady(state.elapsedCycleTicks, quality)) {
            return; // 未到周期 (充能中)。
        }
        // 到点: 清零重计 (周期照走不补偿) + 进灵体态。
        state.elapsedCycleTicks = 0L;
        enterPhasing(mob, level, target, quality, state, nowTick);
    }

    /**
     * 进入灵体态: 先记 lastValidPos = 当前方块位 (spec 9.4 回退链第 3 序位; 入态前的合法立足点), 再置 PHASING 相位
     * + noPhysics 无碰撞 + 停导航 + 清索敌 (静默近战) + 清速度, 播入态音 + 喷入态粒子。逐 tick 漂移由后续
     * {@link #advancePhasing} 驱动 (本 tick 不漂移, 与闪光预兆同 —— 建态那 tick 的每 tick 驱动已在扫描前跑过)。
     */
    private void enterPhasing(Mob mob, ServerLevel level, ServerPlayer target,
                              AffixQuality quality, PhaseState state, long nowTick) {
        long duration = ChampionPhaseWalkPlan.phaseDurationTicks(quality);
        state.lastValidPos = mob.blockPosition();
        state.phase = Phase.PHASING;
        state.targetId = target.getUUID();
        state.phaseEndTick = nowTick + duration;
        state.lastTouchedTick = nowTick;

        mob.noPhysics = true;
        mob.getNavigation().stop();
        mob.setTarget(null);
        mob.setDeltaMovement(Vec3.ZERO);

        Vec3 pos = mob.position();
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.9F, 0.6F);
        emitPhaseTrail(level, mob, pos);

        if (ChampionDiagnostics.shouldTrace(mob)) {
            LOGGER.info("skill-phasewalk enter {} tier{} target={} lastValid=[{},{},{}] dur={}t",
                    mob.getType().getDescriptionId(), quality, target.getGameProfile().getName(),
                    state.lastValidPos.getX(), state.lastValidPos.getY(), state.lastValidPos.getZ(),
                    duration);
        }
    }

    /**
     * 出灵体态实体化: 跑 spec 9.4 回退链, 【无论走哪条分支或抛异常】finally 恒复位 noPhysics=false + 清速度/fallDistance
     * + 停导航 + 回 CHARGING 重充能。noPhysics 泄漏 = 怪永久穿墙掉虚空, 故复位放 finally 兜死。target 可为 null
     * (目标死亡离线出态), 此时环搜跳过 (无环心)。
     */
    private void materialize(Mob mob, ServerLevel level, PhaseState state, ServerPlayer target, long nowTick) {
        try {
            runFallbackChain(mob, level, state, target, nowTick);
        } finally {
            mob.noPhysics = false;
            mob.setDeltaMovement(Vec3.ZERO);
            mob.fallDistance = 0.0F;
            mob.getNavigation().stop();
            state.phase = Phase.CHARGING;
            state.elapsedCycleTicks = 0L;
            state.targetId = null;
            state.lastValidPos = null;
            state.phaseEndTick = Long.MIN_VALUE;
            state.lastTouchedTick = nowTick;
        }
    }

    /**
     * 回退链执行 (spec 9.4 强制按序): handler 侧算三个容身 boolean (世界查询), 交纯逻辑
     * {@link ChampionPhaseWalkPlan#resolveFallback} 裁决序位, 再据枚举执行对应实体化。禁塞墙 (noCollision 闸)、禁悬空
     * 孤儿 (evaluateLanding 的向下落脚柱扫描保证有底)、不穿出缰绳 (环搜半径 &lt;=6 &lt; 缰绳)。
     */
    private void runFallbackChain(Mob mob, ServerLevel level, PhaseState state, ServerPlayer target, long nowTick) {
        BlockPos currentBlock = mob.blockPosition();
        boolean currentContainable = level.noCollision(mob, mob.getBoundingBox())
                && KnockbackSafetyGuard.evaluateLanding(level, currentBlock).outcome()
                == KnockbackSafetyGuard.Outcome.SAFE;

        // 环搜 (只在有目标时; 由近及远取首个 [SAFE + noCollision + 距目标 >=1.5] 落点)。一次搜出即得 boolean + 落点, 不重复。
        Optional<BlockPos> ringLanding = target == null
                ? Optional.empty()
                : findRingLanding(level, mob, target);
        boolean ringHasSolution = ringLanding.isPresent();

        boolean lastValidContainable = state.lastValidPos != null
                && noCollisionAt(level, mob, state.lastValidPos);

        ChampionPhaseWalkPlan.FallbackOutcome outcome =
                ChampionPhaseWalkPlan.resolveFallback(currentContainable, ringHasSolution, lastValidContainable);

        switch (outcome) {
            case IN_PLACE -> {
                // 就地: 当前位安全且容身, 无需瞬移 (finally 清 noPhysics 后原地恢复物理)。
                emitMaterialize(level, mob.position());
            }
            case RING -> {
                BlockPos landing = ringLanding.orElseThrow(); // outcome=RING 蕴含 present, orElseThrow 防御未来改序位引入的漏洞。
                teleportToBlock(mob, landing);
                emitMaterialize(level, mob.position());
            }
            case LAST_VALID -> {
                teleportToBlock(mob, state.lastValidPos);
                emitMaterialize(level, mob.position());
            }
            case FORCED -> {
                // 强制脱离: 强塞回 lastValidPos (不校验) + 2s Slowness V + Glowing 高亮 + 痛击音, 给玩家行动窗口。
                teleportToBlock(mob, state.lastValidPos);
                mob.addEffect(new MobEffectInstance(
                        MobEffects.MOVEMENT_SLOWDOWN, FORCED_STUN_TICKS, SLOWNESS_V_AMPLIFIER, false, true));
                mob.addEffect(new MobEffectInstance(
                        MobEffects.GLOWING, FORCED_STUN_TICKS, 0, false, false));
                Vec3 pos = mob.position();
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 0.8F, 0.7F);
                emitForcedParticles(level, pos);
            }
        }

        if (ChampionDiagnostics.shouldTrace(mob)) {
            Vec3 pos = mob.position();
            LOGGER.info("skill-phasewalk exit {} outcome={} at=[{},{},{}] (current={} ring={} lastValid={})",
                    mob.getType().getDescriptionId(), outcome,
                    String.format("%.1f", pos.x), String.format("%.1f", pos.y), String.format("%.1f", pos.z),
                    currentContainable, ringHasSolution, lastValidContainable);
        }
    }

    /**
     * 以目标为环心由近及远环搜首个合法落点: 每候选补 Y (目标脚 Y) + 过 {@link KnockbackSafetyGuard#evaluateLanding}
     * SAFE + {@link #noCollisionAt} 不塞墙 + {@link ChampionPhaseWalkPlan#meetsMinLandingDistance} 身位下限 (防御,
     * 环候选恒满足)。首个通过即返回。
     */
    private Optional<BlockPos> findRingLanding(ServerLevel level, Mob mob, ServerPlayer target) {
        double tx = target.getX();
        double tz = target.getZ();
        int footY = Mth.floor(target.getY());
        for (double[] c : ChampionPhaseWalkPlan.ringCandidates(tx, tz)) {
            double dx = c[0] - tx;
            double dz = c[1] - tz;
            double horizDist = Math.sqrt(dx * dx + dz * dz);
            if (!ChampionPhaseWalkPlan.meetsMinLandingDistance(horizDist)) {
                continue; // 身位下限硬闸 (环候选恒 >=1.5; 防御性)。
            }
            BlockPos pos = BlockPos.containing(c[0], footY, c[1]);
            if (KnockbackSafetyGuard.evaluateLanding(level, pos).outcome() != KnockbackSafetyGuard.Outcome.SAFE) {
                continue;
            }
            if (!noCollisionAt(level, mob, pos)) {
                continue; // 禁塞墙: 该格容不下冠军实体 AABB。
            }
            return Optional.of(pos);
        }
        return Optional.empty();
    }

    /** 冠军当前攻击目标 (须为存活 ServerPlayer, 否则 null): 非 Mob / 无目标 / 目标非玩家 / 目标死亡均返 null。 */
    private static ServerPlayer resolveTarget(LivingEntity entity) {
        if (!(entity instanceof Mob mob)) {
            return null;
        }
        if (!(mob.getTarget() instanceof ServerPlayer target)) {
            return null;
        }
        if (!target.isAlive()) {
            return null;
        }
        return target;
    }

    /** 冠军死亡: 摘 per-冠军状态防泄漏, 并防御性复位 noPhysics (死亡结算 tick 内实体仍存, 免极端时序残留穿墙态)。 */
    @SubscribeEvent
    public void onChampionDeath(LivingDeathEvent event) {
        PhaseState removed = stateByChampion.remove(event.getEntity().getUUID());
        if (removed != null && removed.phase == Phase.PHASING) {
            event.getEntity().noPhysics = false;
        }
    }

    /**
     * 回收 TTL 内未被触达的 CHARGING 状态条目 (语义安全性见 {@link #STATE_TTL_TICKS})。显式跳过 PHASING —— 灵体态至多
     * 4s &lt;&lt; TTL 绝不过期, 跳过杜绝经清扫路径漏复位 noPhysics (清扫只删表项, 不会跑 materialize 的复位)。
     */
    private void sweepStaleStates(long nowTick) {
        // MIN_VALUE (理论不出现: 建条目点即刻刷触达) 显式视为过期, 防减法溢出漏回收。
        stateByChampion.values().removeIf(state -> state.phase == Phase.CHARGING
                && (state.lastTouchedTick == Long.MIN_VALUE || nowTick - state.lastTouchedTick > STATE_TTL_TICKS));
    }

    /** 瞬移到方块中心 (脚位 Y 取方块 Y; 与闪光 executeBlink 同)。 */
    private static void teleportToBlock(Mob mob, BlockPos pos) {
        mob.teleportTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
    }

    /**
     * 冠军实体 AABB 摆到某方块中心脚位是否无碰撞 (禁塞墙判定)。按冠军当前体宽/体高在候选脚位构造 AABB, 过
     * {@code level.noCollision(mob, box)} (排除自身); true = 容得下。手工构 AABB (而非 makeBoundingBox) 避免体型代理
     * 词条改 pose 时的 API 分歧, 只依赖 getBbWidth/getBbHeight 两个稳定读。
     */
    private static boolean noCollisionAt(ServerLevel level, Mob mob, BlockPos pos) {
        double cx = pos.getX() + 0.5D;
        double cy = pos.getY();
        double cz = pos.getZ() + 0.5D;
        double halfW = mob.getBbWidth() / 2.0D;
        double height = mob.getBbHeight();
        AABB box = new AABB(cx - halfW, cy, cz - halfW, cx + halfW, cy + height, cz + halfW);
        return level.noCollision(mob, box);
    }

    /** 灵体拖尾鬼影粒子 (spec 9A.6 原版可见原语): SOUL + PORTAL 双层, 在冠军身体中段喷, 让纯原版客户端看得见穿墙的鬼影。 */
    private static void emitPhaseTrail(ServerLevel level, Mob mob, Vec3 pos) {
        double bodyY = pos.y + mob.getBbHeight() * 0.5D;
        level.sendParticles(ParticleTypes.SOUL, pos.x, bodyY, pos.z, 4, 0.2D, 0.3D, 0.2D, 0.01D);
        level.sendParticles(ParticleTypes.PORTAL, pos.x, bodyY, pos.z, 6, 0.25D, 0.35D, 0.25D, 0.02D);
    }

    /** 实体化浮现 poof (spec 9A.6): 落点一簇 POOF, 标记"鬼影凝实"。 */
    private static void emitMaterialize(ServerLevel level, Vec3 pos) {
        level.sendParticles(ParticleTypes.POOF, pos.x, pos.y + 0.5D, pos.z, 16, 0.3D, 0.4D, 0.3D, 0.02D);
    }

    /** 强制脱离预兆 (spec 9A.6): 眩晕落点喷 ANGRY_VILLAGER + LARGE_SMOKE, 配合 Glowing 高亮把行动窗口喊给玩家。 */
    private static void emitForcedParticles(ServerLevel level, Vec3 pos) {
        level.sendParticles(ParticleTypes.ANGRY_VILLAGER, pos.x, pos.y + 1.2D, pos.z, 6, 0.3D, 0.3D, 0.3D, 0.0D);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y + 0.4D, pos.z, 12, 0.3D, 0.3D, 0.3D, 0.01D);
    }

    /** 按状态记录的维度检出在册冠军实体 (维度未加载/实体不在返 null; UUID -&gt; 实体为 O(1) 表查, 照闪光/自我修复范式)。 */
    private static LivingEntity resolve(MinecraftServer server, ResourceKey<Level> dimension, UUID id) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            return null;
        }
        Entity found = level.getEntity(id);
        return found instanceof LivingEntity living ? living : null;
    }

    /** 灵体循环相位: 充能 (门控推进周期) / 灵体态 (每 tick 无碰撞漂移穿墙, 至多穿墙时长)。 */
    private enum Phase {
        CHARGING,
        PHASING
    }

    /**
     * per-冠军灵体状态: 相位 + 已累加循环 tick (门控扫描推进, 到点清零) + 所在维度 (灵体态每 tick O(1) 检出实体) +
     * 灵体态目标玩家 (逐 tick 重解析追踪方向) + 灵体态结束 tick (穿墙时长到点) + 入态前记录位 lastValidPos (回退链
     * 第 3 序位) + 最后触达 tick (TTL 清扫依据)。
     */
    private static final class PhaseState {
        private final ResourceKey<Level> dimension;
        private Phase phase = Phase.CHARGING;
        private long elapsedCycleTicks = 0L;
        private UUID targetId = null;
        private long phaseEndTick = Long.MIN_VALUE;
        private BlockPos lastValidPos = null;
        private long lastTouchedTick = Long.MIN_VALUE;

        private PhaseState(ResourceKey<Level> dimension) {
            this.dimension = dimension;
        }
    }
}
