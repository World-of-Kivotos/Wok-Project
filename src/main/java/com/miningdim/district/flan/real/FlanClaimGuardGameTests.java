package com.miningdim.district.flan.real;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.guard.GuardSettings;
import com.miningdim.district.guard.GuardTestZones;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 个人圈地限制 (设计文档 22.20) 对真 Flan 的 GameTest 的登记处。用例体在 {@link FlanClaimGuardScenarios}; 拆成两个类的
 * 理由同 {@link FlanRealGameTests}: 本类的签名里只有 GameTestHelper 与 ServerLevel, 没有 Flan 也登记得了。
 *
 * <p>Flan 在开发运行时里 (S1, 20.1), F1、F2 真的应用, 行为直接走 Flan: 金锄头的 {@code claimLandHandling}、
 * {@code ClaimStorage.createClaim} / {@code resizeClaim}、{@code /flan add}、{@code add rect}、{@code expand}, 以及
 * 编辑状态与登记不一致时的拒绝 (22.22)。batch 开始时经 {@link GuardTestZones} 把守卫装进门面 (默认设置),
 * 用例各在自己的槽位 (30–37、40–41) 里装区。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FlanClaimGuardGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_claim_guard";

    private FlanClaimGuardGameTests() {
    }

    @BeforeBatch(batch = BATCH)
    public static void beforeBatch(ServerLevel level) {
        GuardTestZones.begin(GuardSettings.defaults());
    }

    @AfterBatch(batch = BATCH)
    public static void afterBatch(ServerLevel level) {
        GuardTestZones.end();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void createInBufferRejectedJustOutsideAccepted(GameTestHelper helper) {
        if (FlanRealGameTests.requireFlan(helper)) {
            FlanClaimGuardScenarios.createInBufferRejectedJustOutsideAccepted(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void insideWithoutParentClaimIsRejected(GameTestHelper helper) {
        if (FlanRealGameTests.requireFlan(helper)) {
            FlanClaimGuardScenarios.insideWithoutParentClaimIsRejected(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void threeDClaimAndOtherDimension(GameTestHelper helper) {
        if (FlanRealGameTests.requireFlan(helper)) {
            FlanClaimGuardScenarios.threeDClaimAndOtherDimension(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void goldenHoeClicksAreGuarded(GameTestHelper helper) {
        if (FlanRealGameTests.requireFlan(helper)) {
            FlanClaimGuardScenarios.goldenHoeClicksAreGuarded(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void resizeRules(GameTestHelper helper) {
        if (FlanRealGameTests.requireFlan(helper)) {
            FlanClaimGuardScenarios.resizeRules(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void flanCommandsAreGuarded(GameTestHelper helper) {
        if (FlanRealGameTests.requireFlan(helper)) {
            FlanClaimGuardScenarios.flanCommandsAreGuarded(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void opExemptionMatchesTheCreateBan(GameTestHelper helper) {
        if (FlanRealGameTests.requireFlan(helper)) {
            FlanClaimGuardScenarios.opExemptionMatchesTheCreateBan(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void adminClaimsSubclaimsAndSwitch(GameTestHelper helper) {
        if (FlanRealGameTests.requireFlan(helper)) {
            FlanClaimGuardScenarios.adminClaimsSubclaimsAndSwitch(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void inconsistentEditIsRefusedSlot40(GameTestHelper helper) {
        if (FlanRealGameTests.requireFlan(helper)) {
            FlanClaimGuardScenarios.inconsistentEditIsRefusedSlot40(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void inconsistentEditIsRefusedSlot41(GameTestHelper helper) {
        if (FlanRealGameTests.requireFlan(helper)) {
            FlanClaimGuardScenarios.inconsistentEditIsRefusedSlot41(helper);
        }
    }
}
