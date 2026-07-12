package com.miningdim.job.munitions;

import com.miningdim.core.Subsystem;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.munitions.block.MunitionsBenchBlock;
import com.miningdim.job.munitions.client.GunsmithPressScreen;
import com.miningdim.job.munitions.client.MunitionsBenchScreen;
import com.miningdim.job.munitions.gunsmith.GunsmithGunStats;
import com.miningdim.job.munitions.gunsmith.GunsmithGunTooltip;
import com.miningdim.job.munitions.gunsmith.GunsmithTaczResourceBootstrap;
import com.miningdim.job.munitions.gunsmith.GunsmithTaczStatsHandler;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 军火商子系统入口 (Munitions_Job_DesignSpec 十章; 模块化铁律 3)。在 register 内完成自己的全部 DeferredRegister
 * (Block/Item/BlockEntity/MenuType/CreativeTab) + SERVER 配置 spec + forgeBus 放置/破坏门控订阅 + 客户端 Screen
 * 注册; 跨子系统协作只经职业框架门面 (JobServices) + 货币门面 (EconomyServices), 不 import 他系统实现类。
 *
 * 集成阶段: 在 {@code MiningDim.registerSubsystems()} 追加 new MunitionsSystem() (本任务实现阶段单代理改一次)。
 * 军火商等级/经验数据走共享职业框架 capability (JobProgress, JobId.MUNITIONS), 不新挂 capability。
 *
 * 配置: 军火商自带 SERVER spec (miningdim-munitions.toml), 在此经 ModLoadingContext.registerConfig 注册。
 *
 * 放置门控 (5/10.5 台数上限): forgeBus EntityPlaceEvent 校验军火商等级对应的台数上限 (6.1), 超限取消放置 +
 * actionbar 提示; BreakEvent 回收台数计数。计数走 {@link MunitionsSavedData} (overworld 持久层, 按 ownerUUID 全局)。
 */
public final class MunitionsSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/munitions");

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        GunsmithTaczResourceBootstrap.registerExportPack();

        // 自有 DeferredRegister (顺序: Block -> BlockEntity (依赖 Block) -> Item (依赖 Block) -> Menu -> Tab)。
        ModMunitionsBlocks.register(modBus);
        ModMunitionsBlockEntities.register(modBus);
        ModMunitionsItems.register(modBus);
        ModMunitionsSounds.register(modBus);
        ModMunitionsMenus.register(modBus);
        ModMunitionsTab.register(modBus);

        // SERVER 配置 spec (C6: 全部平衡数值进 ForgeConfigSpec)。
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER,
                MunitionsConfig.SPEC, "miningdim-munitions.toml");

        // forgeBus: 军火台放置上限门控 + 破坏回收计数。
        forgeBus.register(this);
        modBus.addListener((FMLCommonSetupEvent event) ->
                event.enqueueWork(() -> {
                    if (MunitionsAmmoFactory.isTaczLoaded()) {
                        GunsmithTaczStatsHandler.register(forgeBus);
                    }
                }));

        // 客户端 Screen 注册 (FMLClientSetupEvent.enqueueWork; 经 DistExecutor 隔离, 防专用服务器触链)。
        modBus.addListener((FMLClientSetupEvent event) ->
                event.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> {
                            MenuScreens.register(ModMunitionsMenus.MUNITIONS_BENCH.get(), MunitionsBenchScreen::new);
                            MenuScreens.register(ModMunitionsMenus.GUNSMITH_PRESS.get(), GunsmithPressScreen::new);
                        })));

        LOGGER.info("[miningdim] munitions subsystem registered (munitions bench + passive ammo production)");
    }

    /**
     * 放置军火台时校验台数上限 (6.1: 等级对应的最大拥有台数), 超限取消放置。通过则计数 +1 (按玩家 UUID 全局计)。
     * 仅服务端玩家放置受闸门约束 (活塞/掉落方块等非玩家放置不计也不限)。
     */
    @SubscribeEvent
    public void onBenchPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!(event.getPlacedBlock().getBlock() instanceof MunitionsBenchBlock)
                || !MunitionsBenchBlock.isMain(event.getPlacedBlock())) {
            return;
        }
        ServerLevel overworld = player.server.overworld();
        MunitionsSavedData data = MunitionsSavedData.get(overworld);
        int level = JobServices.jobService().level(player, JobId.MUNITIONS);
        int cap = MunitionsLevels.tableCount(level);
        int placed = data.benchCount(player.getUUID());

        if (placed >= cap) {
            event.setCanceled(true);
            player.displayClientMessage(
                    Component.translatable("message.miningdim.munitions.cap_reached", cap), true);
            return;
        }
        data.increment(player.getUUID());
    }

    /** 破坏军火台时回收台数计数 (-1)。仅服务端玩家破坏计入回收 (与放置侧对称)。 */
    @SubscribeEvent
    public void onBenchBroken(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (!(event.getState().getBlock() instanceof MunitionsBenchBlock)) {
            return;
        }
        ServerLevel overworld = player.server.overworld();
        MunitionsSavedData.get(overworld).decrement(player.getUUID());
    }

    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent event) {
        GunsmithGunStats stats = GunsmithGunStats.from(event.getItemStack());
        if (stats == null) {
            return;
        }
        GunsmithGunTooltip.append(event.getToolTip(), stats);
    }

    @Override
    public String name() {
        return "MunitionsSystem";
    }
}
