package com.miningdim.pressure;

import com.miningdim.core.Difficulty;
import com.miningdim.core.GenState;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * liveMobs 对账回归 (F030): {@link MobPressureSystem#reconcileLiveMobs} 是 liveMobs 计数唯一的
 * "世界真相" 校验点 —— 死亡事件之外的消失路径 (discard/超距 despawn/区块卸载) 都不经 onMobDeath 销账,
 * 只能靠周期对账把已不存在于世界的 UUID 从 liveMobs 里清掉, 否则计数只增不减, 最终把
 * {@link MobPressureSystem#atMobCap} 永久钉死为 true, 刷怪硬闸永久关闭。
 *
 * 合成本地 InstanceState (不经 MiningServices.instanceManager() 注册, 不污染其他 GameTest 的真实实例)。
 * MobPressureSystem 与 InstanceState 同为本包类, 测试类同包故可直接调用包内可见方法。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MobCountReconcileGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "mob_reconcile";

    private static InstanceState newLocalInstance(long instanceId) {
        RegionBox box = RegionBox.ofDefault(0, 0);
        return new InstanceState(instanceId, 1L, Difficulty.EASY, box,
                UUID.randomUUID(), false, 0L, GenState.READY);
    }

    // ============================================================
    // 用例一: 幽灵 UUID (世界中已不存在的实体) 必须被对账销账
    // 删 reconcileLiveMobs 的 level.getEntity 存活校验逻辑 -> liveMobs 不再清空 -> 本测试必挂。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void reconcileRemovesGhostUuid(GameTestHelper helper) {
        MobPressureSystem mobPressure = new MobPressureSystem();
        InstanceState instance = newLocalInstance(9001L);

        UUID ghost = UUID.randomUUID();
        instance.liveMobs().add(ghost);
        helper.assertTrue(instance.liveMobs().size() == 1,
                "对账前 liveMobs 应含 1 个幽灵 UUID, 实测 " + instance.liveMobs().size());

        mobPressure.reconcileLiveMobs(helper.getLevel(), instance);

        helper.assertTrue(instance.liveMobs().isEmpty(),
                "对账后幽灵 UUID 必须被清空, 实测残留 " + instance.liveMobs().size() + " 条");
        helper.assertTrue(instance.liveMobs().size() == 0,
                "对账后 liveMobs.size() 必须归零, 实测 " + instance.liveMobs().size());

        helper.succeed();
    }

    // ============================================================
    // 用例二: 活着的真实体必须保留; discard() 之后 (不发 LivingDeathEvent) 仍须被下一次对账销账
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void reconcileKeepsAliveEntityThenRemovesDiscarded(GameTestHelper helper) {
        MobPressureSystem mobPressure = new MobPressureSystem();
        InstanceState instance = newLocalInstance(9002L);

        Zombie zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1, 1, 1));
        UUID zombieId = zombie.getUUID();
        instance.liveMobs().add(zombieId);

        mobPressure.reconcileLiveMobs(helper.getLevel(), instance);
        helper.assertTrue(instance.liveMobs().size() == 1,
                "存活实体不应被对账清掉, 对账后 size 实测 " + instance.liveMobs().size());
        helper.assertTrue(instance.liveMobs().contains(zombieId),
                "存活实体的 UUID 必须仍在 liveMobs 内");

        // discard() 不触发 LivingDeathEvent (锁住 onMobDeath 补不到的这条根因), 但对账必须能看穿。
        zombie.discard();
        mobPressure.reconcileLiveMobs(helper.getLevel(), instance);
        helper.assertTrue(instance.liveMobs().isEmpty(),
                "discard() 后的实体二次对账必须被清账, 实测残留 " + instance.liveMobs().size() + " 条");

        helper.succeed();
    }

    // ============================================================
    // 用例三: 刷怪硬闸 (atMobCap) 必须随对账重新打开 —— F030 影响面的直接业务结果锁
    // 删对账逻辑 -> liveMobs 恒满 -> atMobCap 恒 true -> 本测试必挂。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void reconcileReopensMobCapGate(GameTestHelper helper) {
        MobPressureSystem mobPressure = new MobPressureSystem();
        InstanceState instance = newLocalInstance(9003L);

        int cap = MiningServices.config().mobMaxPerInstance();
        for (int i = 0; i < cap; i++) {
            instance.liveMobs().add(UUID.randomUUID());
        }
        int sizeBeforeReconcile = instance.liveMobs().size();
        helper.assertTrue(sizeBeforeReconcile == cap,
                "封顶前 liveMobs 应恰好塞满 mobMaxPerInstance=" + cap + ", 实测 " + sizeBeforeReconcile);
        helper.assertTrue(mobPressure.atMobCap(instance),
                "liveMobs 达到 cap=" + cap + " 时 atMobCap 必须为 true (刷怪硬闸关闭), 实测 false");

        mobPressure.reconcileLiveMobs(helper.getLevel(), instance);

        int sizeAfterReconcile = instance.liveMobs().size();
        helper.assertTrue(sizeAfterReconcile == 0,
                "对账后 (cap=" + cap + ") liveMobs.size() 必须归零, 实测 " + sizeAfterReconcile);
        helper.assertFalse(mobPressure.atMobCap(instance),
                "对账清完幽灵 UUID 后 atMobCap 必须重新为 false (刷怪硬闸重新打开), 实测 true");

        helper.succeed();
    }
}
