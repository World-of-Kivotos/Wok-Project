package com.miningdim.job.chef.zhangshao;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.chef.station.CookingStationBlock;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MiningConstants.MODID)
public final class ZhangshaoPracticeEvents {
    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> practice = Commands.literal("practice")
                .requires(source -> source.hasPermission(2))
                .executes(context -> open(context.getSource().getPlayerOrException(), 0));
        String[] names = {"pot", "fryer", "oven", "prep"};
        for (int station = 0; station < names.length; station++) {
            int selected = station;
            practice.then(Commands.literal(names[station])
                    .executes(context -> open(context.getSource().getPlayerOrException(), selected)));
        }
        event.getDispatcher().register(Commands.literal("chef").then(practice));
    }

    @SubscribeEvent
    public static void rightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        int station = practiceStation(event.getLevel().getBlockState(event.getPos()), event.getEntity());
        if (station < 0) {
            return;
        }
        // 客户端也必须消费交互，否则原版会继续使用手持物品并另发 UseItem 包。
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!event.getLevel().isClientSide) {
            open((ServerPlayer) event.getEntity(), station);
        }
    }

    /** 台子先开选择界面；潜行放置和创造模式外观预览交给原版。 */
    public static int practiceStation(BlockState state, Player player) {
        if (state.getBlock() instanceof CookingStationBlock station) {
            if (player.isShiftKeyDown() && (player.getMainHandItem().getItem() instanceof BlockItem
                    || player.getOffhandItem().getItem() instanceof BlockItem)) {
                return -1;
            }
            if (player.isCreative() && player.isShiftKeyDown()
                    && player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty()) {
                return -1;
            }
            return switch (station.kind()) {
                case DEEP_FRYER -> -1;
                case BAKING_OVEN -> 2;
                case PREP_COUNTER -> 3;
            };
        }
        return -1;
    }

    private static int open(ServerPlayer player, int station) {
        ZhangshaoPracticeNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ZhangshaoPracticeS2C(station));
        return 1;
    }
}
