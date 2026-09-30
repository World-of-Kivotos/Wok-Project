package com.miningdim.job.agent.panel;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.agent.SealCategory;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 战术扫描面板纯逻辑 GameTest (SpecialAgent_Job_DesignSpec 五章面板 + 第四章探测词条列)。只测 champions-free 的
 * 扫描快照构建 ({@link AgentScanSnapshotBuilder}): 分级解密逐条裁决 + 可封门 (类别/星级) + 头部字段。断言具体业务
 * 结果 (哪几条解密 / 哪几条可封 / 星级门拒), 删被测核心逻辑必挂; 禁 is-not-null 弱校验。
 *
 * 真探测 (读 IChampion 真词条) 须正式服 (Champions 已加载) 验, 不在 dev 断言; 本批只测构建器对给定原料的裁决。
 * template = "empty" (纯逻辑无结构); batch = "agent" 与 {@link com.miningdim.job.agent.AgentGameTests} 同批。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class AgentScanPanelGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "agent";

    /** 构造一条可封候选原料 (集成层已过滤的可封词条; sealed=false)。 */
    private static AgentScanSnapshotBuilder.RawAffix raw(String id, SealCategory cat) {
        return new AgentScanSnapshotBuilder.RawAffix("miningdim:" + id, "affix.miningdim." + id, cat, false);
    }

    // ============================================================
    // 分级解密 (第四章探测词条列): L1-L3 前 N 条 / L4 全被动 / L5+ 含机制
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildDecryptsFirstNAtLowLevels(GameTestHelper helper) {
        // 5 条被动候选, 原始顺序固定。L1 只解密第 1 条, L2 前 2 条, L3 前 3 条 (其余加密)。
        List<AgentScanSnapshotBuilder.RawAffix> raws = List.of(
                raw("a", SealCategory.PASSIVE), raw("b", SealCategory.PASSIVE), raw("c", SealCategory.PASSIVE),
                raw("d", SealCategory.PASSIVE), raw("e", SealCategory.PASSIVE));

        AgentScanSnapshot l1 = AgentScanSnapshotBuilder.build(42, 3, 1, raws);
        helper.assertTrue(l1.entries().get(0).decrypted(), "L1 decrypts first affix");
        helper.assertTrue(!l1.entries().get(1).decrypted(), "L1 does NOT decrypt 2nd affix");
        helper.assertTrue(!l1.entries().get(4).decrypted(), "L1 does NOT decrypt 5th affix");

        AgentScanSnapshot l3 = AgentScanSnapshotBuilder.build(42, 3, 3, raws);
        helper.assertTrue(l3.entries().get(2).decrypted(), "L3 decrypts 3rd affix");
        helper.assertTrue(!l3.entries().get(3).decrypted(), "L3 does NOT decrypt 4th affix (only first 3)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildL4DecryptsAllPassiveButNotMechanic(GameTestHelper helper) {
        // 混合 3 被动 + 1 机制。L4 = 全被动解密, 机制仍加密 (机制需 L5+)。
        List<AgentScanSnapshotBuilder.RawAffix> raws = List.of(
                raw("p1", SealCategory.PASSIVE), raw("m1", SealCategory.MECHANIC),
                raw("p2", SealCategory.PASSIVE), raw("p3", SealCategory.PASSIVE));

        AgentScanSnapshot l4 = AgentScanSnapshotBuilder.build(7, 3, 4, raws);
        helper.assertTrue(l4.entries().get(0).decrypted(), "L4 decrypts passive p1");
        helper.assertTrue(l4.entries().get(2).decrypted(), "L4 decrypts passive p2 (index 2, beyond first-3 window)");
        helper.assertTrue(l4.entries().get(3).decrypted(), "L4 decrypts passive p3");
        helper.assertTrue(!l4.entries().get(1).decrypted(), "L4 does NOT decrypt mechanic m1 (needs L5+)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildL3DoesNotLeakMechanicInFirstNWindow(GameTestHelper helper) {
        // agent-03: 机制类词条若落在原始顺序前 N 位, L1-L3 也绝不解密其真名 (类别门控不被顺序击穿; 机制真名需 L5+)。
        // 构造机制词条排在第 0 位 (前 N 窗口内), 后跟被动: L3 下机制仍加密 (脱敏空显示名), 被动正常解密。
        List<AgentScanSnapshotBuilder.RawAffix> raws = List.of(
                raw("m1", SealCategory.MECHANIC), raw("p1", SealCategory.PASSIVE), raw("p2", SealCategory.PASSIVE));

        AgentScanSnapshot l3 = AgentScanSnapshotBuilder.build(7, 3, 3, raws);
        AgentScanEntry mechanic = l3.entries().get(0); // 机制, 落第 0 位 (visibleAffixCount(3)=3 的前 N 窗口内)。
        helper.assertFalse(mechanic.decrypted(),
                "L3 must NOT decrypt a mechanic affix even when it sits in the first-N window (mechanic name needs L5+)");
        helper.assertTrue(mechanic.displayKey().isEmpty(),
                "the leaked-by-order mechanic affix hides its real display key at L3 (encrypted placeholder)");
        // 同窗口内的被动词条照常解密 (门控只压机制, 不误伤被动): p1/p2 在前 N 位解密。
        helper.assertTrue(l3.entries().get(1).decrypted(), "L3 still decrypts the passive p1 in the first-N window");
        helper.assertTrue(l3.entries().get(2).decrypted(), "L3 still decrypts the passive p2 in the first-N window");
        // 删类别门控 (回到 index<visibleCount 不分类别) 则第 0 位机制在 L3 提前泄漏, 上面 mechanic 断言必挂。

        // L5+ 才解密机制真名: 同一机制词条在 L5 下解密 (含技能/机制全集)。
        AgentScanSnapshot l5 = AgentScanSnapshotBuilder.build(7, 3, 5, raws);
        helper.assertTrue(l5.entries().get(0).decrypted(), "L5 decrypts the mechanic affix (full set incl skill/mechanic)");
        helper.assertTrue(!l5.entries().get(0).displayKey().isEmpty(), "decrypted mechanic affix carries its real display key at L5");

        // L1/L2 同样不泄漏机制 (低于 L3 的前 N 窗口更小, 机制仍恒加密)。
        AgentScanSnapshot l1 = AgentScanSnapshotBuilder.build(7, 1, 1, raws);
        helper.assertFalse(l1.entries().get(0).decrypted(), "L1 does NOT decrypt the first-position mechanic affix either");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildL5DecryptsMechanicToo(GameTestHelper helper) {
        List<AgentScanSnapshotBuilder.RawAffix> raws = List.of(
                raw("p1", SealCategory.PASSIVE), raw("m1", SealCategory.MECHANIC));
        AgentScanSnapshot l5 = AgentScanSnapshotBuilder.build(7, 5, 5, raws);
        helper.assertTrue(l5.entries().get(0).decrypted(), "L5 decrypts passive");
        helper.assertTrue(l5.entries().get(1).decrypted(), "L5 decrypts mechanic (full set incl skill)");
        helper.succeed();
    }

    // ============================================================
    // 可封门 (SealPlan 三门经构建器逐条裁决): 类别解锁 + 星级门; 未解密恒不可封
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildPassiveSealableOnlyFromL3(GameTestHelper helper) {
        List<AgentScanSnapshotBuilder.RawAffix> raws = List.of(raw("p1", SealCategory.PASSIVE));
        // L1: 已解密 (前1条) 但封印 L3 才解锁 -> 不可封。
        AgentScanSnapshot l1 = AgentScanSnapshotBuilder.build(7, 1, 1, raws);
        helper.assertTrue(l1.entries().get(0).decrypted(), "L1 decrypts the single passive");
        helper.assertTrue(!l1.entries().get(0).sealable(), "L1 cannot seal passive (passive seal unlocks at L3)");
        // L3 vs 3star: 解密 + 可封。
        AgentScanSnapshot l3 = AgentScanSnapshotBuilder.build(7, 3, 3, raws);
        helper.assertTrue(l3.entries().get(0).sealable(), "L3 can seal passive on a 3star (category+star gate pass)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildStarGateRejectsTooHighStar(GameTestHelper helper) {
        List<AgentScanSnapshotBuilder.RawAffix> raws = List.of(raw("p1", SealCategory.PASSIVE));
        // L3 干员 (可封星级 = 3) 对 5星精英: 解密但星级门拒 -> 不可封。
        AgentScanSnapshot tooHigh = AgentScanSnapshotBuilder.build(7, 5, 3, raws);
        helper.assertTrue(tooHigh.entries().get(0).decrypted(), "L3 decrypts the affix");
        helper.assertTrue(!tooHigh.entries().get(0).sealable(), "L3 cannot seal a 5star (max sealable star = level = 3)");
        // L5 干员 (可封星级 = 5) 对同 5星: 可封。
        AgentScanSnapshot ok = AgentScanSnapshotBuilder.build(7, 5, 5, raws);
        helper.assertTrue(ok.entries().get(0).sealable(), "L5 can seal a 5star (max sealable star = 5)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildMechanicSealableOnlyFromL8(GameTestHelper helper) {
        List<AgentScanSnapshotBuilder.RawAffix> raws = List.of(raw("m1", SealCategory.MECHANIC));
        // L7 对 7星机制: 机制 L5+ 已解密, 但机制封印 L8 才解锁 -> 不可封。
        AgentScanSnapshot l7 = AgentScanSnapshotBuilder.build(7, 7, 7, raws);
        helper.assertTrue(l7.entries().get(0).decrypted(), "L7 decrypts mechanic affix");
        helper.assertTrue(!l7.entries().get(0).sealable(), "L7 cannot seal mechanic (mechanic seal unlocks at L8)");
        // L8 对 8星机制: 可封。
        AgentScanSnapshot l8 = AgentScanSnapshotBuilder.build(7, 8, 8, raws);
        helper.assertTrue(l8.entries().get(0).sealable(), "L8 can seal mechanic on an 8star");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildEncryptedEntriesAreNeverSealable(GameTestHelper helper) {
        // L1: 第2条加密 -> 即使是可封类别也 sealable=false (未解密不可点封)。
        List<AgentScanSnapshotBuilder.RawAffix> raws = List.of(
                raw("p1", SealCategory.PASSIVE), raw("p2", SealCategory.PASSIVE));
        // 用 L3 (封印已解锁) 但 visibleAffixCount(3)=3 会解密两条; 改用 L3 看不出加密不可封。
        // 取 L1 (前1条解密) 同时封印未解锁: 第2条既加密又封印未解锁。验"加密恒不可封" + 未解密真名空。
        AgentScanSnapshot l1 = AgentScanSnapshotBuilder.build(7, 1, 1, raws);
        AgentScanEntry encrypted = l1.entries().get(1);
        helper.assertTrue(!encrypted.decrypted(), "2nd affix is encrypted at L1");
        helper.assertTrue(!encrypted.sealable(), "encrypted affix is never sealable");
        helper.assertTrue(encrypted.displayKey().isEmpty(), "encrypted affix hides real display key (empty)");
        // F081 脱敏下沉: affixId 与 displayKey 同口径一起清空, 不是只脱 displayKey 留 affixId 明码
        // (真名本不该进入下行对象; 见 AgentScanSnapshotBuilder 类注释)。删掉构建层这道脱敏, 本条必挂。
        helper.assertTrue(encrypted.affixId().isEmpty(), "encrypted affix also hides its real affixId (empty)");
        // 已解密条目仍保留真显示名 (供客户端渲染)。
        helper.assertTrue(!l1.entries().get(0).displayKey().isEmpty(), "decrypted affix carries real display key");
        helper.succeed();
    }

    /**
     * F081 回归网的另一半: 脱敏不得误伤已解密行 —— 已解密条目的 affixId 必须逐字等于喂进来的原始 affixId,
     * 不是被清空的空串, 也不是别的什么值。若构建层把脱敏条件写反 (对 decrypted 条目也清空), 本条必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildDecryptedEntryKeepsOriginalAffixId(GameTestHelper helper) {
        List<AgentScanSnapshotBuilder.RawAffix> raws = List.of(raw("p1", SealCategory.PASSIVE));
        AgentScanSnapshot l1 = AgentScanSnapshotBuilder.build(7, 1, 1, raws);
        AgentScanEntry decrypted = l1.entries().get(0);
        helper.assertTrue(decrypted.decrypted(), "L1 decrypts the single (first-position) affix");
        helper.assertTrue("miningdim:p1".equals(decrypted.affixId()),
                "decrypted entry's affixId must be verbatim the raw affixId (miningdim:p1), 实得 "
                        + decrypted.affixId());
        helper.succeed();
    }

    // ============================================================
    // 快照头部 + sealed 标注透传
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildCarriesHeaderAndSealedFlag(GameTestHelper helper) {
        // sealed=true 的原料 -> 条目 sealed 透传 (集成层先查 SealRegistry 活跃账本)。
        AgentScanSnapshotBuilder.RawAffix sealedRaw =
                new AgentScanSnapshotBuilder.RawAffix("miningdim:p1", "affix.miningdim.p1", SealCategory.PASSIVE, true);
        AgentScanSnapshot s = AgentScanSnapshotBuilder.build(99, 4, 3, List.of(sealedRaw));
        helper.assertTrue(s.targetNetworkId() == 99, "snapshot carries target network id");
        helper.assertTrue(s.star() == 4, "snapshot carries star");
        helper.assertTrue(s.agentLevel() == 3, "snapshot carries agent level");
        helper.assertTrue(s.entries().get(0).sealed(), "sealed flag is passed through from raw to entry");
        helper.succeed();
    }

    /**
     * F024 复核修正: 已封印中的词条即使类别/星级门都通过, sealable 也必须为 false —— 否则面板给玩家一个
     * "可点"的假象, 点下去只会拿到 AFFIX_NOT_SEALABLE/AFFIX_ALREADY_SEALED 而不是更准确的"已被封印中"。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildSealedEntryIsNeverSealableEvenWhenGatesPass(GameTestHelper helper) {
        // L3 对 3star 被动 (buildPassiveSealableOnlyFromL3 已验证该组合类别/星级门全通过) 但当前正被封印中。
        AgentScanSnapshotBuilder.RawAffix sealedRaw =
                new AgentScanSnapshotBuilder.RawAffix("miningdim:p1", "affix.miningdim.p1", SealCategory.PASSIVE, true);
        AgentScanSnapshot s = AgentScanSnapshotBuilder.build(7, 3, 3, List.of(sealedRaw));
        AgentScanEntry entry = s.entries().get(0);
        helper.assertTrue(entry.decrypted(), "前提校验: L3 对 3star 该条目必须已解密");
        helper.assertTrue(entry.sealed(), "前提校验: sealed 标注必须透传");
        helper.assertTrue(!entry.sealable(),
                "已封印中的词条即使类别/星级门都通过, sealable 也必须为 false (删掉 build() 里的 !raw.sealed() "
                        + "短路必挂), 实得 " + entry.sealable());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildEmptyAffixListYieldsEmptySnapshot(GameTestHelper helper) {
        // 无可封候选词条 (集成层全过滤掉): 构建器返空条目快照, 不抛, 头部仍带。
        AgentScanSnapshot s = AgentScanSnapshotBuilder.build(5, 2, 6, List.of());
        helper.assertTrue(s.entries().isEmpty(), "no candidate affixes yields empty entry list");
        helper.assertTrue(s.targetNetworkId() == 5, "empty snapshot still carries header (target id)");
        helper.succeed();
    }

    // ============================================================
    // 数值情报分级 (第四章探测列 L3-L10): 每格恰在自己那一级解锁, 早一级是 null
    // ============================================================

    /** 带品质的一条原料 (L8 品质格用)。 */
    private static AgentScanSnapshotBuilder.RawAffix rawWithQuality(String id, SealCategory cat, String quality) {
        return new AgentScanSnapshotBuilder.RawAffix("miningdim:" + id, "affix.miningdim." + id, cat, false, quality);
    }

    /** 一被动一机制两条候选 (L5 起两条都解密), 机制行 m1 带时序。 */
    private static final List<AgentScanSnapshotBuilder.RawAffix> INTEL_RAWS = List.of(
            rawWithQuality("p1", SealCategory.PASSIVE, "RARE"),
            rawWithQuality("m1", SealCategory.MECHANIC, "EPIC"));

    /**
     * 数值原料: 每格取一个不会与 0 / 默认值混淆的真值。时序表里另塞一条<b>不在词条行里</b>的 ghost 机制, 用来验
     * 构建层只放行已解密机制行对应的时序 (不能靠时序表旁路漏出词条身份)。
     */
    private static AgentScanSnapshotBuilder.RawStats intelStats() {
        return new AgentScanSnapshotBuilder.RawStats(2700.0D, 4.0D, 0.55D, 0.22D, 7.5D, 0.198D, 0.3D, List.of(
                new AgentScanIntel.Mechanic("miningdim:m1", 2.0D, null, 12.0D),
                new AgentScanIntel.Mechanic("miningdim:ghost", 5.0D, 120.0D, null)));
    }

    private static AgentScanIntel intelAt(int level) {
        return AgentScanSnapshotBuilder.build(7, 5, level, INTEL_RAWS, intelStats()).intel();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildEffectiveHpUnlocksAtL3(GameTestHelper helper) {
        helper.assertTrue(intelAt(2).effectiveHp() == null, "L2 有效血量仍加密 (第四章 L3 才解), 必须是 null");
        Double hp = intelAt(3).effectiveHp();
        helper.assertTrue(hp != null && hp == 2700.0D, "L3 解密有效血量且原样透传原料真值, 实得 " + hp);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildArmorAndReductionUnlockAtL4(GameTestHelper helper) {
        AgentScanIntel l3 = intelAt(3);
        helper.assertTrue(l3.armor() == null && l3.damageReductionPct() == null,
                "L3 护甲/减伤仍加密 (第四章 L4 才解)");
        AgentScanIntel l4 = intelAt(4);
        helper.assertTrue(l4.armor() != null && l4.armor() == 4.0D, "L4 解密护甲, 实得 " + l4.armor());
        helper.assertTrue(l4.damageReductionPct() != null && l4.damageReductionPct() == 0.55D,
                "L4 解密减伤率, 实得 " + l4.damageReductionPct());
        helper.assertTrue(l4.bulletResistancePct() == null, "L4 子弹抗性仍加密 (L5 才解)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildBulletResistanceUnlocksAtL5(GameTestHelper helper) {
        helper.assertTrue(intelAt(4).bulletResistancePct() == null, "L4 子弹抗性必须是 null");
        Double bullet = intelAt(5).bulletResistancePct();
        helper.assertTrue(bullet != null && bullet == 0.22D, "L5 解密子弹抗性, 实得 " + bullet);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildAttackSingleHitAndSpeedUnlockAtL6(GameTestHelper helper) {
        AgentScanIntel l5 = intelAt(5);
        helper.assertTrue(l5.attackDamage() == null && l5.singleHitPct() == null && l5.movementSpeed() == null,
                "L5 攻击/单击/移速三格仍加密 (第四章 L6 才解)");
        AgentScanIntel l6 = intelAt(6);
        helper.assertTrue(l6.attackDamage() != null && l6.attackDamage() == 7.5D
                        && l6.singleHitPct() != null && l6.singleHitPct() == 0.198D
                        && l6.movementSpeed() != null && l6.movementSpeed() == 0.3D,
                "L6 三格一起解密且原样透传, 实得 " + l6);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildSkillMechanicsUnlockAtL7ForDecryptedMechanicRowsOnly(GameTestHelper helper) {
        helper.assertTrue(intelAt(6).mechanics() == null, "L6 技能时序整格加密, 必须是 null (不是空表)");
        List<AgentScanIntel.Mechanic> l7 = intelAt(7).mechanics();
        helper.assertTrue(l7 != null && l7.size() == 1,
                "L7 只放行词条行里已解密的机制 m1, 时序表里的 ghost 不得旁路漏出, 实得 " + l7);
        AgentScanIntel.Mechanic m1 = l7.get(0);
        helper.assertTrue("miningdim:m1".equals(m1.affixId()) && m1.chargeSeconds() == 2.0D
                        && m1.cooldownSeconds() == 12.0D && m1.interruptDamagePerPlayer() == null,
                "时序子项原样透传, 没有的概念保持 null (不补 0), 实得 " + m1);
        // 目标没有机制词条时 L7 是空表而不是 null: 空表 = "解锁了, 它没有技能", null = "还没解锁"。
        AgentScanIntel noMechanic = AgentScanSnapshotBuilder.build(7, 5, 7,
                List.of(rawWithQuality("p1", SealCategory.PASSIVE, "RARE")), intelStats()).intel();
        helper.assertTrue(noMechanic.mechanics() != null && noMechanic.mechanics().isEmpty(),
                "L7 且没有已解密机制行: 必须是空表, 实得 " + noMechanic.mechanics());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildQualityUnlocksAtL8(GameTestHelper helper) {
        AgentScanSnapshot l7 = AgentScanSnapshotBuilder.build(7, 5, 7, INTEL_RAWS, intelStats());
        helper.assertTrue(l7.entries().get(0).decrypted() && l7.entries().get(0).quality() == null
                        && l7.entries().get(1).quality() == null,
                "L7 词条已解密但品质表仍加密 (第四章 L8 才解), 品质必须是 null");
        AgentScanSnapshot l8 = AgentScanSnapshotBuilder.build(7, 5, 8, INTEL_RAWS, intelStats());
        helper.assertTrue("RARE".equals(l8.entries().get(0).quality()) && "EPIC".equals(l8.entries().get(1).quality()),
                "L8 每条已解密词条带原料品质, 实得 " + l8.entries());
        // 低级脉冲里加密行永远不带品质 (值对象层也拒); 这里用 L1 看第二条被动。
        AgentScanSnapshot l1 = AgentScanSnapshotBuilder.build(7, 1, 1, List.of(
                rawWithQuality("p1", SealCategory.PASSIVE, "COMMON"),
                rawWithQuality("p2", SealCategory.PASSIVE, "COMMON")), intelStats());
        helper.assertTrue(!l1.entries().get(1).decrypted() && l1.entries().get(1).quality() == null,
                "加密行不带品质");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildLiveUnlocksNumbersAtL9AndAttributesAtL10(GameTestHelper helper) {
        AgentScanSnapshotBuilder.RawLive raw = new AgentScanSnapshotBuilder.RawLive(
                1800.0D, 2700.0D, 4.0D, 3, 1, intelStats());
        helper.assertTrue(AgentScanSnapshotBuilder.buildLive(8, raw) == null,
                "L8 脉冲没有实时透视 (第四章 L9 才解), 必须整格 null");
        helper.assertTrue(!AgentScanSnapshotBuilder.refreshesLive(8) && AgentScanSnapshotBuilder.refreshesLive(9),
                "实时刷新门恰在 L9");

        AgentScanLive l9 = AgentScanSnapshotBuilder.buildLive(9, raw);
        helper.assertTrue(l9 != null && l9.tracked() && l9.currentHp() == 1800.0D && l9.maxHp() == 2700.0D
                        && l9.absorption() == 4.0D && l9.frostStacksOnYou() == 3 && l9.burningStacksOnYou() == 1,
                "L9 实时数值原样透传, 实得 " + l9);
        helper.assertTrue(l9.attributes() == null, "L9 全属性实时仍加密 (L10 才解)");

        AgentScanLive l10 = AgentScanSnapshotBuilder.buildLive(10, raw);
        helper.assertTrue(l10 != null && l10.attributes() != null && l10.attributes().damageReductionPct() == 0.55D
                        && l10.attributes().movementSpeed() == 0.3D,
                "L10 带全属性现值, 实得 " + l10);

        AgentScanLive lost = AgentScanSnapshotBuilder.buildLive(10, null);
        helper.assertTrue(lost != null && !lost.tracked() && lost.currentHp() == null && lost.attributes() == null,
                "目标读不到时报 tracked=false 且不发任何旧值, 实得 " + lost);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildWithoutRawStatsWithholdsEveryIntelField(GameTestHelper helper) {
        AgentScanIntel intel = AgentScanSnapshotBuilder.build(7, 5, 10, INTEL_RAWS).intel();
        helper.assertTrue(intel.equals(AgentScanIntel.WITHHELD),
                "不提供数值原料时即便 L10 也全格加密, 绝不拿 0 充数, 实得 " + intel);
        helper.succeed();
    }

    /**
     * 全表扫一遍: 1-10 级每一格"是否解密"必须与 {@link com.miningdim.job.agent.AgentScanField} 的解锁等级逐级一致。
     * 上面几条各盯一个边界, 这条兜住"改了某格解锁等级却没人改构建层"的漂移。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildIntelTieringMatchesTheScanFieldTableAtEveryLevel(GameTestHelper helper) {
        for (int level = 1; level <= 10; level++) {
            AgentScanIntel intel = intelAt(level);
            assertTier(helper, level, "effectiveHp", intel.effectiveHp() != null, 3);
            assertTier(helper, level, "armor", intel.armor() != null, 4);
            assertTier(helper, level, "damageReductionPct", intel.damageReductionPct() != null, 4);
            assertTier(helper, level, "bulletResistancePct", intel.bulletResistancePct() != null, 5);
            assertTier(helper, level, "attackDamage", intel.attackDamage() != null, 6);
            assertTier(helper, level, "singleHitPct", intel.singleHitPct() != null, 6);
            assertTier(helper, level, "movementSpeed", intel.movementSpeed() != null, 6);
            assertTier(helper, level, "mechanics", intel.mechanics() != null, 7);
            assertTier(helper, level, "highlight", AgentScanSnapshotBuilder.highlightsTargets(level), 8);
            assertTier(helper, level, "live", AgentScanSnapshotBuilder.refreshesLive(level), 9);
        }
        helper.succeed();
    }

    private static void assertTier(GameTestHelper helper, int level, String field, boolean present, int unlock) {
        helper.assertTrue(present == (level >= unlock),
                field + " 在 L" + level + " 的解密态应为 " + (level >= unlock) + " (第四章 L" + unlock + " 解锁)");
    }
}
