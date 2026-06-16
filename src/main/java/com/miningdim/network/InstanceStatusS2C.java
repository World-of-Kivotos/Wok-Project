package com.miningdim.network;

import com.miningdim.core.Difficulty;
import com.miningdim.core.GenState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C 实例状态包 (设计文档 15.4.4)。玩家订阅某实例 (进入/打开 GUI 列表) 或该实例生成进度推进/状态变更时下发。
 *
 * 字段: instanceId; difficulty (byte = Difficulty.id); genState (byte = GenState.ordinal);
 * genProgress [0,1]; playerCount; regionBox 的 XZ 边界四个 int (minX/minZ/maxX/maxZ, 供客户端画 region)。
 * GENERATING 时客户端显示 genProgress 进度条; READY/READY_FALLBACK 时启用"进入"按钮。
 */
public record InstanceStatusS2C(long instanceId, byte difficulty, byte genState, float genProgress,
                                int playerCount, int regionMinX, int regionMinZ,
                                int regionMaxX, int regionMaxZ) {

    public static void encode(InstanceStatusS2C msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.instanceId);
        buf.writeByte(msg.difficulty);
        buf.writeByte(msg.genState);
        buf.writeFloat(msg.genProgress);
        buf.writeVarInt(msg.playerCount);
        buf.writeInt(msg.regionMinX);
        buf.writeInt(msg.regionMinZ);
        buf.writeInt(msg.regionMaxX);
        buf.writeInt(msg.regionMaxZ);
    }

    public static InstanceStatusS2C decode(FriendlyByteBuf buf) {
        long instanceId = buf.readLong();
        byte difficulty = buf.readByte();
        byte genState = buf.readByte();
        float genProgress = buf.readFloat();
        int playerCount = buf.readVarInt();
        int regionMinX = buf.readInt();
        int regionMinZ = buf.readInt();
        int regionMaxX = buf.readInt();
        int regionMaxZ = buf.readInt();
        return new InstanceStatusS2C(instanceId, difficulty, genState, genProgress, playerCount,
                regionMinX, regionMinZ, regionMaxX, regionMaxZ);
    }

    /**
     * 客户端 handler (15.4.4/N4): enqueueWork 切回客户端主线程, 更新 GUI 列表/进度条。不做世界写。
     * 客户端引用经 DistExecutor 隔离专用服务器。
     */
    public static void handle(InstanceStatusS2C msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientFeedback.acceptInstanceStatus(msg)));
        ctx.setPacketHandled(true);
    }

    /** 把 byte 序号还原为 Difficulty (非法序号自然抛 IAE, 与 core 契约一致, 不掩盖错位字节)。 */
    public Difficulty difficultyEnum() {
        return Difficulty.byId(difficulty);
    }

    /** 把 byte ordinal 还原为 GenState (越界兜底 FAILED, 与 InstanceState.load 的前向兼容策略一致)。 */
    public GenState genStateEnum() {
        GenState[] values = GenState.values();
        int idx = genState;
        if (idx < 0 || idx >= values.length) {
            return GenState.FAILED;
        }
        return values[idx];
    }
}
