package com.miningdim.job.farmer.granary;

import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 开放名单的服务面 (规格「开放名单」: 囤主决定谁能存、谁能取)。
 *
 * 命令 ({@code /granary trust ...}) 与账台/囤界面上的按钮**共用这一份实现**: 判定、名单上限、房主独占
 * 都只有一处 —— 两边各写一套的话, 迟早出现"界面上加得进、命令加不进"或者反过来的分叉。
 *
 * 界面只有名字 (玩家在输入框里打的), 命令要一个在线选择器 —— 所以**名字一律按在线玩家解析**
 * (见 {@link #resolve}); 离线的人先让他上线再加, 不接受"档案缓存里有这么一号"就当兑现得了。
 */
public final class GranaryTrustService {

    private GranaryTrustService() {
    }

    /** 界面按钮能做的四件事。 */
    public enum Action {
        /** 加一个人 (已在名单里则改他的权限)。 */
        ADD,
        /** 按名字移出。 */
        REMOVE,
        /** 清空 (转私有)。 */
        CLEAR
    }

    /** 一次操作的结果码 (客户端据此显示一句话; 判定全在服务端)。 */
    public enum Outcome {
        /** 加进去了。 */
        ADDED,
        /** 改了权限。 */
        UPDATED,
        /** 移出了。 */
        REMOVED,
        /** 清空了。 */
        CLEARED,
        /** 这不是你的囤 (只有囤主能改开放名单)。 */
        NOT_OWNER,
        /** 这个名字既不在线、也不在档案缓存里。 */
        UNKNOWN_PLAYER,
        /** 名单满了 ({@link GranaryAccess#MAX_TRUSTEES})。 */
        FULL,
        /** 想把囤主自己加进去 / 想把囤主移出去。 */
        IS_OWNER,
        /** 这个人本来就不在名单里。 */
        NOT_LISTED;

        public String translationKey() {
            return "message.miningdim.granary.trust." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** 一次操作的完整回执 (结果码 + 操作后的名单)。 */
    public record Result(Outcome outcome, List<GranaryAccess.Trustee> trustees) {

        public boolean accepted() {
            return outcome == Outcome.ADDED || outcome == Outcome.UPDATED
                    || outcome == Outcome.REMOVED || outcome == Outcome.CLEARED;
        }
    }

    /**
     * 执行一次名单操作。
     *
     * @param actor 操作者 (必须**是**这个囤的囤主)
     * @param name  目标玩家名 (ADD/REMOVE 用; CLEAR 忽略)
     */
    public static Result apply(MinecraftServer server, GranaryBinBlockEntity bin, ServerPlayer actor,
                               Action action, String name, GranaryAccess.Permission permission) {
        if (bin == null || actor == null || action == null) {
            return new Result(Outcome.NOT_OWNER, List.of());
        }
        if (!actor.getUUID().equals(bin.owner())) {
            return new Result(Outcome.NOT_OWNER, bin.access());
        }
        if (action == Action.CLEAR) {
            bin.setAccess(List.of());
            return new Result(Outcome.CLEARED, bin.access());
        }
        Optional<UUID> target = resolve(server, name);
        if (target.isEmpty()) {
            return new Result(Outcome.UNKNOWN_PLAYER, bin.access());
        }
        UUID uuid = target.get();
        if (action == Action.REMOVE) {
            if (!bin.removeTrustee(uuid)) {
                return new Result(Outcome.NOT_LISTED, bin.access());
            }
            return new Result(Outcome.REMOVED, bin.access());
        }
        boolean already = GranaryAccess.permissionOf(bin.access(), uuid) != null;
        GranaryAccess.Status status = bin.addTrustee(uuid, permission == null
                ? GranaryAccess.Permission.STORE_ONLY : permission);
        return switch (status) {
            case IS_OWNER -> new Result(Outcome.IS_OWNER, bin.access());
            case FULL -> new Result(Outcome.FULL, bin.access());
            default -> new Result(already ? Outcome.UPDATED : Outcome.ADDED, bin.access());
        };
    }

    /**
     * 名字 -> UUID: **只认在线玩家** (与 {@code /granary trust <玩家>} 完全同口径 —— 命令侧要的就是一个在线
     * 选择器, 界面侧也就别再另发明一套"离线也算"的规矩)。
     *
     * 为什么不用档案缓存: 开发/测试环境的缓存会替不存在的名字编出档案 (实测: 一个从没进过服的名字也能解析出
     * UUID), 那样"加一个不存在的人"会静默成功、名单里多一条谁也兑现不了的记录。宁可让囤主等对方上线再加。
     */
    public static Optional<UUID> resolve(MinecraftServer server, String name) {
        if (server == null || name == null || name.isBlank()) {
            return Optional.empty();
        }
        ServerPlayer online = server.getPlayerList().getPlayerByName(name.trim());
        return online == null ? Optional.empty() : Optional.of(online.getUUID());
    }

    /** 名单 -> 界面文本行 (每行一个序列化后的 Component: 名字 + 权限)。 */
    public static List<String> lines(MinecraftServer server, List<GranaryAccess.Trustee> trustees) {
        List<String> lines = new ArrayList<>();
        for (GranaryAccess.Trustee trustee : trustees) {
            lines.add(Component.Serializer.toJson(Component.translatable("granary.trust.row",
                    GranaryQuery.nameOf(server, trustee.player()),
                    Component.translatable(trustee.permission().translationKey()))));
        }
        return lines;
    }
}
