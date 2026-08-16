package com.miningdim.champion;

import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.champion.reward.ContributionTracker;
import com.miningdim.champion.reward.DamageContribution;
import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 6★+ 冠军『受击 -> 拦死 -> 死亡 -> 奖励发放』实体级主链回归 (F105)。现有 20 个血池/奖励用例
 * ({@link ChampionRewardBloodPoolGameTests} / {@link ChampionGameTests}) 全是纯函数单测 (直接构造
 * {@code BloodPool}/{@code DamageContribution} 值对象, 不触实体 hurt/die 事件链), 验不到"拦死之后账本里
 * 到底是几笔"。本类走真 {@link Zombie#hurt} 全链路 (经 {@link MinecraftForge#EVENT_BUS} 真实分发
 * {@code LivingHurtEvent}/{@code LivingDeathEvent}, 非直接 new 事件对象手调 handler), 顺带把
 * F101 (致命击双记) 与 F039/F040 (血池回收与持久化) 钉成可回归的实体级断言。
 *
 * 两个用例均以 star=8、空词条 (Map.of()) 盖章: 任何减伤词条都会让净伤 != 名义伤, 会污染 F101 的精确断言
 * ({@code ChampionRedlines.clampNetKeepFactor} 对空 rates 数组恒返 keep=1.0, {@code applyFlatCaps} 对
 * fortitudeCap/heavyThreshold 均 &lt;=0 时是 no-op, 故净伤 == 名义伤, 详见 ChampionBloodPoolHandler /
 * ChampionRedlines / ChampionDamageReduction 源码核实, 非猜测)。
 *
 * template = "empty", batch = "champion_chain"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionBloodPoolChainGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_chain";
    private static final double EPS = 1e-6D;

    // ============================================================
    // 致命击拦死主链: 死亡真触发 + 贡献记账恰一笔 (F101 钉子) + 血池回收
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void lethalHitCountedExactlyOnceInContributionLedger(GameTestHelper helper) {
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        MiningChampionData champ = MiningChampions.get(zombie).orElse(null);
        if (champ == null) {
            helper.fail("zombie must have champion_data capability attached (MiningChampions.onAttachCapabilities)");
            return;
        }
        // 空词条: 净伤 == 名义伤, F101 的 5500 断言不被任何减伤源污染。
        champ.promote(8, Map.of(), 2_000.0D);
        BloodPoolRegistry.install(zombie.getUUID(), 2_000.0D);

        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        DamageSource src = zombie.level().damageSources().playerAttack(player);
        UUID championId = zombie.getUUID();

        // 第 1 发 (非致死 500): 影子血池扣血 + 贡献记账首笔。invulnerableTime 前置 0 绕过 vanilla 无敌帧。
        zombie.invulnerableTime = 0;
        zombie.hurt(src, 500.0F);
        helper.assertTrue(Math.abs(BloodPoolRegistry.get(championId).currentHp() - 1_500.0D) < EPS,
                "hit1 (500 net dmg on 2000 pool, keep=1.0/no flat caps) -> pool currentHp = 1500");
        helper.assertTrue(ContributionTracker.hasLedger(championId), "hit1 opens a contribution ledger for this champion");

        // 死亡探针: HIGHEST 优先级, 早于默认优先级的 ChampionRewardHandler.onChampionDeath 抢先 drain 账本,
        // 既做"死亡真触发"的判据 (F101 安全网), 又原地取到结算前的贡献快照 (省去依赖 EconomyServices 才能验的结算路径)。
        boolean[] deathFired = {false};
        AtomicReference<List<DamageContribution>> captured = new AtomicReference<>();
        Object deathProbe = new Object() {
            @SubscribeEvent(priority = EventPriority.HIGHEST)
            public void onChampionDeathProbe(LivingDeathEvent event) {
                if (event.getEntity() == zombie) {
                    captured.set(ContributionTracker.drain(championId, id -> true));
                    deathFired[0] = true;
                }
            }
        };
        MinecraftForge.EVENT_BUS.register(deathProbe);
        try {
            // 第 2 发 (致死 5000): 拦死分支扣池到 0 + setHealth(0), 不预记贡献 (F101 修复点)。真死由外层
            // vanilla hurt() 尾部的 die() 驱动, 在本次 LivingHurtEvent 全部监听器 (含 ChampionRewardHandler.
            // onChampionHurt 的常规记账) 跑完之后才触发, 故死亡探针读到的账本必须已含这笔常规记账。
            zombie.invulnerableTime = 0;
            zombie.hurt(src, 5_000.0F);

            // (a) F101 安全网: 拦死分支若退回 victim.kill() 或漏掉 setHealth(0)->die() 链路, vanilla die() 不触发,
            //     LivingDeathEvent 不发, 本断言必挂。
            helper.assertTrue(deathFired[0], "lethal hit triggers real vanilla death (LivingDeathEvent fires)");

            List<DamageContribution> drained = captured.get();
            // (b) 同一玩家两次命中只累计成 ContributionTracker 里的一条 Accum (per-player 累加), 不是两条记录。
            helper.assertTrue(drained != null && drained.size() == 1,
                    "exactly one ledger entry for the sole attacking player, got "
                            + (drained == null ? "null" : String.valueOf(drained.size())));
            helper.assertTrue(drained.get(0).playerId().equals(player.getUUID()),
                    "the single ledger entry belongs to the mock attacking player");

            // (c) F101 钉子: 若 ChampionBloodPoolHandler 拦死分支还留着"预记贡献"那段 ContributionTracker.record,
            //     致命一击会被记两次 -> 500 + 5000(常规) + 5000(预记) = 10500, 本断言必挂。
            helper.assertTrue(Math.abs(drained.get(0).effectiveDamage() - 5_500.0D) < EPS,
                    "lethal hit counted exactly once: 500 (hit1) + 5000 (hit2) = 5500, got "
                            + drained.get(0).effectiveDamage());

            // (d) 拦死分支摘池 + onLivingDeath 回收 (双保险, 幂等): 血池权威表不留死冠军的残留条目。
            helper.assertFalse(BloodPoolRegistry.has(championId), "blood pool entry recycled after lethal death chain");
        } finally {
            // 断言失败也不留脏静态态 (照 ChampionAttackGameTests:310-312 与 ChampionGameTests:604-606 的反泄漏写法):
            // 探针必须先摘, 再清账本/血池, 顺序颠倒不影响正确性但保持与其它用例一致的书写习惯。
            MinecraftForge.EVENT_BUS.unregister(deathProbe);
            ContributionTracker.reset();
            BloodPoolRegistry.remove(championId);
        }
        helper.succeed();
    }

    // ============================================================
    // 血池落账/持久化往返 (F040) + 自然 despawn 回收 (F039)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bloodPoolSurvivesReloadAndIsRecycledOnDiscard(GameTestHelper helper) {
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        MiningChampionData champ = MiningChampions.get(zombie).orElse(null);
        if (champ == null) {
            helper.fail("zombie must have champion_data capability attached (MiningChampions.onAttachCapabilities)");
            return;
        }
        champ.promote(8, Map.of(), 2_000.0D);
        BloodPoolRegistry.install(zombie.getUUID(), 2_000.0D);
        UUID championId = zombie.getUUID();

        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        DamageSource src = zombie.level().damageSources().playerAttack(player);

        // 隔离哨兵: 与本冠军无关的另一血池条目, 用来证明 discard 回收只摘自己那条, 不牵连旁的。
        UUID sentinelId = UUID.randomUUID();
        BloodPoolRegistry.install(sentinelId, 500.0D);

        try {
            // F040 落账点: 受击非致死伤经 ChampionBloodPoolHandler.flushCurrentHp 把血池当前血写回 capability。
            // (注: zombie.hurt 全链路真事件分发也会经 ChampionRewardHandler.onChampionHurt 在 ContributionTracker
            // 开一条本冠军的贡献账本; 本用例不断言贡献, 但 finally 仍须 discard 掉它防跨 test 脏账本。)
            // 删掉该 flushCurrentHp 调用则 capability 停在 promote 时的满血 2000, 本断言必挂。
            zombie.invulnerableTime = 0;
            zombie.hurt(src, 700.0F);
            helper.assertTrue(Math.abs(MiningChampions.get(zombie).orElseThrow().currentHp() - 1_300.0D) < EPS,
                    "700 non-lethal damage on 2000 pool flushes capability currentHp to 1300 (F040 flush point)");

            // 持久化往返: currentHp/effectiveHp/star 三者必须原样带回 (F040 NBT 键)。
            CompoundTag tag = champ.serializeNBT();
            MiningChampionData restored = new MiningChampionData();
            restored.deserializeNBT(tag);
            helper.assertTrue(Math.abs(restored.currentHp() - 1_300.0D) < EPS,
                    "NBT round trip preserves currentHp = 1300 (deleting the current_hp NBT write makes this read back as effectiveHp 2000)");
            helper.assertTrue(Math.abs(restored.effectiveHp() - 2_000.0D) < EPS,
                    "NBT round trip preserves effectiveHp = 2000");
            helper.assertTrue(restored.star() == 8, "NBT round trip preserves star = 8");

            // 向后兼容: 旧存档 (本键上线前已盖章的冠军) 没有 current_hp 键, 缺键须回落满血续战, 不是 0 血
            // (0 血会让重建的血池 install 时以死态出现)。
            tag.remove("current_hp");
            MiningChampionData legacy = new MiningChampionData();
            legacy.deserializeNBT(tag);
            helper.assertTrue(Math.abs(legacy.currentHp() - 2_000.0D) < EPS,
                    "legacy save missing current_hp key falls back to full effectiveHp 2000, not 0 (deleting the "
                            + "tag.contains(current_hp) fallback branch makes this read back as 0)");

            // F039 回收: 自然 despawn 走 zombie.discard() -> RemovalReason.DISCARDED -> EntityLeaveLevelEvent
            // (确认走 vanilla PersistentEntitySectionManager.Callback.onRemove -> stopTracking -> onTrackingEnd
            // 同步触发, 非跨 tick 延迟; 且 discard 不发 LivingDeathEvent, 故只有 EntityLeaveLevelEvent 通道能回收)。
            helper.assertTrue(BloodPoolRegistry.has(championId), "pool present before discard");
            int sizeBeforeDiscard = BloodPoolRegistry.size();
            zombie.discard();
            // 删掉 ChampionBloodPoolHandler.onEntityLeaveLevel (或其内的 BloodPoolRegistry.remove) 则本断言必挂
            // (自然 despawn 不发 LivingDeathEvent, onLivingDeath 那条回收通道覆盖不到这条路径)。
            helper.assertFalse(BloodPoolRegistry.has(championId),
                    "discard (natural despawn path, fires EntityLeaveLevelEvent not LivingDeathEvent) recycles the pool (F039)");
            helper.assertTrue(BloodPoolRegistry.size() == sizeBeforeDiscard - 1,
                    "discard removes exactly this champion's pool entry (size drops by exactly 1)");
            helper.assertTrue(BloodPoolRegistry.has(sentinelId),
                    "unrelated sentinel pool is untouched by this champion's discard");
        } finally {
            BloodPoolRegistry.remove(championId);
            BloodPoolRegistry.remove(sentinelId);
            ContributionTracker.discard(championId);
        }
        helper.succeed();
    }
}
