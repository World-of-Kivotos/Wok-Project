package com.miningdim.job.tarot;

import com.miningdim.combat.CombatConstants;
import com.miningdim.combat.PlayerDamageReduction;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.tarot.card.TarotCardData;
import com.miningdim.job.tarot.card.TarotEffectKind;
import com.miningdim.job.tarot.card.TarotEffectOp;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * fix/tarot-guards 分支 T2 配套 GameTest: 预知入帽 (F096) / 命运之轮闪耀刷新增益 (F023) / 索敌清扫降频缩半径
 * (F069/F080)。断言具体数值/状态/副作用 (删被测核心逻辑必挂), 不依赖 JobFramework capability attach。
 *
 * 批次隔离说明 (F096 用例专用, 详见 {@link #premonitionReductionClampedByGlobalCapAndConsumedOnce}):
 * {@link com.miningdim.combat.PlayerDamageReductionGameTests} 会调 {@link PlayerDamageReduction#unregisterAll()},
 * 批次内执行顺序不受本文件控制, 有可能在本文件的用例之前或之后跑, 把生产期由 {@link TarotSystem} 构造
 * {@link TarotCombatHandlers} 实例时注册的 "tarot_premonition" 源清空。故预知用例绝不假定该源已注册, 而是自己
 * unregisterAll() 后按生产同样的路径 (new {@link TarotCombatHandlers}, 其构造函数会自动登记) 重新登记, 用例结束
 * 再 unregisterAll() 复原, 使断言与批次执行顺序完全无关。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class TarotCombatGuardsGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "tarot";

    // ============================================================
    // F096: 女祭司正位预知减伤必须吃 85% 全局帽 (PLAYER_MAX_REDUCTION), 且窗口一次性消费
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void premonitionReductionClampedByGlobalCapAndConsumedOnce(GameTestHelper helper) {
        // 见类头注释: 本用例自带减伤源注册/复原, 不依赖批次执行顺序。
        PlayerDamageReduction.unregisterAll();
        ServerPlayer victim = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        UUID playerId = victim.getUUID();
        MinecraftServer server = helper.getLevel().getServer();
        DamageSource source = helper.getLevel().damageSources().generic();
        try {
            // 假源: 代表已经把某职业自身减伤单独钳到底的另一来源 (恒 0.90; 单独存在时 keep=1-0.90=0.10 < 0.15
            // 帽, 已先触顶)。
            PlayerDamageReduction.register(fixedRateSource("fake_job_reduction_90", 0.90D));
            // new 出 handler: 其构造函数会像生产 TarotSystem 一样自动登记 "tarot_premonition" 源
            // (见 TarotCombatHandlers() 构造函数), 不需要也不应该手写重复的注册逻辑。
            TarotCombatHandlers combatHandlers = new TarotCombatHandlers();
            PlayerDamageReduction reduction = newReductionHandler();

            long endTick = server.getTickCount() + 200L;
            TarotCombatState.openWindowRaw(playerId, TarotCombatState.WindowKind.PREMONITION, endTick, 0.50D, 0.0D);

            // 受害者 handler 与全局结算 handler 依次处理同一个 LivingHurtEvent (真实事件链: 消费窗口 -> 暂存
            // -> LOWEST 单点取走并入连乘)。
            float originalAmount = 100.0F;
            LivingHurtEvent firstHit = new LivingHurtEvent(victim, source, originalAmount);
            combatHandlers.onLivingHurtVictim(firstHit);
            reduction.onLivingHurt(firstHit);

            // 连乘 (1-0.90)*(1-0.50)=0.05 < 0.15 帽, 必须被钳到 PLAYER_MIN_KEEP, 而不是先各自独立结算
            // (0.90 单源先钳到 0.15, 再被预知的 0.5 在帽外二次相乘成 0.075) 那种绕开全局帽的旧行为。
            float expectedFirst = (float) (originalAmount * CombatConstants.PLAYER_MIN_KEEP);
            float regressionIfUncapped = originalAmount * 0.5F * (float) CombatConstants.PLAYER_MIN_KEEP;
            helper.assertTrue(Math.abs(firstHit.getAmount() - expectedFirst) < 1e-4F,
                    "F096: 0.90 职业减伤 x 0.50 预知连乘后必须被钳在 PLAYER_MAX_REDUCTION, 期望 amount=" + expectedFirst
                            + ", 实得 " + firstHit.getAmount() + " (回归行为会得到帽外二次相乘的 " + regressionIfUncapped + ")");

            // 一次性: 消费后窗口必须已从状态机移除。
            helper.assertFalse(TarotCombatState.hasWindow(playerId, TarotCombatState.WindowKind.PREMONITION,
                            server.getTickCount()),
                    "F096: premonition window must be removed from TarotCombatState after being consumed once");

            // 第二次受击: 换一个不会单独触顶全局帽的假源率 (0.50) 重新注册 (若仍用 0.90, 单源本身就会被钳到与首次
            // 相同的 PLAYER_MIN_KEEP, 数值撞车, 无法证明"预知只消费一次"而非"预知仍生效但恰好算出同一个数")。
            PlayerDamageReduction.unregisterAll();
            PlayerDamageReduction.register(fixedRateSource("fake_job_reduction_50", 0.50D));
            new TarotCombatHandlers(); // unregisterAll 连 tarot_premonition 一并清空, 需按生产路径重新登记。

            LivingHurtEvent secondHit = new LivingHurtEvent(victim, source, originalAmount);
            combatHandlers.onLivingHurtVictim(secondHit); // 无窗可消费 (已被首次消费), 不再 stash。
            newReductionHandler().onLivingHurt(secondHit);

            // 只剩 0.50 假源生效, 未触顶帽 (0.50 > 0.15) -> amount = original * 0.50, 与首次的 0.15 明显不同。
            float expectedSecond = (float) (originalAmount * (1.0D - 0.50D));
            helper.assertTrue(Math.abs(secondHit.getAmount() - expectedSecond) < 1e-4F,
                    "F096: 第二次受击只应受当前注册的 0.50 假源影响 (预知窗已耗尽), 期望 amount=" + expectedSecond
                            + ", 实得 " + secondHit.getAmount());
            helper.assertTrue(Math.abs(secondHit.getAmount() - firstHit.getAmount()) > 1.0F,
                    "两次受击结果必须数值不同 (首次 " + firstHit.getAmount() + ", 二次 " + secondHit.getAmount()
                            + "), 以证明预知窗真被消费一次而非巧合撞到同一个钳制值");
        } finally {
            PlayerDamageReduction.unregisterAll();
            TarotCombatState.clearAll(playerId);
        }
        helper.succeed();
    }

    /**
     * 复核追加 (finding 2/4): F096 把预知减伤从就地 setAmount 挪成暂存到 LOWEST 结算后, 同一 handler 内
     * 后续的延迟记账 / 反伤 / 队友分摊三段逻辑必须改读"预知减伤后"的量, 否则它们会静默读到未减伤的原始值,
     * 记账/反伤/分摊全部被放大。三段互不经过 LOWEST 管线 (记账走 setHealth 直接扣血, 反伤/分摊各自独立
     * hurt/setHealth), 用真实 hurt 调用观测最终数值, 不重写一份等价算法。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void premonitionReductionAppliesToLedgerReflectAndShareBookkeeping(GameTestHelper helper) {
        // 见 F096 用例同款隔离说明: 本用例自带减伤源注册/复原, 不依赖批次执行顺序。
        PlayerDamageReduction.unregisterAll();
        ServerPlayer victim = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer teammate = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        UUID victimId = victim.getUUID();
        UUID teammateId = teammate.getUUID();
        Zombie attacker = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 1));
        try {
            // new 出 handler: 其构造函数会像生产 TarotSystem 一样自动登记 "tarot_premonition" 源, 本用例不
            // 触发 LOWEST 结算 (不调 PlayerDamageReduction.onLivingHurt), 只验证 handler 自身这一半的账。
            TarotCombatHandlers combatHandlers = new TarotCombatHandlers();

            double reductionRate = 0.5D;
            TarotCombatState.openPremonition(victim, 200, reductionRate);
            double settlePercent = 1.0D; // 设 1.0 使 drainLedger 结果直接等于记账值, 隔离本次只验证记账量本身。
            TarotCombatState.openLedger(victim, 200, settlePercent, 0.0D);
            double reflectPct = 1.0D;
            double reflectCap = 1000.0D; // 足够大不触顶封顶, 单纯验证反伤基数是否用了预知减伤后的值。
            TarotCombatState.openReflect(victim, 200, reflectPct, reflectCap);
            double sharePercent = 0.5D;
            // members 集合必须含 victim 自己: TarotCombatState.damageShare(victim.getUUID(), ...) 按"受害者是否
            // 是某个分摊组的成员"查 DAMAGE_SHARES (openDamageShare 是逐 member 写入表项, 不单独给 owner 开一条),
            // 只放 teammateId 会让查victim时查无此表项, 分摊分支整段被跳过 (踩过一次, 记录在此防止再错)。
            TarotCombatState.openDamageShare(victim, java.util.Set.of(victimId, teammateId), 200, sharePercent);

            // 满血起手, 且 rawAmount 远小于 (满血-1): 延迟记账窗自己的 lethalGuard 钳制 ("这一下不能打死人",
            // 与本次复核无关的另一条既有机制) 只在 event.getAmount() 会打穿到 1 滴血以下时才生效, 若触发会
            // 提前改写 event.getAmount(), 和本用例要验证的"预知减伤后的量"互相污染, 故刻意避开。
            victim.setHealth(victim.getMaxHealth());
            float attackerHealthBefore = attacker.getHealth();
            float teammateHealthBefore = teammate.getHealth();
            float rawAmount = 8.0F;
            // "用 magic 源 (绕过攻击者护甲/抗性)" 是本类反伤分支自身的文档不变量 (类头/onLivingHurtVictim 注释),
            // 故直接比对攻击者掉血量而不必担心装甲干扰。
            LivingHurtEvent event = new LivingHurtEvent(victim,
                    helper.getLevel().damageSources().mobAttack(attacker), rawAmount);
            combatHandlers.onLivingHurtVictim(event);

            double expectedAfterPremonition = rawAmount * (1.0D - reductionRate); // = 4.0

            double[] ledgerResult = TarotCombatState.drainLedger(victimId);
            helper.assertTrue(ledgerResult != null, "延迟记账窗必须记到本次伤害");
            double expectedLedger = expectedAfterPremonition * settlePercent;
            helper.assertTrue(Math.abs(ledgerResult[0] - expectedLedger) < 1e-4D,
                    "复核 finding 2/4: 延迟记账必须按预知减伤后的量记账 (期望 " + expectedLedger
                            + ", 实得 " + ledgerResult[0] + "); 若读到未减伤原值会是 " + (rawAmount * settlePercent));

            double expectedReflected = expectedAfterPremonition * reflectPct;
            float attackerLost = attackerHealthBefore - attacker.getHealth();
            helper.assertTrue(Math.abs(attackerLost - expectedReflected) < 1e-2F,
                    "复核 finding 2/4: 反伤必须按预知减伤后的量回击攻击者 (期望掉血 " + expectedReflected
                            + ", 实得 " + attackerLost + "); 若读到未减伤原值会是 " + (rawAmount * reflectPct));

            double expectedDistributed = expectedAfterPremonition * sharePercent;
            float teammateLost = teammateHealthBefore - teammate.getHealth();
            helper.assertTrue(Math.abs(teammateLost - expectedDistributed) < 1e-2F,
                    "复核 finding 2/4: 队友分摊必须按预知减伤后的量切分 (期望掉血 " + expectedDistributed
                            + ", 实得 " + teammateLost + "); 若读到未减伤原值会是 " + (rawAmount * sharePercent));

            // 受害者留给 LOWEST 结算的剩余量必须仍是"未预知调整的 raw - distributed", 否则 reduction 会在
            // 同一次受击里被 LOWEST 的 PlayerDamageReduction 乘算两遍 (一遍在这里, 一遍在 LOWEST)。
            float expectedRemainingRaw = rawAmount - (float) expectedDistributed;
            helper.assertTrue(Math.abs(event.getAmount() - expectedRemainingRaw) < 1e-2F,
                    "受害者剩余 event.getAmount() 必须是未预知调整的 raw-distributed, 留给 LOWEST 统一乘算一次预知率"
                            + " (期望 " + expectedRemainingRaw + ", 实得 " + event.getAmount() + ")");
        } finally {
            PlayerDamageReduction.unregisterAll();
            TarotCombatState.clearAll(victimId);
            TarotCombatState.clearAll(teammateId);
            attacker.discard();
        }
        helper.succeed();
    }

    /**
     * {@link PlayerDamageReduction} 的无参构造函数是包级可见 (仅 combat 包内的 CombatSystem 挂 forgeBus 用),
     * 本类属于 tarot 包, 不能直接 new。onLivingHurt 是本测试要验证的真实单点结算逻辑本体, 不允许在测试里重新
     * 手写一份等价计算 (那样漏不到生产实现自身的回归), 故用反射打开这个构造函数, 而不改动生产代码的可见性。
     */
    private static PlayerDamageReduction newReductionHandler() {
        try {
            java.lang.reflect.Constructor<PlayerDamageReduction> ctor =
                    PlayerDamageReduction.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "failed to reflectively instantiate PlayerDamageReduction for test", e);
        }
    }

    private static PlayerDamageReduction.ReductionSource fixedRateSource(String sourceName, double rate) {
        return new PlayerDamageReduction.ReductionSource() {
            @Override
            public String name() {
                return sourceName;
            }

            @Override
            public double rate(Player victim, DamageSource source) {
                return rate;
            }
        };
    }

    // ============================================================
    // F023: 命运之轮闪耀 self_refresh_beneficial 的放大幅度来自 datapack (非引擎硬编码 4),
    // 且刷新只抬弱增益/不砍已更长时长/绝不触碰强增益 (isNonRefreshable 名单)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wheelOfFortuneShinyRefreshUsesDatapackAmplifierAndSkipsNonRefreshable(GameTestHelper helper) {
        TarotCardData wheel = TarotRuntime.cardLoader().get(TarotArcana.WHEEL_OF_FORTUNE);
        List<TarotEffectOp> shinyOps = wheel.opsFor(TarotQuality.SHINY, true);
        TarotEffectOp refreshOp = findKind(shinyOps, TarotEffectKind.SELF_REFRESH_BENEFICIAL);
        helper.assertTrue(refreshOp != null, "F023: Wheel of Fortune shiny must carry a self_refresh_beneficial op");

        // 放大幅度来自 datapack 的 maxAmplifier (当前配表 1), 不是引擎里曾经硬编码的 4; 且必须遵守 <=2
        // (Resistance III) 全局红线 (刷新会碰到玩家身上任意弱增益, 一旦配到 3+ 就会在抗性上撞线)。
        helper.assertTrue(refreshOp.amplifier() == 1,
                "F023: self_refresh_beneficial amplifier 必须来自 datapack 的 maxAmplifier (配表值 1), got "
                        + refreshOp.amplifier());
        helper.assertTrue(refreshOp.amplifier() <= 2,
                "F023: self_refresh_beneficial amplifier 必须遵守 <=2 (Resistance III) 全局红线, got "
                        + refreshOp.amplifier());

        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        // 弱增益 (再生 I, 200t 远短于 op 的 1200t): 期望被刷新抬到 op 给的上限 amplifier, 时长抬到至少 op 的 durationTicks。
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 0));
        // 已经比 op durationTicks (1200) 更长的弱增益 (抗火, 9600t): 期望时长不被砍短。
        player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 9600, 0));
        // 强增益 (抗性 I, 200t): 在 isNonRefreshable 名单内, 期望整条被跳过 (不借 remove+add 拿到续期/封顶)。
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 200, 0));

        TarotRuntime.effectEngine().applyCard(helper.getLevel(), player, wheel, TarotQuality.SHINY, true);

        MobEffectInstance regen = player.getEffect(MobEffects.REGENERATION);
        helper.assertTrue(regen != null && regen.getAmplifier() == refreshOp.amplifier(),
                "F023 不变量 1: 再生的 amplifier 必须被刷新抬到 op 给的上限 (" + refreshOp.amplifier()
                        + "), 而不是引擎硬编码的 4, got " + (regen == null ? "null" : regen.getAmplifier()));
        helper.assertTrue(regen != null && regen.getDuration() >= refreshOp.durationTicks() - 2,
                "F023 不变量 1: 再生时长必须被刷新抬到至少 op 的 durationTicks (" + refreshOp.durationTicks() + "), got "
                        + (regen == null ? "null" : regen.getDuration()));

        MobEffectInstance fireResistance = player.getEffect(MobEffects.FIRE_RESISTANCE);
        helper.assertTrue(fireResistance != null && fireResistance.getDuration() >= 9600 - 5,
                "F023 不变量 2: 已经比 op 更长的抗火剩余时长不得被砍成 op 的 durationTicks (1200), got "
                        + (fireResistance == null ? "null" : fireResistance.getDuration()));

        MobEffectInstance resistance = player.getEffect(MobEffects.DAMAGE_RESISTANCE);
        helper.assertTrue(resistance != null && resistance.getAmplifier() == 0,
                "F023 不变量 3: 抗性 (isNonRefreshable 强增益) 的 amplifier 不得借 remove+add 被刷新封顶, 仍应是 0, got "
                        + (resistance == null ? "null" : resistance.getAmplifier()));
        helper.assertTrue(resistance != null && resistance.getDuration() == 200,
                "F023 不变量 3: 抗性 (强增益) 的时长不得被刷新续期, 应仍是原始 200, got "
                        + (resistance == null ? "null" : resistance.getDuration()));
        helper.succeed();
    }

    private static TarotEffectOp findKind(List<TarotEffectOp> ops, TarotEffectKind kind) {
        for (TarotEffectOp op : ops) {
            if (op.kind() == kind) {
                return op;
            }
        }
        return null;
    }

    // ============================================================
    // F069/F080: 索敌清扫降频 (每 10 tick 采样一次) 与缩半径 (32 格, 非原 64 格)
    // ============================================================

    /**
     * 全程不手动调 {@link TarotCombatState#tick}, 只摆状态 + 推进 tick + 下一 tick 再读结果, 由生产自身已挂在
     * {@code ServerTickEvent.END} 的自动 tick ({@link TarotSystem#onServerTick}) 真实驱动清除。
     * <p>
     * 原因: GameTest 的 {@code runAfterDelay} 回调在同一真实 tick 内先于 {@code Phase.END} 执行 (回调发生于
     * level tick 阶段, 生产自动 tick 挂在其后的 END phase)。若测试在回调里也手动调一次 {@code tick()}, 会与
     * 随后触发的生产自动调用在同一个对齐 tick 内前后各清一次 —— 手动调用清一次、回调内复位目标后, 生产的
     * 自动调用紧接着在同一 tick 又清一次, 导致下一 tick 观测到的是"已经被清过"的旧结果而非验证降频/半径本身
     * (实测复现: 对齐 tick 70 内先后打印两次 tick() 调用, 复位发生在两次之间, 下一 tick 目标已提前变 null)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 60)
    public static void untargetableScanReducedFrequencyAndRadius(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        BlockPos near = new BlockPos(1, 2, 1);
        BlockPos casterPos = helper.absolutePos(near);
        player.setPos(casterPos.getX() + 0.5D, casterPos.getY(), casterPos.getZ() + 0.5D);

        Zombie close = helper.spawn(EntityType.ZOMBIE, near);
        Zombie far = helper.spawn(EntityType.ZOMBIE, near);
        // 禁用怪物自身索敌 AI (goalSelector/targetSelector 只在 isEffectiveAi() 时跑): 否则真实寻怪 AI 会在
        // runAfterDelay 推进的多个 tick 之间自行改写目标, 让本测试与被测系统的清目标行为纠缠不清。
        close.setNoAi(true);
        far.setNoAi(true);
        close.setPos(player.getX() + 10.0D, player.getY(), player.getZ());
        far.setPos(player.getX() + 40.0D, player.getY(), player.getZ());

        MinecraftServer server = helper.getLevel().getServer();
        UUID playerId = player.getUUID();
        TarotCombatState.openRestriction(player, TarotCombatState.Restriction.UNTARGETABLE, 400);

        long now0 = server.getTickCount();
        int firstAlignDelay = (int) ((10L - (now0 % 10L)) % 10L);
        if (firstAlignDelay == 0) {
            firstAlignDelay = 10; // 恰好落在对齐 tick 上时, 补一整个采样周期, 保证下面还有充分的对齐/非对齐窗口可用。
        }

        helper.runAfterDelay(firstAlignDelay, () -> {
            long alignedNow = server.getTickCount();
            helper.assertTrue(alignedNow % 10L == 0L,
                    "test setup must land on a scan-aligned tick, got " + alignedNow);
            // 摆状态; 本 tick 随后的 Phase.END 会由生产自动 tick 真实扫描一次 (对齐), 结果留到下一 tick 观测。
            close.setTarget(player);
            far.setTarget(player);

            helper.runAfterDelay(1, () -> {
                // ---- 半径断言: 32 格内清目标, 32 格外保留 (若半径改回 64, far 也会被清, 此断言必挂) ----
                helper.assertTrue(close.getTarget() == null,
                        "F069/F080: mob within the 32-block clear radius must lose target after a scan tick elapses, was "
                                + close.getTarget());
                helper.assertTrue(far.getTarget() == player,
                        "F069/F080: mob beyond the reduced clear radius (40 > 32) must keep its target, was "
                                + far.getTarget());

                // ---- 频率断言: 非采样 tick 不扫描 (若降频改回逐 tick 扫, 此断言必挂) ----
                long nonAlignedNow = server.getTickCount();
                helper.assertTrue(nonAlignedNow % 10L != 0L,
                        "second stage must land on a non-scan tick, got " + nonAlignedNow);
                // 本 tick 的自动 tick 还没跑到 Phase.END; 现在摆的目标要等它跑过之后, 到下一 tick 才能验证是否幸存。
                close.setTarget(player);

                helper.runAfterDelay(1, () -> {
                    helper.assertTrue(close.getTarget() == player,
                            "F069/F080: a non-sampled tick must NOT scan (target must survive), got "
                                    + close.getTarget());

                    // ---- 推进到下一个对齐 tick 之后: 目标才被真正清除 (中途全是非对齐 tick, 生产自动 tick
                    // 逐一跳过, 目标全程不变, 直到跨过下一个 10 的倍数才会被清) ----
                    long stillNonAligned = server.getTickCount();
                    int delayPastNextAligned = (int) ((10L - (stillNonAligned % 10L)) % 10L) + 1;
                    helper.runAfterDelay(delayPastNextAligned, () -> {
                        helper.assertTrue(close.getTarget() == null,
                                "F069/F080: the next sampled tick clears the surviving target, got "
                                        + close.getTarget());

                        close.discard();
                        far.discard();
                        TarotCombatState.clearAll(playerId);
                        helper.succeed();
                    });
                });
            });
        });
    }
}
