package com.miningdim.district.flan.real;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictTestEnv;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 真 Flan 的 GameTest 的登记处 (设计文档 20.10)。用例体在 {@link FlanRealScenarios}; 这里每个方法只做两件事: 先查 Flan
 * 在不在位 ({@link #requireFlan}), 再调用例体。
 *
 * <p>为什么拆成两个类: GameTest 框架登记用例时对 holder 调 getDeclaredMethods(), 要解析每个声明方法 (连同 lambda 生成的
 * 合成方法) 签名里的全部类型。签名里带 Flan 的类型 (Claim 之类) 的话, 没有 Flan 的运行时 (服主否决出路 ③、删掉
 * build.gradle 里两行 runtimeOnly, 或 -PwithoutFlan 的专门一轮) 整个 GameTest 服务端会在跑任何用例之前
 * NoClassDefFoundError, 连不需要 Flan 的一千多条用例一起跑不成。所以本类的签名里只有 GameTestHelper, 不碰任何 Flan 类型;
 * 用例体所在的类只在 Flan 在位时才被加载。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FlanRealGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_flan_real";

    private FlanRealGameTests() {
    }

    /**
     * Flan 在位才往下跑。不在位时: -PwithoutFlan 的专门一轮 (系统属性 {@link DistrictTestEnv#WITHOUT_FLAN_PROPERTY}) 里
     * 直接算通过 (那一轮核对的是没有 Flan 的生产路径); 否则失败 —— 开发运行时一定有 Flan (S1), 不静默跳过。
     */
    public static boolean requireFlan(GameTestHelper helper) {
        if (ModList.get() != null && ModList.get().isLoaded(FlanCompat.MOD_ID)) {
            return true;
        }
        if (DistrictTestEnv.withoutFlanRun()) {
            helper.succeed();
            return false;
        }
        helper.fail("开发运行时没有加载 Flan (build.gradle 的 runtimeOnly fg.deobf, 设计文档 20.1); 只有 -PwithoutFlan "
                + "的专门一轮才允许没有 Flan");
        return false;
    }

    // ================================================================
    // 自检与失败保护
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void selfCheckPassesOnTheApprovedFlan(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.selfCheckPassesOnTheApprovedFlan(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void selfCheckMismatchFallsBackWithoutAnyWrite(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.selfCheckMismatchFallsBackWithoutAnyWrite(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gatewayTurnsFlanExceptionsIntoFailures(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.gatewayTurnsFlanExceptionsIntoFailures(helper);
        }
    }

    // ================================================================
    // 父领地
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void districtClaimIsFullHeightAdminWithoutDefaultGroups(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.districtClaimIsFullHeightAdminWithoutDefaultGroups(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void residentsGroupCarriesPublicPermissionsAndMembers(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.residentsGroupCarriesPublicPermissionsAndMembers(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void strayDistrictClaimIsRelinkedNotDuplicated(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.strayDistrictClaimIsRelinkedNotDuplicated(helper);
        }
    }

    // ================================================================
    // 地块
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void plotCreationScrubsInheritedGroupsMembersAndPotions(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.plotCreationScrubsInheritedGroupsMembersAndPotions(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unrecordedPlotClaimIsDeletedAgain(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.unrecordedPlotClaimIsDeletedAgain(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void groupNamesAreUniquePerPlot(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.groupNamesAreUniquePerPlot(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void newPlotWritesEveryNonGlobalPermissionAndUnsetsEveryGlobal(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.newPlotWritesEveryNonGlobalPermissionAndUnsetsEveryGlobal(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void audiencesResolveThroughRealFlan(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.audiencesResolveThroughRealFlan(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void publicBreakForResidentsDoesNotLeakIntoPlots(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.publicBreakForResidentsDoesNotLeakIntoPlots(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void regionRulesLiveOnTheParentAndPlotsFollow(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.regionRulesLiveOnTheParentAndPlotsFollow(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void frozenPlotDeniesEveryoneAndKeepsSavedSettings(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.frozenPlotDeniesEveryoneAndKeepsSavedSettings(helper);
        }
    }

    // ================================================================
    // 对账
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void reconcileRevertsManualFlanEdits(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.reconcileRevertsManualFlanEdits(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void reconcileRepairsReachDiskEvenWithoutOtherEdits(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.reconcileRepairsReachDiskEvenWithoutOtherEdits(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void allowListsAreClearedOnBindAndReconcile(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.allowListsAreClearedOnBindAndReconcile(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void inspectPreviewsAndResyncRevertsThroughCommands(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.inspectPreviewsAndResyncRevertsThroughCommands(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void reconcileHandlesForeignAndOrphanSubclaims(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.reconcileHandlesForeignAndOrphanSubclaims(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void reconcileNeverTouchesUnboundClaims(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.reconcileNeverTouchesUnboundClaims(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pushesWriteOnlyDifferences(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.pushesWriteOnlyDifferences(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void saveAndReloadNeedsNoRewrites(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.saveAndReloadNeedsNoRewrites(helper);
        }
    }

    // ================================================================
    // 绑定、改认与范围
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bindAdoptsAnExistingAdminClaim(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.bindAdoptsAnExistingAdminClaim(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bindExtendsAShallowClaimToTheWorldBottom(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.bindExtendsAShallowClaimToTheWorldBottom(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void recreateRebuildsAMissingDistrictClaim(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.recreateRebuildsAMissingDistrictClaim(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void relinkAdoptsTheLandsClaimAfterARestore(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.relinkAdoptsTheLandsClaimAfterARestore(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void boundsSyncReadsTheClaimAndRejectsPlotsOutside(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.boundsSyncReadsTheClaimAndRejectsPlotsOutside(helper);
        }
    }

    // ================================================================
    // 外围已有的个人领地 (22.20, P40)
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void personalClaimsAreListedNeverDeleted(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanClaimGuardScenarios.personalClaimsAreListedNeverDeleted(helper);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bindAndBoundsSyncReportBufferClaims(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanClaimGuardScenarios.bindAndBoundsSyncReportBufferClaims(helper);
        }
    }

    // ================================================================
    // 备份
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void backupsAreWrittenOutsideDataClaimsAndRotated(GameTestHelper helper) {
        if (requireFlan(helper)) {
            FlanRealScenarios.backupsAreWrittenOutsideDataClaimsAndRotated(helper);
        }
    }
}
