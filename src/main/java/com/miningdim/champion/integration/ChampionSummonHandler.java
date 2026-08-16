package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.AffixRoller;
import com.miningdim.champion.AffixSelection;
import com.miningdim.champion.ChampionDiagnostics;
import com.miningdim.champion.ChampionSummonPlan;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.StarRank;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 支援召唤 SUMMON_SUPPORT 技能施加 (Champions 集成层; ChampionStarAffix spec 7.4 支援 + 红线 8 三重封顶 +
 * 经济闸)。周期召唤低星【同型】冠军助战, 召唤物完全排除经济结算 (货币/经验/掉落/贡献/BOSS 条)。
 *
 * 三个事件:
 *  - {@link #onServerTick} (END, 每 {@value #SCAN_INTERVAL_TICKS}tick=1s): 按玩家 AABB 扫近处冠军 (与
 *    {@code ChampionSelfEffectHandler}/{@code ChampionBossBarHandler} 同范式), 对装配支援召唤的冠军: 剪除失效召唤物 +
 *    存活召唤物拴绳维护 (远离 &gt;24 格持续 5s 传回/作废) + 冷却就绪且有存活位则召唤。数值/门槛 (CD/召唤数/存活上限/
 *    星级钳/词条过滤) 全下沉纯逻辑 {@link ChampionSummonPlan} 真测。
 *  - {@link #onOwnerDeath} (LivingDeathEvent): 主人死亡 -> 名下全部召唤物 discard + 消散粒子 + 摘状态 (防孤儿泄漏)。
 *  - {@link #onSummonDrops} (LivingDropsEvent) / {@link #onSummonXp} (LivingExperienceDropEvent): 召唤物死亡时清空
 *    掉落表 + 取消经验 (经济闸红线 8-b; 按 capability {@code isSummonedByAffix} 判, 随 NBT 持久, 重启后口径不丢)。
 *
 * 经济闸 (spec 红线 8) 分三处: (a) {@link ChampionRewardHandler} 对召唤物受击/死亡早退不记贡献不发奖池;
 * (b) 本 handler 清掉落 + 取消经验; (c) {@link ChampionBossBarHandler#viewOf} 对召唤物返 null 不出 BOSS 条。
 *
 * 生命周期取舍 (keyDecisions): 服务端重启后 owner->召唤物 内存链丢失 -> 召唤物变独立冠军, 但 summonedByAffix 随
 * 实体 NBT 保留 (见 {@link MiningChampionData#isSummonedByAffix()}), 故经济排除口径不随重启失效 (三处闸皆按
 * capability 判, 不依赖本 handler 的内存链)。孤儿召唤物仅"不再受主人拴绳维护 + 死亡不再触发主人级消散", 可接受。
 *
 * 状态泄漏防御: 冠军未设 persistenceRequired, 自然 despawn/区块卸载不发 LivingDeathEvent, 只靠死亡清理会泄漏,
 * 故 TTL 清扫 (见 {@link #sweepStaleStates}) 兜底回收长期未触达的 owner 状态 (仅摘内存链, 不 discard 召唤物,
 * 与重启孤儿同口径)。注册由 {@code ChampionSystem} 主线接 forgeBus (不自注册)。
 */
public final class ChampionSummonHandler {

    /** 诊断日志: 支援召唤真服首验用 (施放/位置全失败/拴绳传回/消散 打行, 技能事件低频不门控, 真服对账用)。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/summon");

    /** 召唤扫描/维护周期 (tick): 1s 扫一次近玩家冠军, 查冷却/拴绳 (与 {@link ChampionSummonPlan} 冷却 tick 对齐)。 */
    private static final int SCAN_INTERVAL_TICKS = 20;

    /** 拴绳距离 (格): 召唤物离主人超此距离开始计"远离"; 持续 {@value #LEASH_GRACE_TICKS}tick 触发传回。 */
    private static final double LEASH_RANGE = 24.0D;

    /** 拴绳宽限 (tick): 召唤物持续远离 &gt;24 格达 5s 才传回 (给 AI 自行归队机会, 防频繁瞬移抖动)。 */
    private static final long LEASH_GRACE_TICKS = 100L;

    /** 落点尝试角度数 (绕主人 8 个方位试可站立位, 全失败该只作废)。 */
    private static final int SPOT_ATTEMPTS = 8;

    /** 落点最小水平半径 (格; 不与主人重叠)。 */
    private static final double SPOT_MIN_RADIUS = 2.0D;

    /** 落点最大水平半径 (格)。 */
    private static final double SPOT_MAX_RADIUS = 4.0D;

    /** 落点竖直探测范围 (格): 以主人脚高为基准, 先下 (地表) 后上各探至多本值格找可站立地表。 */
    private static final int SPOT_VERTICAL_SEARCH = 4;

    /** owner 冠军 UUID -> 召唤状态 (上次召唤 tick + 名下召唤物集 + 各召唤物最后靠近 tick + TTL 触达 tick)。 */
    private final Map<UUID, SummonState> stateByOwner = new HashMap<>();

    /** 状态 TTL 清扫周期 (tick): 每 60s 扫一次 (低频, 表通常极小)。 */
    private static final int STATE_SWEEP_INTERVAL_TICKS = 1200;

    /**
     * 状态条目 TTL (tick): 5min 未被触达 (owner 未被扫描) 即回收。丢态语义安全: 回收后 owner 与召唤物内存链断开,
     * 召唤物变独立冠军但 summonedByAffix 随 NBT 保留 (经济口径不丢, 同重启孤儿); owner 若回到玩家附近会重建状态
     * (从头计冷却/存活)。5min 未触达的冠军必然远离所有玩家, 回收不改任何可观测行为。
     */
    private static final long STATE_TTL_TICKS = 6000L;

    /**
     * 每秒扫近玩家冠军: 对装配支援召唤者维护召唤 (剪除失效 + 拴绳 + 冷却召唤)。按玩家 AABB 扫 + capability 检出
     * 冠军 (命令召唤 + 自然刷一视同仁), 多玩家同看一冠军本轮只结算一次。
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
        for (ChampionProximityScanner.Sighting sighting : ChampionProximityScanner.sightings(server)) {
            if (!sighting.entity().isAlive()) {
                continue; // 快照按 tick 复用, 同 tick 更早的 handler 可能已致死: 存活性逐条重查。
            }
            if (!(sighting.entity() instanceof Mob mob)) {
                continue; // 冠军 capability 只挂 Mob (MiningChampions:58-65), 快照内 entity 恒为 Mob; 防御性早退。
            }
            applySummonTick(mob, nowTick);
        }

        // TTL 清扫 (despawn/卸载不发死亡事件的泄漏兜底): 低频回收长期未触达的 owner 状态条目。
        if (server.getTickCount() % STATE_SWEEP_INTERVAL_TICKS == 0) {
            sweepStaleStates(nowTick);
        }
    }

    /** 对一只冠军 (若装配支援召唤且非召唤物本身) 维护召唤: 剪除失效 + 拴绳 + 冷却召唤。 */
    private void applySummonTick(Mob owner, long nowTick) {
        MiningChampionData champ = MiningChampions.get(owner).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 非本工程冠军。
        }
        if (champ.isSummonedByAffix()) {
            return; // 召唤物自身不召唤 (防递归增殖; 词条已过滤 SUMMON_SUPPORT, 此为双保险)。
        }
        AffixQuality quality = champ.quality(AffixDef.SUMMON_SUPPORT);
        if (quality == null) {
            return; // 未装配支援召唤: 不建状态 (防为无关冠军泄漏 state)。
        }
        if (!(owner.level() instanceof ServerLevel level)) {
            return;
        }

        SummonState state = stateByOwner.computeIfAbsent(owner.getUUID(), k -> new SummonState());
        state.lastTouchedTick = nowTick;

        RandomSource rng = level.getRandom();
        int alive = maintainAndCountAlive(level, owner, state, nowTick, rng);

        if (!ChampionSummonPlan.cooldownElapsed(nowTick, state.lastCastTick, quality)) {
            return; // 冷却中。
        }
        int count = ChampionSummonPlan.actualSummonCount(quality, alive);
        if (count <= 0) {
            return; // 同时存活已满: 不召 (不刷 CD, 待召唤物阵亡腾位再召)。
        }

        int summonStar = ChampionSummonPlan.summonStar(champ.star());
        LivingEntity ownerTarget = owner.getTarget();
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            if (spawnOneSummon(level, owner, summonStar, ownerTarget, state, nowTick, rng)) {
                spawned++;
            }
        }
        if (spawned > 0) {
            // 至少召出一只才刷 CD: 全位置失败不消 CD, 下周期重试 (防被封闭空间永久锁死召唤)。
            state.lastCastTick = nowTick;
            LOGGER.info("skill-summon cast owner={} ownerStar={} summonStar={} requested={} spawned={} aliveNow={}",
                    owner.getUUID(), champ.star(), summonStar, count, spawned, alive + spawned);
        } else if (ChampionDiagnostics.shouldTrace(owner)) {
            // 全位置失败会逐周期重试 (不消 CD), 封闭空间下 1 行/秒 —— 非低频事件, 须经诊断门控防刷屏 (对抗审查)。
            LOGGER.info("skill-summon cast-allfail owner={} ownerStar={} summonStar={} requested={} (no standable spot)",
                    owner.getUUID(), champ.star(), summonStar, count);
        }
    }

    /**
     * 剪除失效召唤物 (死亡/卸载/无法解析) + 存活召唤物拴绳维护, 返回维护后的存活数。拴绳失败 (远离超时且找不到
     * 可站立位) 的召唤物就地消散并从集合剔除。
     */
    private int maintainAndCountAlive(ServerLevel level, Mob owner, SummonState state, long nowTick, RandomSource rng) {
        int alive = 0;
        Iterator<UUID> it = state.summonedIds.iterator();
        while (it.hasNext()) {
            UUID sid = it.next();
            Entity resolved = level.getEntity(sid);
            if (!(resolved instanceof Mob summon) || !summon.isAlive()) {
                it.remove();
                state.lastNearTick.remove(sid);
                continue; // 死亡/卸载/跨维度不可解析: 剔除 (计数不含)。
            }
            if (!maintainLeash(level, owner, summon, state, nowTick, rng)) {
                it.remove();
                state.lastNearTick.remove(sid);
                continue; // 拴绳作废: 已消散。
            }
            alive++;
        }
        return alive;
    }

    /**
     * 单只召唤物拴绳维护: 在 24 格内刷新靠近 tick; 远离持续 &ge;5s 则传回主人旁 2 格内可站立位 (找不到就消散作废)。
     *
     * @return 是否保留该召唤物 (true=近处/已传回; false=已消散作废, 调用方剔除)
     */
    private boolean maintainLeash(ServerLevel level, Mob owner, Mob summon, SummonState state,
                                  long nowTick, RandomSource rng) {
        UUID sid = summon.getUUID();
        if (summon.distanceToSqr(owner) <= LEASH_RANGE * LEASH_RANGE) {
            state.lastNearTick.put(sid, nowTick);
            return true;
        }
        long lastNear = state.lastNearTick.getOrDefault(sid, nowTick);
        if (nowTick - lastNear < LEASH_GRACE_TICKS) {
            return true; // 远离未满 5s: 继续等 (给 AI 自行归队机会)。
        }
        BlockPos spot = findSummonSpot(level, owner, rng);
        if (spot == null) {
            emitPoof(level, summon);
            summon.discard();
            LOGGER.info("skill-summon leash-discard owner={} summon={} (no standable spot near owner)",
                    owner.getUUID(), sid);
            return false;
        }
        summon.teleportTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D);
        state.lastNearTick.put(sid, nowTick);
        LOGGER.info("skill-summon leash-return owner={} summon={} to={}", owner.getUUID(), sid, spot);
        return true;
    }

    /**
     * 召出一只同型低星召唤物: 找落点 -> 建同 EntityType 实体 -> 落地 -> {@link ChampionPromoter#applyChampion} 盖章
     * (词条经 {@link ChampionSummonPlan#retainSummonableAffixes} 剥离技能/支援防递归) -> markSummonedByAffix 盖召唤物
     * 身份 (经济排除) -> setTarget 主人目标直接进战 -> 出怪粒子/音效。落点全失败该只作废 (不盖章)。
     *
     * @return 是否成功召出一只
     */
    private boolean spawnOneSummon(ServerLevel level, Mob owner, int summonStar, LivingEntity ownerTarget,
                                   SummonState state, long nowTick, RandomSource rng) {
        BlockPos spot = findSummonSpot(level, owner, rng);
        if (spot == null) {
            LOGGER.info("skill-summon spot-fail owner={} star{}", owner.getUUID(), summonStar);
            return false;
        }
        Entity created = owner.getType().create(level);
        if (!(created instanceof Mob summon)) {
            if (created != null) {
                created.discard();
            }
            LOGGER.info("skill-summon create-fail type={} (not a Mob)", owner.getType());
            return false;
        }
        double sx = spot.getX() + 0.5D;
        double sy = spot.getY();
        double sz = spot.getZ() + 0.5D;
        summon.moveTo(sx, sy, sz, owner.getYRot(), 0.0F);
        if (!level.addFreshEntity(summon)) {
            summon.discard();
            LOGGER.info("skill-summon addfresh-fail owner={}", owner.getUUID());
            return false;
        }

        // 盖章 (自然升格/命令召唤共用入口): 词条剥离技能/支援后写 capability + 接管基础血量。
        StarRank rank = StarRank.ofStar(summonStar);
        List<AffixSelection> rolled = AffixRoller.roll(rank, rng);
        Map<AffixDef, AffixQuality> summonAffixes = new EnumMap<>(AffixDef.class);
        for (AffixSelection sel : ChampionSummonPlan.retainSummonableAffixes(rolled)) {
            summonAffixes.put(sel.affix(), sel.quality());
        }
        ChampionPromoter.applyChampion(summon, summonStar, summonAffixes);
        // 盖召唤物身份 (须在 applyChampion 之后: promote 会复位 summonedByAffix=false)。
        MiningChampions.get(summon).ifPresent(MiningChampionData::markSummonedByAffix);
        summon.setTarget(ownerTarget); // 主人无目标则为 null: 召唤物随 AI 自行索敌。

        UUID sid = summon.getUUID();
        state.summonedIds.add(sid);
        state.lastNearTick.put(sid, nowTick);
        emitSummonSpawnEffects(level, sx, sy, sz);
        return true;
    }

    /** 主人死亡: 名下全部召唤物 discard + 消散粒子 + 摘状态。非 owner (无状态) 的死亡直接早退。 */
    @SubscribeEvent
    public void onOwnerDeath(LivingDeathEvent event) {
        SummonState state = stateByOwner.remove(event.getEntity().getUUID());
        if (state == null) {
            return; // 死者非在册 owner (普通怪/召唤物/无支援召唤冠军)。
        }
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        int discarded = 0;
        for (UUID sid : state.summonedIds) {
            Entity summon = level.getEntity(sid);
            if (summon != null && summon.isAlive()) {
                emitPoof(level, summon);
                summon.discard();
                discarded++;
            }
        }
        LOGGER.info("skill-summon owner-death owner={} discarded={}", event.getEntity().getUUID(), discarded);
    }

    /**
     * 召唤物死亡掉落清空 (经济闸红线 8-b): summonedByAffix 冠军的掉落表整清 (召唤物不产任何物品 faucet)。
     * 按 capability 判, 不依赖内存 owner 链 -> 重启孤儿召唤物照样清掉落。
     */
    @SubscribeEvent
    public void onSummonDrops(LivingDropsEvent event) {
        MiningChampionData champ = MiningChampions.get(event.getEntity()).orElse(null);
        if (champ == null || !champ.isSummonedByAffix()) {
            return;
        }
        event.getDrops().clear();
    }

    /** 召唤物死亡经验取消 (经济闸红线 8-b): summonedByAffix 冠军不掉经验 (防刷经验 faucet)。 */
    @SubscribeEvent
    public void onSummonXp(LivingExperienceDropEvent event) {
        MiningChampionData champ = MiningChampions.get(event.getEntity()).orElse(null);
        if (champ == null || !champ.isSummonedByAffix()) {
            return;
        }
        event.setCanceled(true);
    }

    /** 回收 TTL 内未被触达 (owner 未被扫描) 的状态条目 (语义安全性见 {@link #STATE_TTL_TICKS})。 */
    private void sweepStaleStates(long nowTick) {
        // MIN_VALUE (理论不出现: 建条目点即刻刷触达) 显式视为过期, 防减法溢出漏回收。
        stateByOwner.values().removeIf(state -> state.lastTouchedTick == Long.MIN_VALUE
                || nowTick - state.lastTouchedTick > STATE_TTL_TICKS);
    }

    /**
     * 找一处召唤落点 (主人周围 2-4 格可站立位): 随机起始角 + 8 方位, 每方位竖直探地表 (先下后上), 首个可站立位即返。
     * 全失败返 null (调用方作废该只)。
     */
    private static BlockPos findSummonSpot(ServerLevel level, Mob owner, RandomSource rng) {
        double baseAngle = rng.nextDouble() * Math.PI * 2.0D;
        int startY = Mth.floor(owner.getY());
        for (int i = 0; i < SPOT_ATTEMPTS; i++) {
            double angle = baseAngle + i * (Math.PI * 2.0D / SPOT_ATTEMPTS);
            double radius = SPOT_MIN_RADIUS + rng.nextDouble() * (SPOT_MAX_RADIUS - SPOT_MIN_RADIUS);
            int wx = Mth.floor(owner.getX() + Math.cos(angle) * radius);
            int wz = Mth.floor(owner.getZ() + Math.sin(angle) * radius);
            BlockPos spot = resolveGroundSpot(level, wx, startY, wz);
            if (spot != null) {
                return spot;
            }
        }
        return null;
    }

    /** 在 (wx,wz) 列上以主人脚高为基准竖直找可站立地表: 距 startY 由近及远, 先下 (地表) 后上 (主人在坡上)。 */
    private static BlockPos resolveGroundSpot(ServerLevel level, int wx, int startY, int wz) {
        for (int d = 0; d <= SPOT_VERTICAL_SEARCH; d++) {
            BlockPos down = new BlockPos(wx, startY - d, wz);
            if (isSummonStandable(level, down)) {
                return down;
            }
            if (d > 0) {
                BlockPos up = new BlockPos(wx, startY + d, wz);
                if (isSummonStandable(level, up)) {
                    return up;
                }
            }
        }
        return null;
    }

    /**
     * 召唤落点可站立判定: 脚部/头顶空气 (怪不卡方块) + 脚下稳固承载面且非流体 (不落岩浆/水上方)。区块未加载视为
     * 不可站立 (不越界读)。
     */
    private static boolean isSummonStandable(ServerLevel level, BlockPos foot) {
        BlockPos below = foot.below();
        BlockPos head = foot.above();
        if (!level.isLoaded(foot) || !level.isLoaded(below) || !level.isLoaded(head)) {
            return false;
        }
        if (!level.getBlockState(foot).isAir() || !level.getBlockState(head).isAir()) {
            return false; // 脚部/头顶须空气。
        }
        BlockState floor = level.getBlockState(below);
        if (!floor.getFluidState().isEmpty()) {
            return false; // 脚下流体 (岩浆/水): 不落。
        }
        return floor.isFaceSturdy(level, below, Direction.UP); // 脚下须稳固承载面。
    }

    /** 出怪表现 (spec 9A.6 原版可见原语): PORTAL 汇聚粒子 + 唤魔者召唤前摇音 (0.8 音量)。 */
    private static void emitSummonSpawnEffects(ServerLevel level, double x, double y, double z) {
        level.sendParticles(ParticleTypes.PORTAL, x, y + 0.5D, z, 20, 0.4D, 0.6D, 0.4D, 0.05D);
        level.playSound(null, x, y, z, SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 0.8F, 1.0F);
    }

    /** 消散表现: POOF 烟 (主人死亡/拴绳作废时召唤物消失可辨)。 */
    private static void emitPoof(ServerLevel level, Entity summon) {
        level.sendParticles(ParticleTypes.POOF, summon.getX(), summon.getY() + 0.5D, summon.getZ(),
                15, 0.3D, 0.4D, 0.3D, 0.02D);
    }

    /**
     * per-owner 召唤状态: 上次召唤 tick (冷却) + 名下召唤物 UUID 集 (存活计数/生命周期) + 各召唤物最后靠近 tick
     * (拴绳宽限, MIN_VALUE 不用 —— 建时即置 now) + 最后触达 tick (TTL 清扫依据, 扫描时刷新)。
     */
    private static final class SummonState {
        private long lastCastTick = Long.MIN_VALUE;
        private final Set<UUID> summonedIds = new HashSet<>();
        private final Map<UUID, Long> lastNearTick = new HashMap<>();
        private long lastTouchedTick = Long.MIN_VALUE;
    }
}
