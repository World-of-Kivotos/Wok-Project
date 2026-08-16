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

import java.util.UUID;

/**
 * /marriage 命令树 (结婚系统 spec 第二/三章, Brigadier; 仿 {@link com.miningdim.job.JobCommands} 独立根)。
 * 独立根不挂 /mining 下 (避免 Brigadier 双根冲突, 与 /job 同纪律)。
 *
 * 子命令:
 *  - /marriage buyring          买一枚订婚戒指 (tryCharge engagementCost)
 *  - /marriage propose &lt;player&gt;  向对方表达订婚意向 (登记意向)
 *  - /marriage accept &lt;player&gt;   接受对方的求婚 (对方须先 propose 你)
 *  - /marriage reject &lt;player&gt;   拒绝对方指向自己的求婚 (F098)
 *  - /marriage withdraw          撤回自己发出的 outgoing 求婚意向 (F098)
 *  - /marriage wed &lt;player&gt;      双方在场办典礼 (各付一半 weddingCost, 事务性; 成功登记关系 + 换结婚戒指)
 *  - /marriage divorce           提交离婚 (扣 divorceCost, 进公示期; 已在公示期中则幂等回执不二次扣费; spec 第六章)
 *  - /marriage divorce cancel    发起方在公示期内撤回, 全额退款
 *  - /marriage divorce confirm   配偶提前确认使公示期立即生效 (到期不确认也会自动生效, 见 finalizeMatured)
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
                .then(Commands.literal("reject")
                        .then(Commands.argument("proposer", EntityArgument.player())
                                .executes(this::reject)))
                .then(Commands.literal("withdraw")
                        .executes(this::withdraw))
                .then(Commands.literal("wed")
                        .then(Commands.argument("partner", EntityArgument.player())
                                .executes(this::wed)))
                .then(Commands.literal("divorce")
                        .executes(this::divorceFile)
                        .then(Commands.literal("cancel")
                                .executes(this::divorceCancel))
                        .then(Commands.literal("confirm")
                                .executes(this::divorceConfirm)));
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

    /** F098: 拒绝对方指向自己的求婚 (不按 proposer 裸删, 只删确实指向本人的那一条; 见 MarriageProposals#rejectFrom)。 */
    private int reject(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer rejecter = ctx.getSource().getPlayerOrException();
        ServerPlayer proposer = EntityArgument.getPlayer(ctx, "proposer");
        if (!proposals.rejectFrom(proposer.getUUID(), rejecter.getUUID())) {
            ctx.getSource().sendFailure(Component.translatable(
                    "message.miningdim.marriage.reject.none", proposer.getGameProfile().getName()));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "message.miningdim.marriage.reject.done", proposer.getGameProfile().getName()), false);
        proposer.sendSystemMessage(Component.translatable(
                "message.miningdim.marriage.reject.notify", rejecter.getGameProfile().getName()));
        return 1;
    }

    /** F098: 撤回自己发出的 outgoing 求婚意向。 */
    private int withdraw(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer proposer = ctx.getSource().getPlayerOrException();
        UUID target = proposals.withdraw(proposer.getUUID());
        if (target == null) {
            ctx.getSource().sendFailure(Component.translatable("message.miningdim.marriage.withdraw.none"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("message.miningdim.marriage.withdraw.done"), false);
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

    /** /marriage divorce (无子命令): 提交离婚, 进公示期; 已在公示期中时幂等回执, 不二次扣费。 */
    private int divorceFile(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer initiator = ctx.getSource().getPlayerOrException();
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        long cost = MiningServerConfig.MARRIAGE_DIVORCE_COST.get();
        MarriageDivorce.Filing filing = new MarriageDivorce(overworld, backpackSessions).file(initiator, cost);
        switch (filing.result()) {
            case OK -> {
                long remainingSeconds = Math.max(0L, filing.effectiveAtTick() - overworld.getGameTime()) / 20L;
                if (filing.alreadyPending()) {
                    ctx.getSource().sendFailure(Component.translatable(
                            "message.miningdim.marriage.divorce.already_pending", remainingSeconds));
                    return 0;
                }
                ctx.getSource().sendSuccess(() ->
                        Component.translatable("message.miningdim.marriage.divorce.filed", remainingSeconds), false);
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

    /** /marriage divorce cancel: 发起方在公示期内撤回, 全额退回提交时扣的成本, 关系照旧存续。 */
    private int divorceCancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer actor = ctx.getSource().getPlayerOrException();
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        MarriageDivorce.PendingAction result = new MarriageDivorce(overworld, backpackSessions).cancel(actor);
        if (result != MarriageDivorce.PendingAction.OK) {
            ctx.getSource().sendFailure(pendingActionFailureMessage(result));
            return 0;
        }
        ctx.getSource().sendSuccess(() ->
                Component.translatable("message.miningdim.marriage.divorce.cancelled"), false);
        return 1;
    }

    /** /marriage divorce confirm: 配偶 (非发起方那一位) 同意提前生效; 到期不确认也会由 finalizeMatured 自动生效。 */
    private int divorceConfirm(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer actor = ctx.getSource().getPlayerOrException();
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        MarriageDivorce.PendingAction result = new MarriageDivorce(overworld, backpackSessions).confirm(actor);
        if (result != MarriageDivorce.PendingAction.OK) {
            ctx.getSource().sendFailure(pendingActionFailureMessage(result));
            return 0;
        }
        ctx.getSource().sendSuccess(() ->
                Component.translatable("message.miningdim.marriage.divorce.done"), false);
        return 1;
    }

    /** cancel/confirm 共用的失败文案映射 (PendingAction 六态之五; OK 不是失败, 调用方须先排除)。 */
    private static Component pendingActionFailureMessage(MarriageDivorce.PendingAction action) {
        return switch (action) {
            case NOT_MARRIED -> Component.translatable("message.miningdim.marriage.not_married");
            case NOT_PENDING -> Component.translatable("message.miningdim.marriage.divorce.not_pending");
            case NOT_INITIATOR -> Component.translatable("message.miningdim.marriage.divorce.not_initiator");
            case NOT_SPOUSE -> Component.translatable("message.miningdim.marriage.divorce.not_spouse");
            case NO_ECONOMY -> Component.translatable("message.miningdim.marriage.wed.no_economy");
            case OK -> throw new IllegalArgumentException("OK is not a failure result");
        };
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
