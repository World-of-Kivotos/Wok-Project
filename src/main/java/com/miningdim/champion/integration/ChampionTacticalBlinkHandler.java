package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionDiagnostics;
import com.miningdim.champion.ChampionTacticalBlinkPlan;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 冠军【机动词条·战术传送 TACTICAL_BLINK】(批4 波1; ChampionStarAffix spec 7.2 脱离型) 效果施加 (集成层)。战术传送
 * 与闪光 (抵近) 相反 —— 冠军短瞬移【离开】玩家方向 4-8 格拉开身位 (脱离型)。两种触发:
 *  - (a) 周期到点: 周期按品质取 {@link ChampionTacticalBlinkPlan#cycleTicks} (160/140/120/100/80 tick), 周期锚点是
 *    per-冠军"已累加循环 tick", 仅在【有存活攻击目标且在缰绳 {@value ChampionTacticalBlinkPlan#TETHER_RANGE} 格内】
 *    的扫描推进 (无目标/超缰绳冻结不耗周期)。
 *  - (b) 受击应激: 冠军受玩家直接伤害且内 CD 已冷却过半 ({@link ChampionTacticalBlinkPlan#hitStressEligible}) 时
 *    立即尝试脱离, 参照攻击者方向。两路共用同一内 CD (循环 tick), 触发后清零重计, 故连续挨打不刷成每击一次的永动。
 *
 * 落点流程 (两路共用): 沿【玩家 -> 冠军 延长线】(远离玩家) 4-8 格 + 左右 30 度扇形取候选 (由
 * {@link ChampionTacticalBlinkPlan#candidates} 生成), 逐个过 {@link KnockbackSafetyGuard#evaluateLanding} 求首个安全
 * 落点; 再经 {@link ChampionTacticalBlinkPlan#isDisengaging} 硬闸复核"落点须比当前更远离玩家"(脱离语义); 首个通过
 * 即瞬移。全拒则本次放弃 (周期已清零, 走冷却)。脱离型【无预兆】(预兆会让脱离失去意义): 瞬移即刻执行, 仅在起/落
 * 两端喷 POOF 烟 + 播末影人传送音, 让玩家肉眼/耳可辨"它跑了"。
 *
 * 入口 {@link #onServerTick} (END, 每 {@value #SCAN_INTERVAL_TICKS}tick=1s): 按玩家 AABB 扫近处冠军 (命令召唤 + 自然
 * 刷一视同仁, 与 {@code ChampionVisualDisruptionHandler}/{@code ChampionCounterUnitHandler} 同范式) 走周期路;
 * {@link #onChampionHurt} (LivingHurtEvent) 走受击应激路。位移是【自体】瞬移 (移动冠军, 非位移玩家), 故不涉波0 玩家
 * 落地保护/控制聚合 —— 两者是位移玩家的效果, 与本词条无关。
 *
 * per-冠军循环状态 (已累加循环 tick + 最后触达 tick) 双清: 冠军死亡摘除 + TTL 清扫兜底 (冠军未设 persistenceRequired,
 * 自然 despawn/区块卸载不发 LivingDeathEvent, 只靠死亡清理会泄漏, 与 {@code ChampionVisualDisruptionHandler} 同纪律)。
 * 自研 capability 检出, 不触任何 top.theillusivec4.champions.*; 注册由主线在 {@code ChampionSystem} 挂 forgeBus。
 * 位移写 (teleportTo) 在 ServerTick END / LivingHurtEvent 内执行, 二者本就是服务端主线程, 无需 server.execute 转派。
 */
public final class ChampionTacticalBlinkHandler {

    /** 诊断日志: 脱离/全拒真服验收/对账用 (经 {@link ChampionDiagnostics#shouldTrace} 门控, 仅 10 格内有玩家的怪)。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/tactical-blink");

    /** 扫描/周期推进周期 (tick): 1s 扫一次近玩家冠军 (与纯逻辑 SCAN_INTERVAL_TICKS 对齐)。 */
    private static final int SCAN_INTERVAL_TICKS = (int) ChampionTacticalBlinkPlan.SCAN_INTERVAL_TICKS;

    /** 扫描可见距离 (格; 与视觉干扰/反击单元同量级)。缰绳 (24) 另在纯逻辑内二次门控周期推进。 */
    private static final double VIEW_RANGE = 48.0D;

    /** 两端脱离表现: POOF 烟颗数 (spec 9A.6 粒子预算纪律)。 */
    private static final int POOF_PARTICLE_COUNT = 15;

    /** per-冠军循环状态; 冠军死亡摘除 + TTL 清扫双保险 (despawn/卸载不发死亡事件的泄漏兜底)。 */
    private final Map<UUID, BlinkState> stateByChampion = new HashMap<>();

    /** 状态 TTL 清扫周期 (tick): 每 60s 扫一次 (低频, 表通常极小)。 */
    private static final int STATE_SWEEP_INTERVAL_TICKS = 1200;

    /**
     * 状态条目 TTL (tick): 5min 未被触达 (未被有目标且在缰绳内的扫描推进) 即回收。丢态语义安全: 回收后视为"循环
     * 从零起", 周期至多 8s、无目标/超缰绳本就冻结 —— 5min 未触达的冠军必然远离玩家或长期脱战, 回收不改变任何
     * 可观测行为, 只兜内存。
     */
    private static final long STATE_TTL_TICKS = 6000L;

    /**
     * 每秒扫近玩家冠军: 对装配战术传送的冠军推进周期 + 到点尝试脱离。按玩家 AABB 扫 + 自研 capability 检出冠军
     * (命令召唤 + 自然刷一视同仁), 多玩家同看一冠军本轮只结算一次。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        long nowTick = server.overworld().getGameTime();
        Set<UUID> processed = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            List<ServerPlayer> players = level.players();
            if (players.isEmpty()) {
                continue;
            }
            for (ServerPlayer player : players) {
                AABB box = player.getBoundingBox().inflate(VIEW_RANGE);
                for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
                    if (!processed.add(entity.getUUID())) {
                        continue; // 多玩家同看一冠军: 本轮只结算一次。
                    }
                    applyBlinkTick(level, entity, nowTick);
                }
            }
        }

        // TTL 清扫 (despawn/卸载不发死亡事件的泄漏兜底): 低频回收长期未触达的状态条目。
        if (server.getTickCount() % STATE_SWEEP_INTERVAL_TICKS == 0) {
            sweepStaleStates(nowTick);
        }
    }

    /** 回收 TTL 内未被触达的状态条目 (语义安全性见 {@link #STATE_TTL_TICKS})。 */
    private void sweepStaleStates(long nowTick) {
        // MIN_VALUE (理论不出现: 建条目点即刻刷触达) 显式视为过期, 防减法溢出漏回收。
        stateByChampion.values().removeIf(state -> state.lastTouchedTick == Long.MIN_VALUE
                || nowTick - state.lastTouchedTick > STATE_TTL_TICKS);
    }

    /**
     * 周期路: 对一只实体 (若是装配战术传送的本工程冠军) 推进周期; 有目标且在缰绳内才推进, 到点尝试脱离。
     * 无目标 / 超缰绳时不触达状态 (冻结不耗周期; 已累加的循环 tick 原样保留, 供受击路复用)。
     */
    private void applyBlinkTick(ServerLevel level, LivingEntity entity, long nowTick) {
        MiningChampionData champ = MiningChampions.get(entity).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 非本工程冠军。
        }
        AffixQuality quality = champ.quality(AffixDef.TACTICAL_BLINK);
        if (quality == null) {
            return; // 未装配战术传送: 不建状态 (防为无关冠军泄漏 state)。
        }
        ServerPlayer target = resolveTarget(entity);
        if (target == null) {
            return; // 无存活玩家攻击目标: 冻结不推进 (不建/不触达状态)。
        }
        if (!ChampionTacticalBlinkPlan.withinTether(entity.distanceToSqr(target))) {
            return; // 超缰绳: 冻结不推进 (循环 tick 保留, 不触达 —— 拉开身位属正常交战, 长期超缰绳才 TTL 回收)。
        }

        BlinkState state = stateByChampion.computeIfAbsent(entity.getUUID(), k -> new BlinkState());
        state.lastTouchedTick = nowTick;
        state.elapsedCycleTicks = ChampionTacticalBlinkPlan.advanceCycle(state.elapsedCycleTicks);
        if (!ChampionTacticalBlinkPlan.cycleReady(state.elapsedCycleTicks, quality)) {
            return; // 未到周期 (脱离 CD 充能中)。
        }
        // 到点: 清零重计 (无论是否找到安全落点, 周期照走不补偿, 单一权威在此)。
        state.elapsedCycleTicks = 0L;
        attemptDisengage(level, entity, target, champ.star(), "cycle");
    }

    /**
     * 受击应激路: 冠军受玩家直接伤害且内 CD 已冷却过半时立即尝试脱离 (参照攻击者方向)。触发后清零内 CD (两路
     * 共用, 不刷永动)。不改伤害本身 (只读事件); 位移在事件线程 (服务端主线程) 直接执行。
     */
    @SubscribeEvent
    public void onChampionHurt(LivingHurtEvent event) {
        // 最廉价早退: 伤害来源须是玩家实体 (受击应激只对玩家直接伤害; 过滤环境/怪/DoT 来源)。
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) {
            return;
        }
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level)) {
            return; // 非服务端世界 (客户端幽灵): 不处理。
        }
        BlinkState state = stateByChampion.get(victim.getUUID());
        if (state == null) {
            return; // 无累积状态 (从未被扫描/刚生成): 内 CD 为零, 不满足冷却过半, 受击不触发。
        }
        MiningChampionData champ = MiningChampions.get(victim).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 非本工程冠军 (状态残留竞态兜底)。
        }
        AffixQuality quality = champ.quality(AffixDef.TACTICAL_BLINK);
        if (quality == null) {
            return; // 词条已被移除 (罕见): 不触发。
        }
        if (!ChampionTacticalBlinkPlan.hitStressEligible(state.elapsedCycleTicks, quality)) {
            return; // 内 CD 未冷却过半: 受击不触发 (防每击刷成永动, 两路共用 CD)。
        }
        long nowTick = level.getGameTime();
        state.elapsedCycleTicks = 0L; // 触发即清零内 CD (与周期路共用, 触发后重置)。
        state.lastTouchedTick = nowTick;
        attemptDisengage(level, victim, attacker, champ.star(), "hit");
    }

    /**
     * 落点流程 (两路共用): 沿远离玩家方向逐档取候选 (4-8 格 + 扇形摆动), 逐个过 KnockbackSafetyGuard 求首个安全
     * 落点, 再经脱离约束硬闸复核 (落点须比当前更远离玩家), 首个通过即瞬移。全拒则本次放弃 (走冷却)。
     */
    private void attemptDisengage(ServerLevel level, LivingEntity champion, ServerPlayer player,
                                  int star, String trigger) {
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        double cx = champion.getX();
        double cy = champion.getY();
        double cz = champion.getZ();
        boolean trace = ChampionDiagnostics.shouldTrace(champion);

        List<ChampionTacticalBlinkPlan.Landing> candidates =
                ChampionTacticalBlinkPlan.candidates(px, py, pz, cx, cy, cz);
        for (ChampionTacticalBlinkPlan.Landing cand : candidates) {
            BlockPos targetPos = BlockPos.containing(cand.x(), cand.y(), cand.z());
            KnockbackSafetyGuard.Decision decision = KnockbackSafetyGuard.evaluateLanding(level, targetPos);
            if (decision.outcome() != KnockbackSafetyGuard.Outcome.SAFE) {
                continue; // 该候选落点不安全 (岩浆/火/虚空边缘/被塞墙): 试下一档。
            }
            BlockPos landing = decision.landing();
            double lx = landing.getX() + 0.5D;
            double ly = landing.getY();
            double lz = landing.getZ() + 0.5D;
            // 脱离约束硬闸: 安全落点仍须比当前更远离玩家, 否则不是"离开"(几何已保证, 此为守卫语义演进的防御闸)。
            if (!ChampionTacticalBlinkPlan.isDisengaging(px, py, pz, cx, cy, cz, lx, ly, lz)) {
                continue;
            }
            performBlink(level, champion, lx, ly, lz);
            if (trace) {
                LOGGER.info("tactical-blink champion={} tier{} trigger={} from=({},{},{}) to=({},{},{})",
                        champion.getUUID(), star, trigger,
                        fmt(cx), fmt(cy), fmt(cz), fmt(lx), fmt(ly), fmt(lz));
            }
            return;
        }
        // 全拒: 无安全脱离落点, 本次放弃 (周期已清零, 走冷却)。
        if (trace) {
            LOGGER.info("tactical-blink champion={} tier{} trigger={} no-safe-landing (all {} candidates rejected)",
                    champion.getUUID(), star, trigger, candidates.size());
        }
    }

    /**
     * 执行自体瞬移: 起点喷 POOF + 播传送音 -> {@link LivingEntity#teleportTo} 落到目标点 -> 落点再喷 POOF + 播传送音。
     * 两端表现让附近玩家肉眼/耳可辨冠军脱离 (脱离型无预兆, 瞬移即刻)。
     */
    private static void performBlink(ServerLevel level, LivingEntity champion, double x, double y, double z) {
        emitBlinkEffects(level, champion.getX(), champion.getY(), champion.getZ()); // 起点。
        champion.teleportTo(x, y, z);
        emitBlinkEffects(level, x, y, z); // 落点。
    }

    /** 单端脱离表现: 该点喷 {@value #POOF_PARTICLE_COUNT} 颗 POOF 烟 + 播末影人传送音 (音量 0.9)。 */
    private static void emitBlinkEffects(ServerLevel level, double x, double y, double z) {
        level.sendParticles(ParticleTypes.POOF, x, y + 0.5D, z, POOF_PARTICLE_COUNT, 0.3D, 0.4D, 0.3D, 0.02D);
        level.playSound(null, x, y, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.9F, 1.0F);
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

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }

    /** 冠军死亡: 摘 per-冠军循环状态防泄漏。 */
    @SubscribeEvent
    public void onChampionDeath(LivingDeathEvent event) {
        stateByChampion.remove(event.getEntity().getUUID());
    }

    /**
     * per-冠军循环状态: 已累加循环 tick (初始 0, 仅有目标且在缰绳内的扫描推进, 周期到点/受击触发由 handler 清零
     * 重计 —— 两路共用同一内 CD) + 最后触达 tick (TTL 清扫依据)。
     */
    private static final class BlinkState {
        private long elapsedCycleTicks = 0L;
        private long lastTouchedTick = Long.MIN_VALUE;
    }
}
