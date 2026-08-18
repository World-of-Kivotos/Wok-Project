package com.miningdim.power.mineral;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** 矿物方块模型、物品模型与破坏粒子纹理继承链的资源回归。 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PowerMineralAssetGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "power_mineral_assets";
    private static final String ASSET_ROOT = "/assets/miningdim/";
    private static final String TEMPLATE_MODEL = ASSET_ROOT + "models/block/tinted_ore.json";
    private static final Set<String> CUBE_FACES = Set.of("down", "up", "north", "south", "west", "east");
    private static final Set<String> EXPECTED_POWER_ORE_IDS = Set.of(
            "bauxite_ore", "deepslate_bauxite_ore",
            "borax_ore", "deepslate_borax_ore",
            "silver_ore", "deepslate_silver_ore",
            "tin_ore", "deepslate_tin_ore",
            "nickel_ore", "deepslate_nickel_ore",
            "chromium_ore", "deepslate_chromium_ore",
            "tungsten_ore", "deepslate_tungsten_ore");

    private PowerMineralAssetGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyOreModelResolvesParticleTexture(GameTestHelper helper) {
        JsonObject template = loadJson(TEMPLATE_MODEL);
        JsonObject templateTextures = requireObject(template, "textures", TEMPLATE_MODEL);
        helper.assertTrue("#ore".equals(requireString(templateTextures, "particle", TEMPLATE_MODEL)),
                "公共矿石模型的 particle 必须引用接受矿种染色的 #ore 纹理");
        helper.assertTrue("miningdim:block/ore_overlay".equals(
                        requireString(templateTextures, "ore", TEMPLATE_MODEL)),
                "公共矿石模型必须继续使用矿物覆盖层贴图");
        assertPngResource(helper, ASSET_ROOT + "textures/block/ore_overlay.png");

        Set<String> actualOreIds = new HashSet<>();
        for (PowerMineral mineral : PowerMineral.values()) {
            actualOreIds.add(mineral.oreId());
            actualOreIds.add(mineral.deepslateOreId());
            assertOreVariant(helper, templateTextures, mineral.oreId(), "minecraft:block/stone");
            assertOreVariant(helper, templateTextures, mineral.deepslateOreId(), "minecraft:block/deepslate");
        }
        helper.assertTrue(EXPECTED_POWER_ORE_IDS.equals(actualOreIds),
                "能源矿物资源必须精确覆盖 14 个既定方块，实得 " + actualOreIds);
        assertLegacyFakeOreAssets(helper);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tintedOreModelCullsBuriedFacesBeforeLighting(GameTestHelper helper) {
        JsonObject template = loadJson(TEMPLATE_MODEL);
        helper.assertTrue("minecraft:block/block".equals(requireString(template, "parent", TEMPLATE_MODEL)),
                "公共矿石模型必须继承原版 block 父模型以保留标准物品展示变换");
        helper.assertTrue(!template.has("ambientocclusion") || template.get("ambientocclusion").getAsBoolean(),
                "公共矿石模型不得关闭环境光遮蔽来掩盖内部面渲染问题");
        helper.assertTrue("minecraft:cutout".equals(requireString(template, "render_type", TEMPLATE_MODEL)),
                "公共矿石模型必须继续使用 cutout 渲染透明覆盖层");

        JsonArray elements = requireArray(template, "elements", TEMPLATE_MODEL);
        helper.assertTrue(elements.size() == 2,
                "公共矿石模型必须精确包含石质基底与矿脉覆盖两层，实得 " + elements.size());
        assertCulledCubeElement(helper, elements.get(0), "#base", false, "石质基底");
        assertCulledCubeElement(helper, elements.get(1), "#ore", true, "矿脉覆盖层");
        helper.succeed();
    }

    private static void assertCulledCubeElement(GameTestHelper helper, JsonElement element,
                                                String expectedTexture, boolean tinted, String layerName) {
        if (!element.isJsonObject()) {
            throw new IllegalStateException(TEMPLATE_MODEL + " 的" + layerName + "必须是 JSON 对象");
        }
        JsonObject elementObject = element.getAsJsonObject();
        helper.assertTrue(!elementObject.has("shade") || elementObject.get("shade").getAsBoolean(),
                layerName + "不得关闭方向明暗来掩盖内部面渲染问题");
        JsonObject faces = requireObject(elementObject, "faces", TEMPLATE_MODEL + " " + layerName);
        helper.assertTrue(CUBE_FACES.equals(faces.keySet()),
                layerName + "必须精确包含六个标准方向面，实得 " + faces.keySet());
        for (String direction : CUBE_FACES) {
            JsonObject face = requireObject(faces, direction, TEMPLATE_MODEL + " " + layerName);
            helper.assertTrue(expectedTexture.equals(requireString(face, "texture", TEMPLATE_MODEL)),
                    layerName + "的 " + direction + " 面必须使用 " + expectedTexture);
            helper.assertTrue(direction.equals(requireString(face, "cullface", TEMPLATE_MODEL)),
                    layerName + "的 " + direction + " 面必须对同方向实体邻块执行剔除");
            if (tinted) {
                helper.assertTrue(face.has("tintindex") && face.get("tintindex").getAsInt() == 0,
                        layerName + "的 " + direction + " 面必须使用 tintindex 0");
            } else {
                helper.assertTrue(!face.has("tintindex"),
                        layerName + "的 " + direction + " 面不得染色石质基底");
            }
        }
    }

    private static void assertOreVariant(GameTestHelper helper, JsonObject templateTextures,
                                         String blockId, String expectedBaseTexture) {
        String blockStatePath = ASSET_ROOT + "blockstates/" + blockId + ".json";
        JsonObject variants = requireObject(loadJson(blockStatePath), "variants", blockStatePath);
        helper.assertTrue(variants.size() == 1,
                blockId + " 方块状态必须只有无属性默认变体，实得 " + variants.keySet());
        JsonObject defaultVariant = requireObject(variants, "", blockStatePath);
        String expectedBlockModel = "miningdim:block/" + blockId;
        helper.assertTrue(expectedBlockModel.equals(requireString(defaultVariant, "model", blockStatePath)),
                blockId + " 默认变体必须引用 " + expectedBlockModel);

        String blockModelPath = ASSET_ROOT + "models/block/" + blockId + ".json";
        JsonObject blockModel = loadJson(blockModelPath);
        helper.assertTrue("miningdim:block/tinted_ore".equals(requireString(blockModel, "parent", blockModelPath)),
                blockId + " 必须继承公共矿石模型");
        JsonObject childTextures = requireObject(blockModel, "textures", blockModelPath);
        helper.assertTrue(expectedBaseTexture.equals(requireString(childTextures, "base", blockModelPath)),
                blockId + " 基底纹理必须为 " + expectedBaseTexture);
        String resolvedParticle = resolveTexture("particle", childTextures, templateTextures, blockModelPath);
        helper.assertTrue("miningdim:block/ore_overlay".equals(resolvedParticle),
                blockId + " 的 particle 必须最终解析为 miningdim:block/ore_overlay"
                        + "，实得 " + resolvedParticle);

        String itemModelPath = ASSET_ROOT + "models/item/" + blockId + ".json";
        JsonObject itemModel = loadJson(itemModelPath);
        helper.assertTrue(expectedBlockModel.equals(requireString(itemModel, "parent", itemModelPath)),
                blockId + " 物品模型必须继承同名方块模型");
    }

    private static void assertLegacyFakeOreAssets(GameTestHelper helper) {
        String blockStatePath = ASSET_ROOT + "blockstates/fake_ore.json";
        JsonObject variants = requireObject(loadJson(blockStatePath), "variants", blockStatePath);
        helper.assertTrue(variants.size() == 1,
                "fake_ore 方块状态必须只有无属性默认变体，实得 " + variants.keySet());
        JsonObject defaultVariant = requireObject(variants, "", blockStatePath);
        helper.assertTrue("miningdim:block/fake_ore".equals(
                        requireString(defaultVariant, "model", blockStatePath)),
                "fake_ore 默认变体必须引用同名方块模型");

        String blockModelPath = ASSET_ROOT + "models/block/fake_ore.json";
        JsonObject blockModel = loadJson(blockModelPath);
        helper.assertTrue("miningdim:block/trap_ore".equals(requireString(blockModel, "parent", blockModelPath)),
                "fake_ore 必须复用现有 trap_ore 石质模型");
        JsonObject textures = requireObject(blockModel, "textures", blockModelPath);
        helper.assertTrue("minecraft:block/stone".equals(requireString(textures, "particle", blockModelPath)),
                "fake_ore 必须显式提供可解析的石质破坏粒子纹理");

        String itemModelPath = ASSET_ROOT + "models/item/fake_ore.json";
        JsonObject itemModel = loadJson(itemModelPath);
        helper.assertTrue("miningdim:block/fake_ore".equals(requireString(itemModel, "parent", itemModelPath)),
                "fake_ore 物品模型必须继承同名方块模型");
    }

    private static String resolveTexture(String initialKey, JsonObject childTextures,
                                         JsonObject templateTextures, String modelPath) {
        Set<String> visited = new HashSet<>();
        String key = initialKey;
        while (visited.add(key)) {
            JsonElement element = childTextures.has(key) ? childTextures.get(key) : templateTextures.get(key);
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalStateException(modelPath + " 缺少可解析的纹理槽 #" + key);
            }
            String value = element.getAsString();
            if (!value.startsWith("#")) {
                return value;
            }
            if (value.length() == 1) {
                throw new IllegalStateException(modelPath + " 包含空纹理引用");
            }
            key = value.substring(1);
        }
        throw new IllegalStateException(modelPath + " 的纹理引用形成循环: " + visited);
    }

    private static JsonObject loadJson(String path) {
        InputStream input = PowerMineralAssetGameTests.class.getResourceAsStream(path);
        if (input == null) {
            throw new IllegalStateException("缺少资源 " + path);
        }
        try (input; InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new IllegalStateException(path + " 根节点必须是 JSON 对象");
            }
            return root.getAsJsonObject();
        } catch (IOException exception) {
            throw new IllegalStateException("读取资源失败 " + path, exception);
        }
    }

    private static JsonObject requireObject(JsonObject parent, String key, String path) {
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonObject()) {
            throw new IllegalStateException(path + " 缺少 JSON 对象字段 '" + key + "'");
        }
        return element.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonObject parent, String key, String path) {
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonArray()) {
            throw new IllegalStateException(path + " 缺少 JSON 数组字段 '" + key + "'");
        }
        return element.getAsJsonArray();
    }

    private static String requireString(JsonObject parent, String key, String path) {
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalStateException(path + " 缺少字符串字段 '" + key + "'");
        }
        return element.getAsString();
    }

    private static void assertPngResource(GameTestHelper helper, String path) {
        InputStream input = PowerMineralAssetGameTests.class.getResourceAsStream(path);
        if (input == null) {
            throw new IllegalStateException("缺少资源 " + path);
        }
        try (input) {
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new IllegalStateException(path + " 无法解码为 PNG 图像");
            }
            helper.assertTrue(image.getWidth() == 16 && image.getHeight() == 16,
                    path + " 必须保持 16x16，实得 " + image.getWidth() + "x" + image.getHeight());
            boolean hasTransparentPixel = false;
            boolean hasVisiblePixel = false;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int alpha = image.getRGB(x, y) >>> 24;
                    hasTransparentPixel |= alpha == 0;
                    hasVisiblePixel |= alpha > 0;
                }
            }
            helper.assertTrue(hasTransparentPixel && hasVisiblePixel,
                    path + " 必须同时包含透明基底与可见矿脉像素");
        } catch (IOException exception) {
            throw new IllegalStateException("读取资源失败 " + path, exception);
        }
    }
}
