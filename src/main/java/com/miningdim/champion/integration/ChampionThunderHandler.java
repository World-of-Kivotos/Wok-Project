package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionDamageTypes;
import com.miningdim.champion.ChampionDiagnostics;
import com.miningdim.champion.ChampionThunderPlan;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 冠军【技能词条·天雷 THUNDER】(批4 波2; ChampionStarAffix spec 7.4 可躲多点 AOE) 效果施加 (集成层)。天雷是电磁蓄力
 * (单点) 的多点版: 冠军对当前攻击目标周期性起雷, 以目标为圆心散布 N 个落点 (3-8 格随机, 两两间距 &gt;=5 格 = 2×半径
 * 不重叠), 1.5s 粒子环预兆 (锁定不追踪, 给玩家散开的可躲窗), 预兆到点逐落点 {@link LightningBolt#setVisualOnly(boolean)}
 * 落雷 (视觉+雷声, 无原版火/伤) + 半径 {@value ChampionThunderPlan#PER_POINT_RADIUS} 格内玩家吃"每点 %maxHP"的
 * CHAMPION_SKILL_AOE, 命中【结算完自身伤害后】逐玩家 grant 2s 免疫缓冲。
 *
 * <p>不可同点叠杀语义 (spec 红线 3): 落点两两间距 &gt;=2×半径由 {@link ChampionThunderPlan#selectScatterPoints}
 * 保证 (核心杀伤圈至多相切), 加上"命中即 grant 免疫缓冲"—— 同一玩家被首点命中并 grant 后, 后续点若也覆盖他
 * (仅两切点边界的极窄带) 时其 CHAMPION_SKILL_AOE 会被 {@link AoeImmunityBuffer} HIGHEST 闸掐 0, 单玩家一次天雷至多
 * 被一点结算。CHAMPION_SKILL_AOE 不触近战 on-hit rider ({@code ChampionAttackHandler} 已豁免), 吃玩家护甲。
 *
 * <p>状态机 (per-冠军, 两相): CHARGING (门控推进周期) -&gt; 到点选散落点则转 TELEGRAPH (锁定落点集 + 1.5s 后落雷)
 * -&gt; 落雷毕回 CHARGING。两个时间尺度 (照 {@code ChampionBlinkHandler} 范式):
 * <ul>
 *   <li>{@link #onServerTick} 每 tick 先推进在册【预兆】(1.5s 窗需 tick 级精度: 逐 tick 隔帧喷落点粒子环, 到点落雷;
 *       charging 条目在此跳过);</li>
 *   <li>再每 {@value #SCAN_INTERVAL_TICKS}tick(1s) 按玩家 AABB 扫近处冠军 (覆盖命令召唤 + 自然刷), 门控通过者推进
 *       周期; 到点选散落点起预兆 (点集为空则本周期放弃, 照进下周期)。</li>
 * </ul>
 *
 * <p>门控 (同电磁): 有存活攻击目标玩家且距目标 &lt;= {@value ChampionThunderPlan#TARGET_RANGE} 格才推进周期
 * (丢目标/超范围冻结不耗周期)。落点/伤害均只作用于玩家 (level.players()), 冠军自身/召唤物不受 (AOE 只判玩家)。
 *
 * <p>per-冠军状态双清 (与 {@code ChampionBlinkHandler} 同纪律): 冠军死亡摘除 + TTL 清扫兜底 (冠军未设
 * persistenceRequired, despawn/区块卸载不发 LivingDeathEvent, 只靠死亡清理会泄漏)。实例状态随本 handler 实例存活
 * (由 {@code ChampionSystem#register} 挂 forgeBus), 无静态账本故无需 onServerStopping 清理。落雷/伤害/粒子写操作
 * 在 ServerTick END 内执行 (服务端主线程), 无需 server.execute 转派。
 */
public final class ChampionThunderHandler {

    /** 诊断日志: 批4 波2 天雷真服首验用 (预兆起/落雷各一行, shouldTrace 门控只追近玩家的怪)。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/thunder");

    /** 扫描/周期推进周期 (tick): 1s 扫一次近玩家冠军 (与纯逻辑 {@link ChampionThunderPlan#SCAN_INTERVAL_TICKS} 对齐)。 */
    private static final int SCAN_INTERVAL_TICKS = (int) ChampionThunderPlan.SCAN_INTERVAL_TICKS;

    /** 预兆时长 (tick): spec 到点前 1.5s 逐落点粒子环预兆; 预兆期落点集锁定不追踪玩家, 给玩家散开窗。 */
    private static final int WARNING_TICKS = ChampionThunderPlan.WARNING_TICKS;

    /** 预兆粒子环隔帧节流 (tick): 每 2 tick 喷一次环 (30tick 窗喷 ~15 帧), 压 sendParticles 包量 (spec 9A.6 粒子预算)。 */
    private static final int RING_EMIT_INTERVAL_TICKS = 2;

    /** 单落点预兆环采样点数 (绕半径圆均匀取点标出杀伤边界, 让玩家肉眼判半径 2.5 的危险圈)。 */
    private static final int RING_SAMPLES = 12;

    /** 作用的玩家可见距离 (格; 与自身被动/BOSS 血条同量级)。远离该范围的冠军不结算 (无玩家在场无需起雷)。 */
    private static final double VIEW_RANGE = 48.0D;

    /** per-冠军天雷循环状态; 冠军死亡摘除 + TTL 清扫双保险 (despawn/卸载不发死亡事件的泄漏兜底)。 */
    private final Map<UUID, ThunderState> stateByChampion = new HashMap<>();

    /** 状态 TTL 清扫周期 (tick): 每 60s 扫一次 (低频, 表通常极小)。 */
    private static final int STATE_SWEEP_INTERVAL_TICKS = 1200;

    /**
     * 状态条目 TTL (tick): 5min 未被触达 (未被门控扫描推进) 即回收。丢态语义安全: 回收后视为"循环从零起", 周期至多
     * 16s、无目标/超范围本就冻结 —— 5min 未触达的冠军必远离玩家或长期脱战, 回收不改任何可观测行为, 只兜内存。
     */
    private static final long STATE_TTL_TICKS = 6000L;

    /**
     * 每 tick 先推进在册预兆 (tick 级), 再每 1s 门控扫描推进周期。预兆需 tick 精度: 1.5s 窗内隔帧喷落点粒子环,
     * 到点即落雷; 扫描 (创建/推进 charging 态) 是 1s 节流 (周期 12~16s 无需亚秒精度)。
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

        // 1s 扫描: 门控推进周期 + 到点起预兆。
        if (server.getTickCount() % SCAN_INTERVAL_TICKS == 0) {
            scanNearbyChampions(server, nowTick);
            if (server.getTickCount() % STATE_SWEEP_INTERVAL_TICKS == 0) {
                sweepStaleStates(nowTick);
            }
        }
    }

    /**
     * 每 tick 推进在册预兆: 遍历状态表, 仅处理 TELEGRAPH 相位条目 (按维度 O(1) 检出实体)。实体消失/死亡 -&gt; 摘除
     * (预兆作废); 未到点 -&gt; 隔帧喷各落点预兆粒子环 (锁定落点, 不追踪玩家); 到点 -&gt; 逐落点落雷 + AOE + 免疫缓冲,
     * 回 CHARGING。
     */
    private void advanceTelegraphs(MinecraftServer server, long nowTick) {
        Iterator<Map.Entry<UUID, ThunderState>> it = stateByChampion.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ThunderState> entry = it.next();
            ThunderState state = entry.getValue();
            if (state.phase != Phase.TELEGRAPH) {
                continue; // charging: 由 1s 扫描推进, 此处不动。
            }
            LivingEntity entity = resolve(server, state.dimension, entry.getKey());
            if (entity == null || !entity.isAlive() || !(entity instanceof Mob mob)
                    || !(entity.level() instanceof ServerLevel level)) {
                it.remove(); // despawn/卸载/死亡/非 Mob: 预兆作废, 摘除。
                continue;
            }
            if (nowTick >= state.warningEndTick) {
                executeStrikes(mob, level, state, nowTick);
            } else if (nowTick % RING_EMIT_INTERVAL_TICKS == 0) {
                emitWarningRings(level, state.strikePoints, state.strikeY);
            }
        }
    }

    /**
     * 每秒扫近玩家冠军 (与 {@code ChampionBlinkHandler} 同范式): 按玩家 AABB 扫 + 自研 capability 检出装配天雷的
     * 冠军 (命令召唤 + 自然刷一视同仁), 门控通过者推进周期; 多玩家同看一冠军本轮只结算一次。
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
                    applyThunderScan(entity, nowTick);
                }
            }
        }
    }

    /** 对一只实体 (若是装配天雷的本工程冠军) 推进周期; 到点选散落点起预兆。 */
    private void applyThunderScan(LivingEntity entity, long nowTick) {
        MiningChampionData champ = MiningChampions.get(entity).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 非本工程冠军。
        }
        AffixQuality quality = champ.quality(AffixDef.THUNDER);
        if (quality == null) {
            return; // 未装配天雷: 不建状态 (防为无关冠军泄漏 state)。
        }

        ThunderState existing = stateByChampion.get(entity.getUUID());
        if (existing != null && existing.phase == Phase.TELEGRAPH) {
            return; // 预兆期由每 tick 推进独占, 扫描不并发动其周期。
        }

        ServerPlayer target = resolveTarget(entity);
        // 门控 (同电磁): 有存活目标且在门控范围内才推进 (丢目标/超范围冻结不耗周期)。
        if (target == null || !ChampionThunderPlan.withinTargetRange(entity.distanceToSqr(target))) {
            return;
        }
        if (!(entity instanceof Mob mob) || !(entity.level() instanceof ServerLevel level)) {
            return; // 冠军 capability 只挂 Mob, 且扫描来自 ServerLevel; 防御性早退。
        }

        ThunderState state = stateByChampion.computeIfAbsent(entity.getUUID(),
                k -> new ThunderState(level.dimension()));
        state.lastTouchedTick = nowTick;
        state.elapsedCycleTicks = ChampionThunderPlan.advanceCycle(state.elapsedCycleTicks);
        if (!ChampionThunderPlan.cycleReady(state.elapsedCycleTicks, quality)) {
            return; // 未到周期 (充能中)。
        }
        // 到点: 清零重计 (无论落点是否凑齐, 周期照走不补偿, 单一权威在 handler 不复制纯逻辑)。
        state.elapsedCycleTicks = 0L;
        beginTelegraphOrSkip(mob, level, target, quality, state, nowTick);
    }

    /**
     * 到点选散落点起预兆: 以目标为圆心, handler 生成随机 (角度, 距离) 提案喂
     * {@link ChampionThunderPlan#selectScatterPoints} 拒绝采样挑出两两达标的落点集; 锁定点集 + 目标脚下 Y + 品质,
     * 转 TELEGRAPH; actionbar 警告目标玩家散开 + 喷首帧粒子环。点集为空 (理论不出现) 则本周期放弃 (保持 CHARGING)。
     */
    private void beginTelegraphOrSkip(Mob mob, ServerLevel level, ServerPlayer target,
                                      AffixQuality quality, ThunderState state, long nowTick) {
        double centerX = target.getX();
        double centerZ = target.getZ();
        double strikeY = target.getY(); // 落点竖直锚 = 目标脚下 (预兆期锁定, 不追踪玩家移动)。
        int desiredCount = ChampionThunderPlan.pointCount(quality);

        RandomSource random = level.getRandom();
        int budget = ChampionThunderPlan.scatterAttemptBudget(desiredCount);
        double[] angles = new double[budget];
        double[] distances = new double[budget];
        double span = ChampionThunderPlan.MAX_SCATTER_DISTANCE - ChampionThunderPlan.MIN_SCATTER_DISTANCE;
        for (int i = 0; i < budget; i++) {
            angles[i] = random.nextDouble() * (Math.PI * 2.0D);
            distances[i] = ChampionThunderPlan.MIN_SCATTER_DISTANCE + random.nextDouble() * span;
        }
        List<ChampionThunderPlan.BlastPoint> points =
                ChampionThunderPlan.selectScatterPoints(centerX, centerZ, angles, distances, desiredCount);

        if (points.isEmpty()) {
            // 提案全被拒 (理论不出现: 预算 desiredCount×8 在 3-8 环内必凑出 ≥1 点): 本周期放弃, 保持 CHARGING。
            if (ChampionDiagnostics.shouldTrace(mob)) {
                LOGGER.info("skill-thunder champion={} tier{} give-up (empty scatter)",
                        mob.getType().getDescriptionId(), quality);
            }
            return;
        }

        state.phase = Phase.TELEGRAPH;
        state.strikePoints = points;
        state.strikeY = strikeY;
        state.castQuality = quality;
        state.warningEndTick = nowTick + WARNING_TICKS;

        target.displayClientMessage(Component.literal("天雷 锁定! 散开!"), true);
        emitWarningRings(level, points, strikeY); // 预兆首帧 (后续由 advanceTelegraphs 隔帧续喷)。
        if (ChampionDiagnostics.shouldTrace(mob)) {
            LOGGER.info("skill-thunder champion={} tier{} telegraph target={} points={} in={}t",
                    mob.getType().getDescriptionId(), quality, target.getGameProfile().getName(),
                    points.size(), WARNING_TICKS);
        }
    }

    /**
     * 预兆到点: 逐落点落雷 (visual-only, 视觉+雷声由客户端自播, 无原版火/伤) + 对半径内玩家结算 CHAMPION_SKILL_AOE
     * (每点 %maxHP) + 命中【结算完自身伤害后】逐玩家 grant 2s 免疫缓冲。落雷毕回 CHARGING 重充能。
     *
     * <p>叠杀兜底: 逐点顺序结算, 同一玩家被首点命中并 grant 后, 后续点对他的 hurt 被 {@link AoeImmunityBuffer}
     * HIGHEST 掐 0 (落点两两间距 &gt;=2×半径, 仅两切点边界极窄带可能双覆盖) —— 单玩家一次天雷至多被一点结算。
     */
    private void executeStrikes(Mob mob, ServerLevel level, ThunderState state, long nowTick) {
        Holder<DamageType> aoeType = level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(ChampionDamageTypes.CHAMPION_SKILL_AOE);
        DamageSource aoeSource = new DamageSource(aoeType, mob);
        AffixQuality quality = state.castQuality;
        boolean trace = ChampionDiagnostics.shouldTrace(mob);
        int pointCount = state.strikePoints.size();

        int hits = 0;
        for (ChampionThunderPlan.BlastPoint point : state.strikePoints) {
            spawnVisualBolt(level, point.x(), state.strikeY, point.z());
            for (ServerPlayer player : level.players()) {
                if (!player.isAlive()) {
                    continue;
                }
                double dx = player.getX() - point.x();
                double dz = player.getZ() - point.z();
                if (!ChampionThunderPlan.withinBlast(dx * dx + dz * dz)) {
                    continue; // 玩家不在该点杀伤圈: 已散开, 躲过此点。
                }
                float damage = (float) ChampionThunderPlan.perPointDamage(player.getMaxHealth(), quality);
                player.hurt(aoeSource, damage);
                AoeImmunityBuffer.grant(player); // 结算完自身伤害后 grant: 窗内后续点/其它冠军来源掐 0 (红线 3)。
                hits++;
            }
        }

        // 回 CHARGING 重充能 (下个周期再起雷)。
        state.phase = Phase.CHARGING;
        state.strikePoints = null;
        state.strikeY = 0.0D;
        state.castQuality = null;
        state.warningEndTick = Long.MIN_VALUE;
        state.lastTouchedTick = nowTick;

        if (trace) {
            LOGGER.info("skill-thunder champion={} strike points={} hits={} tier{}",
                    mob.getType().getDescriptionId(), pointCount, hits, quality);
        }
    }

    /**
     * 落一记 visual-only 雷 (spec 9A.6 原版可见原语): 视觉闪光 + 雷声由【客户端复制实体自己的 tick】播放
     * (playLocalSound 分支), 与服务端 tick 内容无关。审查修复: vanilla 服务端 tick 里 powerLightningRod (避雷针
     * 通电)/clearCopperOnLightningStrike (铜方块冲刷脱氧化)/gameEvent(LIGHTNING_STRIKE) (惊动潜声传感器/监守者)
     * 三个世界写【不受 visualOnly 抑制】(它只抑火/伤) —— 天雷是判决伤害视觉, 不得永久改玩家建筑/触发红石/惊动
     * 监守者, 故用服务端阉割子类把本体 tick 整个掐掉。伤害走我方 CHAMPION_SKILL_AOE, 不叠原版雷击伤。
     */
    private static void spawnVisualBolt(ServerLevel level, double x, double y, double z) {
        LightningBolt bolt = new VisualOnlyLightning(level);
        bolt.moveTo(x, y, z, 0.0F, 0.0F);
        bolt.setVisualOnly(true); // 双保险: 即使未来 tick 改回 super, 火/伤仍被原版 visualOnly 闸住。
        level.addFreshEntity(bolt);
    }

    /**
     * 服务端阉割雷: 覆写 tick 跳过 vanilla 全部世界副作用 (通电/脱铜/震动事件), 存活 10t 后消散。客户端经
     * AddEntity 包按 LIGHTNING_BOLT 类型构造【原版】复制实体, 闪光/雷声照常 (不依赖服务端 tick)。
     */
    private static final class VisualOnlyLightning extends LightningBolt {
        private int visualLife;

        VisualOnlyLightning(ServerLevel level) {
            super(EntityType.LIGHTNING_BOLT, level);
        }

        @Override
        public void tick() {
            // 刻意不调 super.tick(): 静止视觉实体无需物理/流体/火焰逻辑, 只按拍消散。
            if (++visualLife > 10) {
                discard();
            }
        }
    }

    /** 逐落点喷预兆粒子环。 */
    private static void emitWarningRings(ServerLevel level, List<ChampionThunderPlan.BlastPoint> points, double y) {
        for (ChampionThunderPlan.BlastPoint point : points) {
            emitWarningRing(level, point.x(), y, point.z());
        }
    }

    /**
     * 单落点预兆环 (spec 9A.6): 绕半径 {@value ChampionThunderPlan#PER_POINT_RADIUS} 圆均匀取 {@value #RING_SAMPLES}
     * 点各喷一颗电火花, 标出杀伤边界让玩家肉眼判危险圈可躲。
     */
    private static void emitWarningRing(ServerLevel level, double centerX, double y, double centerZ) {
        double radius = ChampionThunderPlan.PER_POINT_RADIUS;
        for (int i = 0; i < RING_SAMPLES; i++) {
            double angle = (Math.PI * 2.0D) * i / RING_SAMPLES;
            double px = centerX + radius * Math.cos(angle);
            double pz = centerZ + radius * Math.sin(angle);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, px, y + 0.1D, pz, 1, 0.0D, 0.0D, 0.0D, 0.0D);
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

    /** 按状态记录的维度检出在册冠军实体 (维度未加载/实体不在返 null; UUID -&gt; 实体为 O(1) 表查, 照闪光范式)。 */
    private static LivingEntity resolve(MinecraftServer server, ResourceKey<Level> dimension, UUID id) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            return null;
        }
        Entity found = level.getEntity(id);
        return found instanceof LivingEntity living ? living : null;
    }

    /** 天雷循环相位: 充能 (门控推进周期) / 预兆 (锁定落点集 1.5s 后落雷)。 */
    private enum Phase {
        CHARGING,
        TELEGRAPH
    }

    /**
     * per-冠军天雷状态: 相位 + 已累加循环 tick (门控扫描推进, 到点清零) + 所在维度 (预兆期每 tick O(1) 检出实体) +
     * 预兆锁定落点集 + 落点竖直锚 Y + 施放品质 (落雷时算伤害/落点数) + 预兆到点 tick + 最后触达 tick (TTL 清扫依据)。
     */
    private static final class ThunderState {
        private final ResourceKey<Level> dimension;
        private Phase phase = Phase.CHARGING;
        private long elapsedCycleTicks = 0L;
        private List<ChampionThunderPlan.BlastPoint> strikePoints = null;
        private double strikeY = 0.0D;
        private AffixQuality castQuality = null;
        private long warningEndTick = Long.MIN_VALUE;
        private long lastTouchedTick = Long.MIN_VALUE;

        private ThunderState(ResourceKey<Level> dimension) {
            this.dimension = dimension;
        }
    }
}
