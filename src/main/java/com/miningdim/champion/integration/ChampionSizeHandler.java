package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionDiagnostics;
import com.miningdim.champion.ChampionSizeScale;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.network.ChampionSizeS2C;
import com.miningdim.network.MiningNetwork;
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
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;
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
 * 冠军【体型词条·巨大化/缩小化】服务端 AABB 缩放 + 客户端同步广播 + 形态守卫 (ChampionStarAffix spec 9A.3 #17
 * 体型渲染 + 9.4 形态守卫; 批4 波3)。三块职责:
 * <ul>
 *   <li>服务端碰撞箱缩放: {@link #onEntitySize} 在 {@code EntityEvent.Size} 按 capability 体型系数缩放服务端 AABB
 *       (含 eyeHeight)。首帧带生成期形态守卫 (放大 AABB 不容纳则降体型档重验, spec 9.4) + 向 tracking 玩家广播尺寸。</li>
 *   <li>客户端同步: capability 不同步客户端, 故盖章首帧 (Size 事件) 广播 {@code TRACKING_ENTITY} + 玩家开始追踪
 *       ({@link #onStartTracking}) 补发后来者, 客户端 {@code ChampionSizeRenderClient} 据此渲染/缩碰撞箱。</li>
 *   <li>运行时形态守卫 (spec 9.4): {@link #onServerTick} 1s 扫巨大化冠军, 连续 2 次被几何阻挡 -&gt; 0.5s 预兆后
 *       blink 到附近安全落点 (过 {@link KnockbackSafetyGuard} 单点裁决 + 容纳放大 AABB + 距玩家 ≥1 格; 内 CD 3s);
 *       并按品质挂巨大化移速补偿 (MULTIPLY_TOTAL, 独立 UUID)。</li>
 * </ul>
 *
 * <p>体型真源: 系数来自 {@link ChampionSizeScale}(读 {@link AffixDef} 副数值), 与血量乘数 (读主数值) 分表; 守卫
 * 降档只改尺寸系数不动 capability 里的词条品质 (spec 9.4)。
 *
 * <p>per-冠军状态 (最终尺寸系数 + 守卫相位) 双清: 冠军死亡摘除 + TTL 清扫兜底 —— 冠军未设 persistenceRequired,
 * despawn/区块卸载不发 LivingDeathEvent, 只靠死亡清理会泄漏 (与 {@code ChampionBlinkHandler} 同纪律)。实例状态随本
 * handler 实例存活 (由 {@code ChampionSystem#register} 挂 forgeBus), 无静态账本故无需 onServerStopping 清理。
 */
public final class ChampionSizeHandler {

    /** 诊断日志: 生成期降档 + 运行时守卫 blink 真服首验用 (降档低频不门控; blink 经 shouldTrace 门控只追近玩家的怪)。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/skill");

    /** 扫描周期 (tick): 1s 扫一次近玩家冠军 (与自身被动/闪光同范式)。 */
    private static final int SCAN_INTERVAL_TICKS = 20;

    /** 作用的玩家可见距离 (格; 与自身被动/BOSS 血条同量级)。远离该范围的冠军不结算 (无玩家在场无需守卫/补偿)。 */
    private static final double VIEW_RANGE = 48.0D;

    /** 巨大化移速补偿 modifier 固定 UUID (瞬态 MULTIPLY_TOTAL, 独立于 SPRINT/OVERDRIVE 的 UUID; 不入 NBT)。 */
    private static final UUID GIGANTISM_SPEED_UUID = UUID.fromString("2b7e4c19-8a63-4f52-9d0e-1c6a5b8f3e70");

    /** 运行时守卫触发的连续被阻挡扫描次数 (spec 9.4: 连续 2 次几何阻挡才 blink, 防单帧误判)。 */
    private static final int GUARD_BLOCKED_THRESHOLD = 2;

    /** 运行时守卫 blink 内 CD (tick): spec 9.4 内 CD 3s, 防岩浆房/死角每秒反复瞬移。 */
    private static final long GUARD_CD_TICKS = 60L;

    /** 守卫 blink 预兆时长 (tick): spec 9.4 到达前 0.5s 粒子预兆。 */
    private static final int PREMONITION_TICKS = 10;

    /** 守卫 blink 落点环搜最大半径 (格): 逐环外扩找容纳放大 AABB 的安全落点, 超此半径放弃本轮 (原地等下轮)。 */
    private static final int GUARD_SEARCH_RADIUS = 6;

    /** 守卫 blink 落点距最近玩家最小间距 (格): spec 9.4 距玩家 ≥1 格 (不把巨怪塞到玩家脸上)。 */
    private static final double MIN_PLAYER_DISTANCE = 1.0D;

    /** per-冠军体型状态; 冠军死亡摘除 + TTL 清扫双保险 (despawn/卸载不发死亡事件的泄漏兜底)。 */
    private final Map<UUID, SizeState> stateByChampion = new HashMap<>();

    /** 状态 TTL 清扫周期 (tick): 每 60s 扫一次 (低频, 表通常极小)。 */
    private static final int STATE_SWEEP_INTERVAL_TICKS = 1200;

    /**
     * 状态条目 TTL (tick): 5min 未被触达 (未被扫描) 即回收。丢态语义安全: 回收后视为"未计算尺寸", 下次扫描
     * 经 refreshDimensions 重算 —— 5min 未触达的冠军必远离玩家, 回收不改任何可观测行为 (体型系数会重算成同值), 只兜内存。
     */
    private static final long STATE_TTL_TICKS = 6000L;

    // ============================================================
    // 服务端 AABB 缩放 (EntityEvent.Size) + 首帧守卫 + 广播
    // ============================================================

    /**
     * 按 capability 体型系数缩放服务端 AABB (spec 9A.3 #17)。首次见到某体型冠军 (无 state) 时计算最终尺寸系数
     * (巨大化含生成期形态守卫降档) + 建 state + 向 tracking 玩家广播; 后续 Size 事件读 state 缓存值。无体型词条的
     * 冠军 (或非冠军/非 Mob/客户端实体) 一律早退不缩。
     *
     * <p>{@code EntityEvent.Size} 也在 Entity 构造器触发 (spec 报备): 彼时 capability 未 gather, {@link MiningChampions#get}
     * 返回 empty -&gt; 早退, 不会用未初始化实体误算。
     */
    @SubscribeEvent
    @SuppressWarnings("removal") // EntityEvent.Size 在 1.20.1 deprecated-for-removal, 但为该版本唯一体型钩子 (无替代)。
    public void onEntitySize(EntityEvent.Size event) {
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (!(mob.level() instanceof ServerLevel level)) {
            return; // 客户端实体走 ChampionSizeRenderClient; 构造期 level 为服务端时才在此缩。
        }
        MiningChampionData champ = MiningChampions.get(mob).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return;
        }
        AffixQuality gig = champ.quality(AffixDef.GIGANTISM);
        AffixQuality mini = champ.quality(AffixDef.MINIATURIZATION);
        if (gig == null && mini == null) {
            return; // 无体型词条: 保持原版体型。
        }

        EntityDimensions base = event.getNewSize();
        SizeState state = stateByChampion.get(mob.getUUID());
        float scale;
        if (state == null) {
            scale = computeGuardedScale(mob, level, base, gig, mini);
            state = new SizeState(scale, level.dimension(), level.getGameTime());
            stateByChampion.put(mob.getUUID(), state);
            broadcastScale(mob, scale); // 首帧向当前 tracker 广播 (后来者由 onStartTracking 补发)。
        } else {
            scale = state.scale;
        }
        // 眼高显式等比缩 (2026-07-10 真服验收修): updateEyeHeight=true 会按新尺寸【重查】实体的眼高函数, 而
        // vanilla 僵尸等人形怪把站立眼高写死 1.74 不看尺寸 -> 巨人眼位停在胸口/小怪眼位飘头顶, TACZ 爆头判定
        // (锚眼位) 随之错位。改单参 setNewSize (不动眼高) + 显式 oldEyeHeight x scale (old 恒为该姿态基准眼高,
        // 事件每次以未缩基准发起, 等比缩幂等)。
        event.setNewSize(base.scale(scale));
        event.setNewEyeHeight(event.getOldEyeHeight() * scale);
    }

    /**
     * 计算最终尺寸系数 (含生成期形态守卫, spec 9.4)。缩小化恒不卡 (变小), 直接返回其系数; 巨大化从当前品质档起
     * 逐档下探 (只改尺寸不动词条), 首个"放大 AABB 容纳 (noCollision)"或降到 COMMON 即定档 —— 保证升格的巨怪不
     * 生成即嵌进墙里 (仍卡在 COMMON 1.25× 时接受, 由运行时守卫兜底 blink)。命令双持体型词条 (非法但可调试) 时
     * 缩小系数并入非巨大化基底连乘, 与 {@code ChampionHpConversion} 血量乘数口径一致。
     */
    private float computeGuardedScale(Mob mob, ServerLevel level, EntityDimensions base,
                                      AffixQuality gig, AffixQuality mini) {
        double nonGig = mini != null ? ChampionSizeScale.sizeMultiplierFor(AffixDef.MINIATURIZATION, mini) : 1.0D;
        if (gig == null) {
            return (float) nonGig; // 纯缩小化: 无需守卫。
        }
        AffixQuality quality = gig;
        while (true) {
            double candidate = nonGig * ChampionSizeScale.sizeMultiplierFor(AffixDef.GIGANTISM, quality);
            if (quality == AffixQuality.COMMON || fitsScaledBox(mob, level, base, candidate)) {
                if (quality != gig) {
                    // 低频 (仅真降档时): 记生成期降档, 真服核对巨怪在窄区被守卫缩档。
                    LOGGER.info("size-guard spawn-downgrade {} gigantism {} -> {} scale={} (no room for full size)",
                            mob.getType().getDescriptionId(), gig, quality, String.format("%.2f", candidate));
                }
                return (float) candidate;
            }
            quality = ChampionSizeScale.downgrade(quality);
        }
    }

    /** 放大后的 AABB 是否容纳 (spec 9.4 生成期守卫): 以脚位为基构造缩放 AABB, noCollision (排除自身) 判几何容纳。 */
    private static boolean fitsScaledBox(Mob mob, ServerLevel level, EntityDimensions base, double scale) {
        AABB box = base.scale((float) scale).makeBoundingBox(mob.position());
        return level.noCollision(mob, box);
    }

    /** 向 tracking 玩家广播体型系数 (客户端 capability 不同步, 靠 S2C 包渲染/缩碰撞箱)。 */
    private static void broadcastScale(Mob mob, float scale) {
        MiningNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> mob),
                new ChampionSizeS2C(mob.getId(), scale));
        // 真服 2026-07-08 体型渲染排障: 每冠军一次的低频取证, 对账客户端 accept 日志定 S2C 断点。
        LOGGER.info("size-s2c broadcast entity={} id={} scale={}",
                mob.getType().getDescriptionId(), mob.getId(), String.format("%.2f", scale));
    }

    /**
     * 玩家开始追踪某体型冠军: 补发尺寸系数给后来者 (盖章首帧广播只覆盖当时的 tracker)。已算出系数则定向发该玩家;
     * 尚未算出 (如刚 NBT 载入未经扫描) 则触发一次 refreshDimensions, 令 Size 事件计算 + 广播给全部 tracker (含本玩家)。
     */
    @SubscribeEvent
    public void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getTarget() instanceof Mob mob)) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MiningChampionData champ = MiningChampions.get(mob).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return;
        }
        if (champ.quality(AffixDef.GIGANTISM) == null && champ.quality(AffixDef.MINIATURIZATION) == null) {
            return; // 无体型词条: 无需同步 (客户端保持原版体型)。
        }
        SizeState state = stateByChampion.get(mob.getUUID());
        if (state != null) {
            MiningNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new ChampionSizeS2C(mob.getId(), state.scale));
            // 真服 2026-07-08 体型渲染排障: 补发取证 (每玩家每冠军一次, 低频)。
            LOGGER.info("size-s2c resend entity={} id={} scale={} to={}",
                    mob.getType().getDescriptionId(), mob.getId(),
                    String.format("%.2f", state.scale), player.getName().getString());
        } else {
            mob.refreshDimensions(); // 触发 Size 事件计算 + TRACKING_ENTITY 广播 (本玩家已在追踪列表内)。
        }
    }

    // ============================================================
    // 运行时形态守卫 + 移速补偿 (ServerTick)
    // ============================================================

    /**
     * 每 tick 先推进在册预兆 (0.5s 需 tick 精度), 再每 1s 扫近玩家巨大化冠军 (挂移速补偿 + 卡死检测)。与
     * {@code ChampionBlinkHandler} 同双时间尺度范式。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        long nowTick = server.overworld().getGameTime();

        if (!stateByChampion.isEmpty()) {
            advancePremonitions(server, nowTick); // 每 tick 推进守卫 blink 预兆 (无预兆条目廉价跳过)。
        }
        if (server.getTickCount() % SCAN_INTERVAL_TICKS == 0) {
            scanNearbyChampions(server, nowTick);
            if (server.getTickCount() % STATE_SWEEP_INTERVAL_TICKS == 0) {
                sweepStaleStates(nowTick);
            }
        }
    }

    /** 每秒扫近玩家冠军 (与自身被动同范式): 对体型冠军确保尺寸已算 + 巨大化挂移速补偿 + 运行时卡死检测。 */
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
                    applySizeScan(entity, level, nowTick);
                }
            }
        }
    }

    /** 对一只实体 (若是体型冠军) 施 tick 体型逻辑: 确保尺寸已算 + 巨大化移速补偿 + 卡死守卫。 */
    private void applySizeScan(LivingEntity entity, ServerLevel level, long nowTick) {
        MiningChampionData champ = MiningChampions.get(entity).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 非本工程冠军。
        }
        AffixQuality gig = champ.quality(AffixDef.GIGANTISM);
        AffixQuality mini = champ.quality(AffixDef.MINIATURIZATION);
        if (gig == null && mini == null) {
            return; // 无体型词条。
        }
        if (!(entity instanceof Mob mob)) {
            return; // capability 只挂 Mob。
        }

        SizeState state = stateByChampion.get(mob.getUUID());
        if (state == null) {
            // 尚未算尺寸 (如 NBT 载入的冠军未经 Size 事件): 触发一次刷新令 Size 事件计算 + 广播。
            mob.refreshDimensions();
            state = stateByChampion.get(mob.getUUID());
            if (state == null) {
                return; // 防御: Size 事件仍未建 state (理论不发生, 体型词条已确认)。
            }
        }
        state.lastTouchedTick = nowTick;

        if (gig != null) {
            // 巨大化移速补偿 (spec 用户裁定): 按品质档 +10%×序号 挂瞬态 MULTIPLY_TOTAL; 用巨大化真实品质
            // (非守卫降档后的尺寸档 —— 守卫只降尺寸不降词条, 移速补偿按词条品质)。
            ensureGigantismSpeedModifier(mob, ChampionSizeScale.speedBonusFor(gig));
            guardStuckScan(mob, level, state, nowTick);
        }
    }

    /**
     * 运行时卡死守卫 (spec 9.4): 连续 {@value #GUARD_BLOCKED_THRESHOLD} 次扫描被几何阻挡 (noCollision false 或
     * horizontalCollision) 且过内 CD -&gt; 环搜安全落点起 0.5s 预兆; 预兆期由 {@link #advancePremonitions} 逐 tick 推进。
     * 找不到落点则原地不动 (blocked 计数保留, 下轮再试)。预兆进行中不重复触发。
     */
    private void guardStuckScan(Mob mob, ServerLevel level, SizeState state, long nowTick) {
        boolean blocked = mob.horizontalCollision || !level.noCollision(mob, mob.getBoundingBox());
        state.consecutiveBlockedScans = blocked ? state.consecutiveBlockedScans + 1 : 0;

        if (state.premonitionActive || nowTick < state.guardCdEndTick) {
            return; // 预兆中 / 内 CD 内: 不新起。
        }
        if (state.consecutiveBlockedScans < GUARD_BLOCKED_THRESHOLD) {
            return; // 未达连续阻挡阈值。
        }
        BlockPos landing = findUnstuckLanding(mob, level);
        if (landing == null) {
            return; // 环内无容纳的安全落点: 原地等下轮 (blocked 计数保留)。
        }
        state.premonitionActive = true;
        state.premonitionLanding = landing;
        state.premonitionEndTick = nowTick + PREMONITION_TICKS;
        emitPremonitionParticles(level, landing);
        if (ChampionDiagnostics.shouldTrace(mob)) {
            LOGGER.info("size-guard premon {} stuck -> landing=[{},{},{}] in={}t",
                    mob.getType().getDescriptionId(),
                    landing.getX(), landing.getY(), landing.getZ(), PREMONITION_TICKS);
        }
    }

    /**
     * 环搜卡死冠军的解困落点: 以冠军当前位置为中心逐环 (半径 1..{@value #GUARD_SEARCH_RADIUS}) 外扩, 每格附少量
     * 竖向微调, 要求 (1) {@link KnockbackSafetyGuard#evaluateLanding} 判 SAFE; (2) 把冠军【当前缩放 AABB】平移到该格
     * 后 noCollision (容纳巨怪); (3) 距最近玩家 ≥{@value #MIN_PLAYER_DISTANCE} 格。首个满足即返回, 全环失败返 null。
     */
    private static BlockPos findUnstuckLanding(Mob mob, ServerLevel level) {
        AABB curBox = mob.getBoundingBox(); // 当前已缩放的服务端 AABB (平移复用其尺寸)。
        int ox = Mth.floor(mob.getX());
        int oy = Mth.floor(mob.getY());
        int oz = Mth.floor(mob.getZ());
        int[] dyWiggle = {0, 1, -1, 2, -2};
        for (int r = 1; r <= GUARD_SEARCH_RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // 只取当前环 (内环上一轮已查)。
                    }
                    for (int dy : dyWiggle) {
                        BlockPos pos = new BlockPos(ox + dx, oy + dy, oz + dz);
                        if (KnockbackSafetyGuard.evaluateLanding(level, pos).outcome()
                                != KnockbackSafetyGuard.Outcome.SAFE) {
                            continue;
                        }
                        double cx = pos.getX() + 0.5D;
                        double cz = pos.getZ() + 0.5D;
                        AABB box = curBox.move(cx - mob.getX(), pos.getY() - mob.getY(), cz - mob.getZ());
                        if (!level.noCollision(mob, box)) {
                            continue; // 该格容纳不下巨怪。
                        }
                        if (level.getNearestPlayer(cx, pos.getY(), cz, MIN_PLAYER_DISTANCE, false) != null) {
                            continue; // 距玩家 <1 格: 禁 (不塞玩家脸上)。
                        }
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 每 tick 推进在册预兆: 仅处理 premonitionActive 条目。实体消失/死亡 -&gt; 清预兆; 未到点 -&gt; 逐 tick 喷落点
     * 预兆粒子; 到点 -&gt; 瞬移 + 两端 poof + 传送音, 置内 CD, 清预兆。
     */
    private void advancePremonitions(MinecraftServer server, long nowTick) {
        for (Map.Entry<UUID, SizeState> entry : stateByChampion.entrySet()) {
            SizeState state = entry.getValue();
            if (!state.premonitionActive) {
                continue;
            }
            LivingEntity entity = resolve(server, state.dimension, entry.getKey());
            if (entity == null || !entity.isAlive() || !(entity instanceof Mob mob)
                    || !(entity.level() instanceof ServerLevel level)) {
                state.premonitionActive = false; // despawn/卸载/死亡/非 Mob: 预兆作废。
                state.premonitionLanding = null;
                continue;
            }
            if (nowTick >= state.premonitionEndTick) {
                executeGuardBlink(mob, level, state, nowTick);
            } else {
                emitPremonitionParticles(level, state.premonitionLanding);
            }
        }
    }

    /** 预兆到点: 冠军瞬移到落点 (方块中心, 底面对齐), 两端 poof + 传送音, 置内 CD 3s, 清预兆与阻挡计数。 */
    private void executeGuardBlink(Mob mob, ServerLevel level, SizeState state, long nowTick) {
        Vec3 from = mob.position();
        double lx = state.premonitionLanding.getX() + 0.5D;
        double ly = state.premonitionLanding.getY();
        double lz = state.premonitionLanding.getZ() + 0.5D;

        emitPoof(level, from.x, from.y + mob.getBbHeight() * 0.5D, from.z);
        mob.teleportTo(lx, ly, lz);
        mob.getNavigation().stop(); // 瞬移后停旧路径, 由原生索敌重寻路。
        emitPoof(level, lx, ly + mob.getBbHeight() * 0.5D, lz);
        level.playSound(null, lx, ly, lz, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 0.8F);

        state.premonitionActive = false;
        state.premonitionLanding = null;
        state.guardCdEndTick = nowTick + GUARD_CD_TICKS;
        state.consecutiveBlockedScans = 0;
        state.lastTouchedTick = nowTick;
        if (ChampionDiagnostics.shouldTrace(mob)) {
            LOGGER.info("size-guard blink {} to [{},{},{}]", mob.getType().getDescriptionId(),
                    String.format("%.1f", lx), String.format("%.1f", ly), String.format("%.1f", lz));
        }
    }

    /**
     * 巨大化移速补偿 modifier (幂等 + 品质变更 remove+add): 目标值与在挂值一致则不动; 不一致先摘旧值, 目标非 0
     * 再挂新瞬态 MULTIPLY_TOTAL (瞬态不入 NBT; 随实体销毁自然消失, 无需死亡手摘)。
     */
    private static void ensureGigantismSpeedModifier(LivingEntity entity, double desired) {
        AttributeInstance attr = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr == null) {
            return;
        }
        AttributeModifier current = attr.getModifier(GIGANTISM_SPEED_UUID);
        if (current != null) {
            if (current.getAmount() == desired) {
                return; // 同品质重复扫描: 已是目标值。
            }
            attr.removeModifier(GIGANTISM_SPEED_UUID);
        }
        if (desired != 0.0D) {
            attr.addTransientModifier(new AttributeModifier(
                    GIGANTISM_SPEED_UUID, "champion_gigantism_speed", desired,
                    AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
    }

    /** 冠军死亡: 摘 per-冠军体型状态防泄漏 (移速 modifier 随实体销毁自然消失, 无需手摘)。 */
    @SubscribeEvent
    public void onChampionDeath(LivingDeathEvent event) {
        stateByChampion.remove(event.getEntity().getUUID());
    }

    /** 回收 TTL 内未被触达 (未被扫描) 的状态条目 (语义安全性见 {@link #STATE_TTL_TICKS})。 */
    private void sweepStaleStates(long nowTick) {
        Iterator<Map.Entry<UUID, SizeState>> it = stateByChampion.entrySet().iterator();
        while (it.hasNext()) {
            SizeState state = it.next().getValue();
            // MIN_VALUE (理论不出现: 建条目即刻带 gameTime) 显式视为过期, 防减法溢出漏回收。
            if (state.lastTouchedTick == Long.MIN_VALUE || nowTick - state.lastTouchedTick > STATE_TTL_TICKS) {
                it.remove();
            }
        }
    }

    /** 预兆落点标记粒子 (spec 9A.6 原版可见原语): 落点上方喷传送门粒子, 让玩家肉眼预判巨怪即将瞬移到此。 */
    private static void emitPremonitionParticles(ServerLevel level, BlockPos landing) {
        level.sendParticles(ParticleTypes.PORTAL,
                landing.getX() + 0.5D, landing.getY() + 0.5D, landing.getZ() + 0.5D,
                12, 0.4D, 0.5D, 0.4D, 0.05D);
    }

    /** 瞬移两端 poof (spec 9A.6): 起点消散 + 落点浮现, 纯原版客户端可见。 */
    private static void emitPoof(ServerLevel level, double x, double y, double z) {
        level.sendParticles(ParticleTypes.POOF, x, y, z, 16, 0.3D, 0.4D, 0.3D, 0.02D);
    }

    /** 按状态记录的维度检出在册冠军实体 (维度未加载/实体不在返 null; 照闪光/自我修复范式)。 */
    private static LivingEntity resolve(MinecraftServer server, ResourceKey<Level> dimension, UUID id) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            return null;
        }
        Entity found = level.getEntity(id);
        return found instanceof LivingEntity living ? living : null;
    }

    /**
     * per-冠军体型状态: 最终尺寸系数 (守卫后, 客户端广播与服务端 AABB 同源) + 所在维度 (预兆期 O(1) 检出实体) +
     * 最后触达 tick (TTL 清扫依据) + 运行时守卫相位 (连续阻挡计数 / 内 CD 到点 tick / 预兆锁定落点与到点 tick)。
     */
    private static final class SizeState {
        private final float scale;
        private final ResourceKey<Level> dimension;
        private long lastTouchedTick;
        private int consecutiveBlockedScans = 0;
        private long guardCdEndTick = Long.MIN_VALUE;
        private boolean premonitionActive = false;
        private BlockPos premonitionLanding = null;
        private long premonitionEndTick = Long.MIN_VALUE;

        private SizeState(float scale, ResourceKey<Level> dimension, long createdTick) {
            this.scale = scale;
            this.dimension = dimension;
            this.lastTouchedTick = createdTick;
        }
    }
}
