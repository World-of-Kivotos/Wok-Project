package com.miningdim.caseopening;

import com.miningdim.caseopening.store.CaseDaoSqlite;
import com.miningdim.caseopening.store.CaseDb;
import com.miningdim.core.Subsystem;
import com.miningdim.store.MiningStore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns case config, SQLite lifecycle, WebUI actions, Saga login recovery and held-gun ownership enforcement. */
public final class CaseOpeningSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/caseopening");

    private CaseDaoSqlite dao;

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        CaseSounds.register(modBus);
        CaseTaczResourceBootstrap.registerExportPack();
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER,
                CaseOpeningConfig.SPEC, "miningdim-case-opening.toml");
        CaseWebUiActions.registerAll();
        forgeBus.register(this);
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            if (ModList.get().isLoaded("tacz")) {
                CaseTaczEventHooks.register(forgeBus);
            }
        }));
        LOGGER.info("[miningdim] case opening subsystem registered (17 skins, case.* actions, SQLite Saga)");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // 开箱表已并入统一库 miningdim.db; 连接由存储子系统在 ServerAboutToStart 开好并完成 schema 迁移。
        this.dao = CaseDb.on(MiningStore.connection());
        CaseOpeningService service = new CaseOpeningService(
                this.dao,
                new EconomyCaseOperations(),
                new CaseRoller(),
                CaseOpeningConfig.ENABLED::get,
                () -> ModList.get().isLoaded("tacz"),
                CaseTaczResourceBootstrap::isRegistered,
                CaseOpeningConfig.CREDIT_COST::get,
                CaseOpeningConfig.AZURE_COST::get,
                CaseOpeningConfig::weights,
                CaseOpeningConfig.OPEN_COOLDOWN_TICKS::get);
        // Fail startup on invalid probability totals instead of discovering a bad table after charging a player.
        service.weights();
        CaseServices.register(service);
        LOGGER.info("[miningdim] case ledger bound ({} CREDIT + {} AZURE per open)",
                service.creditCost(), service.azureCost());
    }

    /**
     * 启动期全量对账。
     *
     * 登录驱动的恢复捞不到从此不再上线的玩家 —— 他们的未完成开箱行会永久悬挂。挂 ServerStarted 而非
     * ServerStarting 是因为货币门面要到 EconomySystem 的 ServerStarted 才注入; 用 LOW 优先级显式表达
     * "晚于经济子系统", 而不是依赖子系统注册顺序这种隐式约定。
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onServerStarted(ServerStartedEvent event) {
        if (!CaseServices.isRegistered()) {
            return;
        }
        int handled = CaseServices.service().reconcileAtStartup();
        if (handled > 0) {
            LOGGER.info("[miningdim] 启动期对账处置了 {} 条未完成的开箱事务", handled);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !CaseServices.isRegistered()) {
            return;
        }
        try {
            int recovered = CaseServices.service().recoverFor(player);
            if (recovered > 0) {
                LOGGER.info("[miningdim] recovered {} interrupted case opening(s) for {}",
                        recovered, player.getGameProfile().getName());
            }
            CaseServices.service().enforceMainHand(player);
        } catch (RuntimeException exception) {
            // Login is a lifecycle recovery boundary: retain the durable Saga for the next retry and preserve the stack trace.
            LOGGER.error("[miningdim] failed to recover case openings for {}",
                    player.getGameProfile().getName(), exception);
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
                || !(event.player instanceof ServerPlayer player)
                || !CaseServices.isRegistered()
                || player.tickCount % CaseOpeningConfig.ENFORCE_INTERVAL_TICKS.get() != 0) {
            return;
        }
        CaseServices.service().enforceMainHand(player);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // 连接是全服共享的, 由存储子系统在 ServerStopped 关闭; 此处只丢引用。
        CaseServices.reset();
        this.dao = null;
    }
}
