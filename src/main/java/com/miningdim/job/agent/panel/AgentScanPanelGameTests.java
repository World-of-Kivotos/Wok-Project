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
}
