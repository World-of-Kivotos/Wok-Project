package com.miningdim.quest;

import com.miningdim.core.Subsystem;
import net.minecraftforge.event.RegisterCommandsEvent;
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

/**
 * 任务子系统入口 (模块化铁律 3): 配置、事件钩子、命令树、服务生命周期。
 *
 * 注册顺序上必须排在经济子系统之后 —— 发奖与收重摇费都经 {@code EconomyServices} 门面。不过这只是<b>运行期</b>
 * 依赖 (玩家领奖时才触达), 服务本身在 ServerStarting 绑定时不碰货币层, 因此对 {@code MiningDim} 里的列表
 * 顺序不敏感, 与 {@code CaseOpeningSystem} 的耦合方式一致。
 */
public final class QuestSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/quest");

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, QuestConfig.SPEC, "miningdim-quest.toml");
        forgeBus.register(this);
        forgeBus.register(new QuestEventHooks());
        QuestWebUiActions.registerAll();
        // TaCZ 是 compileOnly 的可选依赖: 只有 Forge 报告它已加载时才注册边界层, 否则本进程永不 classload
        // 任何 com.tacz.* 类 (没装枪械 mod 的服务器照常起服)。
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            if (ModList.get().isLoaded("tacz")) {
                QuestTaczHooks.register(forgeBus);
                LOGGER.info("[miningdim] quest TaCZ boundary registered (gun kill facts, marksman chain unlock)");
            }
        }));
        LOGGER.info("[miningdim] quest subsystem registered");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        QuestPool pool = QuestPool.builtin();
        QuestServices.register(new QuestService(pool));
        LOGGER.info("[miningdim] quest pool bound ({} daily / {} weekly / {} special / {} chains)",
                pool.bySource(QuestSource.DAILY).size(),
                pool.bySource(QuestSource.WEEKLY).size(),
                pool.bySource(QuestSource.SPECIAL).size(),
                pool.chains().size());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        QuestCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // 解绑而不是留着: 单机连开两个世界时, 残留的服务会让第二个世界读到上一个世界的池子实例。
        QuestServices.reset();
        // 在途矿洞行程是进程内瞬时状态, 跨存档留着只会让下一个世界读到假行程。
        QuestMiningVisits.reset();
        // 近期放置表同理: 坐标只对当前存档有意义。
        QuestPlacedBlocks.reset();
    }
}
