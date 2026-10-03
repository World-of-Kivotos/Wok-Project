package com.miningdim.job.engineer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.engineer.armor.PlateArmorVariant;
import com.miningdim.job.engineer.armor.client.PlateArmorMesh;
import com.miningdim.job.engineer.armor.item.PlateArmorItem;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 插板护甲烘焙网格与穿戴贴图的资产契约测试。
 *
 * <p>缘由：穿戴模型只在客户端渲染，runGameTestServer 是纯服务端进程，从不读 armor_meshes，也从不加载护甲贴图；
 * 网格缺一件、数字写坏或贴图尺寸对不上，客户端只会安静地退回原版胸甲或整件贴图错位，服务端质量门一个字节都看不到。
 * 这里直接读打进 JAR 的资源，逐件核对 tools/plate_armor/export.mjs 与 PlateArmorBakedModel 之间的数据契约。</p>
 *
 * <p>位置按部位锚定，专门拦导出端最容易犯的三类错：把手臂挂点 (∓5, 2, 0) 重复烘焙进顶点、right_arm 与 left_arm
 * 写反、单位错（顶点被预先除以 16）。躯干包围盒必须盖住躯干左右两侧和上下大半（minX ≤ -4、maxX ≥ 4、minY ≤ 1、
 * maxY ≥ 10）；右臂在手臂局部坐标里贴着 x -3 至 1 的手臂（minX ≤ -3，-1 ≤ maxX ≤ 2），左臂与之镜像（maxX ≥ 3，
 * -2 ≤ minX ≤ 1）。三个部位另外共用一个粗外框（x ±12、y -8 至 20、z ±8），拦乘了 16 或离谱的数。
 * 阈值按现有 54 件网格核过，每件都留有余量。</p>
 *
 * <p>每个四边形还要自洽：由顶点绕序算出的几何法线 (v1-v0)×(v2-v1) 与写入的法线同向（原版 ModelPart.Polygon
 * 同样满足），uv 是按 (u2,v1)(u1,v1)(u1,v2)(u2,v2) 排的正向矩形。贴图一侧走游戏实际调用的
 * PlateArmorItem.getArmorTexture 取路径，核对尺寸，并要求每个四边形的贴图矩形里至少有一个不透明像素
 * （导出端会丢掉整块透明的面），只重导了网格或只重导了贴图都会在这里露出来。</p>
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PlateArmorMeshGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "plate_armor_meshes";

    private static final List<String> PARTS = List.of(
            PlateArmorMesh.PART_BODY, PlateArmorMesh.PART_RIGHT_ARM, PlateArmorMesh.PART_LEFT_ARM);
    /** 所有部位共用的粗外框，只拦放大了 16 倍或完全离谱的坐标；挂点与左右靠下面的部位锚点拦。 */
    private static final double ENVELOPE_X = 12.0D;
    private static final double ENVELOPE_MIN_Y = -8.0D;
    private static final double ENVELOPE_MAX_Y = 20.0D;
    private static final double ENVELOPE_Z = 8.0D;
    private static final double UV_TOLERANCE = 1.0E-6D;
    private static final double NORMAL_TOLERANCE = 1.0E-3D;
    /** 几何法线与写入法线的最小点积；位置取整到 1e-4 后，现有网格最差约 0.99994。 */
    private static final double MIN_NORMAL_AGREEMENT = 0.998D;
    /** 由 uv 反推贴图像素矩形时吸收取整误差（单位：贴图像素）。 */
    private static final double TEXEL_EPSILON = 1.0E-3D;

    private static final int MIN_X = 0;
    private static final int MIN_Y = 1;
    private static final int MAX_X = 3;
    private static final int MAX_Y = 4;

    private PlateArmorMeshGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyVariantShipsAWellFormedBakedMesh(GameTestHelper helper) {
        for (PlateArmorVariant variant : PlateArmorVariant.values()) {
            String path = meshPath(variant);
            JsonObject root = loadJson(path);
            String label = variant.itemId();

            helper.assertTrue(root.has("format") && root.get("format").isJsonPrimitive()
                            && root.get("format").getAsJsonPrimitive().isNumber()
                            && root.get("format").getAsDouble() == PlateArmorMesh.FORMAT,
                    label + " 的网格 format 必须是 " + PlateArmorMesh.FORMAT);
            helper.assertTrue(root.has("item") && label.equals(root.get("item").getAsString()),
                    label + " 的网格 item 字段必须等于物品注册名, 实得 " + root.get("item"));
            int[] textureSize = textureSize(helper, label, root);
            helper.assertTrue(textureSize[0] > 0 && textureSize[1] > 0,
                    label + " 的 textureSize 必须为正, 实得 " + Arrays.toString(textureSize));

            JsonObject parts = root.getAsJsonObject("parts");
            helper.assertTrue(parts != null, label + " 的网格缺少 parts");
            for (String key : parts.keySet()) {
                helper.assertTrue(PARTS.contains(key),
                        label + " 的网格出现未知部位 " + key + "; 渲染器只画 " + PARTS + ", 多出来的部件会静默消失");
            }
            for (String part : PARTS) {
                JsonElement element = parts.get(part);
                helper.assertTrue(element != null && element.isJsonArray(),
                        label + " 的 " + part + " 必须是数组 (手臂可以为空)");
                JsonArray quads = element.getAsJsonArray();
                if (PlateArmorMesh.PART_BODY.equals(part)) {
                    helper.assertTrue(quads.size() > 0, label + " 的 body 不能为空");
                }
                for (int quad = 0; quad < quads.size(); quad++) {
                    assertQuad(helper, label + " " + part + " #" + quad, quads.get(quad));
                }
                if (quads.size() > 0) {
                    assertPartAnchor(helper, label, part, bounds(quads));
                }
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyMeshMatchesTheArmorTextureTheGameUses(GameTestHelper helper) {
        String expectedPrefix = MiningConstants.MODID + ":textures/models/armor/";
        for (PlateArmorVariant variant : PlateArmorVariant.values()) {
            String label = variant.itemId();
            JsonObject root = loadJson(meshPath(variant));
            int[] textureSize = textureSize(helper, label, root);

            // 贴图路径取自游戏渲染时真正调用的 getArmorTexture, 不在测试里另拼一份: 那边漏掉或改坏一个外观,
            // 烘焙网格的 uv 就会落到错误或缺失的贴图上。
            Item item = ModEngineerItems.plateArmor(variant).get();
            helper.assertTrue(item instanceof PlateArmorItem, label + " 必须注册为 PlateArmorItem");
            String texture = ((PlateArmorItem) item).getArmorTexture(
                    new ItemStack(item), null, EquipmentSlot.CHEST, null);
            String expected = expectedPrefix + label + "_layer_1.png";
            helper.assertTrue(expected.equals(texture),
                    label + " 胸甲槽的穿戴贴图应为 " + expected + ", 实得 " + texture);
            ResourceLocation location = new ResourceLocation(texture);
            String texturePath = "/assets/" + location.getNamespace() + "/" + location.getPath();

            // 尺寸是网格 uv 归一化时的分母, 对不上就整件错位。
            BufferedImage image = loadImage(texturePath);
            helper.assertTrue(image.getWidth() == textureSize[0] && image.getHeight() == textureSize[1],
                    label + " 的网格声明贴图 " + textureSize[0] + "x" + textureSize[1] + ", 实际 PNG 是 "
                            + image.getWidth() + "x" + image.getHeight());

            // 导出端丢掉了整块透明的面, 留下的每个面在同一次导出的贴图里都至少有一个不透明像素。
            JsonObject parts = root.getAsJsonObject("parts");
            for (String part : PARTS) {
                JsonArray quads = parts.getAsJsonArray(part);
                for (int quad = 0; quad < quads.size(); quad++) {
                    helper.assertTrue(hasOpaqueTexel(image, quads.get(quad).getAsJsonArray()),
                            label + " " + part + " #" + quad + " 的贴图矩形里一个不透明像素都没有;"
                                    + " 网格和贴图不是同一次导出的结果, 重新跑 export.mjs");
                }
            }
        }
        helper.succeed();
    }

    /** 客户端真正使用的解析器必须接受每一件打包的网格, 否则那件护甲在客户端会退回原版模型。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void clientMeshParserAcceptsEveryShippedMesh(GameTestHelper helper) {
        for (PlateArmorVariant variant : PlateArmorVariant.values()) {
            String path = meshPath(variant);
            JsonObject root = loadJson(path);
            int expectedQuads = 0;
            JsonObject parts = root.getAsJsonObject("parts");
            for (String part : PARTS) {
                expectedQuads += parts.getAsJsonArray(part).size();
            }
            PlateArmorMesh mesh;
            try (InputStream input = PlateArmorMeshGameTests.class.getResourceAsStream(path);
                 Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                mesh = PlateArmorMesh.parse(reader, variant.itemId());
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException(variant.itemId() + " 的网格被客户端解析器拒绝: "
                        + exception.getMessage(), exception);
            }
            helper.assertTrue(mesh.quadCount() == expectedQuads,
                    variant.itemId() + " 解析后的四边形数 " + mesh.quadCount() + " 与文件里的 " + expectedQuads + " 不符");
        }
        helper.succeed();
    }

    private static void assertQuad(GameTestHelper helper, String label, JsonElement element) {
        helper.assertTrue(element.isJsonArray() && element.getAsJsonArray().size() == PlateArmorMesh.QUAD_FLOATS,
                label + " 必须恰好是 " + PlateArmorMesh.QUAD_FLOATS + " 个数");
        JsonArray values = element.getAsJsonArray();
        double[] quad = new double[PlateArmorMesh.QUAD_FLOATS];
        for (int index = 0; index < quad.length; index++) {
            JsonElement value = values.get(index);
            helper.assertTrue(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                            && Double.isFinite(value.getAsDouble()),
                    label + " 第 " + index + " 项不是有限数: " + value);
            quad[index] = value.getAsDouble();
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * PlateArmorMesh.VERTEX_FLOATS;
            double x = quad[base];
            double y = quad[base + 1];
            double z = quad[base + 2];
            helper.assertTrue(Math.abs(x) <= ENVELOPE_X && y >= ENVELOPE_MIN_Y && y <= ENVELOPE_MAX_Y
                            && Math.abs(z) <= ENVELOPE_Z,
                    label + " 顶点 " + vertex + " 坐标 (" + x + ", " + y + ", " + z + ") 越出部位粗外框");
            double u = quad[base + 3];
            double v = quad[base + 4];
            helper.assertTrue(u >= -UV_TOLERANCE && u <= 1.0D + UV_TOLERANCE
                            && v >= -UV_TOLERANCE && v <= 1.0D + UV_TOLERANCE,
                    label + " 顶点 " + vertex + " 的 uv (" + u + ", " + v + ") 越出 0 至 1");
        }

        // uv 必须是按 (u2,v1)(u1,v1)(u1,v2)(u2,v2) 排的正向矩形, 顺序一乱贴图就转向或镜像。
        double u0 = quad[3];
        double v0 = quad[4];
        double u1 = quad[PlateArmorMesh.VERTEX_FLOATS + 3];
        double v1 = quad[PlateArmorMesh.VERTEX_FLOATS + 4];
        double u2 = quad[2 * PlateArmorMesh.VERTEX_FLOATS + 3];
        double v2 = quad[2 * PlateArmorMesh.VERTEX_FLOATS + 4];
        double u3 = quad[3 * PlateArmorMesh.VERTEX_FLOATS + 3];
        double v3 = quad[3 * PlateArmorMesh.VERTEX_FLOATS + 4];
        helper.assertTrue(v0 == v1 && u1 == u2 && v2 == v3 && u3 == u0 && u0 > u1 && v2 > v1,
                label + " 的 uv 不是 (u2,v1)(u1,v1)(u1,v2)(u2,v2) 顺序的正向矩形: (" + u0 + "," + v0 + ") ("
                        + u1 + "," + v1 + ") (" + u2 + "," + v2 + ") (" + u3 + "," + v3 + ")");

        int n = PlateArmorMesh.NORMAL_OFFSET;
        double nx = quad[n];
        double ny = quad[n + 1];
        double nz = quad[n + 2];
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        helper.assertTrue(Math.abs(length - 1.0D) <= NORMAL_TOLERANCE,
                label + " 的法线长度 " + length + " 不是单位长度");

        // 几何法线 (v1-v0)×(v2-v1): 同时要求法线垂直于面、并且与顶点绕序同向 (原版 Polygon 的约定)。
        int second = PlateArmorMesh.VERTEX_FLOATS;
        int third = 2 * PlateArmorMesh.VERTEX_FLOATS;
        double ax = quad[second] - quad[0];
        double ay = quad[second + 1] - quad[1];
        double az = quad[second + 2] - quad[2];
        double bx = quad[third] - quad[second];
        double by = quad[third + 1] - quad[second + 1];
        double bz = quad[third + 2] - quad[second + 2];
        double cx = ay * bz - az * by;
        double cy = az * bx - ax * bz;
        double cz = ax * by - ay * bx;
        double area = Math.sqrt(cx * cx + cy * cy + cz * cz);
        helper.assertTrue(area > 1.0E-9D, label + " 是零面积的面");
        double agreement = (cx * nx + cy * ny + cz * nz) / (area * length);
        helper.assertTrue(agreement >= MIN_NORMAL_AGREEMENT,
                label + " 的法线与顶点绕序算出的几何法线不一致 (点积 " + agreement + ")");
    }

    /** 部位锚点: 拦挂点被重复烘焙、左右臂写反、单位错; 阈值见类注释。 */
    private static void assertPartAnchor(GameTestHelper helper, String label, String part, double[] box) {
        String where = label + " 的 " + part + " 包围盒 x " + box[MIN_X] + " 至 " + box[MAX_X]
                + ", y " + box[MIN_Y] + " 至 " + box[MAX_Y];
        switch (part) {
            case PlateArmorMesh.PART_BODY -> helper.assertTrue(
                    box[MIN_X] <= -4.0D && box[MAX_X] >= 4.0D && box[MIN_Y] <= 1.0D && box[MAX_Y] >= 10.0D,
                    where + " 没盖住躯干 (要求 minX ≤ -4、maxX ≥ 4、minY ≤ 1、maxY ≥ 10); 单位错了?");
            case PlateArmorMesh.PART_RIGHT_ARM -> helper.assertTrue(
                    box[MIN_X] <= -3.0D && box[MAX_X] >= -1.0D && box[MAX_X] <= 2.0D,
                    where + " 不贴着手臂局部坐标 x -3 至 1 (要求 minX ≤ -3、-1 ≤ maxX ≤ 2);"
                            + " 挂点 (-5,2,0) 被烘焙进顶点, 或与 left_arm 写反了?");
            case PlateArmorMesh.PART_LEFT_ARM -> helper.assertTrue(
                    box[MAX_X] >= 3.0D && box[MIN_X] >= -2.0D && box[MIN_X] <= 1.0D,
                    where + " 不贴着手臂局部坐标 x -1 至 3 (要求 maxX ≥ 3、-2 ≤ minX ≤ 1);"
                            + " 挂点 (5,2,0) 被烘焙进顶点, 或与 right_arm 写反了?");
            default -> throw new IllegalStateException("未知部位 " + part);
        }
    }

    /** 一个部位全部顶点的包围盒: [minX, minY, minZ, maxX, maxY, maxZ]。 */
    private static double[] bounds(JsonArray quads) {
        double[] box = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (JsonElement element : quads) {
            JsonArray values = element.getAsJsonArray();
            for (int vertex = 0; vertex < 4; vertex++) {
                for (int axis = 0; axis < 3; axis++) {
                    double value = values.get(vertex * PlateArmorMesh.VERTEX_FLOATS + axis).getAsDouble();
                    box[axis] = Math.min(box[axis], value);
                    box[axis + 3] = Math.max(box[axis + 3], value);
                }
            }
        }
        return box;
    }

    /** 四边形 uv 矩形覆盖的贴图像素里是否至少有一个不透明 (alpha 非 0)。 */
    private static boolean hasOpaqueTexel(BufferedImage image, JsonArray quad) {
        int width = image.getWidth();
        int height = image.getHeight();
        double uLow = quad.get(PlateArmorMesh.VERTEX_FLOATS + 3).getAsDouble() * width;
        double vLow = quad.get(PlateArmorMesh.VERTEX_FLOATS + 4).getAsDouble() * height;
        double uHigh = quad.get(3).getAsDouble() * width;
        double vHigh = quad.get(2 * PlateArmorMesh.VERTEX_FLOATS + 4).getAsDouble() * height;
        int i0 = Math.max(0, (int) Math.floor(uLow + TEXEL_EPSILON));
        int i1 = Math.min(width, (int) Math.ceil(uHigh - TEXEL_EPSILON));
        int j0 = Math.max(0, (int) Math.floor(vLow + TEXEL_EPSILON));
        int j1 = Math.min(height, (int) Math.ceil(vHigh - TEXEL_EPSILON));
        for (int j = j0; j < j1; j++) {
            for (int i = i0; i < i1; i++) {
                if ((image.getRGB(i, j) >>> 24) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int[] textureSize(GameTestHelper helper, String label, JsonObject root) {
        JsonElement element = root.get("textureSize");
        helper.assertTrue(element != null && element.isJsonArray() && element.getAsJsonArray().size() == 2,
                label + " 的网格 textureSize 必须是 [宽, 高]");
        JsonArray size = element.getAsJsonArray();
        double width = size.get(0).getAsDouble();
        double height = size.get(1).getAsDouble();
        helper.assertTrue(width == Math.rint(width) && height == Math.rint(height),
                label + " 的 textureSize 必须是整数, 实得 " + size);
        return new int[]{(int) width, (int) height};
    }

    private static String meshPath(PlateArmorVariant variant) {
        return "/assets/miningdim/armor_meshes/" + variant.itemId() + ".json";
    }

    private static JsonObject loadJson(String path) {
        try (InputStream input = PlateArmorMeshGameTests.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("找不到插板护甲网格: " + path);
            }
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException exception) {
            throw new IllegalStateException("读取插板护甲网格失败: " + path, exception);
        }
    }

    private static BufferedImage loadImage(String path) {
        try (InputStream input = PlateArmorMeshGameTests.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("缺少插板护甲穿戴贴图: " + path);
            }
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new IllegalStateException("无法解码插板护甲穿戴贴图: " + path);
            }
            return image;
        } catch (IOException exception) {
            throw new IllegalStateException("读取插板护甲穿戴贴图失败: " + path, exception);
        }
    }
}
