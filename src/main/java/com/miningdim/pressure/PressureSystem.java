package com.miningdim.pressure;

import com.miningdim.core.Subsystem;
import com.miningdim.trap.TrapSystem;
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
 *  - 陷阱 danger 门控 (9.6): 陷阱子系统以"推依赖"方式被注入读 danger 能力。陷阱包已提供稳定注入入口
 *    {@link TrapSystem#setDangerSource}, 故本 register 末尾把 "从 mobPressure.danger() 读玩家 danger" 的
 *    适配器注入进去 (从 stub 的 (p,i)->0f 复活动态陷阱: 岩浆/坍塌/身后苦力怕三类 danger 门控陷阱)。
 *    pressure 单向 import trap (推依赖), trap 不反向 import pressure, 不破坏 "陷阱包不知道压力包存在" 的隔离。
 *    注入顺序前提: TrapSystem 在主类 List 中排在 PressureSystem 之前, 故 register 期 TrapSystem.get() 已就绪。
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
        // 注入 danger 读取适配器: 复活动态陷阱 (C3)。陷阱引擎据此 danger 门控岩浆/坍塌/身后苦力怕,
        // 不再恒读 stub 的 0f。无该玩家压力态 (未进矿洞 / 已离开) 时返回 0f, 与 stub 退化语义一致。
        TrapSystem.get().setDangerSource((player, instanceId) -> {
            PlayerMiningData data = mobPressure.danger().get(player.getUUID());
            return data == null ? 0.0f : data.danger();
        });
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
