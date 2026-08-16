package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionDamageTypes;
import com.miningdim.champion.ChampionDiagnostics;
import com.miningdim.champion.ChampionElectroChargePlan;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 冠军【技能词条·电磁蓄力 ELECTRO_CHARGE】(Stage2 批4 波2; ChampionStarAffix spec 7.4 可躲型单点 AOE) 效果施加
 * (集成层)。冠军对当前攻击目标周期性起手: 把目标【此刻站位】锁为落点, 蓄力 2s (期间落点不追踪玩家) 后在落点半径
 * {@value ChampionElectroChargePlan#AOE_RADIUS} 格内引爆一发大额 AOE, 半径内每名存活玩家吃自身 maxHP 百分比的
 * {@code CHAMPION_SKILL_AOE} 伤害并各获 2s 免疫缓冲。数值/几何/门控纯逻辑下沉 {@link ChampionElectroChargePlan}
 * (dev GameTest 真验), 本 handler 只做真服侧: 实体检出/缰绳门控/落点锁定/蓄力与落点/爆点表现/AABB 扫玩家结算。
 *
 * <p>契约边界 (关键): 电磁蓄力【不位移】冠军也【不位移】玩家 —— 落点是世界坐标锁定的原地 AOE, 玩家靠走出半径可躲,
 * 故不涉及 {@link KnockbackSafetyGuard} (无落脚裁决) / {@link PlayerLandingProtection} (无玩家位移)。仅结算完伤害后
 * 逐玩家 {@link AoeImmunityBuffer#grant} 开 2s 缓冲 (红线 3: 一发已足够压制, 窗内其它冠军大额来源被掐 0 防叠杀)。
 * 伤害类型 {@code CHAMPION_SKILL_AOE} 不入 bypasses_armor (经玩家护甲减免) 且被 {@code ChampionAttackHandler} 豁免
 * on-hit rider (不触燃烧/寒霜/损甲), 均由主线在类型/攻击层落定, 本 handler 只构造该类型 DamageSource 下发。
 *
 * <p>状态机 (per-冠军, 两相): COOLDOWN (门控推进冷却周期) -&gt; 到点起手蓄力转 CHARGING (锁定落点, 2s 后引爆) -&gt;
 * 引爆毕回 COOLDOWN。两个时间尺度 (照 {@code ChampionBlinkHandler} 范式):
 * <ul>
 *   <li>{@link #onServerTick} 每 tick 先推进在册【蓄力】(CHARGING 相位需 tick 级精度: 逐 tick 喷自身蓄力云 +
 *       节流描落点环, 到 {@value #CHARGE_TICKS}tick 精确引爆; COOLDOWN 条目在此跳过);</li>
 *   <li>再每 {@value #SCAN_INTERVAL_TICKS}tick(1s) 按玩家 AABB 扫近处冠军 (覆盖命令召唤 + 自然刷), 门控
 *       (有存活攻击目标且在缰绳 {@value ChampionElectroChargePlan#TETHER_RANGE} 格内) 通过者推进冷却周期; 到点起手蓄力。</li>
 * </ul>
 * 有效施放间隔 = 门控冷却周期 + 2s 蓄力 (蓄力期扫描跳过不累冷却, 引爆毕才把冷却清零重计)。蓄力期【落点锁定不追踪】
 * 是可躲窗的本体。冠军死亡 / 蓄力期丢攻击目标 -&gt; 取消本次不引爆 (spec: 甩开/杀死 BOSS 是合法反制)。
 *
 * <p>per-冠军状态双清 (与 {@code ChampionSelfEffectHandler} 同纪律): 冠军死亡摘除 + TTL 清扫兜底 —— 冠军未设
 * persistenceRequired, despawn/区块卸载不发 LivingDeathEvent, 只靠死亡清理会泄漏。实例状态随本 handler 实例存活
 * (由 {@code ChampionSystem#register} 挂 forgeBus), 无静态账本故无需 onServerStopping 清理。位移写无 (纯 AOE),
 * 结算/表现均在 ServerTick END 服务端主线程, 无需 server.execute 转派。
 */
public final class ChampionElectroChargeHandler {

    /** 诊断日志: 批4 波2 电磁蓄力真服首验用 (起手/引爆/丢目标取消各一行, 经 shouldTrace 门控只追近玩家的怪)。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/skill");

    /** 扫描/冷却推进周期 (tick): 1s 扫一次近玩家冠军 (与纯逻辑 {@link ChampionElectroChargePlan#SCAN_INTERVAL_TICKS} 对齐)。 */
    private static final int SCAN_INTERVAL_TICKS = (int) ChampionElectroChargePlan.SCAN_INTERVAL_TICKS;

    /** 蓄力时长 (tick; 用户裁定 2s = 40): 起手到引爆的可躲窗 (与纯逻辑 {@link ChampionElectroChargePlan#CHARGE_TICKS} 对齐)。 */
    private static final int CHARGE_TICKS = (int) ChampionElectroChargePlan.CHARGE_TICKS;

    /**
     * 落点环描点节流 (tick): 每此 tick 描一次 24 点环 (5Hz)。逐 tick 描 24 点 = 24 包/tick 过密, 5Hz 已足够让玩家
     * 肉眼持续看见边界圈可躲, 平衡可见性与粒子包预算 (spec 9A.6 粒子纪律)。
     */
    private static final int RING_DRAW_INTERVAL_TICKS = 4;

    /** 自身蓄力云每 tick 颗数 (spec 9A.6)。 */
    private static final int CHARGE_CLOUD_PARTICLE_COUNT = 5;

    /** 落点环每点颗数 (逐点单颗描边界)。 */
    private static final int RING_PARTICLE_PER_POINT = 1;

    /** 引爆爆点电火花迸发颗数 (spec 9A.6)。 */
    private static final int IMPACT_SPARK_COUNT = 60;

    /** per-冠军蓄力状态; 冠军死亡摘除 + TTL 清扫双保险 (despawn/卸载不发死亡事件的泄漏兜底)。 */
    private final Map<UUID, ElectroState> stateByChampion = new HashMap<>();

    /** 状态 TTL 清扫周期 (tick): 每 60s 扫一次 (低频, 表通常极小)。 */
    private static final int STATE_SWEEP_INTERVAL_TICKS = 1200;

    /**
     * 状态条目 TTL (tick): 5min 未被触达 (未被门控扫描推进 / 未在蓄力中) 即回收。丢态语义安全: 回收后视为"冷却从零起",
     * 而冷却周期至多 14s、无目标/超缰绳本就冻结不推进 —— 5min 未触达的冠军必远离玩家或长期脱战, 回收不改任何可观测
     * 行为, 只兜内存。
     */
    private static final long STATE_TTL_TICKS = 6000L;

    /**
     * 每 tick 先推进在册蓄力 (tick 级), 再每 1s 门控扫描推进冷却周期。蓄力需 tick 精度: 逐 tick 喷自身蓄力云、按
     * {@value #RING_DRAW_INTERVAL_TICKS} 节流描落点环, 到 {@value #CHARGE_TICKS}tick 精确引爆; 扫描 (创建/推进
     * COOLDOWN 态) 是 1s 节流 (与闪光/视觉干扰同源, 冷却周期 10~14s 无需亚秒精度)。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        long nowTick = server.overworld().getGameTime();

        // 每 tick: 推进在册蓄力 (COOLDOWN 条目在内部跳过, 廉价; 无 state 直接早退)。
        if (!stateByChampion.isEmpty()) {
            advanceCharges(server, nowTick);
        }

        // 1s 扫描: 门控推进冷却周期 + 到点起手蓄力。
        if (server.getTickCount() % SCAN_INTERVAL_TICKS == 0) {
            scanNearbyChampions(server, nowTick);
            if (server.getTickCount() % STATE_SWEEP_INTERVAL_TICKS == 0) {
                sweepStaleStates(nowTick);
            }
        }
    }

    /**
     * 每 tick 推进在册蓄力: 遍历状态表, 仅处理 CHARGING 相位条目 (按维度 O(1) 检出实体)。实体死亡/despawn/卸载 -&gt;
     * 摘除 (取消不引爆); 蓄力期丢攻击目标 -&gt; 回冷却取消不引爆; 到 {@value #CHARGE_TICKS}tick -&gt; 引爆结算后回冷却;
     * 进行中 -&gt; 逐 tick 喷自身蓄力云 + 节流描落点环。
     */
    private void advanceCharges(MinecraftServer server, long nowTick) {
        Iterator<Map.Entry<UUID, ElectroState>> it = stateByChampion.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ElectroState> entry = it.next();
            ElectroState state = entry.getValue();
            if (state.phase != Phase.CHARGING) {
                continue; // COOLDOWN: 由 1s 扫描推进, 此处不动。
            }
            LivingEntity entity = resolve(server, state.dimension, entry.getKey());
            if (entity == null || !entity.isAlive() || !(entity instanceof Mob)
                    || !(entity.level() instanceof ServerLevel level)) {
                it.remove(); // 冠军死亡/despawn/卸载/非 Mob: 蓄力作废, 摘除 (取消不引爆)。
                continue;
            }
            state.lastTouchedTick = nowTick;

            ServerPlayer target = resolveTarget(entity);
            if (target == null) {
                // 蓄力期丢攻击目标 (目标死亡/脱离索敌): 取消本次不引爆, 回冷却重新充能。
                if (ChampionDiagnostics.shouldTrace(entity)) {
                    LOGGER.info("skill-electro cancel champion={} tier{} reason=target-lost",
                            entity.getUUID(), state.star);
                }
                returnToCooldown(state);
                continue;
            }
            if (nowTick >= state.chargeEndTick) {
                detonate(level, entity, state);
                returnToCooldown(state);
                continue;
            }
            // 蓄力进行中: 自身蓄力云 (跟随冠军) + 节流描落点环 (锁定不追踪)。
            emitChargeCloud(level, entity);
            if (nowTick % RING_DRAW_INTERVAL_TICKS == 0) {
                emitLandingRing(level, state);
            }
        }
    }

    /**
     * 每秒扫近玩家冠军 (与 {@code ChampionBlinkHandler} 同范式): 按玩家 AABB 扫 + 自研 capability 检出装配电磁蓄力的
     * 冠军 (命令召唤 + 自然刷一视同仁), 门控通过者推进冷却周期; 多玩家同看一冠军本轮只结算一次。
     */
    private void scanNearbyChampions(MinecraftServer server, long nowTick) {
        for (ChampionProximityScanner.Sighting sighting : ChampionProximityScanner.sightings(server)) {
            if (!sighting.entity().isAlive()) {
                continue; // 快照按 tick 复用, 同 tick 更早的 handler 可能已致死: 存活性逐条重查。
            }
            applyCooldownScan(sighting.entity(), nowTick);
        }
    }

    /**
     * 对一只实体 (若是装配电磁蓄力的本工程冠军且处 COOLDOWN) 推进冷却周期; 到点起手蓄力。CHARGING 中的冠军由每 tick
     * 推进独占, 扫描跳过; 无目标/超缰绳冻结不推进 (不新建/触达 state, 已有 COOLDOWN 态 elapsed 原样保留)。
     */
    private void applyCooldownScan(LivingEntity entity, long nowTick) {
        MiningChampionData champ = MiningChampions.get(entity).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 非本工程冠军。
        }
        AffixQuality quality = champ.quality(AffixDef.ELECTRO_CHARGE);
        if (quality == null) {
            return; // 未装配电磁蓄力: 不建状态 (防为无关冠军泄漏 state)。
        }
        ElectroState existing = stateByChampion.get(entity.getUUID());
        if (existing != null && existing.phase == Phase.CHARGING) {
            return; // 蓄力期由每 tick 推进独占, 扫描不并发动其周期。
        }
        ServerPlayer target = resolveTarget(entity);
        if (target == null) {
            return; // 无存活攻击目标: 冻结不耗周期 (不建/不触达状态)。
        }
        if (!ChampionElectroChargePlan.withinTether(entity.distanceToSqr(target))) {
            return; // 超缰绳: 冻结不推进 (冷却 tick 保留; 长期超缰绳才 TTL 回收)。
        }
        if (!(entity instanceof Mob mob) || !(entity.level() instanceof ServerLevel level)) {
            return; // 冠军 capability 只挂 Mob, 且扫描来自 ServerLevel; 防御性早退。
        }

        ElectroState state = stateByChampion.computeIfAbsent(entity.getUUID(),
                k -> new ElectroState(level.dimension()));
        state.lastTouchedTick = nowTick;
        state.elapsedCycleTicks = ChampionElectroChargePlan.advanceCycle(state.elapsedCycleTicks);
        if (!ChampionElectroChargePlan.cycleReady(state.elapsedCycleTicks, quality)) {
            return; // 未到冷却周期 (充能中)。
        }
        // 到点: 起手蓄力 (锁定落点 = 目标当前站位); 冷却累加于引爆/取消时由 returnToCooldown 清零重计。
        beginCharge(mob, level, target, champ.star(), quality, state, nowTick);
    }

    /**
     * 起手蓄力: 锁定落点为目标此刻站位 (蓄力期不追踪 = 可躲窗前提), 记品质/星级/引爆 tick, 转 CHARGING; 播起手蓄力音
     * + 首帧落点环让玩家即刻看见边界圈。落点 Y 取目标脚下 (地面 AOE 圆心)。
     */
    private void beginCharge(Mob mob, ServerLevel level, ServerPlayer target,
                             int star, AffixQuality quality, ElectroState state, long nowTick) {
        state.phase = Phase.CHARGING;
        state.landingX = target.getX();
        state.landingY = target.getY();
        state.landingZ = target.getZ();
        state.quality = quality;
        state.star = star;
        state.chargeEndTick = nowTick + CHARGE_TICKS;
        state.lastTouchedTick = nowTick;

        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 0.8F, 1.4F);
        emitLandingRing(level, state); // 首帧落点环 (后续每 4 tick 由 advanceCharges 续描)。

        if (ChampionDiagnostics.shouldTrace(mob)) {
            LOGGER.info("skill-electro windup champion={} tier{} q={} target={} landing=[{},{},{}] in={}t",
                    mob.getUUID(), star, quality, target.getGameProfile().getName(),
                    fmt(state.landingX), fmt(state.landingY), fmt(state.landingZ), CHARGE_TICKS);
        }
    }

    /**
     * 引爆结算: 爆点大电火花迸发 + 爆炸粒子 + 雷击音; AABB 扫落点半径内存活玩家, 各吃
     * {@link ChampionElectroChargePlan#aoeDamage} 名义伤 (自身 maxHP × 品质百分比) 的 CHAMPION_SKILL_AOE, 结算完
     * 【逐个】{@link AoeImmunityBuffer#grantIfNotBuffered} 开 2s 缓冲 (红线 3)。已走出半径的玩家 (可躲) 不结算不 grant。
     *
     * <p>DamageSource 构造照 {@code ChampionDeathMarkHandler#executePlayer} 的 registry Holder 写法 (1.20.1
     * DamageSources.source 全 private, 公开路径 = registry 取 Holder 后 new DamageSource(holder, entity))。首发时
     * 玩家尚未获缓冲 (grant 在 hurt 之后), 故 AoeImmunityBuffer 的 HIGHEST 闸不掐首发; 若玩家已在上一发缓冲窗内则
     * 首发即被掐 0, 此时窗内首发不构成新的压制, {@code grantIfNotBuffered} 不再续窗 (F102 修复: 旧版无条件 grant
     * 会被同一玩家站在爆点反复触发的零伤命中链式续到 now+2s, 与本技能"锁定落点可躲"的设计意图相反)。
     */
    private void detonate(ServerLevel level, LivingEntity champion, ElectroState state) {
        double cx = state.landingX;
        double cy = state.landingY;
        double cz = state.landingZ;
        AffixQuality quality = state.quality;
        emitImpact(level, cx, cy, cz);

        Holder<DamageType> aoeType = level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(ChampionDamageTypes.CHAMPION_SKILL_AOE);
        DamageSource source = new DamageSource(aoeType, champion);

        double r = ChampionElectroChargePlan.AOE_RADIUS;
        AABB box = new AABB(cx - r, cy - r, cz - r, cx + r, cy + r, cz + r);
        int hitCount = 0;
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, box, ServerPlayer::isAlive)) {
            if (!ChampionElectroChargePlan.withinAoe(player.distanceToSqr(cx, cy, cz))) {
                continue; // 已走出半径: 免伤 (可躲, 红线 3 放宽前提)。
            }
            float dmg = (float) ChampionElectroChargePlan.aoeDamage(quality, player.getMaxHealth());
            player.hurt(source, dmg);
            AoeImmunityBuffer.grantIfNotBuffered(player); // 结算完自身伤害后开 2s 缓冲 (窗内后续大额 AOE 被掐 0)。
            hitCount++;
        }

        if (ChampionDiagnostics.shouldTrace(champion)) {
            LOGGER.info("skill-electro detonate champion={} tier{} q={} at=[{},{},{}] hit={}",
                    champion.getUUID(), state.star, quality, fmt(cx), fmt(cy), fmt(cz), hitCount);
        }
    }

    /** 冠军死亡: 摘 per-冠军状态防泄漏 (蓄力中死亡即取消不引爆)。 */
    @SubscribeEvent
    public void onChampionDeath(LivingDeathEvent event) {
        stateByChampion.remove(event.getEntity().getUUID());
    }

    /** 回收 TTL 内未被触达的状态条目 (语义安全性见 {@link #STATE_TTL_TICKS})。 */
    private void sweepStaleStates(long nowTick) {
        // MIN_VALUE (理论不出现: 建条目点即刻刷触达) 显式视为过期, 防减法溢出漏回收。
        stateByChampion.values().removeIf(state -> state.lastTouchedTick == Long.MIN_VALUE
                || nowTick - state.lastTouchedTick > STATE_TTL_TICKS);
    }

    /**
     * 回冷却态 (引爆毕 / 蓄力期丢目标取消 共用): 清蓄力字段 + 冷却累加清零重计 (下个门控周期从零起)。品质/星级仅蓄力
     * 期有意义, COOLDOWN 期由扫描重读 capability, 故一并清空。落点坐标留旧值 (COOLDOWN 不用, 下次 beginCharge 覆盖)。
     */
    private static void returnToCooldown(ElectroState state) {
        state.phase = Phase.COOLDOWN;
        state.elapsedCycleTicks = 0L;
        state.quality = null;
        state.star = 0;
        state.chargeEndTick = Long.MIN_VALUE;
    }

    /** 自身蓄力云 (spec 9A.6 原版可见): 冠军身上喷电火花, 让玩家肉眼辨识这只在蓄力。 */
    private static void emitChargeCloud(ServerLevel level, LivingEntity champion) {
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                champion.getX(), champion.getY() + champion.getBbHeight() * 0.6D, champion.getZ(),
                CHARGE_CLOUD_PARTICLE_COUNT, 0.45D, 0.5D, 0.45D, 0.08D);
    }

    /** 落点环描边界圈 (spec 9A.6): 逐点在半径 {@value ChampionElectroChargePlan#AOE_RADIUS} 格圆周喷单颗电火花, 让玩家判定是否已走出。 */
    private static void emitLandingRing(ServerLevel level, ElectroState state) {
        double y = state.landingY + 0.15D;
        for (double[] p : ChampionElectroChargePlan.ringPoints(state.landingX, state.landingZ)) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p[0], y, p[1],
                    RING_PARTICLE_PER_POINT, 0.0D, 0.02D, 0.0D, 0.0D);
        }
    }

    /** 引爆爆点 (spec 9A.6): 落点大电火花迸发 + 爆炸粒子 + 雷击音, 让全场肉眼/耳可辨已引爆。 */
    private static void emitImpact(ServerLevel level, double x, double y, double z) {
        double r = ChampionElectroChargePlan.AOE_RADIUS;
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y + 0.4D, z,
                IMPACT_SPARK_COUNT, r * 0.6D, 0.5D, r * 0.6D, 0.15D);
        level.sendParticles(ParticleTypes.EXPLOSION, x, y + 0.5D, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.playSound(null, x, y, z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.HOSTILE, 1.0F, 1.0F);
        level.playSound(null, x, y, z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 1.0F, 1.2F);
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

    /** 按状态记录的维度检出在册冠军实体 (维度未加载/实体不在返 null; UUID -&gt; 实体为 O(1) 表查, 照闪光/自我修复范式)。 */
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

    /** 电磁蓄力相位: 冷却 (门控推进周期) / 蓄力 (落点锁定 2s 后引爆)。 */
    private enum Phase {
        COOLDOWN,
        CHARGING
    }

    /**
     * per-冠军电磁蓄力状态: 相位 + 已累加冷却 tick (COOLDOWN 期门控扫描推进, 引爆/取消清零) + 所在维度 (蓄力期每 tick
     * O(1) 检出实体) + 锁定落点 (x,y,z) + 蓄力品质 (引爆时算伤害) + 星级 (诊断) + 引爆到点 tick + 最后触达 tick
     * (TTL 清扫依据)。落点/品质/星级仅 CHARGING 期有意义, returnToCooldown 清品质/星级/引爆 tick。
     */
    private static final class ElectroState {
        private final ResourceKey<Level> dimension;
        private Phase phase = Phase.COOLDOWN;
        private long elapsedCycleTicks = 0L;
        private double landingX;
        private double landingY;
        private double landingZ;
        private AffixQuality quality = null;
        private int star = 0;
        private long chargeEndTick = Long.MIN_VALUE;
        private long lastTouchedTick = Long.MIN_VALUE;

        private ElectroState(ResourceKey<Level> dimension) {
            this.dimension = dimension;
        }
    }
}
