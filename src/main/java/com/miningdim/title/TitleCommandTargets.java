package com.miningdim.title;

import com.miningdim.core.auth.PlayerLoginGate;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 称号管理子命令的目标玩家解析 ({@link TitleCommands} 的 grant / revoke / list, {@link CustomTitleCommands} 的 sponsor 与
 * custom admin 各子命令共用; Title_System_DesignSpec 第八章)。
 *
 * 参数类型仍是 {@link GameProfileArgument} (选择器与在线玩家名的补全照旧), 但按玩家名指定的目标不能交给它在执行期解析:
 * 它走 {@code GameProfileCache.get(String)}, 该方法先把名字转成小写, 缓存没命中时在主线程上请求 Mojang, 查不到且
 * 服务器不做正版验证时, 用小写名字派生一个离线 UUID 编出档案并写进 usercache, 从不报"未知玩家"。正式服是离线模式,
 * 玩家的 UUID 按名字的原样大小写派生: 对一个不在缓存里的名字 (超过一个月没上线、还没进过服、打错) 发放的资格与称号
 * 会写到那个编出来的 UUID 上, 命令照常回显成功, 本人上线却什么都没有。
 *
 * 所以这里取玩家敲进去的原文自己解析:
 * <ol>
 *   <li>选择器 (以 @ 开头): 照旧交给 GameProfileArgument, 它只会选到在线玩家;</li>
 *   <li>玩家名先找已通过登录门的在线玩家 (不分大小写), 找到就用他本人的档案。没通过登录门的不算: 不做正版验证的
 *       服务器上, 那样的连接可以顶着别人名字的另一种大小写在线, 不能把它当成这个名字的本人;</li>
 *   <li>不在线、服务器做正版验证 (单人、局域网、正版服): 照旧交给 GameProfileArgument, 此时缓存与 Mojang 查询是对的;</li>
 *   <li>不在线、服务器不做正版验证: 见 {@link #offlineModeProfile}, 没进过服的名字直接拒绝。</li>
 * </ol>
 *
 * 成就模块的管理子命令 (/machievement pending、points) 是同一种参数、同一个问题, 也用这里解析。
 */
public final class TitleCommandTargets {

    private static final String UNKNOWN_PLAYER_KEY = "title.miningdim.command.unknown_player";

    private TitleCommandTargets() {
    }

    /**
     * 解析名为 argument 的 GameProfileArgument 参数指向的玩家档案。
     *
     * @throws CommandSyntaxException 选择器没选到人、正版模式下查无此人, 或离线模式下这个名字没进过服
     */
    public static Collection<GameProfile> resolve(CommandContext<CommandSourceStack> context, String argument)
            throws CommandSyntaxException {
        String typed = typedText(context, argument);
        if (typed.startsWith("@")) {
            return GameProfileArgument.getGameProfiles(context, argument);
        }
        MinecraftServer server = context.getSource().getServer();
        ServerPlayer online = server.getPlayerList().getPlayerByName(typed);
        if (online != null && PlayerLoginGate.allows(online)) {
            return List.of(online.getGameProfile());
        }
        if (server.usesAuthentication()) {
            return GameProfileArgument.getGameProfiles(context, argument);
        }
        GameProfile offline = offlineModeProfile(server, typed).orElseThrow(() ->
                new SimpleCommandExceptionType(Component.translatable(UNKNOWN_PLAYER_KEY, typed)).create());
        return List.of(offline);
    }

    /**
     * 不做正版验证的服务器上, 一名不在线的玩家按名字能不能认出来: 离线 UUID 由名字的原样大小写派生, 存档里有这个 UUID
     * 的玩家数据文件才算进过服 (文件在, 也就说明输入的大小写与他进服时一致), 档案名即输入原文; 否则为空。
     *
     * 不查用户缓存 (理由见类注释); 调用方已确认该名字没有已通过登录门的在线玩家。
     */
    static Optional<GameProfile> offlineModeProfile(MinecraftServer server, String typedName) {
        UUID uuid = UUIDUtil.createOfflinePlayerUUID(typedName);
        boolean joinedBefore = Files.isRegularFile(
                server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat"));
        return joinedBefore ? Optional.of(new GameProfile(uuid, typedName)) : Optional.empty();
    }

    /** 该参数在命令原文里对应的那一段 (玩家实际敲进去的选择器或名字)。 */
    private static String typedText(CommandContext<CommandSourceStack> context, String argument) {
        for (ParsedCommandNode<CommandSourceStack> parsed : context.getNodes()) {
            if (parsed.getNode() instanceof ArgumentCommandNode<?, ?> node && node.getName().equals(argument)) {
                return parsed.getRange().get(context.getInput());
            }
        }
        // 与 CommandContext#getArgument 对未知参数名的处理一致: 这是命令树接线错误, 不是玩家输入的问题。
        throw new IllegalArgumentException("No such argument '" + argument + "' exists on this command");
    }
}
