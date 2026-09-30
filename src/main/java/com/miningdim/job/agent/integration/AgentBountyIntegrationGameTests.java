package com.miningdim.job.agent.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixPool;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.WorldBoss;
import com.miningdim.champion.integration.ChampionPromoter;
import com.miningdim.champion.reward.ContributionTracker;
import com.miningdim.core.MiningConstants;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.JobId;
import com.miningdim.job.agent.AgentBountyConfig;
import com.miningdim.job.agent.AgentBountySavedData;
import com.miningdim.job.agent.AgentBountyService;
import com.miningdim.job.agent.AgentClock;
import com.miningdim.job.agent.BountyBoard;
import com.miningdim.job.agent.BountyDefinition;
import com.miningdim.job.agent.BountyProgress;
import com.miningdim.job.agent.SealRegistry;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 悬赏 x 精英死亡结算端到端 GameTest: 走真 {@link LivingDeathEvent} 总线, 覆盖 {@link AgentRewardHandler} 里
 * "合格集 -> 悬赏推进 -> 世界 BOSS 讨伐令"那一段接线。放在 integration 包是为了直接调
 * {@link AgentSealHandler#requestSeal} (封印中的词条仍算精英的词条, 需要真封一次)。
 *
 * 悬赏板的掷取依赖随机源; 这里先用固定种子在一块独立的板上找到想要的那种悬赏, 再用同一种子、同一日戳周戳去
 * refresh 玩家存档里的那块板 —— 掷取是确定性的, 两块板逐张相同, 之后服务层再取板时同期同级不会重掷。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class AgentBountyIntegrationGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "agent_bounty_integration";

    /**
     * 干员接了一张"讨伐带战斗池词条的精英"悬赏, 自己把目标唯一那条战斗词条封掉再打死它: 悬赏必须照样完成。
     * 若只看死亡瞬间的词条表, 正确使用封印反而让悬赏不算数。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sealedAffixStillCountsForAffixBountyOnRealDeath(GameTestHelper helper) {
        ServerPlayer agent = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(agent, 10);
        long seed = seedWithFirstDailyAffixOffer(AffixPool.COMBAT, 10);
        helper.assertTrue(seed >= 0L, "前提: 应能找到首张日常是战斗池词条类悬赏的种子");
        BountyBoard board = seededBoard(agent, 10, seed);
        BountyProgress target = board.daily().get(0);
        BountyDefinition def = target.definition();
        helper.assertTrue(AgentBountyService.accept(agent, BountyDefinition.Period.DAILY, def.id()).outcome()
                == BountyBoard.AcceptOutcome.OK, "前提: 接取成功");
        helper.assertTrue(def.requiredCount() == 1, "前提: 高星日常词条类只要一只, 实得 " + def.requiredCount());

        Zombie champion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.BURNING, AffixQuality.COMMON);
        ChampionPromoter.applyChampion(champion, def.minStar(), affixes);
        MiningChampionData champ = MiningChampions.get(champion).orElseThrow();

        try {
            AgentSealHandler.Result sealed = AgentSealHandler.requestSeal(agent, champion, "BURNING");
            helper.assertTrue(sealed.ok() && !champ.has(AffixDef.BURNING),
                    "前提: 10 级干员把唯一的战斗池词条封掉, 实得 " + sealed.reason());

            ContributionTracker.record(champion.getUUID(), agent.getUUID(), champ.effectiveHp(),
                    helper.getLevel().getGameTime());
            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(champion, helper.getLevel().damageSources().generic()));

            helper.assertTrue(target.killCount() == 1 && target.claimed(),
                    "封印中的词条仍是精英本来的词条: 悬赏应完成并发奖, 实得 kills=" + target.killCount()
                            + " claimed=" + target.claimed());
        } finally {
            SealRegistry.discard(champion.getUUID());
            AgentSealExecutor.untrack(champion.getUUID());
        }
        helper.succeed();
    }

    /** 世界 BOSS 被击倒: 达入池门槛的 L8+ 已入职干员各结算一次讨伐令, 不需要接取、不占槽位。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void worldBossDeathPaysOrderToQualifiedVeteranAgents(GameTestHelper helper) {
        ServerPlayer veteran = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer rookie = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(veteran, 8);
        setAgentLevel(rookie, 5);
        AgentBountySavedData data = AgentBountySavedData.get(helper.getLevel().getServer().overworld());
        data.markActiveAgent(veteran.getUUID());
        data.markActiveAgent(rookie.getUUID());

        ServerLevel level = helper.getLevel();
        Zombie boss = Objects.requireNonNull(EntityType.ZOMBIE.create(level));
        try {
            helper.assertTrue(WorldBoss.spawn(level, boss, Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 1, 1))),
                    0.0F, 8, new EnumMap<>(AffixDef.class)), "前提: 8★ 世界 BOSS 应能落地");
            double hp = MiningChampions.get(boss).orElseThrow().effectiveHp();
            long now = level.getGameTime();
            ContributionTracker.record(boss.getUUID(), veteran.getUUID(), hp * 0.5D, now);
            ContributionTracker.record(boss.getUUID(), rookie.getUUID(), hp * 0.5D, now);
            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(boss, level.damageSources().generic()));

            BountyBoard veteranBoard = data.existingBoard(veteran.getUUID());
            helper.assertTrue(veteranBoard != null && veteranBoard.worldBossOrders() == 1,
                    "L8 已入职干员参与击倒世界 BOSS 结算讨伐令一次");
            helper.assertTrue(data.existingBoard(rookie.getUUID()) == null,
                    "L5 没有讨伐令, 也不会因此被建板");
            helper.assertTrue(data.weeklyAzureGranted(veteran.getUUID(), AgentClock.currentUtcWeekStamp())
                            == AgentBountyConfig.table().worldBossAzure(),
                    "讨伐令青辉石计入本周悬赏青辉石软上限");
        } finally {
            boss.discard();
        }
        helper.succeed();
    }

    /** 找一个种子, 使 L{level} 的一块新板上第一张日常是指定池的词条类悬赏; 找不到返回 -1。 */
    private static long seedWithFirstDailyAffixOffer(AffixPool pool, int level) {
        for (long seed = 0L; seed < 500L; seed++) {
            BountyBoard probe = new BountyBoard();
            probe.refresh(AgentClock.currentUtcDayStamp(), AgentClock.currentUtcWeekStamp(), level,
                    AgentBountyConfig.table(), RandomSource.create(seed));
            BountyDefinition first = probe.daily().get(0).definition();
            if (first.targetType() == BountyDefinition.TargetType.KILL_WITH_AFFIX_CATEGORY
                    && first.targetPool() == pool) {
                return seed;
            }
        }
        return -1L;
    }

    /** 用固定种子把玩家存档里的悬赏板掷成确定的样子 (与 {@link #seedWithFirstDailyAffixOffer} 的探针板逐张相同)。 */
    private static BountyBoard seededBoard(ServerPlayer player, int level, long seed) {
        AgentBountySavedData data = AgentBountySavedData.get(player.server.overworld());
        BountyBoard board = data.board(player.getUUID());
        board.refresh(AgentClock.currentUtcDayStamp(), AgentClock.currentUtcWeekStamp(), level,
                AgentBountyConfig.table(), RandomSource.create(seed));
        return board;
    }

    private static void setAgentLevel(ServerPlayer player, int level) {
        MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"))
                .jobProgress(JobId.AGENT).setLevel(level);
    }
}
