package com.miningdim.donation;

import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 捐赠箱在 Forge 总线上的监听: 破坏保护 + 指令注册。
 *
 * 破坏保护挂 HIGH 优先级: 要排在经济、任务、职业这些"方块被挖掉就结算"的监听之前取消事件, 它们默认不接收
 * 已取消的事件, 于是一次被拒绝的破坏不会在别处留下任何副作用。Forge 在事件被取消后会把方块与方块实体数据
 * 重新发给该玩家, 客户端预测的消失会被纠正。
 */
public final class DonationEvents {

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide() || event.getPlayer() == null
                || !(event.getLevel().getBlockEntity(event.getPos()) instanceof DonationBoxBlockEntity box)) {
            return;
        }
        if (!box.canBreak(event.getPlayer())) {
            event.setCanceled(true);
            event.getPlayer().displayClientMessage(
                    Component.translatable("message.miningdim.donation_box.break_denied"), true);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        DonationCommands.register(event.getDispatcher());
    }
}
