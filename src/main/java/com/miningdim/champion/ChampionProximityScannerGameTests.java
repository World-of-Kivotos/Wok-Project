package com.miningdim.champion;

import com.miningdim.champion.integration.ChampionProximityScanner;
import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link ChampionProximityScanner} 实体级回归 (分支复核 finding 8)。
 *
 * 本类是 16 个 handler (BossBar/Particle/Blink/CaesarSwap/...) 共同消费的近场扫描单点快照, 但落地时零测试
 * 覆盖 —— 全库既有 36 个 champion GameTest 文件均只断言 Plan/Values 纯值对象层真值表, 没有一个用例真正驱动过
 * {@link ChampionProximityScanner#sightings}(把 sightings 改成恒返 {@code List.of()} 等价于全部 16 个 handler
 * 整体失效, 此前也不会有任何用例挂)。本用例走真实体 + 真 {@link ServerPlayer} (经 {@link MockGameTestPlayers}),
 * 直接钉住 sightings 的三条契约: (1) 近场已盖章冠军入快照; (2) 同一冠军被多名玩家同时看见时快照去重成一条,
 * viewers 汇总全部看见它的玩家 (不是各生成一条); (3) 未盖章 (star=0) 的普通怪不入快照。
 *
 * template = "empty", batch = "champion_chain"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionProximityScannerGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_chain";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sightingsIncludesChampionDedupesViewersExcludesNonChampion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();

        Zombie champion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        Zombie plain = null;
        List<ServerPlayer> ownPlayers = new ArrayList<>();
        try {
            MiningChampionData champ = MiningChampions.get(champion).orElse(null);
            if (champ == null) {
                helper.fail("champion zombie must have champion_data capability attached (MiningChampions.onAttachCapabilities)");
                return;
            }
            champ.promote(6, Map.of(), 5_000.0D); // star=6, 盖章为冠军 (isChampion()=true)。

            plain = helper.spawn(EntityType.ZOMBIE, new BlockPos(3, 1, 0)); // 未盖章: 默认 star=0, 非冠军。

            ServerPlayer viewerA = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            ownPlayers.add(viewerA);
            ServerPlayer viewerB = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            ownPlayers.add(viewerB);
            // 两名玩家都挪到冠军身旁 (远小于 VIEW_RANGE=48 格), 同时也落在 plain 僵尸 (相距 3 格) 的扫描半径内 ——
            // 这正是"非冠军不入快照"断言的构造前提: 若扫描不过滤 capability, plain 也会被两名玩家的 AABB 扫入。
            // 只挪位置 (setPos), 不走 teleportTo: 本用例只关心快照的空间过滤, 不涉及任何客户端可观察的传送表现。
            double championX = champion.getX();
            double championY = champion.getY();
            double championZ = champion.getZ();
            viewerA.setPos(championX + 1.0D, championY, championZ);
            viewerB.setPos(championX - 1.0D, championY, championZ);
            // 对照玩家停在冠军正上方 100 格 (远超 48 格视距)。扫描遍历全服在线玩家, 别的用例留在网格里没下线的
            // mock 玩家也可能看见这只冠军, 所以 viewers 不按总人数断言, 只数本用例自己造的这三名里进了几名。
            ServerPlayer bystander = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            ownPlayers.add(bystander);
            bystander.setPos(championX, championY + 100.0D, championZ);

            ChampionProximityScanner.reset(); // 逼一次真扫描, 不吃跨 test 的 tick 级 memo。
            List<ChampionProximityScanner.Sighting> sightings = ChampionProximityScanner.sightings(server);

            ChampionProximityScanner.Sighting championSighting = null;
            int championSightings = 0;
            for (ChampionProximityScanner.Sighting s : sightings) {
                helper.assertTrue(!s.entity().getUUID().equals(plain.getUUID()),
                        "非冠军 (star=0) 不得出现在快照里 (快照必须经 MiningChampions capability 过滤)");
                if (s.entity().getUUID().equals(champion.getUUID())) {
                    championSighting = s;
                    championSightings++;
                }
            }

            helper.assertTrue(championSighting != null,
                    "近场已盖章冠军必须出现在快照里 (删 sightings 实现或恒返空表, 本条必挂)");
            helper.assertTrue(championSighting.data().star() == 6,
                    "快照携带的冠军数据 star 必须与 promote 一致, got " + championSighting.data().star());
            helper.assertTrue(championSightings == 1,
                    "同一冠军被两名玩家同时看见时, 快照必须去重成一条 Sighting (非各生成一条), got " + championSightings);
            int ownViewers = 0;
            for (ServerPlayer own : ownPlayers) {
                if (championSighting.viewers().contains(own)) {
                    ownViewers++;
                }
            }
            helper.assertTrue(ownViewers == 2,
                    "viewers 必须汇总身旁的两名玩家, 且不含 48 格外的对照玩家, got " + ownViewers);
            helper.assertTrue(championSighting.viewers().contains(viewerA) && championSighting.viewers().contains(viewerB),
                    "viewers 必须恰好是两名实际看见该冠军的玩家");
        } finally {
            ChampionProximityScanner.reset(); // 不留跨 test 的强实体/关卡引用 (清跨存档 memo, 见类 javadoc)。
            // 僵尸常驻且带 AI, 留着会走出本格; 挪进网格的 mock 玩家不会自己下线, 留着会成为 48 格内别的冠军的 viewer。
            champion.discard();
            if (plain != null) {
                plain.discard();
            }
            ownPlayers.forEach(MockGameTestPlayers::logout);
        }

        helper.succeed();
    }
}
