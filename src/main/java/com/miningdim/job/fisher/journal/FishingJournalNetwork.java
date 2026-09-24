package com.miningdim.job.fisher.journal;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.fisher.journal.client.FishingJournalClient;
import com.miningdim.job.fisher.size.FishRecord;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 图鉴只有服务端下发快照，不接受客户端上传收藏进度。
 *
 * 协议 2: 在收藏集合之后追加本人的钓获记录 (次数、最大个体体长毫米、体重毫克), 记录只允许指向本次目录里的条目。
 * 两端版本号必须相等, 旧客户端连新服务端会在握手时被拒, 不会读到错位的字段。
 */
public final class FishingJournalNetwork {
    private static final String PROTOCOL = "2";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MiningConstants.MODID, "fishing_journal"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private FishingJournalNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, SnapshotPacket.class, FishingJournalNetwork::encode,
                FishingJournalNetwork::decode, FishingJournalNetwork::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void send(ServerPlayer player, FishingJournalSnapshot snapshot, boolean open) {
        if (!CHANNEL.isRemotePresent(player.connection.connection)) {
            if (open) {
                player.displayClientMessage(Component.translatable("fishing.miningdim.network_unavailable"), false);
            }
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SnapshotPacket(snapshot, open));
    }

    public record SnapshotPacket(FishingJournalSnapshot snapshot, boolean open) {
    }

    static void encode(SnapshotPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.open());
        buf.writeVarInt(packet.snapshot().entries().size());
        for (FishingJournalEntry entry : packet.snapshot().entries()) {
            buf.writeResourceLocation(entry.itemId());
            buf.writeUtf(entry.category(), 32);
            buf.writeUtf(entry.descriptionKey(), FishingJournalCatalog.MAX_TEXT_KEY);
            buf.writeUtf(entry.habitatKey(), FishingJournalCatalog.MAX_TEXT_KEY);
            buf.writeUtf(entry.conditionsKey(), FishingJournalCatalog.MAX_TEXT_KEY);
        }
        buf.writeVarInt(packet.snapshot().collected().size());
        packet.snapshot().collected().stream().sorted().forEach(buf::writeResourceLocation);
        buf.writeVarInt(packet.snapshot().records().size());
        packet.snapshot().records().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            buf.writeResourceLocation(entry.getKey());
            buf.writeVarInt(entry.getValue().count());
            buf.writeVarInt(entry.getValue().bestLengthMm());
            buf.writeVarLong(entry.getValue().bestWeightMg());
        });
    }

    static SnapshotPacket decode(FriendlyByteBuf buf) {
        boolean open = buf.readBoolean();
        int entryCount = readCount(buf, FishingJournalCatalog.MAX_ENTRIES);
        var entries = new ArrayList<FishingJournalEntry>(entryCount);
        Set<ResourceLocation> ids = new HashSet<>();
        for (int i = 0; i < entryCount; i++) {
            ResourceLocation id = buf.readResourceLocation();
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate journal entry in snapshot: " + id);
            }
            entries.add(new FishingJournalEntry(id, buf.readUtf(32),
                    buf.readUtf(FishingJournalCatalog.MAX_TEXT_KEY),
                    buf.readUtf(FishingJournalCatalog.MAX_TEXT_KEY),
                    buf.readUtf(FishingJournalCatalog.MAX_TEXT_KEY)));
        }
        int collectedCount = readCount(buf, entryCount);
        Set<ResourceLocation> collected = new HashSet<>();
        for (int i = 0; i < collectedCount; i++) {
            ResourceLocation id = buf.readResourceLocation();
            if (!ids.contains(id) || !collected.add(id)) {
                throw new IllegalArgumentException("Invalid collected item in journal snapshot: " + id);
            }
        }
        int recordCount = readCount(buf, entryCount);
        Map<ResourceLocation, FishRecord> records = new HashMap<>();
        for (int i = 0; i < recordCount; i++) {
            ResourceLocation id = buf.readResourceLocation();
            // FishRecord 构造器拒绝非正值, 畸形包在这里整包失败, 不会带着半份记录进界面。
            FishRecord record = new FishRecord(buf.readVarInt(), buf.readVarInt(), buf.readVarLong());
            if (!ids.contains(id) || records.putIfAbsent(id, record) != null) {
                throw new IllegalArgumentException("Invalid fishing record in journal snapshot: " + id);
            }
        }
        return new SnapshotPacket(new FishingJournalSnapshot(entries, collected, records), open);
    }

    private static int readCount(FriendlyByteBuf buf, int maximum) {
        int count = buf.readVarInt();
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException("Invalid fishing journal snapshot size: " + count);
        }
        return count;
    }

    private static void handle(SnapshotPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> FishingJournalClient.accept(packet.snapshot(), packet.open())));
        context.setPacketHandled(true);
    }
}
