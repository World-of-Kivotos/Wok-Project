package com.miningdim.error;

import com.miningdim.core.MiningConstants;
import com.miningdim.core.Subsystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * error 子系统入口 (模块化铁律 3)。error 包本质是一组无状态静态 helper ({@link MiningErrors} /
 * {@link MiningMessages}), 不持有运行态服务, 故不向 {@link com.miningdim.core.MiningServices} 注入门面
 * —— core 也未定义 error 门面接口, 本子系统不擅自向 core 加接口 (任务约束: 不改 core 包)。
 *
 * 本入口仅做一件有副作用的事: 在 {@link ServerStartedEvent} 校验矿山维度是否成功加载, 缺失则
 * 启动期即记 ERROR (对应 20.2 "传送目标维度未加载"为不可恢复配置错误, 越早暴露越好), 让运维在
 * 玩家尝试进入前就发现数据包问题。其余兜底能力由各子系统在最外层直接调用 {@link MiningErrors} 静态法。
 */
public final class ErrorSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/error");

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 维度校验是服务端启动事件, 走 forge 总线。
        forgeBus.register(this);
    }

    /**
     * 启动期维度自检 (20.2 前置暴露)。维度走数据包注册 (C1), 若 JSON 缺失/损坏则 getLevel 返回 null,
     * 此时不崩服 (维度缺失是配置错误), 仅记 ERROR 供运维排查; 玩家级提示在其尝试进入时由进入 Gateway
     * 调用 {@link MiningErrors#miningLevelOrReport} 给出。
     */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ServerLevel mining = server.getLevel(MiningConstants.MINING_LEVEL);
        if (mining == null) {
            LOGGER.error("[miningdim] mining dimension '{}' did not load at startup; "
                            + "check data/miningdim/dimension/mining.json and dimension_type/mining.json",
                    MiningConstants.MINING_LEVEL.location());
        } else {
            LOGGER.info("[miningdim] mining dimension loaded: {}", MiningConstants.MINING_LEVEL.location());
        }
    }
}
