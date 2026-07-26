package com.miningdim.economy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OP 经济管理命令。{@code /economy grant <target> <credit> <azure>} 仅允许权限等级 2 及以上执行，
 * 通过 {@link IEconomyService#grantBundle} 原子发放双币，并把操作者、目标、金额及结果余额写入服务端日志。
 */
public final class EconomyCommands {

    private static final int OP_LEVEL = 2;
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/economy/admin");

    private EconomyCommands() {
    }

    /** 由 {@link EconomySystem} 在 RegisterCommandsEvent 中注册。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("economy")
                .requires(source -> source.hasPermission(OP_LEVEL))
                .then(Commands.literal("grant")
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("credit", LongArgumentType.longArg(1L))
                                        .then(Commands.argument("azure", LongArgumentType.longArg(1L))
                                                .executes(EconomyCommands::grant))))));
    }

    private static int grant(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        long creditAmount = LongArgumentType.getLong(context, "credit");
        long azureAmount = LongArgumentType.getLong(context, "azure");

        if (!EconomyServices.isRegistered()) {
            source.sendFailure(Component.translatable("message.miningdim.economy.not_ready"));
            return 0;
        }

        IEconomyService economy = EconomyServices.economyService();
        try {
            economy.grantBundle(target, creditAmount, azureAmount);
        } catch (EconomyException exception) {
            LOGGER.warn("[miningdim] economy admin grant rejected: issuer={} target={} targetUuid={} credit={} azure={} reason={}",
                    source.getTextName(), target.getGameProfile().getName(), target.getUUID(),
                    creditAmount, azureAmount, exception.reason());
            source.sendFailure(Component.translatable(
                    "message.miningdim.economy.grant_failed", exception.reason().name()));
            return 0;
        }

        long resultingCredit = economy.creditBalance(target);
        long resultingAzure = economy.heartstoneBalance(target);
        LOGGER.info("[miningdim] economy admin grant: issuer={} target={} targetUuid={} credit={} azure={} resultingCredit={} resultingAzure={}",
                source.getTextName(), target.getGameProfile().getName(), target.getUUID(),
                creditAmount, azureAmount, resultingCredit, resultingAzure);
        source.sendSuccess(() -> Component.translatable(
                "message.miningdim.economy.grant_done",
                target.getGameProfile().getName(), creditAmount, azureAmount, resultingCredit, resultingAzure), true);
        return 1;
    }
}
