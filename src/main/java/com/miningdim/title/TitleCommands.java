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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * 称号管理员命令 {@code /mtitle} (Title_System_DesignSpec 第八章), 权限等级 2。
 *
 * 命令根不叫 /title: 原版已有同名命令; 仓库也没有统一的 /wok 命令根, 与 /mchampion 一样各模块自带前缀。
 * grant / revoke / list 的玩家参数用 GameProfile, 离线玩家 (用户缓存里查得到的) 同样可以补发与回收;
 * equip 要刷新在线显示, 只接受在线玩家。称号 id 的补全候选来自当前已加载的定义。
 * 每次变更都写管理日志 (miningdim/title/admin), 与经济管理命令同口径。
 */
public final class TitleCommands {

    private static final int OP_LEVEL = 2;
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/title/admin");

    private static final SuggestionProvider<CommandSourceStack> LOADED_TITLES = (context, builder) ->
            TitleServices.isRegistered()
                    ? SharedSuggestionProvider.suggestResource(
                            TitleServices.titleService().definitions().stream().map(TitleDefinition::id), builder)
                    : builder.buildFuture();

    private TitleCommands() {
    }

    /** 由 {@link TitleSystem} 在 RegisterCommandsEvent 中注册。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mtitle")
                .requires(source -> source.hasPermission(OP_LEVEL))
                .then(Commands.literal("grant")
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .then(Commands.argument("title", ResourceLocationArgument.id())
                                        .suggests(LOADED_TITLES)
                                        .executes(TitleCommands::grant))))
                .then(Commands.literal("revoke")
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .then(Commands.argument("title", ResourceLocationArgument.id())
                                        .suggests(LOADED_TITLES)
                                        .executes(TitleCommands::revoke))))
                .then(Commands.literal("list")
                        .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                                .executes(TitleCommands::list)))
                .then(Commands.literal("equip")
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.literal("none")
                                        .executes(context -> equip(context, null)))
                                .then(Commands.argument("title", ResourceLocationArgument.id())
                                        .suggests(LOADED_TITLES)
                                        .executes(context -> equip(context,
                                                ResourceLocationArgument.getId(context, "title")))))));
    }

    private static int grant(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(context, "targets");
        ResourceLocation titleId = ResourceLocationArgument.getId(context, "title");
        ITitleService titles = serviceOrFail(source);
        if (titles == null) {
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
            Optional<ResourceLocation> equipped = titles.equipped(target.getId());
            Component equippedShown = equipped.map(id -> shown(titles, id))
                    .orElseGet(() -> Component.translatable("title.miningdim.command.list.none_equipped"));
            source.sendSuccess(() -> Component.translatable("title.miningdim.command.list.header",
                    target.getName(), owned.size(), equippedShown), false);
            for (ResourceLocation id : owned) {
                String entryKey = titles.definition(id).isPresent()
                        ? "title.miningdim.command.list.entry"
                        : "title.miningdim.command.list.entry_hidden";
                source.sendSuccess(() -> Component.translatable(entryKey, shown(titles, id), id.toString()), false);
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

    @Nullable
    private static ITitleService serviceOrFail(CommandSourceStack source) {
        if (!TitleServices.isRegistered()) {
            source.sendFailure(Component.translatable("title.miningdim.command.not_ready"));
            return null;
        }
        return TitleServices.titleService();
    }

    /** 反馈里的称号: 定义存在时用渲染好的徽记, 否则退回 id 文本 (定义被删除的持有记录也要能列出来)。 */
    private static Component shown(ITitleService titles, @Nullable ResourceLocation titleId) {
        if (titleId == null) {
            return Component.literal("-");
        }
        return titles.badge(titleId).orElseGet(() -> Component.literal(titleId.toString()));
    }
}
