package com.miningdim.pressure;

import com.miningdim.core.Subsystem;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * 动态压力子系统入口 (设计文档第十章, implements core.Subsystem)。模块化铁律 3: 唯一入口类,
 * 在 register 内完成本子系统的事件订阅, 不在别处散落注册。
 *
 * 装配 (主类 List<Subsystem> 增删一行): 构造 MobPressureSystem worker 并挂 forge 事件总线
 * (ServerTickEvent 评估/刷怪、LivingDeathEvent 计数回收、玩家离开清压力态)。
 *
 * 对外能力:
 *  - HUD: MobPressureSystem 内部经 MiningServices.network().sendDanger 下发 danger, 无需外部接线。
 *  - 陷阱 danger 门控 (9.6): 陷阱子系统以"推依赖"方式被注入读 danger 能力。本子系统不 import 陷阱包
 *    实现类; 当陷阱子系统提供稳定的公开注入入口后, 在本 register 内追加一行注入适配器 (从
 *    mobPressure.danger() 读玩家 danger) 完成接线。当前不预埋对其的引用, 避免对尚在变动的陷阱包产生
 *    编译期硬依赖 —— 信息缺口: 需要陷阱子系统的稳定 danger 注入门面与其 DangerSource 函数签名。
 *
 * 依赖的 core 服务: MobPressureSystem 在 tick 内经 MiningServices 取 IInstanceManager / IMiningConfig /
 * IMiningNetwork。这些服务由各自子系统在本系统之前注入 (主类 List 顺序保证), 故本 register 只订阅事件,
 * 不在 register 当场取服务 —— 取用推迟到服务端 tick 回调 (服务此时必已就绪)。
 */
public final class PressureSystem implements Subsystem {

    private final MobPressureSystem mobPressure = new MobPressureSystem();

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        forgeBus.register(mobPressure);
    }

    @Override
    public String name() {
        return "PressureSystem";
    }

    /** 暴露 worker (供测试 / 未来陷阱 danger 注入适配器取 danger 读取能力)。 */
    public MobPressureSystem mobPressure() {
        return mobPressure;
    }
}
