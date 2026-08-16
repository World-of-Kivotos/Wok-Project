package com.miningdim.job.miner.network;

import com.miningdim.job.miner.client.MinerHighlightRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S2C 连锁预览下发包 (服务端权威跑 plan 后回)。携带熄灭 tick (客户端到该 tick 后停画; 按住期间由持续请求刷新故常亮)
 * 与连锁候选坐标列表; "连锁 N"的计数即列表长度 (单一真源, 与实际连锁的 plan 一致, 永不撒谎)。
 *
 * 客户端渲染进 {@link MinerHighlightRenderer} 的独立"预览槽" (与探测脉冲槽双槽位互不覆盖), 用区别于矿(绿)/陷阱(红)的
 * 预览色 (青)。客户端类引用经 DistExecutor 隔离 (与 {@link MinerHighlightS2C} 同范式, 防专用服务器加载期触链)。
 *
 * 防泄密: 服务端 plan 对未揭示陷阱按其伪装的普通矿石处理, 故本包的位置与计数不泄漏任何陷阱位 (客户端无从分辨)。
 */
public record MinerChainPreviewS2C(long expireTick, List<BlockPos> positions) {

    public static void encode(MinerChainPreviewS2C msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.expireTick);
        buf.writeVarInt(msg.positions.size());
        for (BlockPos p : msg.positions) {
            buf.writeBlockPos(p);
        }
    }

    public static MinerChainPreviewS2C decode(FriendlyByteBuf buf) {
        long expireTick = buf.readLong();
        int n = buf.readVarInt();
        List<BlockPos> positions = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            positions.add(buf.readBlockPos());
        }
        return new MinerChainPreviewS2C(expireTick, positions);
    }

    /**
     * 客户端 handler: enqueueWork 切回客户端主线程, 把预览交给 {@link MinerHighlightRenderer} 的预览槽 (不触世界写)。
     * 客户端类引用经 DistExecutor 隔离 (专用服务器不触达客户端类)。
     */
    public static void handle(MinerChainPreviewS2C msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> MinerHighlightRenderer.acceptPreview(msg.expireTick, msg.positions)));
        ctx.setPacketHandled(true);
    }
}
