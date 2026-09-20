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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PowerCableAssetGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "power_cable_assets";
    /**
     * 六向端口的期望旋转。判据是原版约定而不是本仓库的实现: 端口基准模型的臂占 -Z 半边(朝北), 与原版
     * observer/piston 的基准朝向一致, 因此绕 X 轴的取值必须与原版 blockstate 逐字对齐 —— facing=up 是
     * x=270, facing=down 是 x=90。
     *
     * 这张表原先照抄了写反的实现 (up=90/down=270), 测试与缺陷同源于是长期为绿, 真机上表现为线缆的上下
     * 连接臂画反 (碰撞箱走 EnergyCableBlock 的 arms 表, 是对的, 所以贴图与实体错位)。期望值必须独立于
     * 被测代码, 否则这类断言只是把 bug 抄了一遍。
     */
    private static final List<PortState> PORT_STATES = List.of(
            new PortState("north", 0, 0),
            new PortState("south", 0, 180),
            new PortState("east", 0, 90),
            new PortState("west", 0, 270),
            new PortState("up", 270, 0),
            new PortState("down", 90, 0));

    /**
     * 每档导线图标的期望导体色阶三停 (暗 / 中 / 亮)。真源是同材料线缆的导体色, 即
     * {@code tools/build_power_cable_block_textures.py} 里
     * {@code STYLES["<id>_energy_cable"].conductor_shadow / conductor / conductor_light}
     * ——"导线中间物"与"它合成出的线缆"必须是同一种金属, 所以这里逐字转抄那份表而不是从导线 PNG 反推。
     *
     * 立这张表的直接原因: 旧图标里镀锡铜、镀银铜、NbTi、YBCO 四档被画成了铜棕/橙红色系, 而当时的断言只看
     * Alpha, 错色一路静默通过。
     *
     * 为什么记三停而不是只记中停: 只比"均值色的色相"挡不住同色系互换 —— 十二档里有六档是灰/银/冷蓝系,
     * 银与 NbTi 的色相差 1.4 度、银与 YBCO 差 0.9 度、铁与铝差 0.4 度, 132 种两两互换里有 62 种色相差不到
     * 25 度, 把银的图标换成 YBCO 的照样全绿。三停是每档各不相同的指纹 (实测 12 档三停两两互异),
     * 逐档比对后 132 种互换一种都跑不掉。
     */
    private record ConductorRamp(int shadow, int base, int light) {
    }

    private static final Map<String, ConductorRamp> EXPECTED_WIRE_RAMPS = Map.ofEntries(
            Map.entry("iron", new ConductorRamp(0x44484C, 0x888D91, 0xC0C4C5)),
            Map.entry("aluminum", new ConductorRamp(0x6D747C, 0xB8C1C8, 0xEDF3F5)),
            Map.entry("copper", new ConductorRamp(0x7B2F1F, 0xC45E36, 0xF2A06A)),
            Map.entry("tinned_copper", new ConductorRamp(0x665E58, 0xB7A89F, 0xE4DBD4)),
            Map.entry("ofc_copper", new ConductorRamp(0x812B18, 0xD1602F, 0xFFAA64)),
            Map.entry("ofe_copper", new ConductorRamp(0x953B20, 0xE3793E, 0xFFC080)),
            Map.entry("silver_plated_copper", new ConductorRamp(0x747B82, 0xC6D0D7, 0xF7FCFF)),
            Map.entry("gold", new ConductorRamp(0x8E5C14, 0xD9A32D, 0xFFE17A)),
            Map.entry("silver", new ConductorRamp(0x737A80, 0xC8D1D6, 0xFFFFFF)),
            Map.entry("graphene", new ConductorRamp(0x16171B, 0x3F434A, 0x858D98)),
            Map.entry("nbti_superconductor", new ConductorRamp(0x376788, 0x82B6D0, 0xE4FBFF)),
            Map.entry("ybco_superconductor", new ConductorRamp(0x14232C, 0x315164, 0x79C8D7)));

    /** 导线图标可见像素数的容许区间。线卷轮廓固定 58 像素, 留一点余量以便微调形状而不必改测试。 */
    private static final int MIN_WIRE_VISIBLE_PIXELS = 48;
    private static final int MAX_WIRE_VISIBLE_PIXELS = 72;

    /** 线卷的五级明暗色阶数。三停里的暗/中/亮分别落在第 0/2/4 级, 另两级是插值出来的中间色。 */
    private static final int WIRE_SHADE_LEVELS = 5;

    private PowerCableAssetGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyRegisteredCableUsesNonOverlappingModelsAndValidTextures(GameTestHelper helper) {
        Map<String, ConductorMaterial> wireSignatures = new HashMap<>();
        for (ConductorMaterial material : ConductorMaterial.values()) {
            helper.assertTrue(PowerRegistry.CABLES.containsKey(material),
                    "缺少已声明导体的线缆注册: " + material.blockId());
            verifyCableAssets(helper, material.blockId());
            String signature = verifyWireAssets(helper, material);
            ConductorMaterial clash = wireSignatures.putIfAbsent(signature, material);
            helper.assertTrue(clash == null, material.id() + "_wire 与 " + (clash == null ? "" : clash.id())
                    + "_wire 的可见像素完全相同; 12 档导线必须各自着色, 不能复用同一张图");
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

    /**
     * 校验单档导线中间物的图标资产, 返回可见像素签名供调用方做跨材料互异性断言。
     */
    private static String verifyWireAssets(GameTestHelper helper, ConductorMaterial material) {
        String wireId = material.id() + "_wire";
        JsonObject item = loadJson("/assets/miningdim/models/item/" + wireId + ".json");
        helper.assertTrue("minecraft:item/generated".equals(item.get("parent").getAsString())
                        && ("miningdim:item/" + wireId).equals(
                        item.getAsJsonObject("textures").get("layer0").getAsString()),
                wireId + " 必须绑定同名独立彩色导线图标");

        BufferedImage image = loadImage("/assets/miningdim/textures/item/" + wireId + ".png");
        helper.assertTrue(image.getWidth() == 16 && image.getHeight() == 16,
                wireId + " 导线贴图必须保持 16x16 像素");

        StringBuilder signature = new StringBuilder();
        int transparentPixels = 0;
        int visiblePixels = 0;
        // 用 HashSet 按 RGB 去重, 排序留到后面: 若拿亮度当 TreeSet 的比较键, 两个亮度相同的不同颜色会被
        // 当成同一级悄悄吞掉一个, 色阶数断言就会给出误导性的读数。
        Set<Integer> shades = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                helper.assertTrue(alpha == 0 || alpha == 255,
                        wireId + " 必须使用硬边 Alpha，半透明像素 x=" + x + ", y=" + y + ", alpha=" + alpha);
                if (alpha == 0) {
                    helper.assertTrue((argb & 0x00FFFFFF) == 0,
                            wireId + " 透明区必须清零 RGB，隐藏底色像素 x=" + x + ", y=" + y
                                    + ", rgb=" + Integer.toHexString(argb & 0x00FFFFFF));
                    transparentPixels++;
                    continue;
                }
                visiblePixels++;
                shades.add(argb & 0x00FFFFFF);
                signature.append(x).append(':').append(y).append(':')
                        .append(Integer.toHexString(argb & 0x00FFFFFF)).append(';');
            }
        }

        helper.assertTrue(transparentPixels > 0,
                wireId + " 导线图标必须留出透明区，当前整张不透明");
        helper.assertTrue(visiblePixels >= MIN_WIRE_VISIBLE_PIXELS && visiblePixels <= MAX_WIRE_VISIBLE_PIXELS,
                wireId + " 可见像素数必须落在 " + MIN_WIRE_VISIBLE_PIXELS + "-" + MAX_WIRE_VISIBLE_PIXELS
                        + "，实得 " + visiblePixels + "，线卷轮廓已退化");

        verifyWireRamp(helper, wireId, material, shades);
        return signature.toString();
    }

    /**
     * 按亮度排序后逐级比对导线图标的五级色阶。
     *
     * 第 0/2/4 级必须逐字等于该材料线缆的暗/中/亮三停 —— 生成器对这三级取的是插值端点与中点, 结果就是三停
     * 本身, 不存在取整误差, 所以这里敢用相等而不是容差。第 1/3 级是插值出来的, 只要求亮度严格夹在相邻两级
     * 之间 (取整规则变了也不会误报, 但色阶塌成两级或顺序错乱会被抓住)。
     */
    private static void verifyWireRamp(GameTestHelper helper, String wireId, ConductorMaterial material,
                                       Set<Integer> shades) {
        helper.assertTrue(shades.size() == WIRE_SHADE_LEVELS,
                wireId + " 线卷必须正好用 " + WIRE_SHADE_LEVELS + " 级明暗色阶，实得 " + shades.size() + " 级");

        List<Integer> ramp = shades.stream()
                .sorted(Comparator.comparingDouble(PowerCableAssetGameTests::luminance))
                .toList();
        for (int level = 1; level < ramp.size(); level++) {
            helper.assertTrue(luminance(ramp.get(level)) > luminance(ramp.get(level - 1)),
                    wireId + " 的色阶第 " + level + " 级不比第 " + (level - 1) + " 级更亮，明暗关系已塌陷");
        }

        ConductorRamp expected = EXPECTED_WIRE_RAMPS.get(material.id());
        assertShade(helper, wireId, "最暗级", ramp.get(0), expected.shadow());
        assertShade(helper, wireId, "中停", ramp.get(2), expected.base());
        assertShade(helper, wireId, "最亮级", ramp.get(4), expected.light());
    }

    private static void assertShade(GameTestHelper helper, String wireId, String label, int actual, int expected) {
        helper.assertTrue(actual == expected,
                wireId + " 的" + label + "是 #" + String.format("%06X", actual) + "，同材料线缆的导体色是 #"
                        + String.format("%06X", expected) + "；这一档画成了别的金属");
    }

    /** Rec.709 相对亮度, 只用来给五级色阶定序。 */
    private static double luminance(int rgb) {
        return 0.2126D * ((rgb >> 16) & 0xFF) + 0.7152D * ((rgb >> 8) & 0xFF) + 0.0722D * (rgb & 0xFF);
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
