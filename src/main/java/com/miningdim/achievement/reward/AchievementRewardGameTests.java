package com.miningdim.achievement.reward;

import com.google.gson.JsonObject;
import com.miningdim.achievement.AchievementIds;
import com.miningdim.achievement.AchievementServices;
import com.miningdim.achievement.command.AchievementCommands;
import com.miningdim.achievement.meta.AchievementCatalog;
import com.miningdim.achievement.meta.AchievementMeta;
import com.miningdim.achievement.tier.AchievementTier;
import com.miningdim.achievement.trigger.DailyCounterRepository;
import com.miningdim.core.MiningConstants;
import com.miningdim.store.MiningDb;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.testutil.TempStoreDb;
import com.miningdim.title.ITitleService;
import com.miningdim.title.TitleDefinition;
import com.miningdim.title.TitleDefinitions;
import com.miningdim.title.TitleService;
import com.miningdim.title.TitleServices;
import com.miningdim.title.TitleSource;
import com.miningdim.title.store.SqliteTitleRepository;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.advancements.Advancement;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 待领取奖励、领取与 /machievement 的 GameTest (Achievement_System_DesignSpec 第七章、第十一章, batch
 * {@code achievement_rewards})。
 *
 * <p>每个用例把奖励仓库与称号门面换成 {@link TempStoreDb} 临时统一库上的实现 (称号定义抄自服务端当前加载的那一份, 需要时
 * 去掉某个称号来制造发放失败), 用 mock 玩家的 {@code PlayerAdvancements} 授予服务端真实加载的进度, 让原版授予流程发出
 * 真实的 AdvancementEarnEvent; 命令一律经服务端真实的命令分发器执行。收尾时先让 mock 玩家下线, 再恢复原来的仓库、
 * 称号门面与查询快照。强断言 (删被测核心逻辑必挂):
 * <ol>
 *   <li>获得会产生奖励的成就恰好写一条待领取奖励, 档位、点数、称号取获得那一刻的快照 (之后快照变化不影响领取),
 *       并给玩家本人发一条带可点击 [领取] 的提示, 按钮执行的就是 {@code /machievement claim <进度id>};</li>
 *   <li>撤销后再授予不重复生成、不再提示; 撤销不收回已领取的成就点, 再授予也不能再领一次;</li>
 *   <li>页签根、别的命名空间的进度、缺元数据的进度、0 点无称号的进度什么都不产生;</li>
 *   <li>单条领取与全部领取: 标记已领取、余额与累计获得、claim 流水、称号 (来源 achievement) 一并提交, 提交后只为新得到的
 *       称号发提示, 原本就有的称号不算失败;</li>
 *   <li>称号发不出去、写流水失败时整次领取回滚, 排在前面已处理的奖励也一并撤销 (含事务里已经 GRANTED 的称号: 库里与
 *       在线持有集合都没有它, 也不发"获得称号"提示), 没有残留; 全部领取失败的命令反馈写明其余奖励仍可逐条领取;</li>
 *   <li>领取与管理员调整在调用方已开着的事务里一律拒绝 (否则回滚会被外层吞掉、部分领取随外层提交), 事务外照常可用;</li>
 *   <li>重复领取报已领取, 不存在的奖励报未找到, 没有待领取时全部领取报没有;</li>
 *   <li>真实命令分发器: 权限门按解析结果逐条核对, 补全只给自己的待领取奖励, 自查与代查、领取、加点、扣点 (扣到 0 为止、
 *       流水记 admin 与执行者) 与一致性校验的反馈与落库都对, 用到的文案键两种语言都有。</li>
 * </ol>
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class AchievementRewardGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "achievement_rewards";
    private static final String TACZ = "tacz";

    private static final ResourceLocation FIRST_ENTRY = AchievementIds.id("mining/first_entry");
    private static final ResourceLocation FIRST_EXTRACTION = AchievementIds.id("mining/first_extraction");
    private static final ResourceLocation BLOCKS_100K = AchievementIds.id("mining/blocks_100k");
    private static final ResourceLocation HARD_ACTIVE_100H = AchievementIds.id("mining/hard_active_100h");
    private static final ResourceLocation ORE_CODEX = AchievementIds.id("mining/ore_codex");
    private static final ResourceLocation SOLO_STAR_9 = AchievementIds.id("combat/solo_star_9");
    /** 回滚类用例的待领取顺序, 见 {@link #grantRollbackRewards}。 */
    private static final List<ResourceLocation> ROLLBACK_PENDING = List.of(BLOCKS_100K, FIRST_ENTRY, HARD_ACTIVE_100H);

    private static final String EARNED_KEY = "achievement.miningdim.reward.earned";
    private static final String TITLE_OBTAINED_KEY = "title.miningdim.message.obtained";

    private AchievementRewardGameTests() {
    }

    // ---- 1. 获得成就 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void earningRecordsOneSnapshotRewardWithAClickableNotice(GameTestHelper helper) {
        RewardEnv env = new RewardEnv(helper, Set.of());
        try {
            ServerPlayer player = env.player("ach-rw-earn");
            UUID uuid = player.getUUID();
            grant(env.server, player, BLOCKS_100K);
            List<AchievementReward> pending = env.rewards.pending(uuid);
            helper.assertTrue(pending.size() == 1, "获得地脉行者应恰好写一条待领取奖励, 实为 " + pending);
            AchievementReward reward = pending.get(0);
            helper.assertTrue(reward.advancementId().equals(BLOCKS_100K) && reward.tier() == AchievementTier.DIAMOND
                            && reward.points() == 200 && BLOCKS_100K.equals(reward.titleId()) && !reward.isClaimed()
                            && reward.earnedAt() > 0L,
                    "待领取奖励应记下钻石档、200 点与称号 mining/blocks_100k, 实为 " + reward);
            List<Component> notices = messagesWithKey(drainSystemMessages(player), EARNED_KEY);
            helper.assertTrue(notices.size() == 1, "应恰好给玩家本人发一条待领取提示, 实为 " + notices.size());
            helper.assertTrue(("/machievement claim " + BLOCKS_100K).equals(runCommandOf(notices.get(0))),
                    "提示末尾的 [领取] 应执行 /machievement claim " + BLOCKS_100K + ", 实为 "
                            + runCommandOf(notices.get(0)));
            assertTranslated(helper, keysOf(notices));

            // 快照: 获得那一刻初入矿区是 0 点、附带称号 ore_codex; 之后快照恢复成 10 点无称号, 领取仍按当时的记录发。
            env.overrideMetas(Map.of(FIRST_ENTRY, new AchievementMeta(FIRST_ENTRY, AchievementTier.BRONZE, false, 0,
                    ORE_CODEX)), Set.of());
            grant(env.server, player, FIRST_ENTRY);
            env.restoreCatalog();
            helper.assertTrue(AchievementServices.catalog().points(FIRST_ENTRY) == 10
                    && AchievementServices.catalog().title(FIRST_ENTRY).isEmpty(), "查询快照应已恢复为 10 点、无称号");
            AchievementReward snapshot = env.rewards.reward(uuid, FIRST_ENTRY).orElseThrow();
            helper.assertTrue(snapshot.points() == 0 && ORE_CODEX.equals(snapshot.titleId()),
                    "奖励记录应是获得那一刻的快照 (0 点、称号 ore_codex), 实为 " + snapshot);
            List<Component> titleOnly = messagesWithKey(drainSystemMessages(player), EARNED_KEY);
            helper.assertTrue(titleOnly.size() == 1 && keysOf(titleOnly).contains("achievement.miningdim.reward.title"),
                    "只有称号的奖励应用只写称号的文案, 实为 " + keysOf(titleOnly));

            ClaimResult claimed = AchievementRewardService.claim(player, FIRST_ENTRY);
            helper.assertTrue(claimed.success() && claimed.points() == 0L
                            && PointBalance.ZERO.equals(claimed.balance()),
                    "按快照领取: 0 点不入账, 实为 " + claimed);
            helper.assertTrue(ledger(env.connection, uuid).isEmpty(), "0 点的奖励不写成就点流水");
            helper.assertTrue(env.titles.owned(uuid).contains(ORE_CODEX), "按快照发放称号 ore_codex");
        } finally {
            env.close();
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void revokeAndRegrantNeverDuplicatesNorTakesBackClaimedRewards(GameTestHelper helper) {
        RewardEnv env = new RewardEnv(helper, Set.of());
        try {
            ServerPlayer player = env.player("ach-rw-regrant");
            UUID uuid = player.getUUID();
            grant(env.server, player, FIRST_ENTRY);
            long earnedAt = env.rewards.reward(uuid, FIRST_ENTRY).orElseThrow().earnedAt();
            helper.assertTrue(messagesWithKey(drainSystemMessages(player), EARNED_KEY).size() == 1, "首次获得应提示一次");

            revoke(env.server, player, FIRST_ENTRY);
            helper.assertTrue(!isDone(env.server, player, FIRST_ENTRY), "撤销后进度应未完成");
            grant(env.server, player, FIRST_ENTRY);
            helper.assertTrue(isDone(env.server, player, FIRST_ENTRY), "再次授予后进度应完成 (确实又发了一次获得事件)");
            helper.assertTrue(countRewardRows(env.connection, uuid) == 1
                            && env.rewards.reward(uuid, FIRST_ENTRY).orElseThrow().earnedAt() == earnedAt,
                    "撤销后再授予不得重复生成奖励, 也不得覆盖最早的记录");
            helper.assertTrue(messagesWithKey(drainSystemMessages(player), EARNED_KEY).isEmpty(), "再授予不应再次提示");

            helper.assertTrue(AchievementRewardService.claim(player, FIRST_ENTRY).success(), "待领取的奖励应能领取");
            revoke(env.server, player, FIRST_ENTRY);
            helper.assertTrue(env.rewards.points(uuid).equals(new PointBalance(10L, 10L))
                            && env.rewards.reward(uuid, FIRST_ENTRY).orElseThrow().isClaimed()
                            && ledger(env.connection, uuid).size() == 1,
                    "撤销进度不收回已领取的成就点, 奖励记录与流水原样保留");
            grant(env.server, player, FIRST_ENTRY);
            helper.assertTrue(env.rewards.pending(uuid).isEmpty() && countRewardRows(env.connection, uuid) == 1,
                    "领取后撤销再授予也不得产生新的待领取奖励");
            helper.assertTrue(messagesWithKey(drainSystemMessages(player), EARNED_KEY).isEmpty(), "也不再提示");
            helper.assertTrue(AchievementRewardService.claim(player, FIRST_ENTRY).status()
                            == ClaimResult.Status.ALREADY_CLAIMED && env.rewards.points(uuid).balance() == 10L,
                    "再授予之后不能再领一次");
        } finally {
            env.close();
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rootsForeignAndMetaLessAdvancementsProduceNothing(GameTestHelper helper) {
        RewardEnv env = new RewardEnv(helper, Set.of());
        try {
            ServerPlayer player = env.player("ach-rw-none");
            UUID uuid = player.getUUID();
            for (String tab : AchievementIds.TABS) {
                grant(env.server, player, AchievementIds.root(tab));
                helper.assertTrue(isDone(env.server, player, AchievementIds.root(tab)), "页签根应已授予: " + tab);
            }
            grant(env.server, player, new ResourceLocation("minecraft", "story/root"));
            // 初入矿区缺元数据, 平安归来是 0 点、无称号的档位成就: 都不产生奖励。
            env.overrideMetas(Map.of(FIRST_EXTRACTION, new AchievementMeta(FIRST_EXTRACTION, AchievementTier.BRONZE,
                    false, 0, null)), Set.of(FIRST_ENTRY));
            grant(env.server, player, FIRST_ENTRY);
            grant(env.server, player, FIRST_EXTRACTION);
            helper.assertTrue(isDone(env.server, player, FIRST_ENTRY) && isDone(env.server, player, FIRST_EXTRACTION),
                    "两条进度本身照常授予");
            helper.assertTrue(countRewardRows(env.connection, uuid) == 0,
                    "页签根、原版进度、缺元数据与 0 点无称号的进度都不应产生奖励记录");
            helper.assertTrue(messagesWithKey(drainSystemMessages(player), EARNED_KEY).isEmpty(), "也不应提示");
            helper.assertTrue(AchievementRewardService.claimAll(player).status() == ClaimResult.Status.NOTHING_PENDING,
                    "没有可领取的奖励");
        } finally {
            env.close();
        }
        helper.succeed();
    }

    // ---- 2. 领取 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void claimSingleAndAllCommitPointsLedgerAndTitlesTogether(GameTestHelper helper) {
        RewardEnv env = new RewardEnv(helper, Set.of());
        try {
            ServerPlayer player = env.player("ach-rw-claim");
            UUID uuid = player.getUUID();
            // 一人成军的称号事先已经拥有: 领取时是 ALREADY_OWNED, 不算失败, 也不重复提示。
            env.titles.grant(uuid, SOLO_STAR_9, TitleSource.ADMIN, "test");
            grant(env.server, player, FIRST_ENTRY);
            grant(env.server, player, BLOCKS_100K);
            grant(env.server, player, SOLO_STAR_9);
            drainSystemMessages(player);

            ClaimResult single = AchievementRewardService.claim(player, FIRST_ENTRY);
            helper.assertTrue(single.success() && single.claimed().size() == 1 && single.points() == 10L
                    && new PointBalance(10L, 10L).equals(single.balance()), "单条领取应入账 10 点, 实为 " + single);
            helper.assertTrue(ledger(env.connection, uuid).equals(List.of(
                            new LedgerRow(10L, "claim", FIRST_ENTRY.toString()))),
                    "单条领取写一条 claim 流水, ref 为进度 id, 实为 " + ledger(env.connection, uuid));
            helper.assertTrue(env.rewards.reward(uuid, FIRST_ENTRY).orElseThrow().isClaimed()
                            && Set.copyOf(pendingIds(env.rewards, uuid)).equals(Set.of(BLOCKS_100K, SOLO_STAR_9)),
                    "领取的那条标记已领取, 其余仍待领取");

            ClaimResult all = AchievementRewardService.claimAll(player);
            helper.assertTrue(all.success() && all.claimed().size() == 2 && all.points() == 1_000L
                            && new PointBalance(1_010L, 1_010L).equals(all.balance()),
                    "全部领取应一次入账 200 + 800 点, 实为 " + all);
            helper.assertTrue(env.rewards.points(uuid).equals(new PointBalance(1_010L, 1_010L)),
                    "余额与累计获得都应为 1010");
            List<LedgerRow> rows = ledger(env.connection, uuid);
            helper.assertTrue(rows.size() == 3 && rows.containsAll(List.of(
                            new LedgerRow(200L, "claim", BLOCKS_100K.toString()),
                            new LedgerRow(800L, "claim", SOLO_STAR_9.toString()))),
                    "每条奖励各写一条 claim 流水, 实为 " + rows);
            helper.assertTrue(env.rewards.pending(uuid).isEmpty(), "全部领取后不再有待领取奖励");
            helper.assertTrue(env.titles.owned(uuid).containsAll(Set.of(BLOCKS_100K, SOLO_STAR_9)), "两个称号都已拥有");
            helper.assertTrue(List.of("achievement", BLOCKS_100K.toString()).equals(
                            titleSource(env.connection, uuid, BLOCKS_100K)),
                    "领取发放的称号来源记 achievement, source_ref 为进度 id");
            List<Component> obtained = messagesWithKey(drainSystemMessages(player), TITLE_OBTAINED_KEY);
            helper.assertTrue(obtained.size() == 1, "提交后只为新得到的称号 (地脉行者) 提示一次, 实为 " + obtained.size());

            helper.assertTrue(AchievementRewardService.claimAll(player).status() == ClaimResult.Status.NOTHING_PENDING,
                    "领完后再全部领取应报没有待领取");
        } finally {
            env.close();
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void anyFailureRollsBackTheWholeClaim(GameTestHelper helper) {
        // 称号定义里去掉深渊守望者: 领取它时 grantInTransaction 返回 UNKNOWN_TITLE。
        RewardEnv env = new RewardEnv(helper, Set.of(HARD_ACTIVE_100H));
        try {
            ServerPlayer player = env.player("ach-rw-rollback");
            UUID uuid = player.getUUID();
            grantRollbackRewards(env, player);
            helper.assertTrue(!env.titles.owned(uuid).contains(BLOCKS_100K), "前提: 还没有地脉行者称号");
            // 待领取按获得时间、进度 id 排序: 地脉行者 (标记、入账、写流水, 称号在事务里真的发出去 = GRANTED) 与初入矿区
            // (标记、入账、写流水) 先处理, 深渊守望者的称号随后失败。
            helper.assertTrue(pendingIds(env.rewards, uuid).equals(ROLLBACK_PENDING),
                    "地脉行者、初入矿区应排在深渊守望者前面, 实为 " + pendingIds(env.rewards, uuid));

            ClaimResult failed = AchievementRewardService.claimAll(player);
            helper.assertTrue(failed.status() == ClaimResult.Status.TITLE_UNAVAILABLE
                            && HARD_ACTIVE_100H.equals(failed.advancementId()) && HARD_ACTIVE_100H.equals(failed.titleId())
                            && failed.claimed().isEmpty() && failed.balance() == null,
                    "称号发不出去应报 TITLE_UNAVAILABLE 并指出是哪一条, 实为 " + failed);
            assertNothingClaimed(helper, env, uuid, "称号失败");
            helper.assertTrue(countRows(env.connection, "SELECT COUNT(*) FROM title_owned WHERE player_uuid=?", uuid)
                    == 0, "称号失败: 事务里已经发出的地脉行者称号也必须随之回滚, 不得残留称号");
            helper.assertTrue(!env.titles.owned(uuid).contains(BLOCKS_100K),
                    "称号失败: 在线持有集合里也不得出现回滚掉的地脉行者");
            helper.assertTrue(messagesWithKey(drainSystemMessages(player), TITLE_OBTAINED_KEY).isEmpty(),
                    "回滚后不应发任何称号提示: 提示只在提交后发, 事务里已 GRANTED 的地脉行者也不例外");
            helper.assertTrue(AchievementRewardService.claim(player, HARD_ACTIVE_100H).status()
                    == ClaimResult.Status.TITLE_UNAVAILABLE, "单条领取同样失败");

            // 经真实命令: 全部领取失败的反馈要写明其余奖励仍可逐条领取; 单条领取失败用单条的文案。两者都不留残留。
            CommandDispatcher<CommandSourceStack> dispatcher = env.server.getCommands().getDispatcher();
            CapturingSource playerOut = new CapturingSource();
            CommandSourceStack asPlayer = player.createCommandSourceStack().withSource(playerOut).withPermission(0);
            List<String> feedback = new ArrayList<>();
            feedback.addAll(expectCommand(helper, dispatcher, asPlayer, playerOut, "machievement claim all", 0,
                    "achievement.miningdim.claim.title_unavailable_all"));
            feedback.addAll(expectCommand(helper, dispatcher, asPlayer, playerOut,
                    "machievement claim " + HARD_ACTIVE_100H, 0, "achievement.miningdim.claim.title_unavailable"));
            assertTranslated(helper, feedback);
            assertNothingClaimed(helper, env, uuid, "命令领取失败");
            helper.assertTrue(messagesWithKey(drainSystemMessages(player), TITLE_OBTAINED_KEY).isEmpty(),
                    "命令领取失败: 不应发称号提示");

            // 称号门面未注入时, 带称号的奖励同样整次回滚。
            ITitleService injected = TitleServices.titleService();
            TitleServices.reset();
            try {
                helper.assertTrue(AchievementRewardService.claimAll(player).status()
                        == ClaimResult.Status.TITLE_UNAVAILABLE, "称号门面未注入应报 TITLE_UNAVAILABLE");
            } finally {
                TitleServices.registerTitleService(injected);
            }
            assertNothingClaimed(helper, env, uuid, "门面未注入");

            // 写流水失败 (流水表被删): 已经做完的标记已领取与入账一并回滚。
            execute(env.connection, "DROP TABLE achievement_point_ledger");
            ClaimResult storeFailed = AchievementRewardService.claim(player, FIRST_ENTRY);
            helper.assertTrue(storeFailed.status() == ClaimResult.Status.STORE_FAILED,
                    "写库失败应报 STORE_FAILED, 实为 " + storeFailed);
            helper.assertTrue(env.rewards.points(uuid).equals(PointBalance.ZERO)
                            && !env.rewards.reward(uuid, FIRST_ENTRY).orElseThrow().isClaimed(),
                    "写流水失败: 标记已领取与入账都应回滚");
        } finally {
            env.close();
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void claimsAndAdminChangesRefuseACallersOpenTransaction(GameTestHelper helper) {
        RewardEnv env = new RewardEnv(helper, Set.of(HARD_ACTIVE_100H));
        try {
            ServerPlayer player = env.player("ach-rw-nested");
            UUID uuid = player.getUUID();
            grantRollbackRewards(env, player);
            // 并入调用方的事务时, 领取自己的回滚会被外层吞掉: 全部领取报"已全部撤销", 外层却会把排在前面的地脉行者
            // (连同事务里发出的称号) 与初入矿区一起提交。所以四个入口在外层事务开着时都必须当场拒绝, 什么都不做。
            Map<String, Runnable> entries = new LinkedHashMap<>();
            entries.put("claimAll", () -> AchievementRewardService.claimAll(player));
            entries.put("claim", () -> AchievementRewardService.claim(player, FIRST_ENTRY));
            entries.put("addPoints", () -> AchievementRewardService.addPoints(uuid, 5, "test"));
            entries.put("removePoints", () -> AchievementRewardService.removePoints(uuid, 5, "test"));
            for (Map.Entry<String, Runnable> entry : entries.entrySet()) {
                boolean refused = false;
                try {
                    env.rewards.inTransaction(tx -> {
                        entry.getValue().run();
                        return null;
                    });
                } catch (IllegalStateException expected) {
                    refused = true;
                }
                helper.assertTrue(refused, entry.getKey() + " 在调用方已开着的事务里必须拒绝执行");
                helper.assertTrue(autoCommit(env.connection), entry.getKey() + ": 外层事务应已结束, 连接回到自动提交");
            }
            assertNothingClaimed(helper, env, uuid, "嵌套调用");
            helper.assertTrue(countRows(env.connection, "SELECT COUNT(*) FROM title_owned WHERE player_uuid=?", uuid)
                    == 0, "嵌套调用: 不得残留称号");
            helper.assertTrue(messagesWithKey(drainSystemMessages(player), TITLE_OBTAINED_KEY).isEmpty(),
                    "嵌套调用: 不得发称号提示");

            // 不在事务里时照常可用: 前置检查不误伤正常调用。
            ClaimResult single = AchievementRewardService.claim(player, FIRST_ENTRY);
            helper.assertTrue(single.success() && new PointBalance(10L, 10L).equals(single.balance()),
                    "事务外单条领取应成功, 实为 " + single);
            helper.assertTrue(AchievementRewardService.addPoints(uuid, 5, "test").equals(new PointBalance(15L, 15L)),
                    "事务外加点应成功");
            helper.assertTrue(AchievementRewardService.removePoints(uuid, 5, "test") == 5L
                    && env.rewards.points(uuid).equals(new PointBalance(10L, 15L)), "事务外扣点应成功");
        } finally {
            env.close();
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void doubleClaimAndUnknownRewardsAreRejected(GameTestHelper helper) {
        RewardEnv env = new RewardEnv(helper, Set.of());
        try {
            ServerPlayer player = env.player("ach-rw-double");
            UUID uuid = player.getUUID();
            grant(env.server, player, FIRST_ENTRY);
            helper.assertTrue(AchievementRewardService.claim(player, FIRST_ENTRY).success(), "第一次领取应成功");
            ClaimResult again = AchievementRewardService.claim(player, FIRST_ENTRY);
            helper.assertTrue(again.status() == ClaimResult.Status.ALREADY_CLAIMED
                            && FIRST_ENTRY.equals(again.advancementId()),
                    "重复领取应报 ALREADY_CLAIMED, 实为 " + again);
            helper.assertTrue(env.rewards.points(uuid).equals(new PointBalance(10L, 10L))
                    && ledger(env.connection, uuid).size() == 1, "重复领取不得再入账或写流水");
            for (ResourceLocation unknown : List.of(AchievementIds.id("mining/nope"), AchievementIds.root("mining"),
                    FIRST_EXTRACTION)) {
                ClaimResult result = AchievementRewardService.claim(player, unknown);
                helper.assertTrue(result.status() == ClaimResult.Status.NOT_FOUND && unknown.equals(result.advancementId()),
                        "没有奖励记录的 " + unknown + " 应报 NOT_FOUND, 实为 " + result);
            }
            helper.assertTrue(AchievementRewardService.claimAll(player).status() == ClaimResult.Status.NOTHING_PENDING,
                    "没有待领取时全部领取应报 NOTHING_PENDING");
        } finally {
            env.close();
        }
        helper.succeed();
    }

    // ---- 3. 命令 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void commandsRunThroughTheRealDispatcherWithPermissions(GameTestHelper helper) {
        RewardEnv env = new RewardEnv(helper, Set.of());
        List<String> seenKeys = new ArrayList<>();
        try {
            ServerPlayer player = env.player("ach-rw-cmd");
            UUID uuid = player.getUUID();
            // GameTest 服务端没有用户缓存, 原版 GameProfileArgument 按裸名字解析会 NPE; 用选择器指向在线的 mock 玩家,
            // 走的仍是同一个参数类型与同一条命令执行路径。
            String target = "@a[name=" + player.getGameProfile().getName() + "]";
            CommandDispatcher<CommandSourceStack> dispatcher = env.server.getCommands().getDispatcher();
            CapturingSource playerOut = new CapturingSource();
            CapturingSource adminOut = new CapturingSource();
            CommandSourceStack asPlayer = player.createCommandSourceStack().withSource(playerOut).withPermission(0);
            CommandSourceStack asAdmin = env.server.createCommandSourceStack().withSource(adminOut);

            helper.assertTrue(dispatcher.getRoot().getChild(AchievementCommands.ROOT).canUse(asPlayer),
                    "命令根本身不设权限");
            for (String adminOnly : List.of("machievement pending " + target, "machievement points " + target,
                    "machievement points " + target + " add 5", "machievement points " + target + " remove 5",
                    "machievement check")) {
                helper.assertTrue(parsesToCommand(dispatcher, asAdmin, adminOnly), "管理员应能解析: " + adminOnly);
                helper.assertTrue(!parsesToCommand(dispatcher, asPlayer, adminOnly), "普通玩家不得执行: " + adminOnly);
            }
            for (String playerOnly : List.of("machievement claim all", "machievement claim " + FIRST_ENTRY)) {
                helper.assertTrue(parsesToCommand(dispatcher, asPlayer, playerOnly), "普通玩家应能解析: " + playerOnly);
                helper.assertTrue(!parsesToCommand(dispatcher, asAdmin, playerOnly), "控制台不是玩家, 不能领取: "
                        + playerOnly);
            }
            for (String self : List.of("machievement pending", "machievement points")) {
                helper.assertTrue(parsesToCommand(dispatcher, asPlayer, self), "任何玩家都能查看自己的: " + self);
            }

            grant(env.server, player, FIRST_ENTRY);
            grant(env.server, player, BLOCKS_100K);
            List<Component> notices = messagesWithKey(drainSystemMessages(player), EARNED_KEY);
            String clicked = notices.stream().map(AchievementRewardGameTests::runCommandOf)
                    .filter(command -> command != null && command.endsWith(FIRST_ENTRY.toString()))
                    .findFirst().orElse(null);
            helper.assertTrue(clicked != null && clicked.startsWith("/"), "初入矿区的提示应带 [领取] 命令");
            seenKeys.addAll(keysOf(notices));

            List<String> suggested = dispatcher.getCompletionSuggestions(dispatcher.parse("machievement claim ",
                    asPlayer)).join().getList().stream().map(Suggestion::getText).toList();
            helper.assertTrue(suggested.containsAll(List.of("all", FIRST_ENTRY.toString(), BLOCKS_100K.toString()))
                            && suggested.size() == 3,
                    "claim 的补全应只有 all 与自己的两条待领取奖励, 实为 " + suggested);

            seenKeys.addAll(expectCommand(helper, dispatcher, asPlayer, playerOut, "machievement pending", 1,
                    "achievement.miningdim.command.pending.header_self"));
            seenKeys.addAll(expectCommand(helper, dispatcher, asAdmin, adminOut, "machievement pending " + target, 1,
                    "achievement.miningdim.command.pending.header"));

            seenKeys.addAll(expectCommand(helper, dispatcher, asPlayer, playerOut, clicked.substring(1), 1,
                    "achievement.miningdim.claim.done"));
            helper.assertTrue(env.rewards.points(uuid).equals(new PointBalance(10L, 10L)), "点击 [领取] 应入账 10 点");
            seenKeys.addAll(expectCommand(helper, dispatcher, asPlayer, playerOut, clicked.substring(1), 0,
                    "achievement.miningdim.claim.already_claimed"));
            seenKeys.addAll(expectCommand(helper, dispatcher, asPlayer, playerOut, "machievement claim miningdim:nope",
                    0, "achievement.miningdim.claim.not_found"));
            seenKeys.addAll(expectCommand(helper, dispatcher, asPlayer, playerOut, "machievement claim all", 1,
                    "achievement.miningdim.claim.done_all"));
            helper.assertTrue(env.rewards.points(uuid).equals(new PointBalance(210L, 210L))
                    && env.titles.owned(uuid).contains(BLOCKS_100K), "claim all 应入账 200 点并发放称号");
            seenKeys.addAll(keysOf(messagesWithKey(drainSystemMessages(player), TITLE_OBTAINED_KEY)));
            seenKeys.addAll(expectCommand(helper, dispatcher, asPlayer, playerOut, "machievement claim all", 0,
                    "achievement.miningdim.reward.none_pending"));
            seenKeys.addAll(expectCommand(helper, dispatcher, asPlayer, playerOut, "machievement pending", 1,
                    "achievement.miningdim.reward.none_pending"));
            seenKeys.addAll(expectCommand(helper, dispatcher, asPlayer, playerOut, "machievement points", 1,
                    "achievement.miningdim.command.points.show_self"));
            seenKeys.addAll(expectCommand(helper, dispatcher, asAdmin, adminOut, "machievement points " + target, 1,
                    "achievement.miningdim.command.points.show"));

            int ledgerBefore = ledger(env.connection, uuid).size();
            seenKeys.addAll(expectCommand(helper, dispatcher, asAdmin, adminOut,
                    "machievement points " + target + " add 50", 1, "achievement.miningdim.command.points.added"));
            helper.assertTrue(env.rewards.points(uuid).equals(new PointBalance(260L, 260L)), "加点同时增加余额与累计获得");
            seenKeys.addAll(expectCommand(helper, dispatcher, asAdmin, adminOut,
                    "machievement points " + target + " remove 60", 1, "achievement.miningdim.command.points.removed"));
            helper.assertTrue(env.rewards.points(uuid).equals(new PointBalance(200L, 260L)), "扣点只减余额");
            seenKeys.addAll(expectCommand(helper, dispatcher, asAdmin, adminOut,
                    "machievement points " + target + " remove 1000", 1,
                    "achievement.miningdim.command.points.removed_clamped"));
            helper.assertTrue(env.rewards.points(uuid).equals(new PointBalance(0L, 260L)), "余额不足时只扣到 0, 不出现负数");
            seenKeys.addAll(expectCommand(helper, dispatcher, asAdmin, adminOut,
                    "machievement points " + target + " remove 5", 0,
                    "achievement.miningdim.command.points.nothing_to_remove"));
            List<LedgerRow> adminRows = ledger(env.connection, uuid).subList(ledgerBefore,
                    ledger(env.connection, uuid).size());
            helper.assertTrue(adminRows.equals(List.of(new LedgerRow(50L, "admin", "Server"),
                            new LedgerRow(-60L, "admin", "Server"), new LedgerRow(-200L, "admin", "Server"))),
                    "管理员调整按实际变动写 admin 流水、ref 记执行者, 余额为 0 时不写, 实为 " + adminRows);

            seenKeys.addAll(expectCommand(helper, dispatcher, asAdmin, adminOut, "machievement check", 1,
                    "achievement.miningdim.command.check.clean"));
            if (!ModList.get().isLoaded(TACZ)) {
                helper.assertTrue(seenKeys.contains("achievement.miningdim.command.check.meta_without_advancement"),
                        "没装 TaCZ 时 check 应列出三条没有进度的元数据");
            }
            assertTranslated(helper, seenKeys);
        } finally {
            env.close();
        }
        helper.succeed();
    }

    // ---- 工具 ----

    /**
     * 一个用例的临时环境: 临时统一库上的奖励仓库与称号门面注入全局定位器, 称号定义抄自服务端当前加载的那一份
     * (去掉 withoutTitles)。{@link #close()} 先让 mock 玩家下线 (称号登出钩子还要用临时门面), 再恢复原来的仓库、
     * 称号门面与查询快照, 最后关库删目录。
     */
    private static final class RewardEnv {

        final MinecraftServer server;
        final Connection connection;
        final SqliteAchievementRewardRepository rewards;
        final TitleService titles;
        private final GameTestHelper helper;
        private final Path dir;
        private final AchievementRewardRepository previousRewards;
        private final DailyCounterRepository previousCounters;
        private final ITitleService previousTitles;
        private final AchievementCatalog previousCatalog;
        private final List<ServerPlayer> players = new ArrayList<>();

        RewardEnv(GameTestHelper helper, Set<ResourceLocation> withoutTitles) {
            this.helper = helper;
            this.server = helper.getLevel().getServer();
            this.previousRewards = AchievementServices.rewards();
            this.previousCounters = AchievementServices.dailyCounters();
            this.previousTitles = TitleServices.titleService();
            this.previousCatalog = AchievementServices.catalog();
            Map<ResourceLocation, TitleDefinition> loaded = new LinkedHashMap<>();
            for (TitleDefinition definition : previousTitles.definitions()) {
                if (!withoutTitles.contains(definition.id())) {
                    loaded.put(definition.id(), definition);
                }
            }
            TitleDefinitions definitions = new TitleDefinitions();
            definitions.install(loaded);
            this.dir = TempStoreDb.createTempDir();
            this.connection = TempStoreDb.openUnified(dir.resolve("achievement-rewards.db"));
            this.rewards = new SqliteAchievementRewardRepository(connection);
            this.titles = new TitleService(new SqliteTitleRepository(connection), definitions, server);
            AchievementServices.bindRepositories(rewards, previousCounters);
            TitleServices.registerTitleService(titles);
        }

        /** 登录一个 mock 玩家, 并丢掉登录时积压的封包。 */
        ServerPlayer player(String name) {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper,
                    new GameProfile(UUID.randomUUID(), name));
            players.add(player);
            drainSystemMessages(player);
            return player;
        }

        /** 用改过的元数据重建查询快照并装入 (replaced 覆盖, removed 删除)。 */
        void overrideMetas(Map<ResourceLocation, AchievementMeta> replaced, Set<ResourceLocation> removed) {
            Map<ResourceLocation, AchievementMeta> metas = new HashMap<>(previousCatalog.metas());
            metas.keySet().removeAll(removed);
            metas.putAll(replaced);
            AchievementServices.installCatalog(AchievementCatalog.build(server.getAdvancements().getAllAdvancements(),
                    metas));
        }

        void restoreCatalog() {
            AchievementServices.installCatalog(previousCatalog);
        }

        void close() {
            try {
                for (ServerPlayer player : players) {
                    server.getPlayerList().remove(player);
                }
            } finally {
                restoreCatalog();
                AchievementServices.bindRepositories(previousRewards, previousCounters);
                TitleServices.registerTitleService(previousTitles);
                MiningDb.close(connection);
                TempStoreDb.deleteQuietly(dir);
            }
        }
    }

    /** 一行成就点流水。 */
    private record LedgerRow(long delta, String reason, @Nullable String ref) {
    }

    /**
     * 回滚类用例的三条待领取奖励, 依次授予: 地脉行者 (200 点、称号可发)、初入矿区 (10 点、无称号)、深渊守望者 (称号定义
     * 已被去掉)。获得时间单调不减, 同一毫秒时按进度 id 排序也是这个顺序, 所以领取时深渊守望者一定最后处理。
     */
    private static void grantRollbackRewards(RewardEnv env, ServerPlayer player) {
        grant(env.server, player, BLOCKS_100K);
        grant(env.server, player, FIRST_ENTRY);
        grant(env.server, player, HARD_ACTIVE_100H);
        drainSystemMessages(player);
    }

    private static void assertNothingClaimed(GameTestHelper helper, RewardEnv env, UUID uuid, String when) {
        helper.assertTrue(env.rewards.points(uuid).equals(PointBalance.ZERO), when + ": 不得残留成就点");
        helper.assertTrue(pendingIds(env.rewards, uuid).equals(ROLLBACK_PENDING),
                when + ": 三条奖励都应仍待领取 (排在前面已处理的地脉行者与初入矿区也要回滚), 实为 "
                        + pendingIds(env.rewards, uuid));
        helper.assertTrue(ledger(env.connection, uuid).isEmpty(), when + ": 不得残留流水");
    }

    private static boolean autoCommit(Connection connection) {
        try {
            return connection.getAutoCommit();
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Advancement advancement(MinecraftServer server, ResourceLocation id) {
        return Objects.requireNonNull(server.getAdvancements().getAdvancement(id), () -> "进度未加载: " + id);
    }

    /** 经原版 PlayerAdvancements 授予全部条件, 由原版在完成时发出 AdvancementEarnEvent。 */
    private static void grant(MinecraftServer server, ServerPlayer player, ResourceLocation id) {
        Advancement advancement = advancement(server, id);
        for (String criterion : advancement.getCriteria().keySet()) {
            player.getAdvancements().award(advancement, criterion);
        }
    }

    private static void revoke(MinecraftServer server, ServerPlayer player, ResourceLocation id) {
        Advancement advancement = advancement(server, id);
        for (String criterion : advancement.getCriteria().keySet()) {
            player.getAdvancements().revoke(advancement, criterion);
        }
    }

    private static boolean isDone(MinecraftServer server, ServerPlayer player, ResourceLocation id) {
        return player.getAdvancements().getOrStartProgress(advancement(server, id)).isDone();
    }

    private static List<ResourceLocation> pendingIds(AchievementRewardRepository rewards, UUID player) {
        return rewards.pending(player).stream().map(AchievementReward::advancementId).toList();
    }

    /** 读空 mock 连接的出站队列, 按顺序返回其中的系统聊天消息 (其余封包一并丢弃)。 */
    private static List<Component> drainSystemMessages(ServerPlayer player) {
        EmbeddedChannel channel = (EmbeddedChannel) player.connection.connection.channel();
        List<Component> messages = new ArrayList<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            try {
                if (outbound instanceof ClientboundSystemChatPacket packet) {
                    messages.add(packet.content());
                }
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        }
        return messages;
    }

    private static List<Component> messagesWithKey(List<Component> messages, String key) {
        return messages.stream().filter(message -> message.getContents() instanceof TranslatableContents translatable
                && translatable.getKey().equals(key)).toList();
    }

    /** 消息里 (含翻译参数与子片段) 第一个 RUN_COMMAND 点击事件的命令串; 没有为 null。 */
    @Nullable
    private static String runCommandOf(Component component) {
        ClickEvent click = component.getStyle().getClickEvent();
        if (click != null && click.getAction() == ClickEvent.Action.RUN_COMMAND) {
            return click.getValue();
        }
        if (component.getContents() instanceof TranslatableContents translatable) {
            for (Object argument : translatable.getArgs()) {
                if (argument instanceof Component nested && runCommandOf(nested) != null) {
                    return runCommandOf(nested);
                }
            }
        }
        for (Component sibling : component.getSiblings()) {
            String command = runCommandOf(sibling);
            if (command != null) {
                return command;
            }
        }
        return null;
    }

    private static List<String> keysOf(List<Component> messages) {
        List<String> keys = new ArrayList<>();
        messages.forEach(message -> collectKeys(message, keys));
        return keys;
    }

    private static void collectKeys(Component component, List<String> keys) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            keys.add(translatable.getKey());
            for (Object argument : translatable.getArgs()) {
                if (argument instanceof Component nested) {
                    collectKeys(nested, keys);
                }
            }
        }
        for (Component sibling : component.getSiblings()) {
            collectKeys(sibling, keys);
        }
    }

    /** 本模块发出的每个奖励与命令文案键, 两种语言都必须有非空的文字。 */
    private static void assertTranslated(GameTestHelper helper, List<String> keys) {
        JsonObject zh = readLang("zh_cn");
        JsonObject en = readLang("en_us");
        for (String key : keys) {
            if (key.startsWith("achievement.miningdim.")) {
                helper.assertTrue(hasText(zh, key) && hasText(en, key), "zh_cn / en_us 都必须有文案键 " + key);
            }
        }
    }

    private static boolean hasText(JsonObject lang, String key) {
        return lang.has(key) && lang.get(key).isJsonPrimitive() && !lang.get(key).getAsString().isBlank();
    }

    private static JsonObject readLang(String language) {
        String path = "assets/" + MiningConstants.MODID + "/lang/" + language + ".json";
        InputStream stream = AchievementRewardGameTests.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) {
            throw new AssertionError("运行时 classpath 找不到资源: " + path);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return GsonHelper.parse(reader);
        } catch (IOException exception) {
            throw new AssertionError("读不了语言文件 " + path, exception);
        }
    }

    /**
     * 该命令源能否把整条命令解析到一个可执行节点: 任何一层 requires 不放行, 解析都会停在那一层。只解析不执行,
     * 不受执行期检查 (如普通玩家用选择器被原版拒绝) 的干扰。
     */
    private static boolean parsesToCommand(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source,
                                           String command) {
        ParseResults<CommandSourceStack> parse = dispatcher.parse(command, source);
        return !parse.getReader().canRead() && parse.getContext().getCommand() != null;
    }

    /** 经真实分发器执行命令 (不带斜杠), 断言返回值与反馈里出现的翻译键; 返回这条命令产生的全部翻译键。 */
    private static List<String> expectCommand(GameTestHelper helper, CommandDispatcher<CommandSourceStack> dispatcher,
                                              CommandSourceStack source, CapturingSource output, String command,
                                              int expectedResult, String expectedKey) {
        output.messages.clear();
        Integer result;
        try {
            result = dispatcher.execute(command, source);
        } catch (CommandSyntaxException rejected) {
            result = null;
        }
        List<String> keys = keysOf(output.messages);
        output.messages.clear();
        helper.assertTrue(result != null && result == expectedResult,
                "/" + command + " 应返回 " + expectedResult + ", 实为 " + result + " (反馈 " + keys + ")");
        helper.assertTrue(keys.contains(expectedKey), "/" + command + " 的反馈应含 " + expectedKey + ", 实为 " + keys);
        return keys;
    }

    private static List<LedgerRow> ledger(Connection connection, UUID player) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT delta, reason, ref FROM achievement_point_ledger WHERE player_uuid=? ORDER BY id")) {
            statement.setString(1, player.toString());
            List<LedgerRow> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new LedgerRow(result.getLong(1), result.getString(2), result.getString(3)));
                }
            }
            return rows;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int countRewardRows(Connection connection, UUID player) {
        return countRows(connection, "SELECT COUNT(*) FROM achievement_reward WHERE player_uuid=?", player);
    }

    private static int countRows(Connection connection, String sql, UUID player) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** title_owned 里某个称号的 [source, source_ref]; 没有这一行为空表。 */
    private static List<String> titleSource(Connection connection, UUID player, ResourceLocation titleId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT source, source_ref FROM title_owned WHERE player_uuid=? AND title_id=?")) {
            statement.setString(1, player.toString());
            statement.setString(2, titleId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? List.of(result.getString(1), result.getString(2)) : List.of();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void execute(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 收集命令反馈的命令源: 成功与失败都收, 不向管理员广播。 */
    private static final class CapturingSource implements CommandSource {

        private final List<Component> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message);
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }
}
