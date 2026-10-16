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
 * 腐烂底座 (wok-freshness) 的装配入口, 排在 {@code JobFrameworkSystem} 之后 (规格「模块落位」)。
 *
 * 本切片只落**时钟与配置**: 每小时裁决挂钟推进、每 5 分钟写心跳、开服按心跳补停服时长。
 * 观察点 (掉落修改器、玩家扫描、白名单容器、漏斗、加工出品、进食)、终态「腐烂物」、类别标签自检、
 * 同种合并与粮仓在后续切片落地 —— 那些都挂在本模块自己的 mixin 配置上 (miningdim.freshness.mixins.json),
 * 不碰 miningdim.mixins.json, 也不新增 D036 债务。
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
        forgeBus.addListener(FreshnessCommands::register);
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

    @Override
    public String name() {
        return MODULE_ID;
    }

    private void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        FreshnessClockData data = FreshnessClockData.get(server.overworld());
        long wallHour = FreshnessClock.currentWallHour();
        long compensated = data.onServerStarted(wallHour);
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

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null) {
            return;
        }
        long wallHour = FreshnessClock.currentWallHour();
        long wallMinute = FreshnessClock.currentWallMinute();
        FreshnessClockData data = FreshnessClockData.get(server.overworld());
        data.touchHeartbeat(wallHour, wallMinute);
        if (wallHour == lastObservedHour) {
            return;
        }
        long compensated = data.observeHour(wallHour);
        lastObservedHour = wallHour;
        // 跨整点: 把这一小时攒下的盖章/本该腐烂/真换掉的计数写进统计环 (保留 30 天)。
        data.flushHourStats(wallHour);
        if (compensated > 0L) {
            // 进程挂起或挂钟跳变: 按停服补偿计入偏移, 不冻结, 通知 OP (规格「时钟」)。
            LOGGER.warn("[miningdim] freshness clock: 挂钟一次推进 {} 小时, 其中 {} 小时按停服补偿计入偏移"
                            + " (累计 {} 小时); 如属管理员手误多补, 可用 /freshness clock 核对",
                    compensated + 1L, compensated, data.clock().offsetHours());
        }
    }
}
