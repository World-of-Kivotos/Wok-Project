package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import com.miningdim.freshness.item.FreshnessItems;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import vectorwing.farmersdelight.common.block.entity.SkilletBlockEntity;
import vectorwing.farmersdelight.common.block.entity.StoveBlockEntity;
import vectorwing.farmersdelight.common.registry.ModBlocks;

/**
 * FD 机器「放着不加热也照常烂」的 GameTest (docs/Spoilage_Granary_DesignSpec.md「可选配置」#12、#14、#15)。
 *
 * <p>这是规格点名的**冷库漏洞**: 三台机器的内部物品栏不在任何观察点上, 东西放进去就永远不烂, 等于免费冰箱。
 * 补法是它们的 {@code cookingTick} 每次先把内部物品栏结算一遍 —— 用例直接调这个静态方法, 断言"机器里的
 * 东西会盖章、会烂成腐烂物", 以及三处注入都真的织进去了。</p>
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessFdSettleGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "freshness_fd";

    private static final int SLOT = 12;

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void foodInsideSkilletRotsWhileItSitsThere(GameTestHelper helper) {
        withModeOn(() -> {
            ServerLevel level = helper.getLevel();
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            BlockPos rel = new BlockPos(1, 2, 1);
            helper.setBlock(rel, ModBlocks.SKILLET.get());
            SkilletBlockEntity pan = (SkilletBlockEntity) helper.getBlockEntity(rel);
            helper.assertTrue(pan != null, "煎锅方块实体要放得出来");

            long nowMinute = FreshnessClockData.nowMinute(level);
            ItemStack rotten = new ItemStack(Items.BEEF, 3);
            FreshnessData.stampAt(rotten, nowMinute - 40L * 24L * 60L, 0);
            helper.assertTrue(pan.addItemToCook(rotten, player).isEmpty(), "烂肉要放得进煎锅");

            SkilletBlockEntity.cookingTick(level, helper.absolutePos(rel),
                    helper.getBlockState(rel), pan);
            helper.assertTrue(pan.getStoredStack().is(FreshnessItems.ROT.get()),
                    "煎锅里的烂肉要当场变成腐烂物, 实得 " + pan.getStoredStack());

            // 新东西放进去: 第一趟就盖章 (机器里不是"永远新鲜"的保险箱)。
            ItemStack fresh = new ItemStack(Items.BEEF, 1);
            pan.removeItem();
            helper.assertTrue(pan.addItemToCook(fresh, player).isEmpty(), "新肉也要放得进去");
            helper.assertTrue(!FreshnessData.isStamped(pan.getStoredStack()), "夹具: 放进去时还没盖章");
            SkilletBlockEntity.cookingTick(level, helper.absolutePos(rel), helper.getBlockState(rel), pan);
            helper.assertTrue(FreshnessData.isStamped(pan.getStoredStack()), "机器里的东西第一趟就要盖章");
        });
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void foodInsideStoveAndPotRotsToo(GameTestHelper helper) {
        withModeOn(() -> {
            ServerLevel level = helper.getLevel();
            long nowMinute = FreshnessClockData.nowMinute(level);

            BlockPos stoveRel = new BlockPos(SLOT + 1, 2, 1);
            helper.setBlock(stoveRel, ModBlocks.STOVE.get());
            StoveBlockEntity stove = (StoveBlockEntity) helper.getBlockEntity(stoveRel);
            helper.assertTrue(stove != null, "炉灶方块实体要放得出来");
            ItemStack onStove = new ItemStack(Items.BEEF, 1);
            FreshnessData.stampAt(onStove, nowMinute - 40L * 24L * 60L, 0);
            stove.getInventory().setStackInSlot(0, onStove);
            StoveBlockEntity.cookingTick(level, helper.absolutePos(stoveRel),
                    helper.getBlockState(stoveRel), stove);
            helper.assertTrue(stove.getInventory().getStackInSlot(0).is(FreshnessItems.ROT.get()),
                    "炉灶上的烂肉也要变成腐烂物, 实得 " + stove.getInventory().getStackInSlot(0));

            BlockPos potRel = new BlockPos(SLOT * 2 + 1, 2, 1);
            helper.setBlock(potRel, ModBlocks.COOKING_POT.get());
            CookingPotBlockEntity pot = (CookingPotBlockEntity) helper.getBlockEntity(potRel);
            helper.assertTrue(pot != null, "厨锅方块实体要放得出来");
            ItemStack inPot = new ItemStack(Items.BEEF, 1);
            FreshnessData.stampAt(inPot, nowMinute - 40L * 24L * 60L, 0);
            pot.getInventory().setStackInSlot(0, inPot);
            CookingPotBlockEntity.cookingTick(level, helper.absolutePos(potRel),
                    helper.getBlockState(potRel), pot);
            helper.assertTrue(pot.getInventory().getStackInSlot(0).is(FreshnessItems.ROT.get()),
                    "厨锅里的烂肉也要变成腐烂物, 实得 " + pot.getInventory().getStackInSlot(0));

            // 注入点核对: 三处都真的织进去了 (可选配置 defaultRequire = 0 不会替我们报错)。
            for (String mixin : new String[]{FreshnessCompatStatus.SKILLET_SETTLE,
                    FreshnessCompatStatus.STOVE_SETTLE, FreshnessCompatStatus.POT_SETTLE}) {
                helper.assertTrue(FreshnessCompatStatus.woven(mixin),
                        mixin + " 没织进去: " + System.getProperty(
                                FreshnessCompatMixinPlugin.PROPERTY_PREFIX + mixin));
            }
        });
        helper.succeed();
    }

    /** 这一条要"真的换腐烂物", 所以临时把总开关拨到 ON, 跑完复原。 */
    private static void withModeOn(Runnable body) {
        FreshnessConfig.Mode previous = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.ON);
        try {
            body.run();
        } finally {
            FreshnessConfig.MODE.set(previous);
        }
    }
}
