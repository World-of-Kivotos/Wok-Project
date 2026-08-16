package com.miningdim.quest;

import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.EconomyLedger;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.enchant.ModEnchantments;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 任务物品奖励 GameTest。
 *
 * 三条主线: 档位天花板 (日常最高铁 / 周常最高钻)、装备恒为全新、以及附魔书池的构成 —— 头奖两张等权对半开、
 * 两条经济乘数被压到最低、废票附魔一张不留。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class QuestItemRewardGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "quest_rewards";

    // ============================================================
    // 1. 材料档位
    // ============================================================

    /**
     * 日常档最高到铁, 周常档最高到钻石且不含下界合金。
     *
     * 把哪一档的表接错 (比如 SPECIAL 误指周常表), 本条立刻挂 —— 特殊任务是随机事件抛的, 掉钻石装会让
     * "路过村庄"变成比周常还肥的口子。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dailyPoolTopsOutAtIronAndWeeklyAtDiamond(GameTestHelper helper) {
        for (QuestSource source : List.of(QuestSource.DAILY, QuestSource.SPECIAL)) {
            for (QuestItemRewards.Drop drop : QuestItemRewards.pool(source)) {
                helper.assertTrue(!isAtLeastGoldTier(drop),
                        source + " 档不得出现金/钻石/绿宝石级产出, 实见 " + drop.item());
            }
        }
        boolean weeklyHasDiamond = false;
        for (QuestSource source : List.of(QuestSource.WEEKLY, QuestSource.HIDDEN)) {
            for (QuestItemRewards.Drop drop : QuestItemRewards.pool(source)) {
                helper.assertTrue(!isNetherite(drop),
                        source + " 档不得出现下界合金 (那是玩家自己该挣的终局装备), 实见 " + drop.item());
                if (drop.item() == Items.DIAMOND || drop.item() == Items.DIAMOND_PICKAXE) {
                    weeklyHasDiamond = true;
                }
            }
        }
        helper.assertTrue(weeklyHasDiamond, "周常档应当真的能开出钻石级产出");
        helper.succeed();
    }

    /**
     * 材料表里不含<b>可放置的矿石方块</b>。
     *
     * 给矿石方块会让"挖掘某某矿石 xN"的任务被"放下去再挖掉"直接完成 —— 奖励帮玩家完成任务的循环。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void poolsNeverContainPlaceableOreBlocks(GameTestHelper helper) {
        List<net.minecraft.world.item.Item> forbidden = List.of(
                Blocks.IRON_ORE.asItem(), Blocks.DEEPSLATE_IRON_ORE.asItem(),
                Blocks.COAL_ORE.asItem(), Blocks.COPPER_ORE.asItem(),
                Blocks.GOLD_ORE.asItem(), Blocks.DIAMOND_ORE.asItem(),
                Blocks.REDSTONE_ORE.asItem(), Blocks.LAPIS_ORE.asItem(), Blocks.EMERALD_ORE.asItem());
        for (QuestSource source : QuestSource.values()) {
            for (QuestItemRewards.Drop drop : QuestItemRewards.pool(source)) {
                helper.assertTrue(!forbidden.contains(drop.item()),
                        "掉落表不得含可放置矿石方块 (放下去再挖掉就能刷挖矿任务), 实见 " + drop.item());
            }
        }
        helper.succeed();
    }

    /** 农作物只给原版的, 不给农夫的 mod 小麦 (那是农夫唯一的变现出口, 发它就是砸他的市场)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void poolsNeverContainTheFarmerModWheat(GameTestHelper helper) {
        var modWheat = com.miningdim.job.farmer.item.FarmerItems.FARMER_WHEAT.get();
        for (QuestSource source : QuestSource.values()) {
            for (QuestItemRewards.Drop drop : QuestItemRewards.pool(source)) {
                helper.assertTrue(drop.item() != modWheat,
                        "掉落表不得含农夫 mod 小麦, 实见 " + drop.item());
            }
        }
        helper.succeed();
    }

    /**
     * 每次领奖必得一份材料, 且装备类恒为全新 (主控 2026-08-17: 不掉半损的)。
     *
     * 用固定种子跑满量抽取: 只抽一次可能永远抽不到装备条目, 那种"通过"什么也没证明。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyRollYieldsOneBrandNewMaterial(GameTestHelper helper) {
        RandomSource random = RandomSource.create(20260817L);
        int equipmentSeen = 0;
        for (int i = 0; i < 400; i++) {
            QuestSource source = (i % 2 == 0) ? QuestSource.DAILY : QuestSource.WEEKLY;
            ItemStack stack = QuestItemRewards.rollMaterial(source, random);
            helper.assertTrue(!stack.isEmpty() && stack.getCount() >= 1,
                    "每次必得一份非空材料, 第 " + i + " 次实得 " + stack);
            if (stack.getItem() instanceof TieredItem || stack.getItem() instanceof ArmorItem) {
                equipmentSeen++;
                helper.assertTrue(stack.getDamageValue() == 0,
                        "装备类掉落必须全新, 实得损伤 " + stack.getDamageValue() + " (" + stack.getItem() + ")");
            }
        }
        helper.assertTrue(equipmentSeen > 0, "400 次抽取里应当抽到过装备条目, 否则上面那条断言等于没跑");
        helper.succeed();
    }

    // ============================================================
    // 2. 附魔书池
    // ============================================================

    /**
     * 头奖两张等权对半开: 经济修补与金钱修补同权同档。
     *
     * 这是主控指定的恶趣味 —— 终于开出一本, 是哪张全看脸。任一侧权重被调歪, 本条即挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mendingAndMoneyMendingShareTheSameJackpotWeight(GameTestHelper helper) {
        int mending = weightOf(Enchantments.MENDING);
        int moneyMending = weightOf(ModEnchantments.MONEY_MENDING.get());

        helper.assertTrue(mending > 0 && moneyMending > 0, "两张头奖都必须在池子里");
        helper.assertTrue(mending == moneyMending,
                "经济修补与金钱修补必须等权 (对半开), 实得 " + mending + " vs " + moneyMending);
        helper.succeed();
    }

    /**
     * 两条经济乘数 (时运/抢夺) 的权重必须明显低于常规实用附魔。
     *
     * 它们不是便利而是产出倍率 —— 时运抬高挖矿这条最大 faucet, 抢夺抬高怪物掉落。给它们和效率一样的权重
     * 等于用抽奖发经济倍率。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void economicMultiplierBooksAreRarerThanPlainUtility(GameTestHelper helper) {
        int efficiency = weightOf(Enchantments.BLOCK_EFFICIENCY);
        int fortune = weightOf(Enchantments.BLOCK_FORTUNE);
        int looting = weightOf(Enchantments.MOB_LOOTING);

        helper.assertTrue(efficiency > 0, "效率应当在池子里 (它是矿工的核心便利)");
        helper.assertTrue(fortune > 0 && looting > 0, "时运与抢夺仍应在池子里, 只是要更稀有");
        helper.assertTrue(fortune * 2 <= efficiency,
                "时运权重必须不到常规附魔的一半, 实得 " + fortune + " vs 效率 " + efficiency);
        helper.assertTrue(looting * 2 <= efficiency,
                "抢夺权重必须不到常规附魔的一半, 实得 " + looting + " vs 效率 " + efficiency);
        helper.succeed();
    }

    /** 废票附魔一张不留: 弓弩/三叉戟/钓鱼/诅咒/鸡肋近战全部剔除。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bookPoolExcludesDeadWeightEnchantments(GameTestHelper helper) {
        List<Enchantment> excluded = List.of(
                // 弓弩系: 本服主战武器是 TaCZ 枪械, 开出来纯粹是被耍。
                Enchantments.POWER_ARROWS, Enchantments.PUNCH_ARROWS, Enchantments.FLAMING_ARROWS,
                Enchantments.INFINITY_ARROWS, Enchantments.MULTISHOT, Enchantments.QUICK_CHARGE,
                Enchantments.PIERCING,
                // 三叉戟系: 玩法里没有三叉戟。
                Enchantments.LOYALTY, Enchantments.IMPALING, Enchantments.RIPTIDE, Enchantments.CHANNELING,
                // 钓鱼系: 空军职业尚未落地。
                Enchantments.FISHING_LUCK, Enchantments.FISHING_SPEED,
                // 两条诅咒: 开出来是负收益。
                Enchantments.BINDING_CURSE, Enchantments.VANISHING_CURSE,
                // 鸡肋或反效果的近战。
                Enchantments.BANE_OF_ARTHROPODS, Enchantments.SMITE,
                Enchantments.KNOCKBACK, Enchantments.FIRE_ASPECT);
        for (Enchantment enchantment : excluded) {
            helper.assertTrue(weightOf(enchantment) == 0,
                    "废票附魔不得进池: " + enchantment.getDescriptionId());
        }
        helper.assertTrue(QuestItemRewards.bookPool().size() >= 10, "池子仍应有足够多的有效附魔");
        helper.succeed();
    }

    /** 掷不中时返回空栈而不是 null; 掷中时是一本单附魔的书。掉率恒为 0 与恒为 1 两个边界都验。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bookRollRespectsChanceBoundsAndYieldsASingleEnchantBook(GameTestHelper helper) {
        RandomSource random = RandomSource.create(4242L);
        int books = 0;
        for (int i = 0; i < 200; i++) {
            ItemStack book = QuestItemRewards.rollBook(QuestSource.WEEKLY, random);
            if (!book.isEmpty()) {
                books++;
                helper.assertTrue(book.getItem() == Items.ENCHANTED_BOOK,
                        "掷中时必须是附魔书, 实得 " + book.getItem());
                helper.assertTrue(net.minecraft.world.item.EnchantedBookItem.getEnchantments(book).size() == 1,
                        "每本只带一条附魔, 实得 "
                                + net.minecraft.world.item.EnchantedBookItem.getEnchantments(book).size());
            }
        }
        // 周常 30% 掉率下 200 次几乎不可能一本不出, 也不可能本本都出; 只作"概率闸真的接上了"的粗判。
        helper.assertTrue(books > 0 && books < 200,
                "周常掉率应当既不是 0 也不是 1, 200 次实得 " + books + " 本");
        helper.succeed();
    }

    // ============================================================
    // 3. 端到端: 领奖真把物品发到手
    // ============================================================

    /** 领奖回执带上物品, 且物品真的进了背包。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void claimingAQuestPutsTheRewardItemsInTheInventory(GameTestHelper helper) {
        IEconomyService previous = currentEconomy();
        registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            player.getInventory().clearContent();
            QuestPool pool = QuestPool.builtin();
            QuestService service = new QuestService(pool);
            QuestDefinition definition = pool.byId("daily.mine.iron");

            QuestBoard board = service.boardOf(player);
            board.restorePeriodic(QuestClock.currentUtcDayStamp(), List.of(new QuestProgress(definition)),
                    QuestClock.currentUtcWeekStamp(), List.of());
            for (int i = 0; i < definition.objective().requiredCount(); i++) {
                service.record(new QuestFacts.BlockMine(player, Blocks.IRON_ORE.defaultBlockState()));
            }

            QuestService.ClaimResult result = service.claim(player, definition.id());
            helper.assertTrue(result.outcome() == QuestService.ClaimOutcome.CLAIMED,
                    "达标应可领取, 实得 " + result.outcome());
            helper.assertTrue(!result.items().isEmpty(), "领奖回执必须带上物品奖励");

            for (ItemStack awarded : result.items()) {
                helper.assertTrue(countInInventory(player, awarded) >= awarded.getCount(),
                        "奖励物品必须真的进背包: " + awarded);
            }
        } finally {
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    // ============================================================
    // 辅助
    // ============================================================

    private static int weightOf(Enchantment enchantment) {
        for (QuestItemRewards.BookDrop book : QuestItemRewards.bookPool()) {
            if (book.enchantment() == enchantment) {
                return book.weight();
            }
        }
        return 0;
    }

    private static boolean isAtLeastGoldTier(QuestItemRewards.Drop drop) {
        var item = drop.item();
        return item == Items.GOLD_INGOT || item == Items.DIAMOND || item == Items.EMERALD
                || item instanceof TieredItem tiered
                        && (tiered.getTier() == net.minecraft.world.item.Tiers.GOLD
                                || tiered.getTier() == net.minecraft.world.item.Tiers.DIAMOND
                                || tiered.getTier() == net.minecraft.world.item.Tiers.NETHERITE)
                || item instanceof ArmorItem armor
                        && (armor.getMaterial() == net.minecraft.world.item.ArmorMaterials.GOLD
                                || armor.getMaterial() == net.minecraft.world.item.ArmorMaterials.DIAMOND
                                || armor.getMaterial() == net.minecraft.world.item.ArmorMaterials.NETHERITE);
    }

    private static boolean isNetherite(QuestItemRewards.Drop drop) {
        var item = drop.item();
        return item == Items.NETHERITE_INGOT || item == Items.NETHERITE_SCRAP
                || item instanceof TieredItem tiered
                        && tiered.getTier() == net.minecraft.world.item.Tiers.NETHERITE
                || item instanceof ArmorItem armor
                        && armor.getMaterial() == net.minecraft.world.item.ArmorMaterials.NETHERITE;
    }

    private static int countInInventory(ServerPlayer player, ItemStack sample) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && stack.getItem() == sample.getItem()) {
                total += stack.getCount();
            }
        }
        return total;
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
