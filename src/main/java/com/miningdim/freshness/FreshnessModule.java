package com.miningdim.freshness;

import com.miningdim.core.Subsystem;
import com.miningdim.freshness.item.FreshnessItems;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 腐烂底座 (wok-freshness) 的装配入口。
 *
 * 本模块装配腐烂时钟、物品数据与类别、观察点、迁移、合并、命令和客户端同步；原版目标由本模块的 mixin
 * 配置处理，可选的 FD 兼容则由独立兼容配置处理。
 */
public final class FreshnessModule implements Subsystem {

    public static final String MODULE_ID = FreshnessConstants.MODULE_ID;

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/freshness");

    private MinecraftServer server;
    private long lastObservedHour = FreshnessClock.UNSET_HOUR;

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER,
                FreshnessConfig.SPEC, "miningdim-freshness.toml");
        net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> com.miningdim.freshness.client.FreshnessClientSetup.registerConfig());
        FreshnessItems.register(modBus);
        FreshnessLootModifiers.register(modBus);
        modBus.addListener(this::onBuildTabContents);
        forgeBus.addListener(this::onServerStarted);
        forgeBus.addListener(this::onServerTick);
        // 观察点里不需要 mixin 的那一批 (掉落物、玩家扫描、白名单容器、右键与进食之前、登录)。
        forgeBus.addListener(FreshnessObservation::onPlayerTick);
        forgeBus.addListener(FreshnessObservation::onPlayerLoggedIn);
        forgeBus.addListener(FreshnessObservation::onContainerOpen);
        forgeBus.addListener(FreshnessObservation::onContainerClose);
        forgeBus.addListener(FreshnessObservation::onEntityJoinLevel);
        forgeBus.addListener(FreshnessObservation::onRightClickBlock);
        forgeBus.addListener(FreshnessObservation::onRightClickItem);
        forgeBus.addListener(FreshnessObservation::onEntityInteract);
        // 同种食物合并的「点击叠放」入口 (拾取与配方书两条走本模块的 mixin)。
        forgeBus.addListener(FreshnessMergeHooks::onStackedOnOther);
        // 放置食物记录: 摆下去记一笔、跟着区块存读、掉落时继承 (区块附加数据)。
        forgeBus.addListener(FreshnessPlacedFoodHooks::onBlockPlaced);
        forgeBus.addListener(FreshnessPlacedFoodHooks::onChunkLoad);
        forgeBus.addListener(FreshnessPlacedFoodHooks::onChunkSave);
        forgeBus.addListener(FreshnessPlacedFoodHooks::onChunkUnload);
        // 村民货: 报价时打标记、老村民进世界补一次 (到手时按剩 6 小时盖章在结算那一条路上)。
        forgeBus.addListener(FreshnessVillagerGoods::onVillagerTrades);
        forgeBus.addListener(FreshnessVillagerGoods::onWandererTrades);
        forgeBus.addListener(FreshnessVillagerGoods::onEntityJoinLevel);
        // 开服迁移: 定启用时刻与老玩家名单、区块存档里的老货、实体内容、老玩家第一次登录 (规格「开服迁移」)。
        // 四个入口都是 HIGHEST 优先级: 默认优先级那一批观察点会把没章的东西当场盖成"现在", 迁移必须抢在它们之前。
        forgeBus.addListener(net.minecraftforge.eventbus.api.EventPriority.HIGHEST, FreshnessMigrationHooks::onServerStarted);
        forgeBus.addListener(net.minecraftforge.eventbus.api.EventPriority.HIGHEST, FreshnessMigrationHooks::onChunkLoad);
        forgeBus.addListener(net.minecraftforge.eventbus.api.EventPriority.HIGHEST, FreshnessMigrationHooks::onEntityJoinLevel);
        forgeBus.addListener(net.minecraftforge.eventbus.api.EventPriority.HIGHEST, FreshnessMigrationHooks::onPlayerLoggedIn);

        // 配方过滤: 压缩块拆回 (规则 1) 在每次数据包 reload 的 apply 阶段扫一遍合成配方。
        // Shift 快移合并: 把合并函数挂到核心的接缝上 (主 MOD 那两处重写过的 moveItemStackTo 从那里回调)。
        com.miningdim.menu.ShiftMergeHooks.register(FreshnessMergeHooks::mergeShiftMove);
        forgeBus.addListener(FreshnessRecipeFilterHooks::onAddReloadListener);
        forgeBus.addListener(FreshnessRecipeFilterHooks::onServerStarted);
        forgeBus.addListener(FreshnessCommands::register);
        // 客户端显示: channel 在公共装配窗口注册; 客户端那一半只在客户端调 (DistExecutor 隔离)。
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onClientSetup);
        forgeBus.addListener(this::onPlayerLoggedIn);
        forgeBus.addListener(this::onServerStopped);
        forgeBus.addListener(FreshnessMigrationHooks::onChunkSave);
        forgeBus.addListener(FreshnessMigrationHooks::onNewChunk);
        forgeBus.addListener(net.minecraftforge.eventbus.api.EventPriority.HIGHEST, FreshnessMigrationHooks::onLevelLoad);
        LOGGER.info("[miningdim] {} registered (clock + item data + categories + rot item + 观察点; mixin 那批与粮仓在后续切片)",
                MODULE_ID);
    }

    /**
     * 腐烂物进原版「材料」页。本模块不碰中央创造栏 (那归核心模块), 只往原版页签追加自己的物品 ——
     * 这是往别人的页签塞东西时的标准做法, 不产生模块间依赖。
     */
    private void onBuildTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.INGREDIENTS)) {
            event.accept(FreshnessItems.ROT.get());
        }
    }

    /** 公共装配: 注册 network channel (线程安全窗口, 与厨师那个 channel 同一范式)。 */
    private void onCommonSetup(net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent event) {
        event.enqueueWork(FreshnessNetwork::register);
    }

    /** 客户端装配 (只在客户端跑; 整包 client 类引用集中在 FreshnessClientSetup 里)。 */
    private void onClientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        event.enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> com.miningdim.freshness.client.FreshnessClientSetup.register()));
    }

    /** 玩家登录: 立刻推一次钟与保质期 (客户端一进来就有正确的数)。 */
    private void onPlayerLoggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            FreshnessSync.sendTo(player);
        }
    }

    @Override
    public String name() {
        return MODULE_ID;
    }

    private void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        FreshnessClockData data = FreshnessClockData.get(server.overworld());
        long wallHour = FreshnessClock.currentWallHour();
        long compensated = data.onServerStarted(wallHour, FreshnessClock.currentWallMinute());
        lastObservedHour = wallHour;
        if (compensated > 0L) {
            LOGGER.info("[miningdim] freshness clock: 停服/停机 {} 小时按整小时补回 (累计偏移 {} 小时)",
                    compensated, data.clock().offsetHours());
        }
        LOGGER.info("[miningdim] freshness clock: mode={}, 现在分钟序号={}, 累计偏移 {} 小时",
                FreshnessConfig.MODE.get(), data.nowMinute(), data.clock().offsetHours());
        // 标签自检 (安全阀): 有一条不对就自动退回试运行, 见 FreshnessSelfCheck。
        FreshnessSelfCheck.runOnServerStart(server);
    }

    private void onServerStopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        FreshnessPlacedFood.resetForTest();
        FreshnessMigrationHooks.onServerStopped(event);
        server = null;
        lastObservedHour = FreshnessClock.UNSET_HOUR;
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null) {
            return;
        }
        long wallHour = FreshnessClock.currentWallHour();
        long wallMinute = FreshnessClock.currentWallMinute();
        FreshnessClockData data = FreshnessClockData.get(server.overworld());
        // 客户端显示的定时校准 (每 30 秒): 客户端两次同步之间靠自己流逝的时间往前推。
        if (FreshnessSync.isSyncTick(server.getTickCount())) {
            FreshnessSync.broadcast(server);
        }
        data.touchHeartbeat(wallHour, wallMinute);
        long compensated = data.drainRuntimeCompensationHours();
        if (wallHour != lastObservedHour) {
            lastObservedHour = wallHour;
            data.flushHourStats(wallHour);
        }
        if (compensated > 0L) {
            // 进程挂起或挂钟跳变: 按停服补偿计入偏移, 不冻结, 通知 OP (规格「时钟」)。
            LOGGER.warn("[miningdim] freshness clock: 挂钟一次推进 {} 小时, 其中 {} 小时按停服补偿计入偏移"
                            + " (累计 {} 小时); 如属管理员手误多补, 可用 /freshness clock 核对",
                    compensated + 1L, compensated, data.clock().offsetHours());
        }
    }
}
