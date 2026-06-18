package com.miningdim.job.chef;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 调味台小游戏输入 C2S (Chef_Job_DesignSpec 第四章; 服务端权威, 客户端只发意图)。
 *
 * 包只携带 "做了哪个动作" 的枚举, 不携带任何热度/命中数/品质 (服务端按自己的权威状态结算, 防作弊):
 *  - START: 开始做菜 (校验输入是食物);
 *  - HEAT_CLICK: 火候出锅点击 (服务端按当前 heat 锁定);
 *  - SEASON_HIT: 调味命中点击 (服务端仅当有活跃时机点才计)。
 *
 * 服务端 handler 校验发送者正打开的是调味台菜单 (operator 即开界面者), 委派给 BlockEntity 的服务端方法。
 */
public record SeasoningGameC2S(Action action) {

    /** 小游戏动作 (越界 byte->enum 还原须兜底, 见 decode)。 */
    public enum Action {
        START,
        HEAT_CLICK,
        SEASON_HIT
    }

    public static void encode(SeasoningGameC2S msg, FriendlyByteBuf buf) {
        buf.writeByte(msg.action.ordinal());
    }

    public static SeasoningGameC2S decode(FriendlyByteBuf buf) {
        int ordinal = buf.readByte();
        Action[] all = Action.values();
        // 越界兜底: 非法 ordinal 落回 START (最无害动作; 不构造世界状态, 服务端再校验 phase)。
        Action action = (ordinal >= 0 && ordinal < all.length) ? all[ordinal] : Action.START;
        return new SeasoningGameC2S(action);
    }

    /**
     * 服务端 handler: enqueueWork 切回主线程, 取发送者打开的调味台菜单, 委派对应服务端动作。
     * operator = 当前打开调味台界面的玩家 (谁开界面谁做; BlockEntity 内再校验 operatorUUID 一致)。
     */
    public static void handle(SeasoningGameC2S msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) {
                return;
            }
            AbstractContainerMenu menu = sender.containerMenu;
            if (!(menu instanceof SeasoningMenu seasoningMenu)) {
                return; // 没开调味台界面: 忽略 (防伪造)。
            }
            SeasoningTableBlockEntity be = seasoningMenu.blockEntity();
            switch (msg.action) {
                case START -> be.startCooking(sender);
                case HEAT_CLICK -> be.clickHeat(sender);
                case SEASON_HIT -> be.clickSeason(sender);
            }
        });
        ctx.setPacketHandled(true);
    }
}
