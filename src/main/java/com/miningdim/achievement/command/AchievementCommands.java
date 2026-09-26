package com.miningdim.achievement.command;

import com.miningdim.achievement.AchievementServices;
import com.miningdim.achievement.AchievementStoreException;
import com.miningdim.achievement.meta.AchievementConsistency;
import com.miningdim.achievement.meta.ConsistencyReport;
import com.miningdim.achievement.reward.AchievementReward;
import com.miningdim.achievement.reward.AchievementRewardService;
import com.miningdim.achievement.reward.AchievementRewardText;
import com.miningdim.achievement.reward.ClaimResult;
import com.miningdim.achievement.reward.PointBalance;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 成就命令 {@code /machievement} (Achievement_System_DesignSpec 第十一章)。
 *
 * 命令根不叫 /achievement, 与 /mtitle、/mchampion 一样各模块自带前缀。命令根本身不设权限, 由各子命令分别判断:
 * <ul>
 *   <li>玩家自助 (任何玩家): {@code claim <进度id|all>} 领取自己的奖励 (聊天里的 [领取] 按钮执行的就是它,
 *       进度 id 的补全候选取执行者自己的待领取奖励); 不带玩家参数的 {@code pending}、{@code points} 查看自己的待领取
 *       奖励与成就点。</li>
 *   <li>管理员 (权限等级 2): {@code pending <玩家>}、{@code points <玩家> [add|remove <数量>]}、{@code check}。
 *       玩家参数用 GameProfile, 用户缓存里查得到的离线玩家同样可以查看与调整。调整成就点的流水 reason 记 admin、
 *       ref 记执行者名字, 扣点最多扣到 0; 每次调整写管理日志 miningdim/achievement/admin。</li>
 * </ul>
 * 授予或撤销进度本身用原版的 {@code /advancement}, 本命令不重复实现。
 */
public final class AchievementCommands {

    /** 命令根。 */
    public static final String ROOT = "machievement";
    /** {@code claim all} 的字面量。 */
    public static final String ALL = "all";

    private static final int OP_LEVEL = 2;
    private static final String PREFIX = "achievement.miningdim.command.";
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/achievement");

    /**
     * claim 的进度 id 补全: 执行者自己的待领取奖励。补全请求在主线程的封包处理里执行, Brigadier 只吞语法异常,
     * 所以读库失败在这里就地降级为"没有候选", 不让一次补全把异常带进封包处理。
     */
    private static final SuggestionProvider<CommandSourceStack> OWN_PENDING = (context, builder) -> {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return builder.buildFuture();
        }
        List<AchievementReward> pending;
        try {
            pending = AchievementServices.rewards().pending(player.getUUID());
        } catch (AchievementStoreException failure) {
            LOGGER.warn("[miningdim] could not list pending achievement rewards of {} for completion",
                    player.getGameProfile().getName(), failure);
            return builder.buildFuture();
        }
        return SharedSuggestionProvider.suggestResource(pending.stream().map(AchievementReward::advancementId),
                builder);
    };

    private AchievementCommands() {
    }

    /** {@code /machievement claim <target>} 的完整命令串 (带斜杠), 供聊天按钮的 RUN_COMMAND 使用。 */
    public static String claimCommand(String target) {
        return "/" + ROOT + " claim " + target;
    }

    /** 由 {@code AchievementRewardSystem} 在 RegisterCommandsEvent 中注册。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(ROOT)
                .then(Commands.literal("claim")
                        .requires(CommandSourceStack::isPlayer)
                        .then(Commands.literal(ALL)
                                .executes(AchievementCommands::claimAll))
                        .then(Commands.argument("advancement", ResourceLocationArgument.id())
                                .suggests(OWN_PENDING)
                                .executes(AchievementCommands::claimOne)))
                .then(Commands.literal("pending")
                        .executes(AchievementCommands::pendingSelf)
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .requires(AchievementCommands::isAdmin)
                                .executes(AchievementCommands::pendingOf)))
                .then(Commands.literal("points")
                        .executes(AchievementCommands::pointsSelf)
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .requires(AchievementCommands::isAdmin)
                                .executes(AchievementCommands::pointsOf)
                                .then(Commands.literal("add")
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                .executes(AchievementCommands::addPoints)))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                .executes(AchievementCommands::removePoints)))))
                .then(Commands.literal("check")
                        .requires(AchievementCommands::isAdmin)
                        .executes(AchievementCommands::check)));
    }

    /** 管理员子命令的权限门: 权限等级 2。 */
    private static boolean isAdmin(CommandSourceStack source) {
        return source.hasPermission(OP_LEVEL);
    }

    // ---- 领取 ----

    private static int claimOne(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ResourceLocation advancementId = ResourceLocationArgument.getId(context, "advancement");
        return reportClaim(context.getSource(), AchievementRewardService.claim(player, advancementId), true);
    }

    private static int claimAll(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        return reportClaim(context.getSource(), AchievementRewardService.claimAll(player), false);
    }

    private static int reportClaim(CommandSourceStack source, ClaimResult result, boolean single) {
        MinecraftServer server = source.getServer();
        switch (result.status()) {
            case CLAIMED -> {
                PointBalance balance = result.balance();
                if (single) {
                    AchievementReward reward = result.claimed().get(0);
                    source.sendSuccess(() -> Component.translatable("achievement.miningdim.claim.done",
                            AchievementRewardText.advancementName(server, reward.advancementId()),
                            AchievementRewardText.summary(reward.points(), reward.titleId()),
                            balance.balance()), false);
                } else {
                    source.sendSuccess(() -> Component.translatable("achievement.miningdim.claim.done_all",
                            result.claimed().size(), result.points(), balance.balance()), false);
                }
            }
            case NOT_FOUND -> source.sendFailure(Component.translatable("achievement.miningdim.claim.not_found",
                    String.valueOf(result.advancementId())));
            case ALREADY_CLAIMED -> source.sendFailure(Component.translatable(
                    "achievement.miningdim.claim.already_claimed",
                    AchievementRewardText.advancementName(server, result.advancementId())));
            case NOTHING_PENDING -> source.sendFailure(Component.translatable(
                    "achievement.miningdim.reward.none_pending"));
            // 全部领取卡在某一条的称号上时 (7.1: 整次回滚), 告诉玩家其余奖励仍可逐条领取, 别一直点 [全部领取]。
            case TITLE_UNAVAILABLE -> source.sendFailure(Component.translatable(
                    single ? "achievement.miningdim.claim.title_unavailable"
                            : "achievement.miningdim.claim.title_unavailable_all",
                    AchievementRewardText.advancementName(server, result.advancementId()),
                    AchievementRewardText.titleBadge(result.titleId())));
            case STORE_FAILED -> source.sendFailure(Component.translatable("achievement.miningdim.claim.store_failed"));
        }
        return result.success() ? result.claimed().size() : 0;
    }

    // ---- 待领取 ----

    private static int pendingSelf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        List<AchievementReward> pending = AchievementServices.rewards().pending(player.getUUID());
        if (pending.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("achievement.miningdim.reward.none_pending"), false);
            return 1;
        }
        MutableComponent header = Component.translatable(PREFIX + "pending.header_self", pending.size())
                .append(" ").append(AchievementRewardText.claimAllButton());
        source.sendSuccess(() -> header, false);
        for (AchievementReward reward : pending) {
            Component entry = pendingEntry(source.getServer(), reward).append(" ")
                    .append(AchievementRewardText.claimButton(reward.advancementId()));
            source.sendSuccess(() -> entry, false);
        }
        return 1;
    }

    private static int pendingOf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(context, "targets");
        for (GameProfile target : targets) {
            List<AchievementReward> pending = AchievementServices.rewards().pending(target.getId());
            source.sendSuccess(() -> Component.translatable(PREFIX + "pending.header", target.getName(),
                    pending.size()), false);
            for (AchievementReward reward : pending) {
                Component entry = pendingEntry(source.getServer(), reward);
                source.sendSuccess(() -> entry, false);
            }
        }
        return targets.size();
    }

    private static MutableComponent pendingEntry(MinecraftServer server, AchievementReward reward) {
        return Component.translatable(PREFIX + "pending.entry",
                AchievementRewardText.advancementName(server, reward.advancementId()),
                AchievementRewardText.summary(reward.points(), reward.titleId()));
    }

    // ---- 成就点 ----

    private static int pointsSelf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        PointBalance points = AchievementServices.rewards().points(player.getUUID());
        source.sendSuccess(() -> Component.translatable(PREFIX + "points.show_self", points.balance(),
                points.lifetime()), false);
        return 1;
    }

    private static int pointsOf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(context, "targets");
        for (GameProfile target : targets) {
            PointBalance points = AchievementServices.rewards().points(target.getId());
            source.sendSuccess(() -> Component.translatable(PREFIX + "points.show", target.getName(),
                    points.balance(), points.lifetime()), false);
        }
        return targets.size();
    }

    private static int addPoints(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(context, "targets");
        int amount = IntegerArgumentType.getInteger(context, "amount");
        for (GameProfile target : targets) {
            PointBalance after = AchievementRewardService.addPoints(target.getId(), amount, source.getTextName());
            source.sendSuccess(() -> Component.translatable(PREFIX + "points.added", target.getName(), amount,
                    after.balance(), after.lifetime()), true);
        }
        return targets.size();
    }

    private static int removePoints(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(context, "targets");
        int amount = IntegerArgumentType.getInteger(context, "amount");
        int changed = 0;
        for (GameProfile target : targets) {
            UUID uuid = target.getId();
            long removed = AchievementRewardService.removePoints(uuid, amount, source.getTextName());
            if (removed <= 0L) {
                source.sendFailure(Component.translatable(PREFIX + "points.nothing_to_remove", target.getName()));
                continue;
            }
            changed++;
            long balance = AchievementServices.rewards().points(uuid).balance();
            String key = removed < amount ? "points.removed_clamped" : "points.removed";
            source.sendSuccess(() -> Component.translatable(PREFIX + key, target.getName(), removed, balance), true);
        }
        return changed;
    }

    // ---- 一致性校验 ----

    private static int check(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ConsistencyReport report = AchievementConsistency.check(server.getAdvancements().getAllAdvancements(),
                AchievementServices.catalog().metas(), AchievementConsistency::titleDefined);
        if (report.isClean()) {
            source.sendSuccess(() -> Component.translatable(PREFIX + "check.clean", report.checked()), false);
        } else {
            source.sendFailure(Component.translatable(PREFIX + "check.problems", report.problems().size(),
                    report.checked()));
            for (String problem : report.problems()) {
                source.sendFailure(Component.translatable(PREFIX + "check.problem", problem));
            }
        }
        if (!report.metaWithoutAdvancement().isEmpty()) {
            String orphans = report.metaWithoutAdvancement().stream().map(ResourceLocation::toString)
                    .collect(Collectors.joining(", "));
            source.sendSuccess(() -> Component.translatable(PREFIX + "check.meta_without_advancement",
                    report.metaWithoutAdvancement().size(), orphans), false);
        }
        return report.isClean() ? 1 : 0;
    }
}
