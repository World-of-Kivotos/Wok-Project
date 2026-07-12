package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionCounterUnitWindow;
import com.miningdim.champion.ChampionDamageTypes;
import com.miningdim.champion.ChampionDiagnostics;
import com.miningdim.champion.ChampionEffectRegistries;
import com.miningdim.champion.ChampionTargetLocks;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.aggregate.RetaliationAggregator;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
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
 * 反击单元 COUNTER_UNIT (Champions 集成层; ChampionStarAffix spec 7.4 反击单元 + 红线 2 三层封顶 + 9A.5/9A.6 表现层)。
 *
 * 机制: 冠军每 15s (锁定周期) 锁定当前仇恨玩家开 5s 反击窗; 窗内该玩家对本怪的每笔名义伤害按【反伤比 × 该笔伤害】
 * 折名义反伤, 经三层封顶 (1 本源私有 20%/s -> 2/3 {@link RetaliationAggregator} 30%/s 全局多源 + 40%/窗) 后以真伤
 * champion_thorns 打回攻击者。数值/相位/第一层封顶纯逻辑下沉 {@link ChampionCounterUnitWindow} 真测。
 *
 * 跨冠军互斥 (spec 红线 / 第八章 命定 ⨉ 反击 不并行): 开窗前经 {@link ChampionTargetLocks} 对被锁玩家上锁, 失败
 * (该玩家已被任一锁定类技能占用) 则本周期跳过且不重置周期锚 (下周期再试); 窗口到期/冠军死亡 release。
 *
 * 两个入口:
 *  - {@link #onServerTick} (END, 每 {@value #SCAN_INTERVAL_TICKS}tick=1s): 按玩家 AABB 扫近处冠军 (命令召唤 + 自然刷
 *    一视同仁, 与 {@code ChampionSelfEffectHandler} 同范式), 维护每只反击单元冠军的锁定周期 + 反击窗相位 (开窗/关窗)
 *    + 窗内连线粒子。
 *  - {@link #onCounterChampionHurt} (HIGH, 早于血池 LOWEST 取消): 窗内被锁玩家对本怪的每笔伤害折反伤打回 (读名义
 *    入伤 event.getAmount())。
 *
 * 自研 capability: 冠军 + 词条→品质经 {@link MiningChampions} 读, 不触任何 top.theillusivec4.champions.*;
 * 注册由 {@code ChampionSystem#register} 无条件挂 forgeBus (脱离 Champions 依赖, dev GameTest 可验纯逻辑)。
 */
public final class ChampionCounterUnitHandler {

    /** 诊断日志: 反击单元真服验收/对账用 (锁定/每笔反伤/窗口关闭)。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/skill-counter");

    /** 扫描周期 (tick): 1s 扫一次近玩家冠军, 维护锁定周期/反击窗相位 + 窗内连线粒子 (与连线粒子频率对齐)。 */
    private static final int SCAN_INTERVAL_TICKS = 20;

    /** 作用的玩家可见距离 (格; 与 BOSS 血条/粒子同量级)。远离该范围的冠军不结算 (无玩家在场无需反击窗)。 */
    private static final double VIEW_RANGE = 48.0D;

    /** 窗内连线粒子颗数 (spec 9A.6 粒子预算纪律: 8~12 取中, 沿 冠军->玩家 连线均布)。 */
    private static final int LINE_PARTICLE_COUNT = 10;

    /**
     * per-冠军反击状态 (锁定周期锚 / 反击窗 / 本源私有秒窗累加器); 冠军死亡摘除 + TTL 清扫双保险 —— 冠军自然
     * despawn/区块卸载不发 LivingDeathEvent, 只靠死亡清理会泄漏 (与 {@code ChampionSelfEffectHandler} 同缺口)。
     */
    private final Map<UUID, CounterState> stateByChampion = new HashMap<>();

    /** 状态 TTL 清扫周期 (tick): 每 60s 扫一次 (低频, 表通常极小)。 */
    private static final int STATE_SWEEP_INTERVAL_TICKS = 1200;

    /**
     * 状态条目 TTL (tick): 5min 未被触达 (未被扫描到) 即回收。丢态语义安全: 反击窗仅 5s、周期 15s, 5min 未触达
     * 的冠军必然远离玩家, 回收后视为"从未锁定"不改变任何可观测行为, 只兜内存。
     */
    private static final long STATE_TTL_TICKS = 6000L;

    /**
     * 每秒扫近玩家冠军, 维护反击单元的锁定周期 + 反击窗相位。按玩家 AABB 扫 + capability 检出冠军 (命令召唤 + 自然刷
     * 一视同仁), 多玩家同时看同一冠军本轮只结算一次。
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
                    applyCounterTick(level, entity, nowTick);
                }
            }
        }

        // TTL 清扫 (despawn/卸载不发死亡事件的泄漏兜底): 低频回收长期未触达的状态条目。
        if (server.getTickCount() % STATE_SWEEP_INTERVAL_TICKS == 0) {
            sweepStaleStates(nowTick);
        }
    }

    /** 回收 TTL 内未被触达 (未被扫描) 的状态条目。 */
    private void sweepStaleStates(long nowTick) {
        // MIN_VALUE (理论不出现: 建条目点即刻刷触达) 显式视为过期, 防减法溢出漏回收。
        stateByChampion.values().removeIf(state -> state.lastTouchedTick == Long.MIN_VALUE
                || nowTick - state.lastTouchedTick > STATE_TTL_TICKS);
    }

    /** 对一只实体 (若是装配反击单元的本工程冠军) 维护锁定周期 + 反击窗相位。 */
    private void applyCounterTick(ServerLevel level, LivingEntity entity, long nowTick) {
        MiningChampionData champ = MiningChampions.get(entity).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 非本工程冠军。
        }
        AffixQuality quality = champ.quality(AffixDef.COUNTER_UNIT);
        if (quality == null) {
            return; // 未装配反击单元 (本 handler 只处理装了本词条的冠军)。
        }
        CounterState state = stateByChampion.computeIfAbsent(entity.getUUID(), k -> new CounterState());
        state.lastTouchedTick = nowTick;

        // 已开窗: 窗内每次扫描 (20tick) 沿连线喷粒子, 到期无声关窗。
        if (state.windowStartTick != Long.MIN_VALUE) {
            if (ChampionCounterUnitWindow.isWithinWindow(nowTick - state.windowStartTick)) {
                emitConnectLine(level, entity, state.lockedPlayerId);
                return; // 窗内不尝试开新窗。
            }
            closeWindow(entity, state, "expired");
        }

        // 未开窗: 锁定周期就绪 + 有存活玩家仇恨目标才尝试开窗。
        if (!ChampionCounterUnitWindow.lockCycleReady(nowTick, state.lastLockTick)) {
            return; // 周期未到 (15s)。
        }
        ServerPlayer target = livingPlayerTarget(entity);
        if (target == null) {
            return; // 无存活玩家攻击目标: 不推进/尝试 (周期锚不动)。
        }
        // 跨冠军互斥 (红线): 该玩家已被任一锁定类技能 (命定/反击) 占用则本周期跳过, 不重置周期锚 (下周期再试)。
        if (!ChampionTargetLocks.tryAcquire(target.getUUID(), ChampionTargetLocks.LockKind.COUNTER_UNIT,
                entity.getUUID(), nowTick, ChampionCounterUnitWindow.WINDOW_TICKS)) {
            return;
        }
        openWindow(level, entity, state, target, nowTick, quality);
    }

    /** 开反击窗: 记锚点 + 建本源私有秒窗累加器 + 表现层 (actionbar 警告 + 变调低音) + 低频锁定日志。 */
    private void openWindow(ServerLevel level, LivingEntity entity, CounterState state, ServerPlayer target,
                            long nowTick, AffixQuality quality) {
        state.lockedPlayerId = target.getUUID();
        state.windowStartTick = nowTick;
        state.lastLockTick = nowTick;
        state.privateCap = new ChampionCounterUnitWindow(target.getMaxHealth());

        // 表现 (spec 9A.5 反击单元: 警告 title/音 + 高亮): 仓库无 title 发送先例, 退 actionbar 中文警告 (纯原版可见)。
        target.displayClientMessage(Component.literal("反击单元锁定!"), true);
        // 变调低音经验球音 (pitch 0.5): 低沉预警, 区别于拾取正反馈的高音。
        level.playSound(null, target.getX(), target.getY(), target.getZ(),
                SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.HOSTILE, 0.8F, 0.5F);

        // 锁定 = 技能施放 (低频): 不经诊断门控, 真服对账必打。
        LOGGER.info("skill-counter lock champion={} target={} window={}tick ratio={}",
                entity.getUUID(), target.getGameProfile().getName(), ChampionCounterUnitWindow.WINDOW_TICKS,
                String.format("%.2f", ChampionCounterUnitWindow.reflectRatio(quality)));
    }

    /** 关反击窗 (到期/死亡): 释放跨冠军锁 + 低频关闭日志; 保留 lastLockTick 供下一周期计时。窗口结束无声无粒子。 */
    private void closeWindow(LivingEntity entity, CounterState state, String reason) {
        UUID lockedPlayer = state.lockedPlayerId;
        if (lockedPlayer != null) {
            ChampionTargetLocks.release(lockedPlayer, entity.getUUID());
        }
        // 窗口关闭 = 技能结束 (低频): 不经诊断门控, 真服对账必打。
        LOGGER.info("skill-counter window close champion={} target={} reason={}",
                entity.getUUID(), lockedPlayer, reason);
        state.lockedPlayerId = null;
        state.windowStartTick = Long.MIN_VALUE;
        state.privateCap = null;
    }

    /**
     * 窗内被锁玩家对本怪的每笔伤害折反伤打回。HIGH 优先级: 早于血池 handler (LOWEST) 取消, 读【名义】入伤
     * event.getAmount() (未减伤); 只处理 受击者=开窗冠军 且 攻击者=被锁玩家。
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onCounterChampionHurt(LivingHurtEvent event) {
        // 最廉价早退: 攻击者须是玩家 (窗内只反被锁玩家的伤害; 过滤怪/环境来源)。
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) {
            return;
        }
        LivingEntity victim = event.getEntity();
        CounterState state = stateByChampion.get(victim.getUUID());
        if (state == null || state.windowStartTick == Long.MIN_VALUE) {
            return; // 该冠军无开窗 (非反击单元冠军 / 当前无反击窗)。
        }
        if (!attacker.getUUID().equals(state.lockedPlayerId)) {
            return; // 非被锁玩家: 不反 (窗内只反锁定目标)。
        }
        // 反伤打回的伤害 (我方 champion_thorns / 玩家 vanilla thorns 回弹) 不再触发反击, 防反震/反击互喂死循环:
        // 玩家 vanilla thorns 回弹的 source.getEntity() 恰是被锁玩家, 会穿过上面的锁定校验, 故必须按类型跳过。
        if (event.getSource().is(ChampionDamageTypes.CHAMPION_THORNS)
                || event.getSource().is(DamageTypes.THORNS)) {
            return;
        }
        long nowTick = victim.level().getGameTime();
        if (!ChampionCounterUnitWindow.isWithinWindow(nowTick - state.windowStartTick)) {
            return; // 窗已过 (tick handler 尚未关窗的 tick 内边界): 不反。
        }
        double attackerMaxHp = attacker.getMaxHealth();
        if (!(attackerMaxHp > 0.0D)) {
            return; // 攻击者无有效血量: 反伤 %无基数 (聚合器构造亦要求 >0)。
        }
        MiningChampionData champ = MiningChampions.get(victim).orElse(null);
        if (champ == null) {
            return;
        }
        AffixQuality quality = champ.quality(AffixDef.COUNTER_UNIT);
        if (quality == null) {
            return; // 词条已被移除 (罕见): 不反。
        }

        // 三层封顶 (顺序固定): 名义 (含窗内递增, 2026-07-07 用户定向"越反越疼") -> 本源私有 20%/s ->
        // 聚合器 30%/s 全局 + 40%/窗。
        double nominal = state.privateCap.nominalForNextHit(quality, event.getAmount());
        double afterPrivate = state.privateCap.admit(nominal, nowTick);
        RetaliationAggregator agg = ChampionEffectRegistries.retaliationFor(attacker.getUUID(), attackerMaxHp);
        double reflected = agg.admit(afterPrivate, nowTick);

        // 每笔反伤 = 逐击 (须诊断门控, 仅 10 格内有玩家的怪): 第 n 笔 -> 名义(含递增) -> 私窗剪后 -> 聚合器裁后。
        if (ChampionDiagnostics.shouldTrace(victim)) {
            LOGGER.info("skill-counter reflect champion={} attacker={} hit#{} nominal={} afterPrivate={} afterAgg={}",
                    victim.getUUID(), attacker.getGameProfile().getName(), state.privateCap.reflectedHits(),
                    String.format("%.2f", nominal), String.format("%.2f", afterPrivate),
                    String.format("%.2f", reflected));
        }
        if (reflected <= 0.0D) {
            return; // 三层封顶后无额度: 本笔不反弹 (红线 2)。
        }
        // 真伤 (与反震一致口径): champion_thorns 类型 (bypasses_armor+enchantments), 名义 % 即实付。1.20.1
        // DamageSources.source(...) 重载全 private, 公开路径 = registry 取 Holder 后 new DamageSource(holder, entity)。
        // ChampionAttackHandler 对本类型有跳过守卫 (反伤非攻击, 不再触 on-hit 攻击词条)。
        Holder<DamageType> thornsType = victim.level().registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(ChampionDamageTypes.CHAMPION_THORNS);
        attacker.hurt(new DamageSource(thornsType, victim), (float) reflected);
    }

    /** 冠军死亡: 释放锁 + 摘 per-冠军反击状态防泄漏 (窗内死亡按关窗处理)。 */
    @SubscribeEvent
    public void onCounterChampionDeath(LivingDeathEvent event) {
        CounterState state = stateByChampion.remove(event.getEntity().getUUID());
        if (state != null && state.lockedPlayerId != null) {
            ChampionTargetLocks.release(state.lockedPlayerId, event.getEntity().getUUID());
            // 冠军死亡关窗 = 技能结束 (低频): 真服对账必打。
            LOGGER.info("skill-counter window close champion={} target={} reason=death",
                    event.getEntity().getUUID(), state.lockedPlayerId);
        }
    }

    /** 冠军当前仇恨的存活玩家目标 (无 Mob 目标/非玩家/已死返 null): 锁定候选。 */
    private static ServerPlayer livingPlayerTarget(LivingEntity entity) {
        if (entity instanceof Mob mob && mob.getTarget() instanceof ServerPlayer target && target.isAlive()) {
            return target;
        }
        return null;
    }

    /**
     * 窗内连线粒子: 沿 冠军中心 -> 被锁玩家中心 均布 {@value #LINE_PARTICLE_COUNT} 颗 END_ROD (spec 9A.6 高亮/警告
     * 原版可见原语)。玩家跨维度/登出解析不到则本次不画 (窗口由 tick handler 到期关)。逐 tick 粒子不打诊断日志。
     */
    private static void emitConnectLine(ServerLevel level, LivingEntity champion, UUID lockedPlayerId) {
        if (lockedPlayerId == null) {
            return;
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(lockedPlayerId);
        if (player == null || player.level() != level) {
            return; // 玩家登出/跨维度: 本次不画线 (坐标非同维度; 窗口由 tick handler 到期关)。
        }
        double sx = champion.getX();
        double sy = champion.getY() + champion.getBbHeight() * 0.5D;
        double sz = champion.getZ();
        double ex = player.getX();
        double ey = player.getY() + player.getBbHeight() * 0.5D;
        double ez = player.getZ();
        for (int i = 0; i < LINE_PARTICLE_COUNT; i++) {
            double t = (i + 0.5D) / LINE_PARTICLE_COUNT; // 均布, 避开两端点 (0.05 ~ 0.95)。
            double px = sx + (ex - sx) * t;
            double py = sy + (ey - sy) * t;
            double pz = sz + (ez - sz) * t;
            level.sendParticles(ParticleTypes.END_ROD, px, py, pz, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /**
     * per-冠军反击状态: 被锁玩家 UUID + 反击窗起始 tick (MIN_VALUE = 无窗) + 上次成功锁定 tick (周期计时,
     * MIN_VALUE = 从未锁定) + 本源私有秒窗累加器 (一次开窗一个实例) + 最后触达 tick (TTL 清扫依据)。
     */
    private static final class CounterState {
        private UUID lockedPlayerId = null;
        private long windowStartTick = Long.MIN_VALUE;
        private long lastLockTick = Long.MIN_VALUE;
        private ChampionCounterUnitWindow privateCap = null;
        private long lastTouchedTick = Long.MIN_VALUE;
    }
}
