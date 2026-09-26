package com.miningdim.achievement.datagen;

import com.miningdim.achievement.AchievementIds;
import com.miningdim.achievement.tier.AchievementTier;
import com.miningdim.achievement.trigger.AchievementCountTrigger;
import com.miningdim.achievement.trigger.AchievementStats;
import com.miningdim.achievement.trigger.AchievementTriggers;
import com.miningdim.achievement.trigger.AgentSealTrigger;
import com.miningdim.achievement.trigger.BrewCompleteTrigger;
import com.miningdim.achievement.trigger.CaseOpenTrigger;
import com.miningdim.achievement.trigger.ChampionKillTrigger;
import com.miningdim.achievement.trigger.ChefDishTrigger;
import com.miningdim.achievement.trigger.EnterMiningTrigger;
import com.miningdim.achievement.trigger.GunKillTrigger;
import com.miningdim.achievement.trigger.JobLevelTrigger;
import com.miningdim.achievement.trigger.JobStats;
import com.miningdim.achievement.trigger.JobTriggers;
import com.miningdim.achievement.trigger.MarketTradeTrigger;
import com.miningdim.achievement.trigger.MineOreTrigger;
import com.miningdim.achievement.trigger.MiningExtractionTrigger;
import com.miningdim.achievement.trigger.NanoPlateProducedTrigger;
import com.miningdim.achievement.trigger.QuestCompleteTrigger;
import com.miningdim.achievement.trigger.SpouseTeleportTrigger;
import com.miningdim.achievement.trigger.StatAtLeastTrigger;
import com.miningdim.achievement.trigger.TarotPlayTrigger;
import com.miningdim.caseopening.CaseRarity;
import com.miningdim.champion.AffixDef;
import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.JobId;
import com.miningdim.job.JobXpCurve;
import com.miningdim.job.brewer.WineQuality;
import com.miningdim.job.brewer.WineType;
import com.miningdim.job.chef.ChefQuality;
import com.miningdim.ore.OreType;
import com.miningdim.quest.QuestPool;
import com.miningdim.quest.QuestSource;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.advancements.critereon.ConsumeItemTrigger;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DamageSourcePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.InventoryChangeTrigger;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.advancements.critereon.KilledTrigger;
import net.minecraft.advancements.critereon.LocationPredicate;
import net.minecraft.advancements.critereon.LootTableTrigger;
import net.minecraft.advancements.critereon.PlayerTrigger;
import net.minecraft.advancements.critereon.TagPredicate;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.miningdim.achievement.tier.AchievementTier.BRONZE;
import static com.miningdim.achievement.tier.AchievementTier.DIAMOND;
import static com.miningdim.achievement.tier.AchievementTier.GOLD;
import static com.miningdim.achievement.tier.AchievementTier.LEGEND;
import static com.miningdim.achievement.tier.AchievementTier.MASTER;
import static com.miningdim.achievement.tier.AchievementTier.PLATINUM;
import static com.miningdim.achievement.tier.AchievementTier.SILVER;

/**
 * 首批成就的 P1 部分 (Achievement_System_DesignSpec 第九章、第十三章 P1 行) 与六个页签根 (9.7), 是 datagen 的唯一
 * 声明表。
 *
 * <p>{@code combat/star_10} 随世界 BOSS 事件开放, 已在 P1 的战斗页签里。P2 成就按页签各占一个方法
 * ({@link #p2Jobs()}: 职业; {@link #p2Social()}: 经济、社交与两条成就数量成就), 每个方法在 {@link #p1()} 里接一行;
 * 其余 P2 成就 (如钓鱼三条) 等各模块的监听接口 (9.10) 落地后再加。
 *
 * <p>物品图标与条件里的物品按 id 从注册表取, 不直接引用各职业模块的物品类, 免得为了一个图标在模块间多出一条依赖;
 * 取不到时 datagen 直接失败。
 */
final class AchievementDeclarations {

    /** "命定之死"处决伤害的标签 (6.2), 值为精英怪模块的 {@code miningdim:champion_execution}。 */
    static final TagKey<DamageType> CHAMPION_EXECUTION =
            TagKey.create(Registries.DAMAGE_TYPE, AchievementIds.id("is_champion_execution"));

    private static final String TACZ = "tacz";
    private static final ResourceLocation DUNGEON_CHEST_LOOT =
            new ResourceLocation("minecraft", "chests/simple_dungeon");
    /** 五种矿石鱼羹 (渔夫模块的物品 id)。 */
    private static final List<String> ORE_FISH_SOUPS = List.of("iron_ore_fish_soup", "gold_ore_fish_soup",
            "diamond_ore_fish_soup", "emerald_ore_fish_soup", "dark_gold_ore_fish_soup");

    private AchievementDeclarations() {
    }

    /** 按声明顺序排列, 父进度一律排在子进度之前。 */
    static List<AchievementDeclaration> p1() {
        List<AchievementDeclaration> all = new ArrayList<>();
        addRoots(all);
        addMining(all);
        addCombat(all);
        addProfession(all);
        all.addAll(p2Jobs());
        addSocial(all);
        addMeta(all);
        all.addAll(p2Social());
        return all;
    }

    /** 9.7 页签根: 首次登录由 minecraft:tick 自动获得; 背景 P1 借用原版方块贴图。 */
    private static void addRoots(List<AchievementDeclaration> all) {
        all.add(root("mining", Items.DEEPSLATE_IRON_ORE, "deepslate"));
        all.add(root("combat", Items.SHIELD, "blackstone"));
        all.add(root("profession", Items.CRAFTING_TABLE, "spruce_planks"));
        all.add(root("economy", Items.EMERALD, "bricks"));
        all.add(root("social", Items.BELL, "cherry_planks"));
        all.add(root("meta", Items.KNOWLEDGE_BOOK, "quartz_block_side"));
    }

    /** 9.1 矿区, 14 条。 */
    private static void addMining(List<AchievementDeclaration> all) {
        all.add(achievement("mining/first_entry", BRONZE, "mining/root", modItem("entrance_easy"))
                .criterion("entered", EnterMiningTrigger.TriggerInstance.any()).build());
        all.add(achievement("mining/first_extraction", BRONZE, "mining/first_entry", Items.LANTERN)
                .criterion("extracted", MiningExtractionTrigger.TriggerInstance.any()).build());
        all.add(achievement("mining/blocks_1k", BRONZE, "mining/first_entry", Items.STONE_PICKAXE)
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(
                        AchievementStats.MINING_BLOCKS_MINED.get(), 1_000)).build());
        all.add(achievement("mining/trap_sprung", BRONZE, "mining/first_entry", modItem("fake_ore")).hidden()
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(
                        AchievementStats.MINING_TRAPS_SPRUNG.get(), 1)).build());
        all.add(achievement("mining/deep_regular", SILVER, "mining/first_extraction", Items.IRON_PICKAXE)
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(
                        AchievementStats.MINING_EXTRACTIONS.get(), 20)).build());
        all.add(achievement("mining/medium_extraction", SILVER, "mining/first_extraction", modItem("entrance_medium"))
                .criterion("extracted", MiningExtractionTrigger.TriggerInstance.in(Difficulty.MEDIUM)).build());
        all.add(achievement("mining/narrow_escape", SILVER, "mining/first_extraction", Items.GOLDEN_APPLE).hidden()
                .criterion("extracted", MiningExtractionTrigger.TriggerInstance.narrowEscape(0.10D, 600L)).build());
        all.add(achievement("mining/dungeon_chest", SILVER, "mining/first_entry", Items.CHEST)
                .criterion("opened", new LootTableTrigger.TriggerInstance(inMiningDimension(), DUNGEON_CHEST_LOOT))
                .build());
        all.add(achievement("mining/hard_extraction", GOLD, "mining/medium_extraction", modItem("entrance_hard"))
                .criterion("extracted", MiningExtractionTrigger.TriggerInstance.in(Difficulty.HARD)).build());
        all.add(achievement("mining/blocks_10k", GOLD, "mining/blocks_1k", Items.DIAMOND_PICKAXE)
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(
                        AchievementStats.MINING_BLOCKS_MINED.get(), 10_000)).build());
        Builder codex = achievement("mining/ore_codex", GOLD, "mining/hard_extraction", modItem("raw_tungsten"))
                .withTitle();
        for (OreType ore : OreType.values()) {
            codex.criterion(MineOreTrigger.oreId(ore), MineOreTrigger.TriggerInstance.of(ore));
        }
        all.add(codex.build());
        all.add(achievement("mining/hard_veteran", PLATINUM, "mining/hard_extraction", Items.DEEPSLATE_DIAMOND_ORE)
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(
                        AchievementStats.MINING_EXTRACTIONS_HARD.get(), 25)).build());
        all.add(achievement("mining/blocks_100k", DIAMOND, "mining/blocks_10k", Items.NETHERITE_PICKAXE).withTitle()
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(
                        AchievementStats.MINING_BLOCKS_MINED.get(), 100_000)).build());
        all.add(achievement("mining/hard_active_100h", MASTER, "mining/hard_veteran", Items.ANCIENT_DEBRIS).withTitle()
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(
                        AchievementStats.MINING_HARD_ACTIVE_TICKS.get(), 7_200_000)).build());
    }

    /**
     * 9.2 战斗, 11 条。{@code combat/star_10} 只能在世界 BOSS 身上达成: 10 星精英只由管理员命令
     * {@code /mchampion worldboss} 召唤, 困难矿区自然刷出的上限是 9 星。
     */
    private static void addCombat(List<AchievementDeclaration> all) {
        all.add(achievement("combat/first_champion", BRONZE, "combat/root", Items.IRON_SWORD)
                .criterion("defeated", ChampionKillTrigger.TriggerInstance.minStar(1)).build());
        all.add(achievement("combat/gun_100", BRONZE, "combat/root", modItem("bullet_head")).requires(TACZ)
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(AchievementStats.GUN_KILLS.get(), 100))
                .build());
        all.add(achievement("combat/executed", BRONZE, "combat/first_champion", Items.WITHER_SKELETON_SKULL).hidden()
                .criterion("executed", KilledTrigger.TriggerInstance.entityKilledPlayer(EntityPredicate.ANY,
                        DamageSourcePredicate.Builder.damageType().tag(TagPredicate.is(CHAMPION_EXECUTION))))
                .build());
        all.add(achievement("combat/headshot_100", SILVER, "combat/gun_100", Items.TARGET).requires(TACZ)
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(
                        AchievementStats.GUN_HEADSHOT_KILLS.get(), 100)).build());
        all.add(achievement("combat/giant_slayer", SILVER, "combat/first_champion", Items.ZOMBIE_HEAD)
                .criterion("defeated", ChampionKillTrigger.TriggerInstance.withAffix(AffixDef.GIGANTISM)).build());
        all.add(achievement("combat/champion_100", GOLD, "combat/first_champion", Items.DIAMOND_SWORD)
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(AchievementStats.CHAMPION_KILLS.get(), 100))
                .build());
        all.add(achievement("combat/star_6", GOLD, "combat/first_champion", Items.GOLDEN_SWORD)
                .criterion("defeated", ChampionKillTrigger.TriggerInstance.minStar(6)).build());
        all.add(achievement("combat/long_shot", GOLD, "combat/headshot_100", Items.SPYGLASS).hidden().withTitle()
                .requires(TACZ)
                .criterion("shot", GunKillTrigger.TriggerInstance.headshotBeyond(100.0D)).build());
        all.add(achievement("combat/star_7", PLATINUM, "combat/star_6", Items.NETHERITE_SWORD)
                .criterion("defeated", ChampionKillTrigger.TriggerInstance.minStar(7)).build());
        all.add(achievement("combat/star_10", DIAMOND, "combat/star_7", Items.DRAGON_HEAD).withTitle()
                .criterion("defeated", ChampionKillTrigger.TriggerInstance.minStarWithShare(10, 0.05D)).build());
        all.add(achievement("combat/solo_star_9", LEGEND, "combat/star_7", Items.END_CRYSTAL).withTitle()
                .criterion("defeated", ChampionKillTrigger.TriggerInstance.soloWithin(9, 18_000L)).build());
    }

    /** 9.3 职业里唯一的 P1 条目。 */
    private static void addProfession(List<AchievementDeclaration> all) {
        Item[] soups = ORE_FISH_SOUPS.stream().map(AchievementDeclarations::modItem).toArray(Item[]::new);
        all.add(achievement("profession/ore_soup_in_mine", BRONZE, "profession/root", modItem("iron_ore_fish_soup"))
                .criterion("consumed", new ConsumeItemTrigger.TriggerInstance(inMiningDimension(),
                        ItemPredicate.Builder.item().of(soups).build())).build());
    }

    /**
     * 9.3 职业的 P2 部分, 17 条: 职业等级五条、农夫收获三条, 以及厨师、酿酒、塔罗、特勤、纳米板、弹药。钓鱼三条
     * ({@code fishing_trophy}、{@code journal_50}、{@code journal_100}) 等渔夫模块的监听接口落地后再加。
     */
    private static List<AchievementDeclaration> p2Jobs() {
        List<AchievementDeclaration> jobs = new ArrayList<>();
        jobs.add(achievement("profession/level_2", BRONZE, "profession/root", Items.EXPERIENCE_BOTTLE)
                .criterion("reached", JobLevelTrigger.TriggerInstance.anyJob(2)).build());
        jobs.add(achievement("profession/level_4", SILVER, "profession/level_2", Items.BOOK)
                .criterion("reached", JobLevelTrigger.TriggerInstance.anyJob(4)).build());
        jobs.add(achievement("profession/level_7", GOLD, "profession/level_4", Items.ENCHANTED_BOOK)
                .criterion("reached", JobLevelTrigger.TriggerInstance.anyJob(7)).build());
        jobs.add(achievement("profession/max_level", PLATINUM, "profession/level_7", Items.ENCHANTING_TABLE)
                .withTitle().criterion("reached", JobLevelTrigger.TriggerInstance.anyJob(JobXpCurve.MAX_LEVEL))
                .build());
        jobs.add(achievement("profession/all_max", MASTER, "profession/max_level", Items.BEACON).withTitle()
                .criterion("reached", JobLevelTrigger.TriggerInstance.jobsAtLeast(JobXpCurve.MAX_LEVEL,
                        JobId.values().length)).build());
        jobs.add(achievement("profession/farmer_first_harvest", BRONZE, "profession/root", modItem("farmer_seed"))
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(JobStats.FARMER_HARVESTS.get(), 1))
                .build());
        jobs.add(achievement("profession/farmer_harvest_500", SILVER, "profession/farmer_first_harvest",
                modItem("farmer_wheat"))
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(JobStats.FARMER_HARVESTS.get(), 500))
                .build());
        jobs.add(achievement("profession/farmer_harvest_5000", GOLD, "profession/farmer_harvest_500", Items.HAY_BLOCK)
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(JobStats.FARMER_HARVESTS.get(), 5_000))
                .build());
        jobs.add(achievement("profession/chef_first_dish", BRONZE, "profession/root", modItem("seasoning_table_low"))
                .criterion("cooked", ChefDishTrigger.TriggerInstance.any()).build());
        jobs.add(achievement("profession/chef_radiant", SILVER, "profession/chef_first_dish",
                modItem("seasoning_table_radiant"))
                .criterion("cooked", ChefDishTrigger.TriggerInstance.atLeast(ChefQuality.RADIANT)).build());
        jobs.add(achievement("profession/brewer_first_brew", BRONZE, "profession/root", modItem("brewing_station"))
                .criterion("brewed", BrewCompleteTrigger.TriggerInstance.any()).build());
        Builder nineWines = achievement("profession/brewer_nine_wines", SILVER, "profession/brewer_first_brew",
                modItem("wine_cellar"));
        for (WineType type : WineType.values()) {
            nineWines.criterion(type.id(), BrewCompleteTrigger.TriggerInstance.ofType(type));
        }
        jobs.add(nineWines.build());
        jobs.add(achievement("profession/brewer_brilliant", PLATINUM, "profession/brewer_nine_wines",
                modItem("wine_maotai"))
                .criterion("brewed", BrewCompleteTrigger.TriggerInstance.atLeast(WineQuality.BRILLIANT)).build());
        jobs.add(achievement("profession/first_tarot", BRONZE, "profession/root", modItem("tarot_pack_common"))
                .criterion("played", TarotPlayTrigger.TriggerInstance.any()).build());
        jobs.add(achievement("profession/agent_first_seal", SILVER, "profession/root", Items.CHAIN)
                .criterion("sealed", AgentSealTrigger.TriggerInstance.any()).build());
        jobs.add(achievement("profession/first_nano_plate", BRONZE, "profession/root", modItem("nano_plate_low"))
                .criterion("produced", NanoPlateProducedTrigger.TriggerInstance.any()).build());
        jobs.add(achievement("profession/first_ammo", BRONZE, "profession/root", modItem("munitions_bench"))
                .criterion("produced", new PlayerTrigger.TriggerInstance(JobTriggers.MUNITIONS_BATCH.getId(),
                        ContextAwarePredicate.ANY)).build());
        return jobs;
    }

    /** 9.5 社交里的婚姻三条。 */
    private static void addSocial(List<AchievementDeclaration> all) {
        all.add(achievement("social/engagement_ring", BRONZE, "social/root", modItem("engagement_ring"))
                .criterion("obtained", InventoryChangeTrigger.TriggerInstance.hasItems(modItem("engagement_ring")))
                .build());
        all.add(achievement("social/married", SILVER, "social/engagement_ring", modItem("wedding_ring"))
                .criterion("married", new PlayerTrigger.TriggerInstance(AchievementTriggers.MARRIED.getId(),
                        ContextAwarePredicate.ANY)).build());
        all.add(achievement("social/shared_backpack", BRONZE, "social/married", Items.PINK_SHULKER_BOX)
                .criterion("opened", new PlayerTrigger.TriggerInstance(AchievementTriggers.OPEN_SHARED_BACKPACK.getId(),
                        ContextAwarePredicate.ANY)).build());
    }

    /** 9.6 成就页签: P1 只开放"获得 10 个成就"。 */
    private static void addMeta(List<AchievementDeclaration> all) {
        all.add(achievement("meta/count_10", SILVER, "meta/root", Items.AMETHYST_SHARD)
                .criterion("earned", AchievementCountTrigger.TriggerInstance.atLeast(10)).build());
    }

    /**
     * P2 的经济与社交 (9.4 全部 11 条、9.5 的 7 条 P2 行) 与随 P2 开放的两条成就数量成就 (9.6), 共 20 条。父进度都在
     * P1 部分或本方法前面, 所以整段接在 P1 声明之后。开箱三条与"神射手"依赖 TaCZ。
     */
    static List<AchievementDeclaration> p2Social() {
        List<AchievementDeclaration> all = new ArrayList<>();
        all.add(achievement("economy/first_paycheck", BRONZE, "economy/root", Items.GOLD_NUGGET)
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(
                        AchievementStats.CREDITS_EARNED.get(), 10_000)).build());
        all.add(achievement("economy/income_300k", SILVER, "economy/first_paycheck", Items.GOLD_INGOT)
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(
                        AchievementStats.CREDITS_EARNED.get(), 300_000)).build());
        all.add(achievement("economy/income_1500k", GOLD, "economy/income_300k", Items.GOLD_BLOCK)
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(
                        AchievementStats.CREDITS_EARNED.get(), 1_500_000)).build());
        all.add(achievement("economy/income_5m", PLATINUM, "economy/income_1500k", Items.NETHERITE_INGOT)
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(
                        AchievementStats.CREDITS_EARNED.get(), 5_000_000)).build());
        all.add(achievement("economy/first_trade", BRONZE, "economy/root", Items.BARREL)
                .criterion("traded", MarketTradeTrigger.TriggerInstance.trades(MarketTradeTrigger.Role.ANY, 1))
                .build());
        all.add(achievement("economy/market_100k", SILVER, "economy/first_trade", Items.EMERALD_BLOCK)
                .criterion("sold", MarketTradeTrigger.TriggerInstance.sold(100_000L)).build());
        all.add(achievement("economy/market_1m", GOLD, "economy/market_100k", Items.DIAMOND_BLOCK)
                .criterion("sold", MarketTradeTrigger.TriggerInstance.soldToPartners(1_000_000L, 3, 10_000L))
                .build());
        all.add(achievement("economy/tycoon", MASTER, "economy/market_1m", Items.NETHERITE_BLOCK).withTitle()
                .criterion("sold", MarketTradeTrigger.TriggerInstance.soldToPartners(20_000_000L, 20, 10_000L))
                .build());
        all.add(achievement("economy/first_case", SILVER, "economy/root", Items.ENDER_CHEST).requires(TACZ)
                .criterion("opened", CaseOpenTrigger.TriggerInstance.any()).build());
        all.add(achievement("economy/lucky_case", DIAMOND, "economy/first_case", Items.ENCHANTED_GOLDEN_APPLE)
                .hidden().withTitle().requires(TACZ)
                .criterion("opened", CaseOpenTrigger.TriggerInstance.atLeast(CaseRarity.GOLD)).build());
        all.add(achievement("economy/all_in", BRONZE, "economy/first_case", Items.BOWL).hidden().withTitle()
                .requires(TACZ)
                .criterion("opened", CaseOpenTrigger.TriggerInstance.creditAfterAtMost(999L)).build());

        all.add(achievement("social/quest_first", BRONZE, "social/root", Items.WRITABLE_BOOK)
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(
                        AchievementStats.QUESTS_COMPLETED.get(), 1)).build());
        all.add(achievement("social/special_quest", BRONZE, "social/quest_first", Items.MAP)
                .criterion("completed", QuestCompleteTrigger.TriggerInstance.fromSource(QuestSource.SPECIAL))
                .build());
        all.add(achievement("social/quest_10", SILVER, "social/quest_first", Items.PAPER)
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(
                        AchievementStats.QUESTS_COMPLETED.get(), 10)).build());
        all.add(achievement("social/quest_200", PLATINUM, "social/quest_10", Items.BOOKSHELF)
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(
                        AchievementStats.QUESTS_COMPLETED.get(), 200)).build());
        all.add(achievement("social/daily_clear_60", DIAMOND, "social/quest_10", Items.CLOCK).withTitle()
                .criterion("reached", StatAtLeastTrigger.TriggerInstance.of(
                        AchievementStats.QUEST_DAILY_CLEARS.get(), 60)).build());
        all.add(achievement("social/marksman_chain", GOLD, "social/quest_first", Items.SPECTRAL_ARROW).hidden()
                .requires(TACZ)
                .criterion("completed", QuestCompleteTrigger.TriggerInstance.chainFinished(QuestPool.CHAIN_MARKSMAN))
                .build());
        all.add(achievement("social/long_distance", BRONZE, "social/married", Items.ENDER_PEARL).hidden()
                .criterion("teleported", SpouseTeleportTrigger.TriggerInstance.beyond(1_000.0D)).build());

        all.add(achievement("meta/count_25", GOLD, "meta/count_10", Items.DIAMOND)
                .criterion("earned", AchievementCountTrigger.TriggerInstance.atLeast(25)).build());
        all.add(achievement("meta/count_40", PLATINUM, "meta/count_25", Items.NETHER_STAR).withTitle()
                .criterion("earned", AchievementCountTrigger.TriggerInstance.atLeast(40)).build());
        return all;
    }

    private static AchievementDeclaration root(String tab, Item icon, String backgroundBlock) {
        Map<String, CriterionTriggerInstance> criteria = new LinkedHashMap<>();
        criteria.put("tick", PlayerTrigger.TriggerInstance.tick());
        return new AchievementDeclaration(AchievementIds.root(tab), null, false, null, itemId(icon),
                new ResourceLocation("minecraft", "textures/block/" + backgroundBlock + ".png"), criteria, null, null);
    }

    private static Builder achievement(String path, AchievementTier tier, String parentPath, Item icon) {
        return new Builder(AchievementIds.id(path), tier, AchievementIds.id(parentPath), itemId(icon));
    }

    /** 玩家位于矿区维度 (条件的玩家谓词)。 */
    private static ContextAwarePredicate inMiningDimension() {
        return EntityPredicate.wrap(EntityPredicate.Builder.entity()
                .located(LocationPredicate.inDimension(MiningConstants.MINING_LEVEL)).build());
    }

    /** 本模组已注册的物品; 取不到直接失败, 不生成指向不存在物品的进度。 */
    private static Item modItem(String path) {
        ResourceLocation id = new ResourceLocation(MiningConstants.MODID, path);
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null || item == Items.AIR) {
            throw new IllegalStateException("achievement datagen references unregistered item " + id);
        }
        return item;
    }

    private static ResourceLocation itemId(Item item) {
        return ForgeRegistries.ITEMS.getKey(item);
    }

    /** 有档位的成就的声明构建器。 */
    private static final class Builder {
        private final ResourceLocation id;
        private final AchievementTier tier;
        private final ResourceLocation parent;
        private final ResourceLocation icon;
        private final Map<String, CriterionTriggerInstance> criteria = new LinkedHashMap<>();
        private boolean hidden;
        @Nullable
        private ResourceLocation title;
        @Nullable
        private String requiredMod;

        private Builder(ResourceLocation id, AchievementTier tier, ResourceLocation parent, ResourceLocation icon) {
            this.id = id;
            this.tier = tier;
            this.parent = parent;
            this.icon = icon;
        }

        /** 隐藏成就 (一律全服公告)。 */
        Builder hidden() {
            this.hidden = true;
            return this;
        }

        /** 附带与成就同名的称号 (9.8: 称号 id 与成就 id 相同, 定义在 data/miningdim/titles)。 */
        Builder withTitle() {
            this.title = id;
            return this;
        }

        /** 依赖可选模组, 进度 JSON 带 forge:mod_loaded 加载条件。 */
        Builder requires(String modId) {
            this.requiredMod = modId;
            return this;
        }

        Builder criterion(String name, CriterionTriggerInstance instance) {
            if (criteria.put(name, instance) != null) {
                throw new IllegalStateException(id + " declares criterion '" + name + "' twice");
            }
            return this;
        }

        AchievementDeclaration build() {
            return new AchievementDeclaration(id, tier, hidden, parent, icon, null, criteria, title, requiredMod);
        }
    }
}
