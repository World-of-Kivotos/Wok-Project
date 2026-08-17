package com.miningdim.pressure;

import com.miningdim.core.Difficulty;
import com.miningdim.core.IInstanceManager;
import com.miningdim.core.IMiningConfig;
import com.miningdim.core.IMiningNetwork;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.MobInstanceTag;
import com.miningdim.core.RegionBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态压力刷怪 worker (设计文档第十章, DG-1..DG-7)。每 N tick 对矿山内在线玩家评估 danger,
 * 据 SpawnTier (10.4) 决定刷怪节奏并显式生成怪物, 受单实例硬上限 config.mobMaxPerInstance() 封顶 (DG-5,
 * 与陷阱引擎 DynamicTrapEngine 共用同一配置键 mob.maxPerInstance), 不依赖原版 mobcap。
 * danger HUD 经 IMiningNetwork.sendDanger 下发 (15.4.2)。
 *
 * 模块化: 仅经 core 门面 (MiningServices) 取 IInstanceManager / IMiningConfig / IMiningNetwork; 压力态
 * (PlayerMiningData) 由本包 Danger 自持, 不依赖其他子系统实现类。本类是压力子系统的事件处理 worker,
 * 子系统入口为 PressureSystem (implements Subsystem), 由其在 register 内 forgeBus.register 本实例。
 *
 * 线程纪律 (D8): 评估为轻量纯计算, 在 ServerTickEvent (主线程) 内完成; 刷怪落地直接在该 tick 内
 * 用 ServerLevel.addFreshEntityWithPassengers (本身已是主线程, 无需再 server.execute)。
 */
public final class MobPressureSystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/pressure");

    /** 出生冻结窗口 (11.7 SPAWN_FREEZE_TICKS), 玩家进入实例后该窗口内 danger 钳低且不刷怪。 */
    private static final int SPAWN_FREEZE_TICKS = 200;

    /** 刷怪选点视锥半角余弦 (9.7: dot(look,dir) < cos(70°) 即在视野外)。 */
    private static final double VIEW_CONE_COS = Math.cos(Math.toRadians(70.0));

    /** 身后刷怪最小/最大距离 (9.7)。 */
    private static final int BEHIND_MIN_DIST = 8;
    private static final int BEHIND_MAX_DIST = 20;

    /** 身后刷怪同玩家冷却 (9.7: >= 100 tick)。 */
    private static final int BEHIND_COOLDOWN_TICKS = 100;

    /** 每玩家周边活跃怪数的现查半径下限 (格): 至少覆盖身后刷怪的最远落点, 否则刚落地的怪数不进来。 */
    private static final int PER_PLAYER_COUNT_MIN_RADIUS = BEHIND_MAX_DIST;

    /** 选点尝试上限 (避免狭窄空腔下无限找点)。 */
    private static final int SPAWN_POINT_TRIES = 12;

    /** resolveStandableColumn 自玩家 Y 上下探查的最大格距。 */
    private static final int COLUMN_SEARCH_RANGE = 24;

    /** 怪物所属实例标记的反查表 (mob UUID -> instanceId), 供死亡回收计数 (10.5 step6)。 */
    private final Map<UUID, Long> mobInstanceIndex = new ConcurrentHashMap<>();

    /** 各实例刷怪调度态 (instanceId -> 下次可刷怪 tick + 各玩家身后刷怪冷却)。 */
    private final Map<Long, InstanceSpawnState> spawnStates = new ConcurrentHashMap<>();

    /** danger 计算核心 + 每玩家压力态注册表。本子系统自持。 */
    private final Danger danger = new Danger();

    // ---- 主循环: 每 tick 末驱动, 内部按评估周期节流 (DG-4) ----

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel mining = server.getLevel(MiningConstants.MINING_LEVEL);
        if (mining == null) {
            return; // 维度尚未加载
        }

        IMiningConfig config = MiningServices.config();
        IInstanceManager instances = MiningServices.instanceManager();
        IMiningNetwork network = MiningServices.network();
        long now = mining.getGameTime();
        int evalInterval = Math.max(1, config.dangerEvalIntervalTicks());

        for (ServerPlayer player : mining.players()) {
            tickPlayer(player, mining, instances, config, network, now, evalInterval);
        }
    }

    /** 实例是否已达刷怪硬上限 (与 spawnWave 共用单一真源, 供 GameTest 断言真实业务结果)。 */
    boolean atMobCap(InstanceState instance) {
        return instance.liveMobs().size() >= MiningServices.config().mobMaxPerInstance();
    }

    /**
     * 玩家周边当前活跃怪数 —— <b>现查世界, 不读账本</b>。
     *
     * 每玩家上限 (config.mobMaxPerPlayer) 的语义就是"这个玩家身边有几只", 而这件事世界本身随时知道:
     * 一次 AABB 实体查询即可, 且天然把死亡/despawn/被杀/走远算进去, 没有任何可漂移的中间状态。
     * 相比之下按 UUID 记一份"我刷过谁"的账本, 每条实体消失路径都得单独接线才能不失真 —— 那正是 F030。
     *
     * 只数受本系统实例标记的怪 (MobInstanceTag): 玩家自带的驯服动物、别的 mod 刷的怪、村民都不该占
     * 压力系统的额度, 否则玩家牵两只羊进矿洞就把自己的刷怪配额顶满了。
     */
    int nearbyMobCount(ServerPlayer player, ServerLevel level, int radius) {
        double r = Math.max(PER_PLAYER_COUNT_MIN_RADIUS, radius);
        return level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(r),
                mob -> mob.isAlive() && MobInstanceTag.isTagged(mob)).size();
    }

    /**
     * 对单个矿山内玩家推进压力评估 + 刷怪 (10.2/10.3/10.4 主路径)。
     * 不在任何实例 region (缓冲带/隔层) 或实例 active=false 的玩家跳过并清其压力态 (12.7)。
     */
    private void tickPlayer(ServerPlayer player, ServerLevel mining, IInstanceManager instances,
                            IMiningConfig config, IMiningNetwork network, long now, int evalInterval) {
        InstanceState instance = instances.regionAt(player.getBlockX(), player.getBlockZ());
        if (instance == null || !instance.active()) {
            danger.onLeave(player.getUUID());
            return;
        }

        PlayerMiningData data = danger.get(player.getUUID());
        if (data == null || data.instanceId() != instance.instanceId()) {
            // 首次观测或跨实例移动: 初始化压力态并起冻结窗口 (14.2 步骤 11 / 11.7)。
            data = danger.onEnter(player.getUUID(), instance.instanceId(), now);
            data.setSpawnFreezeUntil(now + SPAWN_FREEZE_TICKS);
        }

        // 按评估周期节流 (DG-4): 未到周期不重算 danger, 也不刷怪。
        if (now - data.lastEvalTick() < evalInterval) {
            return;
        }

        // zone 项按玩家实际所处难度子盒取 (10.2 zone 随实际 Y 变), 回退实例难度。
        Difficulty zone = Danger.zoneAt(player, instance.difficulty());

        // 矿物富集度: 矿物子系统尚无 core 门面暴露富集度, 暂以 0 计入 oreTerm。
        // 信息缺口: 需 core 增设读矿物富集度的门面 (如 IOreService.localOreRichness(level,pos)); 待其提供后接入。
        float oreRichness01 = 0.0f;

        // 活跃判定: 玩家在 region 内即视为作业 (移动/挖掘细分待行为事件接入, 此处按在区作业累积)。
        boolean activeInRegion = true;

        // 职业 danger 时间项系数 (Major 缺陷四): 经 pressure 自有 seam 取 (矿工耐压等职业子系统启动期 bind),
        // 缩放 tWin 累积/衰减。未接线 / 非耐压职业返回 1.0 (无缩放)。不硬 import 职业实现类 (模块化铁律 2)。
        float timeAccrueFactor = DangerJobFactor.factorFor(player);

        float dangerValue = danger.evaluate(data, zone, activeInRegion, oreRichness01, timeAccrueFactor, now, config);
        SpawnTier tier = SpawnTier.forDanger(dangerValue);

        // HUD 同步 (15.4.2): 下发 danger + tier + 光照感知系数。
        network.sendDanger(player, instance.instanceId(), dangerValue, (float) config.dangerMax(),
                tier.hudTier(), tier.lightDimFactor());

        // 冻结期不主动刷怪 (11.7); SAFE 档不刷怪 (10.4)。
        if (data.inSpawnFreeze(now) || !tier.spawns()) {
            return;
        }

        InstanceSpawnState ss = spawnStates.computeIfAbsent(instance.instanceId(), id -> new InstanceSpawnState());
        if (now < ss.nextSpawnTick) {
            return; // 刷怪间隔未到
        }
        ss.nextSpawnTick = now + tier.spawnIntervalTicks();

        spawnWave(player, mining, instance, tier, config, ss, now);
    }

    // ---- 刷怪流程 (10.5 / 9.7) ----

    /**
     * 对一个玩家刷一波怪 (10.5): 实例计数封顶 -> 选点 (身后约束 9.7) -> finalizeSpawn + checkSpawnRules
     * -> addFreshEntityWithPassengers -> 标记 + 计数。单波数量取 tier 区间随机, 受 mobMaxPerInstance 封顶。
     */
    private void spawnWave(ServerPlayer player, ServerLevel mining, InstanceState instance,
                           SpawnTier tier, IMiningConfig config, InstanceSpawnState ss, long now) {
        int mobCap = config.mobMaxPerInstance();
        if (atMobCap(instance)) {
            // 硬上限封顶, 本波跳过 (10.4: 只更新计时, 不强塞)。保留诊断便于调参观察封顶频率。
            LOGGER.debug("instance {} at mob cap {}, skipping wave", instance.instanceId(), mobCap);
            return;
        }

        RandomSource rng = mining.random;
        int spawnRadius = Math.max(BEHIND_MIN_DIST, config.mobSpawnRadius());
        int waveTarget = tier.waveMin() + rng.nextInt(tier.waveMax() - tier.waveMin() + 1);
        int budget = Math.min(waveTarget, mobCap - instance.liveMobs().size());

        /*
         * 每玩家周边上限是"并发存活数"而不是"单波上限"。
         *
         * 原实现写的是 budget = min(budget, mobMaxPerPlayer) —— 那只钳住了一波最多刷几只, 玩家原地不动
         * 每波都能再吃满一波, 实际身边并发数只受每实例 30 那道远得多的闸约束。配置键叫 maxPerPlayer,
         * 语义就该是"身边同时最多这么多只", 故这里改成按现查的周边存活数算剩余额度。
         */
        int perPlayerCap = Math.max(1, config.mobMaxPerPlayer());
        int headroom = perPlayerCap - nearbyMobCount(player, mining, spawnRadius);
        if (headroom <= 0) {
            LOGGER.debug("player {} already at per-player mob cap {}, skipping wave",
                    player.getGameProfile().getName(), perPlayerCap);
            return;
        }
        budget = Math.min(budget, headroom);

        boolean behindEnabled = tier.behindPlayerEnabled();
        double behindChance = config.mobBehindPlayerChance();

        for (int i = 0; i < budget; i++) {
            EntityType<? extends Mob> type = tier.allowedTypes().get(rng.nextInt(tier.allowedTypes().size()));

            // creeper 必须走身后约束 (9.7); 其余按 behindChance 决定是否身后刷。
            boolean behind = type == EntityType.CREEPER || (behindEnabled && rng.nextDouble() < behindChance);

            if (behind) {
                // 身后刷怪同玩家冷却 (9.7: >= 100 tick)。冷却内不刷身后怪, 本只跳过。
                long last = ss.behindCooldown.getOrDefault(player.getUUID(), Long.MIN_VALUE);
                if (now - last < BEHIND_COOLDOWN_TICKS) {
                    continue;
                }
            }

            BlockPos point = behind
                    ? findBehindSpawnPoint(player, mining, instance, rng)
                    : findNearbySpawnPoint(player, mining, instance, spawnRadius, rng);
            if (point == null) {
                continue; // 本只找不到合法点, 跳过 (不强塞)
            }

            Mob mob = spawnMob(type, mining, point, instance);
            if (mob == null) {
                continue;
            }

            if (behind) {
                ss.behindCooldown.put(player.getUUID(), now);
                // 方位提示音 (9.7 必须有提示音 / TR-1): 从生成点播声给玩家听声辨位。
                mining.playSound(null, point.getX() + 0.5, point.getY() + 0.5, point.getZ() + 0.5,
                        SoundEvents.CREEPER_PRIMED, SoundSource.HOSTILE, 1.0f, 1.0f);
            }
        }
    }

    /**
     * 构造并落地一只怪 (10.5 step3/4/5): finalizeSpawn 初始化 -> checkSpawnRules 合法性校验
     * -> addFreshEntityWithPassengers 主线程落地 -> PersistentData 标记 + 实例计数。校验/事件不过丢弃返回 null。
     *
     * 1.20.1 Forge: 直接调 Mob.finalizeSpawn 被 Forge 标记 @Deprecated (会绕过 MobSpawnEvent.FinalizeSpawn,
     * 破坏其他刷怪管理 mod 的修改, 与设计文档 87 行兼容目标相悖)。改用 ForgeEventFactory.onFinalizeSpawn:
     * 它内部仍调 finalizeSpawn 完成原版生成后处理, 同时触发事件; 事件被取消时返回 null, 视为本次刷怪作废。
     */
    private Mob spawnMob(EntityType<? extends Mob> type, ServerLevel level, BlockPos pos, InstanceState instance) {
        Mob mob = type.create(level);
        if (mob == null) {
            return null;
        }
        mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                level.random.nextFloat() * 360.0f, 0.0f);

        // finalizeSpawn (10.5) 经 Forge 事件工厂走: 用 SPAWNER 类型初始化, 走原版生成后处理 + 触发事件。
        // 返回 null 表示事件取消本次刷怪 (如 In Control 拦截), 丢弃实体。
        if (net.minecraftforge.event.ForgeEventFactory.onFinalizeSpawn(
                mob, level, level.getCurrentDifficultyAt(pos), MobSpawnType.SPAWNER, null, null) == null) {
            mob.discard();
            return null;
        }

        // checkSpawnRules: 用原版规则确保位置合法 (避免穿墙/淹没, 10.5 step3)。
        if (!mob.checkSpawnRules(level, MobSpawnType.SPAWNER)) {
            mob.discard();
            return null;
        }

        // 标记所属实例 (10.5 step5) 必须在入场之前打: 计数登记由 onEntityJoinLevel 按这个标记完成,
        // 而 addFreshEntityWithPassengers 内部就会触发 EntityJoinLevelEvent —— 标记晚一步, 这只怪就不计数。
        MobInstanceTag.mark(mob, instance.instanceId());

        level.addFreshEntityWithPassengers(mob);

        // 精英怪升格 seam (模块化铁律 2: 不硬 import champion 实现类): 落地成功后按实例难度尝试升格为冠军。
        // 未接线 (Champions 未加载) 时 promote 直接短路 (普通怪)。冠军照样占本实例计数 (入场事件已登记)。
        com.miningdim.champion.ChampionSpawnSeam.promote(mob, instance.difficulty());
        return mob;
    }

    // ---- 选点 (9.7 身后约束 + 站立点判定) ----

    /**
     * 玩家身后选点 (9.7): minDist..maxDist 环内、视锥外、region 内可站立点。
     * 站立点读真实世界方块 (运行期无体素视图): 头顶 2 格空气 + 脚下稳固承载面。
     */
    private BlockPos findBehindSpawnPoint(ServerPlayer player, ServerLevel level,
                                          InstanceState instance, RandomSource rng) {
        Vec3 look = player.getLookAngle();
        Vec3 lookFlat = new Vec3(look.x, 0, look.z).normalize();
        RegionBox box = instance.regionBox();

        for (int t = 0; t < SPAWN_POINT_TRIES; t++) {
            double dist = BEHIND_MIN_DIST + rng.nextDouble() * (BEHIND_MAX_DIST - BEHIND_MIN_DIST);
            double angle = rng.nextDouble() * Math.PI * 2.0;
            double dx = Math.cos(angle) * dist;
            double dz = Math.sin(angle) * dist;

            // 方向向量 (水平) 与视线水平分量夹角: dot >= cos(70°) 即在视野内, 弃 (9.7)。
            Vec3 dir = new Vec3(dx, 0, dz).normalize();
            if (lookFlat.lengthSqr() > 1.0e-6 && dir.dot(lookFlat) >= VIEW_CONE_COS) {
                continue;
            }

            int wx = (int) Math.floor(player.getX() + dx);
            int wz = (int) Math.floor(player.getZ() + dz);
            BlockPos found = resolveStandableColumn(level, box, wx, player.getBlockY(), wz);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 玩家周边任意方位选点 (低 danger 档非身后刷怪): 环内随机, 仅需站立点合法。 */
    private BlockPos findNearbySpawnPoint(ServerPlayer player, ServerLevel level,
                                          InstanceState instance, int radius, RandomSource rng) {
        RegionBox box = instance.regionBox();
        for (int t = 0; t < SPAWN_POINT_TRIES; t++) {
            double dist = BEHIND_MIN_DIST + rng.nextDouble() * Math.max(1, radius - BEHIND_MIN_DIST);
            double angle = rng.nextDouble() * Math.PI * 2.0;
            int wx = (int) Math.floor(player.getX() + Math.cos(angle) * dist);
            int wz = (int) Math.floor(player.getZ() + Math.sin(angle) * dist);
            BlockPos found = resolveStandableColumn(level, box, wx, player.getBlockY(), wz);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * 在 (wx, wz) 列、以 startY 为中心上下交替探查一个真实世界的合法站立点 (11.2 谓词的世界方块版)。
     * 须 ∈ region 盒 XZ。运行期用真实方块判定: 脚下固体 (isFaceSturdy UP) + 头顶 2 格空气。
     */
    private BlockPos resolveStandableColumn(ServerLevel level, RegionBox box, int wx, int startY, int wz) {
        if (!box.contains(wx, wz)) {
            return null;
        }
        int loY = box.originY();
        int hiY = box.originY() + box.sizeY() - 2; // 留头顶空间
        for (int d = 0; d < COLUMN_SEARCH_RANGE; d++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                int y = startY + sign * d;
                if (y < loY || y > hiY) {
                    continue;
                }
                BlockPos foot = new BlockPos(wx, y, wz);
                if (isWorldStandable(level, foot)) {
                    return foot;
                }
            }
        }
        return null;
    }

    /** 真实世界站立点判定 (头顶 2 格空气 + 脚下稳固承载面)。 */
    private static boolean isWorldStandable(ServerLevel level, BlockPos foot) {
        if (!level.getBlockState(foot).isAir() || !level.getBlockState(foot.above()).isAir()) {
            return false;
        }
        BlockPos below = foot.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    // ---- 实例计数的唯一增减两点 (10.5 step4/step6) ----

    /*
     * 计数为什么挂在实体进出世界这对事件上, 而不是挂在死亡事件 + 定期对账上 (F030 的正解):
     *
     * 怪走的本来就是原版消失逻辑 —— 落地用 MobSpawnType.SPAWNER 且不置 persistenceRequired, 所以超距
     * checkDespawn、区块卸载、苦力怕自爆 discard 全都按原版规则安静发生。旧实现的毛病不在"没保留原版消失",
     * 而在旁边另记了一份只在 LivingDeathEvent 里减的影子账本: 原版让怪消失了, 账本不知道, 于是只增不减,
     * 攒满 mobMaxPerInstance 后该实例永久停止刷怪。
     *
     * 先前的修法是每 5 秒轮询世界把账本同步回真相 —— 那是拿轮询去追一份本来就不该存在的副本。改成:
     *   入场 (EntityJoinLevelEvent) -> 登记;  离场 (EntityLeaveLevelEvent) -> 销账
     * 后, "带本系统实例标记且在世界里的怪" 与 "账本里的怪" 就是同一件事, 按构造相等, 不需要任何对账。
     * 离场事件覆盖全部消失路径 (死亡移除 / discard / despawn / 区块卸载 / 换维度), 入场事件则负责把区块
     * 重新加载回来的怪重新计上 —— 只接离场不接入场会让账本单向少算, 反而刷得比上限更多。
     */

    /** 带本系统实例标记的怪进入世界即登记 (含首次刷怪落地与区块重载回来的怪)。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        // LOWEST + isCanceled 检查: 本事件可被取消 (刷怪管理类 mod 会用), 被取消的实体不会真进世界, 不能计数。
        if (event.isCanceled() || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        Long instanceId = MobInstanceTag.instanceIdOf(mob);
        if (instanceId == null) {
            return; // 非本系统刷的怪 (玩家带进来的动物 / 别的 mod 刷的), 不占压力系统额度
        }
        mobInstanceIndex.put(mob.getUUID(), instanceId);
        MiningServices.instanceManager().byId(instanceId)
                .ifPresent(inst -> inst.liveMobs().add(mob.getUUID()));
    }

    /** 怪离开世界即销账。这是唯一的减账点, 覆盖死亡/discard/despawn/区块卸载/换维度全部路径。 */
    @SubscribeEvent
    public void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        UUID id = mob.getUUID();
        // 优先查反查表; 查不到再读标记 —— 服务端重启后账本是空的, 而世界里的怪仍带着标记。
        Long instanceId = mobInstanceIndex.remove(id);
        if (instanceId == null) {
            instanceId = MobInstanceTag.instanceIdOf(mob);
        }
        if (instanceId == null) {
            return;
        }
        final long target = instanceId;
        MiningServices.instanceManager().byId(target)
                .ifPresent(inst -> inst.liveMobs().remove(id));
    }

    // ---- 玩家离开: 清压力态 (12.6 离开汇聚 + 10.7 离区) ----

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        danger.onLeave(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        // 离开矿山维度即清压力态 (再进入时 onEnter 重置)。
        if (event.getFrom().equals(MiningConstants.MINING_LEVEL)) {
            danger.onLeave(event.getEntity().getUUID());
        }
    }

    /**
     * 实例重置/释放时的清理钩子 (10.7 与重置联动): 清空该实例的刷怪调度态与计数索引, 防泄漏。
     * 实体本身的移除由重置流程的 region 清块完成; 本方法只清本系统的影子态。
     */
    public void onInstanceReset(long instanceId) {
        spawnStates.remove(instanceId);
        mobInstanceIndex.entrySet().removeIf(e -> e.getValue() == instanceId);
    }

    /** danger 核心 (供 PressureSystem / 测试 / 陷阱 danger 注入适配器取 danger 读取能力)。 */
    public Danger danger() {
        return danger;
    }

    // ---- 每实例刷怪调度态 ----

    private static final class InstanceSpawnState {
        /** 下次允许刷怪的 tick (按 tier 间隔推进)。 */
        long nextSpawnTick = Long.MIN_VALUE;
        /** 各玩家身后刷怪上次落地 tick (9.7 同玩家冷却)。 */
        final Map<UUID, Long> behindCooldown = new HashMap<>();
    }
}
