package com.miningdim.champion.network;

import com.miningdim.champion.client.ChampionSizeRenderClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C 冠军体型系数同步包 (ChampionStarAffix spec 9A.3 #17 体型渲染 + 9.4 形态守卫; 批4 波3)。冠军 capability
 * 只在服务端权威, 客户端拿不到词条 -&gt; 客户端的 {@code EntityEvent.Size} 与渲染无从得知体型系数, 故服务端在
 * 盖章刷新维度时 (广播给 tracking 玩家) 与玩家开始追踪时 (补发后来者) 用本包把【最终守卫后的尺寸系数】下发。
 *
 * <p>携带: entityId (客户端按 {@code ClientLevel.getEntity} 反查实体) + sizeScale (巨大化 &gt;1 / 缩小化 &lt;1 /
 * 无体型词条不发包)。客户端 {@link ChampionSizeRenderClient} 缓存 {@code entityId -> scale}, 供渲染 poseStack 缩放
 * 与客户端碰撞箱缩放读取 (两端碰撞箱一致防幽灵卡位)。
 *
 * <p>复用 {@code MiningNetwork.CHANNEL} (统一 CHANNEL 纪律): discriminator id 由 {@code MiningNetwork#register}
 * 集中自增登记, 两端同序; 追加在既有包尾部不改既有 id 分配。N2 服务端权威: 客户端只渲染本包携带的服务端结果,
 * 不自算体型; 客户端类引用经 DistExecutor 隔离 (与 {@code JobSyncS2C}/{@code MinerHighlightS2C} 同范式, 防专用
 * 服务器加载期触链)。
 */
public record ChampionSizeS2C(int entityId, float sizeScale) {

    public static void encode(ChampionSizeS2C msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        buf.writeFloat(msg.sizeScale);
    }

    public static ChampionSizeS2C decode(FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        float sizeScale = buf.readFloat();
        return new ChampionSizeS2C(entityId, sizeScale);
    }

    /**
     * 客户端 handler: enqueueWork 切回客户端主线程, 把体型系数交给 {@link ChampionSizeRenderClient} 缓存并触发
     * 该实体 refreshDimensions (使客户端碰撞箱按新系数即时缩放); 不触发任何世界写。客户端类引用经 DistExecutor 隔离。
     */
    public static void handle(ChampionSizeS2C msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ChampionSizeRenderClient.accept(msg.entityId, msg.sizeScale)));
        ctx.setPacketHandled(true);
    }
}
