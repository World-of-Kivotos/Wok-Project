package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
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

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void accessAndTrustDefaultsSurviveSaveAndLoad(GameTestHelper helper) {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ListTag saved = GranaryAccess.save(List.of(
                new GranaryAccess.Trustee(first, GranaryAccess.Permission.STORE_AND_TAKE),
                new GranaryAccess.Trustee(second, GranaryAccess.Permission.STORE_ONLY)));
        List<GranaryAccess.Trustee> loaded = GranaryAccess.load(saved);
        helper.assertTrue(loaded.equals(List.of(
                new GranaryAccess.Trustee(first, GranaryAccess.Permission.STORE_AND_TAKE),
                new GranaryAccess.Trustee(second, GranaryAccess.Permission.STORE_ONLY))), "名单原样存读");

        // 重复的人只留第一个 (不是坏档)。
        ListTag duplicate = GranaryAccess.save(List.of(
                new GranaryAccess.Trustee(first, GranaryAccess.Permission.STORE_ONLY)));
        duplicate.add(saved.get(0).copy());
        helper.assertTrue(GranaryAccess.load(duplicate).size() == 1, "重复的人只留一个");
        helper.assertTrue(GranaryAccess.load(duplicate).get(0).permission()
                == GranaryAccess.Permission.STORE_ONLY, "留的是第一个");

        // 坏档: 缺人、认不出的权限、超过 16 人。
        ListTag missing = new ListTag();
        missing.add(new CompoundTag());
        helper.assertTrue(rejects(missing), "缺人直接报错");
        ListTag badPerm = GranaryAccess.save(List.of(
                new GranaryAccess.Trustee(first, GranaryAccess.Permission.STORE_ONLY)));
        ((CompoundTag) badPerm.get(0)).putString(GranaryConstants.KEY_PERM, "OWNER");
        helper.assertTrue(rejects(badPerm), "认不出的权限直接报错");
        ListTag tooLong = new ListTag();
        for (int i = 0; i <= GranaryAccess.MAX_TRUSTEES; i++) {
            tooLong.add(GranaryAccess.save(List.of(new GranaryAccess.Trustee(UUID.randomUUID(),
                    GranaryAccess.Permission.STORE_ONLY))).get(0));
        }
        helper.assertTrue(rejects(tooLong), "超过 16 人直接报错");

        // 账本的默认名单: 存读往返 + 老档没有这个键按空读。
        ServerLevel level = helper.getLevel();
        GranarySavedData data = GranarySavedData.get(level);
        data.setTrustDefault(first, List.of(new GranaryAccess.Trustee(second,
                GranaryAccess.Permission.STORE_AND_TAKE)));
        GranarySavedData reloaded = GranarySavedData.load(data.save(new CompoundTag()));
        helper.assertTrue(reloaded.trustDefault(first).size() == 1
                        && reloaded.trustDefault(first).get(0).player().equals(second),
                "默认名单随账本往返");
        CompoundTag withoutTrust = data.save(new CompoundTag());
        withoutTrust.remove(GranaryConstants.KEY_TRUST_DEFAULTS);
        helper.assertTrue(GranarySavedData.load(withoutTrust).trustDefault(first).isEmpty(),
                "这张表落地之前的档照读 (按空名单)");
        data.setTrustDefault(first, List.of());
        helper.succeed();
    }

    private static boolean rejects(ListTag tag) {
        try {
            GranaryAccess.load(tag);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }
}
