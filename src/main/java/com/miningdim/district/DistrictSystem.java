package com.miningdim.district;

import com.miningdim.core.Subsystem;
import com.miningdim.district.command.DistrictCommands;
import com.miningdim.district.core.AcademyCatalog;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.flan.FlanGateway;
import com.miningdim.district.flan.GatewaySelector;
import com.miningdim.district.flan.ReconcileScheduler;
import com.miningdim.district.flan.real.FlanBridge;
import com.miningdim.district.flan.real.FlanCompat;
import com.miningdim.district.guard.DistrictWorldGuards;
import com.miningdim.district.guard.GuardEvents;
import com.miningdim.district.guard.GuardMixinStatus;
import com.miningdim.district.guard.GuardSettings;
import com.miningdim.district.guard.PersonalClaimGuard;
import com.miningdim.district.guard.create.CreateHookTargets;
import com.miningdim.district.notice.DistrictNotices;
import com.miningdim.district.notice.LoginGateNoticeGate;
import com.miningdim.district.notice.NoticeDeliveryGates;
import com.miningdim.district.service.DistrictContext;
import com.miningdim.district.service.SeenPlayerBackfill;
import com.miningdim.district.service.ServerPlayerDirectory;
import com.miningdim.district.store.SqliteDistrictRepository;
import com.miningdim.district.web.DistrictWebUiActions;
import com.miningdim.economy.EconomyServices;
import com.miningdim.store.MiningStore;
import com.miningdim.store.SchemaMigrator;
import com.miningdim.webui.server.HubPanelGates;
import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.forgespi.language.IModInfo;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 自管区子系统 (wok-district): 学院名单、自管区绑定与归档、公共区域开关、住户地块 (统一库 MiningSchema V9), Flan 网关
 * 与对账。设计见 docs/District_Backend_Design.md (阶段 2 见第二十章)。
 *
 * <p>生命周期:
 * <ul>
 *   <li>register: 注册 miningdim-district.toml ({@link DistrictConfig}, enabled 默认 false); 向派发器登记 26 条动作
 *       (须排在 WebUiServerSubsystem 之后); 在 hub.panels 登记自管区入口的门 (功能 LIVE 才下发); RegisterCommandsEvent
 *       注册 /district 命令; 装上聊天通知的投递 gate (22.12: 本分支只在验证身份的服务器上上线即发, 离线模式下
 *       留在队列里)。</li>
 *   <li>ServerStarting: enabled 为假 → 功能 OFF, 不绑定服务、不碰 Flan。否则建仓储; 库里缺 district_notice 表时直接
 *       降级 (22.19); 选网关 ({@link GatewaySelector}:
 *       记录型只限 GameTest 服务端 → 没装 Flan / 版本不符 / 自检不过 → Disabled (DEGRADED) → 真网关 (LIVE)), 绑定
 *       {@link DistrictServices}; 六校 INSERT OR IGNORE, 开关格按目录回填。LIVE 时先对每个有在用自管区的维度做一次
 *       开服备份, 然后才做别的事。</li>
 *   <li>ServerStarted: 见过的玩家回填 (只做一次)、补收停服期间到期的冻结地块; LIVE 时安排开服对账。</li>
 *   <li>ServerStopping: 解除绑定, 功能状态复位; 连接由存储子系统在 ServerStopped 关闭, 此处不碰。</li>
 *   <li>登录 / 登出: 记见过的玩家表, 首次登录补写待生效的住户与朋友 (排着的通知一并换键); 然后交给通知 gate, 登录确认
 *       之后补发离线期间的聊天通知 (22.10–22.12)。数据库失败只记 ERROR, 不能打断登录。</li>
 *   <li>ServerTick END: 每 1200 tick 收回一次到期的冻结地块、重建一次空间索引、清一次过期通知 (每小时至多一次); LIVE 时
 *       推进对账 (每 6000 tick 起一轮, 每 tick 限时 2 ms)。</li>
 *   <li>OnDatapackSyncEvent (玩家为 null = 整服 /reload): 作废 Flan 权限表缓存。</li>
 * </ul>
 * 全部钩子一律经 {@link DistrictServices} 取当前绑定, 本类不另持引用: GameTest 换上的测试 context 因此能经真实的
 * 登录事件验证首次登录补写。
 */
public final class DistrictSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, DistrictConfig.SPEC, "miningdim-district.toml");
        forgeBus.register(this);
        // 守卫的 Forge 事件 (22.5、22.6、22.8、22.9): 门面没装 (功能 OFF、GameTest 默认) 时什么都不拦。
        forgeBus.register(GuardEvents.class);
        // 通知的投递时机 (22.12): 玩家通过登录门之后才发, 没通过之前留在队列里 (见 LoginGateNoticeGate)。
        NoticeDeliveryGates.install(new LoginGateNoticeGate(), DistrictSystem::onLoginConfirmed);
        DistrictWebUiActions.registerAll();
        HubPanelGates.register("district", DistrictFeature::live);
        LOGGER.info("[miningdim] district subsystem registered (academy rosters, districts, plots, Flan gateway, "
                + "{} WebUI action(s), /district)", DistrictWebUiActions.actionCount());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        DistrictCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        if (!enabled()) {
            DistrictFeature.reset();
            LOGGER.info("[miningdim] district feature is off (serverconfig/miningdim-district.toml enabled = false); "
                    + "no services bound, Flan untouched");
            return;
        }
        SqliteDistrictRepository repo = new SqliteDistrictRepository(MiningStore.connection());
        boolean noticeTable = SchemaMigrator.tableExists(repo.connection(), NOTICE_TABLE);
        if (!noticeTable) {
            LOGGER.error(NOTICE_TABLE_MISSING_LOG, String.join(";\n    ", NOTICE_TABLE_REPAIR_SQL));
        }
        GatewaySelector.Selection selection = selectFor(server, noticeTable);
        FlanGateway gateway = selection.gateway();
        GuardSettings guards = GuardSettings.fromConfig();
        DistrictContext ctx = DistrictContext.of(repo, gateway, System::currentTimeMillis,
                () -> EconomyServices.isRegistered() ? EconomyServices.economyService() : null,
                ServerPlayerDirectory.forServer(server, repo, System::currentTimeMillis), guards,
                uuid -> {
                    PlayerList players = server.getPlayerList();
                    return players == null ? null : players.getPlayer(uuid);
                });
        if (!noticeTable) {
            // 缺表时通知一律不做 (入队、投递、首次登录换键、清过期), 业务动作、登录与定时节拍照常; 功能已按上面的选择
            // 降级为只读 (22.19)。
            ctx.notices().disableStore();
        }
        long now = System.currentTimeMillis();
        int academies = repo.inTransaction(() -> repo.ensureAcademies(AcademyCatalog.LAUNCH, now));
        int cells = repo.inTransaction(repo::backfillCells);
        DistrictServices.register(ctx);
        // 守卫只依赖库, 不依赖 Flan: DEGRADED 与 LIVE 都装 (22.3)。
        DistrictWorldGuards.install(ctx);
        LOGGER.info("[miningdim] district guards installed ({} district(s) indexed; crossPlot {}, createMachinery {}, "
                        + "personalClaims {}, Create namespaces [{}], {} list problem(s))",
                ctx.zones().current().districtCount(), guards.crossPlot(), guards.createMachinery(),
                guards.personalClaims(), guards.namespacesText(), guards.problems().size());
        boolean live = selection.state() == DistrictFeature.State.LIVE;
        DistrictFeature.set(new DistrictFeature.Status(selection.state(), selection.reason(), selection.flanVersion(),
                selection.problems(), gateway.getClass().getSimpleName()),
                live ? new ReconcileScheduler(System::currentTimeMillis, System::nanoTime) : null);
        LOGGER.info("[miningdim] district context bound (feature {}{}, gateway {}, {} academy row(s) added, {} "
                        + "permission cell(s) backfilled)", selection.state(),
                selection.reason() == null ? "" : ": " + selection.reason(), gateway.getClass().getSimpleName(),
                academies, cells);
        if (live) {
            startupBackups(ctx);
        }
        // 登录门的接缝 (22.12): 合并之后还装着默认 gate 记 ERROR; 离线模式的服务器上通知一条都不发, 记 WARN。
        NoticeDeliveryGates.checkWiring(server);
    }

    /** 通知队列的表名。 */
    public static final String NOTICE_TABLE = "district_notice";

    /**
     * 补建 district_notice 的两条语句 (与 MiningSchema V9 末尾追加的两条逐字相同; DistrictNoticeGameTests 核对补建出来的
     * 结构与迁移建的一样)。只给运维照着手工执行, 代码不在迁移之外建表。
     */
    public static final List<String> NOTICE_TABLE_REPAIR_SQL = List.of(
            "CREATE TABLE district_notice ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "recipient_uuid TEXT NOT NULL, "
                    + "kind TEXT NOT NULL, "
                    + "args_json TEXT NOT NULL DEFAULT '[]', "
                    + "district_id TEXT, "
                    + "plot_id TEXT, "
                    + "created_at INTEGER NOT NULL)",
            "CREATE INDEX idx_district_notice_recipient ON district_notice(recipient_uuid, id)");

    /**
     * 缺表时的 ERROR (22.19): 只给不丢数据的修法。miningdim.db 是统一库 (钱包与账本、成就、称号、职业……全在里面), 绝不能
     * 让人删它。
     */
    private static final String NOTICE_TABLE_MISSING_LOG = "[miningdim] miningdim.db is at schema version 9 but has no "
            + "district_notice table (the save was opened by a phase 1-2 development build of the district module). "
            + "The district feature runs DEGRADED (read-only tablet, no chat notices) until it is repaired. Repair "
            + "WITHOUT losing data: stop the server, back up <world>/miningdim.db, run these two statements on it with "
            + "sqlite3, then start again:\n    {};\nDo NOT delete miningdim.db: it is the unified store of every module "
            + "(wallets and ledger, achievements, titles, jobs, ...)";

    /**
     * 开服选网关, 先看库 (22.19): 缺 district_notice 表时直接降级 (Disabled 网关带上原因, 真网关不造, 不碰 Flan), 否则照常
     * {@link #selectGateway}。public 只为 GameTest 能核对这道门。
     */
    public static GatewaySelector.Selection selectFor(@Nullable MinecraftServer server, boolean noticeTable) {
        if (!noticeTable) {
            return GatewaySelector.degradedFor(DistrictTexts.NOTICE_TABLE_MISSING, loadedFlanVersion());
        }
        return selectGateway(server);
    }

    /** enabled 读不到 (配置还没加载) 时按关闭处理。 */
    private static boolean enabled() {
        try {
            return DistrictConfig.ENABLED.get();
        } catch (IllegalStateException notLoaded) {
            LOGGER.warn("[miningdim] miningdim-district.toml is not loaded; treating the district feature as off");
            return false;
        }
    }

    /**
     * 开服选网关 (20.2): 记录型只限 GameTest 服务端 → Flan 没装 / 版本不符 / 自检不过 → Disabled → 真网关。
     * Flan 的类只在 ModList 报告已加载、自检通过之后才第一次被碰。public 只为 GameTest 能核对这道门。
     */
    public static GatewaySelector.Selection selectGateway(@Nullable MinecraftServer server) {
        boolean loaded = ModList.get() != null && ModList.get().isLoaded(FlanCompat.MOD_ID);
        String version = loadedFlanVersion();
        return GatewaySelector.select(server, loaded, version,
                () -> FlanCompat.check(version, FlanCompat.expectedSignatures(), DistrictSystem.class.getClassLoader(),
                        server),
                () -> FlanBridge.create(server, System::currentTimeMillis));
    }

    /** ModList 报告已加载的 Flan 的版本串; 没有加载 (或 ModList 还没建好) 为 null。只读 ModList, 不碰 Flan 的类。 */
    @Nullable
    public static String loadedFlanVersion() {
        ModList mods = ModList.get();
        if (mods == null || !mods.isLoaded(FlanCompat.MOD_ID)) {
            return null;
        }
        return mods.getModContainerById(FlanCompat.MOD_ID)
                .map(container -> container.getModInfo())
                .map(IModInfo::getVersion)
                .map(Object::toString)
                .orElse(null);
    }

    /** 开服备份 (20.5): 进入 LIVE 之后、做任何别的事之前, 每个有在用自管区的维度一份。它代表"最近一次能正常载入的状态"。 */
    private static void startupBackups(DistrictContext ctx) {
        Set<String> dimensions = new LinkedHashSet<>();
        for (DistrictRecord district : ctx.repo().liveDistricts()) {
            dimensions.add(district.bounds().dimension());
        }
        for (String dimension : dimensions) {
            var backup = ctx.gateway().backupNow(dimension, "start");
            if (backup.ok()) {
                LOGGER.info("[miningdim] district: startup backup of the Flan admin claims of {}: {}", dimension,
                        backup.value());
            } else {
                LOGGER.error("[miningdim] district: startup backup of the Flan admin claims of {} failed: {}",
                        dimension, backup.error());
            }
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // mixin 与功能开关无关: 功能 OFF 时照样核对, 只记 INFO (22.7)。
        try {
            GuardMixinStatus.check(DistrictServices.isRegistered());
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.error("[miningdim] district guard mixin check failed", failure);
        }
        if (!DistrictServices.isRegistered()) {
            return;
        }
        DistrictContext ctx = DistrictServices.context();
        MinecraftServer server = event.getServer();
        try {
            GameProfileCache cache = server.getProfileCache();
            SeenPlayerBackfill.Result result = SeenPlayerBackfill.runOnce(ctx.repo(),
                    server.getWorldPath(LevelResource.PLAYER_DATA_DIR),
                    uuid -> cache == null ? Optional.empty() : cache.get(uuid).map(GameProfile::getName),
                    ctx.now());
            if (!result.alreadyDone()) {
                LOGGER.info("[miningdim] district seen-player backfill added {} player(s)", result.inserted());
            }
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] district seen-player backfill failed; it will be retried at the next start",
                    failure);
        }
        sweep("server start");
        ReconcileScheduler scheduler = DistrictFeature.scheduler();
        if (DistrictFeature.live() && scheduler != null) {
            // 开服对账: 在开服备份、老玩家回填、到期收回之后, 从下一 tick 起按时间切片跑。
            scheduler.request();
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        DistrictWorldGuards.reset();
        DistrictServices.reset();
        DistrictFeature.reset();
    }

    @SubscribeEvent
    public void onDatapackSync(OnDatapackSyncEvent event) {
        // 玩家为 null: 整服重载 (/reload), 不是某个玩家登录时的同步。Flan 的权限表可能换了一张。
        if (event.getPlayer() == null && DistrictServices.isRegistered()) {
            DistrictServices.context().gateway().onPermissionsReloaded();
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !DistrictServices.isRegistered()) {
            return;
        }
        try {
            DistrictServices.context().firstLogin().onLogin(player.getUUID(), player.getGameProfile().getName());
        } catch (RuntimeException failure) {
            // 登录是生命周期边界: 数据库失败只让这名玩家本次不补写, 不能把登录流程一起打断。
            LOGGER.error("[miningdim] district login bookkeeping failed for {}", player.getGameProfile().getName(),
                    failure);
        }
        try {
            // 在首次登录补写 (含通知换键) 之后: 登录门的 gate 在这里什么都不做, 等登录确认再调 onLoginConfirmed (22.12)。
            NoticeDeliveryGates.current().onPlayerJoined(player);
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] district notice gate failed at login of {}", player.getGameProfile().getName(),
                    failure);
        }
    }

    /**
     * 这名玩家可以看私人内容了 (22.12, 经 {@link NoticeDeliveryGates} 登记的回调): 上线补发排着的通知, 然后给 OP 发
     * "机械动力防护不完整"(22.7) 与"个人圈地限制没有生效"(22.20) 的红字 (不入队)。功能 OFF 时什么都不做; 各段各自接住
     * 异常, 不影响登录与别的监听者。
     */
    static void onLoginConfirmed(ServerPlayer player) {
        if (!DistrictServices.isRegistered()) {
            return;
        }
        DistrictNotices.deliverPendingOnLogin(player);
        try {
            warnOpAboutIncompleteCreateProtection(player);
            warnOpAboutIncompleteClaimLimit(player);
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] district guard login notice failed for {}", player.getGameProfile().getName(),
                    failure);
        }
    }

    /** 机械动力防护不完整时 (22.7), OP 登录确认之后收到一条红字 (不入队)。 */
    private static void warnOpAboutIncompleteCreateProtection(ServerPlayer player) {
        GuardMixinStatus.Status status = GuardMixinStatus.last();
        if (status == null || status.createComplete() || !player.hasPermissions(2)) {
            return;
        }
        player.sendSystemMessage(Component.translatable(GuardEvents.KEY_INCOMPLETE_OP, status.missingIds(),
                status.createApplied(), CreateHookTargets.HOOKS.size()).withStyle(ChatFormatting.RED));
    }

    /** 装着 Flan 而个人圈地限制的 F1、F2 缺了哪个时 (22.20), OP 登录确认之后收到一条红字 (不入队)。 */
    private static void warnOpAboutIncompleteClaimLimit(ServerPlayer player) {
        GuardMixinStatus.Status status = GuardMixinStatus.last();
        if (status == null || status.flanComplete() || !player.hasPermissions(2)) {
            return;
        }
        player.sendSystemMessage(Component.translatable(PersonalClaimGuard.KEY_INCOMPLETE_OP,
                status.flanMissingIds(), DistrictLimits.BUFFER_BLOCKS).withStyle(ChatFormatting.RED));
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !DistrictServices.isRegistered()) {
            return;
        }
        try {
            DistrictServices.context().firstLogin().onLogout(player.getUUID());
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] district logout bookkeeping failed for {}", player.getGameProfile().getName(),
                    failure);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        int tick = event.getServer().getTickCount();
        if (isSweepTick(tick)) {
            sweep("tick");
        }
        reconcileTick(tick);
    }

    /** 定时收回的节拍: 每 {@link DistrictLimits#SWEEP_INTERVAL_TICKS} tick 一次 (第 0 tick 不算)。 */
    public static boolean isSweepTick(int tickCount) {
        return tickCount > 0 && tickCount % DistrictLimits.SWEEP_INTERVAL_TICKS == 0;
    }

    /** 对账只在 LIVE 时跑; 功能在运行中熔断 (转 DEGRADED) 后自然停下。 */
    private static void reconcileTick(int tick) {
        ReconcileScheduler scheduler = DistrictFeature.scheduler();
        if (scheduler == null || !DistrictFeature.live() || !DistrictServices.isRegistered()) {
            return;
        }
        try {
            DistrictContext ctx = DistrictServices.context();
            scheduler.tick(tick, ctx.reconciler(), ctx.repo(), ctx.gateway());
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] district reconciliation tick failed", failure);
        }
    }

    /**
     * 定时节拍 (每 {@link DistrictLimits#SWEEP_INTERVAL_TICKS} tick, 开服一次): 收回到期的冻结地块; 空间索引顺带重建一次
     * (22.3 兜底: 以后万一有新的几何写入忘了登记, 最多一分钟就对上), 前提是连接不在事务里。public 只为 GameTest 能直接
     * 触发一次节拍。
     */
    public static void sweep(String trigger) {
        if (!DistrictServices.isRegistered()) {
            return;
        }
        DistrictContext ctx = DistrictServices.context();
        try {
            int reclaimed = ctx.freezes().reclaimExpired(null);
            if (reclaimed > 0) {
                LOGGER.info("[miningdim] district sweep ({}) reclaimed {} expired frozen plot(s)", trigger, reclaimed);
            }
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] district sweep ({}) failed", trigger, failure);
        }
        try {
            if (!ctx.repo().inOpenTransaction()) {
                ctx.zones().rebuild();
            }
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] district sweep ({}) could not rebuild the zone index", trigger, failure);
        }
        try {
            if (!ctx.repo().inOpenTransaction()) {
                // 通知的保留期 (22.11, P29): 每小时至多一次。
                int pruned = ctx.notices().pruneExpired();
                if (pruned > 0) {
                    LOGGER.info("[miningdim] district sweep ({}) dropped {} chat notice(s) older than 30 days",
                            trigger, pruned);
                }
                // 朋友通知的即时送达有间隔 (22.19): 间隔到了的补发给在线的收件人。
                ctx.notices().flushHeld();
            }
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] district sweep ({}) could not prune or flush chat notices", trigger, failure);
        }
    }
}
