package com.miningdim.champion;

import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.champion.reward.ContributionTracker;
import com.miningdim.core.Difficulty;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import com.miningdim.testutil.MockGameTestPlayers;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.LiteralContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 世界 BOSS 的 GameTest (ChampionStarAffix spec 第十章, batch {@code world_boss})。
 *
 * <p>能走真实路径的一律走真实路径: 召唤走真实的命令分发器 (控制台带 execute positioned 的命令源、有权限的玩家、没权限的玩家),
 * 公告从 mock 玩家连接的出站队列里读真实的聊天包与音效包, 击倒走事件总线, 与贡献池主结算同场 —— 结算后账本已空, 击倒公告
 * 却已带着输出排行发出, 证明读账本在清账之前。期望值按设计独立写在测试里, 每条都钉在判据两侧。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class WorldBossGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "world_boss";
    /** {@link #run} 的"命令被拒"返回值 (命令本身只返回 0 或 1)。 */
    private static final int REJECTED = -1;

    private WorldBossGameTests() {
    }

    /** 自然刷出的困难档: 多个种子各掷一批, 最高恰为 9 星 (上限可达), 从不出现 10 星; 下限仍是 5 星。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void naturalHardRollNeverReachesTenStars(GameTestHelper helper) {
        int highest = Integer.MIN_VALUE;
        int lowest = Integer.MAX_VALUE;
        for (long seed = 0L; seed < 256L; seed++) {
            RandomSource rng = RandomSource.create(seed);
            for (int roll = 0; roll < 64; roll++) {
                int star = ChampionSpawnPolicy.rollStar(Difficulty.HARD, rng);
                highest = Math.max(highest, star);
                lowest = Math.min(lowest, star);
            }
        }
        helper.assertTrue(highest == 9, "困难矿区自然掷星的最高值应恰为 9 (10 星只来自世界 BOSS), 实为 " + highest);
        helper.assertTrue(lowest == 5, "困难矿区自然掷星的最低值应为 5, 实为 " + lowest);
        for (Difficulty difficulty : Difficulty.values()) {
            helper.assertTrue(ChampionSpawnPolicy.maxStar(difficulty) < StarRank.MAX_STAR,
                    difficulty + " 的自然掷星区间不得含 10 星, 实为上限 " + ChampionSpawnPolicy.maxStar(difficulty));
        }
        helper.succeed();
    }

    /**
     * 命令召唤: 控制台 (execute positioned 的命令源) 与有权限的玩家都能召唤, BOSS 落在命令源处、带世界 BOSS 标记、常驻不消失,
     * 实体 NBT 往返后标记与常驻都还在; 普通 summon 的精英没有标记。权限不够解析不到命令, 星级只收 8-10, 非 Mob 实体被拒。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void worldBossCommandSpawnsMarkedPersistentBossFromAnySource(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = helper.getLevel();
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        List<Mob> spawned = new ArrayList<>();
        try {
            Vec3 consoleSpot = spot(helper, 1, 2, 1);
            CommandSourceStack console = console(server, level, consoleSpot);
            helper.assertTrue(run(dispatcher, console, "mchampion worldboss minecraft:skeleton 9") == 1,
                    "控制台带位置的命令源应能召唤世界 BOSS");
            Mob fromConsole = worldBossAt(helper, level, consoleSpot);
            spawned.add(fromConsole);
            MiningChampionData data = MiningChampions.get(fromConsole).orElseThrow();
            helper.assertTrue(data.isChampion() && data.star() == 9 && WorldBoss.isWorldBoss(fromConsole),
                    "控制台召唤的应是 9 星且带世界 BOSS 标记的精英, 实为 star=" + data.star());
            helper.assertTrue(fromConsole.isPersistenceRequired(), "世界 BOSS 必须常驻, 不能自然消失");
            helper.assertTrue(fromConsole.position().distanceToSqr(consoleSpot) < 1.0E-6D && fromConsole.isAddedToWorld(),
                    "世界 BOSS 应落在命令源的位置并已入世, 实为 " + fromConsole.position());
            helper.assertTrue(BloodPoolRegistry.has(fromConsole.getUUID()), "8 星以上的世界 BOSS 应建好影子血池");

            CompoundTag saved = new CompoundTag();
            helper.assertTrue(fromConsole.save(saved), "前提: 活着的世界 BOSS 应能存盘");
            Entity reloaded = EntityType.create(saved, level).orElseThrow();
            helper.assertTrue(reloaded instanceof Mob mob && mob.isPersistenceRequired() && WorldBoss.isWorldBoss(mob)
                            && MiningChampions.get(mob).orElseThrow().star() == 9,
                    "实体 NBT 往返后世界 BOSS 标记、星级与常驻都应保留");

            Vec3 playerSpot = spot(helper, 3, 2, 3);
            player.moveTo(playerSpot.x, playerSpot.y, playerSpot.z, 0.0F, 0.0F);
            CommandSourceStack asOp = player.createCommandSourceStack().withPermission(2).withSuppressedOutput();
            helper.assertTrue(run(dispatcher, asOp, "mchampion worldboss minecraft:zombie 10 regen_tissue thorns") == 1,
                    "有权限的玩家应能召唤世界 BOSS");
            Mob fromPlayer = worldBossAt(helper, level, playerSpot);
            spawned.add(fromPlayer);
            MiningChampionData playerData = MiningChampions.get(fromPlayer).orElseThrow();
            helper.assertTrue(playerData.star() == 10 && playerData.affixes().keySet()
                            .equals(Set.of(AffixDef.REGEN_TISSUE, AffixDef.THORNS)) && fromPlayer.isPersistenceRequired(),
                    "玩家召唤的应是 10 星、恰带指定两条词条的常驻世界 BOSS, 实为 star=" + playerData.star() + " "
                            + playerData.affixes().keySet());

            helper.assertTrue(run(dispatcher, asOp, "mchampion summon minecraft:zombie 10") == 1, "前提: 普通召唤应成功");
            List<Mob> plain = level.getEntitiesOfClass(Mob.class, AABB.ofSize(playerSpot, 8.0D, 4.0D, 8.0D),
                    mob -> MiningChampions.isChampion(mob) && !WorldBoss.isWorldBoss(mob));
            spawned.addAll(plain);
            helper.assertTrue(plain.size() == 1 && !plain.get(0).isPersistenceRequired(),
                    "普通 summon 的精英不带世界 BOSS 标记也不常驻, 实为 " + plain.size() + " 只");

            CommandSourceStack asPlayer = player.createCommandSourceStack().withPermission(0);
            helper.assertTrue(!parsesToCommand(dispatcher, asPlayer, "mchampion worldboss minecraft:zombie 10"),
                    "没有权限的玩家不能解析到 worldboss");
            helper.assertTrue(run(dispatcher, console, "mchampion worldboss minecraft:zombie 7") == REJECTED
                            && run(dispatcher, console, "mchampion worldboss minecraft:zombie 11") == REJECTED,
                    "世界 BOSS 的星级只收 8-10");
            helper.assertTrue(run(dispatcher, console, "mchampion worldboss minecraft:armor_stand 9") == 0,
                    "不是 Mob 的实体不能当世界 BOSS");
            helper.assertTrue(level.getEntitiesOfClass(Mob.class, AABB.ofSize(consoleSpot, 2.0D, 2.0D, 2.0D),
                    WorldBoss::isWorldBoss).size() == 1, "被拒的命令不得多召唤出世界 BOSS");
        } finally {
            spawned.forEach(Entity::discard);
            logout(server, player);
        }
        helper.succeed();
    }

    /**
     * 出现公告: 全部在线玩家 (含身在另一维度的) 各收到一行带星级、实体名、维度、坐标、词条名的聊天公告, 并各自听到一声
     * 凋灵生成音效; 普通 summon 不公告。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void spawnBroadcastReachesEveryOnlinePlayerInAnyDimension(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = helper.getLevel();
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        ServerPlayer here = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer away = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        List<Mob> spawned = new ArrayList<>();
        try {
            helper.assertTrue(level.dimension().equals(Level.OVERWORLD), "前提: 测试结构在主世界");
            sendToMiningDimension(helper, away);
            Vec3 bossSpot = spot(helper, 1, 2, 1);
            drain(here);
            drain(away);

            helper.assertTrue(run(dispatcher, console(server, level, bossSpot),
                    "mchampion worldboss minecraft:zombie 10 regen_tissue") == 1, "前提: 召唤应成功");
            Mob boss = worldBossAt(helper, level, bossSpot);
            spawned.add(boss);
            BlockPos at = boss.blockPosition();
            for (ServerPlayer player : List.of(here, away)) {
                Outbound heard = drain(player);
                List<TranslatableContents> lines = heard.lines(WorldBossBroadcast.SPAWNED_KEY);
                helper.assertTrue(lines.size() == 1, where(player) + " 应恰好收到一行出现公告, 实为 " + lines.size());
                Object[] args = lines.get(0).getArgs();
                helper.assertTrue(textOf(args[0]).equals("10★") && keysIn(args[1]).contains("entity.minecraft.zombie")
                                && keysIn(args[2]).contains(WorldBossBroadcast.KEY_PREFIX + "dimension.overworld"),
                        where(player) + " 的公告应写明 10★、僵尸与主世界");
                helper.assertTrue(args[3].equals(at.getX()) && args[4].equals(at.getY()) && args[5].equals(at.getZ()),
                        where(player) + " 的公告坐标应为 " + at.toShortString());
                helper.assertTrue(keysIn(args[6]).contains(AffixDef.REGEN_TISSUE.displayNameKey()),
                        where(player) + " 的公告应列出词条名");
                helper.assertTrue(heard.sounds.contains(WorldBossBroadcast.SPAWN_SOUND),
                        where(player) + " 应听到凋灵生成音效, 实为 " + heard.sounds);
            }

            here.moveTo(bossSpot.x + 4.0D, bossSpot.y, bossSpot.z, 0.0F, 0.0F);
            helper.assertTrue(run(dispatcher, here.createCommandSourceStack().withPermission(2).withSuppressedOutput(),
                    "mchampion summon minecraft:zombie 9") == 1, "前提: 普通召唤应成功");
            spawned.addAll(level.getEntitiesOfClass(Mob.class, AABB.ofSize(here.position(), 8.0D, 4.0D, 8.0D),
                    mob -> MiningChampions.isChampion(mob) && !WorldBoss.isWorldBoss(mob)));
            helper.assertTrue(drain(here).lines(WorldBossBroadcast.SPAWNED_KEY).isEmpty()
                            && drain(away).lines(WorldBossBroadcast.SPAWNED_KEY).isEmpty(),
                    "普通 summon 的精英不发世界 BOSS 公告");
        } finally {
            spawned.forEach(Entity::discard);
            logout(server, here);
            logout(server, away);
        }
        helper.succeed();
    }

    /**
     * 击倒公告: 死在事件总线上, 本模块在 HIGH 上 peek 账本、贡献池主结算随后在 NORMAL 上清账 —— 结算后账本已空, 公告却已
     * 按伤害从高到低列出前三名与各自占比, 第四名不上榜。没有玩家输出的死亡与 /kill 都不公告。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void defeatBroadcastListsTopContributorsBeforeRewardDrain(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = helper.getLevel();
        List<ServerPlayer> players = new ArrayList<>();
        List<Mob> bosses = new ArrayList<>();
        try {
            for (String name : List.of("wb-alpha", "wb-bravo", "wb-charlie", "wb-delta")) {
                players.add(MockGameTestPlayers.makeMockServerPlayerWithChannel(helper,
                        new GameProfile(UUID.randomUUID(), name)));
            }
            Mob boss = spawnBoss(helper, level, spot(helper, 1, 2, 1), bosses);
            long tick = level.getGameTime();
            double[] damage = {5_000.0D, 3_000.0D, 1_500.0D, 500.0D};
            // 记账顺序与伤害高低错开, 排行必须按伤害排而不是按账本顺序。
            for (int i = players.size() - 1; i >= 0; i--) {
                ContributionTracker.record(boss.getUUID(), players.get(i).getUUID(), damage[i], tick);
            }
            players.forEach(WorldBossGameTests::drain);

            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(boss, level.damageSources().playerAttack(players.get(0))));

            helper.assertTrue(!ContributionTracker.hasLedger(boss.getUUID()),
                    "前提: 贡献池主结算应已在 NORMAL 上清账 (本模块只 peek)");
            List<String> expected = List.of("wb-alpha 50.0%", "wb-bravo 30.0%", "wb-charlie 15.0%");
            for (ServerPlayer player : players) {
                Outbound heard = drain(player);
                List<TranslatableContents> lines = heard.lines(WorldBossBroadcast.DEFEATED_KEY);
                helper.assertTrue(lines.size() == 1, where(player) + " 应恰好收到一行击倒公告, 实为 " + lines.size());
                Object[] args = lines.get(0).getArgs();
                List<String> ranking = contributors(args[2]);
                helper.assertTrue(textOf(args[0]).equals("10★") && ranking.equals(expected),
                        where(player) + " 的击倒公告应按伤害列出前三名与占比 " + expected + ", 实为 " + ranking);
                helper.assertTrue(heard.sounds.contains(WorldBossBroadcast.DEFEAT_SOUND),
                        where(player) + " 应听到挑战完成音效, 实为 " + heard.sounds);
            }

            ServerPlayer alpha = players.get(0);
            Mob unfought = spawnBoss(helper, level, spot(helper, 3, 2, 1), bosses);
            drain(alpha);
            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(unfought, level.damageSources().generic()));
            helper.assertTrue(drain(alpha).lines(WorldBossBroadcast.DEFEATED_KEY).isEmpty(),
                    "没有玩家输出过的世界 BOSS 死亡不公告");

            Mob killed = spawnBoss(helper, level, spot(helper, 1, 2, 3), bosses);
            ContributionTracker.record(killed.getUUID(), alpha.getUUID(), 100.0D, level.getGameTime());
            drain(alpha);
            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(killed, level.damageSources().genericKill()));
            helper.assertTrue(drain(alpha).lines(WorldBossBroadcast.DEFEATED_KEY).isEmpty(),
                    "管理员 /kill (无视无敌的伤害) 不算被玩家击倒, 不公告");
        } finally {
            for (Mob boss : bosses) {
                ContributionTracker.discard(boss.getUUID());
                boss.discard();
            }
            players.forEach(player -> logout(server, player));
        }
        helper.succeed();
    }

    // ---- 工具 ----

    /** 经 API 落一只 10 星僵尸世界 BOSS (带一条固定词条), 记进待清理表。 */
    private static Mob spawnBoss(GameTestHelper helper, ServerLevel level, Vec3 at, List<Mob> bosses) {
        Mob boss = EntityType.ZOMBIE.create(level);
        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.REGEN_TISSUE, AffixQuality.LEGENDARY);
        helper.assertTrue(WorldBoss.spawn(level, boss, at, 0.0F, 10, affixes), "前提: 世界 BOSS 应能落地");
        bosses.add(boss);
        return boss;
    }

    private static Vec3 spot(GameTestHelper helper, int x, int y, int z) {
        return Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(x, y, z)));
    }

    /** 控制台命令源, 位置与维度同 execute in ... positioned ...; 不向控制台回显。 */
    private static CommandSourceStack console(MinecraftServer server, ServerLevel level, Vec3 at) {
        return server.createCommandSourceStack().withLevel(level).withPosition(at).withSuppressedOutput();
    }

    /** 命令源位置上恰好一只世界 BOSS。 */
    private static Mob worldBossAt(GameTestHelper helper, ServerLevel level, Vec3 at) {
        List<Mob> found = level.getEntitiesOfClass(Mob.class, AABB.ofSize(at, 2.0D, 2.0D, 2.0D),
                WorldBoss::isWorldBoss);
        helper.assertTrue(found.size() == 1, "命令源位置上应恰好有一只世界 BOSS, 实为 " + found.size());
        return found.get(0);
    }

    /** 把玩家真实传送进矿区维度某个实例区域 (与主世界不同的维度)。 */
    private static void sendToMiningDimension(GameTestHelper helper, ServerPlayer player) {
        ServerLevel mining = helper.getLevel().getServer().getLevel(MiningConstants.MINING_LEVEL);
        InstanceState instance = MiningServices.instanceManager().snapshot().stream().findFirst().orElse(null);
        if (mining == null || instance == null) {
            helper.fail("前提: 矿区维度与至少一个常驻实例区域必须已就绪");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        RegionBox box = instance.regionBox();
        player.setNoGravity(true);
        player.teleportTo(mining, box.originX() + 1.5D, box.originY() + 2.0D, box.originZ() + 1.5D, 0.0F, 0.0F);
        helper.assertTrue(player.level() == mining, "前提: 玩家应已进入矿区维度");
    }

    /** 执行命令并返回其结果; 被语法或参数校验拒绝 (CommandSyntaxException) 时返回 {@value #REJECTED}。 */
    private static int run(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source,
                           String command) {
        try {
            return dispatcher.execute(command, source);
        } catch (CommandSyntaxException rejected) {
            return REJECTED;
        }
    }

    /** 该命令源能否把整条命令解析到一个可执行节点 (任何一层 requires 不放行, 解析都会停在那一层)。 */
    private static boolean parsesToCommand(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source,
                                           String command) {
        ParseResults<CommandSourceStack> parse = dispatcher.parse(command, source);
        return !parse.getReader().canRead() && parse.getContext().getCommand() != null;
    }

    /** mock 连接出站队列里的系统聊天与提示音。 */
    private record Outbound(List<Component> chat, List<SoundEvent> sounds) {

        /** 顶层是该翻译键的聊天行。 */
        List<TranslatableContents> lines(String key) {
            List<TranslatableContents> matched = new ArrayList<>();
            for (Component line : chat) {
                if (line.getContents() instanceof TranslatableContents translatable && translatable.getKey().equals(key)) {
                    matched.add(translatable);
                }
            }
            return matched;
        }
    }

    /** 读空 mock 连接的出站队列 (关键步骤前也用它丢弃积压的包)。 */
    private static Outbound drain(ServerPlayer player) {
        EmbeddedChannel channel = (EmbeddedChannel) player.connection.connection.channel();
        List<Component> chat = new ArrayList<>();
        List<SoundEvent> sounds = new ArrayList<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            try {
                if (outbound instanceof ClientboundSystemChatPacket packet) {
                    chat.add(packet.content());
                } else if (outbound instanceof ClientboundSoundPacket packet) {
                    sounds.add(packet.getSound().value());
                }
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        }
        return new Outbound(chat, sounds);
    }

    /** 击倒公告的排行参数里, 按出现顺序取出每名贡献者的 "名字 占比"。 */
    private static List<String> contributors(Object ranking) {
        List<String> out = new ArrayList<>();
        if (ranking instanceof Component component) {
            collectContributors(component, out);
        }
        return out;
    }

    private static void collectContributors(Component component, List<String> out) {
        if (component.getContents() instanceof TranslatableContents translatable
                && translatable.getKey().equals(WorldBossBroadcast.CONTRIBUTOR_KEY)) {
            Object[] args = translatable.getArgs();
            out.add(textOf(args[0]) + " " + textOf(args[1]));
        }
        for (Component sibling : component.getSiblings()) {
            collectContributors(sibling, out);
        }
    }

    /** 参数里出现的全部翻译键 (含嵌套)。 */
    private static List<String> keysIn(Object argument) {
        List<String> keys = new ArrayList<>();
        if (argument instanceof Component component) {
            collectKeys(component, keys);
        }
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

    /** 字面量参数的文字 (非字面量返回空串)。 */
    private static String textOf(Object argument) {
        return argument instanceof Component component && component.getContents() instanceof LiteralContents literal
                ? literal.text() : "";
    }

    private static String where(ServerPlayer player) {
        return player.getGameProfile().getName() + "@" + player.level().dimension().location();
    }

    private static void logout(MinecraftServer server, ServerPlayer player) {
        if (server.getPlayerList().getPlayer(player.getUUID()) == player) {
            server.getPlayerList().remove(player);
        }
    }
}
