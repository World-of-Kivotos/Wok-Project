package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryAccessGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_access";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void theListDecidesWhoMayStoreAndWhoMayTake(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        UUID helper_ = UUID.randomUUID();
        UUID guest = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        List<GranaryAccess.Trustee> list = new ArrayList<>();
        helper.assertTrue(GranaryAccess.isPrivate(list), "默认是私有囤");

        helper.assertTrue(GranaryAccess.add(list, owner, helper_,
                GranaryAccess.Permission.STORE_AND_TAKE) == GranaryAccess.Status.ADDED, "加一个人");
        helper.assertTrue(GranaryAccess.add(list, owner, guest,
                GranaryAccess.Permission.STORE_ONLY) == GranaryAccess.Status.ADDED, "再加一个只存的");
        helper.assertTrue(GranaryAccess.add(list, owner, helper_,
                GranaryAccess.Permission.STORE_ONLY) == GranaryAccess.Status.UPDATED, "改权限是替换不是新增");
        helper.assertTrue(list.size() == 2, "改权限不该多出人, 实际 " + list.size());

        helper.assertTrue(GranaryAccess.mayStore(owner, list, owner), "囤主能存");
        helper.assertTrue(GranaryAccess.mayTake(owner, list, owner), "囤主能取");
        helper.assertTrue(GranaryAccess.mayStore(owner, list, guest), "「只存」的人能存");
        helper.assertTrue(!GranaryAccess.mayTake(owner, list, guest), "「只存」的人不能取");
        helper.assertTrue(GranaryAccess.mayStore(owner, list, helper_), "改过权限的人还能存");
        helper.assertTrue(!GranaryAccess.mayTake(owner, list, helper_),
                "改成「只存」之后取不了了 (按新的算, 不是按加进来那一下)");
        helper.assertTrue(!GranaryAccess.mayStore(owner, list, stranger)
                && !GranaryAccess.mayTake(owner, list, stranger), "名单外的人什么都不行");
        helper.assertTrue(!GranaryAccess.mayStore(owner, list, null), "空玩家不放行");

        helper.assertTrue(GranaryAccess.add(list, owner, owner,
                GranaryAccess.Permission.STORE_AND_TAKE) == GranaryAccess.Status.IS_OWNER,
                "囤主不用进名单");
        helper.assertTrue(GranaryAccess.remove(list, guest), "移出一个人");
        helper.assertTrue(!GranaryAccess.remove(list, guest), "移出不在名单上的人是幂等的");
        helper.assertTrue(GranaryAccess.add(list, owner, guest,
                GranaryAccess.Permission.STORE_AND_TAKE) == GranaryAccess.Status.ADDED, "还能再加回来");
        helper.assertTrue(GranaryAccess.takeCount(list) == 1, "可存可取的人数要对 (只剩后来加回来的那一个), 实际 "
                + GranaryAccess.takeCount(list));
        GranaryAccess.clear(list);
        helper.assertTrue(GranaryAccess.isPrivate(list) && list.isEmpty(), "清空回私有");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void theListHoldsAtMostSixteenPlayers(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        List<GranaryAccess.Trustee> list = new ArrayList<>();
        for (int i = 0; i < GranaryAccess.MAX_TRUSTEES; i++) {
            helper.assertTrue(GranaryAccess.add(list, owner, UUID.randomUUID(),
                    GranaryAccess.Permission.STORE_ONLY) == GranaryAccess.Status.ADDED, "第 " + (i + 1) + " 个加得进");
        }
        helper.assertTrue(list.size() == GranaryAccess.MAX_TRUSTEES, "满员 16 人, 实际 " + list.size());
        helper.assertTrue(GranaryAccess.add(list, owner, UUID.randomUUID(),
                GranaryAccess.Permission.STORE_ONLY) == GranaryAccess.Status.FULL, "第 17 个加不进");
        // 满员时改已有成员的权限仍然可以 (那不是「再加一个」)。
        helper.assertTrue(GranaryAccess.add(list, owner, list.get(0).player(),
                GranaryAccess.Permission.STORE_AND_TAKE) == GranaryAccess.Status.UPDATED, "满员也能改权限");
        helper.succeed();
    }
}
