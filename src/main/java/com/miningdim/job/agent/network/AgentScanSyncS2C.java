package com.miningdim.job.agent.network;

import com.miningdim.job.agent.SealCategory;
import com.miningdim.job.agent.client.AgentScanClientReceiver;
import com.miningdim.job.agent.panel.AgentScanEntry;
import com.miningdim.job.agent.panel.AgentScanSnapshot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S2C 战术扫描快照同步包 (SpecialAgent_Job_DesignSpec 五章战术扫描面板)。服务端一次扫描脉冲对某精英分级解密后,
 * 把 {@link AgentScanSnapshot} 推给客户端面板渲染。复用 {@code MiningNetwork.CHANNEL} (第七章统一 CHANNEL 纪律),
 * discriminator id 由 {@link com.miningdim.network.MiningNetwork#register} 集中自增登记, 两端同序。
 *
 * 服务端权威 (N2): 客户端不自算解密分级, 仅渲染本包携带的服务端结果; 客户端类引用 ({@link com.miningdim.job.agent
 * .client.ClientAgentScanState} / {@link AgentScanClientReceiver}) 经 DistExecutor 隔离 (防专用服务器加载期触链,
 * 范式对齐 {@code JobSyncS2C.handle})。
 *
 * 编解码契约: 头部 (targetNetworkId int + star varInt + agentLevel varInt) + 条目数 varInt + 逐条目
 * (affixId utf + displayKey utf + category byte + decrypted/sealable/sealed 三 boolean)。顺序即两端契约。
 */
public record AgentScanSyncS2C(AgentScanSnapshot snapshot) {

    public static void encode(AgentScanSyncS2C msg, FriendlyByteBuf buf) {
        AgentScanSnapshot s = msg.snapshot;
        buf.writeInt(s.targetNetworkId());
        buf.writeVarInt(s.star());
        buf.writeVarInt(s.agentLevel());
        List<AgentScanEntry> entries = s.entries();
        buf.writeVarInt(entries.size());
        for (AgentScanEntry e : entries) {
            buf.writeUtf(e.affixId());
            buf.writeUtf(e.displayKey());
            buf.writeByte(e.category().ordinal());
            buf.writeBoolean(e.decrypted());
            buf.writeBoolean(e.sealable());
            buf.writeBoolean(e.sealed());
        }
    }

    public static AgentScanSyncS2C decode(FriendlyByteBuf buf) {
        int targetNetworkId = buf.readInt();
        int star = buf.readVarInt();
        int agentLevel = buf.readVarInt();
        int count = buf.readVarInt();
        List<AgentScanEntry> entries = new ArrayList<>(count);
        SealCategory[] categories = SealCategory.values();
        for (int i = 0; i < count; i++) {
            String affixId = buf.readUtf();
            String displayKey = buf.readUtf();
            int catOrdinal = buf.readByte();
            // 越界兜底为 PASSIVE (防恶意/错位字节崩客户端面板; 与 DangerSyncS2C.tierEnum 同纪律)。
            SealCategory category = (catOrdinal >= 0 && catOrdinal < categories.length)
                    ? categories[catOrdinal] : SealCategory.PASSIVE;
            boolean decrypted = buf.readBoolean();
            boolean sealable = buf.readBoolean();
            boolean sealed = buf.readBoolean();
            entries.add(new AgentScanEntry(affixId, displayKey, category, decrypted, sealable, sealed));
        }
        return new AgentScanSyncS2C(new AgentScanSnapshot(targetNetworkId, star, agentLevel, entries));
    }

    /**
     * 客户端 handler: enqueueWork 切回客户端主线程, 写入 {@link com.miningdim.job.agent.client.ClientAgentScanState};
     * 不触发任何世界写。客户端类引用经 DistExecutor.unsafeRunWhenOn + 双箭头方法调用隔离 (与 JobSyncS2C.handle 同范式)。
     */
    public static void handle(AgentScanSyncS2C msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> AgentScanClientReceiver.accept(msg.snapshot)));
        ctx.setPacketHandled(true);
    }
}
