package com.miningdim.store;

import com.miningdim.core.Subsystem;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一 SQLite 存储的生命周期编排。
 *
 * 为什么挂 {@link ServerAboutToStartEvent} 而不是和其他子系统一样挂 ServerStartingEvent: 市场与开箱都在
 * ServerStartingEvent 里取连接建 DAO, 同一事件内多个监听器的先后取决于注册顺序, 那是脆弱的隐式依赖。
 * AboutToStart 严格早于 Starting (二者分别在 DedicatedServer.initServer 的两处调用, IntegratedServer 同),
 * 用事件相位表达顺序, 与注册顺序无关。
 *
 * 关闭同理挂 {@link ServerStoppedEvent}: 它在 MinecraftServer 的 finally 里、stopServer() 之后触发, 严格晚于
 * 全部 ServerStoppingEvent 监听器, 保证没有任何子系统还会用这条连接。
 */
public final class MiningStoreSubsystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/store");

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        forgeBus.register(this);
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        // 失败自然冒泡: 库打不开或旧库导入有歧义时, 宁可服务端起不来, 也不能带着结构不完整的库开服。
        MiningStore.open(event.getServer());
        LOGGER.info("[miningdim] 统一 SQLite 已就绪: {}", MiningDb.DB_FILE_NAME);
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        MiningStore.close();
    }

    @Override
    public String name() {
        return "MiningStoreSubsystem";
    }
}
