package com.miningdim.achievement;

import com.miningdim.achievement.datagen.AchievementAdvancementProvider;
import com.miningdim.achievement.meta.AchievementCatalog;
import com.miningdim.achievement.meta.AchievementConsistency;
import com.miningdim.achievement.meta.AchievementMetaLoader;
import com.miningdim.achievement.meta.AchievementMetas;
import com.miningdim.achievement.meta.ConsistencyReport;
import com.miningdim.achievement.reward.AchievementRewardSystem;
import com.miningdim.achievement.reward.SqliteAchievementRewardRepository;
import com.miningdim.achievement.trigger.AchievementStats;
import com.miningdim.achievement.trigger.AchievementTriggers;
import com.miningdim.achievement.trigger.ChampionKillHooks;
import com.miningdim.achievement.trigger.DailyCounterRepository;
import com.miningdim.achievement.trigger.MiningTripHooks;
import com.miningdim.achievement.trigger.PlayerProgressHooks;
import com.miningdim.achievement.trigger.SqliteDailyCounterRepository;
import com.miningdim.achievement.trigger.TaczGunKillHooks;
import com.miningdim.core.Subsystem;
import com.miningdim.store.MiningStore;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;

/**
 * 成就子系统 (wok-achievement, gameplay): 成就就是原版进度 (Achievement_System_DesignSpec 第一章)。本类负责模块骨架:
 * 七档档位、自定义统计项与触发器的注册、事件钩子的挂载、元数据加载与一致性校验、查询快照、存储边界的绑定, 以及 datagen 入口。
 *
 * <p>生命周期:
 * <ul>
 *   <li>mod 构造期: 注册服务端配置 miningdim-achievement.toml, 挂自定义统计项的 DeferredRegister, 把矿区、精英怪击杀与
 *       玩家层面的事件钩子挂上 Forge 总线; FMLCommonSetup 里 (主线程, 早于任何数据包加载) 把触发器登记进原版触发器表、
 *       为统计项绑定格式器, 装了 TaCZ 时再挂枪械击杀的边界层; GatherDataEvent 挂进度与元数据的生成器。</li>
 *   <li>AddReloadListenerEvent: 挂元数据加载器 (开服与每次 /reload 都整表替换)。</li>
 *   <li>ServerStarting: 在存储子系统已开好的共享连接上绑定奖励与每日计数两个仓库, 清掉昨天及更早的每日计数行。</li>
 *   <li>ServerStarted 与每次 /reload 完成后: 用服务端实际加载的进度与当前元数据重建查询快照并做一致性校验 (4.1)。
 *       放在这两个时刻是因为校验要查称号定义, 而称号门面在 ServerStarting 才注入。</li>
 *   <li>ServerStopping: 清快照与仓库、清元数据, 清钩子的进程内状态 (在途行程、流体生成位置、精英攻击记录);
 *       连接由存储子系统在 ServerStopped 关闭, 此处不碰。</li>
 * </ul>
 */
public final class AchievementSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/achievement");
    private static final String TACZ = "tacz";

    private final AchievementMetas metas = new AchievementMetas();
    private final AchievementMetaLoader loader = new AchievementMetaLoader(metas);

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, AchievementConfig.SPEC,
                "miningdim-achievement.toml");
        AchievementStats.register(modBus);
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            AchievementTriggers.register();
            AchievementStats.bindFormatters();
            // TaCZ 是 compileOnly 的可选依赖: 只有 Forge 报告它已加载时才注册边界层, 否则本进程永不加载 com.tacz.* 类。
            if (ModList.get().isLoaded(TACZ)) {
                TaczGunKillHooks.register(forgeBus);
                LOGGER.info("[miningdim] achievement TaCZ gun kill hooks registered");
            }
        }));
        modBus.addListener((GatherDataEvent event) -> event.getGenerator().addProvider(event.includeServer(),
                new AchievementAdvancementProvider(event.getGenerator().getPackOutput(),
                        event.getExistingFileHelper())));
        AchievementRewardSystem.register(forgeBus);
        forgeBus.register(this);
        forgeBus.register(new MiningTripHooks());
        forgeBus.register(new ChampionKillHooks());
        forgeBus.register(new PlayerProgressHooks());
        LOGGER.info("[miningdim] achievement subsystem registered ({} triggers, {} custom stats)",
                AchievementTriggers.all().size(), AchievementStats.all().size());
    }

    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(loader);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // 成就表在统一库 miningdim.db (MiningSchema V7); 连接由存储子系统在 ServerAboutToStart 开好并完成迁移。
        Connection connection = MiningStore.connection();
        DailyCounterRepository dailyCounters = new SqliteDailyCounterRepository(connection);
        AchievementServices.bindRepositories(new SqliteAchievementRewardRepository(connection), dailyCounters);
        int pruned = dailyCounters.deleteBefore(DailyCounterRepository.today());
        LOGGER.info("[miningdim] achievement repositories bound (pruned {} stale daily counter row(s))", pruned);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        refresh(event.getServer());
    }

    @SubscribeEvent
    public void onDatapackSync(OnDatapackSyncEvent event) {
        // 只处理 /reload (getPlayer()==null): 那时新的进度管理器与元数据都已就位。单个玩家登录时的同步不涉及重载。
        if (event.getPlayer() == null) {
            refresh(event.getPlayerList().getServer());
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        AchievementServices.reset();
        metas.clear();
        MiningTripHooks.reset();
        ChampionKillHooks.reset();
    }

    /** 重建查询快照并做一致性校验, 不一致之处逐条记错误日志 (缺元数据的进度已在快照里按 0 点、无称号处理)。 */
    private void refresh(MinecraftServer server) {
        AchievementCatalog catalog = AchievementCatalog.build(server.getAdvancements().getAllAdvancements(),
                metas.snapshot());
        AchievementServices.installCatalog(catalog);
        ConsistencyReport report = AchievementConsistency.check(server.getAdvancements().getAllAdvancements(),
                catalog.metas(), AchievementConsistency::titleDefined);
        for (String problem : report.problems()) {
            LOGGER.error("[miningdim] achievement consistency: {}", problem);
        }
        LOGGER.info("[miningdim] achievement catalog rebuilt: {} achievement advancement(s), {} meta file(s), "
                        + "{} countable, {} problem(s), {} meta file(s) without a loaded advancement {}",
                report.checked(), metas.size(), catalog.countableIds().size(), report.problems().size(),
                report.metaWithoutAdvancement().size(), report.metaWithoutAdvancement());
    }
}
