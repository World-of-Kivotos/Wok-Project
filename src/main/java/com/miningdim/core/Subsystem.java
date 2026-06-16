package com.miningdim.core;

import net.minecraftforge.eventbus.api.IEventBus;

/**
 * 子系统统一入口 (模块化铁律 3)。每个功能子系统提供且仅提供一个实现本接口的入口类
 * (如 WorldgenSystem / InstanceSystem / NetworkSystem ...), 在 register 内完成自己的
 * DeferredRegister / 事件订阅 / 服务注册, 并把自身门面实例注入 MiningServices。
 *
 * MiningDim 主类只持有一个 List<Subsystem> 逐个 register —— 增删功能 = 改主类一行 List 元素。
 * 严禁子系统之间硬编码 import 对方实现类; 跨子系统协作只经 core 门面 + MiningServices (铁律 2)。
 */
public interface Subsystem {

    /**
     * 注册入口。在 mod 构造期由主类调用。
     *
     * @param modBus   mod 事件总线 (FMLJavaModLoadingContext.get().getModEventBus()):
     *                 DeferredRegister、RegisterEvent、FMLCommonSetupEvent、RegisterCapabilitiesEvent 等
     * @param forgeBus forge 事件总线 (MinecraftForge.EVENT_BUS):
     *                 ServerStartingEvent、RegisterCommandsEvent、PlayerEvent、tick 事件等
     */
    void register(IEventBus modBus, IEventBus forgeBus);

    /** 子系统名 (日志/诊断用)。默认取实现类简单名。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
