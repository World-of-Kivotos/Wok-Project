package com.miningdim.job.agent.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionConfig;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.integration.ChampionPromoter;
import com.miningdim.champion.reward.ChampionReward;
import com.miningdim.champion.reward.ContributionPool;
import com.miningdim.champion.reward.ContributionTracker;
import com.miningdim.champion.reward.DamageContribution;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.EconomyServices;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.agent.AgentBountySavedData;
import com.miningdim.job.agent.AgentEnhancedReward;
import com.miningdim.job.agent.AgentSkillTable;
import com.miningdim.job.agent.SealCategory;
import com.miningdim.job.agent.SealRegistry;
import com.miningdim.job.agent.panel.AgentScanEntry;
import com.miningdim.job.agent.panel.AgentScanSnapshot;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 特勤 x 精英怪自研 capability 端到端 GameTest (Full_Repo_Audit_2026-08 附录 A F024/F077/F112/F016)。
 *
 * 放在 integration 包是硬约束: 本组用例直接调包私有的 {@link AgentScanProbe#buildSnapshot} 与
 * {@link AgentSealExecutor} 静态方法, 绕开 {@code AgentSealSeam} (那条接缝的桩在 {@code AgentWebUiGameTests}
 * 里全程装桩不解绑, 走接缝会受批次顺序影响)。全部断言直接命中集成层与自研冠军 capability 本身, 不经 WebUI
 * 派发器。
 *
 * 八条主线 (删掉被测那段生产逻辑必挂):
 *  1. F024 扫描: {@link AgentScanProbe#buildSnapshot} 必须读自研 {@link MiningChampions} 而非某个恒 null 的
 *     第三方桩; 生存池 (纯防御) 词条不进候选表; 技能池词条归类为 {@link SealCategory#MECHANIC}。
 *  2. F024 封印: {@link AgentSealHandler#requestSeal} 必须真从 capability 移除目标词条并置位入职标志; 重复
 *     申请同一条仍在封印窗口内的词条必须落 AFFIX_ALREADY_SEALED (而非退化成不精确的 AFFIX_NOT_SEALABLE)。
 *  3. F077 到期恢复 (本轮最关键的回归): 恢复必须按封印当刻记下的维度定位实体, 不得写死矿洞维度; 且只增量
 *     补回"被封的那几条", 不得整份覆盖吞掉窗口内已被别处消耗的一次性技能词条。
 *  4. F077 召唤物身份: 恢复流程必须保留 {@code isSummonedByAffix}, 否则支援召唤物会变成可反复召唤的发奖冠军。
 *  5. F112 召唤物整池不发: {@link AgentRewardHandler#onChampionDeath} 必须在召唤物身上短路整池, 且账本整笔
 *     作废而非参与结算。
 *  6. F016 死锁解开: 经验入账不得再共用入职标志门, 否则新号永远打不到 L3 (SEAL_UNLOCK_LEVEL) 去封印、去入职。
 *  7. F024 复核 (SPRINT/OVERDRIVE 移速常驻 modifier): 封印必须真摘除 champion.integration 侧挂在实体上的常驻
 *     MOVEMENT_SPEED {@link AttributeModifier}, 只清 capability 不够 —— 否则封印对这两条词条是纯观感 (面板回
 *     OK, 移速一格未变)。
 *  8. 方案 D4/D5 接线: {@link AgentRewardHandler} 循环里的特勤占比门槛与"按本人池份额折算"的加成数额, 经真实
 *     死亡事件端到端钉住 —— 删门槛、或把数额换回固定池/星级定额, 纯函数单测照样全绿, 只有本条会挂。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class AgentChampionIntegrationGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "agent_integration";

    // ============================================================
    // 1. F024 扫描打通: 读自研 capability + 生存池过滤 + 技能池归类
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void scanSnapshotReadsSelfHostedChampionDataAndFiltersSurvivalPool(GameTestHelper helper) {
        ServerPlayer agent = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(agent, 5);

        Zombie champion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.BURNING, AffixQuality.COMMON);            // COMBAT 池 -> PASSIVE
        affixes.put(AffixDef.ELECTRO_CHARGE, AffixQuality.COMMON);     // SKILL 池 -> MECHANIC
        affixes.put(AffixDef.COMPOSITE_ARMOR, AffixQuality.COMMON);    // SURVIVAL 池 -> 不可封, 不进候选表
        ChampionPromoter.applyChampion(champion, 5, affixes);

        AgentScanSnapshot snapshot = AgentScanProbe.buildSnapshot(agent, champion);
        helper.assertTrue(snapshot != null && snapshot.star() == 5,
                "五星盖章精英必须产出非 null 快照且 star=5 (若集成层改读某个恒返 null 的第三方桩, 本条必挂), 实得 "
                        + (snapshot == null ? "null" : snapshot.star()));

        AgentScanEntry burning = findEntry(helper, snapshot, "BURNING");
        helper.assertTrue(burning.decrypted(), "L5 干员必须解密 BURNING (被动词条 L4+ 全解密)");

        helper.assertTrue(entryAbsent(snapshot, "COMPOSITE_ARMOR"),
                "生存池 (纯防御, 旧 Champions AffixCategory.DEFENSE 等价物) 不作封印目标, 不得出现在扫描候选表里");

        AgentScanEntry electro = findEntry(helper, snapshot, "ELECTRO_CHARGE");
        helper.assertTrue(electro.category() == SealCategory.MECHANIC,
                "技能池 (主动有 CD 须预兆的读条核弹) 必须归类为机制类, 实得 " + electro.category());

        Zombie plain = helper.spawn(EntityType.ZOMBIE, new BlockPos(3, 1, 0));
        helper.assertTrue(AgentScanProbe.buildSnapshot(agent, plain) == null,
                "未盖章的普通僵尸 buildSnapshot 必须返回 null");

        helper.succeed();
    }

    // ============================================================
    // 2. F024 封印真生效: 真移除词条 + 置位入职标志 + 重复封印落在真实拒绝态
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sealRequestRemovesAffixAndMarksActiveAgent(GameTestHelper helper) {
        ServerPlayer agent = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(agent, 5);
        Zombie champion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.BURNING, AffixQuality.COMMON);
        ChampionPromoter.applyChampion(champion, 5, affixes);

        AgentSealHandler.Result sealed = AgentSealHandler.requestSeal(agent, champion, "BURNING");
        helper.assertTrue(sealed.ok(), "L5 干员对 5★ 被动词条封印必须成功, 实得 " + sealed.reason());

        helper.assertTrue(!MiningChampions.get(champion).get().has(AffixDef.BURNING),
                "封印成功后 BURNING 必须真从自研 capability 移除, 而不只是标个记号");

        helper.assertTrue(
                AgentBountySavedData.get(helper.getLevel().getServer().overworld()).isActiveAgent(agent.getUUID()),
                "封印申请成功是唯一已接线的特勤活计入口, 必须置位入职标志");

        // 重复申请: requestSeal 先查 SealRegistry.isAffixSealed (F024 复核修正, 先于 champ.has(def) 装配门判),
        // BURNING 仍在活跃封印窗口内, 必须落 AFFIX_ALREADY_SEALED —— 不能因为词条已被真移除就退化成不精确的
        // AFFIX_NOT_SEALABLE (那会让"已封印中"这一态在生产上永不可达, 见 F024 复核)。
        AgentSealHandler.Result again = AgentSealHandler.requestSeal(agent, champion, "BURNING");
        helper.assertTrue(!again.ok() && again.reason() == AgentSealHandler.FailReason.AFFIX_ALREADY_SEALED,
                "词条正封印中时重复申请必须落在 AFFIX_ALREADY_SEALED, 实得 "
                        + (again.ok() ? "ok" : again.reason()));

        helper.succeed();
    }

    // ============================================================
    // 3. F077 到期恢复: 按封印当刻维度定位实体 + 只增量补回被封词条, 不吞掉窗口内已消耗的技能词条
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void expiredSealRestoresIncrementalSnapshotWithoutReplayingConsumedAffixes(GameTestHelper helper) {
        // 全局静态账本防污染 (同范式 AgentGameTests.sealRegistryTeardownNoLeak): 本条要断言 snapshotCount()==0,
        // 先清掉本文件其它用例 (如上一条只封不撤) 遗留的快照, 保证本条只看得到自己制造的那一条。
        AgentSealExecutor.reset();

        ServerPlayer agent = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(agent, 5);
        Zombie champion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.BURNING, AffixQuality.COMMON);
        affixes.put(AffixDef.ELECTRO_CHARGE, AffixQuality.COMMON);
        ChampionPromoter.applyChampion(champion, 5, affixes);

        MiningChampionData champ = MiningChampions.get(champion).orElseThrow();
        AffixQuality burningQualityBeforeSeal = champ.quality(AffixDef.BURNING);

        // 模拟窗口内一次性技能自摘 (LITTLE_BOY 起手即摘防重触发同范式): 该词条不该被恢复流程重新装回。
        helper.assertTrue(champ.removeAffix(AffixDef.ELECTRO_CHARGE),
                "前提校验: 模拟一次性技能自摘必须真移除该词条");

        AgentSealHandler.Result sealed = AgentSealHandler.requestSeal(agent, champion, "BURNING");
        helper.assertTrue(sealed.ok(), "前提校验: 封印必须成功, 实得 " + sealed.reason());
        helper.assertTrue(!champ.has(AffixDef.BURNING), "前提校验: 封印后 BURNING 必须已被移除");

        // 等价于封印窗口到期 (SealRegistry 活跃封印立刻清空), 不必真等 100-240 tick。
        SealRegistry.discard(champion.getUUID());

        // GameTest 跑在主世界 (helper.getLevel().dimension()), 而非 MiningConstants.MINING_LEVEL —— 若恢复实现
        // 写死矿洞维度 getEntity, 在这里必定找不到实体从而丢弃快照 (F077 的真实回归点)。
        AgentSealHandler.processExpiredSeals(helper.getLevel().getServer());

        helper.assertTrue(champ.has(AffixDef.BURNING) && champ.quality(AffixDef.BURNING) == burningQualityBeforeSeal,
                "到期后 BURNING 必须按增量快照真恢复且品质与封印前一致 (写死矿洞维度或'找不到实体就 discard' "
                        + "都会使本条挂), 实得 " + (champ.has(AffixDef.BURNING) ? champ.quality(AffixDef.BURNING) : "缺失"));
        helper.assertTrue(AgentSealExecutor.snapshotCount() == 0,
                "恢复后执行侧快照必须已清, 实得 " + AgentSealExecutor.snapshotCount());
        helper.assertTrue(!champ.has(AffixDef.ELECTRO_CHARGE),
                "增量恢复只补被封的那几条; 若整份覆盖, 窗口内已被别处摘除的技能词条会被重新装回 (可重复触发漏洞)");

        AgentSealExecutor.reset();
        helper.succeed();
    }

    // ============================================================
    // 4. F077 召唤物身份不被恢复流程洗掉
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void expiredSealRestorePreservesSummonedByAffixIdentity(GameTestHelper helper) {
        ServerPlayer agent = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(agent, 5);
        Zombie champion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.BURNING, AffixQuality.COMMON);
        ChampionPromoter.applyChampion(champion, 3, affixes);

        MiningChampionData champ = MiningChampions.get(champion).orElseThrow();
        champ.markSummonedByAffix();
        helper.assertTrue(champ.isSummonedByAffix(), "前提校验: 召唤物身份必须先置位");

        AgentSealHandler.Result sealed = AgentSealHandler.requestSeal(agent, champion, "BURNING");
        helper.assertTrue(sealed.ok(), "前提校验: 封印必须成功, 实得 " + sealed.reason());

        SealRegistry.discard(champion.getUUID());
        AgentSealHandler.processExpiredSeals(helper.getLevel().getServer());

        helper.assertTrue(champ.has(AffixDef.BURNING), "前提校验: 恢复流程必须真把词条还回去");
        helper.assertTrue(champ.isSummonedByAffix(),
                "MiningChampionData.promote 的最后一行恒把 summonedByAffix 复位; 恢复流程必须先读后补盖, 否则"
                        + "被封印过的支援召唤物会变成可反复召唤的正常发奖冠军 (spec 红线 8-a)");

        helper.succeed();
    }

    // ============================================================
    // 5. F112 召唤物整池不发 (差分断言: 同星级一活一死对照)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void summonedByAffixChampionDeathGrantsNoRewardWhileSiblingDoes(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        long nowTick = helper.getLevel().getGameTime();

        Zombie normal = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        ChampionPromoter.applyChampion(normal, 3, new EnumMap<>(AffixDef.class));

        Zombie summoned = helper.spawn(EntityType.ZOMBIE, new BlockPos(3, 1, 0));
        ChampionPromoter.applyChampion(summoned, 3, new EnumMap<>(AffixDef.class));
        MiningChampions.get(summoned).orElseThrow().markSummonedByAffix();

        MiningChampionData normalData = MiningChampions.get(normal).orElseThrow();
        MiningChampionData summonedData = MiningChampions.get(summoned).orElseThrow();
        ContributionTracker.record(normalData, player.getUUID(), 60.0D, 60.0D, nowTick);
        ContributionTracker.record(summonedData, player.getUUID(), 60.0D, 60.0D, nowTick);

        DamageSource src = helper.getLevel().damageSources().generic();

        // 经真实事件总线派发, 而不是单独 new 一个 AgentRewardHandler 直调: 贡献池主结算归 ChampionRewardHandler,
        // 特勤 handler 只在其之上叠加自己那两笔 (见 AgentRewardHandler 类注释)。单独调一个 handler 只能测到半条
        // 链路 —— 那正是这两条断言此前测不出 F099 青辉石按人头复制的原因。
        long creditBeforeNormal = EconomyServices.economyService().creditBalance(player);
        MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(normal, src));
        long creditAfterNormal = EconomyServices.economyService().creditBalance(player);

        helper.assertTrue(creditAfterNormal > creditBeforeNormal,
                "单人独占贡献的普通精英死亡必须真发钱, 实得增量 " + (creditAfterNormal - creditBeforeNormal));
        helper.assertTrue(!ContributionTracker.hasLedger(normalData),
                "正常结算后账本必须被主结算清空 (防重复死亡事件二次发奖)");

        MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(summoned, src));
        long creditAfterSummoned = EconomyServices.economyService().creditBalance(player);

        helper.assertTrue(creditAfterSummoned == creditAfterNormal,
                "支援召唤物死亡必须整池不发 (一分不变), 实得增量 " + (creditAfterSummoned - creditAfterNormal));
        helper.assertTrue(!ContributionTracker.hasLedger(summonedData),
                "召唤物账本必须整笔作废清空, 不参与结算");

        helper.succeed();
    }

    /**
     * 青辉石是<b>一整池按贡献权重瓜分</b>, 不是每个合格者各发一份 (F099)。
     *
     * 这条用例补的是一个真实事故的缺口: F099 的修复只落在 {@code ChampionRewardHandler} 里, 而当时
     * {@code AgentRewardHandler} 挂 HIGHEST 抢先 drain 并按自己那份旧逻辑"每人一份"发, 于是修复在生产里
     * 从未执行过。当时全部青辉石断言都是 {@code ChampionReward.azureDrop(6)==2} 这类<b>纯数值表</b>单测,
     * 谁也没验过"经真实事件总线走完一次精英死亡之后, 全服到手的青辉石总量是几"—— 所以按人头复制发了一轮没人发现。
     *
     * 断言取"全员到手合计 == 一池", 而不是逐人份额: 份额受 round 余数归属影响, 而总量不受, 且总量正是按人头
     * 复制会翻倍的那个量 (两名合格者时 2 变 4)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void championAzurePoolIsSplitByWeightNotCopiedPerHead(GameTestHelper helper) {
        ServerPlayer heavy = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer light = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        // 6★ 是青辉石掉落的起点 (5★ 不掉)。
        Zombie champion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        ChampionPromoter.applyChampion(champion, 6, new EnumMap<>(AffixDef.class));
        double effectiveHp = MiningChampions.get(champion).orElseThrow().effectiveHp();
        helper.assertTrue(effectiveHp > 0.0D, "前提: 盖章必须写入有效血 (盖章门槛一的分母)");

        long nowTick = helper.getLevel().getGameTime();
        // 两人都远超盖章门槛 (个人有效伤害 >= 总有效血 0.5%), 权重 3:1。
        MiningChampionData championData = MiningChampions.get(champion).orElseThrow();
        ContributionTracker.record(championData, heavy.getUUID(), effectiveHp * 0.6D, effectiveHp * 0.6D, nowTick);
        ContributionTracker.record(championData, light.getUUID(), effectiveHp * 0.2D, effectiveHp * 0.2D, nowTick);

        long heavyBefore = EconomyServices.economyService().heartstoneBalance(heavy);
        long lightBefore = EconomyServices.economyService().heartstoneBalance(light);

        MinecraftForge.EVENT_BUS.post(
                new LivingDeathEvent(champion, helper.getLevel().damageSources().generic()));

        long heavyDelta = EconomyServices.economyService().heartstoneBalance(heavy) - heavyBefore;
        long lightDelta = EconomyServices.economyService().heartstoneBalance(light) - lightBefore;
        long pool = ChampionReward.azureDrop(6);

        helper.assertTrue(pool > 0L, "前提: 6star 必须掉青辉石, 实得池 " + pool);
        helper.assertTrue(heavyDelta + lightDelta == pool,
                "两名合格者到手的青辉石合计必须恰好一池 (" + pool + "), 按人头复制会得到 " + (pool * 2)
                        + "; 实得 " + heavyDelta + " + " + lightDelta + " = " + (heavyDelta + lightDelta));
        helper.assertTrue(heavyDelta >= lightDelta,
                "打得多的那位不该分得更少, 实得 " + heavyDelta + " vs " + lightDelta);
        helper.succeed();
    }

    // ============================================================
    // 6. F016 死锁解开: 经验对全体合格击杀者无条件照发, 加强奖励仍只给入职者
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unenrolledRookieGetsAgentXpButNotEnhancedRewardOnQualifiedKill(GameTestHelper helper) {
        ServerPlayer rookie = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        helper.assertTrue(
                !AgentBountySavedData.get(helper.getLevel().getServer().overworld()).isActiveAgent(rookie.getUUID()),
                "前提校验: 新号必须从未做过特勤活计");
        helper.assertTrue(JobServices.jobService().level(rookie, JobId.AGENT) == 1, "前提校验: 新号 AGENT 默认 L1");

        Zombie champion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        ChampionPromoter.applyChampion(champion, 4, new EnumMap<>(AffixDef.class));
        long nowTick = helper.getLevel().getGameTime();
        // 单人独占贡献 (payout 全归他), 让信用点增量可对 creditPoolRaw(4) 精确反推。
        ContributionTracker.record(MiningChampions.get(champion).orElseThrow(), rookie.getUUID(), 100.0D, 100.0D,
                nowTick);

        long xpBefore = JobServices.jobService().totalXp(rookie, JobId.AGENT);
        long creditBefore = EconomyServices.economyService().creditBalance(rookie);

        DamageSource src = helper.getLevel().damageSources().generic();
        // 同上: 走总线才能同时覆盖"主结算发池"与"特勤叠加"两半, 单调一个 handler 测不出职责拆分是否正确。
        MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(champion, src));

        long xpAfter = JobServices.jobService().totalXp(rookie, JobId.AGENT);
        helper.assertTrue(xpAfter > xpBefore,
                "经验必须对全体合格击杀者无条件照发 —— 若把入职门加回经验这一笔, 新号永远升不到 L3 去封印、"
                        + "去入职, 死锁重现。实得 xpBefore=" + xpBefore + " xpAfter=" + xpAfter);

        long creditAfter = EconomyServices.economyService().creditBalance(rookie);
        // 单人独占贡献时贡献池瓜分函数把整池 (无取整损耗) 全给该玩家; 新号首次入账当日毛收入 0, 2400 远小于
        // 60000 一档主闸, 衰减系数恒为 1.0 —— 故 CREDIT 增量必须精确等于整池, 不含加强奖励 (加强奖励额外走
        // AgentEnhancedReward.extraCreditRaw, 仅对已入职且过特勤占比门槛者叠发)。
        long expectedCreditRaw = ChampionReward.creditPoolRaw(4);
        helper.assertTrue(creditAfter - creditBefore == expectedCreditRaw,
                "未入职玩家的 CREDIT 增量必须恰好等于贡献池瓜分额 (不含加强奖励那一笔), 期望 " + expectedCreditRaw
                        + ", 实得 " + (creditAfter - creditBefore));

        helper.succeed();
    }

    // ============================================================
    // 7. F024 复核: 封印移速常驻词条必须真摘除 AttributeModifier, 不能只清 capability (SPRINT/OVERDRIVE)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sealingMobilityAffixStripsSteadyStateMovementModifier(GameTestHelper helper) {
        // 两条词条同归 SealCategory.PASSIVE, 封印 CD 按【干员×类别】计而非按精英 —— 用两个干员分别封两只精英,
        // 避免同一干员连续两次 PASSIVE 封印撞进自己的封印 CD (与业务无关的测试噪音)。
        ServerPlayer sprintAgent = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(sprintAgent, 5);
        ServerPlayer overdriveAgent = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(overdriveAgent, 5);

        Zombie sprintChampion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        Map<AffixDef, AffixQuality> sprintAffixes = new EnumMap<>(AffixDef.class);
        sprintAffixes.put(AffixDef.SPRINT, AffixQuality.COMMON);
        ChampionPromoter.applyChampion(sprintChampion, 5, sprintAffixes);

        Zombie overdriveChampion = helper.spawn(EntityType.ZOMBIE, new BlockPos(3, 1, 0));
        Map<AffixDef, AffixQuality> overdriveAffixes = new EnumMap<>(AffixDef.class);
        overdriveAffixes.put(AffixDef.OVERDRIVE, AffixQuality.COMMON);
        ChampionPromoter.applyChampion(overdriveChampion, 5, overdriveAffixes);

        AttributeInstance sprintSpeed = sprintChampion.getAttribute(Attributes.MOVEMENT_SPEED);
        AttributeInstance overdriveSpeed = overdriveChampion.getAttribute(Attributes.MOVEMENT_SPEED);
        helper.assertTrue(sprintSpeed != null && overdriveSpeed != null, "前提校验: 僵尸必须有 MOVEMENT_SPEED 属性");

        // 模拟 ChampionSelfEffectHandler 已跑过至少一次 tick, 两条常驻移速 modifier 均已挂上 (真服稳态) ——
        // 名字字面量 "champion_sprint"/"champion_overdrive" 与该 handler 的 ensureSprintModifier/
        // ensureOverdriveModifier 写入值同口径 (AttributeModifier#getName 公开可读, 详见 AgentSealExecutor
        // 类注释登记的名字符串桥接方案)。
        sprintSpeed.addTransientModifier(new AttributeModifier(
                UUID.randomUUID(), "champion_sprint", 0.15D, AttributeModifier.Operation.MULTIPLY_TOTAL));
        overdriveSpeed.addTransientModifier(new AttributeModifier(
                UUID.randomUUID(), "champion_overdrive", 1.30D, AttributeModifier.Operation.MULTIPLY_TOTAL));
        helper.assertTrue(
                hasModifierNamed(sprintSpeed, "champion_sprint") && hasModifierNamed(overdriveSpeed, "champion_overdrive"),
                "前提校验: 两条常驻移速 modifier 必须先挂上, 模拟真服稳态");

        AgentSealHandler.Result sealSprint = AgentSealHandler.requestSeal(sprintAgent, sprintChampion, "SPRINT");
        helper.assertTrue(sealSprint.ok(), "L5 干员对 5★ 高速移动封印必须成功, 实得 " + sealSprint.reason());
        helper.assertTrue(!hasModifierNamed(sprintSpeed, "champion_sprint"),
                "封印 SPRINT 后常驻 MOVEMENT_SPEED modifier 必须被真摘除, 否则封印是纯观感 (面板回 OK、词条真被摘,"
                        + "但精英移速一格未变; F024 复核三位复核者共同发现)");

        AgentSealHandler.Result sealOverdrive = AgentSealHandler.requestSeal(overdriveAgent, overdriveChampion, "OVERDRIVE");
        helper.assertTrue(sealOverdrive.ok(), "L5 干员对 5★ 超速移动封印必须成功, 实得 " + sealOverdrive.reason());
        helper.assertTrue(!hasModifierNamed(overdriveSpeed, "champion_overdrive"),
                "封印 OVERDRIVE 后常驻 MOVEMENT_SPEED modifier 必须被真摘除, 否则若封印发生在 SURGE 相位, 加速"
                        + "修饰会冻结在封印当刻的值直到窗口结束 (封印反而是净增益; F024 复核发现)");

        helper.succeed();
    }

    // ============================================================
    // 8. 方案 D4/D5 接线: 加成按本人池份额折算 + 蹭枪者过不了特勤占比门槛 (经真实事件总线)
    // ============================================================

    /**
     * {@link AgentRewardHandler} 循环里特勤加成的两处接线: 占比门槛 ({@code meetsAgentShareThreshold}) 与加成数额
     * (按本人池份额 {@code entry.getValue()} 折算)。两处的纯函数已在 ChampionRewardBalanceGameTests /
     * AgentGameTests 单测过, 但把门槛条件删掉、或把数额换回 fixedPoolRaw / 星级定额, 那些单测照样全绿 —— 只有走完
     * 一次真实死亡事件才钉得住。
     *
     * 放在本类而不是本组新建的 ChampionRewardBalanceGameTests: 后者在 wok-champion 模块, 不能反向依赖 job.agent
     * (入职标志、干员等级、加成公式都在这边)。
     *
     * 场景: 8★, 两名同为 L10 的已入职干员, 净伤 497 : 3。主力占 99.4%; 蹭枪者占 0.6%, 过奖池盖章门槛
     * (≥ 有效血 0.5%) 能分到池份额, 但不足 1% 不计入 N, 于是 N=1、特勤门槛 T=25%, 拿不到加成。
     * 两人都是新号, 当日毛收入从 0 起, 两笔合计远低于 60,000 一档主闸, 衰减系数 1.0, 增量可精确断言。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void agentBonusFollowsPoolShareAndLeechBelowShareThresholdGetsNone(GameTestHelper helper) {
        int star = 8;
        int agentLevel = 10;
        ServerPlayer main = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer leech = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(main, agentLevel);
        setAgentLevel(leech, agentLevel);
        AgentBountySavedData bounty = AgentBountySavedData.get(helper.getLevel().getServer().overworld());
        bounty.markActiveAgent(main.getUUID());
        bounty.markActiveAgent(leech.getUUID());
        helper.assertTrue(JobServices.jobService().level(main, JobId.AGENT) == agentLevel
                        && JobServices.jobService().level(leech, JobId.AGENT) == agentLevel,
                "前提校验: 两人都必须是 L" + agentLevel + " 干员");

        Zombie champion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        ChampionPromoter.applyChampion(champion, star, new EnumMap<>(AffixDef.class));
        MiningChampionData champ = MiningChampions.get(champion).orElseThrow();
        double effectiveHp = champ.effectiveHp();
        helper.assertTrue(effectiveHp > 0.0D, "前提: 盖章必须写入有效血 (盖章门槛一的分母)");

        long nowTick = helper.getLevel().getGameTime();
        double unit = effectiveHp / 500.0D; // 总净伤 = 有效血, 主力 497 份、蹭枪者 3 份
        ContributionTracker.record(champ, main.getUUID(), unit * 497.0D, unit * 497.0D, nowTick);
        ContributionTracker.record(champ, leech.getUUID(), unit * 3.0D, unit * 3.0D, nowTick);

        // 前提: 场景确实落在"进了奖池、但过不了特勤门槛"那一格。
        List<DamageContribution> contributions = ContributionTracker.snapshot(champ,
                playerId -> helper.getLevel().getServer().getPlayerList().getPlayer(playerId) != null);
        double leechRatio = ContributionPool.effectiveShare(leech.getUUID(), contributions);
        helper.assertTrue(leechRatio >= ContributionPool.STAMP_THRESHOLD_BOSS_HP_RATIO
                        && leechRatio < ContributionPool.AGENT_PARTICIPANT_MIN_SHARE,
                "前提: 蹭枪者占比须在 [0.5%, 1%) 之间, 实得 " + leechRatio);
        helper.assertTrue(ContributionPool.agentParticipantCount(contributions) == 1,
                "前提: 蹭枪者不足 1% 不计入 N, N 必须为 1 (门槛 T=25%)");

        // 期望池份额: 与主结算同一个 distribute、同一组参数。死亡事件后账本即被清, 故先算。
        long pool = ChampionReward.creditPoolRaw(star);
        Map<UUID, Long> shares = ContributionPool.distribute(contributions, effectiveHp, pool, nowTick,
                ChampionConfig.contributionRecencyTicks());
        long mainShare = shares.getOrDefault(main.getUUID(), 0L);
        long leechShare = shares.getOrDefault(leech.getUUID(), 0L);
        helper.assertTrue(mainShare > 0L && leechShare > 0L && mainShare + leechShare == pool,
                "前提: 两人都进奖池且份额合计恰为一池 " + pool + ", 实得 " + mainShare + " + " + leechShare);

        long mainBefore = EconomyServices.economyService().creditBalance(main);
        long leechBefore = EconomyServices.economyService().creditBalance(leech);
        MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(champion, helper.getLevel().damageSources().generic()));
        long mainDelta = EconomyServices.economyService().creditBalance(main) - mainBefore;
        long leechDelta = EconomyServices.economyService().creditBalance(leech) - leechBefore;

        long mainBonus = AgentEnhancedReward.extraCreditRaw(agentLevel, mainShare, ChampionConfig.agentBonusRate());
        helper.assertTrue(mainBonus > 0L, "前提: 主力的加成必须为正, 实得 " + mainBonus);
        helper.assertTrue(mainDelta == mainShare + mainBonus,
                "主力增量必须 = 池份额 " + mainShare + " + floor(池份额 × agentBonusRate × 等级倍率) " + mainBonus
                        + " = " + (mainShare + mainBonus) + " (数额若换回整池或星级定额会偏离), 实得 " + mainDelta);
        helper.assertTrue(leechDelta == leechShare,
                "蹭枪者 (占比 " + String.format("%.4f", leechRatio) + " < 特勤门槛 25%) 只能拿池份额 " + leechShare
                        + ", 不得有特勤加成 (门槛条件被删时会多出 "
                        + AgentEnhancedReward.extraCreditRaw(agentLevel, leechShare, ChampionConfig.agentBonusRate())
                        + "), 实得 " + leechDelta);

        // 对照值: 星 × 600 × 等级倍率。加成必须来自本人池份额, 两人的增量都不得等于 池份额 + 对照值。
        long oldFixedBonus = (long) Math.floor(star * ChampionReward.CREDIT_POOL_PER_STAR
                * AgentSkillTable.enhancedRewardMultiplier(agentLevel));
        helper.assertTrue(mainDelta != mainShare + oldFixedBonus && leechDelta != leechShare + oldFixedBonus,
                "两人的增量都不得等于 池份额 + 星 × 600 × 等级倍率 (" + oldFixedBonus + "), 实得 "
                        + mainDelta + " / " + leechDelta);

        helper.succeed();
    }

    private static boolean hasModifierNamed(AttributeInstance attr, String name) {
        for (AttributeModifier modifier : attr.getModifiers()) {
            if (name.equals(modifier.getName())) {
                return true;
            }
        }
        return false;
    }

    // ============================================================
    // 工具
    // ============================================================

    private static AgentScanEntry findEntry(GameTestHelper helper, AgentScanSnapshot snapshot, String affixId) {
        for (AgentScanEntry entry : snapshot.entries()) {
            if (affixId.equals(entry.affixId())) {
                return entry;
            }
        }
        helper.fail("扫描快照里找不到词条 " + affixId + ", 实得 " + snapshot.entries());
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static boolean entryAbsent(AgentScanSnapshot snapshot, String affixId) {
        for (AgentScanEntry entry : snapshot.entries()) {
            if (affixId.equals(entry.affixId())) {
                return false;
            }
        }
        return true;
    }

    private static void setAgentLevel(ServerPlayer player, int level) {
        MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"))
                .jobProgress(JobId.AGENT).setLevel(level);
    }
}
