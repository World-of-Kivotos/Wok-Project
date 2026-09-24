package com.miningdim.job.fisher.quality;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.fisher.FishingTooltipHandler;
import com.miningdim.job.fisher.ore.OreFishType;
import com.miningdim.job.fisher.ore.OreFishingItems;
import com.miningdim.job.fisher.size.FishMeasurement;
import com.miningdim.job.fisher.size.FishSizeClass;
import com.miningdim.job.fisher.size.FishSizeNbt;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FishQualityGameTests {

    /**
     * 期望档位逐字转抄 {@code docs/Fisher_Job_DesignSpec.md} 的鱼种品质表, 不从标签或 {@link OreFishType} 反取:
     * 从被测数据反取期望等于把实现复述一遍, 标签生成错了断言也会跟着一起错。改档位必须同时改模型、标签与文档。
     */
    private static final Map<String, FishQuality> DOCUMENTED_BASE_TIERS = Map.of(
            "minecraft:cod", FishQuality.COMMON,
            "minecraft:salmon", FishQuality.COMMON,
            "minecraft:tropical_fish", FishQuality.COMMON,
            "minecraft:pufferfish", FishQuality.FINE,
            "miningdim:iron_ore_fish", FishQuality.COMMON,
            "miningdim:gold_ore_fish", FishQuality.FINE,
            "miningdim:diamond_ore_fish", FishQuality.RARE,
            "miningdim:emerald_ore_fish", FishQuality.EPIC,
            "miningdim:dark_gold_ore_fish", FishQuality.LEGENDARY);

    /** 同一张文档表里 Tide 一侧的代表条目: 每档至少一条, 并覆盖全部条件种类。 */
    private static final Map<String, FishQuality> DOCUMENTED_TIDE_TIERS = Map.ofEntries(
            Map.entry("tide:trout", FishQuality.COMMON),
            Map.entry("tide:anglerfish", FishQuality.COMMON),
            Map.entry("tide:deep_grouper", FishQuality.FINE),
            Map.entry("tide:ember_koi", FishQuality.FINE),
            Map.entry("tide:pike", FishQuality.FINE),
            Map.entry("tide:magma_mackerel", FishQuality.RARE),
            Map.entry("tide:enderfin", FishQuality.RARE),
            Map.entry("tide:sailfish", FishQuality.RARE),
            Map.entry("tide:aquathorn", FishQuality.EPIC),
            Map.entry("tide:oakfish", FishQuality.EPIC),
            Map.entry("tide:soulscaler", FishQuality.EPIC),
            Map.entry("tide:witherfin", FishQuality.LEGENDARY),
            Map.entry("tide:echofin_snapper", FishQuality.LEGENDARY),
            Map.entry("tide:elytrout", FishQuality.LEGENDARY),
            Map.entry("tide:midas_fish", FishQuality.MYTHIC),
            Map.entry("tide:voidseeker", FishQuality.MYTHIC),
            Map.entry("tide:shooting_starfish", FishQuality.MYTHIC));

    /** 文档表的档位人数: 原版 4 + 矿石鱼 5 = 9 条; 装 Tide 1.6.5 时另加 66 条。 */
    private static final int[] DOCUMENTED_BASE_COUNTS = {4, 2, 1, 1, 1, 0};
    private static final int[] DOCUMENTED_WITH_TIDE_COUNTS = {17, 13, 17, 17, 8, 3};

    private FishQualityGameTests() {
    }

    @GameTest(template = "empty", batch = "fish_quality")
    public static void documentedTiersDriveRarity(GameTestHelper helper) {
        Map<String, FishQuality> expected = new HashMap<>(DOCUMENTED_BASE_TIERS);
        if (supportedTide()) {
            expected.putAll(DOCUMENTED_TIDE_TIERS);
        }
        expected.forEach((id, quality) -> {
            ItemStack stack = stackOf(id);
            helper.assertTrue(FishQuality.of(stack) == quality,
                    id + " 的鱼种品质应为 " + quality.id() + ", 实得 " + FishQuality.of(stack));
            helper.assertTrue(stack.getRarity() == quality.rarity(),
                    id + " 的物品名稀有度必须来自鱼种品质 " + quality.id());
        });
        for (OreFishType type : OreFishType.values()) {
            ItemStack fish = new ItemStack(OreFishingItems.FISH.get(type).get());
            ItemStack soup = new ItemStack(OreFishingItems.SOUPS.get(type).get());
            helper.assertTrue(FishQuality.of(fish) == type.quality(),
                    type.id() + " 的 OreFishType 品质与品质标签分叉了");
            helper.assertTrue(soup.getRarity() == type.quality().rarity() && FishQuality.of(soup) == null,
                    type.id() + " 鱼羹不进品质标签, 名字颜色跟随原料鱼的品质");
        }
        helper.assertTrue(FishQuality.of(new ItemStack(Items.STICK)) == null
                        && new ItemStack(Items.STICK).getRarity() == Rarity.COMMON,
                "非鱼物品不得被品质接缝认领");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "fish_quality")
    public static void shippedTierTagsAreDisjointAndMatchDocumentedCounts(GameTestHelper helper) {
        Map<Item, FishQuality> seen = new HashMap<>();
        int[] counts = new int[FishQuality.values().length];
        for (FishQuality quality : FishQuality.values()) {
            for (var holder : BuiltInRegistries.ITEM.getTagOrEmpty(quality.tag())) {
                FishQuality previous = seen.put(holder.value(), quality);
                helper.assertTrue(previous == null, BuiltInRegistries.ITEM.getKey(holder.value())
                        + " 同时出现在 " + previous + " 与 " + quality + " 两个品质标签里");
                counts[quality.ordinal()]++;
            }
        }
        int[] expected = supportedTide() ? DOCUMENTED_WITH_TIDE_COUNTS : DOCUMENTED_BASE_COUNTS;
        for (FishQuality quality : FishQuality.values()) {
            helper.assertTrue(counts[quality.ordinal()] == expected[quality.ordinal()],
                    quality.id() + " 档应有 " + expected[quality.ordinal()] + " 条, 实得 " + counts[quality.ordinal()]);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "fish_quality")
    public static void extendedRaritiesAreNamespacedAndColored(GameTestHelper helper) {
        // Rarity.create 按名字忽略大小写去重: 名字不带前缀就可能拿到别的 mod 的同名实例和颜色。
        helper.assertTrue(FishQuality.LEGENDARY.rarity().name().equals("MININGDIM_LEGENDARY")
                        && FishQuality.LEGENDARY.rarity().color == ChatFormatting.GOLD,
                "传说档必须是带命名空间前缀的金色扩展稀有度");
        helper.assertTrue(FishQuality.MYTHIC.rarity().name().equals("MININGDIM_MYTHIC")
                        && FishQuality.MYTHIC.rarity().color == ChatFormatting.RED,
                "神话档必须是带命名空间前缀的红色扩展稀有度");
        helper.assertTrue(FishQuality.COMMON.rarity() == Rarity.COMMON && FishQuality.FINE.rarity() == Rarity.UNCOMMON
                        && FishQuality.RARE.rarity() == Rarity.RARE && FishQuality.EPIC.rarity() == Rarity.EPIC,
                "前四档复用原版稀有度");
        // 服务端拼的聊天栏 [物品] 名也要带品质颜色, 这条路径不经过客户端 tooltip。
        Component displayName = new ItemStack(OreFishingItems.FISH.get(OreFishType.DARK_GOLD).get()).getDisplayName();
        helper.assertTrue(TextColor.fromLegacyFormat(ChatFormatting.GOLD).equals(displayName.getStyle().getColor()),
                "暗金鱼的聊天物品名必须是传说档金色, 实得 " + displayName.getStyle().getColor());
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "fish_quality")
    public static void enchantedFishKeepsItsQualityRarity(GameTestHelper helper) {
        // 原版附魔会把 COMMON 升到 RARE; 品质档是鱼种的权威颜色, 附魔不得改写。其它物品仍按原版升档。
        ItemStack cod = new ItemStack(Items.COD);
        cod.enchant(Enchantments.UNBREAKING, 1);
        helper.assertTrue(cod.isEnchanted() && cod.getRarity() == Rarity.COMMON,
                "附魔过的鳕鱼仍是普通档, 实得 " + cod.getRarity());
        ItemStack stick = new ItemStack(Items.STICK);
        stick.enchant(Enchantments.UNBREAKING, 1);
        helper.assertTrue(stick.getRarity() == Rarity.RARE, "未归档物品必须保留原版附魔升档");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "fish_quality")
    public static void tooltipShowsQualityAndSizeBelowTheName(GameTestHelper helper) {
        ItemStack trophy = new ItemStack(OreFishingItems.FISH.get(OreFishType.EMERALD).get());
        FishSizeNbt.stamp(trophy, new FishMeasurement(598, 2_345_000L, FishSizeClass.TROPHY),
                java.util.UUID.randomUUID(), "Angler", 100L);
        List<Component> lines = new ArrayList<>(List.of(trophy.getHoverName(), Component.literal("tail")));
        new FishingTooltipHandler().onItemTooltip(new ItemTooltipEvent(trophy,
                MockGameTestPlayers.makeMockServerPlayerWithChannel(helper), lines, TooltipFlag.Default.NORMAL));
        helper.assertTrue(lines.size() == 6, "奖杯鱼 tooltip 应插入品质、体型、尺寸、钓获者四行, 实得 " + lines.size());
        helper.assertTrue(keyOf(lines.get(1)).equals("tooltip.miningdim.fishing.quality")
                        && keyOf(lines.get(2)).equals("tooltip.miningdim.fishing.size")
                        && keyOf(lines.get(3)).equals("tooltip.miningdim.fishing.trophy")
                        && keyOf(lines.get(4)).equals("tooltip.miningdim.fishing.caught_by")
                        && lines.get(5).getString().equals("tail"),
                "品质与体型必须紧跟在物品名之后, 原有行顺延");
        List<Component> plain = new ArrayList<>(List.of(Component.literal("stick")));
        new FishingTooltipHandler().onItemTooltip(new ItemTooltipEvent(new ItemStack(Items.STICK),
                MockGameTestPlayers.makeMockServerPlayerWithChannel(helper), plain, TooltipFlag.Default.NORMAL));
        helper.assertTrue(plain.size() == 1, "非鱼物品 tooltip 不得被改动");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "fish_quality")
    public static void qualityAndSizeTextIsTranslated(GameTestHelper helper) {
        JsonObject zh = loadJsonResource("/assets/miningdim/lang/zh_cn.json");
        JsonObject en = loadJsonResource("/assets/miningdim/lang/en_us.json");
        for (FishQuality quality : FishQuality.values()) {
            assertTranslated(helper, zh, en, quality.translationKey());
        }
        for (FishSizeClass sizeClass : FishSizeClass.values()) {
            assertTranslated(helper, zh, en, "fishing.miningdim.size." + sizeClass.id());
        }
        for (String key : List.of("tooltip.miningdim.fishing.quality", "tooltip.miningdim.fishing.size",
                "tooltip.miningdim.fishing.trophy", "tooltip.miningdim.fishing.caught_by",
                "message.miningdim.fishing.catch", "message.miningdim.fishing.catch.record",
                "message.miningdim.fishing.catch.trophy", "message.miningdim.fishing.sell.no_fish_inventory",
                "screen.miningdim.fishing_journal.quality", "screen.miningdim.fishing_journal.records",
                "screen.miningdim.fishing_journal.records.count", "screen.miningdim.fishing_journal.records.best",
                "screen.miningdim.fishing_journal.records.none")) {
            assertTranslated(helper, zh, en, key);
        }
        helper.succeed();
    }

    private static boolean supportedTide() {
        return ModList.get().getModContainerById("tide")
                .map(mod -> mod.getModInfo().getVersion().toString().equals("1.6.5")).orElse(false);
    }

    private static ItemStack stackOf(String id) {
        return new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation(id)));
    }

    private static String keyOf(Component component) {
        return component.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translatable
                ? translatable.getKey() : "";
    }

    private static void assertTranslated(GameTestHelper helper, JsonObject zh, JsonObject en, String key) {
        helper.assertTrue(zh.has(key) && !zh.get(key).getAsString().isBlank(), "missing zh_cn translation " + key);
        helper.assertTrue(en.has(key) && !en.get(key).getAsString().isBlank(), "missing en_us translation " + key);
    }

    private static JsonObject loadJsonResource(String path) {
        try (InputStream in = FishQualityGameTests.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("JSON resource not found on classpath: " + path);
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("failed reading JSON resource: " + path, exception);
        }
    }
}
