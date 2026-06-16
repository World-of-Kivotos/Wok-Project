package com.miningdim.network;

import com.miningdim.core.Difficulty;
import com.miningdim.core.IMiningNetwork;
import com.miningdim.core.InstanceLimitException;
import com.miningdim.core.MiningServices;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * C2S 进入意图包 (设计文档 15.4.1)。玩家在矿山 GUI 点"进入"时发送。
 *
 * 防作弊 (15.4.1): 包只表达意图, 不携带任何坐标/权限字段; difficulty 之外的世界状态由服务端重算,
 * 出生点由 SpawnSystem 在主连通分量内选取, 客户端无权指定落点。requestedInstanceId = -1 表示新建,
 * 否则尝试加入指定共享/组队实例 (由 InstanceManager 校验归属与容量)。
 */
public record SelectZoneC2S(Difficulty difficulty, boolean partyJoin, long requestedInstanceId) {

    /** 编码 (15.4.1): 仅按序写字段, 无世界访问。 */
    public static void encode(SelectZoneC2S msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.difficulty);
        buf.writeBoolean(msg.partyJoin);
        buf.writeLong(msg.requestedInstanceId);
    }

    /** 解码 (15.4.1): 按序读回构造不可变 record, 无世界访问。 */
    public static SelectZoneC2S decode(FriendlyByteBuf buf) {
        Difficulty difficulty = buf.readEnum(Difficulty.class);
        boolean partyJoin = buf.readBoolean();
        long requestedInstanceId = buf.readLong();
        return new SelectZoneC2S(difficulty, partyJoin, requestedInstanceId);
    }

    /**
     * 服务端 handler (15.4.1/N4): enqueueWork 切回主线程后, 取发送者、委托 InstanceManager.allocate,
     * 并把结果经 TeleportResultS2C 回包。allocate 是异步 (生成可能在进行中), 故用 whenComplete 回调发结果。
     *
     * 异常纪律 (C9): 业务异常自然冒泡, 仅本入口层 (handler) 兜底 —— allocate 返回的 future 的异常
     * (InstanceLimitException = 背压) 在 whenComplete 内转译为对应 TeleportResult 文案下发, 不在业务层吞。
     * 实际传送/落点解析由命令/进入流程子系统在 allocate 完成后执行 (本包只负责把意图转交并回执结果)。
     */
    public static void handle(SelectZoneC2S msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) {
                // 无发送者 (理论不会出现在 PLAY_TO_SERVER): 不构造任何世界状态, 直接放弃。
                return;
            }
            IMiningNetwork network = MiningServices.network();
            MiningServices.instanceManager()
                    .allocate(sender, msg.difficulty)
                    .whenComplete((instance, error) -> sender.server.execute(() -> {
                        if (error != null) {
                            IMiningNetwork.TeleportResult result = classify(error);
                            String reasonKey = reasonKeyFor(error);
                            network.sendTeleportResult(sender, result, -1L, -1, reasonKey);
                            return;
                        }
                        // 分配成功且生成就绪: 由进入流程负责强加载+落点+传送 (14.2), 本包回报状态包供 UI 反馈。
                        network.sendInstanceStatus(sender, instance, 1.0f);
                    }));
        });
        ctx.setPacketHandled(true);
    }

    private static IMiningNetwork.TeleportResult classify(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof InstanceLimitException) {
            return IMiningNetwork.TeleportResult.REJECTED_FULL;
        }
        return IMiningNetwork.TeleportResult.ERROR;
    }

    private static String reasonKeyFor(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof InstanceLimitException limit) {
            return switch (limit.reason()) {
                case GLOBAL_CAP -> "commands.miningdim.enter.full";
                case QUEUE_TIMEOUT -> "commands.miningdim.enter.queue_timeout";
            };
        }
        return "commands.miningdim.enter.error";
    }

    /** CompletableFuture 把异常包成 CompletionException; 取真实因以分类 (不掩盖, 仅解壳)。 */
    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
