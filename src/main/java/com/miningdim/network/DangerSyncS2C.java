package com.miningdim.network;

import com.miningdim.core.IMiningNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C 压力同步包 (设计文档 15.4.2)。danger 评估降频周期 (默认每 20 tick) 产出新值且超过变化阈值时下发。
 *
 * N2 服务端权威: 客户端不自算 danger, 仅渲染本包携带的服务端计算结果; lightDimFactor 仅驱动客户端
 * 屏幕压暗 overlay, 不修改世界真实光照 (15.4.2)。tier 为 DangerTier 序号 (0 SAFE / 1 ALERT / 2 HIGH)。
 */
public record DangerSyncS2C(long instanceId, float danger, float dangerMax, byte tier, float lightDimFactor) {

    public static void encode(DangerSyncS2C msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.instanceId);
        buf.writeFloat(msg.danger);
        buf.writeFloat(msg.dangerMax);
        buf.writeByte(msg.tier);
        buf.writeFloat(msg.lightDimFactor);
    }

    public static DangerSyncS2C decode(FriendlyByteBuf buf) {
        long instanceId = buf.readLong();
        float danger = buf.readFloat();
        float dangerMax = buf.readFloat();
        byte tier = buf.readByte();
        float lightDimFactor = buf.readFloat();
        return new DangerSyncS2C(instanceId, danger, dangerMax, tier, lightDimFactor);
    }

    /**
     * 客户端 handler (15.4.2/N4): enqueueWork 切回客户端主线程, 写入 ClientDangerState 单例供 HUD/滤镜读取;
     * 不触发任何世界写。客户端类的引用经 DistExecutor 隔离, 防止专用服务器 (无客户端类) 加载期触链。
     */
    public static void handle(DangerSyncS2C msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientDangerState.accept(msg)));
        ctx.setPacketHandled(true);
    }

    /** 把 byte 序号还原为 DangerTier (越界兜底为 SAFE, 防恶意/错位字节崩客户端 UI)。 */
    public IMiningNetwork.DangerTier tierEnum() {
        IMiningNetwork.DangerTier[] values = IMiningNetwork.DangerTier.values();
        int idx = tier;
        if (idx < 0 || idx >= values.length) {
            return IMiningNetwork.DangerTier.SAFE;
        }
        return values[idx];
    }
}
