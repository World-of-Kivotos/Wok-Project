package com.miningdim.district.service;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.core.FriendRecord;
import com.miningdim.district.core.FriendSyncStatus;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.ResidentSyncStatus;
import com.miningdim.district.core.SeenPlayer;
import com.miningdim.district.flan.FlanGroupNames;
import com.miningdim.testutil.TempStoreDb;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;
import java.util.UUID;

import static com.miningdim.district.DistrictTestEnv.T0;
import static com.miningdim.district.DistrictTestEnv.onlinePlayer;
import static com.miningdim.district.DistrictTestEnv.player;
import static com.miningdim.district.DistrictTestEnv.removePlayer;
import static com.miningdim.district.DistrictTestEnv.uuidOf;

/**
 * 从没进过服的玩家 (设计文档第十章): 见过的玩家表、首次登录补写待生效的住户与朋友、大小写不同的名字换键 (含区务长)、
 * 只处理未解绑的自管区、不写记录行, 以及老玩家的一次性回填。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class DistrictFirstLoginGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_first_login";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void loginRecordsSeenAndLogoutUpdatesLastSeen(GameTestHelper helper) {
        ServerPlayer tess = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            // 真实的登录事件经 DistrictSystem 的钩子作用在夹具换上的测试 context 上。
            tess = onlinePlayer(helper, "Login_Tess");
            Optional<SeenPlayer> seen = env.repo.seenByUuid(uuidOf("Login_Tess"));
            helper.assertTrue(seen.isPresent() && seen.get().firstSeenAt() == T0 && seen.get().lastSeenAt() == T0
                            && "login".equals(seen.get().source()) && "Login_Tess".equals(seen.get().name()),
                    "登录时记入见过的玩家表, 实为 " + seen);
            env.advance(5000);
            removePlayer(helper, tess);
            tess = null;
            helper.assertTrue(env.repo.seenByUuid(uuidOf("Login_Tess")).map(SeenPlayer::lastSeenAt).orElse(0L)
                    == T0 + 5000, "登出时更新最后在线时间");
        } finally {
            removePlayer(helper, tess);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pendingResidentBecomesSyncedOnFirstLogin(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.ctx.residents().addResident(env.admin, "abydos", "brand_new_kid", true);
            UUID uuid = uuidOf("brand_new_kid");
            FirstLoginActivation.Activation activation = env.ctx.firstLogin().onLogin(uuid, "brand_new_kid");
            helper.assertTrue(activation.memberActivated() && !activation.rekeyed(), "名单行被激活, 同一个 UUID 不换键");
            MemberRecord member = env.member("brand_new_kid");
            helper.assertTrue(member.syncStatus() == ResidentSyncStatus.SYNCED && member.syncError() == null,
                    "首次登录补写后为已生效, 实为 " + member);
            UUID districtClaim = env.districtRecord("abydos").flanClaimId();
            UUID plotClaim = env.plotRecord(plot).flanClaimId();
            helper.assertTrue(env.recording().membersOf(districtClaim).containsKey(uuid)
                            && FlanGroupNames.plotResident(plot).equals(env.recording().membersOf(plotClaim).get(uuid)),
                    "写进本区居民组与每块地的居民组");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pendingResidentBecomesFailedWhenGatewayFails(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.ctx.residents().addResident(env.admin, "abydos", "brand_new_kid", true);
            env.recording().failNext("setMember", "区块未加载");
            env.ctx.firstLogin().onLogin(uuidOf("brand_new_kid"), "brand_new_kid");
            MemberRecord member = env.member("brand_new_kid");
            helper.assertTrue(member.syncStatus() == ResidentSyncStatus.FAILED && "区块未加载".equals(member.syncError()),
                    "首次登录补写失败时为同步失败并带原因, 实为 " + member);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pendingFriendBecomesSyncedAndPlotRewritten(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_One");
            env.ctx.plotOwners().addFriend(player("Owner_One"), "abydos", plot, "late_friend", true);
            UUID claim = env.plotRecord(plot).flanClaimId();
            helper.assertTrue(!env.recording().membersOf(claim).containsKey(uuidOf("late_friend")), "登录前不在朋友组");
            FirstLoginActivation.Activation activation = env.ctx.firstLogin().onLogin(uuidOf("Late_Friend"),
                    "Late_Friend");
            helper.assertTrue(activation.plotsRewritten().contains(plot), "有朋友被激活的地块整块重写");
            helper.assertTrue(env.repo.countNotices(uuidOf("late_friend")) == 0
                            && env.repo.noticesFor(uuidOf("Late_Friend")).stream().map(n -> n.kind()).toList()
                            .equals(java.util.List.of("friend_added")),
                    "待生效朋友的通知跟着朋友行换键 (22.11)");
            FriendRecord friend = env.repo.friendsOf(plot).get(0);
            helper.assertTrue(friend.syncStatus() == FriendSyncStatus.SYNCED && friend.uuid().equals(uuidOf("Late_Friend"))
                            && "Late_Friend".equals(friend.name()),
                    "朋友行按小写名换键到登录者并改为已生效, 实为 " + friend);
            helper.assertTrue(FlanGroupNames.plotFriend(plot).equals(
                            env.recording().membersOf(claim).get(uuidOf("Late_Friend"))),
                    "登录者进了朋友组");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void caseVariantNameIsRekeyedIncludingWarden(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.ctx.residents().addResident(env.admin, "abydos", "pebble_fox", true);
            env.warden("abydos", "pebble_fox");
            UUID real = uuidOf("Pebble_Fox");
            helper.assertTrue(!real.equals(uuidOf("pebble_fox")), "离线 UUID 与名字的大小写有关");
            helper.assertTrue(env.repo.countNotices(uuidOf("pebble_fox")) == 2, "加入与任命两条通知按输入名字的离线 UUID 排着");
            FirstLoginActivation.Activation activation = env.ctx.firstLogin().onLogin(real, "Pebble_Fox");
            helper.assertTrue(env.repo.countNotices(uuidOf("pebble_fox")) == 0
                            && env.repo.noticesFor(real).stream().map(n -> n.kind()).toList()
                            .equals(java.util.List.of("resident_added", "warden_appointed")),
                    "通知行跟着换键到登录者 (22.11), 顺序不变");
            helper.assertTrue(activation.rekeyed() && activation.memberActivated(), "按小写名匹配待生效行并换键");
            MemberRecord member = env.repo.memberByUuid(real).orElse(null);
            helper.assertTrue(member != null && "Pebble_Fox".equals(member.name())
                            && member.syncStatus() == ResidentSyncStatus.SYNCED,
                    "名单行换到登录者的 UUID 与规范名, 实为 " + member);
            helper.assertTrue(env.repo.memberByUuid(uuidOf("pebble_fox")).isEmpty(), "旧键不再存在");
            helper.assertTrue(real.equals(env.districtRecord("abydos").wardenUuid())
                            && "Pebble_Fox".equals(env.districtRecord("abydos").wardenName()),
                    "区务长一并换键");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void firstLoginWritesNoLogRows(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.ctx.residents().addResident(env.admin, "abydos", "brand_new_kid", true);
            int rows = env.repo.districtLog("abydos", 100).size();
            env.ctx.firstLogin().onLogin(uuidOf("brand_new_kid"), "brand_new_kid");
            helper.assertTrue(env.repo.districtLog("abydos", 100).size() == rows, "首次登录补写不写记录行");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unboundDistrictsAreSkipped(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.ctx.residents().addResident(env.admin, "abydos", "brand_new_kid", true);
            env.ctx.admin().unbind(env.admin, "abydos");
            env.recording().clear();
            FirstLoginActivation.Activation activation = env.ctx.firstLogin().onLogin(uuidOf("brand_new_kid"),
                    "brand_new_kid");
            helper.assertTrue(!activation.memberActivated(), "已解绑学院的待生效成员不补写");
            helper.assertTrue(env.member("brand_new_kid").syncStatus() == ResidentSyncStatus.PENDING, "仍是待生效");
            helper.assertTrue(env.recording().calls().isEmpty(), "不写任何 Flan");
            helper.assertTrue(env.repo.seenByUuid(uuidOf("brand_new_kid")).isPresent(), "见过的玩家表照记");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void backfillReadsPlayerDataOnce(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            UUID known = uuidOf("Old_Timer");
            UUID nameless = uuidOf("No_Name");
            Path knownFile = dir.resolve(known + ".dat");
            Files.writeString(knownFile, "x");
            Files.setLastModifiedTime(knownFile, FileTime.fromMillis(T0 - 86_400_000L));
            Files.writeString(dir.resolve(nameless + ".dat"), "x");
            Files.writeString(dir.resolve("not-a-uuid.dat"), "x");
            Files.writeString(dir.resolve(known + ".dat_old"), "x");

            SeenPlayerBackfill.Result first = SeenPlayerBackfill.runOnce(env.repo, dir,
                    uuid -> uuid.equals(known) ? Optional.of("Old_Timer") : Optional.empty(), T0);
            helper.assertTrue(!first.alreadyDone() && first.inserted() == 1,
                    "只回填取得到名字的玩家, 实为 " + first);
            SeenPlayer seen = env.repo.seenByUuid(known).orElse(null);
            helper.assertTrue(seen != null && "backfill".equals(seen.source()) && seen.lastSeenAt() == T0 - 86_400_000L
                            && seen.firstSeenAt() == T0 - 86_400_000L,
                    "首次与最后在线时间都取文件的修改时间, 实为 " + seen);
            SeenPlayerBackfill.Result second = SeenPlayerBackfill.runOnce(env.repo, dir,
                    uuid -> Optional.of("Anyone"), T0);
            helper.assertTrue(second.alreadyDone() && second.inserted() == 0, "只做一次 (StoreMeta 标记)");
            helper.assertTrue(env.repo.seenByUuid(nameless).isEmpty(), "第二次也不会补");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        } finally {
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void playerDataFallbackRecognizesOldPlayers(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            UUID old = uuidOf("Old_Timer");
            ServerPlayerDirectory directory = new ServerPlayerDirectory(env.repo, name -> null, old::equals,
                    env.clock::get);
            PlayerDirectory.Resolved resolved = directory.resolve("Old_Timer");
            helper.assertTrue(resolved.known() && resolved.uuid().equals(old) && "Old_Timer".equals(resolved.name()),
                    "playerdata 兜底: 离线 UUID 的存档文件存在即进过服");
            helper.assertTrue(env.repo.seenByUuid(old).map(SeenPlayer::source).orElse("").equals("backfill"),
                    "命中时顺手补一行 backfill");
            PlayerDirectory.Resolved wrongCase = directory.resolve("old_timer");
            helper.assertTrue(wrongCase.known() && "Old_Timer".equals(wrongCase.name()),
                    "补过之后按小写名从见过的玩家表认出, 规范名取表里的写法");
            PlayerDirectory.Resolved stranger = directory.resolve("Never_Seen");
            helper.assertTrue(!stranger.known() && stranger.uuid().equals(uuidOf("Never_Seen")),
                    "都查不到时按输入算离线 UUID");
        }
        helper.succeed();
    }
}
