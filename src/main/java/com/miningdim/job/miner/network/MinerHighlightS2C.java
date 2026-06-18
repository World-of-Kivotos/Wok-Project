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
 * S2C 探矿/陷阱高亮坐标下发包 (Miner_Job_DesignSpec 第十一章: 新建一个 S2C 包 + 客户端 RenderLevelStageEvent
 * 画轮廓)。服务端权威查询 (OreScanService/TrapScanService) 后只下发 "球内确有该矿/陷阱" 的坐标列表。
 *
 * 携带: kind (0=矿, 1=陷阱) + 熄灭 tick (客户端到该 tick 后停画, ~8s 脉冲) + 坐标列表。
 * 防 X 光: 单矿种 + 有限半径 + 服务端只下发确有坐标 + 脉冲熄灭, 客户端无从透视全图。
 *
 * 客户端类引用经 DistExecutor 隔离 (与 DangerSyncS2C / JobSyncS2C 同范式, 防专用服务器加载期触链)。
 */
public record MinerHighlightS2C(byte kind, long expireTick, List<BlockPos> positions) {

    /** kind 取值: 矿物高亮。 */
    public static final byte KIND_ORE = 0;
    /** kind 取值: 陷阱高亮。 */
    public static final byte KIND_TRAP = 1;

    public static void encode(MinerHighlightS2C msg, FriendlyByteBuf buf) {
        buf.writeByte(msg.kind);
        buf.writeLong(msg.expireTick);
        buf.writeVarInt(msg.positions.size());
        for (BlockPos p : msg.positions) {
            buf.writeBlockPos(p);
        }
    }

    public static MinerHighlightS2C decode(FriendlyByteBuf buf) {
        byte kind = buf.readByte();
        long expireTick = buf.readLong();
        int n = buf.readVarInt();
        List<BlockPos> positions = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            positions.add(buf.readBlockPos());
        }
        return new MinerHighlightS2C(kind, expireTick, positions);
    }

    /**
     * 客户端 handler: enqueueWork 切回客户端主线程, 把高亮交给 {@link MinerHighlightRenderer} 缓存;
     * 不触发任何世界写。客户端类引用经 DistExecutor 隔离。
     */
    public static void handle(MinerHighlightS2C msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> MinerHighlightRenderer.accept(msg.kind, msg.expireTick, msg.positions)));
        ctx.setPacketHandled(true);
    }
}
