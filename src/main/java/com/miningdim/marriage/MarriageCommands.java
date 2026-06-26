package com.miningdim.marriage;

import com.miningdim.config.MiningServerConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * /marriage 命令树 (结婚系统 spec 第二/三章, Brigadier; 仿 {@link com.miningdim.job.JobCommands} 独立根)。
 * 独立根不挂 /mining 下 (避免 Brigadier 双根冲突, 与 /job 同纪律)。
 *
 * 子命令:
 *  - /marriage buyring          买一枚订婚戒指 (tryCharge engagementCost)
 *  - /marriage propose &lt;player&gt;  向对方表达订婚意向 (登记意向)
 *  - /marriage accept &lt;player&gt;   接受对方的求婚 (对方须先 propose 你)
 *  - /marriage wed &lt;player&gt;      双方在场办典礼 (各付一半 weddingCost, 事务性; 成功登记关系 + 换结婚戒指)
 *  - /marriage divorce          离婚 (扣 divorceCost + 共享背包清算退回发起方 + 再婚冷却递增; spec 第六章)
 *
 * 命令只做参数解析 + 委派 {@link MarriageEngine} / {@link MarriageProposals} / {@link MarriageDivorce}; 成本数值实时读
 * {@link MiningServerConfig} (不缓存)。业务异常自然冒泡, 用户输入错误在此兜底 sendFailure。
 */
public final class MarriageCommands {

    private final MarriageProposals proposals;
    private final MarriageBackpackSessions backpackSessions;

    MarriageCommands(MarriageProposals proposals, MarriageBackpackSessions backpackSessions) {
        this.proposals = proposals;
        this.backpackSessions = backpackSessions;
    }

    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("marriage")
                .then(Commands.literal("buyring")
                        .executes(this::buyRing))
                .then(Commands.literal("propose")
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(this::propose)))
                .then(Commands.literal("accept")
                        .then(Commands.argument("proposer", EntityArgument.player())
                                .executes(this::accept)))
                .then(Commands.literal("wed")
                        .then(Commands.argument("partner", EntityArgument.player())
                                .executes(this::wed)))
                .then(Commands.literal("divorce")
                        .executes(this::divorce));
        dispatcher.register(root);
    }

    private MarriageEngine engineFor(CommandSourceStack source) {
        ServerLevel overworld = source.getServer().overworld();
        return new MarriageEngine(overworld);
    }

    private int buyRing(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        long cost = MiningServerConfig.MARRIAGE_ENGAGEMENT_COST.get();
        boolean bought = engineFor(ctx.getSource()).buyEngagementRing(player, cost);
        if (!bought) {
            ctx.getSource().sendFailure(Component.translatable("message.miningdim.marriage.buyring.insufficient", cost));
            return 0;
        }
        ctx.getSource().sendSuccess(() ->
                Component.translatable("message.miningdim.marriage.buyring.done", cost), false);
        return 1;
    }

    private int propose(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer proposer = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        if (proposer.getUUID().equals(target.getUUID())) {
            ctx.getSource().sendFailure(Component.translatable("message.miningdim.marriage.self"));
            return 0;
        }
        proposals.propose(proposer.getUUID(), target.getUUID());
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "message.miningdim.marriage.propose.sent", target.getGameProfile().getName()), false);
        // 提示对方有人求婚 (actionbar=false, 普通聊天提示)。
        target.sendSystemMessage(Component.translatable(
                "message.miningdim.marriage.propose.received", proposer.getGameProfile().getName()));
        return 1;
    }

    private int accept(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer accepter = ctx.getSource().getPlayerOrException();
        ServerPlayer proposer = EntityArgument.getPlayer(ctx, "proposer");
        if (!proposals.accept(proposer.getUUID(), accepter.getUUID())) {
            ctx.getSource().sendFailure(Component.translatable(
                    "message.miningdim.marriage.accept.no_proposal", proposer.getGameProfile().getName()));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "message.miningdim.marriage.accept.done", proposer.getGameProfile().getName()), false);
        proposer.sendSystemMessage(Component.translatable(
                "message.miningdim.marriage.accept.notify", accepter.getGameProfile().getName()));
        return 1;
    }

    private int wed(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer initiator = ctx.getSource().getPlayerOrException();
        ServerPlayer partner = EntityArgument.getPlayer(ctx, "partner");

        // 婚约意向须已被接受 (任一方向): initiator->partner 或 partner->initiator。
        boolean accepted = proposals.isAccepted(initiator.getUUID(), partner.getUUID())
                || proposals.isAccepted(partner.getUUID(), initiator.getUUID());
        if (!accepted) {
            ctx.getSource().sendFailure(Component.translatable(
                    "message.miningdim.marriage.wed.no_accepted_proposal", partner.getGameProfile().getName()));
            return 0;
        }

        long totalCost = MiningServerConfig.MARRIAGE_WEDDING_COST.get();
        // officiant: 命令发起者作为典礼记录的 source 不充当证婚人 (证婚人机制 spec 第十二章 PENDING), 此处传 null。
        MarriageEngine.WeddingResult result = engineFor(ctx.getSource()).wed(initiator, partner, totalCost, null);
        if (!result.success()) {
            ctx.getSource().sendFailure(weddingFailureMessage(result.reason(), partner));
            return 0;
        }
        // 典礼成功: 清掉双方残留意向, 广播。
        proposals.clear(initiator.getUUID());
        proposals.clear(partner.getUUID());
        ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(
                Component.translatable("message.miningdim.marriage.wed.broadcast",
                        initiator.getGameProfile().getName(), partner.getGameProfile().getName()),
                false);
        return 1;
    }

    private int divorce(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer initiator = ctx.getSource().getPlayerOrException();
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        long cost = MiningServerConfig.MARRIAGE_DIVORCE_COST.get();
        MarriageDivorce.Result result =
                new MarriageDivorce(overworld, backpackSessions).divorce(initiator, cost);
        switch (result) {
            case OK -> {
                ctx.getSource().sendSuccess(() ->
                        Component.translatable("message.miningdim.marriage.divorce.done"), false);
                return 1;
            }
            case NOT_MARRIED -> {
                ctx.getSource().sendFailure(Component.translatable("message.miningdim.marriage.not_married"));
                return 0;
            }
            case INSUFFICIENT_FUNDS -> {
                ctx.getSource().sendFailure(
                        Component.translatable("message.miningdim.marriage.divorce.insufficient", cost));
                return 0;
            }
            case NO_ECONOMY -> {
                ctx.getSource().sendFailure(Component.translatable("message.miningdim.marriage.wed.no_economy"));
                return 0;
            }
            default -> {
                return 0;
            }
        }
    }

    private static Component weddingFailureMessage(MarriageEngine.Reason reason, ServerPlayer partner) {
        return switch (reason) {
            case SELF_MARRIAGE -> Component.translatable("message.miningdim.marriage.self");
            case ALREADY_MARRIED -> Component.translatable("message.miningdim.marriage.wed.already_married");
            case NO_ENGAGEMENT_RING -> Component.translatable("message.miningdim.marriage.wed.no_ring");
            case INSUFFICIENT_FUNDS -> Component.translatable("message.miningdim.marriage.wed.insufficient");
            case NO_ECONOMY -> Component.translatable("message.miningdim.marriage.wed.no_economy");
            case REMARRY_COOLDOWN -> Component.translatable("message.miningdim.marriage.wed.remarry_cooldown");
            case OK -> Component.translatable("message.miningdim.marriage.wed.broadcast",
                    partner.getGameProfile().getName());
        };
    }
}
