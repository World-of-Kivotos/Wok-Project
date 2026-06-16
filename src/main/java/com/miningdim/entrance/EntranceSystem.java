package com.miningdim.entrance;

import com.miningdim.core.Subsystem;
import com.miningdim.registry.ModBlockEntities;
import com.miningdim.registry.ModCreativeTabs;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * 入口方块子系统入口 (R4)。装配本子系统自己的 DeferredRegister:
 *  - {@link ModBlockEntities}: 入口方块实体类型 (浮空字 + 触发冷却);
 *  - {@link ModCreativeTabs}:  mod 创造物品栏 (入口方块入栏)。
 *
 * 入口方块 ({@code entrance_*}) 本身与其 BlockItem 由基础 registry holder (ModBlocks/ModItems) 在主类
 * 构造期统一注册 (与 mining_portal/fake_ore 同批), 本子系统只补方块实体类型与创造栏这两个 entrance 专属注册表。
 *
 * 不直接 import entry 实现: 入口方块 -> 入场流程经 {@link EntranceHooks} seam, 由 entry 子系统在服务端
 * 启动期反向 bind (见 EntrySystem.onServerStarted)。故本子系统对 entry 零编译依赖, 注入顺序无前置要求。
 */
public final class EntranceSystem implements Subsystem {

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        ModBlockEntities.register(modBus);
        ModCreativeTabs.register(modBus);
    }

    @Override
    public String name() {
        return "EntranceSystem";
    }
}
