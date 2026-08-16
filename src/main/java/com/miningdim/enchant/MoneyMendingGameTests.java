package com.miningdim.enchant;

import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyLedger;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.economy.ShopPriceTable;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 金钱修补 GameTest。
 *
 * 主线是<b>无套利不变式</b>: 任何一件支持的装备, 把它从 0 修满的总花费必须高于它的材料总价。这条一破,
 * 附魔立刻变成"收破损装备 -> 修满 -> 转手卖"的印钞生意, 而这正是当初否掉"全局一口价"方案的原因 ——
 * 各类装备的每点耐久内含价值相差 50 倍以上。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MoneyMendingGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "money_mending";

    /** 一个下界合金锭的计价值 (4 残骸 + 4 金锭), 与实现同口径, 供期望值反推。 */
    private static long netheriteIngot() {
        return Math.round(ShopPriceTable.ORE_BASE_NETHERITE_SCRAP * 4.0D + ShopPriceTable.ORE_BASE_GOLD * 4.0D);
    }

    // ============================================================
    // 1. 定价模型
    // ============================================================

    /**
     * 单价按"材料总价 ÷ 最大耐久 × 倍率"逐件算出, 且各档差异真实存在。
     *
     * 把实现换成任何一个全局常数, 这四行里必然有几行挂 —— 它们的期望值彼此相差一个数量级以上, 正是
     * 单一常数无法同时满足的那种分布。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void repairUnitPriceIsDerivedPerItemNotAFlatConstant(GameTestHelper helper) {
        double multiplier = MoneyMendingConfig.PRICE_MULTIPLIER.get();

        assertUnitPrice(helper, Items.DIAMOND_PICKAXE,
                3 * ShopPriceTable.ORE_BASE_DIAMOND, multiplier, "钻石镐");
        assertUnitPrice(helper, Items.NETHERITE_PICKAXE,
                3 * ShopPriceTable.ORE_BASE_DIAMOND + netheriteIngot(), multiplier, "下界合金镐");
        assertUnitPrice(helper, Items.NETHERITE_HELMET,
                5 * ShopPriceTable.ORE_BASE_DIAMOND + netheriteIngot(), multiplier, "下界合金头盔");
        assertUnitPrice(helper, Items.GOLDEN_PICKAXE,
                3 * ShopPriceTable.ORE_BASE_GOLD, multiplier, "金镐");

        // 差异必须真实存在: 若某天有人把模型改回一口价, 这行会立刻挂。
        long diamondPick = RepairPricing.costPerDurability(new ItemStack(Items.DIAMOND_PICKAXE));
        long netheriteHelmet = RepairPricing.costPerDurability(new ItemStack(Items.NETHERITE_HELMET));
        helper.assertTrue(netheriteHelmet > diamondPick * 10L,
                "下界合金头盔的每点耐久单价应远高于钻石镐 (材料密度差一个数量级), 实得 "
                        + netheriteHelmet + " vs " + diamondPick);
        helper.succeed();
    }

    /**
     * <b>无套利不变式</b>: 逐件核对"修满总价 &gt; 材料总价"。
     *
     * 这是整个定价模型存在的理由。把倍率配成 1.0 或更低、或把公式换成不挂物品价值的常数, 本条必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void repairingFullDurabilityAlwaysCostsMoreThanTheMaterials(GameTestHelper helper) {
        record Case(Item item, double materialValue, String name) {
        }
        List<Case> cases = List.of(
                new Case(Items.DIAMOND_PICKAXE, 3 * ShopPriceTable.ORE_BASE_DIAMOND, "钻石镐"),
                new Case(Items.DIAMOND_CHESTPLATE, 8 * ShopPriceTable.ORE_BASE_DIAMOND, "钻石胸甲"),
                new Case(Items.DIAMOND_BOOTS, 4 * ShopPriceTable.ORE_BASE_DIAMOND, "钻石靴"),
                new Case(Items.NETHERITE_PICKAXE,
                        3 * ShopPriceTable.ORE_BASE_DIAMOND + netheriteIngot(), "下界合金镐"),
                new Case(Items.NETHERITE_HELMET,
                        5 * ShopPriceTable.ORE_BASE_DIAMOND + netheriteIngot(), "下界合金头盔"),
                new Case(Items.GOLDEN_SHOVEL, ShopPriceTable.ORE_BASE_GOLD, "金锹"),
                new Case(Items.IRON_SWORD, 2 * MoneyMendingConfig.IRON_UNIT_VALUE.get(), "铁剑"));

        for (Case c : cases) {
            long unit = RepairPricing.costPerDurability(new ItemStack(c.item()));
            helper.assertTrue(unit != RepairPricing.UNSUPPORTED, c.name() + " 应当被支持");
            long fullRepair = unit * c.item().getMaxDamage();
            helper.assertTrue(fullRepair > c.materialValue(),
                    c.name() + " 修满 (" + fullRepair + ") 必须比材料总价 (" + (long) c.materialValue()
                            + ") 贵, 否则收破损装修满转卖就是稳赚的套利");
        }
        helper.succeed();
    }

    /**
     * 算不出价的物品一律不支持, 且不支持是"附不上去", 不是"附上了悄悄不生效"。
     *
     * 给不认识的物品一个兜底费率是这套模型唯一的漏点 —— 只要某件高价装备落进偏低的默认档, 套利就成立。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unpricedItemsAreRejectedRatherThanGivenAFallbackRate(GameTestHelper helper) {
        List<Item> unsupported = List.of(
                Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.LEATHER_CHESTPLATE,
                Items.CHAINMAIL_CHESTPLATE, Items.TURTLE_HELMET, Items.BOW, Items.SHIELD);
        for (Item item : unsupported) {
            helper.assertTrue(!RepairPricing.supports(item),
                    item + " 无计价口径, 必须判为不支持");
            helper.assertTrue(RepairPricing.costPerDurability(new ItemStack(item)) == RepairPricing.UNSUPPORTED,
                    item + " 不支持时必须返回哨兵, 不得返回 0 (0 会变成免费修补)");
            helper.assertTrue(!ModEnchantments.MONEY_MENDING.get().canEnchant(new ItemStack(item)),
                    item + " 不支持时必须连附都附不上");
        }
        helper.assertTrue(ModEnchantments.MONEY_MENDING.get().canEnchant(new ItemStack(Items.DIAMOND_PICKAXE)),
                "支持的装备必须附得上");
        helper.succeed();
    }

    /** 与原版经济修补互斥: 共存时经验修补 (近乎免费) 永远先修完, 本附魔会变成一张废票。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void moneyMendingIsMutuallyExclusiveWithVanillaMending(GameTestHelper helper) {
        helper.assertTrue(!ModEnchantments.MONEY_MENDING.get().isCompatibleWith(Enchantments.MENDING),
                "金钱修补与原版经济修补必须互斥");
        helper.assertTrue(!Enchantments.MENDING.isCompatibleWith(ModEnchantments.MONEY_MENDING.get()),
                "互斥必须是双向的 (原版侧问也要答不兼容)");
        helper.assertTrue(ModEnchantments.MONEY_MENDING.get().isCompatibleWith(Enchantments.BLOCK_EFFICIENCY),
                "与效率这类无关附魔不该互斥");
        helper.succeed();
    }

    /** 只走任务发书: 附魔台附不出、村民不卖。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void moneyMendingIsQuestOnlyAndNotObtainableElsewhere(GameTestHelper helper) {
        helper.assertTrue(ModEnchantments.MONEY_MENDING.get().isTreasureOnly(), "必须是宝藏级附魔");
        helper.assertTrue(!ModEnchantments.MONEY_MENDING.get().isDiscoverable(), "附魔台不得附出");
        helper.assertTrue(!ModEnchantments.MONEY_MENDING.get().isTradeable(), "村民不得交易");
        helper.assertTrue(ModEnchantments.MONEY_MENDING.get().getMaxLevel() == 1, "单等级");
        helper.succeed();
    }

    // ============================================================
    // 2. 运行期结算
    // ============================================================

    /** 装备在身且余额充足: 按配置点数补耐久, 并精确扣掉 点数 x 单价。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void equippedGearIsRepairedAndChargedExactly(GameTestHelper helper) {
        IEconomyService previous = currentEconomy();
        registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            IEconomyService economy = EconomyServices.economyService();
            economy.grant(player, Currency.CREDIT, 1_000_000L);

            ItemStack pick = enchanted(Items.DIAMOND_PICKAXE);
            pick.setDamageValue(500);
            player.setItemSlot(EquipmentSlot.MAINHAND, pick);

            long unit = RepairPricing.costPerDurability(pick);
            int points = MoneyMendingConfig.REPAIR_POINTS_PER_SECOND.get();
            long balanceBefore = economy.creditBalance(player);

            MoneyMendingHandler.repairEquipped(player);

            helper.assertTrue(pick.getDamageValue() == 500 - points,
                    "应恰好补 " + points + " 点耐久, 实得损伤 " + pick.getDamageValue());
            helper.assertTrue(balanceBefore - economy.creditBalance(player) == unit * points,
                    "应恰好扣 点数 x 单价 = " + (unit * points) + ", 实扣 "
                            + (balanceBefore - economy.creditBalance(player)));
        } finally {
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    /**
     * 余额不足时按修得起的点数少修, 而不是一点不修; 余额为 0 则完全不动。
     *
     * 全有或全无会让一个余额将尽的玩家眼睁睁看着装备一路损坏到断, 而他明明还撑得住几点。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void partialBalanceRepairsPartiallyAndZeroBalanceRepairsNothing(GameTestHelper helper) {
        IEconomyService previous = currentEconomy();
        registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            IEconomyService economy = EconomyServices.economyService();

            ItemStack pick = enchanted(Items.DIAMOND_PICKAXE);
            pick.setDamageValue(500);
            player.setItemSlot(EquipmentSlot.MAINHAND, pick);
            long unit = RepairPricing.costPerDurability(pick);

            // 只给够修 3 点的钱 (且不足以修满配置点数)。
            helper.assertTrue(MoneyMendingConfig.REPAIR_POINTS_PER_SECOND.get() > 3,
                    "前提: 配置的每秒点数需大于 3 才能验出'少修'");
            economy.grant(player, Currency.CREDIT, unit * 3L);
            MoneyMendingHandler.repairEquipped(player);

            helper.assertTrue(pick.getDamageValue() == 497,
                    "余额只够 3 点时应恰好修 3 点, 实得损伤 " + pick.getDamageValue());
            helper.assertTrue(economy.creditBalance(player) == 0L,
                    "钱应正好花完, 实得余额 " + economy.creditBalance(player));

            // 余额归零后再跑一轮: 一点不修, 也不许把余额扣成负数。
            MoneyMendingHandler.repairEquipped(player);
            helper.assertTrue(pick.getDamageValue() == 497, "余额为 0 时不得修补");
            helper.assertTrue(economy.creditBalance(player) == 0L, "余额为 0 时不得扣款");
        } finally {
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    /** 只修已装备的六个槽; 背包里躺着的同款装备一点不动 (那会是玩家看不见的持续开销)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void onlyEquippedGearIsRepairedNotTheBackpack(GameTestHelper helper) {
        IEconomyService previous = currentEconomy();
        registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            EconomyServices.economyService().grant(player, Currency.CREDIT, 1_000_000L);
            player.getInventory().clearContent();

            ItemStack worn = enchanted(Items.DIAMOND_CHESTPLATE);
            worn.setDamageValue(200);
            player.setItemSlot(EquipmentSlot.CHEST, worn);

            ItemStack spare = enchanted(Items.DIAMOND_CHESTPLATE);
            spare.setDamageValue(200);
            // 槽位必须避开 0-8 的快捷栏: 快捷栏当前选中格就是主手, 放那里等于"装备着", 本用例就白写了。
            // 用 setItem 而不是 add: add 会把传入的 stack 消费掉 (拷贝进槽位后把原引用 count 置 0),
            // 之后再读那个引用只会读到空栈的 0 损伤 —— 那是假通过, 不是"没被修"。
            int backpackSlot = 9;
            player.getInventory().setItem(backpackSlot, spare);

            MoneyMendingHandler.repairEquipped(player);

            ItemStack inBackpack = player.getInventory().getItem(backpackSlot);
            helper.assertTrue(worn.getDamageValue() < 200, "穿在身上的必须被修");
            helper.assertTrue(!inBackpack.isEmpty() && inBackpack.getDamageValue() == 200,
                    "背包里的备用装备不得被修, 实得损伤 " + inBackpack.getDamageValue());
        } finally {
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    /** 没附本附魔的装备一分钱不扣, 一点不修。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gearWithoutTheEnchantmentIsUntouched(GameTestHelper helper) {
        IEconomyService previous = currentEconomy();
        registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            IEconomyService economy = EconomyServices.economyService();
            economy.grant(player, Currency.CREDIT, 1_000_000L);

            ItemStack plain = new ItemStack(Items.DIAMOND_PICKAXE);
            plain.setDamageValue(300);
            player.setItemSlot(EquipmentSlot.MAINHAND, plain);
            long before = economy.creditBalance(player);

            MoneyMendingHandler.repairEquipped(player);

            helper.assertTrue(plain.getDamageValue() == 300, "无附魔不得修补");
            helper.assertTrue(economy.creditBalance(player) == before, "无附魔不得扣款");
        } finally {
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    // ============================================================
    // 辅助
    // ============================================================

    private static ItemStack enchanted(Item item) {
        ItemStack stack = new ItemStack(item);
        stack.enchant(ModEnchantments.MONEY_MENDING.get(), 1);
        return stack;
    }

    private static void assertUnitPrice(GameTestHelper helper, Item item, double materialValue,
                                        double multiplier, String name) {
        long expected = Math.max(1L, (long) Math.ceil(materialValue / item.getMaxDamage() * multiplier));
        long actual = RepairPricing.costPerDurability(new ItemStack(item));
        helper.assertTrue(actual == expected,
                name + " 单价应为 " + expected + " (材料 " + (long) materialValue + " / 耐久 "
                        + item.getMaxDamage() + " x 倍率 " + multiplier + "), 实得 " + actual);
    }

    private static void registerFreshEconomy() {
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        Function<UUID, PlayerAbuseState> resolver = id -> states.computeIfAbsent(id, k -> new PlayerAbuseState());
        EconomyServices.reset();
        EconomyServices.registerEconomyService(new EconomyService(ledger, new AbuseGuard(), resolver));
    }

    private static IEconomyService currentEconomy() {
        return EconomyServices.isRegistered() ? EconomyServices.economyService() : null;
    }

    private static void restoreEconomy(IEconomyService previous) {
        EconomyServices.reset();
        if (previous != null) {
            EconomyServices.registerEconomyService(previous);
        }
    }
}
