package com.miningdim.title;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * 称号命令 {@code /mtitle} (Title_System_DesignSpec 第八章与 13.7)。
 *
 * 命令根不叫 /title: 原版已有同名命令; 仓库也没有统一的 /wok 命令根, 与 /mchampion 一样各模块自带前缀。
 * 命令根本身不设权限, 由各子命令分别判断:
 * <ul>
 *   <li>管理员子命令 grant / revoke / list / equip, 以及赞助与专属称号的管理子命令 (见 {@link CustomTitleCommands}),
 *       要求权限等级 2;</li>
 *   <li>玩家子命令 mine / wear 与 custom preview / set / info 对所有玩家开放 (只要求执行者是玩家),
 *       custom 的预览与提交在执行时再由服务端判断赞助资格。</li>
 * </ul>
 * grant / revoke / list 的玩家参数用 GameProfile, 离线玩家 (用户缓存里查得到的) 同样可以补发与回收;
 * equip 要刷新在线显示, 只接受在线玩家。称号 id 的补全候选: 管理员子命令取当前已加载的定义, wear 取执行者
 * 自己拥有的称号。管理员对普通称号的每次变更写管理日志 (miningdim/title/admin), 与经济管理命令同口径;
 * 赞助与专属称号的变更由服务层统一写审计日志 (miningdim/title/custom)。
 */
public final class TitleCommands {

    private static final int OP_LEVEL = 2;
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/title/admin");

    private static final SuggestionProvider<CommandSourceStack> LOADED_TITLES = (context, builder) ->
            TitleServices.isRegistered()
                    ? SharedSuggestionProvider.suggestResource(
                            TitleServices.titleService().definitions().stream().map(TitleDefinition::id), builder)
                    : builder.buildFuture();

    private static final SuggestionProvider<CommandSourceStack> OWN_TITLES = (context, builder) -> {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null || !TitleServices.isRegistered()) {
            return builder.buildFuture();
        }
        return SharedSuggestionProvider.suggestResource(TitleServices.titleService().owned(player.getUUID()), builder);
    };

    private TitleCommands() {
    }

    /** 由 {@link TitleSystem} 在 RegisterCommandsEvent 中注册。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mtitle")
                .then(Commands.literal("grant")
                        .requires(TitleCommands::isAdmin)
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .then(Commands.argument("title", ResourceLocationArgument.id())
                                        .suggests(LOADED_TITLES)
                                        .executes(TitleCommands::grant))))
                .then(Commands.literal("revoke")
                        .requires(TitleCommands::isAdmin)
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .then(Commands.argument("title", ResourceLocationArgument.id())
                                        .suggests(LOADED_TITLES)
                                        .executes(TitleCommands::revoke))))
                .then(Commands.literal("list")
                        .requires(TitleCommands::isAdmin)
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .executes(TitleCommands::list)))
                .then(Commands.literal("equip")
                        .requires(TitleCommands::isAdmin)
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.literal("none")
                                        .executes(context -> equip(context, null)))
                                .then(Commands.argument("title", ResourceLocationArgument.id())
                                        .suggests(LOADED_TITLES)
                                        .executes(context -> equip(context,
                                                ResourceLocationArgument.getId(context, "title"))))))
                .then(Commands.literal("mine")
                        .requires(CommandSourceStack::isPlayer)
                        .executes(TitleCommands::mine))
                .then(Commands.literal("wear")
                        .requires(CommandSourceStack::isPlayer)
                        .then(Commands.literal("none")
                                .executes(context -> wear(context, null)))
                        .then(Commands.argument("title", ResourceLocationArgument.id())
                                .suggests(OWN_TITLES)
                                .executes(context -> wear(context, ResourceLocationArgument.getId(context, "title")))))
                .then(CustomTitleCommands.customNode())
                .then(CustomTitleCommands.sponsorNode()));
    }

    /** 管理员子命令的权限门: 权限等级 2。 */
    static boolean isAdmin(CommandSourceStack source) {
        return source.hasPermission(OP_LEVEL);
    }

    private static int grant(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(context, "targets");
        ResourceLocation titleId = ResourceLocationArgument.getId(context, "title");
        ITitleService titles = serviceOrFail(source);
        if (titles == null) {
            return 0;
        }
        if (CustomTitle.isCustomId(titleId)) {
            source.sendFailure(Component.translatable("title.miningdim.command.custom_reserved", titleId.toString()));
            return 0;
        }
        if (titles.definition(titleId).isEmpty()) {
            source.sendFailure(Component.translatable("title.miningdim.command.unknown_title", titleId.toString()));
            return 0;
        }
        Component shown = shown(titles, titleId);
        int granted = 0;
        for (GameProfile target : targets) {
            GrantResult result = titles.grant(target.getId(), titleId, TitleSource.ADMIN, source.getTextName());
            LOGGER.info("[miningdim] title admin grant: issuer={} target={} targetUuid={} title={} result={}",
                    source.getTextName(), target.getName(), target.getId(), titleId, result);
            if (result == GrantResult.GRANTED) {
                granted++;
                source.sendSuccess(() -> Component.translatable(
                        "title.miningdim.command.grant.done", target.getName(), shown), true);
            } else {
                source.sendFailure(Component.translatable(
                        "title.miningdim.command.grant.already", target.getName(), shown));
            }
        }
        return granted;
    }

    private static int revoke(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(context, "targets");
        ResourceLocation titleId = ResourceLocationArgument.getId(context, "title");
        ITitleService titles = serviceOrFail(source);
        if (titles == null) {
            return 0;
        }
        if (CustomTitle.isCustomId(titleId)) {
            source.sendFailure(Component.translatable("title.miningdim.command.custom_reserved", titleId.toString()));
            return 0;
        }
        Component shown = shown(titles, titleId);
        int revoked = 0;
        for (GameProfile target : targets) {
            boolean removed = titles.revoke(target.getId(), titleId);
            LOGGER.info("[miningdim] title admin revoke: issuer={} target={} targetUuid={} title={} removed={}",
                    source.getTextName(), target.getName(), target.getId(), titleId, removed);
            if (removed) {
                revoked++;
                source.sendSuccess(() -> Component.translatable(
                        "title.miningdim.command.revoke.done", target.getName(), shown), true);
            } else {
                source.sendFailure(Component.translatable(
                        "title.miningdim.command.revoke.missing", target.getName(), shown));
            }
        }
        return revoked;
    }

    private static int list(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(context, "targets");
        ITitleService titles = serviceOrFail(source);
        if (titles == null) {
            return 0;
        }
        int listed = 0;
        for (GameProfile target : targets) {
            Set<ResourceLocation> owned = titles.owned(target.getId());
            Component equippedShown = equippedShown(titles, target.getId());
            source.sendSuccess(() -> Component.translatable("title.miningdim.command.list.header",
                    target.getName(), owned.size(), equippedShown), false);
            for (ResourceLocation id : owned) {
                source.sendSuccess(() -> ownedEntry(titles, id), false);
            }
            listed++;
        }
        return listed;
    }

    private static int equip(CommandContext<CommandSourceStack> context, @Nullable ResourceLocation titleId)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        ITitleService titles = serviceOrFail(source);
        if (titles == null) {
            return 0;
        }
        EquipResult result = titles.equip(target, titleId);
        LOGGER.info("[miningdim] title admin equip: issuer={} target={} targetUuid={} title={} result={}",
                source.getTextName(), target.getGameProfile().getName(), target.getUUID(), titleId, result);
        String name = target.getGameProfile().getName();
        switch (result) {
            case EQUIPPED -> source.sendSuccess(() -> Component.translatable(
                    "title.miningdim.command.equip.done", name, shown(titles, titleId)), true);
            case UNEQUIPPED -> source.sendSuccess(() -> Component.translatable(
                    "title.miningdim.command.equip.cleared", name), true);
            case NOT_OWNED -> source.sendFailure(Component.translatable(
                    "title.miningdim.command.equip.not_owned", name, shown(titles, titleId)));
            case UNKNOWN_TITLE -> source.sendFailure(Component.translatable(
                    "title.miningdim.command.unknown_title", String.valueOf(titleId)));
        }
        return result.success() ? 1 : 0;
    }

    /** 玩家查看自己拥有的称号与当前佩戴 ("我的称号"页签上线前的替代入口); 每一项点击即填入 wear 命令。 */
    private static int mine(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ITitleService titles = serviceOrFail(source);
        if (titles == null) {
            return 0;
        }
        Set<ResourceLocation> owned = titles.owned(player.getUUID());
        Component equippedShown = equippedShown(titles, player.getUUID());
        source.sendSuccess(() -> Component.translatable("title.miningdim.command.mine.header",
                owned.size(), equippedShown), false);
        for (ResourceLocation id : owned) {
            Component entry = ownedEntry(titles, id).copy().withStyle(style -> style
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/mtitle wear " + id))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.translatable("title.miningdim.command.mine.click_to_wear"))));
            source.sendSuccess(() -> entry, false);
        }
        return 1;
    }

    /**
     * 玩家佩戴自己拥有的称号或卸下; 与管理员 equip 走同一条服务端校验。别人的专属称号服务端一律答未拥有, 这里也只回
     * id 原文而不渲染它: 渲染就等于把对方 (可能已失效、或是管理员预先备好) 的称号展示给任何来试探的人。
     */
    private static int wear(CommandContext<CommandSourceStack> context, @Nullable ResourceLocation titleId)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ITitleService titles = serviceOrFail(source);
        if (titles == null) {
            return 0;
        }
        EquipResult result = titles.equip(player, titleId);
        boolean ownCustom = CustomTitle.idOf(player.getUUID()).equals(titleId);
        boolean othersCustom = titleId != null && CustomTitle.isCustomId(titleId) && !ownCustom;
        switch (result) {
            case EQUIPPED -> source.sendSuccess(() -> Component.translatable(
                    "title.miningdim.command.wear.done", shown(titles, titleId)), false);
            case UNEQUIPPED -> source.sendSuccess(() -> Component.translatable(
                    "title.miningdim.command.wear.cleared"), false);
            case NOT_OWNED -> source.sendFailure(ownCustom
                    ? Component.translatable("title.miningdim.custom.not_sponsor")
                    : Component.translatable("title.miningdim.command.wear.not_owned",
                    othersCustom ? Component.literal(titleId.toString()) : shown(titles, titleId)));
            case UNKNOWN_TITLE -> source.sendFailure(ownCustom
                    ? Component.translatable(titles.customSelfServiceEnabled()
                    ? "title.miningdim.custom.not_set" : "title.miningdim.custom.not_set_staff")
                    : Component.translatable("title.miningdim.command.unknown_title", String.valueOf(titleId)));
        }
        return result.success() ? 1 : 0;
    }

    @Nullable
    static ITitleService serviceOrFail(CommandSourceStack source) {
        if (!TitleServices.isRegistered()) {
            source.sendFailure(Component.translatable("title.miningdim.command.not_ready"));
            return null;
        }
        return TitleServices.titleService();
    }

    /** 反馈里的称号: 定义存在时用渲染好的徽记, 否则退回 id 文本 (定义被删除的持有记录也要能列出来)。 */
    static Component shown(ITitleService titles, @Nullable ResourceLocation titleId) {
        if (titleId == null) {
            return Component.literal("-");
        }
        return titles.badge(titleId).orElseGet(() -> Component.literal(titleId.toString()));
    }

    private static Component equippedShown(ITitleService titles, UUID player) {
        return titles.equipped(player).map(id -> shown(titles, id))
                .orElseGet(() -> Component.translatable("title.miningdim.command.list.none_equipped"));
    }

    private static Component ownedEntry(ITitleService titles, ResourceLocation id) {
        String entryKey = titles.definition(id).isPresent()
                ? "title.miningdim.command.list.entry"
                : "title.miningdim.command.list.entry_hidden";
        return Component.translatable(entryKey, shown(titles, id), id.toString());
    }
}
