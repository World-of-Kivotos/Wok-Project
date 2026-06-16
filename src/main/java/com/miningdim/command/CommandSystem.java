package com.miningdim.command;

import com.miningdim.core.Subsystem;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * 命令子系统入口 (模块化铁律 3)。命令不是门面服务, 故本子系统不向 MiningServices 注入任何实现,
 * 只在 forge 事件总线订阅 RegisterCommandsEvent (17.1), 在回调内构建 /mining 命令树。
 *
 * 命令执行期 (RegisterCommandsEvent 之后, 玩家敲命令时) 才经 MiningServices 取用 instanceManager/
 * resetService/network/config —— 那时各门面早已注入完毕, 故命令子系统在主类 List 中的相对顺序不敏感。
 */
public final class CommandSystem implements Subsystem {

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        forgeBus.addListener(this::onRegisterCommands);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        MiningCommands.register(event.getDispatcher());
    }
}
