package com.miningdim.achievement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.achievement.meta.AchievementCatalog;
import com.miningdim.achievement.meta.AchievementConsistency;
import com.miningdim.achievement.meta.AchievementMeta;
import com.miningdim.achievement.meta.AchievementMetaLoader;
import com.miningdim.achievement.meta.ConsistencyReport;
import com.miningdim.achievement.reward.AchievementReward;
import com.miningdim.achievement.reward.LedgerReason;
import com.miningdim.achievement.reward.PointBalance;
import com.miningdim.achievement.reward.SqliteAchievementRewardRepository;
import com.miningdim.achievement.tier.AchievementTier;
import com.miningdim.achievement.trigger.AchievementStats;
import com.miningdim.achievement.trigger.AchievementTriggers;
import com.miningdim.achievement.trigger.ChampionKill;
import com.miningdim.achievement.trigger.DailyCounterRepository;
import com.miningdim.achievement.trigger.SqliteDailyCounterRepository;
import com.miningdim.champion.AffixDef;
import com.miningdim.champion.ChampionDamageTypes;
import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import com.miningdim.ore.OreType;
import com.miningdim.store.MiningDb;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.testutil.TempStoreDb;
import com.miningdim.title.GrantResult;
import com.miningdim.title.TierPalette;
import com.miningdim.title.TitleDefinition;
import com.miningdim.title.TitleDefinitions;
import com.miningdim.title.TitleService;
import com.miningdim.title.TitleSource;
import com.miningdim.title.store.SqliteTitleRepository;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.advancements.FrameType;
import net.minecraft.advancements.critereon.PlayerTrigger;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.Stats;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 成就系统地基的 GameTest (Achievement_System_DesignSpec 第十二章, batch {@code achievement_foundation})。
 *
 * <p>期望值一律按设计文档独立写在本类里 ({@link #EXPECTED} 抄自第九章各表与 9.7、9.8, {@link #TIER_SPEC} 抄自第四章),
 * 不从 datagen 的声明表或 {@link AchievementTier} 反推 —— 否则声明写错、档位表写错时, 测试会跟着一起错。
 * 强断言 (删被测核心逻辑必挂):
 * <ol>
 *   <li>P1 的 29 条成就与 6 个页签根都按规格加载: 父节点、框体、公告、隐藏、Toast、图标、背景、触发器与标题颜色;
 *       依赖 TaCZ 的三条随加载条件出现或缺席; 随后续阶段开放的三条不存在;</li>
 *   <li>每一条都有元数据, 点数取档位默认值, 附带称号与 9.8 一致;</li>
 *   <li>一致性校验在真实服务端上无问题, 并能逐条报出框体不符、隐藏却不公告、缺元数据、称号不存在四类构造出的错误;</li>
 *   <li>成就数量候选不含页签根与 meta 页签、含隐藏成就; 真实触发器经 mock 玩家的原版监听授予真实进度, 计数随之而变;</li>
 *   <li>自定义统计项已注册并带正确格式器; 处决伤害标签覆盖精英怪的处决伤害类型;</li>
 *   <li>奖励仓库按主键防重、领取只成功一次、事务回滚后点数流水称号都无残留; 每日计数到上限即停;</li>
 *   <li>元数据加载器跳过写坏的条目; 配置默认值与规格一致; 两种语言的成就键齐全且一一对应。</li>
 * </ol>
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class AchievementFoundationGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "achievement_foundation";
    private static final String TACZ = "tacz";

    /** 第四章: 档位 -> 原版框体、是否全服公告、默认成就点、标题色标。 */
    private static final Map<String, TierSpec> TIER_SPEC = Map.of(
            "bronze", new TierSpec(FrameType.TASK, false, 10, 0xC8834A),
            "silver", new TierSpec(FrameType.TASK, false, 25, 0xD0D7DE),
            "gold", new TierSpec(FrameType.GOAL, true, 50, 0xFFCC33),
            "platinum", new TierSpec(FrameType.GOAL, true, 100, 0xFFFFFF, 0xFFF1C9, 0xFFD66B),
            "diamond", new TierSpec(FrameType.CHALLENGE, true, 200, 0x6FF2FF, 0x7FB0FF, 0xD59CFF),
            "master", new TierSpec(FrameType.CHALLENGE, true, 400, 0xA55CFF, 0xE05CFF, 0xFF5CB8),
            "legend", new TierSpec(FrameType.CHALLENGE, true, 800, 0xFF3D3D, 0xFF8A1F, 0xFFD23F));

    /** 第九章 P1 的 29 条与 9.7 的 6 个页签根; 称号按 9.8 (称号 id 与成就 id 相同)。 */
    private static final List<Expected> EXPECTED = List.of(
            root("mining", "minecraft:deepslate_iron_ore", "deepslate"),
            root("combat", "minecraft:shield", "blackstone"),
            root("profession", "minecraft:crafting_table", "spruce_planks"),
            root("economy", "minecraft:emerald", "bricks"),
            root("social", "minecraft:bell", "cherry_planks"),
            root("meta", "minecraft:knowledge_book", "quartz_block_side"),
            row("mining/first_entry", "bronze", "mining/root", "miningdim:entrance_easy", "miningdim:enter_mining"),
            row("mining/first_extraction", "bronze", "mining/first_entry", "minecraft:lantern",
                    "miningdim:mining_extraction"),
            row("mining/blocks_1k", "bronze", "mining/first_entry", "minecraft:stone_pickaxe",
                    "miningdim:stat_at_least"),
            row("mining/trap_sprung", "bronze", "mining/first_entry", "miningdim:fake_ore",
                    "miningdim:stat_at_least").hidden(),
            row("mining/deep_regular", "silver", "mining/first_extraction", "minecraft:iron_pickaxe",
                    "miningdim:stat_at_least"),
            row("mining/medium_extraction", "silver", "mining/first_extraction", "miningdim:entrance_medium",
                    "miningdim:mining_extraction"),
            row("mining/narrow_escape", "silver", "mining/first_extraction", "minecraft:golden_apple",
                    "miningdim:mining_extraction").hidden(),
            row("mining/dungeon_chest", "silver", "mining/first_entry", "minecraft:chest",
                    "minecraft:player_generates_container_loot"),
            row("mining/hard_extraction", "gold", "mining/medium_extraction", "miningdim:entrance_hard",
                    "miningdim:mining_extraction"),
            row("mining/blocks_10k", "gold", "mining/blocks_1k", "minecraft:diamond_pickaxe",
                    "miningdim:stat_at_least"),
            row("mining/ore_codex", "gold", "mining/hard_extraction", "miningdim:raw_tungsten", "miningdim:mine_ore")
                    .criteria(16).titled(),
            row("mining/hard_veteran", "platinum", "mining/hard_extraction", "minecraft:deepslate_diamond_ore",
                    "miningdim:stat_at_least"),
            row("mining/blocks_100k", "diamond", "mining/blocks_10k", "minecraft:netherite_pickaxe",
                    "miningdim:stat_at_least").titled(),
            row("mining/hard_active_100h", "master", "mining/hard_veteran", "minecraft:ancient_debris",
                    "miningdim:stat_at_least").titled(),
            row("combat/first_champion", "bronze", "combat/root", "minecraft:iron_sword", "miningdim:champion_kill"),
            row("combat/gun_100", "bronze", "combat/root", "miningdim:bullet_head", "miningdim:stat_at_least").tacz(),
            row("combat/executed", "bronze", "combat/first_champion", "minecraft:wither_skeleton_skull",
                    "minecraft:entity_killed_player").hidden(),
            row("combat/headshot_100", "silver", "combat/gun_100", "minecraft:target", "miningdim:stat_at_least")
                    .tacz(),
            row("combat/giant_slayer", "silver", "combat/first_champion", "minecraft:zombie_head",
                    "miningdim:champion_kill"),
            row("combat/champion_100", "gold", "combat/first_champion", "minecraft:diamond_sword",
                    "miningdim:stat_at_least"),
            row("combat/star_6", "gold", "combat/first_champion", "minecraft:golden_sword", "miningdim:champion_kill"),
            row("combat/long_shot", "gold", "combat/headshot_100", "minecraft:spyglass", "miningdim:gun_kill")
                    .hidden().tacz().titled(),
            row("combat/star_7", "platinum", "combat/star_6", "minecraft:netherite_sword", "miningdim:champion_kill"),
            row("combat/solo_star_9", "legend", "combat/star_7", "minecraft:end_crystal", "miningdim:champion_kill")
                    .titled(),
            row("profession/ore_soup_in_mine", "bronze", "profession/root", "miningdim:iron_ore_fish_soup",
                    "minecraft:consume_item"),
            row("social/engagement_ring", "bronze", "social/root", "miningdim:engagement_ring",
                    "minecraft:inventory_changed"),
            row("social/married", "silver", "social/engagement_ring", "miningdim:wedding_ring", "miningdim:married"),
            row("social/shared_backpack", "bronze", "social/married", "minecraft:pink_shulker_box",
                    "miningdim:open_shared_backpack"),
            row("meta/count_10", "silver", "meta/root", "minecraft:amethyst_shard", "miningdim:achievement_count"));

    /** 规格里有、但不在 P1 里生成的成就 (随世界 BOSS 事件、随 P2 开放)。 */
    private static final List<String> NOT_YET_OPEN = List.of("combat/star_10", "meta/count_25", "meta/count_40");

    private AchievementFoundationGameTests() {
    }

    // ---- 1. 进度按规格加载 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void p1AdvancementsLoadAsSpecified(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        boolean tacz = ModList.get().isLoaded(TACZ);
        JsonObject zh = readLang("zh_cn");
        helper.assertTrue(EXPECTED.size() == 35 && EXPECTED.stream().filter(e -> e.tier != null).count() == 29,
                "期望表应为 29 条 P1 成就 + 6 个页签根");

        for (Expected expected : EXPECTED) {
            ResourceLocation id = AchievementIds.id(expected.path);
            Advancement advancement = server.getAdvancements().getAdvancement(id);
            if (expected.tacz && !tacz) {
                helper.assertTrue(advancement == null, id + " 依赖 TaCZ, 没装 TaCZ 时加载条件不满足, 不应存在");
                continue;
            }
            helper.assertTrue(advancement != null, "服务端没有加载 " + id);
            DisplayInfo display = advancement.getDisplay();
            helper.assertTrue(display != null, id + " 缺少显示信息");
            helper.assertTrue(itemId(display.getIcon()).equals(expected.icon),
                    id + " 的图标应为 " + expected.icon + ", 实为 " + itemId(display.getIcon()));
            helper.assertTrue(!advancement.sendsTelemetryEvent(), id + " 不应让客户端发遥测");
            ResourceLocation parent = advancement.getParent() == null ? null : advancement.getParent().getId();
            ResourceLocation expectedParent = expected.parent == null ? null : AchievementIds.id(expected.parent);
            helper.assertTrue(Objects.equals(parent, expectedParent),
                    id + " 的父进度应为 " + expectedParent + ", 实为 " + parent);
            Set<ResourceLocation> triggers = new LinkedHashSet<>();
            for (Criterion criterion : advancement.getCriteria().values()) {
                triggers.add(criterion.getTrigger().getCriterion());
            }
            helper.assertTrue(triggers.equals(Set.of(new ResourceLocation(expected.trigger)))
                            && advancement.getCriteria().size() == expected.criteria
                            && advancement.getMaxCriteraRequired() == expected.criteria,
                    id + " 应有 " + expected.criteria + " 个全部必需的 " + expected.trigger + " 条件, 实为 " + triggers
                            + " x" + advancement.getCriteria().size());

            if (expected.tier == null) {
                helper.assertTrue(!display.shouldAnnounceChat() && !display.shouldShowToast() && !display.isHidden(),
                        id + " 是页签根: 不公告、不发 Toast、不隐藏");
                helper.assertTrue(expected.background.equals(display.getBackground()),
                        id + " 的背景应为 " + expected.background + ", 实为 " + display.getBackground());
                continue;
            }
            TierSpec spec = TIER_SPEC.get(expected.tier);
            helper.assertTrue(display.getFrame() == spec.frame,
                    id + " (" + expected.tier + ") 的框体应为 " + spec.frame + ", 实为 " + display.getFrame());
            boolean announce = spec.announce || expected.hidden;
            helper.assertTrue(display.shouldAnnounceChat() == announce,
                    id + " 的全服公告应为 " + announce + " (隐藏成就一律公告)");
            helper.assertTrue(display.isHidden() == expected.hidden, id + " 的隐藏标记应为 " + expected.hidden);
            helper.assertTrue(display.shouldShowToast() && display.getBackground() == null,
                    id + " 应发 Toast, 且只有页签根带背景");
            assertTitleStyle(helper, id, display.getTitle(), spec, zh);
            helper.assertTrue(display.getDescription().getContents() instanceof TranslatableContents description
                            && description.getKey().equals(AchievementIds.descriptionKey(id)),
                    id + " 的描述应保留 translate 键 " + AchievementIds.descriptionKey(id));
        }

        for (String path : NOT_YET_OPEN) {
            helper.assertTrue(server.getAdvancements().getAdvancement(AchievementIds.id(path)) == null,
                    AchievementIds.id(path) + " 随后续阶段开放, P1 不应生成");
        }
        long loaded = server.getAdvancements().getAllAdvancements().stream()
                .filter(advancement -> AchievementIds.isAchievement(advancement.getId())).count();
        int expectedLoaded = tacz ? 35 : 32;
        helper.assertTrue(loaded == expectedLoaded,
                "miningdim 命名空间下 (配方以外) 应恰好加载 " + expectedLoaded + " 个进度, 实为 " + loaded);
        helper.succeed();
    }

    // ---- 2. 元数据 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void metaLoadedForEveryAdvancementWithSpecPointsAndTitles(GameTestHelper helper) {
        AchievementCatalog catalog = AchievementServices.catalog();
        int totalPoints = 0;
        for (Expected expected : EXPECTED) {
            ResourceLocation id = AchievementIds.id(expected.path);
            AchievementMeta meta = catalog.meta(id).orElse(null);
            helper.assertTrue(meta != null, "缺少元数据: " + id);
            if (expected.tier == null) {
                helper.assertTrue(meta.isRoot() && catalog.points(id) == 0 && catalog.title(id).isEmpty()
                        && !catalog.isRewarding(id), id + " 是页签根: 0 点、无称号、不产生奖励");
                continue;
            }
            helper.assertTrue(meta.tier() != null && meta.tier().id().equals(expected.tier),
                    id + " 的档位应为 " + expected.tier + ", 实为 " + meta.tier());
            helper.assertTrue(meta.hidden() == expected.hidden, id + " 元数据的隐藏标记应为 " + expected.hidden);
            int points = TIER_SPEC.get(expected.tier).points;
            helper.assertTrue(catalog.points(id) == points && meta.pointsOverride() == null,
                    id + " 应取档位默认 " + points + " 点, 实为 " + catalog.points(id));
            ResourceLocation title = expected.titled ? id : null;
            helper.assertTrue(Objects.equals(catalog.title(id).orElse(null), title),
                    id + " 附带的称号应为 " + title + ", 实为 " + catalog.title(id));
            if (title != null) {
                helper.assertTrue(AchievementConsistency.titleDefined(title), "称号定义缺失: " + title);
            }
            helper.assertTrue(catalog.isRewarding(id), id + " 有成就点, 获得后应产生待领取奖励");
            totalPoints += points;
        }
        // 铜 10 条 x10 + 银 8 条 x25 + 金 6 条 x50 + 白金 2 条 x100 + 钻石、大师、传说各 1 条 = 2200。
        helper.assertTrue(totalPoints == 2200, "P1 29 条成就的默认点数合计应为 2200, 实为 " + totalPoints);
        helper.assertTrue(catalog.metas().size() == EXPECTED.size(),
                "数据包里应恰好有 " + EXPECTED.size() + " 份成就元数据, 实为 " + catalog.metas().size());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void metaLoaderSkipsMalformedEntries(GameTestHelper helper) {
        Map<ResourceLocation, JsonElement> raw = new LinkedHashMap<>();
        raw.put(testId("ok_tier"), json("{\"tier\":\"diamond\",\"hidden\":true,\"points\":7,"
                + "\"title\":\"miningdim:mining/blocks_100k\"}"));
        raw.put(testId("ok_root"), json("{\"root\":true}"));
        raw.put(testId("unknown_tier"), json("{\"tier\":\"mythic\"}"));
        raw.put(testId("missing_tier"), json("{\"hidden\":true}"));
        raw.put(testId("root_with_tier"), json("{\"root\":true,\"tier\":\"gold\"}"));
        raw.put(testId("negative_points"), json("{\"tier\":\"gold\",\"points\":-1}"));
        raw.put(testId("bad_title"), json("{\"tier\":\"gold\",\"title\":\"Not A Valid:Id\"}"));
        Map<ResourceLocation, AchievementMeta> loaded = AchievementMetaLoader.parseAll(raw);
        helper.assertTrue(loaded.keySet().equals(Set.of(testId("ok_tier"), testId("ok_root"))),
                "只有两条合格的元数据应被加载, 实为 " + loaded.keySet());
        AchievementMeta tiered = loaded.get(testId("ok_tier"));
        helper.assertTrue(tiered.tier() == AchievementTier.DIAMOND && tiered.hidden() && tiered.points() == 7
                        && AchievementIds.id("mining/blocks_100k").equals(tiered.title()),
                "合格条目的档位、隐藏、点数覆盖与称号应原样读出");
        helper.assertTrue(loaded.get(testId("ok_root")).isRoot() && loaded.get(testId("ok_root")).points() == 0,
                "页签根元数据应为 0 点");
        helper.assertTrue(AchievementMeta.fromJson(testId("ok_tier"), tiered.toJson()).equals(tiered),
                "元数据写出后再读回应完全一致");
        helper.succeed();
    }

    // ---- 3. 一致性校验 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void consistencyCheckIsCleanOnServerAndReportsBrokenCases(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        boolean tacz = ModList.get().isLoaded(TACZ);
        ConsistencyReport live = AchievementConsistency.check(server.getAdvancements().getAllAdvancements(),
                AchievementServices.catalog().metas(), AchievementConsistency::titleDefined);
        helper.assertTrue(live.isClean(), "真实服务端上的成就应当一致, 实报 " + live.problems());
        helper.assertTrue(live.checked() == (tacz ? 35 : 32), "应核对全部本模块进度, 实为 " + live.checked());
        List<ResourceLocation> gated = tacz ? List.of() : List.of(AchievementIds.id("combat/gun_100"),
                AchievementIds.id("combat/headshot_100"), AchievementIds.id("combat/long_shot"));
        helper.assertTrue(live.metaWithoutAdvancement().equals(gated),
                "只有加载条件不满足的进度才应有元数据而无进度, 实为 " + live.metaWithoutAdvancement());

        ResourceLocation rootId = testId("root");
        Advancement root = advancement(rootId, null, FrameType.TASK, false, false, false);
        ResourceLocation fine = testId("fine");
        ResourceLocation wrongFrame = testId("wrong_frame");
        ResourceLocation quietHidden = testId("quiet_hidden");
        ResourceLocation noMeta = testId("no_meta");
        ResourceLocation unknownTitle = testId("unknown_title");
        ResourceLocation orphan = testId("orphan");
        List<Advancement> advancements = List.of(root,
                advancement(fine, root, FrameType.TASK, true, false, false),
                advancement(wrongFrame, root, FrameType.TASK, true, true, false),
                advancement(quietHidden, root, FrameType.TASK, true, false, true),
                advancement(noMeta, root, FrameType.TASK, true, false, false),
                advancement(unknownTitle, root, FrameType.TASK, true, false, false),
                advancement(AchievementIds.id("recipes/test/ignored"), null, null, false, false, false),
                advancement(new ResourceLocation("minecraft", "test/ignored"), null, FrameType.GOAL, true, false,
                        false));
        Map<ResourceLocation, AchievementMeta> metas = new LinkedHashMap<>();
        metas.put(rootId, AchievementMeta.root(rootId));
        metas.put(fine, new AchievementMeta(fine, AchievementTier.BRONZE, false, null, null));
        metas.put(wrongFrame, new AchievementMeta(wrongFrame, AchievementTier.GOLD, false, null, null));
        metas.put(quietHidden, new AchievementMeta(quietHidden, AchievementTier.BRONZE, true, null, null));
        metas.put(unknownTitle, new AchievementMeta(unknownTitle, AchievementTier.BRONZE, false, null,
                testId("no_such_title")));
        metas.put(orphan, new AchievementMeta(orphan, AchievementTier.SILVER, false, null, null));

        ConsistencyReport report = AchievementConsistency.check(advancements, metas, title -> false);
        helper.assertTrue(report.checked() == 6, "应只核对 miningdim 命名空间下配方以外的 6 个进度, 实为 " + report.checked());
        helper.assertTrue(report.problems().size() == 4, "应恰好报出 4 处问题, 实为 " + report.problems());
        for (ResourceLocation broken : List.of(wrongFrame, quietHidden, noMeta, unknownTitle)) {
            long hits = report.problems().stream().filter(problem -> problem.startsWith(broken + ":")).count();
            helper.assertTrue(hits == 1, "应恰好报出一处 " + broken + " 的问题, 实报 " + report.problems());
        }
        helper.assertTrue(report.problems().stream().noneMatch(problem -> problem.startsWith(fine + ":")
                        || problem.startsWith(rootId + ":")), "一致的成就与页签根不应被报出");
        helper.assertTrue(report.problems().stream().anyMatch(problem -> problem.startsWith(wrongFrame + ":")
                && problem.contains("frame")), "框体与档位不符应报 frame");
        helper.assertTrue(report.problems().stream().anyMatch(problem -> problem.startsWith(quietHidden + ":")
                && problem.contains("announce_to_chat")), "隐藏成就不公告应报 announce_to_chat");
        helper.assertTrue(report.metaWithoutAdvancement().equals(List.of(orphan)),
                "有元数据无进度的应只有 orphan, 实为 " + report.metaWithoutAdvancement());

        AchievementCatalog catalog = AchievementCatalog.build(advancements, metas);
        helper.assertTrue(catalog.points(noMeta) == 0 && catalog.title(noMeta).isEmpty()
                && !catalog.isRewarding(noMeta), "缺元数据的进度按 0 点、无称号处理, 不产生奖励");
        helper.assertTrue(catalog.points(wrongFrame) == 50
                && catalog.tier(wrongFrame).orElse(null) == AchievementTier.GOLD, "元数据存在时点数取档位默认 (金 50)");
        helper.succeed();
    }

    // ---- 4. 成就数量候选与真实触发 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void countableIdsExcludeRootsAndMetaTab(GameTestHelper helper) {
        boolean tacz = ModList.get().isLoaded(TACZ);
        Set<ResourceLocation> countable = AchievementServices.catalog().countableIds();
        Set<ResourceLocation> expected = new TreeSet<>();
        for (Expected entry : EXPECTED) {
            if (entry.tier != null && !entry.path.startsWith("meta/") && (tacz || !entry.tacz)) {
                expected.add(AchievementIds.id(entry.path));
            }
        }
        helper.assertTrue(new TreeSet<>(countable).equals(expected),
                "成就数量候选应为 P1 里页签根与 meta 页签以外、已加载的成就 (" + expected.size() + " 条), 实为 " + countable);
        helper.assertTrue(countable.size() == (tacz ? 28 : 25), "计数池应为 " + (tacz ? 28 : 25) + " 条");
        helper.assertTrue(countable.contains(AchievementIds.id("mining/trap_sprung")), "隐藏成就应计入成就数量");
        helper.assertTrue(countable.stream().noneMatch(id -> id.getPath().startsWith("recipes/")
                        || id.getPath().endsWith("/root") || id.getPath().startsWith("meta/")),
                "候选里不能有配方进度、页签根或 meta 页签的成就");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void triggersGrantRealAdvancementsEndToEnd(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            AchievementTriggers.ENTER_MINING.trigger(player, Difficulty.EASY);
            assertDone(helper, player, "mining/first_entry", true);

            AchievementTriggers.MINING_EXTRACTION.trigger(player, Difficulty.EASY, 0.5D, Long.MAX_VALUE);
            assertDone(helper, player, "mining/first_extraction", true);
            assertDone(helper, player, "mining/medium_extraction", false);
            AchievementTriggers.MINING_EXTRACTION.trigger(player, Difficulty.MEDIUM, 0.08D, 601L);
            assertDone(helper, player, "mining/medium_extraction", true);
            assertDone(helper, player, "mining/narrow_escape", false);

            AchievementStats.award(player, AchievementStats.MINING_TRAPS_SPRUNG, 1);
            assertDone(helper, player, "mining/trap_sprung", true);
            AchievementStats.award(player, AchievementStats.MINING_BLOCKS_MINED, 999);
            assertDone(helper, player, "mining/blocks_1k", false);
            AchievementStats.award(player, AchievementStats.MINING_BLOCKS_MINED, 1);
            assertDone(helper, player, "mining/blocks_1k", true);
            assertDone(helper, player, "mining/blocks_10k", false);
            helper.assertTrue(player.getStats().getValue(Stats.CUSTOM.get(AchievementStats.MINING_BLOCKS_MINED.get()))
                    == 1_000, "统计项应累加到 1000");

            OreType[] ores = OreType.values();
            for (int i = 0; i < ores.length - 1; i++) {
                AchievementTriggers.MINE_ORE.trigger(player, ores[i], Difficulty.HARD);
            }
            assertDone(helper, player, "mining/ore_codex", false);
            AchievementTriggers.MINE_ORE.trigger(player, ores[ores.length - 1], Difficulty.EASY);
            assertDone(helper, player, "mining/ore_codex", true);

            AchievementTriggers.CHAMPION_KILL.trigger(player,
                    new ChampionKill(9, Set.of(AffixDef.HEAVY_ARMOR), 1.0D, true, 18_000L));
            for (String path : List.of("combat/first_champion", "combat/star_6", "combat/star_7",
                    "combat/solo_star_9")) {
                assertDone(helper, player, path, true);
            }
            assertDone(helper, player, "combat/giant_slayer", false);

            // 处决: 真实的伤害标签 + entity_killed_player。反震伤害不在标签里, 不能算处决。
            Zombie champion = helper.spawn(EntityType.ZOMBIE, 1, 2, 1);
            Registry<DamageType> damageTypes = server.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
            CriteriaTriggers.ENTITY_KILLED_PLAYER.trigger(player, champion,
                    new DamageSource(damageTypes.getHolderOrThrow(ChampionDamageTypes.CHAMPION_THORNS), champion));
            assertDone(helper, player, "combat/executed", false);
            CriteriaTriggers.ENTITY_KILLED_PLAYER.trigger(player, champion,
                    new DamageSource(damageTypes.getHolderOrThrow(ChampionDamageTypes.CHAMPION_EXECUTION), champion));
            assertDone(helper, player, "combat/executed", true);
            champion.discard();

            // 矿石鱼羹只在矿区里喝才算: 测试结构在主世界, 不应授予。
            ItemStack soup = new ItemStack(ForgeRegistries.ITEMS.getValue(AchievementIds.id("iron_ore_fish_soup")));
            CriteriaTriggers.CONSUME_ITEM.trigger(player, soup);
            assertDone(helper, player, "profession/ore_soup_in_mine", false);

            ItemStack ring = new ItemStack(ForgeRegistries.ITEMS.getValue(AchievementIds.id("engagement_ring")));
            player.getInventory().add(ring.copy());
            CriteriaTriggers.INVENTORY_CHANGED.trigger(player, player.getInventory(), ring);
            assertDone(helper, player, "social/engagement_ring", true);
            AchievementTriggers.MARRIED.trigger(player);
            AchievementTriggers.OPEN_SHARED_BACKPACK.trigger(player);
            assertDone(helper, player, "social/married", true);
            assertDone(helper, player, "social/shared_backpack", true);

            // 成就数量: 上面授予了 14 个计数成就 (矿区 6、战斗 5、社交 3)。获得第 10 个时, 成就数量钩子
            // (PlayerProgressHooks, 挂在 AdvancementEarnEvent 上) 已经自动授予了 count_10; 撤销后再手动喂阈值两侧。
            AchievementCatalog catalog = AchievementServices.catalog();
            int earned = catalog.countEarned(player.getAdvancements());
            helper.assertTrue(earned == 14, "应已获得 14 个计数成就, 实为 " + earned);
            assertDone(helper, player, "meta/count_10", true);
            Advancement count10 = server.getAdvancements().getAdvancement(AchievementIds.id("meta/count_10"));
            for (String criterion : count10.getCriteria().keySet()) {
                player.getAdvancements().revoke(count10, criterion);
            }
            AchievementTriggers.ACHIEVEMENT_COUNT.trigger(player, 9);
            assertDone(helper, player, "meta/count_10", false);
            AchievementTriggers.ACHIEVEMENT_COUNT.trigger(player, earned);
            assertDone(helper, player, "meta/count_10", true);
            helper.assertTrue(catalog.countEarned(player.getAdvancements()) == earned,
                    "meta 页签自己的成就不计入成就数量");
            Advancement miningRoot = server.getAdvancements().getAdvancement(AchievementIds.root("mining"));
            player.getAdvancements().award(miningRoot, "tick");
            helper.assertTrue(catalog.countEarned(player.getAdvancements()) == earned, "页签根不计入成就数量");
        } finally {
            server.getPlayerList().remove(player);
        }
        helper.succeed();
    }

    // ---- 5. 统计项与伤害标签 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void customStatsRegisteredWithFormatters(GameTestHelper helper) throws IOException {
        List<String> expected = List.of("mining_entries", "mining_extractions", "mining_extractions_hard",
                "mining_blocks_mined", "mining_traps_sprung", "mining_hard_active_ticks", "champion_kills", "gun_kills",
                "gun_headshot_kills");
        List<String> registered = new ArrayList<>();
        for (RegistryObject<ResourceLocation> stat : AchievementStats.all()) {
            registered.add(stat.getId().getPath());
        }
        helper.assertTrue(registered.equals(expected), "P1 统计项应恰好是 6.1 的九项, 实为 " + registered);
        JsonObject zh = readLang("zh_cn");
        JsonObject en = readLang("en_us");
        for (RegistryObject<ResourceLocation> stat : AchievementStats.all()) {
            ResourceLocation id = AchievementIds.id(stat.getId().getPath());
            helper.assertTrue(BuiltInRegistries.CUSTOM_STAT.get(id) == stat.get(),
                    id + " 应注册在原版自定义统计注册表里, 且注册的就是这个对象");
            helper.assertTrue(Stats.CUSTOM.contains(stat.get()), id + " 应在通用初始化时就建好 Stat 对象");
            StatFormatter formatter = id.getPath().equals("mining_hard_active_ticks") ? StatFormatter.TIME
                    : StatFormatter.DEFAULT;
            String shown = Stats.CUSTOM.get(stat.get()).format(72_000);
            helper.assertTrue(shown.equals(formatter.format(72_000)),
                    id + " 的显示格式不对: " + shown + " (应与 " + formatter.format(72_000) + " 相同)");
            String key = "stat.miningdim." + id.getPath();
            helper.assertTrue(hasText(zh, key) && hasText(en, key), "两种语言都要有统计项名称键 " + key);
        }
        helper.assertTrue(!StatFormatter.TIME.format(72_000).equals(StatFormatter.DEFAULT.format(72_000)),
                "时间格式与默认格式在 72000 上应当不同, 否则上面的断言测不出格式器");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void championExecutionTagCoversOnlyTheExecutionDamage(GameTestHelper helper) {
        Registry<DamageType> damageTypes = helper.getLevel().getServer().registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE);
        TagKey<DamageType> tag = TagKey.create(Registries.DAMAGE_TYPE, AchievementIds.id("is_champion_execution"));
        Holder<DamageType> execution = damageTypes.getHolderOrThrow(ChampionDamageTypes.CHAMPION_EXECUTION);
        Holder<DamageType> thorns = damageTypes.getHolderOrThrow(ChampionDamageTypes.CHAMPION_THORNS);
        helper.assertTrue(execution.is(tag), "is_champion_execution 标签必须包含精英怪的处决伤害类型");
        helper.assertTrue(!thorns.is(tag), "反震伤害不是处决, 不应在标签里");
        helper.succeed();
    }

    // ---- 6. 存储 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rewardRepositoryKeepsFirstRewardAndClaimsOnceInOneTransaction(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("achievement.db"));
        try {
            SqliteAchievementRewardRepository rewards = new SqliteAchievementRewardRepository(connection);
            UUID player = UUID.randomUUID();
            ResourceLocation codex = AchievementIds.id("mining/ore_codex");
            ResourceLocation entry = AchievementIds.id("mining/first_entry");

            helper.assertTrue(rewards.insertReward(player, codex, AchievementTier.GOLD, 50, codex, 1_000L),
                    "首次写入待领取奖励应成功");
            helper.assertTrue(!rewards.insertReward(player, codex, AchievementTier.BRONZE, 10, null, 2_000L),
                    "同一玩家同一进度的第二次写入必须被主键挡下 (撤销后再授予不重复发奖)");
            AchievementReward first = rewards.reward(player, codex).orElseThrow();
            helper.assertTrue(first.tier() == AchievementTier.GOLD && first.points() == 50
                            && codex.equals(first.titleId()) && first.earnedAt() == 1_000L && !first.isClaimed(),
                    "重复写入不得覆盖最早的快照, 实为 " + first);
            rewards.insertReward(player, entry, AchievementTier.BRONZE, 10, null, 500L);
            helper.assertTrue(rewards.pending(player).stream().map(AchievementReward::advancementId).toList()
                    .equals(List.of(entry, codex)), "待领取列表应按获得时间排序");

            TitleDefinitions definitions = new TitleDefinitions();
            definitions.install(Map.of(codex, new TitleDefinition(codex, Component.literal("矿物学者"),
                    TierPalette.GOLD, List.of(), null, null, TierPalette.GOLD.defaultSort())));
            TitleService titles = new TitleService(new SqliteTitleRepository(connection), definitions,
                    helper.getLevel().getServer());

            boolean rolledBack = false;
            try {
                rewards.inTransaction(tx -> {
                    claim(helper, rewards, titles, tx, player, codex);
                    throw new SimulatedFailure();
                });
            } catch (SimulatedFailure expected) {
                rolledBack = true;
            }
            helper.assertTrue(rolledBack, "模拟失败应冒泡出事务");
            helper.assertTrue(rewards.points(player).equals(PointBalance.ZERO), "回滚后不得残留成就点");
            helper.assertTrue(!rewards.reward(player, codex).orElseThrow().isClaimed(), "回滚后奖励仍应待领取");
            helper.assertTrue(countRows(connection, "SELECT COUNT(*) FROM achievement_point_ledger WHERE player_uuid=?",
                    player) == 0, "回滚后不得残留流水");
            helper.assertTrue(countRows(connection, "SELECT COUNT(*) FROM title_owned WHERE player_uuid=?",
                    player) == 0, "回滚后不得残留称号 (grantInTransaction 随外层事务回滚)");

            rewards.inTransaction(tx -> {
                claim(helper, rewards, titles, tx, player, codex);
                return null;
            });
            helper.assertTrue(rewards.points(player).equals(new PointBalance(50L, 50L)), "领取后余额与累计获得都应为 50");
            AchievementReward claimed = rewards.reward(player, codex).orElseThrow();
            helper.assertTrue(claimed.isClaimed() && claimed.claimedAt() == 3_000L, "领取时间应记为 3000");
            helper.assertTrue(countRows(connection, "SELECT COUNT(*) FROM achievement_point_ledger WHERE player_uuid=? "
                    + "AND delta=50 AND reason='claim' AND ref='miningdim:mining/ore_codex'", player) == 1,
                    "领取应写恰好一条 claim 流水");
            helper.assertTrue(titles.owned(player).contains(codex), "事务提交后称号应已落库");
            helper.assertTrue(!rewards.markClaimed(player, codex, 4_000L)
                            && rewards.reward(player, codex).orElseThrow().claimedAt() == 3_000L,
                    "重复领取必须失败且不改领取时间");
            helper.assertTrue(!rewards.markClaimed(player, AchievementIds.id("mining/nope"), 4_000L),
                    "领取不存在的奖励必须失败");
            helper.assertTrue(rewards.pending(player).stream().map(AchievementReward::advancementId).toList()
                    .equals(List.of(entry)), "已领取的奖励不再出现在待领取列表");

            helper.assertTrue(!rewards.debit(player, 51L) && rewards.points(player).balance() == 50L,
                    "余额不足时扣点必须失败且不改余额");
            helper.assertTrue(rewards.debit(player, 20L) && rewards.points(player).equals(new PointBalance(30L, 50L)),
                    "扣点只减余额, 累计获得不变");
            helper.assertTrue(!rewards.debit(UUID.randomUUID(), 1L), "从未有过成就点的玩家扣点必须失败");
            boolean rejected = false;
            try {
                rewards.credit(player, 0L);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "入账数额必须为正");
        } finally {
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dailyCounterStopsAtCapPerKeyAndDay(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Connection connection = TempStoreDb.openUnified(dir.resolve("achievement.db"));
        try {
            SqliteDailyCounterRepository counters = new SqliteDailyCounterRepository(connection);
            UUID player = UUID.randomUUID();
            String extraction = DailyCounterRepository.EXTRACTION_KEY;
            String hard = DailyCounterRepository.HARD_EXTRACTION_KEY;
            long today = 20_000L;
            for (int i = 1; i <= 5; i++) {
                helper.assertTrue(counters.incrementIfBelow(player, extraction, today, 5), "第 " + i + " 次应在上限内");
            }
            helper.assertTrue(!counters.incrementIfBelow(player, extraction, today, 5), "第 6 次必须被上限挡下");
            helper.assertTrue(counters.count(player, extraction, today) == 5, "到上限后计数应停在 5");
            helper.assertTrue(counters.incrementIfBelow(player, hard, today, 2)
                    && counters.incrementIfBelow(player, hard, today, 2)
                    && !counters.incrementIfBelow(player, hard, today, 2), "困难撤离另计, 上限 2");
            helper.assertTrue(counters.incrementIfBelow(player, extraction, today + 1, 5)
                    && counters.count(player, extraction, today + 1) == 1, "换一天重新计");
            helper.assertTrue(counters.incrementIfBelow(UUID.randomUUID(), extraction, today, 5), "别的玩家各自计");
            helper.assertTrue(!counters.incrementIfBelow(player, "zero_cap", today, 0)
                    && counters.count(player, "zero_cap", today) == 0, "上限为 0 时一次都不计, 也不落行");
            int pruned = counters.deleteBefore(today + 1);
            helper.assertTrue(pruned == 3 && counters.count(player, extraction, today) == 0
                            && counters.count(player, extraction, today + 1) == 1,
                    "清理应只删掉更早日期的 3 行, 实删 " + pruned);
        } finally {
            MiningDb.close(connection);
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ---- 7. 配置与文案 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void configDefaultsMatchSpec(GameTestHelper helper) {
        helper.assertTrue(AchievementConfig.EXTRACTION_MIN_DWELL_TICKS.getDefault() == 6_000
                && AchievementConfig.EXTRACTION_MIN_DWELL_TICKS.get() == 6_000, "extractionMinDwellTicks 默认 6000");
        helper.assertTrue(AchievementConfig.MIN_TRIP_BLOCKS.getDefault() == 32
                && AchievementConfig.MIN_TRIP_BLOCKS.get() == 32, "minTripBlocks 默认 32");
        helper.assertTrue(AchievementConfig.DAILY_EXTRACTION_CAP.getDefault() == 5
                && AchievementConfig.DAILY_EXTRACTION_CAP.get() == 5, "dailyExtractionCap 默认 5");
        helper.assertTrue(AchievementConfig.DAILY_HARD_EXTRACTION_CAP.getDefault() == 2
                && AchievementConfig.DAILY_HARD_EXTRACTION_CAP.get() == 2, "dailyHardExtractionCap 默认 2");
        helper.assertTrue(AchievementConfig.HARD_ACTIVE_GAP_CAP_TICKS.getDefault() == 3_600
                && AchievementConfig.HARD_ACTIVE_GAP_CAP_TICKS.get() == 3_600, "hardActiveGapCapTicks 默认 3600");
        helper.assertTrue(AchievementConfig.HARD_ACTIVE_PER_BLOCK_CAP_TICKS.getDefault() == 600
                && AchievementConfig.HARD_ACTIVE_PER_BLOCK_CAP_TICKS.get() == 600, "hardActivePerBlockCapTicks 默认 600");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void achievementLangKeysArePairedInBothLanguages(GameTestHelper helper) throws IOException {
        JsonObject zh = readLang("zh_cn");
        JsonObject en = readLang("en_us");
        for (Expected expected : EXPECTED) {
            ResourceLocation id = AchievementIds.id(expected.path);
            for (String key : List.of(AchievementIds.titleKey(id), AchievementIds.descriptionKey(id))) {
                helper.assertTrue(hasText(zh, key) && hasText(en, key), "zh_cn / en_us 必须都有非空键 " + key);
            }
        }
        Set<String> zhKeys = keysWithPrefix(zh, "achievement.miningdim.");
        Set<String> enKeys = keysWithPrefix(en, "achievement.miningdim.");
        helper.assertTrue(zhKeys.equals(enKeys), "两种语言的 achievement.miningdim.* 键必须一一对应, 差集 "
                + difference(zhKeys, enKeys) + " / " + difference(enKeys, zhKeys));
        // 奖励提示与命令反馈的键 (reward / claim / command 段) 不是某个进度的标题或说明, 只数页签段下的键。
        long advancementKeys = zhKeys.stream().filter(key -> AchievementIds.TABS.contains(
                key.substring("achievement.miningdim.".length(), key.indexOf('.', "achievement.miningdim.".length()))))
                .count();
        helper.assertTrue(advancementKeys == EXPECTED.size() * 2, "应恰好有 " + EXPECTED.size() * 2
                + " 个进度文案键, 实为 " + advancementKeys);
        helper.assertTrue("深渊守望者".equals(zh.get("achievement.miningdim.mining.hard_active_100h.title").getAsString())
                        && "WOK · 矿区".equals(zh.get("achievement.miningdim.mining.root.title").getAsString()),
                "中文名称应取自第九章与 9.7");
        // 颜色与格式一律由组件样式给出: 两种语言里本模块的全部文案 (进度、奖励、领取与命令反馈, 以及自定义统计项名称)
        // 都不能直接写格式码。上面已断言两种语言的 achievement.miningdim.* 键集合相同。
        List<String> ownedKeys = new ArrayList<>(zhKeys);
        for (RegistryObject<ResourceLocation> stat : AchievementStats.all()) {
            ownedKeys.add("stat.miningdim." + stat.getId().getPath());
        }
        for (JsonObject lang : List.of(zh, en)) {
            for (String key : ownedKeys) {
                helper.assertTrue(hasText(lang, key), "缺少文案键 " + key);
                String value = lang.get(key).getAsString();
                helper.assertTrue(value.indexOf('§') < 0, "文案里不能直接写格式码: " + key + " = " + value);
            }
        }
        helper.succeed();
    }

    // ---- 工具 ----

    /** 第四章一档的规格。 */
    private record TierSpec(FrameType frame, boolean announce, int points, int... colors) {
    }

    /** 第九章一行 (页签根 tier 为 null)。 */
    private static final class Expected {
        final String path;
        @Nullable
        final String tier;
        @Nullable
        final String parent;
        final ResourceLocation icon;
        final String trigger;
        @Nullable
        final ResourceLocation background;
        boolean hidden;
        boolean tacz;
        boolean titled;
        int criteria = 1;

        Expected(String path, @Nullable String tier, @Nullable String parent, String icon, String trigger,
                 @Nullable ResourceLocation background) {
            this.path = path;
            this.tier = tier;
            this.parent = parent;
            this.icon = new ResourceLocation(icon);
            this.trigger = trigger;
            this.background = background;
        }

        Expected hidden() {
            this.hidden = true;
            return this;
        }

        Expected tacz() {
            this.tacz = true;
            return this;
        }

        Expected titled() {
            this.titled = true;
            return this;
        }

        Expected criteria(int count) {
            this.criteria = count;
            return this;
        }
    }

    private static Expected root(String tab, String icon, String backgroundBlock) {
        return new Expected(tab + "/root", null, null, icon, "minecraft:tick",
                new ResourceLocation("minecraft", "textures/block/" + backgroundBlock + ".png"));
    }

    private static Expected row(String path, String tier, String parent, String icon, String trigger) {
        return new Expected(path, tier, parent, icon, trigger, null);
    }

    /** 单色档保留 translate 并整段套主色; 渐变档是 zh_cn 文字的逐字字面量, 首尾字恰为首尾色标, 全部加粗。 */
    private static void assertTitleStyle(GameTestHelper helper, ResourceLocation id, Component title, TierSpec spec,
                                         JsonObject zh) {
        String key = AchievementIds.titleKey(id);
        if (spec.colors.length == 1) {
            helper.assertTrue(title.getContents() instanceof TranslatableContents translatable
                            && translatable.getKey().equals(key) && colorOf(title) == spec.colors[0]
                            && !title.getStyle().isBold(),
                    id + " 的单色标题应为 translate " + key + " 且颜色为 " + hex(spec.colors[0]) + ", 实为 "
                            + Component.Serializer.toJson(title));
            return;
        }
        String text = zh.get(key).getAsString();
        List<Component> pieces = title.getSiblings();
        helper.assertTrue(title.getString().equals(text) && pieces.size() == text.codePointCount(0, text.length()),
                id + " 的渐变标题应逐字拆开 zh_cn 文字 " + text + ", 实为 " + title.getString());
        Component firstPiece = pieces.get(0);
        Component lastPiece = pieces.get(pieces.size() - 1);
        helper.assertTrue(colorOf(firstPiece) == spec.colors[0]
                        && colorOf(lastPiece) == spec.colors[spec.colors.length - 1]
                        && pieces.stream().allMatch(piece -> piece.getStyle().isBold()),
                id + " 的渐变标题首尾字应恰为首尾色标且全部加粗, 实为 " + Component.Serializer.toJson(title));
    }

    private static void assertDone(GameTestHelper helper, ServerPlayer player, String path, boolean done) {
        Advancement advancement = player.getServer().getAdvancements().getAdvancement(AchievementIds.id(path));
        helper.assertTrue(advancement != null, "进度未加载: " + path);
        boolean actual = player.getAdvancements().getOrStartProgress(advancement).isDone();
        helper.assertTrue(actual == done, path + (done ? " 此时应已获得" : " 此时不应获得"));
    }

    /** 领取的四步 (标记已领取、入账、写流水、发称号) 都写在同一条事务连接上。 */
    private static void claim(GameTestHelper helper, SqliteAchievementRewardRepository rewards, TitleService titles,
                              Connection tx, UUID player, ResourceLocation advancementId) {
        helper.assertTrue(rewards.markClaimed(player, advancementId, 3_000L), "待领取的奖励应能标记为已领取");
        rewards.credit(player, 50L);
        rewards.appendLedger(player, 50L, LedgerReason.CLAIM, advancementId.toString(), 3_000L);
        GrantResult granted = titles.grantInTransaction(tx, player, advancementId, TitleSource.ACHIEVEMENT,
                advancementId.toString());
        helper.assertTrue(granted == GrantResult.GRANTED, "事务内发放称号应为 GRANTED, 实为 " + granted);
    }

    /** 测试用进度; frame 为 null 时不带显示信息。 */
    private static Advancement advancement(ResourceLocation id, @Nullable Advancement parent, @Nullable FrameType frame,
                                           boolean toast, boolean announce, boolean hidden) {
        Advancement.Builder builder = Advancement.Builder.advancement()
                .addCriterion("tick", PlayerTrigger.TriggerInstance.tick());
        if (parent != null) {
            builder.parent(parent);
        }
        if (frame != null) {
            builder.display(new DisplayInfo(new ItemStack(Items.STONE), Component.literal(id.getPath()),
                    Component.literal("test"), null, frame, toast, announce, hidden));
        }
        return builder.build(id);
    }

    private static ResourceLocation testId(String path) {
        return AchievementIds.id("test/" + path);
    }

    private static ResourceLocation itemId(ItemStack stack) {
        return ForgeRegistries.ITEMS.getKey(stack.getItem());
    }

    private static int colorOf(Component piece) {
        TextColor color = piece.getStyle().getColor();
        return color == null ? -1 : color.getValue();
    }

    private static String hex(int color) {
        return String.format("#%06X", color);
    }

    private static JsonElement json(String raw) {
        return JsonParser.parseString(raw);
    }

    private static boolean hasText(JsonObject lang, String key) {
        return lang.has(key) && lang.get(key).isJsonPrimitive() && !lang.get(key).getAsString().isBlank();
    }

    private static Set<String> keysWithPrefix(JsonObject lang, String prefix) {
        Set<String> keys = new TreeSet<>();
        for (String key : lang.keySet()) {
            if (key.startsWith(prefix)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static JsonObject readLang(String language) throws IOException {
        String path = "assets/" + MiningConstants.MODID + "/lang/" + language + ".json";
        InputStream stream = AchievementFoundationGameTests.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) {
            throw new AssertionError("运行时 classpath 找不到资源: " + path);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return GsonHelper.parse(reader);
        }
    }

    private static int countRows(Connection connection, String sql, UUID player) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 模拟领取中途失败让外层事务回滚; 专用类型, 不会误吞断言失败。 */
    private static final class SimulatedFailure extends RuntimeException {
        SimulatedFailure() {
            super("simulated claim failure");
        }
    }
}
