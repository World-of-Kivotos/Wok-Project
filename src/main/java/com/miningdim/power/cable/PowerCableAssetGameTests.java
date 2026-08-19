package com.miningdim.power.cable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.power.PowerRegistry;
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
import java.util.List;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PowerCableAssetGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "power_cable_assets";
    private static final List<PortState> PORT_STATES = List.of(
            new PortState("north", 0, 0),
            new PortState("south", 0, 180),
            new PortState("east", 0, 90),
            new PortState("west", 0, 270),
            new PortState("up", 90, 0),
            new PortState("down", 270, 0));

    private PowerCableAssetGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyRegisteredCableUsesNonOverlappingModelsAndValidTextures(GameTestHelper helper) {
        for (ConductorMaterial material : ConductorMaterial.values()) {
            helper.assertTrue(PowerRegistry.CABLES.containsKey(material),
                    "缺少已声明导体的线缆注册: " + material.blockId());
            verifyCableAssets(helper, material.blockId());
        }

        String tungsten = SpecialCableMaterial.TUNGSTEN.blockId();
        helper.assertTrue(PowerRegistry.TUNGSTEN_HEAT_RESISTANT_CABLE.getId().getPath().equals(tungsten),
                "钨耐热线注册 ID 必须与特殊线缆档案一致");
        verifyCableAssets(helper, tungsten);
        helper.succeed();
    }

    private static void verifyCableAssets(GameTestHelper helper, String cableId) {
        String blockTexture = "miningdim:block/" + cableId;
        JsonObject blockState = loadJson("/assets/miningdim/blockstates/" + cableId + ".json");
        JsonArray multipart = blockState.getAsJsonArray("multipart");
        helper.assertTrue(multipart != null && multipart.size() == 7,
                cableId + " 必须包含一个中心模型和六个方向连接模型");
        helper.assertTrue(modelOf(multipart.get(0).getAsJsonObject()).equals(
                        "miningdim:block/" + cableId + "_core"),
                cableId + " multipart 首项必须是中心模型");

        for (int index = 0; index < PORT_STATES.size(); index++) {
            PortState expected = PORT_STATES.get(index);
            JsonObject part = multipart.get(index + 1).getAsJsonObject();
            JsonObject when = part.getAsJsonObject("when");
            JsonObject apply = part.getAsJsonObject("apply");
            helper.assertTrue(when.size() == 1 && "true".equals(when.get(expected.property()).getAsString()),
                    cableId + " 缺少 " + expected.property() + " 方向连接条件");
            helper.assertTrue(modelOf(part).equals("miningdim:block/" + cableId + "_port")
                            && intOrZero(apply, "x") == expected.rotationX()
                            && intOrZero(apply, "y") == expected.rotationY(),
                    cableId + " 的 " + expected.property() + " 方向模型旋转错误");
        }

        JsonObject core = loadJson("/assets/miningdim/models/block/" + cableId + "_core.json");
        JsonObject port = loadJson("/assets/miningdim/models/block/" + cableId + "_port.json");
        verifyTextureBindings(helper, cableId, core, blockTexture);
        verifyTextureBindings(helper, cableId, port, blockTexture);
        verifyCoreGeometry(helper, cableId, core);
        verifyPortGeometry(helper, cableId, port);

        BufferedImage blockImage = loadImage("/assets/miningdim/textures/block/" + cableId + ".png");
        helper.assertTrue(blockImage.getWidth() == 32 && blockImage.getHeight() == 32,
                cableId + " 摆放态方块贴图必须保持 32x32 像素");
        int blockTextureScale = blockImage.getWidth() / 16;
        for (int x = 0; x < 10 * blockTextureScale; x++) {
            for (int y = 6 * blockTextureScale; y < 10 * blockTextureScale; y++) {
                int alpha = (blockImage.getRGB(x, y) >>> 24) & 0xFF;
                helper.assertTrue(alpha == 255,
                        cableId + " 模型使用的 2x 像素密度 UV 实体带必须完全不透明，失败像素 x=" + x
                                + ", y=" + y);
            }
        }

        JsonObject item = loadJson("/assets/miningdim/models/item/" + cableId + ".json");
        helper.assertTrue("minecraft:item/generated".equals(item.get("parent").getAsString())
                        && ("miningdim:item/" + cableId).equals(
                        item.getAsJsonObject("textures").get("layer0").getAsString()),
                cableId + " 物品模型必须绑定同名扁平图标");
        BufferedImage itemImage = loadImage("/assets/miningdim/textures/item/" + cableId + ".png");
        helper.assertTrue(itemImage.getWidth() == 16 && itemImage.getHeight() == 16,
                cableId + " 物品贴图必须保持 16x16 像素");
    }

    private static void verifyTextureBindings(GameTestHelper helper, String cableId,
                                              JsonObject model, String expectedTexture) {
        JsonObject textures = model.getAsJsonObject("textures");
        helper.assertTrue("minecraft:cutout".equals(model.get("render_type").getAsString())
                        && expectedTexture.equals(textures.get("cable").getAsString())
                        && expectedTexture.equals(textures.get("particle").getAsString()),
                cableId + " 的方块面与破坏粒子必须绑定同一条有效贴图");
    }

    private static void verifyCoreGeometry(GameTestHelper helper, String cableId, JsonObject model) {
        JsonArray elements = model.getAsJsonArray("elements");
        helper.assertTrue(elements.size() == 1, cableId + " 中心模型必须只有一个元素");
        JsonObject element = elements.get(0).getAsJsonObject();
        assertVector(helper, cableId + " 中心起点", element.getAsJsonArray("from"), 6, 6, 6);
        assertVector(helper, cableId + " 中心终点", element.getAsJsonArray("to"), 10, 10, 10);
        JsonObject faces = element.getAsJsonObject("faces");
        helper.assertTrue(faces.size() == 6, cableId + " 中心模型必须封闭六个面");
        for (String face : List.of("down", "up", "north", "south", "west", "east")) {
            verifyFace(helper, cableId + " 中心 " + face, element, face, 6, 6, 10, 10);
        }
    }

    private static void verifyPortGeometry(GameTestHelper helper, String cableId, JsonObject model) {
        JsonArray elements = model.getAsJsonArray("elements");
        helper.assertTrue(elements.size() == 1, cableId + " 连接模型必须只有一个元素");
        JsonObject element = elements.get(0).getAsJsonObject();
        assertVector(helper, cableId + " 连接起点", element.getAsJsonArray("from"), 6, 6, 0);
        assertVector(helper, cableId + " 连接终点", element.getAsJsonArray("to"), 10, 10, 6);
        JsonObject faces = element.getAsJsonObject("faces");
        helper.assertTrue(faces.size() == 5 && !faces.has("south"),
                cableId + " 连接段不得伸入中心或保留共面内端面");
        verifyFace(helper, cableId + " 连接 north", element, "north", 0, 6, 4, 10);
        for (String face : List.of("down", "up", "west", "east")) {
            verifyFace(helper, cableId + " 连接 " + face, element, face, 0, 6, 6, 10);
        }
        helper.assertTrue(faces.getAsJsonObject("up").get("rotation").getAsInt() == 90
                        && faces.getAsJsonObject("down").get("rotation").getAsInt() == 270,
                cableId + " 连接段顶面与底面 UV 必须沿线缆轴旋转");
    }

    private static void verifyFace(GameTestHelper helper, String label, JsonObject element,
                                   String faceName, int... expectedUv) {
        JsonObject face = element.getAsJsonObject("faces").getAsJsonObject(faceName);
        helper.assertTrue(face != null && "#cable".equals(face.get("texture").getAsString()),
                label + " 必须显式绑定线缆贴图");
        JsonArray uv = face.getAsJsonArray("uv");
        assertVector(helper, label + " UV", uv == null ? implicitUv(element, faceName) : uv, expectedUv);
    }

    private static JsonArray implicitUv(JsonObject element, String faceName) {
        JsonArray from = element.getAsJsonArray("from");
        JsonArray to = element.getAsJsonArray("to");
        int fromX = from.get(0).getAsInt();
        int fromY = from.get(1).getAsInt();
        int fromZ = from.get(2).getAsInt();
        int toX = to.get(0).getAsInt();
        int toY = to.get(1).getAsInt();
        int toZ = to.get(2).getAsInt();
        int[] values = switch (faceName) {
            case "down" -> new int[]{fromX, 16 - toZ, toX, 16 - fromZ};
            case "up" -> new int[]{fromX, fromZ, toX, toZ};
            case "north" -> new int[]{16 - toX, 16 - toY, 16 - fromX, 16 - fromY};
            case "south" -> new int[]{fromX, 16 - toY, toX, 16 - fromY};
            case "west" -> new int[]{fromZ, 16 - toY, toZ, 16 - fromY};
            case "east" -> new int[]{16 - toZ, 16 - toY, 16 - fromZ, 16 - fromY};
            default -> throw new IllegalArgumentException("未知方块面: " + faceName);
        };
        JsonArray uv = new JsonArray();
        for (int value : values) {
            uv.add(value);
        }
        return uv;
    }

    private static void assertVector(GameTestHelper helper, String label, JsonArray actual, int... expected) {
        boolean matches = actual != null && actual.size() == expected.length;
        for (int index = 0; matches && index < expected.length; index++) {
            matches = actual.get(index).getAsInt() == expected[index];
        }
        helper.assertTrue(matches, label + " 数值错误，实得 " + actual);
    }

    private static String modelOf(JsonObject multipartEntry) {
        return multipartEntry.getAsJsonObject("apply").get("model").getAsString();
    }

    private static int intOrZero(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsInt() : 0;
    }

    private static JsonObject loadJson(String path) {
        try (InputStream input = PowerCableAssetGameTests.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("找不到线缆 JSON 资源: " + path);
            }
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException exception) {
            throw new IllegalStateException("读取线缆 JSON 资源失败: " + path, exception);
        }
    }

    private static BufferedImage loadImage(String path) {
        try (InputStream input = PowerCableAssetGameTests.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("找不到线缆图片资源: " + path);
            }
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new IllegalStateException("无法解码线缆图片资源: " + path);
            }
            return image;
        } catch (IOException exception) {
            throw new IllegalStateException("读取线缆图片资源失败: " + path, exception);
        }
    }

    private record PortState(String property, int rotationX, int rotationY) {
    }
}
