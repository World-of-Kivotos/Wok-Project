package com.miningdim.pressure;

import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.MobInstanceTag;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 实例刷怪计数的事件式记账回归 (F030 的正解, 取代原先的周期对账)。
 *
 * 锁死的不变量: <b>带实例标记且在世界里的怪 ⟺ 在 liveMobs 计数里</b>。它由两个事件维持 ——
 * {@link MobPressureSystem#onEntityJoinLevel} 登记, {@link MobPressureSystem#onEntityLeaveLevel} 销账。
 *
 * 本文件<b>刻意不自己 new MobPressureSystem 也不自己 post 事件</b>: 那样只能测到方法体, 测不到"事件到底
 * 会不会在这条消失路径上触发"。而 F030 的根因恰恰就是消失路径与事件的错配 (只接了 LivingDeathEvent, 而怪
 * 大量走 discard/despawn/区块卸载消失)。故这里走真实链路 —— 直接对世界做增删, 让 PressureSystem 启动时挂上
 * 总线的那个真实 worker 去响应, 断言真实 InstanceState 的计数变化。
 *
 * 用真实驻留实例 (非合成对象) 同理: 事件处理器要经 MiningServices.instanceManager().byId 反查实例, 合成的
 * 本地实例查不到, 测出来的绿是假绿。代价是会碰共享状态, 故每条用例都在 finally 里把自己塞进去的 UUID 摘干净。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MobCountAccountingGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "mob_accounting";

    // ============================================================
    // 用例一: 入场即计数, 离场即销账 —— 且走的是 discard (不发 LivingDeathEvent) 这条 F030 根因路径
    // ============================================================

    /**
     * 删掉 onEntityJoinLevel 的登记 -> 第一段"必须已被计数"挂;
     * 删掉 onEntityLeaveLevel 的销账 -> 第二段"discard 后必须销账"挂 (这正是 F030 的复现条件)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void taggedMobIsCountedOnJoinAndClearedOnDiscard(GameTestHelper helper) {
        InstanceState instance = requireResidentInstance(helper);
        int before = instance.liveMobs().size();
        List<UUID> mine = new ArrayList<>();
        try {
            Zombie zombie = taggedZombie(helper, instance, new BlockPos(1, 1, 1));
            UUID id = zombie.getUUID();
            mine.add(id);

            helper.assertTrue(instance.liveMobs().contains(id),
                    "带实例标记的怪一入场就该被登记进 liveMobs (入场事件), 实测未登记");
            helper.assertTrue(instance.liveMobs().size() == before + 1,
                    "计数应恰好 +1, 实测 " + (instance.liveMobs().size() - before));

            /*
             * discard() 是 F030 的根因路径: 它不发 LivingDeathEvent (苦力怕自爆、超距 despawn、区块卸载走的
             * 都是这条), 旧实现只接死亡事件所以永远减不掉这只怪。这里断言离场事件能覆盖它。
             */
            zombie.discard();
            helper.assertFalse(instance.liveMobs().contains(id),
                    "discard 不发死亡事件, 但离场事件必须把它销账 —— 实测仍留在 liveMobs 里 (F030 复现)");
            helper.assertTrue(instance.liveMobs().size() == before,
                    "销账后计数必须回到基线 " + before + ", 实测 " + instance.liveMobs().size());
            helper.succeed();
        } finally {
            instance.liveMobs().removeAll(mine);
        }
    }

    // ============================================================
    // 用例二: 没有实例标记的怪不占额度
    // ============================================================

    /**
     * 玩家牵进矿洞的动物、别的 mod 刷的怪都不带本系统标记, 一律不该占压力系统的刷怪配额 ——
     * 否则牵两只羊下矿就把自己的配额顶满了。删掉 onEntityJoinLevel 里的标记判空短路, 本条挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void untaggedMobDoesNotConsumeQuota(GameTestHelper helper) {
        InstanceState instance = requireResidentInstance(helper);
        int before = instance.liveMobs().size();
        Zombie stray = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        try {
            helper.assertFalse(instance.liveMobs().contains(stray.getUUID()),
                    "未打实例标记的怪不得进 liveMobs");
            helper.assertTrue(instance.liveMobs().size() == before,
                    "未打标记的怪落地后计数必须不变 (基线 " + before + "), 实测 " + instance.liveMobs().size());
            helper.succeed();
        } finally {
            instance.liveMobs().remove(stray.getUUID());
            stray.discard();
        }
    }

    // ============================================================
    // 用例三: 每玩家上限改为"现查周边并发存活数", 不再是"单波上限"
    // ============================================================

    /**
     * {@link MobPressureSystem#nearbyMobCount} 是每玩家上限的新判据。三件事一起锁:
     *  1. 数得出周边刚落地的带标记怪 (半径下限覆盖身后刷怪最远落点 20 格, 否则刚刷的怪数不进来);
     *  2. 怪消失后立刻少一个 —— 这是"现查世界"相对"读账本"的全部意义, 无需任何销账接线;
     *  3. 不带标记的怪不计入 (与用例二同一条判据, 但这里锁的是每玩家这一侧)。
     *
     * 把 nearbyMobCount 改回读 instance.liveMobs().size() 会让第 2 条在账本失真时静默通过, 故这里断言的是
     * 世界侧的真值差, 而不是账本值。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nearbyCountReadsWorldNotLedger(GameTestHelper helper) {
        InstanceState instance = requireResidentInstance(helper);
        MobPressureSystem worker = new MobPressureSystem();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerLevel level = helper.getLevel();
        player.moveTo(helper.absolutePos(new BlockPos(1, 1, 1)).getX() + 0.5,
                helper.absolutePos(new BlockPos(1, 1, 1)).getY(),
                helper.absolutePos(new BlockPos(1, 1, 1)).getZ() + 0.5);

        int baseline = worker.nearbyMobCount(player, level, 8);
        List<UUID> mine = new ArrayList<>();
        Zombie a = null;
        Zombie b = null;
        Zombie untagged = null;
        try {
            a = taggedZombie(helper, instance, new BlockPos(1, 1, 2));
            b = taggedZombie(helper, instance, new BlockPos(2, 1, 1));
            mine.add(a.getUUID());
            mine.add(b.getUUID());
            helper.assertTrue(worker.nearbyMobCount(player, level, 8) == baseline + 2,
                    "两只带标记的怪落在玩家身边, 现查数应为基线+2, 实测 "
                            + (worker.nearbyMobCount(player, level, 8) - baseline));

            untagged = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(2, 1, 2));
            helper.assertTrue(worker.nearbyMobCount(player, level, 8) == baseline + 2,
                    "不带标记的怪不得计入每玩家额度, 实测被算进去了");

            a.discard();
            helper.assertTrue(worker.nearbyMobCount(player, level, 8) == baseline + 1,
                    "一只消失后现查数必须立刻少一个 (世界即真相, 无需销账), 实测 "
                            + (worker.nearbyMobCount(player, level, 8) - baseline));
            helper.succeed();
        } finally {
            instance.liveMobs().removeAll(mine);
            if (a != null) {
                a.discard();
            }
            if (b != null) {
                b.discard();
            }
            if (untagged != null) {
                untagged.discard();
            }
        }
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 取一个真实驻留实例。事件处理器要经 instanceManager().byId 反查, 故必须是管理器里真有的那个,
     * 合成对象测出来是假绿。三个固定难度实例在服务器起来时就已驻留; 取不到就是装配缺陷, 直接失败不兜底。
     */
    private static InstanceState requireResidentInstance(GameTestHelper helper) {
        List<InstanceState> found = new ArrayList<>();
        MiningServices.instanceManager().forEach(found::add);
        if (found.isEmpty()) {
            helper.fail("no resident mining instance registered; pressure accounting cannot be verified");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return found.get(0);
    }

    /**
     * 造一只带实例标记的僵尸并真正加入世界。
     *
     * 标记必须在 addFreshEntity 之前打 —— 入场事件在加入世界的那一刻就触发, 晚一步标记就读不到,
     * 这只怪永远不计数。用 spawnWithNoFreeWill 拿不到"入场前"这个时机, 故手工走构造 + 加入两步。
     */
    private static Zombie taggedZombie(GameTestHelper helper, InstanceState instance, BlockPos relativePos) {
        ServerLevel level = helper.getLevel();
        Zombie zombie = EntityType.ZOMBIE.create(level);
        if (zombie == null) {
            helper.fail("EntityType.ZOMBIE.create returned null");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        BlockPos abs = helper.absolutePos(relativePos);
        zombie.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0.0f, 0.0f);
        zombie.setNoAi(true); // 与 spawnWithNoFreeWill 同口径: 不让它乱跑出取样盒
        MobInstanceTag.mark(zombie, instance.instanceId());
        level.addFreshEntity(zombie);
        return zombie;
    }

    /** 断言用: 某怪是否带标记 (锁 MobInstanceTag 的读写对称, 写进去必须读得出来)。 */
    static boolean isTagged(Mob mob) {
        return MobInstanceTag.isTagged(mob);
    }
}
