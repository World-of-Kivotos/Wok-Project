package com.miningdim.donation;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * 协管增删与箱主转让的唯一业务入口, 指令与界面网络包共用, 权限在这里判定而不是在调用方各写一遍。
 *
 * actor 为发起操作的玩家; 为 null 表示真控制台 / RCON, 调用方 (指令层 DonationCommands.privilegedSource)
 * 必须已经确认过这一点 —— 命令方块、数据包函数这类无实体的 2 级来源在指令层就被拒, 到不了这里。
 * 每次成功的变更都写一行审计日志; 失败只回提示, 不写日志 (失败的触发权在客户端手里, 记日志会被刷屏)。
 * 成功分支的触发权同样在客户端手里, 界面网络包由 {@link DonationNetwork} 按玩家限速, 指令走原版聊天防刷。
 *
 * 公开方法按服务器解析玩家名 ({@link DonationProfiles#forServer}); 包级重载接收显式的解析函数, 供 GameTest
 * 用一个独立的 profile cache 实例驱动离线解析路径 (GameTest 服务端本身不带 profile cache)。
 */
public final class DonationBoxService {

    private DonationBoxService() {
    }

    public enum Status {
        OK,
        NO_PERMISSION,
        NOT_FOUND,
        ALREADY_PRESENT,
        IS_OWNER,
        LIMIT_REACHED,
        NOT_PRESENT
    }

    public record Outcome(Status status, Component message) {
        public boolean ok() {
            return status == Status.OK;
        }
    }

    public static Outcome addCoAdmin(DonationBoxBlockEntity box, MinecraftServer server,
                                     @Nullable ServerPlayer actor, String name) {
        return addCoAdmin(box, DonationProfiles.forServer(server), actor, name);
    }

    static Outcome addCoAdmin(DonationBoxBlockEntity box, DonationProfiles.Resolver resolver,
                              @Nullable ServerPlayer actor, String name) {
        if (actor != null && !box.canEditCoAdmins(actor)) {
            return fail(Status.NO_PERMISSION, "message.miningdim.donation_box.coadmin.no_permission");
        }
        Optional<GameProfile> resolved = resolver.resolve(name);
        if (resolved.isEmpty()) {
            return fail(Status.NOT_FOUND, "message.miningdim.donation_box.player_not_found", name);
        }
        GameProfile profile = resolved.get();
        return switch (box.addCoAdmin(profile.getId(), profile.getName())) {
            case ADDED -> {
                DonationAudit.admin(box.getLevel(), box.getBlockPos(), "COADMIN_ADD", actorName(actor),
                        "target=" + DonationAudit.describe(profile.getId(), profile.getName()));
                yield new Outcome(Status.OK, Component.translatable("message.miningdim.donation_box.coadmin.added",
                        profile.getName(), box.coAdmins().size(), DonationBoxBlockEntity.MAX_CO_ADMINS));
            }
            case ALREADY_PRESENT -> fail(Status.ALREADY_PRESENT,
                    "message.miningdim.donation_box.coadmin.already", profile.getName());
            case IS_OWNER -> fail(Status.IS_OWNER, "message.miningdim.donation_box.coadmin.is_owner", profile.getName());
            case LIMIT_REACHED -> fail(Status.LIMIT_REACHED, "message.miningdim.donation_box.coadmin.limit",
                    DonationBoxBlockEntity.MAX_CO_ADMINS);
            case REMOVED, NOT_PRESENT -> throw new IllegalStateException("addCoAdmin never reports a removal");
        };
    }

    public static Outcome removeCoAdmin(DonationBoxBlockEntity box, MinecraftServer server,
                                        @Nullable ServerPlayer actor, String name) {
        return removeCoAdmin(box, DonationProfiles.forServer(server), actor, name);
    }

    /** 先按名单里记下的名字找 (协管改名或离线都能移除), 找不到再按解析出的 UUID 找。 */
    static Outcome removeCoAdmin(DonationBoxBlockEntity box, DonationProfiles.Resolver resolver,
                                 @Nullable ServerPlayer actor, String name) {
        if (actor != null && !box.canEditCoAdmins(actor)) {
            return fail(Status.NO_PERMISSION, "message.miningdim.donation_box.coadmin.no_permission");
        }
        String trimmed = name == null ? "" : name.trim();
        Optional<UUID> target = box.findCoAdminByName(trimmed);
        if (target.isEmpty()) {
            target = resolver.resolve(trimmed).map(GameProfile::getId).filter(box::isCoAdmin);
        }
        if (target.isEmpty()) {
            return fail(Status.NOT_PRESENT, "message.miningdim.donation_box.coadmin.not_present", trimmed);
        }
        UUID id = target.get();
        String storedName = box.coAdmins().get(id);
        box.removeCoAdmin(id);
        DonationAudit.admin(box.getLevel(), box.getBlockPos(), "COADMIN_REMOVE", actorName(actor),
                "target=" + DonationAudit.describe(id, storedName));
        return new Outcome(Status.OK, Component.translatable("message.miningdim.donation_box.coadmin.removed", storedName));
    }

    /** 转让箱主: 仅 OP (或控制台)。 */
    public static Outcome transferOwner(DonationBoxBlockEntity box, MinecraftServer server,
                                        @Nullable ServerPlayer actor, String name) {
        return transferOwner(box, DonationProfiles.forServer(server), actor, name);
    }

    static Outcome transferOwner(DonationBoxBlockEntity box, DonationProfiles.Resolver resolver,
                                 @Nullable ServerPlayer actor, String name) {
        if (actor != null && !DonationBoxBlockEntity.isOperator(actor)) {
            return fail(Status.NO_PERMISSION, "message.miningdim.donation_box.transfer.no_permission");
        }
        Optional<GameProfile> resolved = resolver.resolve(name);
        if (resolved.isEmpty()) {
            return fail(Status.NOT_FOUND, "message.miningdim.donation_box.player_not_found", name);
        }
        GameProfile profile = resolved.get();
        String previous = box.ownerDescription();
        Map<UUID, String> cleared = box.transferOwner(profile.getId(), profile.getName());
        StringJoiner clearedDesc = new StringJoiner(",", "[", "]");
        cleared.forEach((id, storedName) -> clearedDesc.add(DonationAudit.describe(id, storedName)));
        DonationAudit.admin(box.getLevel(), box.getBlockPos(), "OWNER_TRANSFER", actorName(actor),
                "from=" + previous + " to=" + DonationAudit.describe(profile.getId(), profile.getName())
                        + " clearedCoAdmins=" + clearedDesc);
        return new Outcome(Status.OK, Component.translatable("message.miningdim.donation_box.transfer.done",
                profile.getName(), cleared.size()));
    }

    private static Outcome fail(Status status, String key, Object... args) {
        return new Outcome(status, Component.translatable(key, args));
    }

    private static String actorName(@Nullable ServerPlayer actor) {
        return actor == null ? "console" : DonationAudit.describe(actor.getUUID(), actor.getGameProfile().getName());
    }
}
