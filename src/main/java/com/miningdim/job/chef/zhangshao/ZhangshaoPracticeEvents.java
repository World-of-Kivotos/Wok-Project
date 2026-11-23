package com.miningdim.job.chef.zhangshao;

import com.miningdim.core.MiningConstants;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
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

    private static int open(ServerPlayer player, int station) {
        ZhangshaoPracticeNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ZhangshaoPracticeS2C(station));
        return 1;
    }
}
