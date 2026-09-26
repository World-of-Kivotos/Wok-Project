package com.miningdim.achievement.trigger;

import com.miningdim.achievement.AchievementConfig;
import com.miningdim.achievement.AchievementIds;
import com.miningdim.achievement.AchievementServices;
import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.integration.ChampionPromoter;
import com.miningdim.champion.reward.ContributionTracker;
import com.miningdim.champion.reward.DamageContribution;
import com.miningdim.config.MiningServerConfig;
import com.miningdim.core.Difficulty;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.MobInstanceTag;
import com.miningdim.core.RegionBox;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.trap.StaticTrapKind;
import com.miningdim.trap.TrapDebugPlacement;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 成就事件钩子的 GameTest (Achievement_System_DesignSpec 6.1、6.3、6.4、6.5、9.5, batch {@code achievement_hooks})。
 *
 * <p>能走真实事件的一律走真实事件: 进出矿区是真的跨维度传送 (Forge 发 PlayerChangedDimensionEvent), 登出是真的
 * {@code PlayerList.remove}, 陷阱是真的 {@code gameMode.destroyBlock} 经事件总线让陷阱系统先取消、本模块后接收, 精英死亡
 * 是真的在事件总线上与贡献池主结算同场。其余用真实的 Forge 事件对象直接派发给钩子 (避免连带触发与本测试无关的整条死亡链),
 * 或经钩子的包内结算入口喂确定的 tick。期望值按设计文档独立写在测试里, 每条都钉在判据两侧 (删掉被测判据必挂)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class AchievementHookGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "achievement_hooks";
    private static final int TRIP_BLOCKS = 32;

    private AchievementHookGameTests() {
    }

    // ---- 有效撤离 (6.3) ----

    /** 停留不足、挖掘不足、途中阵亡三种都不算; 被更高优先级取消的死亡不作废; 难度按进入时所在区域记。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void extractionValidityFollowsRealDimensionChanges(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel mining = requireMiningLevel(helper);
        InstanceState easy = requireInstance(helper, Difficulty.EASY);
        InstanceState hard = requireInstance(helper, Difficulty.HARD);
        MiningTripHooks hooks = new MiningTripHooks();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        UUID id = player.getUUID();
        int dwell = AchievementConfig.EXTRACTION_MIN_DWELL_TICKS.get();
        try {
            // 1. 停留不足: 默认 6000 tick 门槛下同一 tick 进出, 挖够 32 块也不算。
            enterRegion(helper, player, mining, easy);
            helper.assertTrue(MiningTrips.isOpen(id) && MiningTrips.countedBlocks(id) == 0,
                    "从主世界进入矿区应开一段行程");
            helper.assertTrue(stat(player, AchievementStats.MINING_ENTRIES) == 1, "进入矿区应加 1 次 mining_entries");
            assertDone(helper, player, "mining/first_entry", true);
            breakStone(hooks, mining, player, easy, TRIP_BLOCKS);
            helper.assertTrue(MiningTrips.countedBlocks(id) == TRIP_BLOCKS, "行程应记下 32 次计数挖掘");
            leaveMining(helper, player);
            helper.assertTrue(!MiningTrips.isOpen(id), "离开矿区后行程应已结算并摘除");
            helper.assertTrue(stat(player, AchievementStats.MINING_EXTRACTIONS) == 0, "停留不足不是有效撤离");

            AchievementConfig.EXTRACTION_MIN_DWELL_TICKS.set(0);
            // 2. 计数挖掘差一块。
            enterRegion(helper, player, mining, easy);
            breakStone(hooks, mining, player, easy, TRIP_BLOCKS - 1);
            leaveMining(helper, player);
            helper.assertTrue(stat(player, AchievementStats.MINING_EXTRACTIONS) == 0, "31 块不够 minTripBlocks=32");

            // 3. 途中阵亡 (死亡在 LOWEST 上标记)。
            enterRegion(helper, player, mining, easy);
            breakStone(hooks, mining, player, easy, TRIP_BLOCKS);
            hooks.onPlayerDeath(new LivingDeathEvent(player, mining.damageSources().generic()));
            leaveMining(helper, player);
            helper.assertTrue(stat(player, AchievementStats.MINING_EXTRACTIONS) == 0, "途中阵亡的行程不算撤离");
            assertDone(helper, player, "mining/first_extraction", false);

            // 4. 死亡被更高优先级取消 (职业技能、纳米反应堆): 取消后的事件不会到达 LOWEST 且不收已取消事件的监听。
            enterRegion(helper, player, mining, hard);
            breakStone(hooks, mining, player, hard, TRIP_BLOCKS);
            LivingDeathEvent rescued = new LivingDeathEvent(player, mining.damageSources().generic());
            rescued.setCanceled(true);
            MinecraftForge.EVENT_BUS.post(rescued);
            leaveMining(helper, player);
            helper.assertTrue(stat(player, AchievementStats.MINING_EXTRACTIONS) == 1
                            && stat(player, AchievementStats.MINING_EXTRACTIONS_HARD) == 1,
                    "被救下的死亡不作废行程, 困难行程应同时加两项撤离统计");
            assertDone(helper, player, "mining/first_extraction", true);
            assertDone(helper, player, "mining/hard_extraction", true);
            assertDone(helper, player, "mining/medium_extraction", false);
            helper.assertTrue(stat(player, AchievementStats.MINING_ENTRIES) == 4, "四次进入应计 4 次 mining_entries");
        } finally {
            AchievementConfig.EXTRACTION_MIN_DWELL_TICKS.set(dwell);
            logout(server, player);
        }
        helper.succeed();
    }

    /**
     * 下线作废, 在矿区里上线从头计时 (补触发 enter_mining、不加 mining_entries), 死后在矿区外重生丢弃; 在矿区外上线
     * 不开行程也不触发 enter_mining。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void logoutDiscardsAndLoginInsideRestartsTheTrip(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel mining = requireMiningLevel(helper);
        InstanceState medium = requireInstance(helper, Difficulty.MEDIUM);
        MiningTripHooks hooks = new MiningTripHooks();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer other = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            enterRegion(helper, player, mining, medium);
            breakStone(hooks, mining, player, medium, 3);
            helper.assertTrue(MiningTrips.countedBlocks(player.getUUID()) == 3, "前提: 行程已记 3 块");
            helper.assertTrue(stat(player, AchievementStats.MINING_ENTRIES) == 1, "前提: 进入一次, mining_entries 为 1");
            // 模块上线前就留在矿区里的玩家: 人在矿区, 却从没经维度切换触发过 enter_mining。
            revokeFully(helper, player, "mining/first_entry");
            assertDone(helper, player, "mining/first_entry", false);
            hooks.onLoggedIn(new PlayerEvent.PlayerLoggedInEvent(player));
            helper.assertTrue(MiningTrips.countedBlocks(player.getUUID()) == 0,
                    "在矿区里上线应从头开一段新行程, 不接续之前的计数");
            // 在矿区里上线补触发 enter_mining: 否则 first_entry 下面的子成就 (千镐之始、平安归来等) 会先于它到手。
            assertDone(helper, player, "mining/first_entry", true);
            helper.assertTrue(stat(player, AchievementStats.MINING_ENTRIES) == 1,
                    "在矿区里上线不加 mining_entries (那只算从其他维度进入)");
            server.getPlayerList().remove(player);
            helper.assertTrue(!MiningTrips.isOpen(player.getUUID()), "下线应丢弃在途行程");

            MiningTrips.open(other.getUUID(), Difficulty.EASY, server.overworld().getGameTime());
            hooks.onPlayerDeath(new LivingDeathEvent(other, helper.getLevel().damageSources().generic()));
            hooks.onRespawn(new PlayerEvent.PlayerRespawnEvent(other, false));
            helper.assertTrue(!MiningTrips.isOpen(other.getUUID()), "死后在矿区外重生应丢弃这一趟");
            hooks.onLoggedIn(new PlayerEvent.PlayerLoggedInEvent(other));
            helper.assertTrue(!MiningTrips.isOpen(other.getUUID()), "在矿区外上线不开行程");
            assertDone(helper, other, "mining/first_entry", false);
        } finally {
            logout(server, player);
            logout(server, other);
        }
        helper.succeed();
    }

    /** 每日上限: 全部撤离 5 次、困难撤离 2 次, 两项各计各的 (6.1)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dailyCapsCountAllAndHardExtractionsIndependently(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MiningTripHooks hooks = new MiningTripHooks();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            for (int i = 0; i < 6; i++) {
                validTrip(hooks, player, Difficulty.EASY, 1L);
            }
            helper.assertTrue(stat(player, AchievementStats.MINING_EXTRACTIONS) == 5,
                    "当天第 6 次有效撤离应被每日上限 5 挡下, 实为 " + stat(player, AchievementStats.MINING_EXTRACTIONS));
            for (int i = 0; i < 3; i++) {
                validTrip(hooks, player, Difficulty.HARD, 1L);
            }
            helper.assertTrue(stat(player, AchievementStats.MINING_EXTRACTIONS) == 5,
                    "全部撤离的上限已满, 困难撤离不再加 mining_extractions");
            helper.assertTrue(stat(player, AchievementStats.MINING_EXTRACTIONS_HARD) == 2,
                    "困难撤离另计: 全部撤离的上限已满时仍计, 自己的上限是 2, 实为 "
                            + stat(player, AchievementStats.MINING_EXTRACTIONS_HARD));
            DailyCounterRepository counters = AchievementServices.dailyCounters();
            long today = DailyCounterRepository.today();
            helper.assertTrue(counters.count(player.getUUID(), DailyCounterRepository.EXTRACTION_KEY, today) == 5
                            && counters.count(player.getUUID(), DailyCounterRepository.HARD_EXTRACTION_KEY, today) == 2,
                    "每日计数表应停在 5 与 2");
            assertDone(helper, player, "mining/hard_extraction", true);
        } finally {
            logout(server, player);
        }
        helper.succeed();
    }

    /** 困难作业时长: 相邻计数挖掘的间隔单段封顶 3600, 总量不超过 块数 x 600; 只在有效的困难撤离时加 (6.1)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void hardActiveTicksAddCappedGapsUpToThePerBlockCeiling(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MiningTripHooks hooks = new MiningTripHooks();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            // 31 段间隔各 5000 tick, 单段封顶 3600 -> 111600, 超过 32 x 600 = 19200 的总量上限, 计 19200。
            validTrip(hooks, player, Difficulty.HARD, 5_000L);
            helper.assertTrue(stat(player, AchievementStats.MINING_HARD_ACTIVE_TICKS) == 19_200,
                    "应按块数上限计 19200, 实为 " + stat(player, AchievementStats.MINING_HARD_ACTIVE_TICKS));
            // 31 段各 100 tick = 3100, 低于上限, 原样累加。
            validTrip(hooks, player, Difficulty.HARD, 100L);
            helper.assertTrue(stat(player, AchievementStats.MINING_HARD_ACTIVE_TICKS) == 22_300,
                    "应再加 3100, 实为 " + stat(player, AchievementStats.MINING_HARD_ACTIVE_TICKS));
            // 中等难度的有效撤离不计作业时长。
            validTrip(hooks, player, Difficulty.MEDIUM, 100L);
            // 阵亡的困难行程不计。
            openTrip(player, Difficulty.HARD, 100L);
            hooks.onPlayerDeath(new LivingDeathEvent(player, helper.getLevel().damageSources().generic()));
            leaveEvent(hooks, player);
            helper.assertTrue(stat(player, AchievementStats.MINING_HARD_ACTIVE_TICKS) == 22_300,
                    "中等行程与阵亡行程都不应加作业时长, 实为 " + stat(player, AchievementStats.MINING_HARD_ACTIVE_TICKS));
        } finally {
            logout(server, player);
        }
        helper.succeed();
    }

    /** 被怪物或陷阱打过、残血撤离才是死里逃生; 没带实例标记的怪、玩家造成的伤害都不算威胁。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void narrowEscapeNeedsARecentHitFromAMobOrTrap(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel mining = requireMiningLevel(helper);
        InstanceState easy = requireInstance(helper, Difficulty.EASY);
        MiningTripHooks hooks = new MiningTripHooks();
        ServerPlayer mobHit = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer strayHit = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer lavaHit = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        Zombie tagged = instanceMob(mining, easy, true);
        Zombie stray = instanceMob(mining, easy, false);
        DamageSources damage = mining.damageSources();
        int dwell = AchievementConfig.EXTRACTION_MIN_DWELL_TICKS.get();
        try {
            helper.assertTrue(MiningTripHooks.isThreat(damage.mobAttack(tagged))
                            && MiningTripHooks.isThreat(damage.lava())
                            && MiningTripHooks.isThreat(damage.explosion(null, null)),
                    "实例怪的攻击、岩浆与无主爆炸 (陷阱) 都是威胁");
            helper.assertTrue(!MiningTripHooks.isThreat(damage.mobAttack(stray))
                            && !MiningTripHooks.isThreat(damage.playerAttack(mobHit))
                            && !MiningTripHooks.isThreat(damage.explosion(mobHit, mobHit))
                            && !MiningTripHooks.isThreat(damage.generic()),
                    "没有实例标记的怪、玩家的攻击与玩家引爆、泛用伤害都不是威胁");

            AchievementConfig.EXTRACTION_MIN_DWELL_TICKS.set(0);
            for (ServerPlayer player : List.of(mobHit, strayHit, lavaHit)) {
                enterRegion(helper, player, mining, easy);
                breakStone(hooks, mining, player, easy, TRIP_BLOCKS);
                player.setHealth(1.0F);
            }
            hooks.onPlayerHurt(new LivingHurtEvent(mobHit, damage.mobAttack(tagged), 1.0F));
            hooks.onPlayerHurt(new LivingHurtEvent(strayHit, damage.mobAttack(stray), 1.0F));
            hooks.onPlayerHurt(new LivingHurtEvent(lavaHit, damage.lava(), 1.0F));
            for (ServerPlayer player : List.of(mobHit, strayHit, lavaHit)) {
                leaveMining(helper, player);
                assertDone(helper, player, "mining/first_extraction", true);
            }
            assertDone(helper, mobHit, "mining/narrow_escape", true);
            assertDone(helper, lavaHit, "mining/narrow_escape", true);
            assertDone(helper, strayHit, "mining/narrow_escape", false);
        } finally {
            AchievementConfig.EXTRACTION_MIN_DWELL_TICKS.set(dwell);
            logout(server, mobHit);
            logout(server, strayHit);
            logout(server, lavaHit);
        }
        helper.succeed();
    }

    // ---- 计数挖掘与陷阱 (6.3、6.1) ----

    /** 计数挖掘的每条排除: FakePlayer、硬度 0、放置白名单、流体生成、区域外、矿区维度外; 石头与深板岩版矿石都算。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void countedBreakAppliesEveryExclusion(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel mining = requireMiningLevel(helper);
        InstanceState hard = requireInstance(helper, Difficulty.HARD);
        RegionBox box = hard.regionBox();
        MiningTripHooks hooks = new MiningTripHooks();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        FakePlayer fake = new FakePlayer(mining, new GameProfile(UUID.randomUUID(), "achievement_hook_fake"));
        long now = server.overworld().getGameTime();
        try {
            MiningTrips.open(player.getUUID(), Difficulty.HARD, now);
            MiningTrips.open(fake.getUUID(), Difficulty.HARD, now);

            breakAt(hooks, mining, inside(box, 40, 40), Blocks.STONE, player);
            helper.assertTrue(stat(player, AchievementStats.MINING_BLOCKS_MINED) == 1
                    && MiningTrips.countedBlocks(player.getUUID()) == 1, "区域内挖石头应计 1 次计数挖掘");

            Block deepslateTungsten = ForgeRegistries.BLOCKS.getValue(AchievementIds.id("deepslate_tungsten_ore"));
            helper.assertTrue(deepslateTungsten != null && deepslateTungsten != Blocks.AIR, "前提: 深板岩钨矿已注册");
            breakAt(hooks, mining, inside(box, 41, 40), deepslateTungsten, player);
            helper.assertTrue(stat(player, AchievementStats.MINING_BLOCKS_MINED) == 2, "矿石也是计数挖掘");
            helper.assertTrue(criterionDone(player, "mining/ore_codex", "tungsten")
                            && !criterionDone(player, "mining/ore_codex", "diamond"),
                    "深板岩钨矿应算作 tungsten (石头版与深板岩版视为同一种), 且只点亮这一种");

            breakAt(hooks, mining, inside(box, 42, 40), Blocks.DIAMOND_ORE, fake);
            helper.assertTrue(MiningTrips.countedBlocks(fake.getUUID()) == 0
                            && !criterionDone(fake, "mining/ore_codex", "diamond"),
                    "FakePlayer 的破坏既不计数也不触发 mine_ore");

            breakAt(hooks, mining, inside(box, 43, 40), Blocks.TORCH, player);
            helper.assertTrue(stat(player, AchievementStats.MINING_BLOCKS_MINED) == 2, "硬度为 0 的方块不计");

            BlockPos planks = inside(box, 44, 40);
            List<? extends String> whitelist = MiningServerConfig.PLACE_WHITELIST.get();
            try {
                MiningServerConfig.PLACE_WHITELIST.set(List.of("minecraft:scaffolding", "minecraft:oak_planks"));
                breakAt(hooks, mining, planks, Blocks.OAK_PLANKS, player);
                helper.assertTrue(stat(player, AchievementStats.MINING_BLOCKS_MINED) == 2, "放置白名单里的方块不计");
            } finally {
                MiningServerConfig.PLACE_WHITELIST.set(whitelist);
            }
            breakAt(hooks, mining, planks, Blocks.OAK_PLANKS, player);
            helper.assertTrue(stat(player, AchievementStats.MINING_BLOCKS_MINED) == 3,
                    "对照: 橡木板不在白名单时照常计数, 上一条确实是白名单挡下的");

            BlockPos generated = inside(box, 45, 40);
            hooks.onFluidPlace(new BlockEvent.FluidPlaceBlockEvent(mining, generated, generated.above(),
                    Blocks.COBBLESTONE.defaultBlockState()));
            breakAt(hooks, mining, generated, Blocks.COBBLESTONE, player);
            helper.assertTrue(stat(player, AchievementStats.MINING_BLOCKS_MINED) == 3, "流体生成的方块不计");
            breakAt(hooks, mining, generated, Blocks.COBBLESTONE, player);
            helper.assertTrue(stat(player, AchievementStats.MINING_BLOCKS_MINED) == 4,
                    "流体生成位置被挖一次就消费掉, 之后同一位置的方块照常计数");

            BlockPos buffer = new BlockPos(box.originX() - 1, box.originY() + 8, box.originZ() - 1);
            helper.assertTrue(MiningServices.instanceManager().regionAt(buffer.getX(), buffer.getZ()) == null,
                    "前提: 区域原点外侧一格落在缓冲带, 不属于任何实例");
            breakAt(hooks, mining, buffer, Blocks.STONE, player);
            breakAt(hooks, helper.getLevel(), inside(box, 46, 40), Blocks.STONE, player);
            helper.assertTrue(stat(player, AchievementStats.MINING_BLOCKS_MINED) == 4
                            && MiningTrips.countedBlocks(player.getUUID()) == 4,
                    "区域外与矿区维度外的破坏都不计, 实为 " + stat(player, AchievementStats.MINING_BLOCKS_MINED));
        } finally {
            MiningTrips.discard(player.getUUID());
            MiningTrips.discard(fake.getUUID());
            fake.getAdvancements().stopListening();
            logout(server, player);
        }
        helper.succeed();
    }

    /** 玩家自己挖到陷阱: 陷阱系统在 HIGHEST 取消破坏并吞掉方块, 本模块在 LOWEST 收到已取消的事件。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void trapSprungNeedsTheOwnCancelledBreakThatLeftAir(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel mining = requireMiningLevel(helper);
        InstanceState medium = requireInstance(helper, Difficulty.MEDIUM);
        RegionBox box = medium.regionBox();
        MiningTripHooks hooks = new MiningTripHooks();
        ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        try {
            enterRegion(helper, player, mining, medium);
            BlockPos trap = inside(box, 60, 60);
            for (int dy = 1; dy <= 6; dy++) {
                // 头顶留空: 落石陷阱找不到承重方块就不坍塌, 反应窗口到点后不会在测试区域里留下落石。
                mining.setBlock(trap.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            TrapDebugPlacement.place(mining, trap, StaticTrapKind.COLLAPSING_TUNNEL, Blocks.IRON_ORE.defaultBlockState());
            boolean broken = player.gameMode.destroyBlock(trap);
            helper.assertTrue(!broken && mining.getBlockState(trap).isAir(),
                    "前提: 陷阱系统应取消这次破坏并自己吞掉方块");
            helper.assertTrue(stat(player, AchievementStats.MINING_TRAPS_SPRUNG) == 1, "挖到陷阱应计 1 次");
            helper.assertTrue(stat(player, AchievementStats.MINING_BLOCKS_MINED) == 0, "被取消的破坏不是计数挖掘");
            assertDone(helper, player, "mining/trap_sprung", true);

            BlockPos guarded = inside(box, 62, 60);
            mining.setBlock(guarded, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
            hooks.onTrapSprung(cancelledBreak(mining, guarded, Blocks.IRON_ORE, player));
            helper.assertTrue(stat(player, AchievementStats.MINING_TRAPS_SPRUNG) == 1,
                    "被别的系统拦下、矿石还在原处的破坏不是陷阱");

            BlockPos cleared = inside(box, 64, 60);
            mining.setBlock(cleared, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            hooks.onTrapSprung(cancelledBreak(mining, cleared, Blocks.STONE, player));
            helper.assertTrue(stat(player, AchievementStats.MINING_TRAPS_SPRUNG) == 1, "不是伪装矿石的方块不是陷阱");
            hooks.onTrapSprung(new BlockEvent.BreakEvent(mining, cleared, Blocks.IRON_ORE.defaultBlockState(), player));
            helper.assertTrue(stat(player, AchievementStats.MINING_TRAPS_SPRUNG) == 1, "没被取消的破坏是普通挖掘, 不是陷阱");
        } finally {
            logout(server, player);
        }
        helper.succeed();
    }

    // ---- 精英怪击杀 (6.4) ----

    /**
     * 精英死在事件总线上: 本模块在 HIGH 上读账本, 贡献池主结算随后在 NORMAL 上清账 —— 结算后账本已空, 统计与成就却已发出,
     * 证明读在清账之前。只有合格的贡献者计数; 词条随击杀传给触发器。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void championKillReadsTheLedgerBeforeTheRewardDrain(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel mining = requireMiningLevel(helper);
        InstanceState hard = requireInstance(helper, Difficulty.HARD);
        ServerPlayer main = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer bystander = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
            affixes.put(AffixDef.GIGANTISM, AffixQuality.COMMON);
            Zombie champion = champion(mining, hard, 1, affixes, true);
            MiningChampionData data = MiningChampions.get(champion).orElseThrow();
            long tick = mining.getGameTime();
            ContributionTracker.record(champion.getUUID(), main.getUUID(), data.effectiveHp(), tick);
            ContributionTracker.record(champion.getUUID(), bystander.getUUID(), 0.01D, tick);

            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(champion, mining.damageSources().generic()));

            helper.assertTrue(!ContributionTracker.hasLedger(champion.getUUID()),
                    "前提: 贡献池主结算应已清账 (本模块只 peek, 从不 drain)");
            helper.assertTrue(stat(main, AchievementStats.CHAMPION_KILLS) == 1, "合格贡献者应加 1 次 champion_kills");
            assertDone(helper, main, "combat/first_champion", true);
            assertDone(helper, main, "combat/giant_slayer", true);
            assertDone(helper, main, "combat/star_6", false);
            helper.assertTrue(stat(bystander, AchievementStats.CHAMPION_KILLS) == 0,
                    "蹭伤害的玩家不是有效贡献者, 不计数");
            assertDone(helper, bystander, "combat/first_champion", false);
        } finally {
            logout(server, main);
            logout(server, bystander);
        }
        helper.succeed();
    }

    /** 击杀过滤: 无实例标记、词条召唤物、非精英、矿区外一律跳过; 挂机冻结只挡计数统计, 不挡一次性成就。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void championFiltersSkipUntaggedSummonedAndAfkCounts(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel mining = requireMiningLevel(helper);
        InstanceState easy = requireInstance(helper, Difficulty.EASY);
        ChampionKillHooks hooks = new ChampionKillHooks();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer idle = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        List<Zombie> spawned = new ArrayList<>();
        try {
            Zombie untagged = champion(mining, easy, 2, Map.of(), false);
            Zombie summoned = champion(mining, easy, 2, Map.of(), true);
            MiningChampions.get(summoned).orElseThrow().markSummonedByAffix();
            Zombie plain = instanceMob(mining, easy, true);
            Zombie elsewhere = EntityType.ZOMBIE.create(helper.getLevel());
            MobInstanceTag.mark(elsewhere, easy.instanceId());
            ChampionPromoter.applyChampion(elsewhere, 2, Map.of());
            Zombie counted = champion(mining, easy, 2, Map.of(), true);
            spawned.addAll(List.of(untagged, summoned, plain, elsewhere, counted));

            for (Zombie victim : spawned) {
                ContributionTracker.record(victim.getUUID(), player.getUUID(), 50.0D, mining.getGameTime());
                hooks.onChampionDeath(new LivingDeathEvent(victim, victim.level().damageSources().generic()));
                boolean shouldCount = victim == counted;
                helper.assertTrue(stat(player, AchievementStats.CHAMPION_KILLS) == (shouldCount ? 1 : 0),
                        (shouldCount ? "带标记的矿区精英应计数" : "被过滤的击杀不应计数") + ", 实为 "
                                + stat(player, AchievementStats.CHAMPION_KILLS));
            }
            assertDone(helper, player, "combat/first_champion", true);

            Zombie afk = champion(mining, easy, 2, Map.of(), true);
            spawned.add(afk);
            ContributionTracker.record(afk.getUUID(), idle.getUUID(), 50.0D, mining.getGameTime());
            ChampionKillHooks.settle(server, afk, MiningChampions.get(afk).orElseThrow(), Set.of(), frozen -> true);
            helper.assertTrue(stat(idle, AchievementStats.CHAMPION_KILLS) == 0, "挂机冻结的贡献者不加 champion_kills");
            assertDone(helper, idle, "combat/first_champion", true);
        } finally {
            for (Zombie victim : spawned) {
                ContributionTracker.discard(victim.getUUID());
            }
            logout(server, player);
            logout(server, idle);
        }
        helper.succeed();
    }

    /** 独自击杀、输出占比与战斗时长按 6.4 的定义逐项计算, 并与真实的条件实例对拍。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void soloShareAndFightTimeFollowTheDefinition(GameTestHelper helper) {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        DamageContribution alone = new DamageContribution(a, 100.0D, 1_000L, true);
        ChampionKill solo = ChampionKillHooks.killView(alone, List.of(alone), 9, Set.of(), 100.0D, Set.of(a),
                19_000L);
        helper.assertTrue(solo.solo() && solo.share() == 1.0D && solo.fightTicks() == 18_000L,
                "账本只有他、伤害恰为有效血量、只打过他: 独自击杀, 占比 1, 战斗 18000 tick, 实为 " + solo);
        helper.assertTrue(ChampionKillTrigger.TriggerInstance.soloWithin(9, 18_000L).matches(solo),
                "一人成军的条件应成立");

        DamageContribution weak = new DamageContribution(a, 99.9D, 1_000L, true);
        helper.assertTrue(!ChampionKillHooks.killView(weak, List.of(weak), 9, Set.of(), 100.0D, Set.of(a), 2_000L)
                .solo(), "记录伤害低于有效血量不算独自击杀");
        helper.assertTrue(!ChampionKillHooks.killView(alone, List.of(alone), 9, Set.of(), 100.0D, Set.of(a, b), 2_000L)
                .solo(), "精英还攻击过别人就不算独自击杀");
        helper.assertTrue(ChampionKillHooks.killView(alone, List.of(alone), 9, Set.of(), 100.0D, Set.of(), 2_000L)
                .solo(), "精英一次没出手就被打死, 仍是独自击杀");

        DamageContribution carry = new DamageContribution(a, 95.0D, 500L, true);
        DamageContribution support = new DamageContribution(b, 5.0D, 300L, false);
        List<DamageContribution> ledger = List.of(support, carry);
        ChampionKill carryView = ChampionKillHooks.killView(carry, ledger, 10, Set.of(AffixDef.GIGANTISM), 100.0D,
                Set.of(a), 1_000L);
        helper.assertTrue(!carryView.solo() && Math.abs(carryView.share() - 0.95D) < 1e-9
                        && carryView.fightTicks() == 700L && carryView.affixes().equals(Set.of(AffixDef.GIGANTISM)),
                "两人打的精英: 不是独自击杀, 占比 0.95 (离线者的伤害也算进总数), 战斗时长从最早的首伤算起, 实为 "
                        + carryView);
        ChampionKillTrigger.TriggerInstance worldBoss =
                new ChampionKillTrigger.TriggerInstance(ContextAwarePredicate.ANY, 10, null, 0.05D, false, null);
        helper.assertTrue(worldBoss.matches(ChampionKillHooks.killView(support, ledger, 10, Set.of(), 100.0D,
                Set.of(a), 1_000L)), "占比恰为 5% 满足 min_share=0.05");
        DamageContribution slightlyLess = new DamageContribution(b, 4.9D, 300L, true);
        helper.assertTrue(!worldBoss.matches(ChampionKillHooks.killView(slightlyLess,
                        List.of(slightlyLess, new DamageContribution(a, 95.1D, 500L, true)), 10, Set.of(), 100.0D,
                        Set.of(a), 1_000L)),
                "占比 4.9% 不满足 min_share=0.05");
        helper.succeed();
    }

    // ---- 枪械击杀 (6.1、6.2) ----

    /** 枪械击杀的过滤与两项统计; 距离只算水平方向。触发器本身随 TaCZ 的加载条件出现, 这里只测不依赖 TaCZ 的判定。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gunKillsCountOnlyInstanceMobsAndSkipAfk(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel mining = requireMiningLevel(helper);
        InstanceState easy = requireInstance(helper, Difficulty.EASY);
        ServerPlayer shooter = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            Zombie tagged = instanceMob(mining, easy, true);
            Zombie stray = instanceMob(mining, easy, false);
            Zombie elsewhere = EntityType.ZOMBIE.create(helper.getLevel());
            MobInstanceTag.mark(elsewhere, easy.instanceId());

            GunKillHooks.onGunKill(shooter, tagged, true, player -> false);
            GunKillHooks.onGunKill(shooter, tagged, false, player -> false);
            helper.assertTrue(stat(shooter, AchievementStats.GUN_KILLS) == 2
                            && stat(shooter, AchievementStats.GUN_HEADSHOT_KILLS) == 1,
                    "两次击杀一次爆头: gun_kills=2, gun_headshot_kills=1");
            GunKillHooks.onGunKill(shooter, stray, true, player -> false);
            GunKillHooks.onGunKill(shooter, elsewhere, true, player -> false);
            GunKillHooks.onGunKill(shooter, tagged, true, player -> true);
            helper.assertTrue(stat(shooter, AchievementStats.GUN_KILLS) == 2
                            && stat(shooter, AchievementStats.GUN_HEADSHOT_KILLS) == 1,
                    "无实例标记、矿区外、挂机冻结的击杀都不计");

            Zombie near = instanceMob(mining, easy, true);
            Zombie far = instanceMob(mining, easy, true);
            near.setPos(10.0D, 0.0D, 10.0D);
            far.setPos(70.0D, 200.0D, 90.0D);
            helper.assertTrue(Math.abs(GunKillHooks.horizontalDistance(near, far) - 100.0D) < 1e-9,
                    "水平距离只算 dx、dz: (60, 80) 应为 100, 高度差不计");
        } finally {
            logout(server, shooter);
        }
        helper.succeed();
    }

    // ---- 成就数量、婚姻与共享背包 ----

    /** 成就数量随真实授予的 AdvancementEarnEvent 触发; 页签根与 meta 页签不计, 第 10 个计数成就到手时授予 meta/count_10。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void achievementCountFollowsCountableEarnsOnly(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            List<String> nine = List.of("mining/first_entry", "mining/first_extraction", "mining/blocks_1k",
                    "mining/trap_sprung", "mining/deep_regular", "mining/medium_extraction", "mining/narrow_escape",
                    "mining/dungeon_chest", "mining/hard_extraction");
            for (String path : nine) {
                grantFully(helper, player, path);
            }
            assertDone(helper, player, "meta/count_10", false);
            for (String tab : AchievementIds.TABS) {
                grantFully(helper, player, tab + "/" + AchievementIds.ROOT_NAME);
            }
            assertDone(helper, player, "meta/count_10", false);
            helper.assertTrue(AchievementServices.catalog().countEarned(player.getAdvancements()) == 9,
                    "九个计数成就加六个页签根, 成就数量仍是 9");
            grantFully(helper, player, "mining/blocks_10k");
            assertDone(helper, player, "meta/count_10", true);
            helper.assertTrue(AchievementServices.catalog().countEarned(player.getAdvancements()) == 10,
                    "meta/count_10 自己不计入成就数量, 到手后成就数量仍是 10");
        } finally {
            logout(server, player);
        }
        helper.succeed();
    }

    /** 婚姻指针在登录时与存档时触发 married; 只认主世界那一次存档; 未婚不触发。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void marriagePointerTriggersMarriedAtLoginAndSave(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel mining = requireMiningLevel(helper);
        PlayerProgressHooks hooks = new PlayerProgressHooks();
        ServerPlayer single = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer loggedIn = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer saved = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            setMarriage(loggedIn, 7L, saved.getUUID());
            setMarriage(saved, 7L, loggedIn.getUUID());

            hooks.onLoggedIn(new PlayerEvent.PlayerLoggedInEvent(loggedIn));
            assertDone(helper, loggedIn, "social/married", true);
            assertDone(helper, saved, "social/married", false);

            hooks.onLevelSave(new LevelEvent.Save(mining));
            assertDone(helper, saved, "social/married", false);
            hooks.onLevelSave(new LevelEvent.Save(server.overworld()));
            assertDone(helper, saved, "social/married", true);
            assertDone(helper, single, "social/married", false);
        } finally {
            setMarriage(loggedIn, IMiningPlayerData.NO_MARRIAGE, null);
            setMarriage(saved, IMiningPlayerData.NO_MARRIAGE, null);
            logout(server, single);
            logout(server, loggedIn);
            logout(server, saved);
        }
        helper.succeed();
    }

    /**
     * 打开注册名为 miningdim:marriage_backpack 的菜单才触发; 没有 MenuType 的菜单 (马的物品栏) 不出错。共享背包只在婚姻
     * 模块核实了有效婚姻之后才打得开, 所以打开的那一刻先补 married: 刚办完婚礼、登录与存档的补查都还没轮到的玩家,
     * 不会在父成就"执子之手"之前拿到"两人的口袋"。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sharedBackpackMenuOpenTriggers(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            ChestMenu chest = ChestMenu.threeRows(1, player.getInventory(), new SimpleContainer(27));
            MinecraftForge.EVENT_BUS.post(new PlayerContainerEvent.Open(player, chest));
            Horse horse = EntityType.HORSE.create(helper.getLevel());
            HorseInventoryMenu saddle = new HorseInventoryMenu(2, player.getInventory(), new SimpleContainer(2), horse);
            MinecraftForge.EVENT_BUS.post(new PlayerContainerEvent.Open(player, saddle));
            assertDone(helper, player, "social/shared_backpack", false);

            // 刚办完婚礼: 婚姻指针已写好, 登录与存档的补查都还没轮到。
            setMarriage(player, 1L, UUID.randomUUID());
            assertDone(helper, player, "social/married", false);
            MenuType<?> backpackType = ForgeRegistries.MENU_TYPES.getValue(PlayerProgressHooks.SHARED_BACKPACK_MENU);
            helper.assertTrue(backpackType != null, "前提: 共享背包菜单已注册为 miningdim:marriage_backpack");
            FriendlyByteBuf extraData = new FriendlyByteBuf(Unpooled.buffer());
            extraData.writeLong(1L);
            extraData.writeVarInt(9);
            AbstractContainerMenu backpack = backpackType.create(3, player.getInventory(), extraData);
            MinecraftForge.EVENT_BUS.post(new PlayerContainerEvent.Open(player, backpack));
            assertDone(helper, player, "social/shared_backpack", true);
            assertDone(helper, player, "social/married", true);
        } finally {
            setMarriage(player, IMiningPlayerData.NO_MARRIAGE, null);
            logout(server, player);
        }
        helper.succeed();
    }

    // ---- 工具 ----

    /** 以包内入口开一段满足停留与挖掘门槛的行程 (计数挖掘间隔 gapTicks), 再派发真实的离开事件结算。 */
    private static void validTrip(MiningTripHooks hooks, ServerPlayer player, Difficulty difficulty, long gapTicks) {
        openTrip(player, difficulty, gapTicks);
        leaveEvent(hooks, player);
    }

    /** 开一段早于门槛进入的行程并记 32 次计数挖掘, 挖掘时刻按 gapTicks 等距排在进入之后、此刻之前。 */
    private static void openTrip(ServerPlayer player, Difficulty difficulty, long gapTicks) {
        long now = player.server.overworld().getGameTime();
        long enter = now - AchievementConfig.EXTRACTION_MIN_DWELL_TICKS.get() - gapTicks * TRIP_BLOCKS - 1L;
        MiningTrips.open(player.getUUID(), difficulty, enter);
        for (int i = 0; i < TRIP_BLOCKS; i++) {
            MiningTrips.recordCountedBreak(player.getUUID(), enter + 1L + gapTicks * i,
                    AchievementConfig.HARD_ACTIVE_GAP_CAP_TICKS.get());
        }
    }

    private static void leaveEvent(MiningTripHooks hooks, ServerPlayer player) {
        hooks.onChangedDimension(new PlayerEvent.PlayerChangedDimensionEvent(player, MiningConstants.MINING_LEVEL,
                Level.OVERWORLD));
    }

    /** 真实跨维度传送进某实例的区域 (Forge 在传送末尾发 PlayerChangedDimensionEvent)。 */
    private static void enterRegion(GameTestHelper helper, ServerPlayer player, ServerLevel mining,
                                    InstanceState instance) {
        RegionBox box = instance.regionBox();
        player.setNoGravity(true);
        player.teleportTo(mining, box.originX() + 1.5D, box.originY() + 2.0D, box.originZ() + 1.5D, 0.0F, 0.0F);
        helper.assertTrue(player.level() == mining
                        && MiningServices.instanceManager().regionAt(player.getBlockX(), player.getBlockZ()) == instance,
                "前提: 玩家应已进入 " + instance.difficulty().configName() + " 区域");
    }

    /** 真实跨维度传送回主世界的测试结构旁。 */
    private static void leaveMining(GameTestHelper helper, ServerPlayer player) {
        BlockPos spot = helper.absolutePos(new BlockPos(1, 2, 1));
        player.teleportTo(helper.getLevel(), spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, 0.0F, 0.0F);
        helper.assertTrue(player.level() == helper.getLevel(), "前提: 玩家应已回到主世界");
    }

    /** 在区域内不同位置派发 count 次挖石头的真实 BreakEvent。 */
    private static void breakStone(MiningTripHooks hooks, ServerLevel mining, ServerPlayer player,
                                   InstanceState instance, int count) {
        RegionBox box = instance.regionBox();
        for (int i = 0; i < count; i++) {
            breakAt(hooks, mining, inside(box, 8 + i % 16, 8 + i / 16), Blocks.STONE, player);
        }
    }

    private static void breakAt(MiningTripHooks hooks, ServerLevel level, BlockPos pos, Block block,
                                ServerPlayer player) {
        hooks.onBlockBreak(new BlockEvent.BreakEvent(level, pos, block.defaultBlockState(), player));
    }

    private static BlockEvent.BreakEvent cancelledBreak(ServerLevel level, BlockPos pos, Block block,
                                                         ServerPlayer player) {
        BlockState state = block.defaultBlockState();
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, pos, state, player);
        event.setCanceled(true);
        return event;
    }

    /** 区域内相对原点 (dx, dz) 的一格, 高度取区域中部。 */
    private static BlockPos inside(RegionBox box, int dx, int dz) {
        return new BlockPos(box.originX() + dx, box.originY() + box.sizeY() / 2, box.originZ() + dz);
    }

    /** 矿区维度里一只 (不入世的) 僵尸, tagged 时打上实例标记。 */
    private static Zombie instanceMob(ServerLevel mining, InstanceState instance, boolean tagged) {
        Zombie zombie = EntityType.ZOMBIE.create(mining);
        BlockPos pos = inside(instance.regionBox(), 100, 100);
        zombie.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        if (tagged) {
            MobInstanceTag.mark(zombie, instance.instanceId());
        }
        return zombie;
    }

    private static Zombie champion(ServerLevel mining, InstanceState instance, int star,
                                   Map<AffixDef, AffixQuality> affixes, boolean tagged) {
        Zombie zombie = instanceMob(mining, instance, tagged);
        ChampionPromoter.applyChampion(zombie, star, affixes);
        return zombie;
    }

    private static void setMarriage(ServerPlayer player, long marriageId, UUID spouse) {
        IMiningPlayerData data = MiningCapabilities.get(player).orElseThrow();
        data.setMarriageId(marriageId);
        data.setSpouseUUID(spouse);
    }

    private static void grantFully(GameTestHelper helper, ServerPlayer player, String path) {
        Advancement advancement = player.getServer().getAdvancements().getAdvancement(AchievementIds.id(path));
        helper.assertTrue(advancement != null, "进度未加载: " + path);
        for (String criterion : advancement.getCriteria().keySet()) {
            player.getAdvancements().award(advancement, criterion);
        }
    }

    /** 撤销进度的全部条件 (撤销后原版重新为它挂上监听, 触发器可以再次授予)。 */
    private static void revokeFully(GameTestHelper helper, ServerPlayer player, String path) {
        Advancement advancement = player.getServer().getAdvancements().getAdvancement(AchievementIds.id(path));
        helper.assertTrue(advancement != null, "进度未加载: " + path);
        for (String criterion : advancement.getCriteria().keySet()) {
            player.getAdvancements().revoke(advancement, criterion);
        }
    }

    private static void assertDone(GameTestHelper helper, ServerPlayer player, String path, boolean done) {
        Advancement advancement = player.getServer().getAdvancements().getAdvancement(AchievementIds.id(path));
        helper.assertTrue(advancement != null, "进度未加载: " + path);
        boolean actual = player.getAdvancements().getOrStartProgress(advancement).isDone();
        helper.assertTrue(actual == done, player.getGameProfile().getName() + ": " + path
                + (done ? " 此时应已获得" : " 此时不应获得"));
    }

    private static boolean criterionDone(ServerPlayer player, String path, String criterion) {
        Advancement advancement = player.getServer().getAdvancements().getAdvancement(AchievementIds.id(path));
        CriterionProgress progress = player.getAdvancements().getOrStartProgress(advancement).getCriterion(criterion);
        return progress != null && progress.isDone();
    }

    private static int stat(ServerPlayer player, RegistryObject<ResourceLocation> stat) {
        return player.getStats().getValue(Stats.CUSTOM.get(stat.get()));
    }

    private static void logout(MinecraftServer server, ServerPlayer player) {
        if (server.getPlayerList().getPlayer(player.getUUID()) == player) {
            server.getPlayerList().remove(player);
        }
    }

    private static InstanceState requireInstance(GameTestHelper helper, Difficulty difficulty) {
        InstanceState[] found = new InstanceState[1];
        MiningServices.instanceManager().forEach(instance -> {
            if (found[0] == null && instance.difficulty() == difficulty) {
                found[0] = instance;
            }
        });
        if (found[0] == null) {
            helper.fail("前提: 未找到运行期 " + difficulty.configName() + " 常驻区域");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return found[0];
    }

    private static ServerLevel requireMiningLevel(GameTestHelper helper) {
        ServerLevel mining = helper.getLevel().getServer().getLevel(MiningConstants.MINING_LEVEL);
        if (mining == null) {
            helper.fail("前提: 矿区维度 " + MiningConstants.MINING_LEVEL.location() + " 必须已加载");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return mining;
    }
}
