package com.miningdim.job.chef.zhangshao;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.chef.station.CookingStationBlock;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MiningConstants.MODID)
public final class ZhangshaoPracticeEvents {
    private static final ResourceLocation FARMERS_POT = new ResourceLocation("farmersdelight", "cooking_pot");

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> practice = Commands.literal("practice")
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
        if (event.getLevel().isClientSide || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        int station = practiceStation(event.getLevel().getBlockState(event.getPos()), event.getEntity());
        if (station < 0) {
            return;
        }
        open((ServerPlayer) event.getEntity(), station);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /** 保留创造模式的外观预览操作；农夫乐事厨锅只在空手潜行时进入练习。 */
    public static int practiceStation(BlockState state, Player player) {
        if (state.getBlock() instanceof CookingStationBlock station) {
            if (player.isCreative() && player.isShiftKeyDown()
                    && player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty()) {
                return -1;
            }
            return switch (station.kind()) {
                case DEEP_FRYER -> 1;
                case BAKING_OVEN -> 2;
                case PREP_COUNTER -> 3;
            };
        }
        if (FARMERS_POT.equals(ForgeRegistries.BLOCKS.getKey(state.getBlock()))
                && player.isShiftKeyDown() && player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty()) {
            return 0;
        }
        return -1;
    }

    private static int open(ServerPlayer player, int station) {
        ZhangshaoPracticeNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ZhangshaoPracticeS2C(station));
        return 1;
    }
}
