package com.miningdim.job.chef;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * 调味台小游戏输入 C2S (Chef_Job_DesignSpec 第四章; 服务端权威, 客户端只发意图)。
 *
 * 包只携带 "做了哪个动作" 的枚举, 不携带任何热度/命中数/品质 (服务端按自己的权威状态结算, 防作弊):
 *  - START + target: 选择目标品质并开始做菜;
 *  - HEAT_PRESS / HEAT_RELEASE: 按住或松开控火;
 *  - SEASON_HIT + target: 仅命中当前服务端生成的随机位置才计分。
 *
 * 服务端 handler 校验发送者正打开的是调味台菜单 (operator 即开界面者), 委派给 BlockEntity 的服务端方法。
 */
public record SeasoningGameC2S(Action action, int target) {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/chef");

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
            // 下面两条拒绝分支的触发权完全在客户端手里 (改包客户端能以每 tick 20 个的速率制造)。故只记 DEBUG
            // 且不回发任何数据包: 记 WARN 会被单个玩家把日志与磁盘 IO 拉爆, 回消息等于让服务端自己放大出站带宽。
            if (msg.action == null) {
                LOGGER.debug("Rejected invalid seasoning packet from {}", sender.getGameProfile().getName());
                return;
            }
            AbstractContainerMenu menu = sender.containerMenu;
            if (!(menu instanceof SeasoningMenu seasoningMenu)) {
                LOGGER.debug("Rejected seasoning action {} from {} without seasoning menu", msg.action,
                        sender.getGameProfile().getName());
                return; // 没开调味台界面: 忽略 (防伪造)。
            }
            SeasoningTableBlockEntity be = seasoningMenu.blockEntity();
            if (!seasoningMenu.stillValid(sender)) {
                be.reject(sender, msg.action.name(), "距离过远或调味台已不存在");
                return;
            }
            switch (msg.action) {
                case START -> be.startCooking(sender, msg.target);
                case HEAT_PRESS -> be.pressHeat(sender);
                case HEAT_RELEASE -> be.releaseHeat(sender);
                case SEASON_HIT -> be.hitSeason(sender, msg.target);
            }
        });
        ctx.setPacketHandled(true);
    }
}
