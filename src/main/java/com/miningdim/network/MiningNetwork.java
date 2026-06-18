package com.miningdim.network;

import com.miningdim.core.IMiningConfig;
import com.miningdim.core.IMiningNetwork;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * 网络门面实现 (设计文档第十五章, IMiningNetwork)。基于 Forge SimpleChannel (1.20.1 正确 API),
 * 通过 NetworkRegistry.newSimpleChannel 创建并逐包 registerMessage (15.2)。
 *
 * 服务端权威 (C5/N1): 仅服务端调用 sendXxx 下发 S2C; 客户端只收 S2C 做渲染。注册次序集中在 register(),
 * 两端共用同一份代码以保证 discriminator id 一致 (15.2)。注册时机由 NetworkSystem 在 FMLCommonSetupEvent
 * 的 enqueueWork 内调用 register() (线程安全窗口, 15.2)。
 */
public final class MiningNetwork implements IMiningNetwork {

    /** 协议版本; 客户端/服务端不一致时握手拒绝 (N5)。 */
    private static final String PROTOCOL_VERSION = "1";

    /** danger 视觉档阈值 (相对 dangerMax 的占比): >=0.66 高危, >=0.33 警戒, 否则安全。便捷重载用。 */
    private static final float TIER_HIGH_RATIO = 0.66f;
    private static final float TIER_ALERT_RATIO = 0.33f;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MiningConstants.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private int nextId = 0;

    private int nextId() {
        return nextId++;
    }

    /**
     * 集中注册全部 packet (15.2)。注册次序两端一致, 显式传 NetworkDirection 用于握手期方向校验。
     * 由 NetworkSystem 在 FMLCommonSetupEvent.enqueueWork 内调用一次。
     */
    public void register() {
        CHANNEL.registerMessage(nextId(), SelectZoneC2S.class,
                SelectZoneC2S::encode, SelectZoneC2S::decode, SelectZoneC2S::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), DangerSyncS2C.class,
                DangerSyncS2C::encode, DangerSyncS2C::decode, DangerSyncS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId(), TeleportResultS2C.class,
                TeleportResultS2C::encode, TeleportResultS2C::decode, TeleportResultS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId(), InstanceStatusS2C.class,
                InstanceStatusS2C::encode, InstanceStatusS2C::decode, InstanceStatusS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        // 职业框架全职业进度同步 (JobFramework spec 第七章: 复用本 CHANNEL, discriminator 集中自增登记)。
        // 追加在既有 4 包之后, 两端同序, 不改动既有 id 分配。
        CHANNEL.registerMessage(nextId(), JobSyncS2C.class,
                JobSyncS2C::encode, JobSyncS2C::decode, JobSyncS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /**
     * 下发全职业进度同步包到指定玩家 (职业框架登录同步 / 等级变化时调用)。静态便捷入口: 职业框架经此发包,
     * 无需经 IMiningNetwork core 门面 (该门面为阶段0 定稿, 不含职业方法; 职业包直接用 CHANNEL 是第七章纪律)。
     */
    public static void sendJobSync(ServerPlayer player, JobSyncS2C msg) {
        if (!canReceive(player)) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    /**
     * S2C 发包健壮性守卫 (N1): 仅向持有活动连接的玩家下发定向包。向尚未连上 (握手未完成) 或正在断开
     * (channel 已关闭) 的连接发包, 在 Forge 网络栈深处会因 netty channel 为空/关闭而异常, 这不是业务错误,
     * 故在出口处过滤为 no-op。判定基于 ServerGamePacketListenerImpl.isAcceptingMessages()
     * (1.20.1 为 public, 等价于内部 Connection.isConnected() == channel != null && channel.isOpen())。
     * connection 在玩家完成登录前理论上非 null, 仍做空判防御极端时序。
     */
    private static boolean canReceive(ServerPlayer player) {
        return player.connection != null && player.connection.isAcceptingMessages();
    }

    // ---- IMiningNetwork (服务端调用; 下发到指定玩家) ----

    @Override
    public void sendDanger(ServerPlayer player, long instanceId, float danger, float dangerMax,
                           DangerTier tier, float lightDimFactor) {
        DangerSyncS2C msg = new DangerSyncS2C(instanceId, danger, dangerMax, (byte) tier.ordinal(), lightDimFactor);
        sendTo(player, msg);
    }

    @Override
    public void sendDanger(ServerPlayer player, float danger) {
        // 便捷重载 (15.4.2): dangerMax 取配置, tier/lightDimFactor 按归一化占比推导。instanceId 取 -1
        // (此重载不携带具体实例上下文, 压力系统的全量重载携带)。
        IMiningConfig config = MiningServices.config();
        float dangerMax = (float) config.dangerMax();
        float ratio = dangerMax > 0.0f ? danger / dangerMax : 0.0f;
        DangerTier tier = tierFor(ratio);
        // 压暗系数与危险占比线性相关, 封顶 1.0 (后续可由 client 配置 dangerVisualMode 决定是否真正应用)。
        float lightDimFactor = Math.max(0.0f, Math.min(1.0f, ratio));
        sendDanger(player, -1L, danger, dangerMax, tier, lightDimFactor);
    }

    @Override
    public void sendTeleportResult(ServerPlayer player, TeleportResult result, long instanceId,
                                   int queuePos, String reasonKey) {
        String key = reasonKey == null ? "" : reasonKey;
        TeleportResultS2C msg = new TeleportResultS2C((byte) result.ordinal(), instanceId, queuePos, key);
        sendTo(player, msg);
    }

    @Override
    public void sendInstanceStatus(ServerPlayer player, InstanceState instance, float genProgress) {
        RegionBox box = instance.regionBox();
        InstanceStatusS2C msg = new InstanceStatusS2C(
                instance.instanceId(),
                (byte) instance.difficulty().id(),
                (byte) instance.genState().ordinal(),
                genProgress,
                instance.refCount(),
                box.originX(),
                box.originZ(),
                box.originX() + box.sizeX() - 1,
                box.originZ() + box.sizeZ() - 1);
        sendTo(player, msg);
    }

    @Override
    public void openGui(ServerPlayer player, String menuKind) {
        // 15.5: GUI 打开走原版 NetworkHooks.openScreen, 需由 GUI 子系统提供 MenuProvider。本期 (网络子系统)
        // 不含菜单实现; 在 GUI 子系统注册前调用此方法是装配缺陷, 按 C9 自然抛出暴露, 不静默吞或伪装成功。
        throw new UnsupportedOperationException(
                "openGui requires a GUI subsystem to supply a MenuProvider; menuKind=" + menuKind
                        + " (NetworkSystem does not implement menus; wire NetworkHooks.openScreen in the GUI subsystem)");
    }

    // ---- 内部下发 (PacketDistributor.PLAYER) ----

    private <MSG> void sendTo(ServerPlayer player, MSG msg) {
        if (!canReceive(player)) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    private DangerTier tierFor(float ratio) {
        if (ratio >= TIER_HIGH_RATIO) {
            return DangerTier.HIGH;
        }
        if (ratio >= TIER_ALERT_RATIO) {
            return DangerTier.ALERT;
        }
        return DangerTier.SAFE;
    }
}
