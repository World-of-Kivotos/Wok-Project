package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import vectorwing.farmersdelight.common.block.entity.CuttingBoardBlockEntity;
import vectorwing.farmersdelight.common.registry.ModBlocks;
import vectorwing.farmersdelight.common.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * FD 兼容 mixin 的 GameTest (docs/Spoilage_Granary_DesignSpec.md「加工与压缩」「可选配置」#10、#11)。
 *
 * <p>锁三件事, 全都是"注入点真的织进去了"才能成立的:</p>
 * <ol>
 *   <li>砧板切出来的东西**继承板上那件原料** (加成 0: 切配不改新鲜度);</li>
 *   <li>切完来源上下文要清掉 (清不掉的话下一次别的工序会拿错原料);</li>
 *   <li>两处 mixin 都织进去了 ({@link FreshnessCompatStatus}) —— 可选配置的 defaultRequire = 0, 不织也不报错,
 *       所以"换了 FD 版本、继承悄悄失效"只能靠这一条抓住。</li>
 * </ol>
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessCuttingBoardGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "freshness_fd";

    private static final int SLOT = 8;

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cuttingInheritsTheStoredItemAndClearsTheContext(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.setBlock(new BlockPos(1, 2, 1), ModBlocks.CUTTING_BOARD.get());
        helper.assertTrue(helper.getBlockEntity(new BlockPos(1, 2, 1)) instanceof CuttingBoardBlockEntity,
                "砧板方块实体要放得出来");
        CuttingBoardBlockEntity board = (CuttingBoardBlockEntity) helper.getBlockEntity(new BlockPos(1, 2, 1));

        long nowMinute = FreshnessClockData.nowMinute(level);
        int beefShelfHours = FreshnessCategories.shelfLifeHours(new ItemStack(Items.BEEF));
        int productShelfHours = FreshnessCategories.shelfLifeHours(new ItemStack(ModItems.MINCED_BEEF.get()));
        helper.assertTrue(beefShelfHours > 0 && productShelfHours > 0, "夹具: 生牛肉与肉馅都要有保质期");

        // 板上放一块"剩一半"的生牛肉: 批次 = 现在 − 半条保质期。
        ItemStack beef = new ItemStack(Items.BEEF, 1);
        FreshnessData.stampAt(beef, nowMinute - (long) beefShelfHours * 30L, 0);
        helper.assertTrue(board.addItem(beef).isEmpty(), "生牛肉要放得上砧板");

        helper.assertTrue(board.processStoredItemUsingTool(new ItemStack(ModItems.IRON_KNIFE.get()), player),
                "砧板应当认得这个切分配方 (铁刀切生牛肉)");
        helper.assertTrue(board.isEmpty(), "切完板上就空了");

        // 产物落在地上: 找到那堆肉馅, 断言它的章是按来源算的。
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class,
                new net.minecraft.world.phys.AABB(pos).inflate(3.0D));
        ItemStack product = drops.stream()
                .map(ItemEntity::getItem)
                .filter(stack -> stack.is(ModItems.MINCED_BEEF.get()))
                .findFirst()
                .orElse(null);
        helper.assertTrue(product != null, "砧板应当喷出肉馅 (掉落物数量 " + drops.size() + ")");
        helper.assertTrue(FreshnessData.isStamped(product), "产物要盖上继承来的章");
        long remaining = FreshnessData.remainingMinutes(product, productShelfHours, nowMinute);
        double ratio = remaining / (productShelfHours * 60.0D);
        helper.assertTrue(ratio > 0.35D && ratio < 0.65D,
                "继承的应当是原料的剩余比例 (约一半), 实得 " + ratio + " (剩 " + remaining + " 分钟)");
        helper.assertTrue(FreshnessData.stoppedHours(product) == 0, "切配不产生停表额度");

        // 来源上下文清掉了 (清不掉会污染下一次别的工序)。
        helper.assertTrue(FreshnessSourceContext.current().isEmpty(), "切完要清掉来源上下文");

        // 注入点核对: 两处都真的织进去了 (defaultRequire = 0 不会替我们报错)。
        helper.assertTrue(FreshnessCompatStatus.woven(FreshnessCompatStatus.CUTTING_BOARD),
                "砧板 mixin 没织进去: " + System.getProperty(
                        FreshnessCompatMixinPlugin.PROPERTY_PREFIX + FreshnessCompatStatus.CUTTING_BOARD));
        helper.assertTrue(FreshnessCompatStatus.woven(FreshnessCompatStatus.ITEM_UTILS),
                "喷物品 mixin 没织进去: " + System.getProperty(
                        FreshnessCompatMixinPlugin.PROPERTY_PREFIX + FreshnessCompatStatus.ITEM_UTILS));
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void emptyBoardLeavesNoContextBehind(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        helper.setBlock(new BlockPos(SLOT + 1, 2, 1), ModBlocks.CUTTING_BOARD.get());
        CuttingBoardBlockEntity board = (CuttingBoardBlockEntity) helper.getBlockEntity(new BlockPos(SLOT + 1, 2, 1));
        helper.assertTrue(board != null, "砧板方块实体要放得出来");

        // 空板切一刀: 没配方、没产物, 也不该留下任何上下文。
        helper.assertTrue(!board.processStoredItemUsingTool(new ItemStack(ModItems.IRON_KNIFE.get()), player),
                "空板切不出东西");
        helper.assertTrue(FreshnessSourceContext.current().isEmpty(), "空板切完也不能留下来源上下文");

        // 再放一件东西切: 上下文是这一趟的原料, 不是上一趟的残留。
        long nowMinute = FreshnessClockData.nowMinute(level);
        ItemStack beef = new ItemStack(Items.BEEF, 1);
        FreshnessData.stamp(beef, nowMinute);
        board.addItem(beef);
        helper.assertTrue(board.processStoredItemUsingTool(new ItemStack(ModItems.IRON_KNIFE.get()), player),
                "有东西时切得动");
        helper.assertTrue(FreshnessSourceContext.current().isEmpty(), "切完照样清干净");
        helper.succeed();
    }
}
