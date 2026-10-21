package com.miningdim.social;

import com.miningdim.core.Subsystem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * 社交平板服务端子系统：加载/落盘社交存档，注册 WebUI action，维护登录档案。
 * 本类不引用任何客户端/MCEF 类，专用服务器可无条件加载。
 */
public final class SocialSystem implements Subsystem {

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        SocialActions.register();
        // 图鉴里程碑 → 平板主题解锁（社交侧注册监听；塔罗侧不认识社交模块）。
        SocialCodexMilestones.register();
        forgeBus.addListener(this::onServerStarted);
        forgeBus.addListener(this::onServerStopping);
        forgeBus.addListener(this::onLogin);
        forgeBus.addListener(this::onLogout);
    }

    private void onServerStarted(ServerStartedEvent event) {
        SocialService.load(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        SocialService.stop();
    }

    private void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && SocialService.isLoaded()) {
            SocialService.store().touch(player.getUUID(), player.getName().getString());
        }
    }

    private void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && SocialService.isLoaded()) {
            SocialService.store().touch(player.getUUID(), player.getName().getString());
        }
    }
}
