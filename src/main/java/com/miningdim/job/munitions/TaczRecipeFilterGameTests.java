package com.miningdim.job.munitions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprint;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * TaCZ 配方旁路 (审计 V03) 与图纸弹源 (口径补全) 的回归测试。
 *
 * 旧的 data/miningdim/recipe_filters/munitions_disable_tacz.json 注册成 miningdim:munitions_disable_tacz, 没有任何
 * TaCZ 工作台指向它, 黑名单从未生效; 玩家放一张 TaCZ 枪械台就能零信用点造枪造弹。现改为
 * data/tacz/recipe_filters/default.json: TaCZ 1.1.8 的 RecipeFilterManager 用 FileToIdConverter("recipe_filters")
 * 按文件路径取 id, 同 id 的多份文件经 RecipeFilter.merge 把白/黑名单各自 addAll (均 javap 核实), 所以本文件并进
 * 三个工作台方块数据里写死的 tacz:default。
 *
 * dev GameTest 不加载 TaCZ (build.gradle compileOnly), TimelessAPI 里真正生效的过滤器拿不到。这里退一步:
 * 用服务端真实 ResourceManager 按 TaCZ 同一套扫描规则找出所有并入 tacz:default 的文件, 叠上 TaCZ 默认枪包自带的那份
 * (内容按 jar 原文还原), 再按 RecipeFilter.contains 的字节码语义逐条判定真实配方 id。配方 id 清单取自
 * tacz-1.20.1-1.1.8-hotfix.jar 的 assets/tacz/custom/tacz_default_gun/data/tacz/recipes/ 目录 (ammo 24 / gun 53 /
 * attachments 95), 形如 tacz:ammo/9mm、tacz:gun/ak47、tacz:attachments/ammo_mod_he。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class TaczRecipeFilterGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "tacz_recipe_filter";

    /** TaCZ RecipeFilterManager 的扫描口径 (构造器里的 FileToIdConverter.json("recipe_filters"))。 */
    private static final FileToIdConverter FILTER_LISTER = FileToIdConverter.json("recipe_filters");
    /** gun_smith_table / ammo_workbench / attachment_workbench 三份方块数据的 filter 字段。 */
    private static final ResourceLocation TACZ_DEFAULT_FILTER = new ResourceLocation("tacz", "default");
    /** 旧的死过滤器 id; 留着只会让人误以为 TaCZ 配方已禁用。 */
    private static final ResourceLocation DEAD_FILTER = new ResourceLocation(MiningConstants.MODID, "munitions_disable_tacz");

    /** TaCZ 默认枪包自带的 tacz:default 原文 (去掉注释): 白名单 ^.*$, 黑名单只有 tacz:test。 */
    private static final String TACZ_DEFAULT_PACK_FILTER = "{\"whitelist\":[\"^.*$\"],\"blacklist\":[\"tacz:test\"]}";

    /** 默认枪包 recipes/ammo/ 全部 24 个文件名, 与 data/tacz/index/ammo/ 一一对应。 */
    private static final List<String> TACZ_AMMO_RECIPES = List.of(
            "12g", "22wmr", "308", "30_06", "338", "357mag", "40mm", "45acp", "45_70", "46x30", "500mag", "50ae",
            "50bmg", "545x39", "556x45", "57x28", "58x42", "68x51fury", "762x25", "762x39", "762x54", "792x57", "9mm",
            "rpg_rocket");

    /** 默认枪包 recipes/gun/ 全部 53 个文件名。 */
    private static final List<String> TACZ_GUN_RECIPES = List.of(
            "aa12", "ai_awp", "ak47", "aug", "b93r", "cz75", "db_long", "db_short", "deagle", "deagle_golden",
            "fn_evolys", "fn_fal", "g36k", "glock_17", "hk416d", "hk_g3", "hk_mk23", "hk_mp5a5", "kar98", "lonetrail",
            "m1014", "m107", "m16a1", "m16a4", "m1911", "m249", "m320", "m4a1", "m700", "m870", "m95", "m9a4",
            "minigun", "mk14", "p320", "p90", "qbz_191", "qbz_95", "rhino357", "rpg7", "rpk", "scar_h", "scar_l",
            "sks_tactical", "spas_12", "spr15hb", "springfield1873", "taurus500", "timeless50", "type_81", "ump45",
            "uzi", "vector45");

    /**
     * recipes/attachments/ 的抽样。产品决定是保留配件 (含 HE 弹头改装), 五个 ammo_mod_* 全部列上: 它们的 path 以
     * ammo 开头, 是正则写宽时最先被误伤的一类。
     */
    private static final List<String> TACZ_ATTACHMENT_RECIPE_SAMPLES = List.of(
            "ammo_mod_he", "ammo_mod_fmj", "ammo_mod_hp", "ammo_mod_i", "ammo_mod_slug", "extended_mag_3",
            "light_extended_mag_1", "sniper_extended_mag_3", "shotgun_extended_mag_2", "scope_acog_ta31",
            "sight_rmr_dot", "muzzle_silencer_ptilopsis", "muzzle_compensator_trident", "grip_magpul_afg_2",
            "stock_militech_b5", "laser_compact", "bayonet_m9");

    /**
     * 每张图纸对应枪的弹药 id, 逐个取自枪数据文件的 ammo 字段: tacz 枪查 jar 与测试端 tacz_default_gun 的
     * data/tacz/data/guns/*_data.json; 第三方枪查测试端枪包 (ClassicRCCRP 的 ccrp:m1887_long/mpx, HareTactics 的
     * hare:ksg, sxgunpack 的 wyyc1991:stl, lavender_converted 的 lavender:smle_iii)。枪匠自建的 miningdim 连发变体
     * 同样吃 tacz:556x45。新增图纸必须在这里补一行, 否则下面的覆盖断言会先红。
     */
    private static final Map<GunsmithBlueprint, String> BLUEPRINT_AMMO = Map.ofEntries(
            Map.entry(GunsmithBlueprint.M4A1, "tacz:556x45"),
            Map.entry(GunsmithBlueprint.M16A1, "tacz:556x45"),
            Map.entry(GunsmithBlueprint.M16A4, "tacz:556x45"),
            Map.entry(GunsmithBlueprint.HK416D, "tacz:556x45"),
            Map.entry(GunsmithBlueprint.SPR15HB, "tacz:556x45"),
            Map.entry(GunsmithBlueprint.AK47, "tacz:762x39"),
            Map.entry(GunsmithBlueprint.RPK, "tacz:762x39"),
            Map.entry(GunsmithBlueprint.TYPE_81, "tacz:762x39"),
            Map.entry(GunsmithBlueprint.M1911, "tacz:45acp"),
            Map.entry(GunsmithBlueprint.M870, "tacz:12g"),
            Map.entry(GunsmithBlueprint.M1887_LONG, "tacz:12g"),
            Map.entry(GunsmithBlueprint.KSG, "tacz:12g"),
            Map.entry(GunsmithBlueprint.M1014, "tacz:12g"),
            Map.entry(GunsmithBlueprint.UZI, "tacz:9mm"),
            Map.entry(GunsmithBlueprint.UMP45, "tacz:45acp"),
            Map.entry(GunsmithBlueprint.HK_MP5A5, "tacz:9mm"),
            Map.entry(GunsmithBlueprint.STERLING, "tacz:9mm"),
            Map.entry(GunsmithBlueprint.MPX, "tacz:9mm"),
            Map.entry(GunsmithBlueprint.KAR98K, "tacz:792x57"),
            Map.entry(GunsmithBlueprint.SMLE_III, "lavender:british0x303"),
            Map.entry(GunsmithBlueprint.M700, "tacz:30_06"));

    private TaczRecipeFilterGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void recipeFilterOverlayRegistersUnderTaczDefaultId(GameTestHelper helper) {
        Map<ResourceLocation, List<Resource>> filters = scanRecipeFilters(helper);
        List<Resource> taczDefault = filters.get(TACZ_DEFAULT_FILTER);
        helper.assertTrue(taczDefault != null && !taczDefault.isEmpty(),
                "服务端数据里必须有并入 tacz:default 的过滤器文件 (data/tacz/recipe_filters/default.json);"
                        + " 实际扫到的过滤器 id: " + filters.keySet());
        helper.assertFalse(filters.containsKey(DEAD_FILTER),
                "miningdim:munitions_disable_tacz 没有任何 TaCZ 工作台引用, 不得再留着冒充禁用配方");
        // dev 运行期只有本 mod 带 recipe_filters (TaCZ 未加载), 扫到的每个 id 都来自本 mod。
        for (ResourceLocation id : filters.keySet()) {
            helper.assertTrue(TACZ_DEFAULT_FILTER.equals(id),
                    "本 mod 只应往 tacz:default 里合并规则; 另起的过滤器 id 不会被任何工作台读到: " + id);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mergedTaczDefaultFilterBlocksGunsAndAmmoButKeepsAttachments(GameTestHelper helper) {
        MergedRecipeFilter filter = effectiveTaczDefaultFilter(helper);

        helper.assertTrue(TACZ_AMMO_RECIPES.size() == 24 && TACZ_GUN_RECIPES.size() == 53,
                "配方清单须与 TaCZ 1.1.8 默认枪包的 ammo 24 / gun 53 个文件对齐");
        for (String ammo : TACZ_AMMO_RECIPES) {
            String recipeId = "tacz:ammo/" + ammo;
            helper.assertFalse(filter.contains(recipeId), "TaCZ 弹药配方必须被 tacz:default 拒绝: " + recipeId);
        }
        for (String gun : TACZ_GUN_RECIPES) {
            String recipeId = "tacz:gun/" + gun;
            helper.assertFalse(filter.contains(recipeId), "TaCZ 枪械配方必须被 tacz:default 拒绝: " + recipeId);
        }
        for (String attachment : TACZ_ATTACHMENT_RECIPE_SAMPLES) {
            String recipeId = "tacz:attachments/" + attachment;
            helper.assertTrue(filter.contains(recipeId),
                    "配件配方必须继续放行 (产品决定允许 HE 等配件): " + recipeId);
        }
        // 默认枪包原有的 tacz:test 黑名单必须保留: 合并只能追加, 不能把别人的规则冲掉。
        helper.assertFalse(filter.contains("tacz:test"), "合并后仍须保留默认枪包的 tacz:test 黑名单");

        // 两半对账: 军火台产出的每个 tacz 弹药, TaCZ 台子那头都得被关掉, 否则军火台不是唯一弹源。
        for (MunitionsCaliber caliber : MunitionsCaliber.values()) {
            if (!MunitionsCaliber.TACZ_NAMESPACE.equals(caliber.ammoNamespace())) {
                continue;
            }
            helper.assertTrue(TACZ_AMMO_RECIPES.contains(caliber.defaultAmmoPath()),
                    caliber + " 的弹药 " + caliber.defaultAmmoPath() + " 不在 TaCZ 默认枪包的弹药清单里");
            helper.assertFalse(filter.contains("tacz:ammo/" + caliber.defaultAmmoPath()),
                    caliber + " 的弹药在 TaCZ 弹药台上仍可合成");
        }
        for (GunsmithBlueprint blueprint : GunsmithBlueprint.values()) {
            if (!"tacz".equals(blueprint.gunId().getNamespace())) {
                continue;
            }
            helper.assertTrue(TACZ_GUN_RECIPES.contains(blueprint.templateId()),
                    blueprint + " 的枪 " + blueprint.gunId() + " 不在 TaCZ 默认枪包的枪械配方清单里");
            helper.assertFalse(filter.contains("tacz:gun/" + blueprint.templateId()),
                    blueprint + " 的枪在 TaCZ 枪械台上仍可直接合成, 绕过枪匠装配");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyGunsmithBlueprintAmmoComesFromSomeMunitionsCaliber(GameTestHelper helper) {
        helper.assertTrue(BLUEPRINT_AMMO.size() == GunsmithBlueprint.values().length,
                "图纸弹药表必须覆盖全部 " + GunsmithBlueprint.values().length + " 张图纸, 实有 " + BLUEPRINT_AMMO.size());
        Set<String> producible = new HashSet<>();
        for (MunitionsCaliber caliber : MunitionsCaliber.values()) {
            producible.add(caliber.ammoNamespace() + ":" + caliber.defaultAmmoPath());
        }
        for (GunsmithBlueprint blueprint : GunsmithBlueprint.values()) {
            String ammo = BLUEPRINT_AMMO.get(blueprint);
            helper.assertTrue(ammo != null, blueprint + " 没有登记弹药 id");
            helper.assertTrue(producible.contains(ammo),
                    blueprint + " 用 " + ammo + ", 军火台没有任何口径能产; TaCZ 配方已禁用, 这张图纸造出来打不响");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void appendedCalibersKeepStableIndicesAndTierGates(GameTestHelper helper) {
        // 序号只追加: 第 i 个枚举常量的 index 必须就是 i, 前 10 档的序号与存量 NBT 一致。
        MunitionsCaliber[] calibers = MunitionsCaliber.values();
        for (int i = 0; i < calibers.length; i++) {
            helper.assertTrue(calibers[i].index() == i,
                    calibers[i] + " 的 index 应为 " + i + ", 实为 " + calibers[i].index() + "; 口径序号只能追加不能重排");
        }
        helper.assertTrue(MunitionsCaliber.RIFLE_556.index() == 9, "存量最后一档 RIFLE_556 必须仍是 9");
        assertAppended(helper, MunitionsCaliber.PISTOL_45ACP, 10, 1, MunitionsCaliber.PISTOL, "tacz", "45acp");
        assertAppended(helper, MunitionsCaliber.SNIPER_3006, 11, 6, MunitionsCaliber.SNIPER, "tacz", "30_06");
        assertAppended(helper, MunitionsCaliber.SNIPER_792, 12, 6, MunitionsCaliber.SNIPER, "tacz", "792x57");
        assertAppended(helper, MunitionsCaliber.SNIPER_303, 13, 6, MunitionsCaliber.SNIPER, "lavender", "british0x303");

        // 追加的同级口径不得抢走 highestUnlockedCaliber 的既有结果 (平级时保留先声明的那档)。
        helper.assertTrue(MunitionsLevels.highestUnlockedCaliber(1) == MunitionsCaliber.PISTOL,
                "L1 最高口径仍是 PISTOL");
        helper.assertTrue(MunitionsLevels.highestUnlockedCaliber(6) == MunitionsCaliber.SNIPER,
                "L6 最高口径仍是 SNIPER");
        helper.assertFalse(MunitionsLevels.isCaliberUnlocked(5, MunitionsCaliber.SNIPER_3006),
                "L5 不得解锁 .30-06 (狙击档 L6)");
        helper.assertTrue(MunitionsLevels.isCaliberUnlocked(1, MunitionsCaliber.PISTOL_45ACP),
                "L1 即解锁 .45 ACP (手枪档)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyCaliberHasLangKeyInBothLocales(GameTestHelper helper) {
        JsonObject zh = loadJsonResource("/assets/miningdim/lang/zh_cn.json");
        JsonObject en = loadJsonResource("/assets/miningdim/lang/en_us.json");
        for (MunitionsCaliber caliber : MunitionsCaliber.values()) {
            String key = "munitions.caliber." + caliber.name().toLowerCase(Locale.ROOT);
            helper.assertTrue(zh.has(key) && !zh.get(key).getAsString().isBlank(), "zh_cn 缺口径键 " + key);
            helper.assertTrue(en.has(key) && !en.get(key).getAsString().isBlank(), "en_us 缺口径键 " + key);
        }
        helper.succeed();
    }

    private static void assertAppended(GameTestHelper helper, MunitionsCaliber caliber, int index, int unlockLevel,
                                       MunitionsCaliber priceTier, String namespace, String path) {
        helper.assertTrue(caliber.index() == index, caliber + " index 应为 " + index);
        helper.assertTrue(caliber.unlockLevel() == unlockLevel, caliber + " 解锁等级应为 L" + unlockLevel);
        helper.assertTrue(caliber.category() == priceTier.category(), caliber + " 应与 " + priceTier + " 同类别");
        helper.assertTrue(caliber.shopPrice() == priceTier.shopPrice()
                        && caliber.sellPrice() == priceTier.sellPrice()
                        && caliber.yieldFactor() == priceTier.yieldFactor(),
                caliber + " 的商店价/售价/缩产系数应沿用 " + priceTier + " 档");
        helper.assertTrue(namespace.equals(caliber.ammoNamespace()) && path.equals(caliber.defaultAmmoPath()),
                caliber + " 弹药应为 " + namespace + ":" + path + ", 实为 "
                        + caliber.ammoNamespace() + ":" + caliber.defaultAmmoPath());
    }

    private static Map<ResourceLocation, List<Resource>> scanRecipeFilters(GameTestHelper helper) {
        Map<ResourceLocation, List<Resource>> byFileId =
                FILTER_LISTER.listMatchingResourceStacks(helper.getLevel().getServer().getResourceManager());
        Map<ResourceLocation, List<Resource>> byFilterId = new HashMap<>();
        for (Map.Entry<ResourceLocation, List<Resource>> entry : byFileId.entrySet()) {
            byFilterId.put(FILTER_LISTER.fileToId(entry.getKey()), entry.getValue());
        }
        return byFilterId;
    }

    /** 默认枪包那份 + 服务端数据里所有并入 tacz:default 的文件, 按 RecipeFilterManager 的 compute/merge 叠起来。 */
    private static MergedRecipeFilter effectiveTaczDefaultFilter(GameTestHelper helper) {
        MergedRecipeFilter filter = new MergedRecipeFilter();
        filter.merge(JsonParser.parseString(TACZ_DEFAULT_PACK_FILTER).getAsJsonObject());
        List<Resource> overlays = scanRecipeFilters(helper).get(TACZ_DEFAULT_FILTER);
        if (overlays == null || overlays.isEmpty()) {
            throw new IllegalStateException("服务端数据里没有并入 tacz:default 的过滤器文件");
        }
        for (Resource resource : overlays) {
            try (Reader reader = resource.openAsReader()) {
                JsonReader json = new JsonReader(reader);
                // TaCZ 读过滤器用 GsonHelper 宽松模式 (默认枪包的原文带 // 注释)。
                json.setLenient(true);
                filter.merge(JsonParser.parseReader(json).getAsJsonObject());
            } catch (IOException exception) {
                throw new IllegalStateException("读取 tacz:default 过滤器失败: " + resource.sourcePackId(), exception);
            }
        }
        return filter;
    }

    private static JsonObject loadJsonResource(String path) {
        try (InputStream in = TaczRecipeFilterGameTests.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("找不到资源: " + path);
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException exception) {
            throw new IllegalStateException("读取资源失败: " + path, exception);
        }
    }

    /**
     * TaCZ 1.1.8 RecipeFilter 的判定语义 (javap 核实):
     *  - Deserializer.loadFilters: 以 ^ 开头的串编成 RegexFilter (Matcher.matches 全串匹配), 其余按 ResourceLocation
     *    字面量收进同一个 LiteralFilter, 每个出现的数组都会补上这一个 LiteralFilter; 键缺省则什么都不加;
     *  - merge: 两张名单各自 addAll;
     *  - contains: 初值 = 白名单为空, 白名单任一命中置真, 再由黑名单任一命中置假 (黑名单优先)。
     */
    private static final class MergedRecipeFilter {
        private final List<Predicate<String>> whitelist = new ArrayList<>();
        private final List<Predicate<String>> blacklist = new ArrayList<>();

        void merge(JsonObject json) {
            load(json, "whitelist", whitelist);
            load(json, "blacklist", blacklist);
        }

        boolean contains(String recipeId) {
            boolean result = whitelist.isEmpty();
            for (Predicate<String> entry : whitelist) {
                if (entry.test(recipeId)) {
                    result = true;
                    break;
                }
            }
            for (Predicate<String> entry : blacklist) {
                if (entry.test(recipeId)) {
                    result = false;
                    break;
                }
            }
            return result;
        }

        private static void load(JsonObject json, String key, List<Predicate<String>> into) {
            if (!json.has(key)) {
                return;
            }
            JsonArray array = json.getAsJsonArray(key);
            Set<String> literals = new HashSet<>();
            for (JsonElement element : array) {
                String value = element.getAsString();
                if (value.startsWith("^")) {
                    Pattern pattern = Pattern.compile(value);
                    into.add(id -> pattern.matcher(id).matches());
                } else {
                    ResourceLocation literal = ResourceLocation.tryParse(value);
                    if (literal != null) {
                        literals.add(literal.toString());
                    }
                }
            }
            into.add(literals::contains);
        }
    }
}
