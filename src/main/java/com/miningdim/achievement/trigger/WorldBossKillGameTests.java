package com.miningdim.achievement.trigger;

import com.miningdim.achievement.AchievementIds;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.WorldBoss;
import com.miningdim.champion.reward.ContributionTracker;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MobInstanceTag;
import com.miningdim.testutil.MockGameTestPlayers;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.advancements.Advancement;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 世界 BOSS 击杀的成就判定 (Achievement_System_DesignSpec 6.4 世界 BOSS 例外、9.2 十星弑神; batch
 * {@code achievement_world_boss})。
 *
 * <p>BOSS 一律经真实的 {@code /mchampion} 命令召唤到测试结构所在的主世界 (矿区以外、不带实例标记), 死亡走事件总线与贡献池
 * 主结算同场, 或直接派发给钩子以喂确定的攻击记录。期望值按设计文档独立写在测试里: 十星弑神卡在 5% 占比两侧, 普通
 * summon 的精英在主世界照旧被过滤, 世界 BOSS 打过别人就不算独自击杀。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class WorldBossKillGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "achievement_world_boss";

    private WorldBossKillGameTests() {
    }

    /**
     * 矿区外的 10 星世界 BOSS 死在事件总线上: 三名在线有效贡献者都计 champion_kills 并拿到精英猎手; 十星弑神只给占比不少于
     * 5% 的两人 (90000 / 5000 / 4900, 占比 90.09% / 5.005% / 4.905%)。结算后账本已被主结算清空, 证明读在清账之前。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void worldBossKilledOutsideMiningGrantsStarTenByShare(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = helper.getLevel();
        ServerPlayer carry = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer support = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer shy = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        List<Mob> spawned = new ArrayList<>();
        try {
            helper.assertTrue(!level.dimension().equals(MiningConstants.MINING_LEVEL), "前提: 测试结构在矿区以外的维度");
            Mob boss = summonWorldBoss(helper, spot(helper, 1, 2, 1), spawned);
            helper.assertTrue(!MobInstanceTag.isTagged(boss) && !KillFilter.isInstanceMob(boss)
                            && KillFilter.countsForChampionKills(boss),
                    "世界 BOSS 不是矿区实例的怪, 却应计入精英击杀");
            long tick = level.getGameTime();
            ContributionTracker.record(boss.getUUID(), carry.getUUID(), 90_000.0D, tick);
            ContributionTracker.record(boss.getUUID(), support.getUUID(), 5_000.0D, tick);
            ContributionTracker.record(boss.getUUID(), shy.getUUID(), 4_900.0D, tick);

            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(boss, level.damageSources().playerAttack(carry)));

            helper.assertTrue(!ContributionTracker.hasLedger(boss.getUUID()),
                    "前提: 贡献池主结算应已清账 (本模块只 peek, 从不 drain)");
            for (ServerPlayer player : List.of(carry, support, shy)) {
                helper.assertTrue(stat(player, AchievementStats.CHAMPION_KILLS) == 1,
                        "世界 BOSS 的有效贡献者都应加 1 次 champion_kills");
                assertDone(helper, player, "combat/first_champion", true);
            }
            assertDone(helper, carry, "combat/star_10", true);
            assertDone(helper, support, "combat/star_10", true);
            assertDone(helper, shy, "combat/star_10", false);
        } finally {
            cleanUp(spawned);
            logout(server, carry);
            logout(server, support);
            logout(server, shy);
        }
        helper.succeed();
    }

    /** 普通 /mchampion summon 的 10 星精英在主世界被击杀: 奖励照常结算, 但不计 champion_kills、不给任何战斗成就。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void plainSummonedChampionStaysExcluded(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = helper.getLevel();
        ServerPlayer op = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        List<Mob> spawned = new ArrayList<>();
        try {
            Vec3 at = spot(helper, 1, 2, 1);
            op.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
            helper.assertTrue(run(server.getCommands().getDispatcher(),
                            op.createCommandSourceStack().withPermission(2).withSuppressedOutput(),
                            "mchampion summon minecraft:zombie 10 regen_tissue"),
                    "前提: 普通召唤应成功");
            List<Mob> found = level.getEntitiesOfClass(Mob.class, AABB.ofSize(at, 8.0D, 4.0D, 8.0D),
                    MiningChampions::isChampion);
            spawned.addAll(found);
            helper.assertTrue(found.size() == 1, "前提: 应恰好召唤出一只精英, 实为 " + found.size());
            Mob plain = found.get(0);
            helper.assertTrue(!WorldBoss.isWorldBoss(plain) && !KillFilter.countsForChampionKills(plain),
                    "普通 summon 的精英不是世界 BOSS, 不计入精英击杀");
            ContributionTracker.record(plain.getUUID(), op.getUUID(), 90_000.0D, level.getGameTime());

            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(plain, level.damageSources().playerAttack(op)));

            helper.assertTrue(!ContributionTracker.hasLedger(plain.getUUID()), "前提: 奖励仍由主结算照常清账");
            helper.assertTrue(stat(op, AchievementStats.CHAMPION_KILLS) == 0, "普通 summon 的精英不计 champion_kills");
            assertDone(helper, op, "combat/first_champion", false);
            assertDone(helper, op, "combat/star_10", false);
        } finally {
            cleanUp(spawned);
            logout(server, op);
        }
        helper.succeed();
    }

    /**
     * 攻击记录在矿区外也认世界 BOSS: 打过旁人的世界 BOSS 被独自输出击倒, 十星弑神到手但一人成军不给; 只打过他本人的世界 BOSS
     * 才算独自击杀。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void worldBossAttackLogCountsOutsideMining(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = helper.getLevel();
        ChampionKillHooks hooks = new ChampionKillHooks();
        ServerPlayer soloist = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer bystander = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        List<Mob> spawned = new ArrayList<>();
        try {
            Mob tagTeam = summonWorldBoss(helper, spot(helper, 1, 2, 1), spawned);
            hooks.onChampionAttack(new LivingHurtEvent(bystander, level.damageSources().mobAttack(tagTeam), 5.0F));
            killAlone(hooks, level, tagTeam, soloist);
            assertDone(helper, soloist, "combat/star_10", true);
            assertDone(helper, soloist, "combat/solo_star_9", false);

            Mob duel = summonWorldBoss(helper, spot(helper, 3, 2, 3), spawned);
            hooks.onChampionAttack(new LivingHurtEvent(soloist, level.damageSources().mobAttack(duel), 5.0F));
            killAlone(hooks, level, duel, soloist);
            assertDone(helper, soloist, "combat/solo_star_9", true);
        } finally {
            cleanUp(spawned);
            logout(server, soloist);
            logout(server, bystander);
        }
        helper.succeed();
    }

    // ---- 工具 ----

    /** 控制台在 at 处 (execute positioned) 召唤一只 10 星僵尸世界 BOSS, 返回它并记进待清理表。 */
    private static Mob summonWorldBoss(GameTestHelper helper, Vec3 at, List<Mob> spawned) {
        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack console = server.createCommandSourceStack().withLevel(helper.getLevel()).withPosition(at)
                .withSuppressedOutput();
        helper.assertTrue(run(server.getCommands().getDispatcher(), console,
                "mchampion worldboss minecraft:zombie 10 regen_tissue"), "前提: 世界 BOSS 召唤应成功");
        List<Mob> found = helper.getLevel().getEntitiesOfClass(Mob.class, AABB.ofSize(at, 2.0D, 2.0D, 2.0D),
                WorldBoss::isWorldBoss);
        spawned.addAll(found);
        helper.assertTrue(found.size() == 1, "前提: 命令源位置上应恰好有一只世界 BOSS, 实为 " + found.size());
        return found.get(0);
    }

    /** 账本里只记 player 一人、伤害恰为有效血量, 然后把死亡直接派发给钩子。 */
    private static void killAlone(ChampionKillHooks hooks, ServerLevel level, Mob boss, ServerPlayer player) {
        MiningChampionData data = MiningChampions.get(boss).orElseThrow();
        ContributionTracker.record(boss.getUUID(), player.getUUID(), data.effectiveHp(), level.getGameTime());
        hooks.onChampionDeath(new LivingDeathEvent(boss, level.damageSources().playerAttack(player)));
    }

    private static Vec3 spot(GameTestHelper helper, int x, int y, int z) {
        return Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(x, y, z)));
    }

    /** 执行命令, 返回是否成功 (结果为 1); 被语法或参数校验拒绝也算失败。 */
    private static boolean run(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source,
                               String command) {
        try {
            return dispatcher.execute(command, source) == 1;
        } catch (CommandSyntaxException rejected) {
            return false;
        }
    }

    private static void cleanUp(List<Mob> spawned) {
        for (Mob mob : spawned) {
            ContributionTracker.discard(mob.getUUID());
        }
        spawned.forEach(Entity::discard);
    }

    private static void assertDone(GameTestHelper helper, ServerPlayer player, String path, boolean done) {
        Advancement advancement = player.getServer().getAdvancements().getAdvancement(AchievementIds.id(path));
        helper.assertTrue(advancement != null, "进度未加载: " + path);
        boolean actual = player.getAdvancements().getOrStartProgress(advancement).isDone();
        helper.assertTrue(actual == done, player.getGameProfile().getName() + ": " + path
                + (done ? " 此时应已获得" : " 此时不应获得"));
    }

    private static int stat(ServerPlayer player, RegistryObject<ResourceLocation> stat) {
        return player.getStats().getValue(Stats.CUSTOM.get(stat.get()));
    }

    private static void logout(MinecraftServer server, ServerPlayer player) {
        if (server.getPlayerList().getPlayer(player.getUUID()) == player) {
            server.getPlayerList().remove(player);
        }
    }
}
