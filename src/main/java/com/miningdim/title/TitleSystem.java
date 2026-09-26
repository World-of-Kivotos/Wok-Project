package com.miningdim.title;

import com.miningdim.core.Subsystem;
import com.miningdim.store.MiningStore;
import com.miningdim.title.network.TitleNetwork;
import com.miningdim.title.store.SqliteTitleRepository;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 称号子系统 (wok-title, foundation): 定义加载、SQLite 持有/佩戴、{@link ITitleService} 门面、三处显示、
 * 名牌同步包、赞助专属称号、/mtitle 命令与 G 面板的 title.* action。详见 docs/Title_System_DesignSpec.md。
 *
 * <p>生命周期:
 * <ul>
 *   <li>mod 构造期: 注册服务端配置 miningdim-title.toml (专属称号的校验阈值与冷却), 挂 forge 总线, 把
 *       {@link TitleWebUiActions} 注册进 WebUI 派发器; FMLCommonSetup 注册自有网络通道。</li>
 *   <li>AddReloadListenerEvent: 挂定义加载器 (开服与每次 /reload 都整表替换)。</li>
 *   <li>ServerStarting: 在存储子系统已开好的共享连接上绑定仓库与门面, 注入 {@link TitleServices}。</li>
 *   <li>ServerStopping: 清定位器、清定义; 连接由存储子系统在 ServerStopped 关闭, 此处不碰。
 *       停服时 PlayerLoggedOut 晚于 ServerStopping 触发, 那时定位器已清空, 登出钩子自然跳过。</li>
 * </ul>
 *
 * <p>全部钩子一律经 {@link TitleServices} 取当前注入的门面, 本类不另持引用: 显示 (NameFormat / TabListNameFormat /
 * 开始追踪) 只读门面的内存缓存, 不查库; 登录加载、登出移出、重载后刷新、队伍变化兜底、赞助到期巡检需要门面之外的
 * 生命周期方法, 取到的实现是本模块的 {@link TitleService} 时才执行。GameTest 因此可以注入临时库上的实现, 经真实的
 * 登录、登出、重生事件验证这些钩子。
 */
public final class TitleSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/title");

    /** Tab 名队伍兜底重算的间隔 (tick): 每秒一次, 结果不变时不发包。 */
    private static final int TEAM_RECHECK_INTERVAL_TICKS = 20;
    /** 在线赞助玩家到期巡检的间隔 (tick): 每 60 秒一次 (Title_System_DesignSpec 13.5), 只读缓存。 */
    private static final int SPONSOR_EXPIRY_CHECK_INTERVAL_TICKS = 60 * 20;

    private final TitleDefinitions definitions = new TitleDefinitions();
    private final TitleDefinitionLoader loader = new TitleDefinitionLoader(definitions);

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, TitleConfig.SPEC, "miningdim-title.toml");
        forgeBus.register(this);
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(TitleNetwork::register));
        TitleWebUiActions.registerAll();
        LOGGER.info("[miningdim] title subsystem registered (datapack titles, sponsor custom titles, "
                + "SQLite ownership, /mtitle, title.* WebUI actions)");
    }

    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(loader);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // 称号表在统一库 miningdim.db (MiningSchema V5 持有与佩戴、V6 赞助资格与专属称号); 连接由存储子系统在
        // ServerAboutToStart 开好并完成迁移。
        TitleServices.registerTitleService(new TitleService(new SqliteTitleRepository(MiningStore.connection()),
                definitions, event.getServer()));
        LOGGER.info("[miningdim] title service bound ({} definition(s) loaded)", definitions.size());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        TitleServices.reset();
        definitions.clear();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        TitleCommands.register(event.getDispatcher());
    }

    // ---- 在线缓存 ----

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        TitleService service = boundService();
        if (!(event.getEntity() instanceof ServerPlayer player) || service == null) {
            return;
        }
        try {
            service.loadPlayer(player);
        } catch (RuntimeException exception) {
            // 登录是生命周期边界: 读库失败只让这名玩家本次不显示称号, 不能把登录流程一起打断。
            LOGGER.error("[miningdim] failed to load titles for {}", player.getGameProfile().getName(), exception);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        TitleService service = boundService();
        if (!(event.getEntity() instanceof ServerPlayer player) || service == null) {
            return;
        }
        service.forgetPlayer(player.getUUID());
    }

    @SubscribeEvent
    public void onDatapackSync(OnDatapackSyncEvent event) {
        // 只处理 /reload (getPlayer()==null): 单个玩家登录时的这次同步早于登录事件, 那时缓存尚未加载,
        // 登录钩子自己会刷新。重载后前缀缓存已随定义换代作废, 这里把在线玩家的三处显示刷一遍。
        TitleService service = boundService();
        if (event.getPlayer() != null || service == null) {
            return;
        }
        service.refreshOnlineDisplays(event.getPlayerList().getPlayers());
    }

    /**
     * 两项低频巡检, 都只读缓存: 队伍变化兜底 (原版改队伍不触发 Tab 名重算, 每秒对佩戴着称号的玩家重算一次) 与
     * 在线赞助玩家的到期检查 (每 60 秒一次)。详见 TitleService 对应方法。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        int tick = server.getTickCount();
        boolean teamRecheck = tick % TEAM_RECHECK_INTERVAL_TICKS == 0;
        boolean sponsorCheck = tick % SPONSOR_EXPIRY_CHECK_INTERVAL_TICKS == 0;
        if (!teamRecheck && !sponsorCheck) {
            return;
        }
        TitleService service = boundService();
        if (service == null) {
            return;
        }
        if (sponsorCheck) {
            // 逐人巡检、逐人兜住异常: 巡检只有在真的卸下时才写库, 某名玩家写库失败只让他的卸下推迟到下一轮, 既不拖累
            // 排在他后面的玩家, 也不能让外观功能打断服务端 tick。
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                try {
                    service.checkSponsorExpiry(List.of(player));
                } catch (RuntimeException exception) {
                    LOGGER.error("[miningdim] sponsor expiry check failed for {}", player.getGameProfile().getName(),
                            exception);
                }
            }
        }
        if (teamRecheck) {
            service.refreshTitledTabNames(server.getPlayerList().getPlayers());
        }
    }

    // ---- 三处显示 ----

    /**
     * 聊天前缀: 1.20.1 的聊天发送者名取自 getDisplayName, 改显示名即可, 不碰聊天签名。
     * 集成服务端里服务端与客户端共用同一条 forge 总线, 客户端侧玩家实体也会触发本事件, 必须挡掉,
     * 否则称号会混进客户端的名牌文字。
     */
    @SubscribeEvent
    public void onNameFormat(PlayerEvent.NameFormat event) {
        Player player = event.getEntity();
        if (player.level().isClientSide() || !TitleServices.isRegistered()) {
            return;
        }
        Component prefix = TitleServices.titleService().displayPrefix(player.getUUID());
        if (prefix != null) {
            event.setDisplayname(Component.empty().append(prefix).append(event.getDisplayname()));
        }
    }

    /**
     * Tab 列表: 事件默认值为 null (原版显示"队伍格式化后的名字"), 有称号时要自己把这层格式化补回来再拼前缀。
     * 显示名一旦非 null, 客户端就不再自己套队伍样式, 而队伍变化不会触发 Tab 名重算, 所以由 {@link #onServerTick}
     * 每秒兜底重算一次。
     */
    @SubscribeEvent
    public void onTabListNameFormat(PlayerEvent.TabListNameFormat event) {
        Player player = event.getEntity();
        if (player.level().isClientSide() || !TitleServices.isRegistered()) {
            return;
        }
        Component prefix = TitleServices.titleService().displayPrefix(player.getUUID());
        if (prefix == null) {
            return;
        }
        Component base = event.getDisplayName() != null
                ? event.getDisplayName()
                : PlayerTeam.formatNameForTeam(player.getTeam(), player.getName());
        event.setDisplayName(Component.empty().append(prefix).append(base));
    }

    /** 名牌: 观察者开始看到某个玩家时, 把该玩家的称号补发给这名观察者 (StartTracking 不会对自己触发)。 */
    @SubscribeEvent
    public void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getTarget() instanceof ServerPlayer subject)
                || !(event.getEntity() instanceof ServerPlayer watcher)
                || !TitleServices.isRegistered()) {
            return;
        }
        Component badge = TitleServices.titleService().displayBadge(subject.getUUID());
        if (badge != null) {
            TitleNetwork.sendTo(watcher, subject, badge);
        }
    }

    /** 换维度: 客户端整个世界换新、缓存已清, 自己的称号要重发一次 (他人的由新维度里的 StartTracking 补)。 */
    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        sendOwnTitle(event.getEntity());
    }

    /**
     * 重生 (含从末地返回): PlayerList.respawn 换了一个新的 ServerPlayer (entityId 沿用旧值), 客户端也重建本地玩家
     * 实体、摘掉了旧的缓存条目, 自己的称号要重发。
     *
     * <p>新实体的 Tab 名字段是 null 且未初始化, 而各客户端 Tab 里显示的仍是旧实体广播过的带称号名字; 若不在这里
     * 重算一次, 之后卸下称号时 refreshTabListName 比较 null 与 null 认为没变, 不会广播, 旧称号就会一直挂在所有人的
     * Tab 里。这里的重算把字段对齐为当前应有的名字 (有称号时顺带补发一次, 没有时不发包)。
     */
    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && TitleServices.isRegistered()) {
            player.refreshTabListName();
        }
        sendOwnTitle(event.getEntity());
    }

    private static void sendOwnTitle(Player entity) {
        if (!(entity instanceof ServerPlayer player) || !TitleServices.isRegistered()) {
            return;
        }
        Component badge = TitleServices.titleService().displayBadge(player.getUUID());
        if (badge != null) {
            TitleNetwork.sendTo(player, player, badge);
        }
    }

    /** 当前注入的门面实现; 未注入 (开服前 / 停服后) 或注入的不是本模块实现时为 null, 生命周期钩子据此跳过。 */
    @Nullable
    private static TitleService boundService() {
        return TitleServices.isRegistered() && TitleServices.titleService() instanceof TitleService service
                ? service
                : null;
    }
}
