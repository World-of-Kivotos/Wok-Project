package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionBlinkPlan;
import com.miningdim.champion.ChampionDiagnostics;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 冠军【机动词条·闪光 BLINK】(Stage2 批4 波1; ChampionStarAffix spec 7.2 抵近型反风筝瞬移) 效果施加 (集成层)。
 * 冠军对当前攻击目标玩家周期性抵近瞬移到其身后 2-3 格, 落点前 0.5s 粒子预兆 (可躲/可拉开), 预兆到点瞬移 + 两端
 * poof + 传送音 + lookAt 玩家。数值/门控/落点环几何纯逻辑下沉 {@link ChampionBlinkPlan} (dev GameTest 真验), 本
 * handler 只做真服侧: 实体检出/目标缰绳查询/逐候选过 {@link KnockbackSafetyGuard#evaluateLanding} 选点/预兆表现/
 * mob.teleportTo。
 *
 * <p>契约边界 (关键): 闪光是【冠军自身位移】(把冠军挪到玩家旁), 不对玩家施加任何位移/控制 —— 故不涉及
 * {@link PlayerLandingProtection} (那是位移玩家的效果动手前的自查/授保护) 与 {@code PlayerControlAggregator}
 * (控制时长入闸)。落点安全仅经 {@link KnockbackSafetyGuard} 单点裁决冠军自己的落脚柱, 避免瞬移进岩浆/虚空。
 *
 * <p>状态机 (per-冠军, 两相): CHARGING (门控推进周期) -&gt; 到点选到安全落点则转 PREMONITION (预兆锁定落点, 0.5s 后
 * 瞬移) -&gt; 瞬移毕回 CHARGING。两个时间尺度:
 * <ul>
 *   <li>{@link #onServerTick} 每 tick 先推进在册【预兆】(0.5s = {@value #PREMONITION_TICKS}tick 需 tick 级精度, 逐个
 *       按维度 O(1) 检出实体; charging 条目在此跳过);</li>
 *   <li>再每 {@value #SCAN_INTERVAL_TICKS}tick(1s) 按玩家 AABB 扫近处冠军 (与 {@code ChampionVisualDisruptionHandler}
 *       同范式, 覆盖命令召唤 + 自然刷), 门控通过者推进周期; 到点逐候选选安全落点起预兆 (全拒则本周期放弃, 照进下周期)。</li>
 * </ul>
 * 预兆期落点【不追踪玩家移动】(锁定初次算出的落点, 给玩家可躲/可拉开的反制窗)。
 *
 * <p>per-冠军状态双清 (与 {@code ChampionSelfEffectHandler} 同纪律): 冠军死亡摘除 + TTL 清扫兜底 —— 冠军未设
 * persistenceRequired, despawn/区块卸载不发 LivingDeathEvent, 只靠死亡清理会泄漏。实例状态随本 handler 实例存活
 * (由 {@code ChampionSystem#register} 挂 forgeBus), 无静态账本故无需 onServerStopping 清理。
 */
public final class ChampionBlinkHandler {

    /** 诊断日志: 批4 波1 闪光真服首验用 (预兆起/瞬移毕/全拒放弃各一行, shouldTrace 门控只追近玩家的怪)。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/skill");

    /** 扫描/周期推进周期 (tick): 1s 扫一次近玩家冠军 (与纯逻辑 {@link ChampionBlinkPlan#SCAN_INTERVAL_TICKS} 对齐)。 */
    private static final int SCAN_INTERVAL_TICKS = (int) ChampionBlinkPlan.SCAN_INTERVAL_TICKS;

    /** 预兆时长 (tick): spec 7.2 到达前 0.5s 落点粒子预兆; 预兆期落点锁定不追踪玩家, 给玩家可躲窗。 */
    private static final int PREMONITION_TICKS = 10;

    /** per-冠军闪光循环状态; 冠军死亡摘除 + TTL 清扫双保险 (despawn/卸载不发死亡事件的泄漏兜底)。 */
    private final Map<UUID, BlinkState> stateByChampion = new HashMap<>();

    /** 状态 TTL 清扫周期 (tick): 每 60s 扫一次 (低频, 表通常极小)。 */
    private static final int STATE_SWEEP_INTERVAL_TICKS = 1200;

    /**
     * 状态条目 TTL (tick): 5min 未被触达 (未被门控扫描推进) 即回收。丢态语义安全: 回收后视为"循环从零起", 而周期至多
     * 9s、非门控本就冻结不推进 —— 5min 未触达的冠军必远离玩家或长期脱战/超缰绳, 回收不改任何可观测行为, 只兜内存。
     */
    private static final long STATE_TTL_TICKS = 6000L;

    /**
     * 每 tick 先推进在册预兆 (tick 级), 再每 1s 门控扫描推进周期。预兆需 tick 精度: 0.5s 窗内逐 tick 喷落点粒子,
     * 到点即瞬移; 扫描 (创建/推进 charging 态) 是 1s 节流 (与视觉干扰同源, 周期 4~9s 无需亚秒精度)。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        long nowTick = server.overworld().getGameTime();

        // 每 tick: 推进在册预兆 (charging 条目在内部跳过, 廉价; 无 state 直接早退)。
        if (!stateByChampion.isEmpty()) {
            advancePremonitions(server, nowTick);
        }

        // 1s 扫描: 门控推进周期 + 到点起预兆。
        if (server.getTickCount() % SCAN_INTERVAL_TICKS == 0) {
            scanNearbyChampions(server, nowTick);
            if (server.getTickCount() % STATE_SWEEP_INTERVAL_TICKS == 0) {
                sweepStaleStates(nowTick);
            }
        }
    }

    /**
     * 每 tick 推进在册预兆: 遍历状态表, 仅处理 PREMONITION 相位条目 (按维度 O(1) 检出实体)。实体消失/死亡 -&gt; 摘除
     * (预兆作废); 未到点 -&gt; 逐 tick 喷落点预兆粒子 (锁定落点, 不追踪玩家); 到点 -&gt; 瞬移 + 两端特效 + lookAt, 回 CHARGING。
     */
    private void advancePremonitions(MinecraftServer server, long nowTick) {
        Iterator<Map.Entry<UUID, BlinkState>> it = stateByChampion.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, BlinkState> entry = it.next();
            BlinkState state = entry.getValue();
            if (state.phase != Phase.PREMONITION) {
                continue; // charging: 由 1s 扫描推进, 此处不动。
            }
            LivingEntity entity = resolve(server, state.dimension, entry.getKey());
            if (entity == null || !entity.isAlive() || !(entity instanceof Mob mob)
                    || !(entity.level() instanceof ServerLevel level)) {
                it.remove(); // despawn/卸载/死亡/非 Mob: 预兆作废, 摘除。
                continue;
            }
            if (nowTick >= state.premonitionEndTick) {
                executeBlink(mob, level, state, nowTick);
            } else {
                emitPremonitionParticles(level, state.landing);
            }
        }
    }

    /**
     * 每秒扫近玩家冠军 (与 {@code ChampionVisualDisruptionHandler} 同范式): 按玩家 AABB 扫 + 自研 capability 检出装配
     * 闪光的冠军 (命令召唤 + 自然刷一视同仁), 门控通过者推进周期; 多玩家同看一冠军本轮只结算一次。
     */
    private void scanNearbyChampions(MinecraftServer server, long nowTick) {
        for (ChampionProximityScanner.Sighting sighting : ChampionProximityScanner.sightings(server)) {
            if (!sighting.entity().isAlive()) {
                continue; // 快照按 tick 复用, 同 tick 更早的 handler 可能已致死: 存活性逐条重查。
            }
            applyBlinkScan(sighting.entity(), nowTick);
        }
    }

    /** 对一只实体 (若是装配闪光的本工程冠军) 推进周期; 到点选安全落点起预兆。 */
    private void applyBlinkScan(LivingEntity entity, long nowTick) {
        MiningChampionData champ = MiningChampions.get(entity).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 非本工程冠军。
        }
        AffixQuality quality = champ.quality(AffixDef.BLINK);
        if (quality == null) {
            return; // 未装配闪光: 不建状态 (防为无关冠军泄漏 state)。
        }

        BlinkState existing = stateByChampion.get(entity.getUUID());
        if (existing != null && existing.phase == Phase.PREMONITION) {
            return; // 预兆期由每 tick 推进独占, 扫描不并发动其周期 (防御: 预兆 <1s 通常不撞 1s 扫描)。
        }

        ServerPlayer target = resolveTarget(entity);
        // 门控: 有存活目标且在缰绳内才推进 (丢目标/超缰绳冻结; 无目标时距离参数不参与, 传 MAX 占位)。
        double distance = target == null ? Double.MAX_VALUE : entity.distanceTo(target);
        if (!ChampionBlinkPlan.shouldAdvanceCycle(target != null, distance)) {
            return; // 冻结不耗周期: 不新建/触达 state, 已有 charging 态原样保留 (elapsed 冻结)。
        }
        if (!(entity instanceof Mob mob) || !(entity.level() instanceof ServerLevel level)) {
            return; // 冠军 capability 只挂 Mob, 且扫描来自 ServerLevel; 防御性早退。
        }

        BlinkState state = stateByChampion.computeIfAbsent(entity.getUUID(),
                k -> new BlinkState(level.dimension()));
        state.lastTouchedTick = nowTick;
        state.elapsedCycleTicks = ChampionBlinkPlan.advanceCycle(state.elapsedCycleTicks);
        if (!ChampionBlinkPlan.cycleReady(state.elapsedCycleTicks, quality)) {
            return; // 未到周期 (充能中)。
        }
        // 到点: 清零重计 (无论选点成败, 周期照走不补偿, 单一权威在 handler 不复制纯逻辑)。
        state.elapsedCycleTicks = 0L;
        beginPremonitionOrSkip(mob, level, target, quality, state, nowTick);
    }

    /**
     * 到点选落点起预兆: 以目标为中心在背后优先环上取候选, 逐个过 {@link KnockbackSafetyGuard#evaluateLanding} +
     * 硬排除距玩家 &lt;1 格 (禁近, {@link ChampionBlinkPlan#tooClose}); 首个 SAFE 即锁为预兆落点; 全拒则本周期放弃
     * (不进预兆, 照常进入下个周期, 防岩浆房每 tick 重试)。
     */
    private void beginPremonitionOrSkip(Mob mob, ServerLevel level, ServerPlayer target,
                                        AffixQuality quality, BlinkState state, long nowTick) {
        Vec3 targetPos = target.position();
        double baseAngle = behindAngle(target);
        int footY = Mth.floor(target.getY());
        List<double[]> candidates = ChampionBlinkPlan.ringCandidates(targetPos.x, targetPos.z, baseAngle);

        BlockPos chosen = null;
        for (double[] c : candidates) {
            if (ChampionBlinkPlan.tooClose(targetPos.x, targetPos.z, c[0], c[1])) {
                continue; // 禁近硬闸 (环候选常态不触发; 防御性)。
            }
            BlockPos pos = BlockPos.containing(c[0], footY, c[1]);
            if (KnockbackSafetyGuard.evaluateLanding(level, pos).outcome() == KnockbackSafetyGuard.Outcome.SAFE) {
                chosen = pos;
                break;
            }
        }

        if (chosen == null) {
            // 全候选被拒 (岩浆房/悬崖边): 本周期放弃, 保持 CHARGING 进下周期 (elapsed 已清零)。
            if (ChampionDiagnostics.shouldTrace(mob)) {
                LOGGER.info("blink give-up {} tier{} (no safe landing on ring)",
                        mob.getType().getDescriptionId(), quality);
            }
            return;
        }

        state.phase = Phase.PREMONITION;
        state.landing = chosen;
        state.targetId = target.getUUID();
        state.premonitionEndTick = nowTick + PREMONITION_TICKS;
        emitPremonitionParticles(level, chosen);      // 预兆首帧落点标记 (后续每 tick 由 advancePremonitions 续喷)。
        level.playSound(null, chosen.getX() + 0.5D, chosen.getY() + 0.5D, chosen.getZ() + 0.5D,
                SoundEvents.ENDERMAN_STARE, SoundSource.HOSTILE, 0.6F, 1.2F);
        if (ChampionDiagnostics.shouldTrace(mob)) {
            LOGGER.info("blink premon {} tier{} target={} landing=[{},{},{}] in={}t",
                    mob.getType().getDescriptionId(), quality, target.getGameProfile().getName(),
                    chosen.getX(), chosen.getY(), chosen.getZ(), PREMONITION_TICKS);
        }
    }

    /**
     * 预兆到点: 冠军瞬移到锁定落点 (方块中心), 两端 poof + 传送音, 传送后 lookAt 玩家 (若仍在), 回 CHARGING 重充能。
     * 落点已在起预兆时经 {@link KnockbackSafetyGuard} 裁 SAFE, 此处不复判 (预兆期锁定, 世界期间被填埋属极端边界,
     * 与其它位移词条同交守卫单点权威, 不在本词条二次兜)。
     */
    private void executeBlink(Mob mob, ServerLevel level, BlinkState state, long nowTick) {
        Vec3 from = mob.position();
        double lx = state.landing.getX() + 0.5D;
        double ly = state.landing.getY();
        double lz = state.landing.getZ() + 0.5D;

        emitPoof(level, from.x, from.y + mob.getBbHeight() * 0.5D, from.z);
        mob.teleportTo(lx, ly, lz);
        mob.getNavigation().stop(); // 瞬移后停旧路径, 由原生索敌重新贴脸。
        emitPoof(level, lx, ly + mob.getBbHeight() * 0.5D, lz);
        level.playSound(null, lx, ly, lz, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 1.0F);

        Player targetPlayer = level.getPlayerByUUID(state.targetId);
        if (targetPlayer != null && targetPlayer.isAlive()) {
            mob.lookAt(targetPlayer, 180.0F, 180.0F); // 抵近后面向玩家 (180 步长 = 直接朝向)。
        }

        // 回 CHARGING 重新充能 (下个周期再抵近)。
        state.phase = Phase.CHARGING;
        state.landing = null;
        state.targetId = null;
        state.elapsedCycleTicks = 0L;
        state.premonitionEndTick = Long.MIN_VALUE;
        state.lastTouchedTick = nowTick;

        if (ChampionDiagnostics.shouldTrace(mob)) {
            LOGGER.info("blink teleport {} to [{},{},{}]",
                    mob.getType().getDescriptionId(),
                    String.format("%.1f", lx), String.format("%.1f", ly), String.format("%.1f", lz));
        }
    }

    /** 冠军死亡: 摘 per-冠军状态防泄漏。 */
    @SubscribeEvent
    public void onChampionDeath(LivingDeathEvent event) {
        stateByChampion.remove(event.getEntity().getUUID());
    }

    /** 回收 TTL 内未被触达 (未被门控扫描推进) 的状态条目 (语义安全性见 {@link #STATE_TTL_TICKS})。 */
    private void sweepStaleStates(long nowTick) {
        // MIN_VALUE (理论不出现: 建条目点即刻刷触达) 显式视为过期, 防减法溢出漏回收。
        stateByChampion.values().removeIf(state -> state.lastTouchedTick == Long.MIN_VALUE
                || nowTick - state.lastTouchedTick > STATE_TTL_TICKS);
    }

    /**
     * 玩家背后方向的水平角 (弧度; 供 {@link ChampionBlinkPlan#ringCandidates} 的 baseAngle): MC 前向水平分量 =
     * (-sin(yaw), cos(yaw)), 背后取其反 = (sin(yaw), -cos(yaw)); atan2 转角度使 offset=0 候选恰落玩家正背后。
     */
    private static double behindAngle(ServerPlayer target) {
        double yawRad = Math.toRadians(target.getYRot());
        double behindX = Math.sin(yawRad);
        double behindZ = -Math.cos(yawRad);
        return Math.atan2(behindZ, behindX);
    }

    /** 预兆落点标记粒子 (spec 9A.6 原版可见原语): 落点上方喷传送门粒子, 让玩家肉眼预判抵近点可躲/可拉开。 */
    private static void emitPremonitionParticles(ServerLevel level, BlockPos landing) {
        level.sendParticles(ParticleTypes.PORTAL,
                landing.getX() + 0.5D, landing.getY() + 0.5D, landing.getZ() + 0.5D,
                12, 0.4D, 0.5D, 0.4D, 0.05D);
    }

    /** 瞬移两端 poof (spec 9A.6): 起点消散 + 落点浮现, 纯原版客户端可见。 */
    private static void emitPoof(ServerLevel level, double x, double y, double z) {
        level.sendParticles(ParticleTypes.POOF, x, y, z, 16, 0.3D, 0.4D, 0.3D, 0.02D);
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

    /** 按状态记录的维度检出在册冠军实体 (维度未加载/实体不在返 null; UUID -&gt; 实体为 O(1) 表查, 照自我修复范式)。 */
    private static LivingEntity resolve(MinecraftServer server, ResourceKey<Level> dimension, UUID id) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            return null;
        }
        Entity found = level.getEntity(id);
        return found instanceof LivingEntity living ? living : null;
    }

    /** 闪光循环相位: 充能 (门控推进周期) / 预兆 (锁定落点 0.5s 后瞬移)。 */
    private enum Phase {
        CHARGING,
        PREMONITION
    }

    /**
     * per-冠军闪光状态: 相位 + 已累加循环 tick (门控扫描推进, 到点清零) + 所在维度 (预兆期每 tick O(1) 检出实体) +
     * 预兆锁定落点 + 预兆目标玩家 (瞬移后 lookAt) + 预兆到点 tick + 最后触达 tick (TTL 清扫依据)。
     */
    private static final class BlinkState {
        private final ResourceKey<Level> dimension;
        private Phase phase = Phase.CHARGING;
        private long elapsedCycleTicks = 0L;
        private BlockPos landing = null;
        private UUID targetId = null;
        private long premonitionEndTick = Long.MIN_VALUE;
        private long lastTouchedTick = Long.MIN_VALUE;

        private BlinkState(ResourceKey<Level> dimension) {
            this.dimension = dimension;
        }
    }
}
