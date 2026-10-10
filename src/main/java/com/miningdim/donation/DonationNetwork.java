package com.miningdim.donation;

import com.miningdim.core.MiningConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * 捐赠箱专属 SimpleChannel (模块自持, 与厨师模块同范式, 不改中央 MiningNetwork)。
 *
 * 三个包:
 *  - {@link ViewRequest} (C2S): 请求账本某一页;
 *  - {@link CoAdminEdit} (C2S): 增删协管 (按玩家名);
 *  - {@link ViewSync} (S2C): 账本快照 + 可选的一行操作结果提示。
 *
 * 两个 C2S 包都以"发送者当前开着的管理界面"为锚: 服务端取 sender.containerMenu, 必须是本模块的管理界面且
 * stillValid (方块仍在、距离内、仍有管理权限), 否则静默丢弃。包里不带坐标, 客户端没法借它去操作别处的箱子。
 * 拒绝分支只记 DEBUG、不回包: 触发权在客户端手里, 回包或记 WARN 都会被拿来放大流量与日志。
 *
 * 成功分支同样由客户端触发, 所以两个 C2S 包都按玩家限速 (见 {@link #VIEW_COOLDOWN_TICKS}、
 * {@link #EDIT_COOLDOWN_TICKS}): 任何人放个箱子就是箱主, 不限速时改过的客户端每秒几千个协管增删包, 每包一行
 * 永久审计日志外加一次全服在线玩家遍历与一份账本快照; 只发账本请求也能让服务端每包复制 300 条流水、排序整张
 * 累计表再回包。限速按玩家而不是按界面记, 关了再开界面不会重置额度。被限速的包同样静默丢弃。
 */
public final class DonationNetwork {

    private DonationNetwork() {
    }

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MiningConstants.MODID, "donation"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/donation");
    private static final int MAX_NAME_CHARS = 64;
    private static boolean registered;

    /** 同一玩家两次账本请求的最小间隔 (tick, 每秒至多 4 次); 正常翻页远慢于此。 */
    static final int VIEW_COOLDOWN_TICKS = 5;

    /** 同一玩家两次协管增删的最小间隔 (tick, 每秒至多 2 次)。 */
    static final int EDIT_COOLDOWN_TICKS = 10;

    private static final int KIND_VIEW = 0;
    private static final int KIND_EDIT = 1;
    private static final long NEVER = Long.MIN_VALUE;

    /**
     * 每名玩家各类请求最近一次被受理的服务端 tick。只在服务端主线程读写 (包处理都在 enqueueWork 里);
     * 以玩家对象为弱键, 玩家下线或重生换对象后自然释放。
     */
    private static final Map<ServerPlayer, long[]> LAST_ACCEPTED = new WeakHashMap<>();

    /** 两端同序注册 (discriminator 一致)。由模块入口在 FMLCommonSetupEvent.enqueueWork 内调用一次。 */
    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        int id = 0;
        CHANNEL.registerMessage(id++, ViewRequest.class, ViewRequest::encode, ViewRequest::decode, ViewRequest::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, CoAdminEdit.class, CoAdminEdit::encode, CoAdminEdit::decode, CoAdminEdit::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id, ViewSync.class, ViewSync::encode, ViewSync::decode, ViewSync::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** 客户端请求账本: 流水第 page 页、累计表第 totalsPage 页 (均 0 起)。 */
    public static void requestView(int page, int totalsPage) {
        CHANNEL.sendToServer(new ViewRequest(page, totalsPage));
    }

    /** 客户端请求增删协管。 */
    public static void requestCoAdminEdit(boolean add, String name) {
        CHANNEL.sendToServer(new CoAdminEdit(add, name));
    }

    static void sendView(ServerPlayer player, DonationView view, @Nullable Component notice) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ViewSync(view, notice));
    }

    /** 发送者当前开着且仍有效的管理界面所绑定的箱子; 否则 null。 */
    @Nullable
    private static DonationBoxBlockEntity openBox(ServerPlayer sender) {
        if (sender.containerMenu instanceof DonationManagerMenu menu && menu.stillValid(sender)) {
            return menu.blockEntity();
        }
        return null;
    }

    /** 该玩家这一类请求是否已过冷却; 过了就记下本次受理时刻。 */
    private static boolean tryAcquire(ServerPlayer player, int kind, int cooldownTicks) {
        long now = player.server.getTickCount();
        long[] last = LAST_ACCEPTED.computeIfAbsent(player, p -> new long[] {NEVER, NEVER});
        if (last[kind] != NEVER && now - last[kind] < cooldownTicks) {
            return false;
        }
        last[kind] = now;
        return true;
    }

    /**
     * 受理一次账本请求并回一份快照。被限速或发送者没开着有效管理界面时静默丢弃, 返回 false。
     */
    static boolean handleViewRequest(ServerPlayer sender, int page, int totalsPage) {
        if (!tryAcquire(sender, KIND_VIEW, VIEW_COOLDOWN_TICKS)) {
            return false;
        }
        DonationBoxBlockEntity box = openBox(sender);
        if (box == null) {
            LOGGER.debug("Rejected donation ledger request from {} without a valid manager menu",
                    sender.getGameProfile().getName());
            return false;
        }
        sendView(sender, DonationView.build(box, sender, page, totalsPage), null);
        return true;
    }

    /**
     * 受理一次协管增删 (权限由 {@link DonationBoxService} 判定) 并回一份附结果提示的快照。被限速或发送者没开着
     * 有效管理界面时静默丢弃, 返回 false。
     */
    static boolean handleCoAdminEdit(ServerPlayer sender, boolean add, String name) {
        if (!tryAcquire(sender, KIND_EDIT, EDIT_COOLDOWN_TICKS)) {
            return false;
        }
        DonationBoxBlockEntity box = openBox(sender);
        if (box == null) {
            LOGGER.debug("Rejected donation co-admin edit from {} without a valid manager menu",
                    sender.getGameProfile().getName());
            return false;
        }
        DonationBoxService.Outcome outcome = add
                ? DonationBoxService.addCoAdmin(box, sender.server, sender, name)
                : DonationBoxService.removeCoAdmin(box, sender.server, sender, name);
        // 操作者可能刚把自己的管理资格改没 (OP 被 deop 等极端情况), 此时不再下发账本。
        if (openBox(sender) == box) {
            sendView(sender, DonationView.build(box, sender, 0, 0), outcome.message());
        }
        return true;
    }

    public record ViewRequest(int page, int totalsPage) {
        static void encode(ViewRequest msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.page);
            buf.writeVarInt(msg.totalsPage);
        }

        static ViewRequest decode(FriendlyByteBuf buf) {
            return new ViewRequest(buf.readVarInt(), buf.readVarInt());
        }

        static void handle(ViewRequest msg, Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                ServerPlayer sender = ctx.getSender();
                if (sender != null) {
                    handleViewRequest(sender, msg.page, msg.totalsPage);
                }
            });
            ctx.setPacketHandled(true);
        }
    }

    public record CoAdminEdit(boolean add, String name) {
        static void encode(CoAdminEdit msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.add);
            buf.writeUtf(msg.name, MAX_NAME_CHARS);
        }

        static CoAdminEdit decode(FriendlyByteBuf buf) {
            return new CoAdminEdit(buf.readBoolean(), buf.readUtf(MAX_NAME_CHARS));
        }

        static void handle(CoAdminEdit msg, Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                ServerPlayer sender = ctx.getSender();
                if (sender != null) {
                    handleCoAdminEdit(sender, msg.add, msg.name);
                }
            });
            ctx.setPacketHandled(true);
        }
    }

    public record ViewSync(DonationView view, @Nullable Component notice) {
        static void encode(ViewSync msg, FriendlyByteBuf buf) {
            msg.view.write(buf);
            buf.writeBoolean(msg.notice != null);
            if (msg.notice != null) {
                buf.writeComponent(msg.notice);
            }
        }

        static ViewSync decode(FriendlyByteBuf buf) {
            DonationView view = DonationView.read(buf);
            Component notice = buf.readBoolean() ? buf.readComponent() : null;
            return new ViewSync(view, notice);
        }

        static void handle(ViewSync msg, Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.miningdim.donation.client.DonationClientHooks.acceptView(msg.view, msg.notice)));
            ctx.setPacketHandled(true);
        }
    }
}
