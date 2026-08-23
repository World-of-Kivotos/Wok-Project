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
 *  - HEAT_PRESS / HEAT_RELEASE: 按住或松开控火;
 *  - SEASON_HIT + target: 仅命中当前服务端生成的随机位置才计分。
 *
 * 服务端 handler 校验发送者正打开的是调味台菜单 (operator 即开界面者), 委派给 BlockEntity 的服务端方法。
 */
public record SeasoningGameC2S(Action action, int target) {

    /** 小游戏动作 (越界 byte->enum 还原须兜底, 见 decode)。 */
    public enum Action {
        START,
        HEAT_PRESS,
        HEAT_RELEASE,
        SEASON_HIT
    }

    public SeasoningGameC2S(Action action) {
        this(action, -1);
    }

    public static void encode(SeasoningGameC2S msg, FriendlyByteBuf buf) {
        buf.writeByte(msg.action.ordinal());
        buf.writeVarInt(msg.target);
    }

    public static SeasoningGameC2S decode(FriendlyByteBuf buf) {
        int ordinal = buf.readByte();
        Action[] all = Action.values();
        Action action = (ordinal >= 0 && ordinal < all.length) ? all[ordinal] : null;
        return new SeasoningGameC2S(action, buf.readVarInt());
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
            if (msg.action == null) {
                org.slf4j.LoggerFactory.getLogger("miningdim/chef").warn(
                        "Rejected invalid seasoning packet from {}", sender.getGameProfile().getName());
                sender.displayClientMessage(net.minecraft.network.chat.Component.literal("调味操作被拒绝：无效动作。"), true);
                return;
            }
            AbstractContainerMenu menu = sender.containerMenu;
            if (!(menu instanceof SeasoningMenu seasoningMenu)) {
                org.slf4j.LoggerFactory.getLogger("miningdim/chef").warn(
                        "Rejected seasoning action {} from {} without seasoning menu", msg.action,
                        sender.getGameProfile().getName());
                sender.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "调味操作被拒绝：未打开调味台。"), true);
                return; // 没开调味台界面: 忽略 (防伪造)。
            }
            SeasoningTableBlockEntity be = seasoningMenu.blockEntity();
            if (!seasoningMenu.stillValid(sender)) {
                be.reject(sender, msg.action.name(), "距离过远或调味台已不存在");
                return;
            }
            switch (msg.action) {
                case START -> be.startCooking(sender);
                case HEAT_PRESS -> be.pressHeat(sender);
                case HEAT_RELEASE -> be.releaseHeat(sender);
                case SEASON_HIT -> be.hitSeason(sender, msg.target);
            }
        });
        ctx.setPacketHandled(true);
    }
}
