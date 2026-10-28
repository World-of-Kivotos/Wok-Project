package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraftforge.event.village.WandererTradesEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 村民货的 GameTest (docs/Spoilage_Granary_DesignSpec.md「村民」「观察点」村民交易那一行,
 * 反漏洞 #46 村民食物银行与 #54 界面里 Shift 绕过 6 小时)。
 *
 * 锁四件事: 报价模板生成出来的易腐产物带村民货标记 (不腐烂的产物不标)、**报价那一刻不动章**
 * (开服几小时后买的东西不能一到手就是烂的)、到手时才按剩 6 小时盖章、以及这个换算是幂等的
 * (不能被每次结算重置回 6 小时, 否则村民货永远不会烂)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessVillagerGoodsGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "freshness_villager";

    /** 一个固定的「现在」(不依赖实际挂钟, 断言只看相对关系)。 */
    private static final long NOW = 500_000L * 60L;

    // ============================================================
    // 报价: 只打标记, 不动章
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tradeListingsMarkPerishableResults(GameTestHelper helper) {
        // 面包 (易腐) 与石头 (不腐烂) 两条报价。
        List<VillagerTrades.ItemListing> listings = new ArrayList<>(List.of(
                new FixedListing(new ItemStack(Items.BREAD, 3)),
                new FixedListing(new ItemStack(Items.STONE, 8))));
        it.unimi.dsi.fastutil.ints.Int2ObjectMap<List<VillagerTrades.ItemListing>> trades =
                new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
        trades.put(1, listings);
        FreshnessVillagerGoods.onVillagerTrades(new net.minecraftforge.event.village.VillagerTradesEvent(
                trades, net.minecraft.world.entity.npc.VillagerProfession.FARMER));

        MerchantOffer bread = listings.get(0).getOffer(null, RandomSource.create(1L));
        MerchantOffer stone = listings.get(1).getOffer(null, RandomSource.create(1L));
        helper.assertTrue(bread != null && FreshnessData.isVillagerGoods(bread.getResult()),
                "面包报价的产物要带村民货标记");
        helper.assertTrue(stone != null && !FreshnessData.isVillagerGoods(stone.getResult()),
                "石头不是易腐品, 不打标记");
        helper.assertTrue(!FreshnessData.isStamped(bread.getResult()),
                "报价那一刻**不盖章** (开服几小时后买的东西不能一到手就是烂的)");

        // 流浪商人的两条列表同样处理。
        List<VillagerTrades.ItemListing> generic = new ArrayList<>(List.of(
                new FixedListing(new ItemStack(Items.COOKED_BEEF, 2))));
        List<VillagerTrades.ItemListing> rare = new ArrayList<>(List.of(
                new FixedListing(new ItemStack(Items.SWEET_BERRIES, 4))));
        FreshnessVillagerGoods.onWandererTrades(new WandererTradesEvent(generic, rare));
        helper.assertTrue(FreshnessData.isVillagerGoods(
                        generic.get(0).getOffer(null, RandomSource.create(2L)).getResult()),
                "流浪商人的普通报价也要打标记");
        helper.assertTrue(FreshnessData.isVillagerGoods(
                        rare.get(0).getOffer(null, RandomSource.create(3L)).getResult()),
                "流浪商人的稀有报价也要打标记");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void existingVillagersGetMarkedWhenTheyJoinTheWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        net.minecraft.world.entity.npc.Villager villager =
                new net.minecraft.world.entity.npc.Villager(net.minecraft.world.entity.EntityType.VILLAGER, level);
        MerchantOffer bread = new MerchantOffer(new ItemStack(Items.EMERALD, 1),
                new ItemStack(Items.BREAD, 2), 12, 1, 0.05F);
        MerchantOffer stone = new MerchantOffer(new ItemStack(Items.EMERALD, 1),
                new ItemStack(Items.STONE, 4), 12, 1, 0.05F);
        villager.getOffers().add(bread);
        villager.getOffers().add(stone);

        // 老村民 (存档里带过来的报价) 进世界时补一次标记。
        FreshnessVillagerGoods.onEntityJoinLevel(
                new net.minecraftforge.event.entity.EntityJoinLevelEvent(villager, level));
        helper.assertTrue(FreshnessData.isVillagerGoods(bread.getResult()), "已有的报价补上了村民货标记");
        helper.assertTrue(!FreshnessData.isVillagerGoods(stone.getResult()), "不腐烂的产物不标记");
        helper.assertTrue(!FreshnessData.isStamped(bread.getResult()), "补标记时同样不动章");
        helper.assertTrue(FreshnessVillagerGoods.markOffers(villager) == 0, "再扫一遍是幂等的");
        villager.discard();
        helper.succeed();
    }
    // ============================================================
    // 到手: 按剩 6 小时盖章
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void handOverSqueezesToSixHoursAndIsIdempotent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 村民货: 只有标记、没有章 (报价那一刻的样子)。
        ItemStack bread = new ItemStack(Items.BREAD, 1);
        FreshnessData.markVillagerGoodsFlag(bread);
        helper.assertTrue(!FreshnessData.isStamped(bread), "到手之前没有章");

        // 结算 (背包扫描、登录、开容器、右键、掉落物都走这一条) → 压到剩 6 小时。
        FreshnessSettle.settleSlot(bread, NOW, false, null);
        helper.assertTrue(FreshnessData.isStamped(bread), "到手时盖章");
        int shelfLife = FreshnessCategories.shelfLifeHours(bread);
        long remaining = FreshnessData.remainingMinutes(bread, shelfLife, NOW);
        helper.assertTrue(remaining == FreshnessConstants.VILLAGER_GOODS_HOURS * 60L,
                "剩余正好 6 小时, 实际 " + remaining + " 分钟");

        // 幂等: 过一小时再结算, 剩余只会更少, 不会被重置回 6 小时。
        long later = NOW + 60L;
        FreshnessSettle.settleSlot(bread, later, false, null);
        long remainingLater = FreshnessData.remainingMinutes(bread, shelfLife, later);
        helper.assertTrue(remainingLater == FreshnessConstants.VILLAGER_GOODS_HOURS * 60L - 60L,
                "一小时之后剩 5 小时, 实际 " + remainingLater + " 分钟");

        // 普通货 (没有标记) 不吃这一套: 盖「现在」的章, 剩余是一整个保质期。
        ItemStack normal = new ItemStack(Items.BREAD, 1);
        FreshnessSettle.settleSlot(normal, NOW, false, null);
        helper.assertTrue(FreshnessData.remainingMinutes(normal, shelfLife, NOW) == (long) shelfLife * 60L,
                "普通面包照旧按整段保质期算");

        // 已经烂掉的村民货: 不压 (剩余 < 6 小时), 照常走腐烂那一支。
        ItemStack rotten = new ItemStack(Items.BREAD, 1);
        FreshnessData.markVillagerGoodsFlag(rotten);
        FreshnessData.stampAt(rotten, NOW - (long) shelfLife * 60L - 60L, 0);
        helper.assertTrue(!FreshnessVillagerGoods.applyOnHandOver(rotten, NOW), "已经烂了就不再压时间");
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 一条固定的报价模板 (产物写死, 不看随机)。 */
    private record FixedListing(ItemStack result) implements VillagerTrades.ItemListing {

        @Override
        public MerchantOffer getOffer(Entity trader, RandomSource random) {
            return new MerchantOffer(new ItemStack(Items.EMERALD, 1), result.copy(), 12, 1, 0.05F);
        }
    }

}
