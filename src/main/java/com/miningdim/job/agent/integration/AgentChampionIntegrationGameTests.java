package com.miningdim.job.agent.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.WorldBoss;
import com.miningdim.champion.integration.ChampionPromoter;
import com.miningdim.champion.reward.ChampionReward;
import com.miningdim.champion.reward.ContributionTracker;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.EconomyServices;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.agent.AgentBountySavedData;
import com.miningdim.job.agent.SealCategory;
import com.miningdim.job.agent.SealRegistry;
import com.miningdim.job.agent.panel.AgentScanEntry;
import com.miningdim.job.agent.panel.AgentScanSnapshot;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 特勤 x 精英怪自研 capability 端到端 GameTest (Full_Repo_Audit_2026-08 附录 A F024/F077/F112/F016)。
 *
 * 放在 integration 包是硬约束: 本组用例直接调包私有的 {@link AgentScanProbe#buildSnapshot} 与
 * {@link AgentSealExecutor} 静态方法, 绕开 {@code AgentSealSeam} (那条接缝的桩在 {@code AgentWebUiGameTests}
 * 里全程装桩不解绑, 走接缝会受批次顺序影响)。全部断言直接命中集成层与自研冠军 capability 本身, 不经 WebUI
 * 派发器。
 *
 * 十条主线 (删掉被测那段生产逻辑必挂):
 *  1. F024 扫描: {@link AgentScanProbe#buildSnapshot} 必须读自研 {@link MiningChampions} 而非某个恒 null 的
 *     第三方桩; 生存池 (纯防御) 词条不进候选表; 技能池词条归类为 {@link SealCategory#MECHANIC}。
 *  2. F024 封印: {@link AgentSealHandler#requestSeal} 必须真从 capability 移除目标词条并置位入职标志; 重复
 *     申请同一条仍在封印窗口内的词条必须落 AFFIX_ALREADY_SEALED (而非退化成不精确的 AFFIX_NOT_SEALABLE)。
 *  3. F077 到期恢复 (本轮最关键的回归): 恢复必须按封印当刻记下的维度定位实体, 不得写死矿洞维度; 且只增量
 *     补回"被封的那几条", 不得整份覆盖吞掉窗口内已被别处消耗的一次性技能词条。
 *  4. F077 召唤物身份: 恢复流程必须保留 {@code isSummonedByAffix}, 否则支援召唤物会变成可反复召唤的发奖冠军。
 *  5. F112 召唤物整池不发: {@link AgentRewardHandler#onChampionDeath} 必须在召唤物身上短路整池, 且账本被
 *     discard 而非结算后清空。
 *  6. F016 死锁解开: 经验入账不得再共用入职标志门, 否则新号永远打不到 L3 (SEAL_UNLOCK_LEVEL) 去封印、去入职。
 *  7. F024 复核 (SPRINT/OVERDRIVE 移速常驻 modifier): 封印必须真摘除 champion.integration 侧挂在实体上的常驻
 *     MOVEMENT_SPEED {@link AttributeModifier}, 只清 capability 不够 —— 否则封印对这两条词条是纯观感 (面板回
 *     OK, 移速一格未变)。
 *  8. 世界 BOSS 封印到期恢复: 恢复只动词条表, 不得像整份重新盖章那样洗掉世界 BOSS 标记 (击倒公告与十星弑神从此失效)
 *     或改写当前血量。
 *  9. 被封词条随精英 capability 持久化: 封印窗口内服务端重启、封完让区块卸载超过恢复宽限期, 都不得把被封词条永久
 *     剥掉 (旧版恢复源只在进程内存, 两条路径都会让精英永久削弱却仍按初始星级发全额奖励池)。重载路径用"取实体存档
 *     NBT -> discard -> 新实体 load 同一份 NBT -> addFreshEntity 入世"模拟, 与区块载入走同一条 Entity.load 反序列化
 *     capability 的路径, 入世事件经真实事件总线派发到已注册的 {@link AgentSealHandler}。
 *  10. 逐词条到期恢复: 同一只精英身上的几条封印各按各的窗口到期, 哪条的槽释放了哪条词条就回来 (到期 tick 与入世对账
 *     同一口径)。旧版要等全部封印到期才整份放回, 机制词条被被动词条的长窗口拖着不恢复, 交替封印还能让被摘词条数
 *     超过槽容量。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class AgentChampionIntegrationGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "agent_integration";
    /** {@link MiningChampionData} 的被封词条 NBT 键 (存档格式契约, 改名即旧存档里封印中的精英读不回恢复源)。 */
    private static final String SEALED_AFFIXES_KEY = "sealed_affixes";
    /**
     * 第 10 组用例进用例体前的空等 tick 数。它们把封印时刻注入到最早"此刻往前 4 秒" (见 {@link #sealAsIfAt}), 而封印账本
     * 把"没有 CD 记录"记作 tick 0, 负 tick 会被判成 CD 中 (gameTime 本不为负)。全新存档上本批次可能在开服头几 tick
     * 就跑, 先空等够一个机制窗口, 注入的时刻才不会落到 0 以前。
     */
    private static final long SEAL_TIMELINE_SETUP_TICKS = 100L;
    /** 第 10 组用例传给执行层的 tick 索引保留时长: 不是被测对象, 只需晚于用例里所有封印窗口, 让精英在用例期间一直在册。 */
    private static final long INDEX_KEEP_TICKS = 24000L;

    // ============================================================
    // 1. F024 扫描打通: 读自研 capability + 生存池过滤 + 技能池归类
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void scanSnapshotReadsSelfHostedChampionDataAndFiltersSurvivalPool(GameTestHelper helper) {
        ServerPlayer agent = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(agent, 5);

        List<Zombie> spawned = new ArrayList<>();
        try {
            Zombie champion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
            spawned.add(champion);
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
            spawned.add(plain);
            helper.assertTrue(AgentScanProbe.buildSnapshot(agent, plain) == null,
                    "未盖章的普通僵尸 buildSnapshot 必须返回 null");
        } finally {
            spawned.forEach(AgentChampionIntegrationGameTests::recycle);
        }

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
        try {
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
        } finally {
            recycle(champion);
        }

        helper.succeed();
    }

    // ============================================================
    // 3. F077 到期恢复: 按封印当刻维度定位实体 + 只增量补回被封词条, 不吞掉窗口内已消耗的技能词条
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void expiredSealRestoresIncrementalSnapshotWithoutReplayingConsumedAffixes(GameTestHelper helper) {
        // 全局静态索引防污染 (同范式 AgentGameTests.sealRegistryTeardownNoLeak): 本条要断言 trackedCount()==0,
        // 先清掉别的用例 (含别的测试类里只封不撤的) 遗留的 tick 索引, 保证本条只看得到自己制造的那一条。
        AgentSealExecutor.reset();

        ServerPlayer agent = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(agent, 5);
        Zombie champion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        try {
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
            helper.assertTrue(AgentSealExecutor.trackedCount() == 0,
                    "恢复后执行侧 tick 索引必须已清, 实得 " + AgentSealExecutor.trackedCount());
            helper.assertTrue(!champ.hasSealedAffixes(),
                    "恢复后 capability 里的被封词条登记必须已取走, 否则下次入世对账会再合并一次, 实得 " + champ.sealedAffixes());
            helper.assertTrue(!champ.has(AffixDef.ELECTRO_CHARGE),
                    "增量恢复只补被封的那几条; 若整份覆盖, 窗口内已被别处摘除的技能词条会被重新装回 (可重复触发漏洞)");
        } finally {
            recycle(champion);
        }

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
        try {
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
                    "恢复流程只能换词条表, 不得复位 summonedByAffix (改回 MiningChampionData.promote 整份重新盖章就会复位),"
                            + " 否则被封印过的支援召唤物会变成可反复召唤的正常发奖冠军 (spec 红线 8-a)");
        } finally {
            recycle(champion);
        }

        helper.succeed();
    }

    // ============================================================
    // 5. F112 召唤物整池不发 (差分断言: 同星级一活一死对照)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void summonedByAffixChampionDeathGrantsNoRewardWhileSiblingDoes(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        long nowTick = helper.getLevel().getGameTime();

        List<Zombie> spawned = new ArrayList<>();
        try {
            Zombie normal = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
            spawned.add(normal);
            ChampionPromoter.applyChampion(normal, 3, new EnumMap<>(AffixDef.class));

            Zombie summoned = helper.spawn(EntityType.ZOMBIE, new BlockPos(3, 1, 0));
            spawned.add(summoned);
            ChampionPromoter.applyChampion(summoned, 3, new EnumMap<>(AffixDef.class));
            MiningChampions.get(summoned).orElseThrow().markSummonedByAffix();

            ContributionTracker.record(normal.getUUID(), player.getUUID(), 60.0D, nowTick);
            ContributionTracker.record(summoned.getUUID(), player.getUUID(), 60.0D, nowTick);

            DamageSource src = helper.getLevel().damageSources().generic();

            // 经真实事件总线派发, 而不是单独 new 一个 AgentRewardHandler 直调: 贡献池主结算归 ChampionRewardHandler,
            // 特勤 handler 只在其之上叠加自己那两笔 (见 AgentRewardHandler 类注释)。单独调一个 handler 只能测到半条
            // 链路 —— 那正是这两条断言此前测不出 F099 青辉石按人头复制的原因。
            long creditBeforeNormal = EconomyServices.economyService().creditBalance(player);
            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(normal, src));
            long creditAfterNormal = EconomyServices.economyService().creditBalance(player);

            helper.assertTrue(creditAfterNormal > creditBeforeNormal,
                    "单人独占贡献的普通精英死亡必须真发钱, 实得增量 " + (creditAfterNormal - creditBeforeNormal));
            helper.assertTrue(!ContributionTracker.hasLedger(normal.getUUID()),
                    "正常结算后账本必须被 drain 清空");

            MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(summoned, src));
            long creditAfterSummoned = EconomyServices.economyService().creditBalance(player);

            helper.assertTrue(creditAfterSummoned == creditAfterNormal,
                    "支援召唤物死亡必须整池不发 (一分不变), 实得增量 " + (creditAfterSummoned - creditAfterNormal));
            helper.assertTrue(!ContributionTracker.hasLedger(summoned.getUUID()),
                    "召唤物账本必须被 discard 清空 (防泄漏), 而不是结算后清空");
        } finally {
            spawned.forEach(AgentChampionIntegrationGameTests::recycle);
        }

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
        try {
            ChampionPromoter.applyChampion(champion, 6, new EnumMap<>(AffixDef.class));
            double effectiveHp = MiningChampions.get(champion).orElseThrow().effectiveHp();
            helper.assertTrue(effectiveHp > 0.0D, "前提: 盖章必须写入有效血 (盖章门槛一的分母)");

            long nowTick = helper.getLevel().getGameTime();
            // 两人都远超盖章门槛 (个人有效伤害 >= 总有效血 0.5%), 权重 3:1。
            ContributionTracker.record(champion.getUUID(), heavy.getUUID(), effectiveHp * 0.6D, nowTick);
            ContributionTracker.record(champion.getUUID(), light.getUUID(), effectiveHp * 0.2D, nowTick);

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
        } finally {
            recycle(champion);
        }
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
        try {
            ChampionPromoter.applyChampion(champion, 4, new EnumMap<>(AffixDef.class));
            long nowTick = helper.getLevel().getGameTime();
            // 单人独占贡献 (payout 全归他), 让信用点增量可对 creditPoolRaw(4) 精确反推。
            ContributionTracker.record(champion.getUUID(), rookie.getUUID(), 100.0D, nowTick);

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
            // 单人独占贡献时贡献池瓜分函数把整池 (无 round 损耗) 全给该玩家; 新号首次入账当日毛收入 0, 2400 远小于
            // 60000 一档主闸, 衰减系数恒为 1.0 —— 故 CREDIT 增量必须精确等于整池, 不含加强奖励 (加强奖励额外走
            // AgentEnhancedReward.extraCreditRaw, 仅对已入职者叠发)。
            long expectedCreditRaw = ChampionReward.creditPoolRaw(4);
            helper.assertTrue(creditAfter - creditBefore == expectedCreditRaw,
                    "未入职玩家的 CREDIT 增量必须恰好等于贡献池瓜分额 (不含加强奖励那一笔), 期望 " + expectedCreditRaw
                            + ", 实得 " + (creditAfter - creditBefore));
        } finally {
            recycle(champion);
        }

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

        List<Zombie> spawned = new ArrayList<>();
        try {
            Zombie sprintChampion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
            spawned.add(sprintChampion);
            Map<AffixDef, AffixQuality> sprintAffixes = new EnumMap<>(AffixDef.class);
            sprintAffixes.put(AffixDef.SPRINT, AffixQuality.COMMON);
            ChampionPromoter.applyChampion(sprintChampion, 5, sprintAffixes);

            Zombie overdriveChampion = helper.spawn(EntityType.ZOMBIE, new BlockPos(3, 1, 0));
            spawned.add(overdriveChampion);
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
        } finally {
            spawned.forEach(AgentChampionIntegrationGameTests::recycle);
        }

        helper.succeed();
    }

    // ============================================================
    // 8. 世界 BOSS 被封印后到期恢复: 恢复只动词条表, 世界 BOSS 身份与当前血量不被改写
    // ============================================================

    /**
     * 世界 BOSS (ChampionStarAffix spec 第十章) 可以被 L8+ 干员封印被动词条 (maxSealableStar(L)=L, 8★+ 有两个槽), 而封印
     * 窗口远短于一场约 10 人的世界 BOSS 战, 所以第一次封印到期就会走恢复。恢复若经 {@link MiningChampionData#promote}
     * 整份重新盖章, promote 会把 worldBoss 复位成 false: 击倒公告、成就的击杀过滤 (combat/star_10) 与 NBT 里的
     * world_boss 键从此全部失效。恢复必须只把被封的词条放回词条表, 星级、有效血、当前血量与身份标记一律不动。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void expiredSealRestoreKeepsWorldBossIdentityAndCurrentHp(GameTestHelper helper) {
        ServerPlayer agent = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(agent, 10);
        ServerLevel level = helper.getLevel();
        Zombie boss = Objects.requireNonNull(EntityType.ZOMBIE.create(level));
        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.BURNING, AffixQuality.COMMON);
        affixes.put(AffixDef.REGEN_TISSUE, AffixQuality.LEGENDARY);
        try {
            helper.assertTrue(WorldBoss.spawn(level, boss, Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 1, 1))),
                    0.0F, 10, affixes), "前提: 10★ 世界 BOSS 应能落地");
            MiningChampionData champ = MiningChampions.get(boss).orElseThrow();
            helper.assertTrue(champ.isWorldBoss() && champ.star() == 10, "前提: 落地后应是 10★ 世界 BOSS");
            double wounded = champ.effectiveHp() * 0.4D;
            champ.setCurrentHp(wounded);

            AgentSealHandler.Result sealed = AgentSealHandler.requestSeal(agent, boss, "BURNING");
            helper.assertTrue(sealed.ok(), "前提: 10 级干员封 10★ 世界 BOSS 的被动词条应成功, 实得 " + sealed.reason());
            helper.assertTrue(!champ.has(AffixDef.BURNING), "前提: 封印后 BURNING 应已移除");

            // 等价于封印窗口到期。
            SealRegistry.discard(boss.getUUID());
            AgentSealHandler.processExpiredSeals(level.getServer());

            helper.assertTrue(champ.quality(AffixDef.BURNING) == AffixQuality.COMMON
                            && champ.quality(AffixDef.REGEN_TISSUE) == AffixQuality.LEGENDARY,
                    "前提: 恢复应把 BURNING 按原品质放回, 其余词条不动, 实得 " + champ.affixes());
            helper.assertTrue(champ.isWorldBoss() && WorldBoss.isWorldBoss(boss),
                    "封印到期恢复后仍须是世界 BOSS, 否则击倒公告与十星弑神从此失效");
            helper.assertTrue(champ.serializeNBT().getBoolean("world_boss"),
                    "世界 BOSS 标记必须仍随 NBT 写出, 否则区块重载或重启后身份丢失");
            helper.assertTrue(champ.star() == 10 && Math.abs(champ.currentHp() - wounded) < 1.0E-6,
                    "恢复只动词条表: 星级与当前血量保持封印前的值, 实得 star=" + champ.star() + " currentHp="
                            + champ.currentHp() + " (应为 " + wounded + ")");
        } finally {
            SealRegistry.discard(boss.getUUID());
            AgentSealExecutor.untrack(boss.getUUID());
            boss.discard();
        }
        helper.succeed();
    }

    // ============================================================
    // 9. 被封词条随精英 capability 持久化: 重启 / 卸载超过宽限期 / 隐藏区块都不再永久丢词条
    // ============================================================

    /** capability 本身: 被封词条登记随 NBT 往返原样带回, 按条取走即清, 重新盖章即清, 无登记不写键。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void championSealedAffixesRoundTripThroughNbt(GameTestHelper helper) {
        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.BURNING, AffixQuality.RARE);
        affixes.put(AffixDef.ELECTRO_CHARGE, AffixQuality.UNCOMMON);
        affixes.put(AffixDef.REGEN_TISSUE, AffixQuality.COMMON);
        MiningChampionData data = new MiningChampionData();
        data.promote(5, affixes, 500.0D);
        helper.assertTrue(!data.serializeNBT().contains(SEALED_AFFIXES_KEY),
                "无被封词条的冠军不得写 " + SEALED_AFFIXES_KEY + " 键 (每只冠军都挂本 capability, 防 NBT 膨胀)");

        // 与 AgentSealExecutor.sealAffix 同一对动作: 摘词条 + 登记原品质。
        data.removeAffix(AffixDef.BURNING);
        data.recordSealedAffix(AffixDef.BURNING, AffixQuality.RARE);
        data.removeAffix(AffixDef.ELECTRO_CHARGE);
        data.recordSealedAffix(AffixDef.ELECTRO_CHARGE, AffixQuality.UNCOMMON);

        CompoundTag tag = data.serializeNBT();
        helper.assertTrue(tag.contains(SEALED_AFFIXES_KEY), "封印窗口内的冠军必须把被封词条写进 NBT");
        MiningChampionData restored = new MiningChampionData();
        restored.deserializeNBT(tag);
        helper.assertTrue(restored.sealedAffixes().equals(Map.of(
                        AffixDef.BURNING, AffixQuality.RARE, AffixDef.ELECTRO_CHARGE, AffixQuality.UNCOMMON)),
                "NBT 往返必须原样带回被封词条及其原品质 (删掉 sealed_affixes 的写或读, 重启后恢复源即为空), 实得 "
                        + restored.sealedAffixes());
        helper.assertTrue(!restored.has(AffixDef.BURNING) && !restored.has(AffixDef.ELECTRO_CHARGE)
                        && restored.quality(AffixDef.REGEN_TISSUE) == AffixQuality.COMMON,
                "存盘态就是封印中态: 被封词条不在词条表里, 未封词条原样, 实得 " + restored.affixes());

        AffixQuality taken = restored.takeSealedAffix(AffixDef.BURNING);
        helper.assertTrue(taken == AffixQuality.RARE && restored.takeSealedAffix(AffixDef.BURNING) == null,
                "按条取走即清: 第一次取回原品质, 第二次必须为 null, 否则多条恢复路径会重复合并, 实得第一次 " + taken);
        helper.assertTrue(restored.sealedAffixes().equals(Map.of(AffixDef.ELECTRO_CHARGE, AffixQuality.UNCOMMON)),
                "按条取只动被取的那一条: 另一条 (窗口还没到期) 的登记必须原样留着, 实得 " + restored.sealedAffixes());
        helper.assertTrue(restored.takeSealedAffix(AffixDef.ELECTRO_CHARGE) == AffixQuality.UNCOMMON
                        && !restored.hasSealedAffixes(), "逐条取完后登记必须为空, 实得 " + restored.sealedAffixes());
        helper.assertTrue(!restored.serializeNBT().contains(SEALED_AFFIXES_KEY), "取空后再存盘不得残留 sealed_affixes 键");

        data.promote(5, affixes, 500.0D);
        helper.assertTrue(!data.hasSealedAffixes(),
                "重新盖章是一只新冠军, 旧词条表的封印登记必须清空, 否则到期会被合并进新词条表凭空多出词条");
        helper.succeed();
    }

    /** 封印窗口内服务端重启: 进程内封印账本与 tick 索引全丢, 精英重新载入时必须按原品质恢复。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sealedAffixesSurviveServerRestartAndRestoreOnRejoin(GameTestHelper helper) {
        ServerPlayer agent = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(agent, 5);
        Zombie champion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.BURNING, AffixQuality.RARE);
        affixes.put(AffixDef.ELECTRO_CHARGE, AffixQuality.UNCOMMON);
        ChampionPromoter.applyChampion(champion, 5, affixes);
        UUID championId = champion.getUUID();

        AgentSealHandler.Result sealed = AgentSealHandler.requestSeal(agent, champion, "BURNING");
        helper.assertTrue(sealed.ok(), "前提: 封印必须成功, 实得 " + sealed.reason());

        CompoundTag saved = saveAndUnload(champion);
        // 停服: 与 AgentSystem.onServerStopping 同一组清理, 进程内的封印账本与 tick 索引全部清空。
        SealRegistry.reset();
        AgentSealExecutor.reset();
        helper.assertTrue(!AgentSealExecutor.isTracked(championId), "前提: 重启后 tick 索引里已没有这只精英");

        Zombie reloaded = reload(helper, saved);
        try {
            MiningChampionData after = MiningChampions.get(reloaded).orElseThrow();
            helper.assertTrue(after.quality(AffixDef.BURNING) == AffixQuality.RARE,
                    "重启后精英入世时必须按原品质恢复 BURNING (旧版恢复源只在进程内存, 重启即永久丢词条), 实得 "
                            + after.affixes());
            helper.assertTrue(after.quality(AffixDef.ELECTRO_CHARGE) == AffixQuality.UNCOMMON && after.star() == 5,
                    "未封词条与星级原样, 实得 star=" + after.star() + " " + after.affixes());
            helper.assertTrue(!after.hasSealedAffixes(), "恢复后登记必须已取走, 实得 " + after.sealedAffixes());
        } finally {
            reloaded.discard();
        }
        helper.succeed();
    }

    /** 封完让区块卸载超过恢复宽限期: 到期 tick 只删索引, 精英重新载入时仍按 capability 恢复, 不再永久丢失。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sealIndexDroppedPastGraceStillRestoresWhenChunkReloads(GameTestHelper helper) {
        Zombie champion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.BURNING, AffixQuality.RARE);
        affixes.put(AffixDef.ELECTRO_CHARGE, AffixQuality.UNCOMMON);
        ChampionPromoter.applyChampion(champion, 5, affixes);
        MiningChampionData champ = MiningChampions.get(champion).orElseThrow();
        UUID championId = champion.getUUID();
        ServerLevel level = helper.getLevel();

        // 直接走执行层并把索引保留截止 tick 设为此刻: 等价于封印窗口早已结束、宽限期也已耗尽, 不必真推进 24000 tick
        // (改全局 gameTime 会打乱整批 GameTest 的超时计时)。SealRegistry 里没有这只精英的活跃封印, 与窗口已过一致。
        helper.assertTrue(AgentSealExecutor.sealAffix(champion, champ, AffixDef.BURNING, level.getGameTime()),
                "前提: 执行层封印必须真移除 BURNING");

        CompoundTag saved = saveAndUnload(champion); // 区块卸载: 实体随区块存盘离场, 到期 tick 再也找不到它。
        AgentSealHandler.processExpiredSeals(level.getServer());
        helper.assertTrue(!AgentSealExecutor.isTracked(championId),
                "过了宽限期仍找不到实体时只删 tick 索引 (旧版在这里连同恢复源一起丢弃, 词条从此永久消失)");

        Zombie reloaded = reload(helper, saved);
        try {
            MiningChampionData after = MiningChampions.get(reloaded).orElseThrow();
            helper.assertTrue(after.quality(AffixDef.BURNING) == AffixQuality.RARE,
                    "区块重新载入时必须按 capability 里的登记恢复 BURNING, 而不是因索引过期永久丢失, 实得 "
                            + after.affixes());
            helper.assertTrue(after.quality(AffixDef.ELECTRO_CHARGE) == AffixQuality.UNCOMMON && !after.hasSealedAffixes(),
                    "未封词条原样且登记已取走, 实得 " + after.affixes() + " / " + after.sealedAffixes());
        } finally {
            reloaded.discard();
        }
        helper.succeed();
    }

    /** 封印窗口内卸载重载 (或跨维度): 入世对账不得提前恢复, 只按新位置重新登记索引, 到期再由 tick 恢复。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rejoinInsideSealWindowKeepsSealUntilExpiry(GameTestHelper helper) {
        ServerPlayer agent = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(agent, 5);
        Zombie champion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.BURNING, AffixQuality.COMMON);
        affixes.put(AffixDef.ELECTRO_CHARGE, AffixQuality.COMMON);
        ChampionPromoter.applyChampion(champion, 5, affixes);
        UUID championId = champion.getUUID();

        AgentSealHandler.Result sealed = AgentSealHandler.requestSeal(agent, champion, "BURNING");
        helper.assertTrue(sealed.ok(), "前提: 封印必须成功, 实得 " + sealed.reason());

        CompoundTag saved = saveAndUnload(champion);
        // 旧索引作废 (跨维度后旧维度的索引同样找不到人): 入世对账必须自己把它重新登记回来。
        AgentSealExecutor.untrack(championId);
        Zombie reloaded = reload(helper, saved);
        try {
            MiningChampionData after = MiningChampions.get(reloaded).orElseThrow();
            helper.assertTrue(!after.has(AffixDef.BURNING) && after.sealedAffixes().get(AffixDef.BURNING) == AffixQuality.COMMON,
                    "窗口内重载不得提前恢复 (封印时长不因卸载重载缩短), 实得 " + after.affixes() + " / " + after.sealedAffixes());
            helper.assertTrue(AgentSealExecutor.isTracked(championId),
                    "窗口内重载必须按当前所在维度重新登记 tick 索引, 否则到期那一 tick 没人去找它");

            // 扫描面板读 capability 登记: 重载后"封印中"那一行仍在, 且仍标为封印中。
            AgentScanEntry burning = findEntry(helper, AgentScanProbe.buildSnapshot(agent, reloaded), "BURNING");
            helper.assertTrue(burning.sealed(), "重载后扫描面板仍须把 BURNING 标为封印中");

            SealRegistry.discard(championId); // 等价于封印窗口到期。
            AgentSealHandler.processExpiredSeals(helper.getLevel().getServer());
            helper.assertTrue(after.quality(AffixDef.BURNING) == AffixQuality.COMMON && !after.hasSealedAffixes()
                            && !AgentSealExecutor.isTracked(championId),
                    "到期后必须由 tick 路径恢复并清索引, 实得 " + after.affixes());
        } finally {
            SealRegistry.discard(championId);
            AgentSealExecutor.untrack(championId);
            reloaded.discard();
        }
        helper.succeed();
    }

    /** 持久化恢复路径同样只增量合并: 窗口内小男孩自摘的一次性词条, 重启恢复后不得被装回。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void persistedRestoreDoesNotReplayLittleBoyConsumedInsideWindow(GameTestHelper helper) {
        ServerPlayer agent = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(agent, 7);
        Zombie champion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.LITTLE_BOY, AffixQuality.EPIC);
        affixes.put(AffixDef.BURNING, AffixQuality.EPIC);
        ChampionPromoter.applyChampion(champion, 7, affixes);
        MiningChampionData champ = MiningChampions.get(champion).orElseThrow();

        AgentSealHandler.Result sealed = AgentSealHandler.requestSeal(agent, champion, "BURNING");
        helper.assertTrue(sealed.ok(), "前提: L7 干员封 7★ 被动词条必须成功, 实得 " + sealed.reason());
        // 窗口内小男孩起手即摘 (与 ChampionLittleBoyHandler 同一调用): 这条一次性词条已被消耗。
        helper.assertTrue(champ.removeAffix(AffixDef.LITTLE_BOY), "前提: 小男孩自摘必须真移除该词条");
        helper.assertTrue(!champ.sealedAffixes().containsKey(AffixDef.LITTLE_BOY),
                "被封词条登记只记封印摘下的那几条, 不得把别处摘除的词条记进去");

        CompoundTag saved = saveAndUnload(champion);
        SealRegistry.reset();
        AgentSealExecutor.reset();
        Zombie reloaded = reload(helper, saved);
        try {
            MiningChampionData after = MiningChampions.get(reloaded).orElseThrow();
            helper.assertTrue(after.quality(AffixDef.BURNING) == AffixQuality.EPIC,
                    "前提: 重启恢复必须把 BURNING 按原品质放回, 实得 " + after.affixes());
            helper.assertTrue(!after.has(AffixDef.LITTLE_BOY),
                    "持久化恢复只合并被封的那几条; 若整份还原, 窗口内已引爆的小男孩会被装回 (可重复触发核弹), 实得 "
                            + after.affixes());
        } finally {
            reloaded.discard(); // 7★ 带小男孩的实体不留在测试场里, 防后续 tick 起手。
        }
        helper.succeed();
    }

    /** 持久化恢复路径同样只换词条表: 支援召唤物身份不得被洗掉。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void persistedRestorePreservesSummonedByAffixIdentity(GameTestHelper helper) {
        ServerPlayer agent = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(agent, 5);
        Zombie champion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
        affixes.put(AffixDef.BURNING, AffixQuality.COMMON);
        ChampionPromoter.applyChampion(champion, 3, affixes);
        MiningChampions.get(champion).orElseThrow().markSummonedByAffix();
        UUID championId = champion.getUUID();

        AgentSealHandler.Result sealed = AgentSealHandler.requestSeal(agent, champion, "BURNING");
        helper.assertTrue(sealed.ok(), "前提: 封印必须成功, 实得 " + sealed.reason());

        CompoundTag saved = saveAndUnload(champion);
        SealRegistry.discard(championId);
        AgentSealExecutor.untrack(championId);
        Zombie reloaded = reload(helper, saved);
        try {
            MiningChampionData after = MiningChampions.get(reloaded).orElseThrow();
            helper.assertTrue(after.has(AffixDef.BURNING), "前提: 入世对账必须真把词条还回去, 实得 " + after.affixes());
            helper.assertTrue(after.isSummonedByAffix() && after.serializeNBT().getBoolean("summoned_by_affix"),
                    "持久化恢复只能换词条表, 不得复位 summonedByAffix, 否则被封印过的支援召唤物会变成正常发奖冠军"
                            + " (spec 红线 8-a)");
        } finally {
            reloaded.discard();
        }
        helper.succeed();
    }

    /**
     * 隐藏区块兜底: 精英所在区块降为隐藏但未卸载时, 到期 tick 按 UUID 查不到它, 宽限期后删了索引, 它回到可见时又
     * 不会重新发入世事件。LivingTickEvent 兜底必须在它下一次 tick 时按 capability 恢复。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void untrackedSealedChampionRestoresOnNextLivingTick(GameTestHelper helper) {
        Zombie champion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        try {
            Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
            affixes.put(AffixDef.BURNING, AffixQuality.RARE);
            ChampionPromoter.applyChampion(champion, 5, affixes);
            MiningChampionData champ = MiningChampions.get(champion).orElseThrow();

            helper.assertTrue(AgentSealExecutor.sealAffix(champion, champ, AffixDef.BURNING, helper.getLevel().getGameTime()),
                    "前提: 执行层封印必须真移除 BURNING");
            // 宽限期后索引已删 (本用例不去真造隐藏区块, 直接删索引得到同一状态); SealRegistry 无活跃封印, 窗口已过。
            AgentSealExecutor.untrack(champion.getUUID());

            AgentSealHandler handler = new AgentSealHandler();
            champion.tickCount = 1; // 非检查周期的 tick: 兜底按周期节流, 不做任何事。
            handler.onLivingTick(new LivingEvent.LivingTickEvent(champion));
            helper.assertTrue(!champ.has(AffixDef.BURNING), "非检查周期的 tick 不应触发对账 (节流), 实得 " + champ.affixes());

            champion.tickCount = 20;
            handler.onLivingTick(new LivingEvent.LivingTickEvent(champion));
            helper.assertTrue(champ.quality(AffixDef.BURNING) == AffixQuality.RARE && !champ.hasSealedAffixes(),
                    "索引已失效的被封精英在下一个检查周期 tick 时必须按 capability 恢复, 否则晾在隐藏区块里的精英会一直"
                            + "带着封印状态直到下次真正卸载重载, 实得 " + champ.affixes());
        } finally {
            recycle(champion);
        }
        helper.succeed();
    }

    // ============================================================
    // 10. 逐词条到期恢复: 两个槽各按各的窗口到期, 槽一释放那条词条就回到精英身上
    // ============================================================

    /**
     * 第四章 L9 行: 机制窗口 4 秒、被动窗口 11 秒, 8★+ 两个槽。干员 t=0 封机制词条, t=3.9 秒再压一条被动词条; 到
     * t=4 秒机制词条的窗口结束、它占的槽已释放, 词条必须在这一 tick 回到精英身上, 被动词条继续压到自己的 11 秒。
     * 旧版等该精英的全部封印都到期才整份放回: 机制词条被多压到 14.9 秒 (六章"机制类仅短暂封印"落空); 而让出来的槽
     * 照样能再封别的词条, 两人交替封印就把词条越摘越多 (九章"每精英 1 槽, 8★+ 2 槽"落空)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH,
            setupTicks = SEAL_TIMELINE_SETUP_TICKS)
    public static void twoSlotSealsRestoreEachAffixAtItsOwnExpiry(GameTestHelper helper) {
        ServerPlayer secondAgent = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setAgentLevel(secondAgent, 9);
        UUID firstAgentId = UUID.randomUUID();
        ServerLevel level = helper.getLevel();
        Zombie champion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        UUID championId = champion.getUUID();
        try {
            Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
            affixes.put(AffixDef.ELECTRO_CHARGE, AffixQuality.UNCOMMON); // 机制类
            affixes.put(AffixDef.BURNING, AffixQuality.RARE);            // 被动类
            affixes.put(AffixDef.FROST, AffixQuality.COMMON);            // 被动类, 留给第二名干员
            ChampionPromoter.applyChampion(champion, 8, affixes);
            MiningChampionData champ = MiningChampions.get(champion).orElseThrow();
            Map<AffixDef, AffixQuality> beforeSeal = new EnumMap<>(AffixDef.class);
            beforeSeal.putAll(champ.affixes());

            long now = level.getGameTime();
            sealAsIfAt(helper, champion, firstAgentId, AffixDef.ELECTRO_CHARGE, SealCategory.MECHANIC, 9, now - 4L * 20L);
            sealAsIfAt(helper, champion, firstAgentId, AffixDef.BURNING, SealCategory.PASSIVE, 9, now - 2L);
            helper.assertTrue(SealRegistry.activeSealCount(championId, now - 1L) == 2,
                    "前提: 上一 tick 两条封印还同时占着 8★ 的两个槽");
            helper.assertTrue(SealRegistry.activeSealCount(championId, now) == 1,
                    "前提: 机制词条的 4 秒窗口此刻结束, 它占的槽已释放, 只剩被动词条占着一个槽");

            AgentSealHandler.processExpiredSeals(level.getServer());

            helper.assertTrue(champ.quality(AffixDef.ELECTRO_CHARGE) == AffixQuality.UNCOMMON,
                    "机制词条的窗口一结束就必须按原品质恢复, 不得等同一只精英身上另一条封印到期 (六章: 机制类仅短暂封印),"
                            + " 实得 " + champ.affixes());
            helper.assertTrue(!champ.has(AffixDef.BURNING)
                            && champ.sealedAffixes().equals(Map.of(AffixDef.BURNING, AffixQuality.RARE)),
                    "被动词条的 11 秒窗口还没到, 不得被连带恢复, 登记里应只剩它, 实得 " + champ.affixes() + " / "
                            + champ.sealedAffixes());
            helper.assertTrue(AgentSealExecutor.isTracked(championId),
                    "还有一条封印压着, tick 索引必须保留, 否则它到期那一 tick 没人去找它");

            // 机制词条让出的槽被第二名干员拿去封另一条: 同时被摘下的词条数仍须等于槽数。
            AgentSealHandler.Result third = AgentSealHandler.requestSeal(secondAgent, champion, "FROST");
            helper.assertTrue(third.ok(), "前提: 让出来的槽应能再封一条, 实得 " + third.reason());
            helper.assertTrue(champ.sealedAffixes().keySet().equals(EnumSet.of(AffixDef.BURNING, AffixDef.FROST))
                            && champ.has(AffixDef.ELECTRO_CHARGE),
                    "8★ 只有两个封印槽, 同时被摘下的词条不得超过两条 (九章); 槽释放了词条却不恢复, 交替封印就能把词条越摘越多,"
                            + " 实得被摘 " + champ.sealedAffixes().keySet());

            SealRegistry.discard(championId); // 等价于剩下两条的窗口也都结束。
            AgentSealHandler.processExpiredSeals(level.getServer());
            helper.assertTrue(champ.affixes().equals(beforeSeal) && !champ.hasSealedAffixes()
                            && !AgentSealExecutor.isTracked(championId),
                    "全部窗口结束后词条表必须与封印前完全一致, 登记与索引清空, 实得 " + champ.affixes());
        } finally {
            SealRegistry.discard(championId);
            AgentSealExecutor.untrack(championId);
            champion.discard();
        }
        helper.succeed();
    }

    /**
     * 入世对账同一口径: 精英带着两条被封词条随区块卸载, 其间机制词条的窗口结束、被动词条的还没到。重新载入时只恢复
     * 窗口已结束的那条, 另一条留在登记里并重新登记索引, 到期再由 tick 路径恢复。旧版只要还有一条活跃封印就整份不恢复。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH,
            setupTicks = SEAL_TIMELINE_SETUP_TICKS)
    public static void rejoinRestoresOnlySealsWhoseWindowEnded(GameTestHelper helper) {
        UUID agentId = UUID.randomUUID();
        ServerLevel level = helper.getLevel();
        Zombie champion = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        UUID championId = champion.getUUID();
        try {
            Map<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
            affixes.put(AffixDef.ELECTRO_CHARGE, AffixQuality.UNCOMMON); // 机制类
            affixes.put(AffixDef.BURNING, AffixQuality.RARE);            // 被动类
            ChampionPromoter.applyChampion(champion, 8, affixes);

            long now = level.getGameTime();
            sealAsIfAt(helper, champion, agentId, AffixDef.ELECTRO_CHARGE, SealCategory.MECHANIC, 9, now - 4L * 20L);
            sealAsIfAt(helper, champion, agentId, AffixDef.BURNING, SealCategory.PASSIVE, 9, now - 2L);

            CompoundTag saved = saveAndUnload(champion);
            // 旧索引作废 (跨维度后旧维度的索引同样找不到人): 入世对账必须自己把还压着的那条重新登记回来。
            AgentSealExecutor.untrack(championId);
            champion = reload(helper, saved);

            MiningChampionData after = MiningChampions.get(champion).orElseThrow();
            helper.assertTrue(after.quality(AffixDef.ELECTRO_CHARGE) == AffixQuality.UNCOMMON,
                    "卸载期间窗口已结束的机制词条, 重新载入时必须按原品质恢复, 不得因另一条封印还活跃而整份不恢复, 实得 "
                            + after.affixes());
            helper.assertTrue(!after.has(AffixDef.BURNING)
                            && after.sealedAffixes().equals(Map.of(AffixDef.BURNING, AffixQuality.RARE)),
                    "窗口还没到的被动词条不得被提前恢复 (封印时长不因卸载重载缩短), 实得 " + after.affixes() + " / "
                            + after.sealedAffixes());
            helper.assertTrue(AgentSealExecutor.isTracked(championId),
                    "还压着的那条必须按当前所在维度重新登记 tick 索引, 否则它到期那一 tick 没人去找它");

            SealRegistry.discard(championId); // 等价于被动词条的窗口也结束。
            AgentSealHandler.processExpiredSeals(level.getServer());
            helper.assertTrue(after.quality(AffixDef.BURNING) == AffixQuality.RARE && !after.hasSealedAffixes()
                            && !AgentSealExecutor.isTracked(championId),
                    "被动词条到期后必须由 tick 路径恢复并清索引, 实得 " + after.affixes());
        } finally {
            SealRegistry.discard(championId);
            AgentSealExecutor.untrack(championId);
            champion.discard();
        }
        helper.succeed();
    }

    /**
     * 让精英处于"某干员在过去某一刻封了这条词条"之后的状态: 封印账本按那一刻占槽 (窗口从那一刻起算), 执行层照常摘
     * 词条并登记恢复源, 与 {@link AgentSealHandler#requestSeal} 当时留下的状态一致。requestSeal 只认当前 gameTime, 真等
     * 窗口走完要上百 tick, 改全局 gameTime 又会打乱整批 GameTest 的超时计时, 故直接注入封印时刻。
     */
    private static void sealAsIfAt(GameTestHelper helper, Zombie champion, UUID agentId, AffixDef def,
                                   SealCategory category, int agentLevel, long sealedAtTick) {
        MiningChampionData champ = MiningChampions.get(champion).orElseThrow();
        SealRegistry.ApplyResult applied = SealRegistry.applySeal(champion.getUUID(), agentId, def.name(), category,
                agentLevel, champ.star(), sealedAtTick);
        helper.assertTrue(applied.ok(), "前提: " + def + " 在 tick " + sealedAtTick + " 占槽必须成功, 实得 " + applied.reason());
        helper.assertTrue(AgentSealExecutor.sealAffix(champion, champ, def, applied.expiryTick() + INDEX_KEEP_TICKS),
                "前提: 执行层必须真摘下 " + def);
    }

    /** 模拟区块卸载: 取实体完整存档 NBT (含 ForgeCaps 里的冠军 capability), 再 discard 原实体。 */
    private static CompoundTag saveAndUnload(Zombie champion) {
        CompoundTag saved = champion.saveWithoutId(new CompoundTag());
        champion.discard();
        return saved;
    }

    /**
     * 模拟区块重新载入: 新建同类实体 load 存档 NBT (与区块载入同一条 Entity.load 路径, capability 在 load 内反序列化,
     * UUID 也随之还原), 再经 addFreshEntity 真实入世 —— EntityJoinLevelEvent 走真实事件总线派发。
     */
    private static Zombie reload(GameTestHelper helper, CompoundTag saved) {
        Zombie reloaded = Objects.requireNonNull(EntityType.ZOMBIE.create(helper.getLevel()));
        reloaded.load(saved);
        helper.assertTrue(helper.getLevel().addFreshEntity(reloaded), "前提: 重新载入的实体必须能入世");
        return reloaded;
    }

    /**
     * 收走用例自己造的僵尸, 放 finally。helper.spawn 出来的 Mob 常驻且带 AI, 留着会在测试大厅里游走并随 run/world
     * 存盘, 走进别的用例按数量取样的盒子。封印账本、tick 索引、贡献账本是按 UUID 记的进程级登记, discard 不发死亡
     * 事件, 不会像真死亡那样被顺带清掉, 所以一并收走 (本来就没有登记时是空操作)。
     */
    private static void recycle(Zombie zombie) {
        UUID id = zombie.getUUID();
        SealRegistry.discard(id);
        AgentSealExecutor.untrack(id);
        ContributionTracker.discard(id);
        zombie.discard();
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
