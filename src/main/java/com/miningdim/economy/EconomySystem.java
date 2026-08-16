package com.miningdim.economy;

import com.miningdim.core.IInstanceManager;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.Subsystem;
import com.miningdim.error.MiningErrors;
import com.miningdim.error.MiningMessages;
import com.miningdim.store.MiningSchema;
import com.miningdim.store.MiningStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 经济/反滥用子系统入口 (设计文档第十八章; 模块化铁律 3)。
 *
 * 持有唯一 {@link AbuseGuard} 实例与玩家级 {@link PlayerAbuseState} 表 (UUID 键, 登入重建),
 * 在 register 内订阅所需 forge 事件, 把第十八章各闸门接到真实游戏事件上:
 *  - {@link BlockEvent.BreakEvent}: 18.3 矿物计数 + 18.4 刷新 lastBreakTick (解 AFK)。
 *  - {@link LivingDeathEvent} / {@link LivingDropsEvent}: 18.6 死亡惩罚 (再入冷却) 与掉落处理。
 *  - {@link PlayerEvent.Clone}: D5 跨死亡/换维度复制玩家反滥用态。
 *  - {@link PlayerEvent.PlayerLoggedInEvent} / {@code PlayerLoggedOutEvent}: 玩家态表生命周期。
 *  - {@link TickEvent.ServerTickEvent}: 18.4 AFK 评估 + 18.3/18.2 翻日清零, 每 evalInterval tick 一批。
 *
 * 跨子系统只经 core 门面: 经 {@link MiningServices#instanceManager()} 定位玩家所在实例 (regionAt),
 * 经 {@link MiningServices#config()} 读评估间隔。danger 实际写回 (18.5 重入起算值 / 18.6 死亡清零)
 * 属未交付压力子系统的职责, 本子系统只在 {@link AbuseGuard} 算出裁决值并存 {@link PlayerAbuseState},
 * 待压力子系统经其门面消费; 此处不 import 其实现类 (铁律 2)。
 *
 * 货币层接线 (经济文档第九章 + 框架 spec 第三章): 在 {@link ServerStartedEvent} 把旧存档
 * {@link EconomyWalletData} 一次性搬进统一 SQLite、在共享连接上建 {@link SqliteEconomyLedger} 账本、
 * 构造 {@link EconomyService}、注入 {@link EconomyServices} 定位器
 * (job 包定位器范式; 不碰 core.MiningServices, 见 EconomyServices 注释)。{@link ServerStoppingEvent} 时
 * 经 {@link EconomyServices#reset} 清引用防跨存档脏引用。第十八章闸门本身在 core 无对应门面接口, 仍只做事件接线。
 *
 * 线程: 全部回调在服务端主线程。状态表用并发容器仅作 Clone/登入登出可能的事件时序防御, 写仍只主线程。
 */
public final class EconomySystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/economy");

    /** 终态双币操作的保留期: 30 天。跨这个跨度的重放不可能来自同一次客户端交互。 */
    private static final long TERMINAL_OPERATION_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L;

    private final AbuseGuard abuseGuard = new AbuseGuard();

    /** 玩家级反滥用态 (UUID -> state)。登入建、登出留 (供重连/Clone), 无世界存档时仅内存。 */
    private final Map<UUID, PlayerAbuseState> playerStates = new ConcurrentHashMap<>();

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 全部是 forge 总线运行期事件 (挖掘/死亡/tick/玩家生命周期/服务端启停), 不涉及 mod 总线注册。
        forgeBus.register(this);
        // 面板层与事件层必须共用同一份玩家反滥用态和同一个 AbuseGuard: 传 this 是为了让 economy.priceTable 读到的
        // 当日矿物计数, 就是 onBlockBreak 正在写的那一份 (另起一份等于让面板显示一个谁也没在用的影子账)。
        EconomyWebUiActions.registerAll(this);
        EconomyAdminWebUiActions.registerAll();
        LOGGER.info("[miningdim] economy subsystem registered (abuse gates: reset/ore-softcap/afk/reentry/death)");
    }

    // ============================================================
    // 货币层接线 (服务端启停: 建账本 + 注入门面 / 清引用)
    // ============================================================

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        EconomyCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        ServerLevel mining = event.getServer().getLevel(MiningConstants.MINING_LEVEL);
        if (mining == null) {
            // 矿山维度未加载是配置错误 (20.2); 货币账本无处落盘, 不注入门面 (取用方在 EconomyServices 自然抛
            // IllegalStateException 暴露未接线, 不静默 fallback 掩盖)。启动期 ErrorSystem 已记 ERROR。
            LOGGER.error("[miningdim] economy: mining dimension absent at server start, wallet ledger not bound");
            return;
        }
        // 旧存档里的余额只在这里被读一次: 把 SavedData 账本搬进统一 SQLite 并打标记, 之后 .dat 只读留作回滚保险。
        // 迁移失败自然冒泡 —— 钱对不上时宁可起不来, 也不能带着说不清的余额开服。
        EconomyLedgerBootstrap.migrateIfNeeded(MiningStore.connection(),
                EconomyWalletData.get(mining), System.currentTimeMillis());
        // 存档若停在 user_version=1 直升 3 (case_openings 已在统一库、钱包还在 SavedData), MiningSchema
        // 的 V3 回填会在 ServerAboutToStartEvent 对着一张空的 bundle_operations 判定, 30 天保留期内的
        // COMMITTED 行因此全部误判成未结算; 此刻旧账本刚搬完, 证据才第一次到位, 必须补跑一次同一套判据
        // 把它们追平, 否则这批玩家会在保留期后被真实重复扣款 (见 MiningSchema.backfillCaseEconomySettled)。
        MiningSchema.backfillCaseEconomySettled(MiningStore.connection());
        // 门面引用由 EconomyServices 定位器持有 (单一所有者); 本子系统不另存字段, 避免与定位器重复持有。
        EconomyLedger ledger = new SqliteEconomyLedger(MiningStore.connection());
        // 态表唯一所有者仍是本子系统; 门面经 playerState 取同一 PlayerAbuseState (recordMinedOreDrops 计数 / isAfkFrozen 读冻结态)。
        EconomyServices.registerEconomyService(new EconomyService(ledger, abuseGuard, this::playerState));
        // 终态双币操作只用于有限窗口内的幂等重放, 不回收会随开箱次数无限累积。CHARGED 永不回收 ——
        // 那是在途的付款事实, 删掉等于让玩家的钱凭空消失且无从追溯。
        int pruned = ledger.pruneTerminalOperations(
                System.currentTimeMillis() - TERMINAL_OPERATION_RETENTION_MILLIS);
        if (pruned > 0) {
            LOGGER.info("[miningdim] economy: 回收了 {} 条已终结的双币操作记录", pruned);
        }
        LOGGER.info("[miningdim] economy: wallet ledger bound, IEconomyService registered (faucet/sink/settleOreSale live)");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // 清门面引用防跨存档/跨重启脏引用 (与 MiningServices.reset / JobServices.reset 同纪律)。
        EconomyServices.reset();
    }

    // ============================================================
    // 玩家态生命周期
    // ============================================================

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // 登入建态。Capability 子系统就绪后此处应从持久层 load(PlayerAbuseState.load); 当前阶段无持久层, 新建。
        playerStates.computeIfAbsent(player.getUUID(), k -> new PlayerAbuseState());
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        // 保留态以便重连 (18.x 玩家级数据不因下线清零); 无世界存档时该表随进程存活。
        // 不在此 remove, 避免重连丢失当日计数/冷却 (与设计文档 12.x 断线保留态一致)。
    }

    /**
     * D5 跨死亡/换维度复制玩家反滥用态。死亡 (wasDeath) 与换维度均把旧 Player 的态迁到新 Player 实例,
     * 保证当日矿物计数、重入冷却、死亡冷却不因死亡/切维度丢失 (否则死一次即清空当日上限 = 绕过 A2/A5)。
     */
    @SubscribeEvent
    public void onClone(PlayerEvent.Clone event) {
        UUID id = event.getEntity().getUUID();
        // 旧实例与新实例 UUID 相同 (同一玩家), 态以 UUID 为键已自然延续; 仅确保表内存在 (防极端时序缺失)。
        playerStates.computeIfAbsent(id, k -> new PlayerAbuseState());
    }

    private PlayerAbuseState stateOf(ServerPlayer player) {
        return playerStates.computeIfAbsent(player.getUUID(), k -> new PlayerAbuseState());
    }

    // ============================================================
    // 18.3 矿物计数 + 18.4 解 AFK (BlockEvent.BreakEvent)
    // ============================================================

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        // economy-04: 被取消的破坏事件 (反作弊/保护插件/上游 handler 拦截) 不真正破坏方块, 故整条结算 (计数 + 解 AFK +
        // 卖矿发钱) 都不得发生 —— 否则取消的破坏仍走 settleOreSale = 凭空印钞。守卫置于最顶, 与同库
        // FarmerSystem.onCropHarvested 的 isCanceled 范式一致; 撤掉则取消的破坏照样计数发钱。
        if (shouldSkipBreak(event)) {
            return;
        }
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        Level level = player.level();
        if (!isMiningDimension(level)) {
            return;
        }
        // 只统计真正落在某实例 region 内的挖掘 (缓冲带/region 外 regionAt 返回 null, 不计)。
        BlockPos pos = event.getPos();
        IInstanceManager mgr = MiningServices.instanceManager();
        InstanceState instance = mgr.regionAt(pos.getX(), pos.getZ());
        if (instance == null) {
            return;
        }

        PlayerAbuseState state = stateOf(player);
        long nowTick = level.getGameTime();

        // 18.4: 有效挖掘刷新 lastBreakTick 并立即解 AFK 冻结 (一次有效 BreakEvent 即解冻)。
        state.setLastBreakTick(nowTick);
        if (state.afkFrozen()) {
            state.setAfkFrozen(false);
        }

        recordAndSettleBreak(player, event.getState().getBlock(), state);
    }

    /**
     * economy-04 反洗钱守卫 (单一来源, 便于 GameTest 直断言): 被取消的破坏事件不真正破坏方块, 返回 true 令
     * {@link #onBlockBreak} 整条结算短路。撤掉此判定 (恒返 false) 则取消的破坏照样计数发钱 = 凭空印钞。
     */
    static boolean shouldSkipBreak(BlockEvent.BreakEvent event) {
        return event.isCanceled();
    }

    /**
     * 18.3 计数 + 第十一章决策 3 卖矿结算的单一出口 (从 {@link #onBlockBreak} 抽出, 便于 GameTest 直断言反洗钱口径:
     * 取消的破坏经 {@link #shouldSkipBreak} 不到达此方法, 故此结算永不为取消的破坏发钱)。
     *
     * 隐藏软上限 (经济文档 8.5 / Miner_Job_DesignSpec 第六章): 软上限"无形递减", 撞限后只在收购价 (settleOreSale)
     * 衰减体现, 不向玩家发任何"已达软上限"红字提示 (无撞墙挫败); 故此处仅计数, 不再 notify。
     *
     * 防重口径: recordMinedOre 已把本块计入当日计数并返回计入后的累计 (含本块), 直接用作 settleOreSale 的 countSoFar,
     * 一块只发一次。非高价矿 / AFK 冻结时 recordMinedOre 返回 -1, 自然短路不发钱 (计数与发钱共用同一 state 同一口径)。
     * settleOreSale 内部经 grantDaily 并入全服统一衰减主闸 (与农夫卖菜共享天花板); 货币层未绑定时 EconomyServices
     * 自然抛 IllegalStateException 由最外层 ErrorSystem 兜底 (不静默吞), 故此处仅在 isRegistered 时结算。
     */
    void recordAndSettleBreak(ServerPlayer player, Block block, PlayerAbuseState state) {
        int countSoFar = abuseGuard.recordMinedOre(state, block);
        if (countSoFar > 0) {
            EconomyConstants.HighValueOre ore = abuseGuard.classify(block);
            if (ore != null && EconomyServices.isRegistered()) {
                EconomyServices.economyService().settleOreSale(
                        player, ore, countSoFar, ShopPriceTable.oreBasePrice(ore));
            }
        }
    }

    // ============================================================
    // 18.6 死亡惩罚 (LivingDeathEvent + LivingDropsEvent)
    // ============================================================

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof ServerPlayer player)) {
            return;
        }
        Level level = player.level();
        if (!isMiningDimension(level)) {
            return;
        }
        BlockPos pos = player.blockPosition();
        IInstanceManager mgr = MiningServices.instanceManager();
        InstanceState instance = mgr.regionAt(pos.getX(), pos.getZ());
        long instanceId = instance != null ? instance.instanceId() : -1L;

        PlayerAbuseState state = stateOf(player);
        long nowTick = level.getGameTime();
        boolean clearDanger = abuseGuard.applyDeathPenalty(state, instanceId, nowTick);

        // 18.6 danger 处理: RESET_TO_ZERO 时记录"应清零"。danger 实际归属压力子系统 Capability,
        // 本子系统把离开值清零意图落在玩家态 (recordLeave 以 0 覆盖), 压力子系统消费时归零。
        if (clearDanger) {
            state.recordLeave(instanceId, nowTick, 0.0f);
        }

        // 18.6 复活点为"进入前坐标" (D5 priorPos), 实际传送由实例/出生子系统在 PlayerRespawnEvent 处理,
        // 此处仅给玩家死亡惩罚文案 (不暴露堆栈, 20.1)。
        MiningErrors.notify(player, MiningMessages.DEATH_PENALTY);
        LOGGER.info("[miningdim] death penalty applied: player={} instance={} reentryCooldownTicks={}",
                player.getGameProfile().getName(), instanceId,
                EconomyConstants.DEATH_REENTRY_COOLDOWN_TICKS);
    }

    @SubscribeEvent
    public void onDeathDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!isMiningDimension(player.level())) {
            return;
        }
        // 18.6 death.dropMode: KEEP_IN_PLACE 不动 (原版默认); DESPAWN_FAST 缩短 despawn; VOID 清除。
        switch (EconomyConstants.DEATH_DROP_MODE) {
            case KEEP_IN_PLACE:
                // 默认: 掉落物留在死亡点 region, 不干预。
                break;
            case DESPAWN_FAST:
                for (ItemEntity drop : event.getDrops()) {
                    // 把剩余存活时间压到接近 despawn 阈值 (原版 6000 tick despawn): lifespan 调小使其快速消失。
                    drop.lifespan = FAST_DESPAWN_LIFESPAN_TICKS;
                }
                break;
            case VOID:
                // 硬核向: 直接清空掉落集合, 死亡物品全损。
                event.getDrops().clear();
                break;
            default:
                throw new IllegalStateException("Unknown drop mode: " + EconomyConstants.DEATH_DROP_MODE);
        }
    }

    /** DESPAWN_FAST 模式下掉落物的 lifespan (tick): 远小于原版 6000, 快速 despawn 但留一点拾取窗口。 */
    private static final int FAST_DESPAWN_LIFESPAN_TICKS = 200;

    // ============================================================
    // 18.4 AFK 评估 + 翻日清零 (ServerTickEvent, 降频)
    // ============================================================

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        net.minecraft.server.MinecraftServer server = event.getServer();
        ServerLevel mining = server.getLevel(MiningConstants.MINING_LEVEL);
        if (mining == null) {
            // 维度未加载是配置错误 (20.2), 启动期 ErrorSystem 已记 ERROR; tick 路径静默跳过避免刷屏。
            return;
        }

        long nowTick = mining.getGameTime();
        int evalInterval = MiningServices.config().dangerEvalIntervalTicks();
        if (evalInterval <= 0) {
            evalInterval = EconomyConstants.TICKS_PER_SECOND;
        }
        if (nowTick % evalInterval != 0L) {
            return;
        }

        long playerDayStamp = abuseGuard.currentPlayerDayStamp();
        IInstanceManager mgr = MiningServices.instanceManager();

        // 仅评估在矿山维度内的在线玩家 (18.4: AFK 只对在区玩家有意义; 离区玩家无 danger 累积可冻结)。
        for (ServerPlayer player : mining.players()) {
            PlayerAbuseState state = stateOf(player);

            // 18.3 翻日: 每玩家当日矿物计数跨真实日清零 (在降频 tick 批量做, 不在请求路径)。
            state.rolloverIfNewDay(playerDayStamp);

            // 只对落在实例 region 内的玩家做 AFK 评估 (缓冲带/region 外不参与压力, 无需冻结)。
            BlockPos pos = player.blockPosition();
            InstanceState instance = mgr.regionAt(pos.getX(), pos.getZ());
            if (instance == null) {
                continue;
            }
            abuseGuard.evaluateAfk(state, player.getX(), player.getZ(), nowTick);
        }

        // 18.2 实例重置计数翻日: 对每个存活实例批量清零 (用任一在线玩家解析游戏时间; 无玩家则跳过, 下次再清)。
        ServerPlayer anyPlayer = mining.players().isEmpty()
                ? (server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0))
                : mining.players().get(0);
        if (anyPlayer != null) {
            final ServerPlayer ref = anyPlayer;
            mgr.forEach(inst -> abuseGuard.rolloverResetCounters(ref, inst.instanceId()));
        }
    }

    // ============================================================
    // 对外暴露 (供 ResetService / 出生 / 进入 Gateway 子系统在最外层调用)
    // ============================================================

    /** 暴露闸门实例 (阶段2 接线: ResetService 调 checkAndChargeReset, 进入 Gateway 调 checkReentryGate)。 */
    public AbuseGuard abuseGuard() {
        return abuseGuard;
    }

    /** 取某玩家反滥用态 (阶段2 接线: 进入/重置流程读重入冷却、压力子系统消费 danger 离开值)。 */
    public PlayerAbuseState playerState(UUID playerId) {
        return playerStates.computeIfAbsent(playerId, k -> new PlayerAbuseState());
    }

    // ============================================================
    // 工具
    // ============================================================

    private boolean isMiningDimension(Level level) {
        return level.dimension().equals(MiningConstants.MINING_LEVEL);
    }
}
