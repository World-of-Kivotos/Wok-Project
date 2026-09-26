package com.miningdim.achievement.trigger;

import com.tacz.guns.api.event.common.EntityKillByGunEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;

/**
 * TaCZ 边界层: 把 {@code EntityKillByGunEvent} 翻译成 {@link GunKillHooks#onGunKill}。
 *
 * <b>只在 Forge 报告 TaCZ 已加载时才注册</b> (成就子系统在 FMLCommonSetup 里判), 与任务模块的 QuestTaczHooks 同范式;
 * 本模块其余代码都不含 {@code com.tacz.*} 引用, 所以没装 TaCZ 的服务器完全不会加载本类。
 */
public final class TaczGunKillHooks {

    private TaczGunKillHooks() {
    }

    public static void register(IEventBus forgeBus) {
        forgeBus.register(new TaczGunKillHooks());
    }

    @SubscribeEvent
    public void onKillByGun(EntityKillByGunEvent event) {
        // 事件在两个逻辑端都发, 只在服务端计, 否则单机会双倍计数。
        if (event.getLogicalSide() != LogicalSide.SERVER || !(event.getAttacker() instanceof ServerPlayer shooter)) {
            return;
        }
        LivingEntity victim = event.getKilledEntity();
        if (victim != null) {
            GunKillHooks.onGunKill(shooter, victim, event.isHeadShot(), KillFilter::isAfkFrozen);
        }
    }
}
