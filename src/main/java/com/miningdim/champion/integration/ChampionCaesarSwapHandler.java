package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionCaesarSwapPlan;
import com.miningdim.champion.ChampionDiagnostics;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 冠军【技能词条·凯撒实验型转换器 CAESAR_SWAP】(批4 波2; ChampionStarAffix spec 7.4, ★5 技能, 传送家族) 效果施加
 * (集成层)。凯撒转换器不是自体瞬移而是【与目标玩家换位】: CD 到点后 1s 预兆 (双方发光 + 脚下旋环粒子 + 目标
 * actionbar 警告 + 警告音), 预兆到点若双向落点都安全则把玩家挪到冠军原位、冠军挪到玩家原位, 强行拆散近战玩家的
 * 安全站位。数值/门控/预兆取消/落点双向安全判定纯逻辑下沉 {@link ChampionCaesarSwapPlan} (dev GameTest 真验), 本
 * handler 只做真服侧: 实体检出/目标缰绳查询/预兆表现/逐格过 {@link KnockbackSafetyGuard#evaluateLanding} 双向裁决/
 * 双端 {@code teleportTo} 换位/换位后 {@link PlayerLandingProtection#grant}。
 *
 * <p>与波1 传送家族 (闪光/战术传送) 的关键契约差异: 那两者是冠军【自体】瞬移, 不位移玩家, 故不涉波0 玩家落地保护;
 * 凯撒转换器【位移玩家】(把玩家拽到冠军原位), 故换位后必须调 {@link PlayerLandingProtection#grant} 开 2s 抗位移窗
 * (红线 6: 换位后玩家获 2s 抗位移落地保护, 防紧接着的原版近战击退把玩家推回岩浆/虚空)。换位不造成任何伤害, 故不
 * 涉 AOE 免疫缓冲 / 伤害类型。
 *
 * <p>状态机 (per-冠军, 两相, 与 {@code ChampionBlinkHandler} 同范式): CHARGING (门控推进 CD) -&gt; 到点且有缰绳内
 * 存活目标则转 TELEGRAPH (锁定目标 UUID, 双方发光, 1s 后换位) -&gt; 换位/取消/放弃后回 CHARGING。两个时间尺度:
 * <ul>
 *   <li>{@link #onServerTick} 每 tick 先推进在册【预兆】(1s = {@value #TELEGRAPH_TICKS}tick 需 tick 级精度: 逐 tick
 *       喷双方旋环粒子 + 刷 actionbar + 查取消条件; charging 条目在此跳过);</li>
 *   <li>再每 {@value #SCAN_INTERVAL_TICKS}tick(1s) 按玩家 AABB 扫近处冠军 (覆盖命令召唤 + 自然刷), 门控通过者推进
 *       CD; 到点起预兆 (无缰绳内目标则本周期跳过, CD 已清零)。</li>
 * </ul>
 * 预兆期取消 (目标死亡/离线/跑出缰绳) 与换位落点双向不安全 (任一格非 SAFE) 均放弃本次: CD 照走不重置预兆
 * ({@link ChampionCaesarSwapPlan#telegraphShouldCancel} / {@link ChampionCaesarSwapPlan#bothLandingsSafe})。
 *
 * <p>per-冠军状态双清 (与 {@code ChampionBlinkHandler} 同纪律): 冠军死亡摘除 + TTL 清扫兜底 —— 冠军未设
 * persistenceRequired, despawn/区块卸载不发 LivingDeathEvent, 只靠死亡清理会泄漏。实例状态随本 handler 实例存活
 * (由 {@code ChampionSystem#register} 挂 forgeBus), 无静态账本故无需 onServerStopping 清理。自研 capability 检出,
 * 不触任何 top.theillusivec4.champions.*。位移写 (teleportTo/grant) 在 ServerTick END 内执行, 本就是服务端主线程,
 * 无需 server.execute 转派。
 */
public final class ChampionCaesarSwapHandler {

    /** 诊断日志: 批4 波2 凯撒换位真服首验用 (预兆起/换位毕/取消/落点放弃各一行, shouldTrace 门控只追近玩家的怪)。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/skill");

    /** skill 类日志前缀 (统一 skill-<名> 便于真服 grep)。 */
    private static final String SKILL_TAG = "skill-caesar-swap";

    /** 扫描/CD 推进周期 (tick): 1s 扫一次近玩家冠军 (与纯逻辑 {@link ChampionCaesarSwapPlan#SCAN_INTERVAL_TICKS} 对齐)。 */
    private static final int SCAN_INTERVAL_TICKS = (int) ChampionCaesarSwapPlan.SCAN_INTERVAL_TICKS;

    /** 预兆时长 (tick): 用户裁定 1s (与纯逻辑 {@link ChampionCaesarSwapPlan#TELEGRAPH_TICKS} 对齐)。 */
    private static final int TELEGRAPH_TICKS = (int) ChampionCaesarSwapPlan.TELEGRAPH_TICKS;

    /** 作用的玩家可见距离 (格; 与闪光/战术传送同量级)。缰绳 (24) 另在纯逻辑内二次门控 CD 推进/预兆维持。 */
    private static final double VIEW_RANGE = 48.0D;

    /** 预兆脚下旋环: 每帧每方 portal 环点数。 */
    private static final int TELEGRAPH_RING_POINTS = 12;

    /** 预兆旋环半径 (格): 略大于常规实体脚印, 肉眼可辨"这两个要换"。 */
    private static final double TELEGRAPH_RING_RADIUS = 0.9D;

    /** 换位两端 poof 颗数 (spec 9A.6 粒子预算纪律)。 */
    private static final int POOF_PARTICLE_COUNT = 18;

    /** per-冠军换位循环状态; 冠军死亡摘除 + TTL 清扫双保险 (despawn/卸载不发死亡事件的泄漏兜底)。 */
    private final Map<UUID, SwapState> stateByChampion = new HashMap<>();

    /** 状态 TTL 清扫周期 (tick): 每 60s 扫一次 (低频, 表通常极小)。 */
    private static final int STATE_SWEEP_INTERVAL_TICKS = 1200;

    /**
     * 状态条目 TTL (tick): 5min 未被触达 (未被门控扫描推进) 即回收。丢态语义安全: 回收后视为"充能从零起", 而 CD 至多
     * 20s、非门控本就冻结不推进 —— 5min 未触达的冠军必远离玩家或长期脱战/超缰绳, 回收不改任何可观测行为, 只兜内存。
     */
    private static final long STATE_TTL_TICKS = 6000L;

    /**
     * 每 tick 先推进在册预兆 (tick 级), 再每 1s 门控扫描推进 CD。预兆需 tick 精度: 1s 窗内逐 tick 喷双方旋环粒子 + 刷
     * actionbar + 查取消, 到点即换位; 扫描 (创建/推进 charging 态) 是 1s 节流 (CD 10~20s 无需亚秒精度)。
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
            advanceTelegraphs(server, nowTick);
        }

        // 1s 扫描: 门控推进 CD + 到点起预兆。
        if (server.getTickCount() % SCAN_INTERVAL_TICKS == 0) {
            scanNearbyChampions(server, nowTick);
            if (server.getTickCount() % STATE_SWEEP_INTERVAL_TICKS == 0) {
                sweepStaleStates(nowTick);
            }
        }
    }

    /**
     * 每 tick 推进在册预兆: 遍历状态表, 仅处理 TELEGRAPH 相位条目 (按维度 O(1) 检出实体)。冠军消失/死亡 -&gt; 摘除
     * (预兆作废); 目标取消条件成立 (死亡/离线/跑出缰绳) -&gt; 撤发光回充能 (CD 照走); 未到点 -&gt; 逐 tick 喷双方旋环 +
     * 刷 actionbar; 到点 -&gt; 双向落点裁决, 都安全则换位, 否则放弃 (CD 照走)。
     */
    private void advanceTelegraphs(MinecraftServer server, long nowTick) {
        Iterator<Map.Entry<UUID, SwapState>> it = stateByChampion.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, SwapState> entry = it.next();
            SwapState state = entry.getValue();
            if (state.phase != Phase.TELEGRAPH) {
                continue; // charging: 由 1s 扫描推进, 此处不动。
            }
            LivingEntity entity = resolve(server, state.dimension, entry.getKey());
            if (entity == null || !entity.isAlive() || !(entity instanceof Mob mob)
                    || !(entity.level() instanceof ServerLevel level)) {
                it.remove(); // despawn/卸载/死亡/非 Mob: 预兆作废, 摘除 (发光随实体消失/短挂自灭)。
                continue;
            }
            advanceOneTelegraph(mob, level, state, nowTick);
        }
    }

    /** 单只冠军的预兆推进 (取消 / 续帧 / 到点换位)。 */
    private void advanceOneTelegraph(Mob champion, ServerLevel level, SwapState state, long nowTick) {
        ServerPlayer target = resolveOnlineTarget(level, state.targetId);
        boolean alive = target != null && target.isAlive();
        boolean online = target != null;
        boolean within = target != null
                && ChampionCaesarSwapPlan.withinTether(champion.distanceToSqr(target));
        if (ChampionCaesarSwapPlan.telegraphShouldCancel(alive, online, within)) {
            cancelTelegraph(champion, level, state, nowTick, "target-lost");
            return;
        }
        if (nowTick >= state.telegraphEndTick) {
            executeSwapOrAbandon(champion, level, target, state, nowTick);
            return;
        }
        // 预兆进行中: 双方脚下旋环 + 刷目标 actionbar (发光已在起预兆时短挂 20t, 自灭)。
        emitTelegraphRing(level, champion.getX(), champion.getY(), champion.getZ(), nowTick);
        emitTelegraphRing(level, target.getX(), target.getY(), target.getZ(), nowTick);
        target.displayClientMessage(Component.literal("凯撒转换器锁定: 即将换位!"), true);
    }

    /**
     * 每秒扫近玩家冠军 (与 {@code ChampionBlinkHandler} 同范式): 按玩家 AABB 扫 + 自研 capability 检出装配凯撒转换器
     * 的冠军 (命令召唤 + 自然刷一视同仁), 门控通过者推进 CD; 多玩家同看一冠军本轮只结算一次。
     */
    private void scanNearbyChampions(MinecraftServer server, long nowTick) {
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
                    applySwapScan(entity, nowTick);
                }
            }
        }
    }

    /** 对一只实体 (若是装配凯撒转换器的本工程冠军) 推进 CD; 到点起预兆。 */
    private void applySwapScan(LivingEntity entity, long nowTick) {
        MiningChampionData champ = MiningChampions.get(entity).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 非本工程冠军。
        }
        AffixQuality quality = champ.quality(AffixDef.CAESAR_SWAP);
        if (quality == null) {
            return; // 未装配凯撒转换器: 不建状态 (防为无关冠军泄漏 state)。
        }

        SwapState existing = stateByChampion.get(entity.getUUID());
        if (existing != null && existing.phase == Phase.TELEGRAPH) {
            return; // 预兆期由每 tick 推进独占, 扫描不并发动其 CD (预兆 1s 通常不撞 1s 扫描, 防御性)。
        }

        ServerPlayer target = resolveTarget(entity);
        // 门控: 有存活目标且在缰绳内才推进 (丢目标/超缰绳冻结; 无目标时距离参数不参与, 传 MAX 占位)。
        double distanceSq = target == null ? Double.MAX_VALUE : entity.distanceToSqr(target);
        if (!ChampionCaesarSwapPlan.shouldAdvanceCycle(target != null, distanceSq)) {
            return; // 冻结不耗周期: 不新建/触达 state, 已有 charging 态原样保留 (elapsed 冻结)。
        }
        if (!(entity instanceof Mob mob) || !(entity.level() instanceof ServerLevel level)) {
            return; // 冠军 capability 只挂 Mob, 且扫描来自 ServerLevel; 防御性早退。
        }

        SwapState state = stateByChampion.computeIfAbsent(entity.getUUID(),
                k -> new SwapState(level.dimension()));
        state.lastTouchedTick = nowTick;
        state.elapsedCycleTicks = ChampionCaesarSwapPlan.advanceCycle(state.elapsedCycleTicks);
        if (!ChampionCaesarSwapPlan.cycleReady(state.elapsedCycleTicks, quality)) {
            return; // 未到 CD (充能中)。
        }
        // 到点: 清零重计 (无论换位是否成行, CD 照走不补偿, 单一权威在 handler 不复制纯逻辑)。
        state.elapsedCycleTicks = 0L;
        beginTelegraph(mob, level, target, state, nowTick);
    }

    /**
     * 起预兆: 锁定目标 UUID, 双方短挂 Glowing {@value #TELEGRAPH_TICKS}t (自灭, 无需显式撤除) + 首帧旋环 + 目标
     * actionbar 警告 + 警告音。目标在扫描门控已判存活且在缰绳内, 此处直接锁定。
     */
    private void beginTelegraph(Mob champion, ServerLevel level, ServerPlayer target, SwapState state, long nowTick) {
        state.phase = Phase.TELEGRAPH;
        state.targetId = target.getUUID();
        state.telegraphEndTick = nowTick + TELEGRAPH_TICKS;

        applyTelegraphGlow(champion);
        applyTelegraphGlow(target);
        emitTelegraphRing(level, champion.getX(), champion.getY(), champion.getZ(), nowTick);
        emitTelegraphRing(level, target.getX(), target.getY(), target.getZ(), nowTick);
        target.displayClientMessage(Component.literal("凯撒转换器锁定: 即将换位!"), true);
        // 警告音: 双方位置各播一次末影凝视 (与换位的末影传送音同主题, 一起一落听感连贯)。
        level.playSound(null, champion.getX(), champion.getY(), champion.getZ(),
                SoundEvents.ENDERMAN_STARE, SoundSource.HOSTILE, 0.9F, 0.8F);
        level.playSound(null, target.getX(), target.getY(), target.getZ(),
                SoundEvents.ENDERMAN_STARE, SoundSource.HOSTILE, 0.9F, 0.8F);

        if (ChampionDiagnostics.shouldTrace(champion)) {
            LOGGER.info("{} champion={} TELEGRAPH target={} in={}t",
                    SKILL_TAG, champion.getType().getDescriptionId(),
                    target.getGameProfile().getName(), TELEGRAPH_TICKS);
        }
    }

    /**
     * 预兆到点: 双向落点裁决 (玩家目的格 = 冠军当前格, 冠军目的格 = 玩家当前格, 两格分别经守卫), 都 SAFE 则换位,
     * 否则放弃本次 (CD 照走)。位置/朝向在任何 teleport 前一次性记录 (换位是双向, 先挪一方会污染另一方的原位读值)。
     */
    private void executeSwapOrAbandon(Mob champion, ServerLevel level, ServerPlayer target,
                                      SwapState state, long nowTick) {
        // 换位前一次性记录双方位置/朝向 (原位): 玩家去冠军原位, 冠军去玩家原位, 各保留自身朝向 (换位不转身)。
        Vec3 champPos = champion.position();
        Vec3 playerPos = target.position();
        BlockPos champBlock = champion.blockPosition();
        BlockPos playerBlock = target.blockPosition();
        float playerYaw = target.getYRot();
        float playerPitch = target.getXRot();

        // 双向落点裁决: 玩家目的格 (冠军当前格) + 冠军目的格 (玩家当前格) 分别过守卫单点裁决, 任一非 SAFE 则放弃。
        boolean playerDestSafe =
                KnockbackSafetyGuard.evaluateLanding(level, champBlock).outcome() == KnockbackSafetyGuard.Outcome.SAFE;
        boolean champDestSafe =
                KnockbackSafetyGuard.evaluateLanding(level, playerBlock).outcome() == KnockbackSafetyGuard.Outcome.SAFE;
        boolean trace = ChampionDiagnostics.shouldTrace(champion);
        if (!ChampionCaesarSwapPlan.bothLandingsSafe(playerDestSafe, champDestSafe)) {
            // 放弃本次: 落点危险 (岩浆/火/虚空边缘/被填埋), CD 照走。回充能等下个 CD (elapsed 已清零)。
            returnToCharging(state, nowTick);
            if (trace) {
                LOGGER.info("{} champion={} ABANDON no-safe-landing playerDestSafe={} champDestSafe={}",
                        SKILL_TAG, champion.getType().getDescriptionId(), playerDestSafe, champDestSafe);
            }
            return;
        }

        // 换位: 双端 poof + 末影音 (两原位各喷一次: 各自既是一方去处又是另一方来处), 再双向 teleport。
        emitPoof(level, champPos.x, champPos.y + champion.getBbHeight() * 0.5D, champPos.z);
        emitPoof(level, playerPos.x, playerPos.y + target.getBbHeight() * 0.5D, playerPos.z);
        level.playSound(null, champPos.x, champPos.y, champPos.z,
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 1.0F);
        level.playSound(null, playerPos.x, playerPos.y, playerPos.z,
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 1.0F);

        target.teleportTo(level, champPos.x, champPos.y, champPos.z, playerYaw, playerPitch);
        champion.teleportTo(playerPos.x, playerPos.y, playerPos.z);
        champion.getNavigation().stop(); // 换位后停旧路径, 由原生索敌重新贴脸。

        // 红线 6: 换位【位移玩家】, 挪到冠军原位后开 2s 抗位移落地保护 (防紧接着的原版近战击退推回危险区)。
        PlayerLandingProtection.grant(target);

        returnToCharging(state, nowTick);
        if (trace) {
            LOGGER.info("{} champion={} SWAP player={} champ->[{},{},{}] player->[{},{},{}]",
                    SKILL_TAG, champion.getType().getDescriptionId(), target.getGameProfile().getName(),
                    fmt(playerPos.x), fmt(playerPos.y), fmt(playerPos.z),
                    fmt(champPos.x), fmt(champPos.y), fmt(champPos.z));
        }
    }

    /** 预兆取消 (目标死亡/离线/跑出缰绳): 撤冠军发光 (目标发光短挂自灭或随其消失) + 回充能, CD 照走。 */
    private void cancelTelegraph(Mob champion, ServerLevel level, SwapState state, long nowTick, String reason) {
        champion.removeEffect(MobEffects.GLOWING);
        ServerPlayer target = resolveOnlineTarget(level, state.targetId);
        if (target != null) {
            target.removeEffect(MobEffects.GLOWING); // 目标仍在线 (取消因跑出缰绳): 撤发光免残留误导。
        }
        returnToCharging(state, nowTick);
        if (ChampionDiagnostics.shouldTrace(champion)) {
            LOGGER.info("{} champion={} CANCEL reason={}",
                    SKILL_TAG, champion.getType().getDescriptionId(), reason);
        }
    }

    /** 回充能相位重充 CD (换位/取消/放弃共用): elapsed 已在到点时清零, 此处复位相位 + 清目标 + 刷触达。 */
    private void returnToCharging(SwapState state, long nowTick) {
        state.phase = Phase.CHARGING;
        state.targetId = null;
        state.telegraphEndTick = Long.MIN_VALUE;
        state.lastTouchedTick = nowTick;
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

    /** 预兆短挂 Glowing (spec 9A.6 原版可见原语; 20t 自灭免显式撤除, 无粒子/环境噪声只轮廓高亮)。 */
    private static void applyTelegraphGlow(LivingEntity entity) {
        entity.addEffect(new MobEffectInstance(MobEffects.GLOWING, TELEGRAPH_TICKS, 0, false, false));
    }

    /**
     * 预兆脚下旋环 (spec 9A.6 原版可见原语): 一圈 portal 粒子随 gameTime 旋转 (1 转/秒), 双方脚下各喷一圈, 让玩家肉眼
     * 预判"这两个要换位"。旋转相位取 nowTick 对 20t 取模, 一秒预兆恰转一整圈。
     */
    private static void emitTelegraphRing(ServerLevel level, double x, double y, double z, long nowTick) {
        double spin = (nowTick % SCAN_INTERVAL_TICKS) / (double) SCAN_INTERVAL_TICKS * (2.0D * Math.PI);
        for (int i = 0; i < TELEGRAPH_RING_POINTS; i++) {
            double angle = spin + i * (2.0D * Math.PI / TELEGRAPH_RING_POINTS);
            double rx = x + TELEGRAPH_RING_RADIUS * Math.cos(angle);
            double rz = z + TELEGRAPH_RING_RADIUS * Math.sin(angle);
            level.sendParticles(ParticleTypes.PORTAL, rx, y + 0.15D, rz, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /** 换位单端 poof (spec 9A.6): 该点消散/浮现, 纯原版客户端可见。 */
    private static void emitPoof(ServerLevel level, double x, double y, double z) {
        level.sendParticles(ParticleTypes.POOF, x, y, z, POOF_PARTICLE_COUNT, 0.3D, 0.4D, 0.3D, 0.02D);
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

    /** 按锁定 UUID 解析在线目标玩家 (离线/不在本层返 null; 存活与否由调用方另判)。 */
    private static ServerPlayer resolveOnlineTarget(ServerLevel level, UUID targetId) {
        if (targetId == null) {
            return null;
        }
        if (level.getPlayerByUUID(targetId) instanceof ServerPlayer player) {
            return player;
        }
        return null;
    }

    /** 按状态记录的维度检出在册冠军实体 (维度未加载/实体不在返 null; UUID -&gt; 实体为 O(1) 表查, 照闪光范式)。 */
    private static LivingEntity resolve(MinecraftServer server, ResourceKey<Level> dimension, UUID id) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            return null;
        }
        Entity found = level.getEntity(id);
        return found instanceof LivingEntity living ? living : null;
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }

    /** 凯撒转换器循环相位: 充能 (门控推进 CD) / 预兆 (锁定目标 1s 后换位)。 */
    private enum Phase {
        CHARGING,
        TELEGRAPH
    }

    /**
     * per-冠军换位状态: 相位 + 已累加充能 tick (门控扫描推进, 到点清零) + 所在维度 (预兆期每 tick O(1) 检出实体) +
     * 预兆锁定目标玩家 UUID + 预兆到点 tick + 最后触达 tick (TTL 清扫依据)。
     */
    private static final class SwapState {
        private final ResourceKey<Level> dimension;
        private Phase phase = Phase.CHARGING;
        private long elapsedCycleTicks = 0L;
        private UUID targetId = null;
        private long telegraphEndTick = Long.MIN_VALUE;
        private long lastTouchedTick = Long.MIN_VALUE;

        private SwapState(ResourceKey<Level> dimension) {
            this.dimension = dimension;
        }
    }
}
