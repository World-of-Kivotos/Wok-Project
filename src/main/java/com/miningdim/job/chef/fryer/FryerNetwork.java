package com.miningdim.job.chef.fryer;

import com.google.gson.Gson;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.chef.fryer.client.FryerClient;
import com.miningdim.job.chef.pot.PotPacketRouter;
import com.miningdim.job.chef.zhangshao.ZhangshaoKernel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

@Mod.EventBusSubscriber(modid = MiningConstants.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class FryerNetwork {
    private static final Gson GSON = new Gson();
    // v3 adds the FD pot start packet and shared manual round routing.
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MiningConstants.MODID, "fryer"), () -> "3", "3"::equals, "3"::equals);

    private FryerNetwork() {}

    @SubscribeEvent
    public static void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            CHANNEL.registerMessage(0, Session.class, Session::encode, Session::decode, Session::handle,
                    Optional.of(NetworkDirection.PLAY_TO_CLIENT));
            CHANNEL.registerMessage(1, Inputs.class, Inputs::encode, Inputs::decode, Inputs::handle,
                    Optional.of(NetworkDirection.PLAY_TO_SERVER));
            CHANNEL.registerMessage(2, Abort.class, Abort::encode, Abort::decode, Abort::handle,
                    Optional.of(NetworkDirection.PLAY_TO_SERVER));
            CHANNEL.registerMessage(3, Bottle.class, Bottle::encode, Bottle::decode, Bottle::handle,
                    Optional.of(NetworkDirection.PLAY_TO_CLIENT));
            CHANNEL.registerMessage(4, Outcome.class, Outcome::encode, Outcome::decode, Outcome::handle,
                    Optional.of(NetworkDirection.PLAY_TO_CLIENT));
            CHANNEL.registerMessage(5, StartPot.class, StartPot::encode, StartPot::decode, StartPot::handle,
                    Optional.of(NetworkDirection.PLAY_TO_SERVER));
        });
    }

    public static void session(ServerPlayer player, BlockPos pos, long roundId, int seed, int[] params, ItemStack dish) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Session(pos, roundId, seed, params, dish));
    }

    public static void bottle(ServerPlayer player, long roundId, boolean gold) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Bottle(roundId, gold));
    }

    public static void result(ServerPlayer player, long roundId, ZhangshaoKernel.Result result, String message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Outcome(roundId, result, message));
    }

    public static void inputs(BlockPos pos, long roundId, int seq, int firstTick, byte[] inputs, int hash, boolean last) {
        CHANNEL.sendToServer(new Inputs(pos, roundId, seq, firstTick, inputs, hash, last));
    }

    public static void abort(BlockPos pos, long roundId) {
        CHANNEL.sendToServer(new Abort(pos, roundId));
    }

    public static void startPot(BlockPos pos) { CHANNEL.sendToServer(new StartPot(pos)); }

    private static FryerBlockEntity currentFryer(ServerPlayer player, BlockPos pos) {
        if (player == null || !(player.containerMenu instanceof FryerMenu menu)
                || !menu.stillValid(player) || !menu.position().equals(pos)) return null;
        return menu.blockEntity();
    }

    public record StartPot(BlockPos pos) {
        static void encode(StartPot message, FriendlyByteBuf buf) { buf.writeBlockPos(message.pos); }
        static StartPot decode(FriendlyByteBuf buf) { return new StartPot(buf.readBlockPos()); }
        static void handle(StartPot message, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null && ModList.get().isLoaded("farmersdelight")) PotPacketRouter.start(player, message.pos);
            });
            context.setPacketHandled(true);
        }
    }

    public record Session(BlockPos pos, long roundId, int seed, int[] params, ItemStack dish) {
        static void encode(Session message, FriendlyByteBuf buf) {
            buf.writeBlockPos(message.pos);
            buf.writeLong(message.roundId);
            buf.writeInt(message.seed);
            buf.writeVarIntArray(message.params);
            buf.writeInt(ZhangshaoKernel.fnv1a(message.params));
            buf.writeItem(message.dish);
        }
        static Session decode(FriendlyByteBuf buf) {
            BlockPos pos = buf.readBlockPos();
            long round = buf.readLong();
            int seed = buf.readInt();
            int[] params = buf.readVarIntArray(ZhangshaoKernel.PARAM_COUNT);
            if (params.length != ZhangshaoKernel.PARAM_COUNT || ZhangshaoKernel.fnv1a(params) != buf.readInt()) {
                throw new IllegalArgumentException("Corrupt fryer parameter block");
            }
            return new Session(pos, round, seed, params, buf.readItem());
        }
        static void handle(Session message, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> FryerClient.session(message.pos, message.roundId, message.seed, message.params, message.dish)));
            context.setPacketHandled(true);
        }
    }

    public record Inputs(BlockPos pos, long roundId, int seq, int firstTick, byte[] inputs, int hash, boolean last) {
        public Inputs {
            if (inputs.length == 0 || inputs.length > 40) throw new IllegalArgumentException("Fryer input batch must contain 1..40 ticks");
            inputs = inputs.clone();
        }
        static void encode(Inputs message, FriendlyByteBuf buf) {
            buf.writeBlockPos(message.pos);
            buf.writeLong(message.roundId);
            buf.writeVarInt(message.seq);
            buf.writeVarInt(message.firstTick);
            buf.writeByteArray(message.inputs);
            buf.writeInt(message.hash);
            buf.writeBoolean(message.last);
        }
        static Inputs decode(FriendlyByteBuf buf) {
            return new Inputs(buf.readBlockPos(), buf.readLong(), buf.readVarInt(), buf.readVarInt(),
                    buf.readByteArray(40), buf.readInt(), buf.readBoolean());
        }
        static void handle(Inputs message, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                FryerBlockEntity fryer = currentFryer(player, message.pos);
                if (fryer != null) fryer.acceptInput(player, message.roundId, message.seq, message.firstTick,
                        message.inputs, message.hash, message.last);
                else if (player != null && ModList.get().isLoaded("farmersdelight"))
                    PotPacketRouter.input(player, message.pos, message.roundId, message.seq, message.firstTick,
                            message.inputs, message.hash, message.last);
            });
            context.setPacketHandled(true);
        }
    }

    public record Abort(BlockPos pos, long roundId) {
        static void encode(Abort message, FriendlyByteBuf buf) { buf.writeBlockPos(message.pos).writeLong(message.roundId); }
        static Abort decode(FriendlyByteBuf buf) { return new Abort(buf.readBlockPos(), buf.readLong()); }
        static void handle(Abort message, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                FryerBlockEntity fryer = currentFryer(player, message.pos);
                if (fryer != null) fryer.abort(player, message.roundId, "主动撂勺");
                else if (player != null && ModList.get().isLoaded("farmersdelight"))
                    PotPacketRouter.abort(player, message.pos, message.roundId);
            });
            context.setPacketHandled(true);
        }
    }

    public record Bottle(long roundId, boolean gold) {
        static void encode(Bottle message, FriendlyByteBuf buf) { buf.writeLong(message.roundId).writeBoolean(message.gold); }
        static Bottle decode(FriendlyByteBuf buf) { return new Bottle(buf.readLong(), buf.readBoolean()); }
        static void handle(Bottle message, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> FryerClient.bottle(message.roundId, message.gold)));
            context.setPacketHandled(true);
        }
    }

    public record Outcome(long roundId, ZhangshaoKernel.Result result, String message) {
        static void encode(Outcome outcome, FriendlyByteBuf buf) {
            buf.writeLong(outcome.roundId).writeBoolean(outcome.result != null);
            if (outcome.result != null) buf.writeUtf(GSON.toJson(outcome.result), 16384);
            buf.writeUtf(outcome.message, 2048);
        }
        static Outcome decode(FriendlyByteBuf buf) {
            long round = buf.readLong();
            ZhangshaoKernel.Result result = buf.readBoolean()
                    ? GSON.fromJson(buf.readUtf(16384), ZhangshaoKernel.Result.class) : null;
            return new Outcome(round, result, buf.readUtf(2048));
        }
        static void handle(Outcome outcome, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> FryerClient.result(outcome.roundId, outcome.result, outcome.message)));
            context.setPacketHandled(true);
        }
    }
}
