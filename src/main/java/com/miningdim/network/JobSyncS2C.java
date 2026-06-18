package com.miningdim.network;

import com.miningdim.job.ClientJobState;
import com.miningdim.job.JobId;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * S2C 全职业进度同步包 (JobFramework_Shared_Foundation_DesignSpec 第七章 + 实现手册登录同步范式)。
 * 登录时 (PlayerLoggedInEvent) 与等级变化时由服务端下发, 客户端写入 {@link ClientJobState} 供 /job HUD 读取。
 *
 * 复用 MiningNetwork.CHANNEL (第七章统一 CHANNEL 纪律): discriminator id 由 {@link MiningNetwork#register}
 * 集中自增登记, 两端同序。本包携带固定 7 个 JobId 的 [level, xp]; 按 JobId.values() 顺序读写, 顺序即契约。
 *
 * N2 服务端权威: 客户端不自算升级, 仅渲染本包携带的服务端结果; 客户端类引用经 DistExecutor 隔离
 * (防专用服务器加载期触链)。
 */
public record JobSyncS2C(Map<JobId, long[]> levels) {

    public static void encode(JobSyncS2C msg, FriendlyByteBuf buf) {
        // 固定按 JobId.values() 顺序写 (level int + xp long), 顺序即两端契约; 缺职业写默认 (1,0)。
        for (JobId job : JobId.values()) {
            long[] lv = msg.levels.get(job);
            int level = lv == null ? 1 : (int) lv[0];
            long xp = lv == null ? 0L : lv[1];
            buf.writeVarInt(level);
            buf.writeVarLong(xp);
        }
    }

    public static JobSyncS2C decode(FriendlyByteBuf buf) {
        Map<JobId, long[]> levels = new EnumMap<>(JobId.class);
        for (JobId job : JobId.values()) {
            int level = buf.readVarInt();
            long xp = buf.readVarLong();
            levels.put(job, new long[]{level, xp});
        }
        return new JobSyncS2C(levels);
    }

    /**
     * 客户端 handler: enqueueWork 切回客户端主线程, 写入 ClientJobState; 不触发任何世界写。
     * 客户端类引用经 DistExecutor 隔离 (与 DangerSyncS2C.handle 同范式)。
     */
    public static void handle(JobSyncS2C msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientJobState.accept(msg.levels)));
        ctx.setPacketHandled(true);
    }
}
