package com.miningdim.title;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /mtitle custom ...} 与 {@code /mtitle sponsor ...} 两棵子命令树 (Title_System_DesignSpec 13.7), 由
 * {@link TitleCommands} 挂到命令根下。
 *
 * <ul>
 *   <li>玩家: {@code custom preview|set <颜色> <粗体> <文字>}、{@code custom info}。预览与提交要求赞助资格有效,
 *       由服务端在执行时判断并给出明确提示; info 对所有玩家开放, 资格失效的玩家也能看到到期时间与保留的称号。</li>
 *   <li>管理员 (权限等级 2): {@code custom admin set|reset|lock|unlock|cooldown <玩家>} 与
 *       {@code sponsor grant <玩家> [天数] | revoke | info <玩家> | list}。</li>
 * </ul>
 * {@code <颜色> <粗体> <文字>} 收在一个贪婪字符串参数里, 由 {@link CustomTitleDraft#parse} 拆分 (理由见该方法)。
 * 玩家参数用 GameProfile, 离线玩家同样可以处置。变更的审计日志由服务层统一写 (miningdim/title/custom)。
 */
final class CustomTitleCommands {

    /** 单次发放赞助资格的天数上限 (一百年), 只为挡住误输入的天文数字。 */
    private static final int MAX_SPONSOR_DAYS = 36_500;

    private CustomTitleCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> customNode() {
        return Commands.literal("custom")
                .then(Commands.literal("preview")
                        .requires(CommandSourceStack::isPlayer)
                        .then(Commands.argument("spec", StringArgumentType.greedyString())
                                .executes(CustomTitleCommands::preview)))
                .then(Commands.literal("set")
                        .requires(CommandSourceStack::isPlayer)
                        .then(Commands.argument("spec", StringArgumentType.greedyString())
                                .executes(CustomTitleCommands::set)))
                .then(Commands.literal("info")
                        .requires(CommandSourceStack::isPlayer)
                        .executes(CustomTitleCommands::info))
                .then(Commands.literal("admin")
                        .requires(TitleCommands::isAdmin)
                        .then(Commands.literal("set")
                                .then(targets()
                                        .then(Commands.argument("spec", StringArgumentType.greedyString())
                                                .executes(CustomTitleCommands::adminSet))))
                        .then(Commands.literal("reset")
                                .then(targets().executes(context -> adminAction(context, AdminAction.RESET))))
                        .then(Commands.literal("lock")
                                .then(targets().executes(context -> adminAction(context, AdminAction.LOCK))))
                        .then(Commands.literal("unlock")
                                .then(targets().executes(context -> adminAction(context, AdminAction.UNLOCK))))
                        .then(Commands.literal("cooldown")
                                .then(targets().executes(context -> adminAction(context, AdminAction.COOLDOWN)))));
    }

    static LiteralArgumentBuilder<CommandSourceStack> sponsorNode() {
        return Commands.literal("sponsor")
                .requires(TitleCommands::isAdmin)
                .then(Commands.literal("grant")
                        .then(targets()
                                .executes(context -> sponsorGrant(context, null))
                                .then(Commands.argument("days", IntegerArgumentType.integer(1, MAX_SPONSOR_DAYS))
                                        .executes(context -> sponsorGrant(context,
                                                IntegerArgumentType.getInteger(context, "days"))))))
                .then(Commands.literal("revoke")
                        .then(targets().executes(CustomTitleCommands::sponsorRevoke)))
                .then(Commands.literal("info")
                        .then(targets().executes(CustomTitleCommands::sponsorInfo)))
                .then(Commands.literal("list")
                        .executes(CustomTitleCommands::sponsorList));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, GameProfileArgument.Result> targets() {
        return Commands.argument("targets", GameProfileArgument.gameProfile());
    }

    // ---- 玩家 ----

    private static int preview(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ITitleService titles = TitleCommands.serviceOrFail(source);
        if (titles == null) {
            return 0;
        }
        CustomTitleDraft draft = parseOrFail(source, StringArgumentType.getString(context, "spec"));
        if (draft == null) {
            return 0;
        }
        CustomTitleResult result = titles.previewCustomTitle(player.getUUID(), draft);
        if (result.status() != CustomTitleResult.Status.VALID) {
            reportRejection(source, result, Component.translatable("title.miningdim.custom.invalid.header"));
            return 0;
        }
        // 回显"玩家名 + 称号"的实际效果, 只发给执行者本人。
        Component prefix = TitleRenderer.Rendered.of(TitleDefinition.custom(CustomTitle.idOf(player.getUUID()),
                result.style())).prefix();
        Component shown = Component.empty().append(prefix).append(player.getName());
        source.sendSuccess(() -> Component.translatable("title.miningdim.custom.preview", shown), false);
        if (result.nextEditAt() != 0L) {
            source.sendSuccess(() -> Component.translatable("title.miningdim.custom.preview_cooldown",
                    TitleText.time(result.nextEditAt())), false);
        }
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ITitleService titles = TitleCommands.serviceOrFail(source);
        if (titles == null) {
            return 0;
        }
        CustomTitleDraft draft = parseOrFail(source, StringArgumentType.getString(context, "spec"));
        if (draft == null) {
            return 0;
        }
        CustomTitleResult result = titles.setCustomTitle(player, draft);
        if (result.status() != CustomTitleResult.Status.APPLIED) {
            reportRejection(source, result, Component.translatable("title.miningdim.custom.invalid.header"));
            return 0;
        }
        ResourceLocation customId = CustomTitle.idOf(player.getUUID());
        source.sendSuccess(() -> Component.translatable("title.miningdim.custom.applied",
                TitleCommands.shown(titles, customId), TitleText.time(result.nextEditAt())), false);
        if (!titles.equipped(player.getUUID()).map(customId::equals).orElse(false)) {
            String command = "/mtitle wear " + customId;
            Component clickable = Component.literal(command).withStyle(style -> style
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.translatable("title.miningdim.command.mine.click_to_wear"))));
            source.sendSuccess(() -> Component.translatable("title.miningdim.custom.wear_hint", clickable), false);
        }
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ITitleService titles = TitleCommands.serviceOrFail(source);
        if (titles == null) {
            return 0;
        }
        CustomTitleInfo info = titles.customTitleInfo(player.getUUID());
        source.sendSuccess(() -> Component.translatable("title.miningdim.custom.info.header_self"), false);
        sendInfoLines(source, titles, info, false);
        return 1;
    }

    // ---- 管理员: 专属称号 ----

    private static int adminSet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(context, "targets");
        ITitleService titles = TitleCommands.serviceOrFail(source);
        if (titles == null) {
            return 0;
        }
        CustomTitleDraft draft = parseOrFail(source, StringArgumentType.getString(context, "spec"));
        if (draft == null) {
            return 0;
        }
        int applied = 0;
        for (GameProfile target : targets) {
            CustomTitleResult result = titles.adminSetCustomTitle(target.getId(), draft, source.getTextName());
            if (result.status() == CustomTitleResult.Status.APPLIED) {
                applied++;
                Component shown = TitleCommands.shown(titles, CustomTitle.idOf(target.getId()));
                source.sendSuccess(() -> Component.translatable("title.miningdim.command.custom.admin.set",
                        target.getName(), shown), true);
            } else {
                reportRejection(source, result, Component.translatable(
                        "title.miningdim.command.custom.admin.invalid", target.getName()));
            }
        }
        return applied;
    }

    private static int adminAction(CommandContext<CommandSourceStack> context, AdminAction action)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(context, "targets");
        ITitleService titles = TitleCommands.serviceOrFail(source);
        if (titles == null) {
            return 0;
        }
        int done = 0;
        for (GameProfile target : targets) {
            CustomAdminResult result = action.apply(titles, target.getId(), source.getTextName());
            String name = target.getName();
            switch (result) {
                case DONE -> {
                    done++;
                    source.sendSuccess(() -> Component.translatable(action.doneKey, name), true);
                }
                case NO_CUSTOM_TITLE -> source.sendFailure(Component.translatable(action.noCustomKey, name));
                case UNCHANGED -> source.sendFailure(Component.translatable(action.unchangedKey, name));
                case LOCKED -> source.sendFailure(Component.translatable(
                        "title.miningdim.command.custom.admin.reset_locked", name));
            }
        }
        return done;
    }

    // ---- 管理员: 赞助资格 ----

    private static int sponsorGrant(CommandContext<CommandSourceStack> context, @Nullable Integer days)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(context, "targets");
        ITitleService titles = TitleCommands.serviceOrFail(source);
        if (titles == null) {
            return 0;
        }
        for (GameProfile target : targets) {
            SponsorStatus status = titles.grantSponsor(target.getId(), days, source.getTextName());
            String name = target.getName();
            if (!status.permanent()) {
                source.sendSuccess(() -> Component.translatable("title.miningdim.command.sponsor.granted_until",
                        name, TitleText.time(status.expiresAt())), true);
            } else if (days == null) {
                source.sendSuccess(() -> Component.translatable(
                        "title.miningdim.command.sponsor.granted_permanent", name), true);
            } else {
                source.sendSuccess(() -> Component.translatable(
                        "title.miningdim.command.sponsor.kept_permanent", name), true);
            }
        }
        return targets.size();
    }

    private static int sponsorRevoke(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(context, "targets");
        ITitleService titles = TitleCommands.serviceOrFail(source);
        if (titles == null) {
            return 0;
        }
        int revoked = 0;
        for (GameProfile target : targets) {
            String name = target.getName();
            if (titles.revokeSponsor(target.getId(), source.getTextName())) {
                revoked++;
                source.sendSuccess(() -> Component.translatable("title.miningdim.command.sponsor.revoked", name), true);
            } else {
                source.sendFailure(Component.translatable("title.miningdim.command.sponsor.not_sponsor", name));
            }
        }
        return revoked;
    }

    private static int sponsorInfo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(context, "targets");
        ITitleService titles = TitleCommands.serviceOrFail(source);
        if (titles == null) {
            return 0;
        }
        for (GameProfile target : targets) {
            CustomTitleInfo info = titles.customTitleInfo(target.getId());
            source.sendSuccess(() -> Component.translatable("title.miningdim.command.sponsor.info.header",
                    target.getName()), false);
            SponsorStatus sponsor = info.sponsor();
            if (sponsor != null) {
                source.sendSuccess(() -> Component.translatable("title.miningdim.command.sponsor.info.granted",
                        TitleText.time(sponsor.grantedAt()), sponsor.grantedBy()), false);
            }
            sendInfoLines(source, titles, info, true);
        }
        return targets.size();
    }

    private static int sponsorList(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ITitleService titles = TitleCommands.serviceOrFail(source);
        if (titles == null) {
            return 0;
        }
        List<CustomTitleInfo> sponsors = titles.sponsors();
        if (sponsors.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("title.miningdim.command.sponsor.list.empty"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("title.miningdim.command.sponsor.list.header",
                sponsors.size()), false);
        for (CustomTitleInfo info : sponsors) {
            String name = TitleText.playerName(source.getServer(), info.player());
            Component status = sponsorStatus(info);
            source.sendSuccess(() -> Component.translatable("title.miningdim.command.sponsor.list.entry",
                    name, status), false);
        }
        return sponsors.size();
    }

    // ---- 共用 ----

    @Nullable
    private static CustomTitleDraft parseOrFail(CommandSourceStack source, String spec) {
        Optional<CustomTitleDraft> draft = CustomTitleDraft.parse(spec);
        if (draft.isEmpty()) {
            source.sendFailure(Component.translatable("title.miningdim.custom.usage"));
        }
        return draft.orElse(null);
    }

    /**
     * 预览 / 提交 / 代设置被拒时的反馈 (只处理四种拒绝结果); 校验不合格时先发 invalidHeader, 再逐条列出
     * 不合格项。
     */
    private static void reportRejection(CommandSourceStack source, CustomTitleResult result, Component invalidHeader) {
        switch (result.status()) {
            case NOT_SPONSOR -> source.sendFailure(Component.translatable("title.miningdim.custom.not_sponsor"));
            case LOCKED -> source.sendFailure(Component.translatable("title.miningdim.custom.locked"));
            case ON_COOLDOWN -> source.sendFailure(Component.translatable("title.miningdim.custom.cooldown",
                    TitleText.time(result.nextEditAt())));
            case INVALID -> {
                source.sendFailure(invalidHeader);
                for (CustomTitleViolation violation : result.violations()) {
                    source.sendFailure(Component.literal("- ").append(violation.message()));
                }
            }
        }
    }

    /**
     * custom info 与 sponsor info 共用的状态行: 赞助资格、专属称号、锁定、下次可修改时间。
     *
     * @param staffView 管理员视角 (sponsor info) 才写出锁定的执行者; 玩家看自己时只说"已被管理员锁定", 与其他
     *                  面向玩家的处置提示一样不点名管理员
     */
    private static void sendInfoLines(CommandSourceStack source, ITitleService titles, CustomTitleInfo info,
                                      boolean staffView) {
        Component status = sponsorStatus(info);
        source.sendSuccess(() -> Component.translatable("title.miningdim.custom.info.sponsor", status), false);
        CustomTitle custom = info.custom();
        Component title = custom == null
                ? Component.translatable("title.miningdim.custom.info.unset")
                : TitleCommands.shown(titles, custom.id());
        source.sendSuccess(() -> Component.translatable("title.miningdim.custom.info.title", title), false);
        if (custom != null && custom.locked()) {
            String lockedBy = custom.lockedBy() == null ? "-" : custom.lockedBy();
            source.sendSuccess(() -> staffView
                    ? Component.translatable("title.miningdim.custom.info.locked", lockedBy)
                    : Component.translatable("title.miningdim.custom.info.locked_self"), false);
        }
        if (info.sponsorActive()) {
            Component nextEdit = info.nextEditAt() == 0L
                    ? Component.translatable("title.miningdim.custom.info.editable_now")
                    : Component.literal(TitleText.time(info.nextEditAt()));
            source.sendSuccess(() -> Component.translatable("title.miningdim.custom.info.next_edit", nextEdit), false);
        }
    }

    private static Component sponsorStatus(CustomTitleInfo info) {
        SponsorStatus sponsor = info.sponsor();
        if (sponsor == null) {
            return Component.translatable("title.miningdim.sponsor.status.none");
        }
        if (sponsor.permanent()) {
            return Component.translatable("title.miningdim.sponsor.status.permanent");
        }
        String expiry = TitleText.time(sponsor.expiresAt());
        return info.sponsorActive()
                ? Component.translatable("title.miningdim.sponsor.status.until", expiry)
                : Component.translatable("title.miningdim.sponsor.status.expired", expiry);
    }

    /**
     * 专属称号的四种管理员处置, 各自的服务调用与成功 / 未改动 / 没有记录时的提示键。清空只会得到 DONE、
     * NO_CUSTOM_TITLE 或 LOCKED (从不返回 UNCHANGED), 所以它没有"未改动"提示键。锁定在没有记录时另给一句做法提示:
     * 要清掉不当内容并禁止再改, 是"代设置成中性文字 + 锁定", 而不是先清空 (清空删掉整条记录, 之后就没有可锁的了)。
     */
    private enum AdminAction {
        RESET("title.miningdim.command.custom.admin.reset", null, "title.miningdim.command.custom.admin.no_custom"),
        LOCK("title.miningdim.command.custom.admin.locked", "title.miningdim.command.custom.admin.already_locked",
                "title.miningdim.command.custom.admin.no_custom_lock"),
        UNLOCK("title.miningdim.command.custom.admin.unlocked",
                "title.miningdim.command.custom.admin.already_unlocked",
                "title.miningdim.command.custom.admin.no_custom"),
        COOLDOWN("title.miningdim.command.custom.admin.cooldown_cleared",
                "title.miningdim.command.custom.admin.no_cooldown",
                "title.miningdim.command.custom.admin.no_custom");

        private final String doneKey;
        @Nullable
        private final String unchangedKey;
        private final String noCustomKey;

        AdminAction(String doneKey, @Nullable String unchangedKey, String noCustomKey) {
            this.doneKey = doneKey;
            this.unchangedKey = unchangedKey;
            this.noCustomKey = noCustomKey;
        }

        private CustomAdminResult apply(ITitleService titles, UUID player, String issuer) {
            return switch (this) {
                case RESET -> titles.resetCustomTitle(player, issuer);
                case LOCK -> titles.setCustomTitleLocked(player, true, issuer);
                case UNLOCK -> titles.setCustomTitleLocked(player, false, issuer);
                case COOLDOWN -> titles.clearCustomTitleCooldown(player, issuer);
            };
        }
    }
}
