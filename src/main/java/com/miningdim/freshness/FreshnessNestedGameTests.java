package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 嵌套容器下钻 ({@link FreshnessNested}) 的契约 GameTest。纯函数, 不碰世界。
 *
 * 这一层是市场与后续消费点的公共底座, 它坏掉的方式都很安静: 读错存放键 = 烂货藏在盒子里过关;
 * 读深了 = 恶意套娃把主线程拖住; 读到脏 NBT 抛异常 = 一个畸形潜影盒把上架请求打崩;
 * 盖章不回写 = 跑了但存档里一个章都没有。四条用例逐条钉住这四种沉默失败。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessNestedGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "freshness_nested";

    /** 取样时刻: 随便挑一个够大的分钟数, 免得与"未盖章按现在算"的边界混在一起。 */
    private static final long NOW = 900_000L;

    // ============================================================
    // 1. 存放键: 原版方块实体容器 + FD 厨锅物品
    // ============================================================

    /**
     * 两条登记过的路径都要能读到: {@code BlockEntityTag.Items} (潜影盒/箱子这类) 与
     * {@code BlockEntityTag.Inventory.Items} (Farmer's Delight 厨锅物品形态), 且能一路钻到第二层。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void flattenReadsBothRegisteredContentPathsAndNests(GameTestHelper helper) {
        ItemStack bread = new ItemStack(Items.BREAD, 1);
        ItemStack beef = new ItemStack(Items.COOKED_BEEF, 1);

        ItemStack box = shulkerWith(listTagOf(bread.save(new CompoundTag()), beef.save(new CompoundTag())));
        List<ItemStack> flatBox = FreshnessNested.flatten(box);
        helper.assertTrue(flatBox.size() == 3, "盒子 + 两件内容物 = 3 层, 实得 " + flatBox.size());
        helper.assertTrue(flatBox.get(0) == box, "本体排第一 (调用方靠它判「这一件自己」)");
        helper.assertTrue(contentsOf(flatBox).size() == 2, "两件内容物都要展开出来");

        // FD 厨锅物品形态: BlockEntityTag.Inventory.Items。
        ItemStack pot = kitchenPotWith(listTagOf(bread.save(new CompoundTag())));
        List<ItemStack> flatPot = FreshnessNested.flatten(pot);
        helper.assertTrue(flatPot.size() == 2,
                "厨锅物品的内容物在 Inventory.Items 下, 也要读得到, 实得 " + flatPot.size());

        // 盒子里装厨锅: 第二层必须能钻到 (深度上限 3 层, 两层绰绰有余)。
        ItemStack potInBox = shulkerWith(listTagOf(pot.save(new CompoundTag())));
        List<ItemStack> flatNested = FreshnessNested.flatten(potInBox);
        helper.assertTrue(flatNested.size() == 3,
                "盒子 -> 厨锅 -> 面包 = 3 层, 实得 " + flatNested.size());
        helper.assertTrue(FreshnessCategories.spoils(flatNested.get(2)),
                "最里面那件必须是那件会烂的面包 (读错键的话这里会是个空位或厨锅本身)");
        helper.succeed();
    }

    // ============================================================
    // 2. 深度上限
    // ============================================================

    /** 恶意套娃不许无限递归: 本体之下最多再钻 {@link FreshnessNested#MAX_DEPTH} 层, 多出来的直接不看。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void flattenStopsAtTheDepthCap(GameTestHelper helper) {
        ItemStack chain = new ItemStack(Items.BREAD, 1);
        for (int level = 0; level < 6; level++) {
            chain = shulkerWith(listTagOf(chain.save(new CompoundTag())));
        }
        List<ItemStack> flat = FreshnessNested.flatten(chain);
        helper.assertTrue(flat.size() == FreshnessNested.MAX_DEPTH + 1,
                "六层套娃只该展开成 " + (FreshnessNested.MAX_DEPTH + 1) + " 层 (本体 + "
                        + FreshnessNested.MAX_DEPTH + " 层下钻), 实得 " + flat.size());
        helper.succeed();
    }

    // ============================================================
    // 3. 脏 NBT 不抛
    // ============================================================

    /**
     * 读不出来就当没装东西: 标签类型不对、行不是复合标签、id 非法、缺 id —— 一律**不抛**。
     * 这条路同时服务只读预判与上架, 抛出去就是一个畸形潜影盒把面板打不开。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void malformedNestedTagsAreTreatedAsEmptyInsteadOfThrowing(GameTestHelper helper) {
        // BlockEntityTag 是字符串 (不是复合标签)。
        ItemStack stringTag = new ItemStack(Items.SHULKER_BOX);
        stringTag.getOrCreateTag().putString("BlockEntityTag", "not-a-compound");
        helper.assertTrue(FreshnessNested.flatten(stringTag).size() == 1, "读不出来就当空的 (只剩本体)");

        // Items 是 int (不是列表)。
        ItemStack intItems = new ItemStack(Items.SHULKER_BOX);
        intItems.getOrCreateTag().putInt("BlockEntityTag", 7);
        helper.assertTrue(FreshnessNested.flatten(intItems).size() == 1, "Items 类型不对也不抛");

        // Items 是一整张字符串列表 (元素类型不是复合标签): 一行都读不出来, 但也不抛。
        // NBT 的列表头只带一个元素类型, 所以"复合标签与字符串混在同一张列表里"这种形状根本无法编码 ——
        // 能构造出来的坏形状只有"整张列表类型不对"与"列表里某一行内容不对", 下面各测一种。
        ListTag strings = new ListTag();
        strings.add(StringTag.valueOf("garbage"));
        strings.add(StringTag.valueOf("more garbage"));
        helper.assertTrue(FreshnessNested.flatten(shulkerWith(strings)).size() == 1,
                "元素类型不对的列表当作没装东西");

        // 行内容不对: id 非法 + 缺 id + 一行好的 -> 只读得出那一行好的。
        ListTag rows = new ListTag();
        CompoundTag badId = new CompoundTag();
        badId.putString("id", "not a valid id !!");
        badId.putByte("Count", (byte) 1);
        rows.add(badId);
        CompoundTag noId = new CompoundTag();
        noId.putByte("Count", (byte) 3);
        rows.add(noId);
        rows.add(new ItemStack(Items.BREAD, 1).save(new CompoundTag()));
        List<ItemStack> flat = FreshnessNested.flatten(shulkerWith(rows));
        helper.assertTrue(flat.size() == 2, "两行坏数据 + 一行好数据 = 本体 + 那一行, 实得 " + flat.size());
        helper.assertTrue(flat.get(1).getItem() == Items.BREAD, "好行必须照读出来");
        helper.succeed();
    }

    // ============================================================
    // 4. 判定与盖章写回
    // ============================================================

    /**
     * 判定取"最坏的那一层": 最少的剩余分钟数、最早开始腐烂的时刻; 盖章必须**写回容器 NBT** ——
     * {@link ItemStack#of} 读出来的是副本, 只给副本盖章等于什么都没做 (托管期间那件东西永不腐烂)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void judgementsUseTheWorstLayerAndStampingWritesBack(GameTestHelper helper) {
        ItemStack ancient = breadRottenFor(10L * 24L * 60L);
        ItemStack recent = breadRottenFor(2L * 24L * 60L);
        ItemStack fresh = new ItemStack(Items.BREAD, 1);
        FreshnessData.stamp(fresh, NOW);

        ItemStack box = shulkerWith(listTagOf(ancient.save(new CompoundTag()),
                recent.save(new CompoundTag()), fresh.save(new CompoundTag())));
        long remaining = FreshnessNested.minRemainingMinutes(box, NOW);
        helper.assertTrue(remaining <= 0L, "盒子里有烂的, 最少剩余必须落在「烂」这一侧, 实得 " + remaining);
        helper.assertTrue(FreshnessNested.anyRotten(box, NOW), "盒子里有烂的 -> 整件算烂");
        long rottenSince = FreshnessNested.rottenSinceMinute(box, NOW);
        helper.assertTrue(rottenSince == NOW - 10L * 24L * 60L,
                "开始腐烂的时刻取**最早**那一层 (10 天前), 实得 " + (NOW - rottenSince) + " 分钟前");
        helper.assertTrue(FreshnessNested.rottenSinceMinute(shulkerWith(listTagOf(
                        fresh.save(new CompoundTag()))), NOW) == Long.MIN_VALUE,
                "没有烂层时是 MIN (哨兵), 不是 0 —— 0 会被当成「1970 年就烂了」");

        // 盖章: 两层没章的易腐品都要被盖上, 并且写回容器 (重新序列化后章还在)。
        ItemStack unbaked = shulkerWith(listTagOf(new ItemStack(Items.BREAD, 1).save(new CompoundTag()),
                new ItemStack(Items.COOKED_BEEF, 1).save(new CompoundTag())));
        int stamped = FreshnessNested.stampDeep(unbaked, NOW);
        helper.assertTrue(stamped == 2, "两层易腐品都该盖章, 实得 " + stamped);
        ItemStack reloaded = ItemStack.of(unbaked.save(new CompoundTag()));
        List<ItemStack> layers = FreshnessNested.flatten(reloaded);
        helper.assertTrue(layers.size() == 3, "重新读回来仍是 3 层, 实得 " + layers.size());
        for (int i = 1; i < layers.size(); i++) {
            helper.assertTrue(FreshnessData.isStamped(layers.get(i)),
                    "第 " + i + " 层的章必须写回容器 NBT (只给副本盖章 = 跑了但什么都没发生)");
        }
        helper.assertTrue(!FreshnessData.isStamped(layers.get(0)), "潜影盒自己不是易腐品, 不盖章");
        helper.assertTrue(FreshnessNested.stampDeep(unbaked, NOW + 600L) == 0,
                "已有章的层不该被重盖 (幂等)");
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 一件「已经烂了多少分钟」的面包。 */
    private static ItemStack breadRottenFor(long rottenMinutes) {
        ItemStack bread = new ItemStack(Items.BREAD, 1);
        int shelfLife = FreshnessCategories.shelfLifeHours(bread);
        FreshnessData.stampAt(bread, NOW - ((long) shelfLife * 60L + rottenMinutes), 0);
        return bread;
    }

    /** 原版方块实体容器的物品形态 (内容物在 {@code BlockEntityTag.Items})。 */
    private static ItemStack shulkerWith(ListTag items) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.put("Items", items);
        box.getOrCreateTag().put("BlockEntityTag", blockEntity);
        return box;
    }

    /** Farmer's Delight 厨锅的物品形态 (内容物在 {@code BlockEntityTag.Inventory.Items})。 */
    private static ItemStack kitchenPotWith(ListTag items) {
        ItemStack pot = new ItemStack(Items.CAULDRON, 1);
        CompoundTag inventory = new CompoundTag();
        inventory.put("Items", items);
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.put("Inventory", inventory);
        pot.getOrCreateTag().put("BlockEntityTag", blockEntity);
        return pot;
    }

    private static ListTag listTagOf(CompoundTag... entries) {
        ListTag items = new ListTag();
        for (int i = 0; i < entries.length; i++) {
            entries[i].putByte("Slot", (byte) i);
            items.add(entries[i]);
        }
        return items;
    }

    /** 展平结果里的内容物 (去掉排第一的本体)。 */
    private static List<ItemStack> contentsOf(List<ItemStack> flat) {
        return flat.subList(1, flat.size());
    }
}
