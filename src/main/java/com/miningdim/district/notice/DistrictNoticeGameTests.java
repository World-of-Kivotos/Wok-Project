package com.miningdim.district.notice;

import com.miningdim.core.MiningConstants;
import com.miningdim.core.auth.PlayerLoginGate;
import com.miningdim.district.DistrictFeature;
import com.miningdim.district.DistrictLimits;
import com.miningdim.district.DistrictSystem;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.access.Actor;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.FriendRecord;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.NoticeRecord;
import com.miningdim.district.core.PermissionCatalog;
import com.miningdim.district.core.PermissionItemDef;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.core.PlotAudience;
import com.miningdim.district.core.ResidentSyncStatus;
import com.miningdim.district.flan.DisabledFlanGateway;
import com.miningdim.district.flan.GatewaySelector;
import com.miningdim.district.service.PlotLayoutService;
import com.miningdim.store.SchemaMigrator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static com.miningdim.district.DistrictTestEnv.onlinePlayer;
import static com.miningdim.district.DistrictTestEnv.op;
import static com.miningdim.district.DistrictTestEnv.player;
import static com.miningdim.district.DistrictTestEnv.removePlayer;
import static com.miningdim.district.DistrictTestEnv.uuidOf;

/**
 * 聊天通知 (设计文档 22.10–22.12): 行随业务事务提交或回滚; 在线的提交后立即送达, 离线的上线后按发生顺序补发 (带头一行);
 * 从没进过服的人首次登录换键后收到; 每类事件的收件人; 不给自己发 (买地除外); 只发在线的开放购买与它的节流; 每人 30 条与
 * 30 天; 登录门接缝 (gate 没放行之前一条都不发); 不重复; 通知失败不影响登录; 不认识的行丢掉。
 *
 * 聊天从 mock 玩家的 EmbeddedChannel 出站队列里读 (照 CustomTitleGameTests.systemChatKeys), 只看
 * district.miningdim.notice.* 的系统消息 (登录时别的模块也会发聊天)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class DistrictNoticeGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_notices";
    private static final String LOGIN_GATE_BATCH = "district_notices_login_gate";
    /** 登录门每 5 tick 巡检一次等待表; 等这么多 tick 还没补发就不是时序问题。 */
    private static final int LOGIN_GATE_WAIT_TICKS = 40;
    private static final String ABYDOS = "abydos";
    private static final String ACADEMY = "阿拜多斯学院";
    private static final String DISTRICT = "阿拜多斯自管区";

    private static final String HEADER = DistrictNoticeKind.HEADER_KEY;

    private DistrictNoticeGameTests() {
    }

    // ================================================================
    // 事务与投递
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void noticeRowsCommitAndRollBackWithTheBusinessTransaction(GameTestHelper helper) {
        ServerPlayer owner = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident(ABYDOS, "Nt_Tx_Owner");
            env.resident(ABYDOS, "Nt_Tx_Target");
            String plot = env.plot(ABYDOS, 10, 10, 25, 25);
            env.own(plot, "Nt_Tx_Owner");
            env.ctx.plotOwners().addFriend(player("Nt_Tx_Owner"), ABYDOS, plot, "Nt_Tx_Target", false);
            owner = onlinePlayer(helper, "Nt_Tx_Owner");
            clearQueue(env, "Nt_Tx_Owner", "Nt_Tx_Target");
            drain(owner);

            // 第二条通知 (给户主的 friend_suspended) 入队时失败: 已经插进去的第一条 (给被移出的人) 随事务一起回滚。
            env.exec("CREATE TEMP TRIGGER gt_notice_fail_second BEFORE INSERT ON district_notice "
                    + "WHEN NEW.kind = 'friend_suspended' BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
            boolean failed = false;
            try {
                env.ctx.residents().removeResident(env.admin, ABYDOS, "Nt_Tx_Target", "violation", "刷屏");
            } catch (RuntimeException expected) {
                failed = true;
            }
            helper.assertTrue(failed, "通知写入失败要让整次移出失败 (缺表时同理, 22.11)");
            helper.assertTrue(env.repo.memberByUuid(uuidOf("Nt_Tx_Target")).isPresent(), "移出整体回滚: 还在名单上");
            helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Tx_Target")) == 0
                            && env.repo.countNotices(uuidOf("Nt_Tx_Owner")) == 0,
                    "回滚的操作不留通知行");
            helper.assertTrue(drain(owner).isEmpty(), "回滚的操作不发聊天");

            env.exec("DROP TRIGGER gt_notice_fail_second");
            env.ctx.residents().removeResident(env.admin, ABYDOS, "Nt_Tx_Target", "violation", "刷屏");
            helper.assertTrue(kindsOf(env, "Nt_Tx_Target").equals(List.of("resident_removed.violation")),
                    "离线的被移出者排着一条, 实为 " + kindsOf(env, "Nt_Tx_Target"));
            List<Chat> ownerChats = drain(owner);
            helper.assertTrue(keys(ownerChats).equals(List.of(key(DistrictNoticeKind.FRIEND_SUSPENDED))),
                    "在线的户主提交后立即收到, 实为 " + ownerChats);
            helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Tx_Owner")) == 0, "送达的行已删");
        } finally {
            removePlayer(helper, owner);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void onlineRecipientIsNotifiedRightAfterCommit(GameTestHelper helper) {
        ServerPlayer online = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            online = onlinePlayer(helper, "Nt_Online");
            drain(online);
            env.ctx.residents().addResident(env.admin, ABYDOS, "Nt_Online", false);
            List<Chat> chats = drain(online);
            helper.assertTrue(chats.size() == 1, "恰好一条, 实为 " + chats);
            Chat chat = chats.get(0);
            helper.assertTrue(chat.key().equals(key(DistrictNoticeKind.RESIDENT_ADDED))
                            && chat.args().equals(List.of(ACADEMY, DISTRICT)),
                    "键与参数 (学院全称、区名), 实为 " + chat);
            helper.assertTrue(!chat.overlay() && color(ChatFormatting.AQUA).equals(chat.color()),
                    "聊天栏、青色, 实为 " + chat);
            helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Online")) == 0, "送达之后队列里没有 TA 的行");
        } finally {
            removePlayer(helper, online);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void offlineRecipientGetsQueuedNoticesOnNextLoginInOrder(GameTestHelper helper) {
        ServerPlayer away = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident(ABYDOS, "Nt_Host");
            String plot = env.plot(ABYDOS, 10, 10, 25, 25);
            env.own(plot, "Nt_Host");
            env.seen("Nt_Away");
            env.ctx.residents().addResident(env.admin, ABYDOS, "Nt_Away", false);
            env.warden(ABYDOS, "Nt_Away");
            env.ctx.plotOwners().addFriend(player("Nt_Host"), ABYDOS, plot, "Nt_Away", false);
            helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Away")) == 3, "离线时三条都排着");

            away = onlinePlayer(helper, "Nt_Away");
            List<Chat> chats = drain(away);
            helper.assertTrue(keys(chats).equals(List.of(HEADER, key(DistrictNoticeKind.RESIDENT_ADDED),
                            key(DistrictNoticeKind.WARDEN_APPOINTED), key(DistrictNoticeKind.FRIEND_ADDED))),
                    "上线: 灰色头一行, 然后按发生顺序三条, 实为 " + keys(chats));
            helper.assertTrue(chats.get(0).args().equals(List.of("3"))
                            && color(ChatFormatting.GRAY).equals(chats.get(0).color()),
                    "头一行带条数、灰色, 实为 " + chats.get(0));
            helper.assertTrue(chats.get(3).args().equals(List.of("Nt_Host", env.plotRecord(plot).code())),
                    "朋友通知的参数是户主名与地块标签, 实为 " + chats.get(3));
            helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Away")) == 0, "补发之后行已删");

            removePlayer(helper, away);
            away = onlinePlayer(helper, "Nt_Away");
            helper.assertTrue(drain(away).isEmpty(), "再次上线不重发, 也没有头一行");
        } finally {
            removePlayer(helper, away);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pendingPlayerHearsAboutJoiningOnFirstLogin(GameTestHelper helper) {
        ServerPlayer foo = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.ctx.residents().addResident(env.admin, ABYDOS, "Nt_Foo", true);
            helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Foo")) == 1, "从没进过服的人按输入名字的离线 UUID 入队");

            // 大小写不同的名字第一次上线: UUID 不同, 首次登录补写换键之后收到。
            foo = onlinePlayer(helper, "nt_foo");
            List<Chat> chats = drain(foo);
            helper.assertTrue(keys(chats).equals(List.of(HEADER, key(DistrictNoticeKind.RESIDENT_ADDED))),
                    "换键之后收到加入通知, 实为 " + keys(chats));
            MemberRecord member = env.repo.memberByUuid(uuidOf("nt_foo")).orElse(null);
            helper.assertTrue(member != null && member.syncStatus() == ResidentSyncStatus.SYNCED,
                    "名单行换到登录者并生效, 实为 " + member);
            helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Foo")) == 0
                    && env.repo.countNotices(uuidOf("nt_foo")) == 0, "旧键与新键都不剩行");
        } finally {
            removePlayer(helper, foo);
        }
        helper.succeed();
    }

    // ================================================================
    // 每类事件的收件人
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void removalSendsKindNeverReasonText(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident(ABYDOS, "Nt_Rm_Own");
            env.resident(ABYDOS, "Nt_Rm_Idle");
            env.resident(ABYDOS, "Nt_Rm_Bad");
            env.resident(ABYDOS, "Nt_Rm_Self");
            env.resident(ABYDOS, "Nt_Rm_Other");
            String home = env.plot(ABYDOS, 10, 10, 25, 25);
            String other = env.plot(ABYDOS, 40, 10, 55, 25);
            env.own(home, "Nt_Rm_Own");
            env.own(other, "Nt_Rm_Other");
            env.ctx.plotOwners().addFriend(player("Nt_Rm_Own"), ABYDOS, home, "Nt_Rm_Bad", false);
            clearQueue(env, "Nt_Rm_Own", "Nt_Rm_Idle", "Nt_Rm_Bad", "Nt_Rm_Self", "Nt_Rm_Other");

            env.ctx.residents().removeResident(env.admin, ABYDOS, "Nt_Rm_Idle", "inactive", "SECRET_ONE");
            env.ctx.residents().removeResident(env.admin, ABYDOS, "Nt_Rm_Bad", "violation", "SECRET_TWO");
            env.ctx.residents().removeResident(env.admin, ABYDOS, "Nt_Rm_Self", "selfRequest", "SECRET_THREE");
            env.ctx.residents().removeResident(env.admin, ABYDOS, "Nt_Rm_Other", "other", "SECRET_FOUR");

            expectQueue(helper, env, "Nt_Rm_Idle", List.of(
                    row(DistrictNoticeKind.RESIDENT_REMOVED_INACTIVE, ACADEMY)));
            expectQueue(helper, env, "Nt_Rm_Bad", List.of(
                    row(DistrictNoticeKind.RESIDENT_REMOVED_VIOLATION, ACADEMY)));
            expectQueue(helper, env, "Nt_Rm_Self", List.of(
                    row(DistrictNoticeKind.RESIDENT_REMOVED_SELF_REQUEST, ACADEMY)));
            String otherCode = env.plotRecord(other).code();
            expectQueue(helper, env, "Nt_Rm_Other", List.of(
                    row(DistrictNoticeKind.RESIDENT_REMOVED_OTHER, ACADEMY),
                    row(DistrictNoticeKind.PLOT_FROZEN, otherCode, String.valueOf(DistrictLimits.FREEZE_DAYS))));
            expectQueue(helper, env, "Nt_Rm_Own", List.of(
                    row(DistrictNoticeKind.FRIEND_SUSPENDED, env.plotRecord(home).code(), "Nt_Rm_Bad")));
            for (String name : List.of("Nt_Rm_Own", "Nt_Rm_Idle", "Nt_Rm_Bad", "Nt_Rm_Self", "Nt_Rm_Other")) {
                for (NoticeRecord notice : env.repo.noticesFor(uuidOf(name))) {
                    helper.assertTrue(notice.args().stream().noneMatch(arg -> arg.contains("SECRET")),
                            "移出原因的原文绝不进通知: " + notice);
                }
            }
            helper.assertTrue(env.repo.countNotices(uuidOf("Op_Admin")) == 0, "操作人自己什么都不收");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wardenAppointReplaceRevoke(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident(ABYDOS, "Nt_Ward_A");
            env.resident(ABYDOS, "Nt_Ward_B");
            clearQueue(env, "Nt_Ward_A", "Nt_Ward_B");

            env.warden(ABYDOS, "Nt_Ward_A");
            expectQueue(helper, env, "Nt_Ward_A", List.of(row(DistrictNoticeKind.WARDEN_APPOINTED, DISTRICT)));
            clearQueue(env, "Nt_Ward_A");

            env.warden(ABYDOS, "Nt_Ward_B");
            expectQueue(helper, env, "Nt_Ward_A", List.of(row(DistrictNoticeKind.WARDEN_REVOKED, DISTRICT)));
            expectQueue(helper, env, "Nt_Ward_B", List.of(row(DistrictNoticeKind.WARDEN_APPOINTED, DISTRICT)));
            long revokeId = env.repo.noticesFor(uuidOf("Nt_Ward_A")).get(0).id();
            long appointId = env.repo.noticesFor(uuidOf("Nt_Ward_B")).get(0).id();
            helper.assertTrue(revokeId < appointId, "换人: 先撤旧的再任命新的");
            clearQueue(env, "Nt_Ward_A", "Nt_Ward_B");

            env.ctx.admin().setWarden(env.admin, ABYDOS, null);
            expectQueue(helper, env, "Nt_Ward_B", List.of(row(DistrictNoticeKind.WARDEN_REVOKED, DISTRICT)));
            helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Ward_A")) == 0, "撤销只通知当时的区务长");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void adminOnBehalfNotifiesTheOwner(GameTestHelper helper) {
        ServerPlayer owner = null;
        ServerPlayer pal = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident(ABYDOS, "Nt_Home");
            String plot = env.plot(ABYDOS, 10, 10, 25, 25);
            env.own(plot, "Nt_Home");
            String code = env.plotRecord(plot).code();
            owner = onlinePlayer(helper, "Nt_Home");
            pal = onlinePlayer(helper, "Nt_Pal");
            drain(owner);
            drain(pal);
            Actor admin = env.admin;

            PermissionItemDef item = PermissionCatalog.memberItems().get(0);
            boolean flipped = !item.plotDefault(PlotAudience.OUTSIDER);
            env.ctx.plotOwners().setPermission(admin, ABYDOS, plot, item.permissionId(), "outsider", flipped);
            expectChats(helper, owner, List.of(chat(DistrictNoticeKind.PLOT_ADMIN_PERMISSION, "Op_Admin", code,
                    DistrictTexts.permissionChangeText(PlotAudience.OUTSIDER, item.label(), flipped))));
            helper.assertTrue(DistrictTexts.permissionChangeText(PlotAudience.OUTSIDER, "破坏方块", false)
                    .equals("外人·破坏方块 → 关"), "改动文字与平板的叫法一致");

            env.ctx.plotOwners().resetPermissions(admin, ABYDOS, plot);
            expectChats(helper, owner, List.of(chat(DistrictNoticeKind.PLOT_ADMIN_RESET, "Op_Admin", code,
                    DistrictTexts.NOTICE_RESET_SCOPE, "1")));
            env.ctx.plotOwners().resetPermissions(admin, ABYDOS, plot);
            helper.assertTrue(drain(owner).isEmpty(), "一格都没改的恢复默认不发");

            env.ctx.plotOwners().addFriend(admin, ABYDOS, plot, "Nt_Pal", false);
            expectChats(helper, owner, List.of(chat(DistrictNoticeKind.PLOT_ADMIN_FRIEND_ADDED, "Op_Admin", "Nt_Pal",
                    code)));
            expectChats(helper, pal, List.of(chat(DistrictNoticeKind.FRIEND_ADDED, "Nt_Home", code)));

            FriendRecord friend = env.repo.friendsOf(plot).get(0);
            env.repo.setFriendSuspended(friend.id(), env.clock.get());
            // 给朋友的通知即时送达有 60 秒的间隔 (22.19): 拨过间隔再做下一步。
            env.advance(DistrictLimits.FRIEND_NOTICE_INTERVAL_MS);
            env.ctx.plotOwners().restoreFriend(admin, ABYDOS, plot, "Nt_Pal");
            expectChats(helper, owner, List.of(chat(DistrictNoticeKind.PLOT_ADMIN_FRIEND_RESTORED, "Op_Admin",
                    "Nt_Pal", code)));
            expectChats(helper, pal, List.of(chat(DistrictNoticeKind.FRIEND_RESTORED, "Nt_Home", code)));

            env.advance(DistrictLimits.FRIEND_NOTICE_INTERVAL_MS);
            env.ctx.plotOwners().removeFriend(admin, ABYDOS, plot, "Nt_Pal");
            expectChats(helper, owner, List.of(chat(DistrictNoticeKind.PLOT_ADMIN_FRIEND_REMOVED, "Op_Admin",
                    "Nt_Pal", code)));
            expectChats(helper, pal, List.of(chat(DistrictNoticeKind.FRIEND_REMOVED, "Nt_Home", code)));

            env.ctx.layout().resize(admin, ABYDOS, plot,
                    PlotLayoutService.AreaInput.of(new PlotArea(10, 10, 26, 25)));
            expectChats(helper, owner, List.of(chat(DistrictNoticeKind.PLOT_ADMIN_RESIZED, "Op_Admin", code,
                    "16 × 16", "17 × 16")));
            helper.assertTrue(drain(pal).isEmpty(), "改范围与朋友无关");
            helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Home")) == 0
                    && env.repo.countNotices(uuidOf("Nt_Pal")) == 0, "在线的都已送达, 不留行");
        } finally {
            removePlayer(helper, owner);
            removePlayer(helper, pal);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ownerActionsNotifyTheFriendButNotTheOwner(GameTestHelper helper) {
        ServerPlayer owner = null;
        ServerPlayer pal = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident(ABYDOS, "Nt_Self_Home");
            String plot = env.plot(ABYDOS, 10, 10, 25, 25);
            env.own(plot, "Nt_Self_Home");
            String code = env.plotRecord(plot).code();
            owner = onlinePlayer(helper, "Nt_Self_Home");
            pal = onlinePlayer(helper, "Nt_Self_Pal");
            drain(owner);
            drain(pal);
            Actor self = player("Nt_Self_Home");

            env.ctx.plotOwners().addFriend(self, ABYDOS, plot, "Nt_Self_Pal", false);
            expectChats(helper, pal, List.of(chat(DistrictNoticeKind.FRIEND_ADDED, "Nt_Self_Home", code)));
            env.repo.setFriendSuspended(env.repo.friendsOf(plot).get(0).id(), env.clock.get());
            env.advance(DistrictLimits.FRIEND_NOTICE_INTERVAL_MS);
            env.ctx.plotOwners().restoreFriend(self, ABYDOS, plot, "Nt_Self_Pal");
            expectChats(helper, pal, List.of(chat(DistrictNoticeKind.FRIEND_RESTORED, "Nt_Self_Home", code)));
            env.advance(DistrictLimits.FRIEND_NOTICE_INTERVAL_MS);
            env.ctx.plotOwners().removeFriend(self, ABYDOS, plot, "Nt_Self_Pal");
            expectChats(helper, pal, List.of(chat(DistrictNoticeKind.FRIEND_REMOVED, "Nt_Self_Home", code)));

            PermissionItemDef item = PermissionCatalog.memberItems().get(0);
            env.ctx.plotOwners().setPermission(self, ABYDOS, plot, item.permissionId(), "outsider",
                    !item.plotDefault(PlotAudience.OUTSIDER));
            env.ctx.plotOwners().resetPermissions(self, ABYDOS, plot);
            helper.assertTrue(drain(owner).isEmpty(), "户主自己改的, 户主什么都不收");
            helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Self_Home")) == 0, "也不排队");
        } finally {
            removePlayer(helper, owner);
            removePlayer(helper, pal);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void freezeUnfreezeReclaim(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident(ABYDOS, "Nt_Fz_Back");
            env.resident(ABYDOS, "Nt_Fz_Now");
            env.resident(ABYDOS, "Nt_Fz_Late");
            String back = env.plot(ABYDOS, 10, 10, 25, 25);
            String now = env.plot(ABYDOS, 40, 10, 55, 25);
            String late = env.plot(ABYDOS, 70, 10, 85, 25);
            env.own(back, "Nt_Fz_Back");
            env.own(now, "Nt_Fz_Now");
            env.own(late, "Nt_Fz_Late");
            for (String name : List.of("Nt_Fz_Back", "Nt_Fz_Now", "Nt_Fz_Late")) {
                env.ctx.residents().removeResident(env.admin, ABYDOS, name, "inactive", "不上线");
            }
            clearQueue(env, "Nt_Fz_Back", "Nt_Fz_Now", "Nt_Fz_Late");

            env.ctx.residents().addResident(env.admin, ABYDOS, "Nt_Fz_Back", false);
            expectQueue(helper, env, "Nt_Fz_Back", List.of(row(DistrictNoticeKind.RESIDENT_ADDED, ACADEMY, DISTRICT),
                    row(DistrictNoticeKind.RESIDENT_ADDED_FROZEN, env.plotRecord(back).code())));
            clearQueue(env, "Nt_Fz_Back");
            env.ctx.freezes().unfreeze(env.admin, ABYDOS, back);
            expectQueue(helper, env, "Nt_Fz_Back", List.of(row(DistrictNoticeKind.PLOT_UNFROZEN,
                    env.plotRecord(back).code())));

            String nowCode = env.plotRecord(now).code();
            env.ctx.freezes().reclaimNow(env.admin, ABYDOS, now);
            expectQueue(helper, env, "Nt_Fz_Now", List.of(row(DistrictNoticeKind.PLOT_RECLAIMED_NOW, nowCode)));

            String lateCode = env.plotRecord(late).code();
            env.advance(DistrictLimits.FREEZE_MS + 1);
            int reclaimed = env.ctx.freezes().reclaimExpired(null);
            helper.assertTrue(reclaimed == 1, "到期收回一块, 实为 " + reclaimed);
            expectQueue(helper, env, "Nt_Fz_Late", List.of(row(DistrictNoticeKind.PLOT_RECLAIMED_EXPIRED,
                    lateCode)));
            helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Fz_Now")) == 1, "到期收回不牵连别人");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void purchaseOpenedReachesOnlineResidentsWithoutPlotsOnly(GameTestHelper helper) {
        List<ServerPlayer> online = new ArrayList<>();
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident(ABYDOS, "Nt_Po_Buyer");
            env.resident(ABYDOS, "Nt_Po_Landed");
            env.resident(ABYDOS, "Nt_Po_Frozen");
            env.resident(ABYDOS, "Nt_Po_Away");
            String landed = env.plot(ABYDOS, 10, 10, 25, 25);
            String frozen = env.plot(ABYDOS, 40, 10, 55, 25);
            env.own(landed, "Nt_Po_Landed");
            env.own(frozen, "Nt_Po_Frozen");
            env.ctx.residents().removeResident(env.admin, ABYDOS, "Nt_Po_Frozen", "inactive", "不上线");
            env.ctx.residents().addResident(env.admin, ABYDOS, "Nt_Po_Frozen", false);
            clearQueue(env, "Nt_Po_Buyer", "Nt_Po_Landed", "Nt_Po_Frozen", "Nt_Po_Away");
            ServerPlayer buyer = onlinePlayer(helper, "Nt_Po_Buyer");
            ServerPlayer owner = onlinePlayer(helper, "Nt_Po_Landed");
            ServerPlayer former = onlinePlayer(helper, "Nt_Po_Frozen");
            online.addAll(List.of(buyer, owner, former));
            online.forEach(DistrictNoticeGameTests::drain);

            env.ctx.admin().setPurchaseOpen(env.admin, ABYDOS, true);
            expectChats(helper, buyer, List.of(chat(DistrictNoticeKind.PURCHASE_OPENED, DISTRICT,
                    DistrictTexts.formatCredit(env.districtRecord(ABYDOS).unitPrice()))));
            helper.assertTrue(drain(owner).isEmpty(), "已有地块的不发");
            helper.assertTrue(drain(former).isEmpty(), "冻结地块的原户主不发");
            helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Po_Away")) == 0, "离线的不入队 (P28)");
            env.ctx.admin().setPurchaseOpen(env.admin, ABYDOS, true);
            helper.assertTrue(drain(buyer).isEmpty(), "已经开着再开一次不发");
        } finally {
            online.forEach(player -> removePlayer(helper, player));
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void purchaseOpenedIsThrottledPerDistrict(GameTestHelper helper) {
        ServerPlayer buyer = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident(ABYDOS, "Nt_Th_Buyer");
            buyer = onlinePlayer(helper, "Nt_Th_Buyer");
            drain(buyer);
            env.ctx.admin().setPurchaseOpen(env.admin, ABYDOS, true);
            helper.assertTrue(drain(buyer).size() == 1, "第一次开放购买广播");
            env.ctx.admin().setPurchaseOpen(env.admin, ABYDOS, false);
            env.advance(DistrictLimits.PURCHASE_NOTICE_COOLDOWN_MS - 1);
            env.ctx.admin().setPurchaseOpen(env.admin, ABYDOS, true);
            helper.assertTrue(drain(buyer).isEmpty(), "10 分钟内关了又开, 不重复广播");
            env.ctx.admin().setPurchaseOpen(env.admin, ABYDOS, false);
            env.advance(1);
            env.ctx.admin().setPurchaseOpen(env.admin, ABYDOS, true);
            helper.assertTrue(keys(drain(buyer)).equals(List.of(key(DistrictNoticeKind.PURCHASE_OPENED))),
                    "过了冷却期再开, 照常广播");
        } finally {
            removePlayer(helper, buyer);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void selfActionsSendNothingExceptPurchase(GameTestHelper helper) {
        ServerPlayer opal = null;
        ServerPlayer buyer = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            opal = onlinePlayer(helper, "Nt_Opal");
            drain(opal);
            Actor self = op("Nt_Opal");
            env.ctx.residents().addResident(self, ABYDOS, "Nt_Opal", false);
            env.ctx.admin().setWarden(self, ABYDOS, "Nt_Opal");
            env.ctx.admin().setWarden(self, ABYDOS, null);
            helper.assertTrue(drain(opal).isEmpty() && env.repo.countNotices(uuidOf("Nt_Opal")) == 0,
                    "OP 把自己加进名单、任命与撤销自己: 都不发");

            env.resident(ABYDOS, "Nt_Self_Buyer");
            String plot = env.plot(ABYDOS, 10, 10, 25, 25);
            env.fund("Nt_Self_Buyer", 10_000);
            buyer = onlinePlayer(helper, "Nt_Self_Buyer");
            env.ctx.admin().setPurchaseOpen(env.admin, ABYDOS, true);
            drain(buyer);
            PlotArea area = env.area(plot);
            long price = env.districtRecord(ABYDOS).priceOf(area);
            env.ctx.market().buy(player("Nt_Self_Buyer"), buyer, ABYDOS, plot, area, price);
            expectChats(helper, buyer, List.of(chat(DistrictNoticeKind.PLOT_BOUGHT, env.plotRecord(plot).code(),
                    DistrictTexts.formatCredit(price))));
            helper.assertTrue("1,280 信用点".equals(DistrictTexts.formatCredit(price)), "金额按信用点格式化");
        } finally {
            removePlayer(helper, opal);
            removePlayer(helper, buyer);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void deliveryInOneTransactionIsExactlyOnce(GameTestHelper helper) {
        ServerPlayer once = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident(ABYDOS, "Nt_Once");
            String plot = env.plot(ABYDOS, 10, 10, 25, 25);
            env.own(plot, "Nt_Once");
            once = onlinePlayer(helper, "Nt_Once");
            drain(once);
            String code = env.plotRecord(plot).code();

            // 一个事务里给同一人两条: 提交后只投一次, 两条各一次。
            env.ctx.residents().removeResident(env.admin, ABYDOS, "Nt_Once", "other", "换学院");
            expectChats(helper, once, List.of(chat(DistrictNoticeKind.RESIDENT_REMOVED_OTHER, ACADEMY),
                    chat(DistrictNoticeKind.PLOT_FROZEN, code, String.valueOf(DistrictLimits.FREEZE_DAYS))));
            env.ctx.residents().addResident(env.admin, ABYDOS, "Nt_Once", false);
            expectChats(helper, once, List.of(chat(DistrictNoticeKind.RESIDENT_ADDED, ACADEMY, DISTRICT),
                    chat(DistrictNoticeKind.RESIDENT_ADDED_FROZEN, code)));
            helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Once")) == 0, "都送达了");

            removePlayer(helper, once);
            once = onlinePlayer(helper, "Nt_Once");
            helper.assertTrue(drain(once).isEmpty(), "已送达的上线不重发");
        } finally {
            removePlayer(helper, once);
        }
        helper.succeed();
    }

    // ================================================================
    // 保留与上限
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void capAndRetentionDropTheOldest(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            UUID flood = uuidOf("Nt_Flood");
            boolean refused = false;
            try {
                env.ctx.notices().enqueue(flood, DistrictNoticeKind.WARDEN_APPOINTED, List.of("区"), null, null);
            } catch (IllegalStateException expected) {
                refused = true;
            }
            helper.assertTrue(refused, "事务之外入队是代码错误");
            for (int i = 1; i <= 35; i++) {
                String name = "区" + i;
                env.repo.inTransaction(() -> {
                    env.ctx.notices().enqueue(flood, DistrictNoticeKind.WARDEN_APPOINTED, List.of(name), null, null);
                    return null;
                });
            }
            List<NoticeRecord> kept = env.repo.noticesFor(flood);
            helper.assertTrue(kept.size() == DistrictLimits.NOTICE_KEEP_PER_PLAYER
                            && kept.get(0).args().equals(List.of("区6"))
                            && kept.get(kept.size() - 1).args().equals(List.of("区35")),
                    "每人最多 30 条, 超出删最旧的, 实为 " + kept.size() + " 条 " + kept.get(0).args());

            env.advance(31L * 24L * 3_600_000L);
            env.repo.inTransaction(() -> {
                env.ctx.notices().enqueue(flood, DistrictNoticeKind.WARDEN_APPOINTED, List.of("新"), null, null);
                return null;
            });
            DistrictSystem.sweep("gametest");
            List<NoticeRecord> left = env.repo.noticesFor(flood);
            helper.assertTrue(left.size() == 1 && left.get(0).args().equals(List.of("新")),
                    "定时节拍清掉 30 天前的, 新的留着, 实为 " + left.size());
        }
        helper.succeed();
    }

    // ================================================================
    // 朋友通知防刷 (22.19)
    // ================================================================

    /**
     * 户主对一个离线的人反复加、移朋友 20 轮: 加了又移互相抵消, 队列里 TA 的移出、冻结通知原样留着; 再加一次只留一条
     * friend_added。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void friendAddRemoveFloodKeepsImportantNotices(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident(ABYDOS, "Nt_Fl_Owner");
            env.resident(ABYDOS, "Nt_Fl_Victim");
            String plot = env.plot(ABYDOS, 10, 10, 25, 25);
            String victimPlot = env.plot(ABYDOS, 40, 10, 55, 25);
            env.own(plot, "Nt_Fl_Owner");
            env.own(victimPlot, "Nt_Fl_Victim");
            String victimCode = env.plotRecord(victimPlot).code();
            clearQueue(env, "Nt_Fl_Victim");
            env.ctx.residents().removeResident(env.admin, ABYDOS, "Nt_Fl_Victim", "inactive", "不上线");
            List<String> important = List.of(row(DistrictNoticeKind.RESIDENT_REMOVED_INACTIVE, ACADEMY),
                    row(DistrictNoticeKind.PLOT_FROZEN, victimCode, String.valueOf(DistrictLimits.FREEZE_DAYS)));
            expectQueue(helper, env, "Nt_Fl_Victim", important);

            Actor owner = player("Nt_Fl_Owner");
            for (int i = 0; i < 20; i++) {
                env.ctx.plotOwners().addFriend(owner, ABYDOS, plot, "Nt_Fl_Victim", false);
                env.ctx.plotOwners().removeFriend(owner, ABYDOS, plot, "Nt_Fl_Victim");
            }
            expectQueue(helper, env, "Nt_Fl_Victim", important);
            env.ctx.plotOwners().addFriend(owner, ABYDOS, plot, "Nt_Fl_Victim", false);
            String code = env.plotRecord(plot).code();
            List<String> withFriend = new ArrayList<>(important);
            withFriend.add(row(DistrictNoticeKind.FRIEND_ADDED, "Nt_Fl_Owner", code));
            expectQueue(helper, env, "Nt_Fl_Victim", withFriend);
            env.ctx.plotOwners().removeFriend(owner, ABYDOS, plot, "Nt_Fl_Victim");
            env.ctx.plotOwners().addFriend(owner, ABYDOS, plot, "Nt_Fl_Victim", false);
            expectQueue(helper, env, "Nt_Fl_Victim", withFriend);
        }
        helper.succeed();
    }

    /** 队列超出上限时先删朋友通知 (最旧的先删), 冻结这类要紧的留着; 全是要紧的才删最旧的要紧的。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void capEvictsFriendNoticesFirst(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            UUID target = uuidOf("Nt_Cap_Target");
            env.repo.inTransaction(() -> {
                env.ctx.notices().enqueue(target, DistrictNoticeKind.PLOT_FROZEN, List.of("地块", "7"), null, "p0");
                return null;
            });
            for (int i = 1; i <= 35; i++) {
                String plotId = "p" + i;
                env.repo.inTransaction(() -> {
                    env.ctx.notices().enqueue(target, DistrictNoticeKind.FRIEND_ADDED, List.of("户主", plotId), null,
                            plotId);
                    return null;
                });
            }
            List<NoticeRecord> kept = env.repo.noticesFor(target);
            helper.assertTrue(kept.size() == DistrictLimits.NOTICE_KEEP_PER_PLAYER
                            && kept.get(0).kind().equals(DistrictNoticeKind.PLOT_FROZEN.wire())
                            && kept.get(1).args().equals(List.of("户主", "p7"))
                            && kept.get(kept.size() - 1).args().equals(List.of("户主", "p35")),
                    "冻结通知留着, 朋友通知删最旧的, 实为 " + kept.size() + " 条, 第二条 " + kept.get(1).args());
            for (int i = 1; i <= 30; i++) {
                String name = "区" + i;
                env.repo.inTransaction(() -> {
                    env.ctx.notices().enqueue(target, DistrictNoticeKind.WARDEN_APPOINTED, List.of(name), null, null);
                    return null;
                });
            }
            List<String> kinds = env.repo.noticesFor(target).stream().map(NoticeRecord::kind).toList();
            helper.assertTrue(kinds.size() == DistrictLimits.NOTICE_KEEP_PER_PLAYER
                            && !kinds.contains(DistrictNoticeKind.FRIEND_ADDED.wire())
                            && !kinds.contains(DistrictNoticeKind.PLOT_FROZEN.wire()),
                    "全是要紧的之后才删最旧的要紧的, 实为 " + kinds);
        }
        helper.succeed();
    }

    /**
     * 在线的朋友: 第一条即时送达; 60 秒内接着来的留在队列里、合并成净变化 (移了又加 = 没变), 间隔到了由定时节拍补发。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void friendNoticesToAnOnlinePlayerAreSpacedAndNetted(GameTestHelper helper) {
        ServerPlayer pal = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident(ABYDOS, "Nt_Sp_Owner");
            String plot = env.plot(ABYDOS, 10, 10, 25, 25);
            env.own(plot, "Nt_Sp_Owner");
            String code = env.plotRecord(plot).code();
            pal = onlinePlayer(helper, "Nt_Sp_Pal");
            drain(pal);
            Actor owner = player("Nt_Sp_Owner");

            env.ctx.plotOwners().addFriend(owner, ABYDOS, plot, "Nt_Sp_Pal", false);
            expectChats(helper, pal, List.of(chat(DistrictNoticeKind.FRIEND_ADDED, "Nt_Sp_Owner", code)));
            env.ctx.plotOwners().removeFriend(owner, ABYDOS, plot, "Nt_Sp_Pal");
            helper.assertTrue(drain(pal).isEmpty(), "60 秒内的第二条不即时送");
            expectQueue(helper, env, "Nt_Sp_Pal", List.of(row(DistrictNoticeKind.FRIEND_REMOVED, "Nt_Sp_Owner", code)));
            env.ctx.plotOwners().addFriend(owner, ABYDOS, plot, "Nt_Sp_Pal", false);
            expectQueue(helper, env, "Nt_Sp_Pal", List.of());
            env.ctx.plotOwners().removeFriend(owner, ABYDOS, plot, "Nt_Sp_Pal");
            DistrictSystem.sweep("gametest");
            helper.assertTrue(drain(pal).isEmpty(), "间隔没到, 定时节拍也不补发");

            env.advance(DistrictLimits.FRIEND_NOTICE_INTERVAL_MS);
            DistrictSystem.sweep("gametest");
            expectChats(helper, pal, List.of(chat(DistrictNoticeKind.FRIEND_REMOVED, "Nt_Sp_Owner", code)));
            helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Sp_Pal")) == 0, "补发之后行已删");
        } finally {
            removePlayer(helper, pal);
        }
        helper.succeed();
    }

    // ================================================================
    // 登录门接缝
    // ================================================================

    /**
     * 默认 gate 在不验证身份的服务器上 (离线模式、没有登录门) 一条都不发 (22.19): 提交后不发、上线也不发, 行留着;
     * 验证身份的服务器上 (GameTest 服务端也算) 上线补发。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void defaultGateHoldsNoticesWithoutVerifiedIdentity(GameTestHelper helper) {
        ServerPlayer held = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            held = onlinePlayer(helper, "Nt_Held");
            drain(held);
            helper.assertTrue(DefaultNoticeGate.identityVerifiedByServer(held), "GameTest 服务端当作已验证");
            DefaultNoticeGate offlineMode = new DefaultNoticeGate(player -> false);
            try (NoticeDeliveryGates.Restore ignored = NoticeDeliveryGates.swapForTest(offlineMode)) {
                env.ctx.residents().addResident(env.admin, ABYDOS, "Nt_Held", false);
                helper.assertTrue(drain(held).isEmpty(), "离线模式: 提交后不发");
                removePlayer(helper, held);
                held = onlinePlayer(helper, "Nt_Held");
                helper.assertTrue(drain(held).isEmpty(), "离线模式: 上线 (/login 之前) 也不发");
                helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Held")) == 1, "行留在队列里");
            }
            removePlayer(helper, held);
            held = onlinePlayer(helper, "Nt_Held");
            helper.assertTrue(keys(drain(held)).equals(List.of(HEADER, key(DistrictNoticeKind.RESIDENT_ADDED))),
                    "验证身份之后上线补发");
        } finally {
            removePlayer(helper, held);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void deliveryWaitsForTheGate(GameTestHelper helper) {
        ServerPlayer gated = null;
        TestGate gate = new TestGate();
        try (DistrictTestEnv env = DistrictTestEnv.open();
             NoticeDeliveryGates.Restore ignored = NoticeDeliveryGates.swapForTest(gate)) {
            env.abydos();
            gated = onlinePlayer(helper, "Nt_Gated");
            drain(gated);

            env.ctx.residents().addResident(env.admin, ABYDOS, "Nt_Gated", false);
            helper.assertTrue(drain(gated).isEmpty(), "gate 没放行: 提交后不发");
            helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Gated")) == 1, "行还在");

            removePlayer(helper, gated);
            gated = onlinePlayer(helper, "Nt_Gated");
            helper.assertTrue(drain(gated).isEmpty(), "登录确认之前重新上线也不发 (登录门的 gate 在进服时什么都不做)");
            env.ctx.admin().setPurchaseOpen(env.admin, ABYDOS, true);
            helper.assertTrue(drain(gated).isEmpty(), "只发在线的广播同样先问 gate");

            gate.open = true;
            helper.assertTrue(gate.callback != null, "register 登记的回调已交给测试 gate");
            gate.callback.accept(gated);
            helper.assertTrue(keys(drain(gated)).equals(List.of(HEADER, key(DistrictNoticeKind.RESIDENT_ADDED))),
                    "登录确认之后补发");
            helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Gated")) == 0, "补发之后行已删");
            gate.callback.accept(gated);
            helper.assertTrue(drain(gated).isEmpty(), "登录确认重复触发无害 (22.12 第 3 条)");
        } finally {
            removePlayer(helper, gated);
        }
        helper.assertTrue(NoticeDeliveryGates.current() != gate, "用例结束换回原来的 gate");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void loginGateSeamIsWiredWhenPresent(GameTestHelper helper) {
        boolean loginGatePresent = NoticeDeliveryGates.loginGatePresent();
        NoticeDeliveryGate current = NoticeDeliveryGates.current();
        if (loginGatePresent) {
            helper.assertTrue(!(current instanceof DefaultNoticeGate),
                    "合并提醒 (22.12): 登录门 PlayerLoginGate 已在, 通知却仍是上线即发; 按 22.12 的清单装上按登录确认判定的 "
                            + "gate");
        } else {
            helper.assertTrue(current instanceof DefaultNoticeGate,
                    "本分支没有登录门: 装的应是 DefaultNoticeGate, 实为 " + current.getClass().getName());
        }
        helper.succeed();
    }

    /**
     * 生产装的 gate 接在真登录门上 (22.12 合并清单第 6 条): 没通过登录门的连接进服、以及进服之后提交的通知都不发, 行留着;
     * 判定翻为放行之后由登录门自己的逐 tick 巡检触发补发。
     *
     * 独占一个 batch: 夹具把测试 context 挂在进程级的 DistrictServices 上, 这条用例要跨 tick 等巡检, 不能与同 batch
     * 里别的用例交错。到点仍没等到也先收夹具再失败, 不把测试 context 留给后面的 batch。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = LOGIN_GATE_BATCH, timeoutTicks = 100)
    public static void installedGateHoldsNoticesUntilTheLoginGateConfirms(GameTestHelper helper) {
        helper.assertTrue(NoticeDeliveryGates.current() instanceof LoginGateNoticeGate,
                "生产装的应是 LoginGateNoticeGate, 实为 " + NoticeDeliveryGates.current().getClass().getName());
        DistrictTestEnv env = DistrictTestEnv.open();
        ServerPlayer[] waiting = new ServerPlayer[1];
        boolean[] cleaned = {false};
        Runnable cleanup = () -> {
            if (!cleaned[0]) {
                cleaned[0] = true;
                try {
                    removePlayer(helper, waiting[0]);
                } finally {
                    env.close();
                }
            }
        };
        try {
            env.abydos();
            env.ctx.residents().addResident(env.admin, ABYDOS, "Nt_Lg_Wait", true);
            try (PlayerLoginGate.ForcedVerdict ignored = PlayerLoginGate.forceVerdictForTest(uuidOf("Nt_Lg_Wait"),
                    PlayerLoginGate.Verdict.NOT_LOGGED_IN)) {
                waiting[0] = onlinePlayer(helper, "Nt_Lg_Wait");
                helper.assertTrue(drain(waiting[0]).isEmpty(), "没通过登录门: 进服时不补发");
                env.resident(ABYDOS, "Nt_Lg_Owner");
                String plot = env.plot(ABYDOS, 10, 10, 25, 25);
                env.own(plot, "Nt_Lg_Owner");
                env.ctx.plotOwners().addFriend(player("Nt_Lg_Owner"), ABYDOS, plot, "Nt_Lg_Wait", false);
                helper.assertTrue(drain(waiting[0]).isEmpty(), "没通过登录门: 提交后的即时投递也不发");
                helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Lg_Wait")) == 2, "两行都留在队列里, 实为 "
                        + env.repo.countNotices(uuidOf("Nt_Lg_Wait")));
            }
        } catch (RuntimeException | Error failure) {
            cleanup.run();
            throw failure;
        }

        List<String> delivered = new ArrayList<>();
        int[] ticksWaited = {0};
        List<String> expected = List.of(HEADER, key(DistrictNoticeKind.RESIDENT_ADDED),
                key(DistrictNoticeKind.FRIEND_ADDED));
        helper.succeedWhen(() -> {
            if (!cleaned[0]) {
                delivered.addAll(keys(drain(waiting[0])));
            }
            boolean arrived = delivered.equals(expected);
            boolean queueEmpty = !cleaned[0] && env.repo.countNotices(uuidOf("Nt_Lg_Wait")) == 0;
            if (arrived || ++ticksWaited[0] >= LOGIN_GATE_WAIT_TICKS) {
                cleanup.run();
            }
            helper.assertTrue(arrived, "登录确认之后应按发生顺序补发 " + expected + ", 实为 " + delivered);
            helper.assertTrue(queueEmpty, "补发之后行已删");
        });
    }

    // ================================================================
    // 失败与坏行
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void noticeFailuresNeverBreakLogin(GameTestHelper helper) {
        ServerPlayer stuck = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.ctx.residents().addResident(env.admin, ABYDOS, "Nt_Stuck", true);
            env.exec("CREATE TEMP TRIGGER gt_notice_keep BEFORE DELETE ON district_notice "
                    + "BEGIN SELECT RAISE(ABORT, 'injected failure'); END");

            stuck = onlinePlayer(helper, "Nt_Stuck");
            helper.assertTrue(keys(drain(stuck)).equals(List.of(HEADER, key(DistrictNoticeKind.RESIDENT_ADDED))),
                    "删除失败不挡发送");
            helper.assertTrue(env.member("Nt_Stuck").syncStatus() == ResidentSyncStatus.SYNCED,
                    "首次登录补写照常完成");
            helper.assertTrue(env.repo.seenByUuid(uuidOf("Nt_Stuck")).isPresent(), "登录照常记见过的玩家表");
            helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Stuck")) == 1, "删不掉的行留着 (至少送达一次)");

            env.exec("DROP TRIGGER gt_notice_keep");
            removePlayer(helper, stuck);
            stuck = onlinePlayer(helper, "Nt_Stuck");
            helper.assertTrue(keys(drain(stuck)).equals(List.of(HEADER, key(DistrictNoticeKind.RESIDENT_ADDED))),
                    "下次上线重发一次");
            helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Stuck")) == 0, "这次删掉了");
        } finally {
            removePlayer(helper, stuck);
        }
        helper.succeed();
    }

    /**
     * 库在 user_version 9 却没有 district_notice 表 (用阶段 1–2 的开发构建开过的存档, 22.19): 开服选成降级 (只读, 原因写明
     * 缺表, 不碰 Flan); 通知停用之后加住户、首次登录换键、移出、定时节拍照常, 不报 STORE_FAILED; 日志里给的两条补建语句
     * 建出来的结构与迁移建的逐字相同。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void missingNoticeTableDegradesInsteadOfFailing(GameTestHelper helper) throws SQLException {
        GatewaySelector.Selection selection = DistrictSystem.selectFor(helper.getLevel().getServer(), false);
        helper.assertTrue(selection.state() == DistrictFeature.State.DEGRADED
                        && DistrictTexts.NOTICE_TABLE_MISSING.equals(selection.reason())
                        && selection.gateway() instanceof DisabledFlanGateway,
                "缺表: 降级、原因写明、Disabled 网关, 实为 " + selection.state() + " " + selection.reason());
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            List<String> original = noticeSchema(env);
            helper.assertTrue(original.size() == 2, "迁移建了表与索引, 实为 " + original);
            env.exec("DROP TABLE " + DistrictSystem.NOTICE_TABLE);
            helper.assertTrue(!SchemaMigrator.tableExists(env.repo.connection(), DistrictSystem.NOTICE_TABLE),
                    "前提: 表没了");
            env.ctx.notices().disableStore();

            env.ctx.residents().addResident(env.admin, ABYDOS, "Nt_NoTable", true);
            env.ctx.firstLogin().onLogin(uuidOf("nt_notable"), "nt_notable");
            helper.assertTrue(env.repo.memberByUuid(uuidOf("nt_notable")).isPresent(),
                    "首次登录换键照常 (通知的换键跳过)");
            env.ctx.residents().removeResident(env.admin, ABYDOS, "nt_notable", "other", "换学院");
            helper.assertTrue(env.repo.memberByUuid(uuidOf("nt_notable")).isEmpty(), "移出照常");
            DistrictSystem.sweep("gametest");

            for (String sql : DistrictSystem.NOTICE_TABLE_REPAIR_SQL) {
                env.exec(sql);
            }
            helper.assertTrue(noticeSchema(env).equals(original),
                    "日志给的补建语句建出来的结构与迁移逐字相同, 实为 " + noticeSchema(env));
        }
        helper.succeed();
    }

    private static List<String> noticeSchema(DistrictTestEnv env) throws SQLException {
        List<String> sql = new ArrayList<>();
        try (Statement statement = env.repo.connection().createStatement();
             ResultSet rows = statement.executeQuery("SELECT sql FROM sqlite_master WHERE tbl_name = '"
                     + DistrictSystem.NOTICE_TABLE + "' ORDER BY name")) {
            while (rows.next()) {
                sql.add(rows.getString(1));
            }
        }
        return sql;
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unknownKindIsDroppedWithAWarning(GameTestHelper helper) {
        ServerPlayer odd = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String uuid = uuidOf("Nt_Odd").toString();
            String insert = "INSERT INTO district_notice (recipient_uuid, kind, args_json, created_at) VALUES ('"
                    + uuid + "', ";
            env.exec(insert + "'nope', '[]', " + env.clock.get() + ")");
            env.exec(insert + "'warden_appointed', '{bad', " + env.clock.get() + ")");
            env.exec(insert + "'warden_appointed', '[\"甲\", \"乙\"]', " + env.clock.get() + ")");
            env.exec(insert + "'purchase_opened', '[\"甲\", \"乙\"]', " + env.clock.get() + ")");
            env.exec(insert + "'warden_appointed', '[\"好区\"]', " + env.clock.get() + ")");

            odd = onlinePlayer(helper, "Nt_Odd");
            List<Chat> chats = drain(odd);
            helper.assertTrue(keys(chats).equals(List.of(HEADER, key(DistrictNoticeKind.WARDEN_APPOINTED)))
                            && chats.get(0).args().equals(List.of("1"))
                            && chats.get(1).args().equals(List.of("好区")),
                    "不认识的 kind、坏参数、参数个数不对、不该入队的 kind 都不发, 头一行只数发出的, 实为 " + chats);
            helper.assertTrue(env.repo.countNotices(uuidOf("Nt_Odd")) == 0, "坏行一并删掉");
        } finally {
            removePlayer(helper, odd);
        }
        helper.succeed();
    }

    // ================================================================
    // 工具
    // ================================================================

    /** 测试 gate: 像登录门那样, 进服时什么都不做, 放行由测试决定。 */
    private static final class TestGate implements NoticeDeliveryGate {
        volatile boolean open;
        @Nullable
        volatile Consumer<ServerPlayer> callback;

        @Override
        public boolean canDeliverNow(ServerPlayer player) {
            return open;
        }

        @Override
        public void install(Consumer<ServerPlayer> deliverPending) {
            this.callback = deliverPending;
        }

        @Override
        public void onPlayerJoined(ServerPlayer player) {
        }
    }

    /** 一条聊天栏 / 动作栏里的通知: 键、参数 (字符串)、是否动作栏、颜色。 */
    private record Chat(String key, List<String> args, boolean overlay, @Nullable TextColor color) {
    }

    /** 读空 mock 连接的出站队列, 按顺序返回其中 district.miningdim.notice.* 的系统消息。 */
    private static List<Chat> drain(ServerPlayer player) {
        EmbeddedChannel channel = (EmbeddedChannel) player.connection.connection.channel();
        List<Chat> chats = new ArrayList<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            try {
                if (outbound instanceof ClientboundSystemChatPacket packet
                        && packet.content().getContents() instanceof TranslatableContents translatable
                        && translatable.getKey().startsWith(DistrictNoticeKind.KEY_PREFIX)) {
                    List<String> args = new ArrayList<>();
                    for (Object arg : translatable.getArgs()) {
                        args.add(arg instanceof Component component ? component.getString() : String.valueOf(arg));
                    }
                    chats.add(new Chat(translatable.getKey(), args, packet.overlay(),
                            packet.content().getStyle().getColor()));
                }
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        }
        return chats;
    }

    private static List<String> keys(List<Chat> chats) {
        return chats.stream().map(Chat::key).toList();
    }

    private static String key(DistrictNoticeKind kind) {
        return kind.key();
    }

    private static TextColor color(ChatFormatting formatting) {
        return TextColor.fromLegacyFormat(formatting);
    }

    /** 期望的一条聊天: 键与参数, 颜色取这种通知的颜色, 在聊天栏。 */
    private static Chat chat(DistrictNoticeKind kind, String... args) {
        return new Chat(kind.key(), List.of(args), false, color(kind.color()));
    }

    /** 这名在线玩家从上次读到现在恰好收到这几条 (键、参数、颜色、聊天栏都对)。 */
    private static void expectChats(GameTestHelper helper, ServerPlayer player, List<Chat> expected) {
        List<Chat> actual = drain(player);
        helper.assertTrue(actual.equals(expected), player.getGameProfile().getName() + " 应恰好收到 " + expected
                + ", 实为 " + actual);
    }

    /** 期望的一行队列: kind 与参数。 */
    private static String row(DistrictNoticeKind kind, String... args) {
        return kind.wire() + List.of(args);
    }

    /** 这名离线玩家的队列恰好是这几行 (按发生顺序)。 */
    private static void expectQueue(GameTestHelper helper, DistrictTestEnv env, String name, List<String> expected) {
        List<String> actual = new ArrayList<>();
        for (NoticeRecord notice : env.repo.noticesFor(uuidOf(name))) {
            actual.add(notice.kind() + notice.args());
        }
        helper.assertTrue(actual.equals(expected), name + " 的队列应为 " + expected + ", 实为 " + actual);
    }

    private static List<String> kindsOf(DistrictTestEnv env, String name) {
        return env.repo.noticesFor(uuidOf(name)).stream().map(NoticeRecord::kind).toList();
    }

    /** 清空这些人排着的通知 (造数据时产生的, 与本条用例要看的无关)。 */
    private static void clearQueue(DistrictTestEnv env, String... names) {
        for (String name : names) {
            List<Long> ids = env.repo.noticesFor(uuidOf(name)).stream().map(NoticeRecord::id).toList();
            env.repo.deleteNotices(ids);
        }
    }
}
