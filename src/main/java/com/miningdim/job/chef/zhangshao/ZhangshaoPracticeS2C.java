package com.miningdim.job.chef.zhangshao;

import com.miningdim.job.chef.zhangshao.client.ZhangshaoPracticeClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 练习只打开本地模拟界面，不接受客户端上报奖励或修改职业进度。 */
public record ZhangshaoPracticeS2C(int station) {
    public ZhangshaoPracticeS2C {
        if (station < 0 || station > 3) {
            throw new IllegalArgumentException("Unknown practice station: " + station);
        }
    }

    public static void encode(ZhangshaoPracticeS2C message, FriendlyByteBuf buffer) {
        buffer.writeByte(message.station);
    }

    public static ZhangshaoPracticeS2C decode(FriendlyByteBuf buffer) {
        return new ZhangshaoPracticeS2C(buffer.readUnsignedByte());
    }

    public static void handle(ZhangshaoPracticeS2C message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ZhangshaoPracticeClient.open(message.station)));
        context.setPacketHandled(true);
    }
}
