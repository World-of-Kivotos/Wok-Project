package com.miningdim.network;

import com.miningdim.core.MiningServices;
import com.miningdim.core.Subsystem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * 网络子系统入口 (模块化铁律 3, IMiningNetwork 提供方)。
 *
 * register 时机分两段:
 *   1) mod 构造期 (本方法体内): 立即把 MiningNetwork 注入 MiningServices, 使后续子系统能按接口取用;
 *      同时订阅 FMLCommonSetupEvent。注入不依赖 channel 是否已注册 packet, 仅是引用绑定。
 *   2) FMLCommonSetupEvent (enqueueWork 内, 15.2 线程安全窗口): 调用 MiningNetwork.register() 完成
 *      SimpleChannel 的逐包 registerMessage —— 该步两端共用同一代码以保证 discriminator id 一致。
 *
 * 注入顺序契约: 若其他子系统在自己的 register 期 (mod 构造) 就要取用 network(), NetworkSystem 须排在其前
 * (见 MiningServices 注释); 多数子系统在事件回调期才用 network, 不受顺序约束。
 */
public final class NetworkSystem implements Subsystem {

    private final MiningNetwork network = new MiningNetwork();

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 构造期注入: 引用绑定先于 packet 注册, 保证依赖方在 register 期可取用门面。
        MiningServices.registerNetwork(network);

        // packet 注册推迟到 setup 的 enqueueWork (主线程安全窗口, 两端同序)。
        modBus.addListener(this::onCommonSetup);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(network::register);
    }
}
