package com.miningdim.quest;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * /quest 命令树 (第一阶段的玩家入口)。
 *
 * 槽位序号<b>对玩家是 1 起</b>而对内部是 0 起 —— 玩家看到的清单从 [1] 开始, 让他们敲 0 才能重摇第一个槽是
 * 反直觉的。转换只在本类做一次, 服务层一律 0 起。
 */
public final class QuestCommands {

    private QuestCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("quest")
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource())))
                .then(Commands.literal("claim")
                        .then(Commands.argument("id", StringArgumentType.string())
                                .executes(context -> claim(context.getSource(),
                                        StringArgumentType.getString(context, "id")))))
                .then(Commands.literal("turnin")
                        .then(Commands.argument("id", StringArgumentType.string())
                                .executes(context -> turnIn(context.getSource(),
                                        StringArgumentType.getString(context, "id")))))
                .then(Commands.literal("refresh")
                        .then(Commands.literal("daily")
                                .then(Commands.argument("slot", IntegerArgumentType.integer(1))
                                        .executes(context -> refresh(context.getSource(), QuestSource.DAILY,
                                                IntegerArgumentType.getInteger(context, "slot")))))
                        .then(Commands.literal("weekly")
                                .then(Commands.argument("slot", IntegerArgumentType.integer(1))
                                        .executes(context -> refresh(context.getSource(), QuestSource.WEEKLY,
                                                IntegerArgumentType.getInteger(context, "slot")))))));
    }

    private static int list(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!requireActive(source)) {
            return 0;
        }
        QuestBoard board = QuestServices.service().boardOf(player);
        source.sendSuccess(() -> Component.literal("=== 任务板 ===").withStyle(ChatFormatting.GOLD), false);
        section(source, "每日 (重摇 " + QuestRewards.refreshCost(QuestSource.DAILY) + " 信用点)", board.daily());
        section(source, "每周 (重摇 " + QuestRewards.refreshCost(QuestSource.WEEKLY) + " 信用点)", board.weekly());
        section(source, "特殊", board.special());

        for (QuestChainState state : board.chains()) {
            if (state.finished()) {
                source.sendSuccess(() -> Component.literal("[任务线] " + state.chain().title() + " 已完成")
                        .withStyle(ChatFormatting.DARK_GREEN), false);
                continue;
            }
            QuestProgress current = state.current();
            source.sendSuccess(() -> Component.literal("[任务线] " + state.chain().title()
                            + " 阶段 " + (state.stageIndex() + 1) + "/" + state.chain().stageCount())
                    .withStyle(ChatFormatting.GOLD), false);
            source.sendSuccess(() -> Component.literal("  " + line(current)), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void section(CommandSourceStack source, String title, List<QuestProgress> progresses) {
        if (progresses.isEmpty()) {
            return;
        }
        source.sendSuccess(() -> Component.literal("[" + title + "]").withStyle(ChatFormatting.YELLOW), false);
        for (int i = 0; i < progresses.size(); i++) {
            QuestProgress progress = progresses.get(i);
            String prefix = "  [" + (i + 1) + "] ";
            source.sendSuccess(() -> Component.literal(prefix + line(progress))
                    .withStyle(progress.isComplete() && !progress.claimed()
                            ? ChatFormatting.GREEN : ChatFormatting.WHITE), false);
        }
    }

    private static String line(QuestProgress progress) {
        QuestDefinition definition = progress.definition();
        String state = progress.claimed() ? "已领取"
                : progress.isComplete() ? "可领取 (/quest claim " + definition.id() + ")"
                : progress.count() + "/" + progress.requiredCount();
        return definition.title() + " - " + definition.objective().describe()
                + " - " + state + " - 奖励 " + QuestRewards.creditFor(definition) + " 信用点";
    }

    private static int claim(CommandSourceStack source, String questId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!requireActive(source)) {
            return 0;
        }
        QuestService.ClaimResult result = QuestServices.service().claim(player, questId);
        switch (result.outcome()) {
            case CLAIMED -> source.sendSuccess(() -> Component.literal(
                            "领取成功: " + result.definition().title() + " (+" + result.credit() + " 信用点)")
                    .withStyle(ChatFormatting.GREEN), false);
            // 实发额低于名义奖励是全服 faucet 衰减主闸的正常结果, 不是错误, 故仍走成功分支。
            case NOT_FOUND -> source.sendFailure(Component.literal("任务板上没有这条任务: " + questId));
            case NOT_COMPLETE -> source.sendFailure(Component.literal("任务尚未完成: " + result.definition().title()));
            case ALREADY_CLAIMED -> source.sendFailure(Component.literal("奖励已经领过了: " + result.definition().title()));
        }
        return result.outcome() == QuestService.ClaimOutcome.CLAIMED ? Command.SINGLE_SUCCESS : 0;
    }

    /**
     * 上交物品。一次尽可能多交 (按剩余需求裁剪), 而不是一次一个 —— 交 64 个腐肉要敲 64 次命令是纯粹的折磨。
     */
    private static int turnIn(CommandSourceStack source, String questId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!requireActive(source)) {
            return 0;
        }
        QuestService.TurnInResult result = QuestServices.service().turnIn(player, questId);
        switch (result.outcome()) {
            case TURNED_IN -> source.sendSuccess(() -> Component.literal(
                    "已上交 x" + result.count() + ": " + line(
                            QuestServices.service().boardOf(player).find(questId))), false);
            case NOT_FOUND -> source.sendFailure(Component.literal("任务板上没有这条任务: " + questId));
            case NOT_A_TURN_IN -> source.sendFailure(Component.literal(
                    "这条任务不是上交类: " + result.definition().title()));
            case ALREADY_COMPLETE -> source.sendFailure(Component.literal(
                    "已经交够了: " + result.definition().title() + " —— 用 /quest claim " + questId + " 领奖"));
            case NOTHING_TO_TURN_IN -> source.sendFailure(Component.literal(
                    "背包里没有可上交的物品: " + result.definition().objective().describe()));
        }
        return result.outcome() == QuestService.TurnInOutcome.TURNED_IN ? Command.SINGLE_SUCCESS : 0;
    }

    private static int refresh(CommandSourceStack source, QuestSource questSource, int playerFacingSlot)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!requireActive(source)) {
            return 0;
        }
        QuestBoard board = QuestServices.service().boardOf(player);
        int slots = questSource == QuestSource.DAILY ? board.daily().size() : board.weekly().size();
        if (playerFacingSlot > slots) {
            source.sendFailure(Component.literal("没有这个槽位, 当前只有 " + slots + " 个"));
            return 0;
        }
        QuestService.RefreshResult result =
                QuestServices.service().refresh(player, questSource, playerFacingSlot - 1);
        if (result.outcome() == QuestService.RefreshOutcome.NOT_ENOUGH_CREDIT) {
            source.sendFailure(Component.literal("信用点不足, 重摇需要 " + result.cost()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已重摇 (-" + result.cost() + " 信用点): "
                + line(result.replacement())).withStyle(ChatFormatting.AQUA), false);
        return Command.SINGLE_SUCCESS;
    }

    /** 任务系统关停时给出明确回执, 而不是让玩家对着一块空板猜是不是自己没任务。 */
    private static boolean requireActive(CommandSourceStack source) {
        if (QuestServices.active()) {
            return true;
        }
        source.sendFailure(Component.literal("任务系统当前未启用"));
        return false;
    }
}
