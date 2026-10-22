package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import com.miningdim.freshness.item.FreshnessItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 结算与腐烂终态的 GameTest (docs/Spoilage_Granary_DesignSpec.md「腐烂终态」「加工与压缩」)。
 *
 * 结算就是「观察点看到一格东西之后该做什么」的唯一实现, 所以这里锁的是它的契约:
 * 未盖章的盖章、已腐烂的按开关 1 换 1、碗瓶退回、不腐烂的东西一概不碰。
 * 时间一律用「远在过去」的批次构造过期, 免得受配置里保质期取值影响。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessSettleGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "freshness_settle";

    /** 一个固定的「现在」; 过期用远在过去的批次构造, 与配置里的保质期无关。 */
    private static final long NOW_MINUTE = 500_000L * 60L;

    /** 远在两年前的批次: 比最长的保质期 (腌制 7 天, 配置上限 365 天) 还老得多。 */
    private static final long ANCIENT_MINUTE = NOW_MINUTE - 2L * 365L * 24L * 60L;

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void settleStampsSpoilingItemsAndLeavesOthersAlone(GameTestHelper helper) {
        List<ItemStack> returns = new ArrayList<>();

        ItemStack beef = new ItemStack(Items.BEEF);
        helper.assertTrue(FreshnessSettle.settleSlot(beef, NOW_MINUTE, true, returns).isEmpty(),
                "没过期的东西不该被换掉");
        helper.assertTrue(FreshnessData.isStamped(beef), "观察点看到未盖章的易腐品要当场盖章");
        helper.assertTrue(returns.isEmpty(), "盖章不产生容器退回");

        ItemStack diamond = new ItemStack(Items.DIAMOND);
        helper.assertTrue(FreshnessSettle.settleSlot(diamond, NOW_MINUTE, true, returns).isEmpty(),
                "不腐烂的东西返回空");
        helper.assertTrue(!FreshnessData.isStamped(diamond), "不该给不腐烂的东西盖章");

        ItemStack empty = ItemStack.EMPTY;
        helper.assertTrue(FreshnessSettle.settleSlot(empty, NOW_MINUTE, true, returns).isEmpty(),
                "空格子什么都不做");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void settleReplacesRottenOneForOneOnlyWhenReplacementIsOn(GameTestHelper helper) {
        List<ItemStack> returns = new ArrayList<>();

        // 试运行档: 照常结算但不换 (安全阀「试运行时照常盖章, 但不换腐烂物」)。
        ItemStack dryRun = new ItemStack(Items.BEEF, 7);
        FreshnessData.stampAt(dryRun, ANCIENT_MINUTE, 0);
        helper.assertTrue(FreshnessSettle.settleSlot(dryRun, NOW_MINUTE, false, returns).isEmpty(),
                "试运行不换腐烂物");
        helper.assertTrue(dryRun.getCount() == 7 && dryRun.is(Items.BEEF),
                "试运行下原物原样留着, 只统计");

        // 开启档: 1 换 1。
        ItemStack live = new ItemStack(Items.BEEF, 7);
        FreshnessData.stampAt(live, ANCIENT_MINUTE, 0);
        ItemStack replacement = FreshnessSettle.settleSlot(live, NOW_MINUTE, true, returns);
        helper.assertTrue(!replacement.isEmpty(), "开启档必须换掉已腐烂的食物");
        helper.assertTrue(replacement.is(FreshnessItems.ROT.get()), "换成腐烂物");
        helper.assertTrue(replacement.getCount() == 7, "1 换 1, 件数不变");
        helper.assertTrue(returns.isEmpty(), "生肉没有容器要退");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void settleReturnsBowlAndBottlePerItem(GameTestHelper helper) {
        List<ItemStack> returns = new ArrayList<>();
        ItemStack stew = new ItemStack(Items.MUSHROOM_STEW, 4);
        FreshnessData.stampAt(stew, ANCIENT_MINUTE, 0);
        ItemStack replacement = FreshnessSettle.settleSlot(stew, NOW_MINUTE, true, returns);
        helper.assertTrue(replacement.is(FreshnessItems.ROT.get()) && replacement.getCount() == 4,
                "碗装食物同样 1 换 1");
        helper.assertTrue(returns.size() == 1, "碗要退回来");
        helper.assertTrue(returns.get(0).is(Items.BOWL), "退的是碗");
        helper.assertTrue(returns.get(0).getCount() == 4, "按件退碗, 4 份炖菜退 4 个碗");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void settleContainerReplacesInPlaceAndStampsTheRest(GameTestHelper helper) {
        SimpleContainer container = new SimpleContainer(3);
        ItemStack rotten = new ItemStack(Items.COOKED_BEEF, 5);
        FreshnessData.stampAt(rotten, ANCIENT_MINUTE, 0);
        container.setItem(0, rotten);
        container.setItem(1, new ItemStack(Items.BREAD));
        container.setItem(2, new ItemStack(Items.DIAMOND));

        List<ItemStack> returns = new ArrayList<>();
        int rotted = FreshnessSettle.settleContainer(container, NOW_MINUTE, true, returns);

        helper.assertTrue(rotted == 1, "只有那一格该换");
        helper.assertTrue(container.getItem(0).is(FreshnessItems.ROT.get())
                        && container.getItem(0).getCount() == 5,
                "被换掉的格子放的是腐烂物, 件数不变");
        helper.assertTrue(FreshnessData.isStamped(container.getItem(1)),
                "同一趟里没盖章的也顺手盖上");
        helper.assertTrue(container.getItem(2).is(Items.DIAMOND), "不腐烂的东西一动不动");
        helper.assertTrue(returns.isEmpty(), "这一趟没有容器要退");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rotItemIsARegisteredInedibleMaterial(GameTestHelper helper) {
        helper.assertTrue(FreshnessItems.ROT.isPresent(), "腐烂物必须已注册");
        ItemStack rot = FreshnessSettle.rotStack(3);
        helper.assertTrue(!rot.isEdible(), "腐烂物不能吃");
        helper.assertTrue(rot.getMaxStackSize() == 64, "腐烂物 64 个一组");
        helper.assertTrue(FreshnessSettle.rotStack(0).getCount() == 1, "至少 1 个 (不会换出空栈)");
        helper.assertTrue(FreshnessLootModifiers.STAMP.isPresent(),
                "全局掉落修改器的序列化器必须已注册 (json 里的 type 指向它)");
        helper.succeed();
    }
}
