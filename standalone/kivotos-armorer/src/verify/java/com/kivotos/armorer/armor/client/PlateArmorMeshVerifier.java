package com.kivotos.armorer.armor.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.kivotos.armorer.ArmorerMod;
import com.kivotos.armorer.armor.PlateArmorVariant;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 插板护甲烘焙网格与穿戴贴图的资产契约校验，由构建任务 verifyPlateArmorMeshes 在普通 JVM 里执行，挂在 check 上。
 *
 * <p>缘由：穿戴模型只在客户端渲染，本子工程没有 GameTest，服务端启动也从不读 armor_meshes、不加载护甲贴图；
 * 网格缺一件、数字写坏或贴图尺寸对不上，客户端只会安静地退回原版胸甲或整件贴图错位。这里在构建期直接读打进 JAR
 * 的资源，逐件核对本体导出器与 PlateArmorBakedModel 之间的数据契约。校验项与阈值原样移植自 WOK 本体 3d0c4b72 的
 * PlateArmorMeshGameTests（网格是从那次重做逐字节同步来的，阈值在那边按现有 54 件核过，每件都留有余量）。</p>
 *
 * <p>位置按部位锚定，专门拦导出端最容易犯的三类错：把手臂挂点 (∓5, 2, 0) 重复烘焙进顶点、right_arm 与 left_arm
 * 写反、单位错（顶点被预先除以 16）。躯干包围盒必须盖住躯干左右两侧和上下大半（minX ≤ -4、maxX ≥ 4、minY ≤ 1、
 * maxY ≥ 10）；右臂在手臂局部坐标里贴着 x -3 至 1 的手臂（minX ≤ -3，-1 ≤ maxX ≤ 2），左臂与之镜像（maxX ≥ 3，
 * -2 ≤ minX ≤ 1）。三个部位另外共用一个粗外框（x ±12、y -8 至 20、z ±8），拦乘了 16 或离谱的数。</p>
 *
 * <p>每个四边形还要自洽：由顶点绕序算出的几何法线 (v1-v0)×(v2-v1) 与写入的法线同向（原版 ModelPart.Polygon
 * 同样满足），uv 是按 (u2,v1)(u1,v1)(u1,v2)(u2,v2) 排的正向矩形。贴图要求尺寸与网格声明一致，且每个四边形的贴图
 * 矩形里至少有一个不透明像素（导出端会丢掉整块透明的面），只同步了网格或只同步了贴图都会在这里露出来。</p>
 *
 * <p>每件外观报第一处违约，全部跑完后统一列出并以非零码退出，让 gradlew build 失败。</p>
 */
public final class PlateArmorMeshVerifier {

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

    private PlateArmorMeshVerifier() {
    }

    public static void main(String[] args) {
        List<String> failures = new ArrayList<>();
        for (PlateArmorVariant variant : PlateArmorVariant.values()) {
            try {
                verify(variant);
            } catch (MeshContractViolation violation) {
                failures.add(variant.itemId() + ": " + violation.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            System.err.println("插板护甲网格校验失败 " + failures.size() + " 件：");
            failures.forEach(failure -> System.err.println("  " + failure));
            System.exit(1);
        }
        System.out.println("插板护甲网格校验通过：" + PlateArmorVariant.values().length + " 件");
    }

    private static void verify(PlateArmorVariant variant) {
        String label = variant.itemId();
        String meshPath = "/assets/" + ArmorerMod.MODID + "/armor_meshes/" + label + ".json";
        JsonObject root = loadJson(meshPath);

        require(root.has("format") && root.get("format").isJsonPrimitive()
                        && root.get("format").getAsJsonPrimitive().isNumber()
                        && root.get("format").getAsDouble() == PlateArmorMesh.FORMAT,
                "网格 format 必须是 " + PlateArmorMesh.FORMAT);
        require(root.has("item") && label.equals(root.get("item").getAsString()),
                "网格 item 字段必须等于物品注册名，实得 " + root.get("item"));
        int[] textureSize = textureSize(root);
        require(textureSize[0] > 0 && textureSize[1] > 0,
                "textureSize 必须为正，实得 " + Arrays.toString(textureSize));

        JsonObject parts = root.getAsJsonObject("parts");
        require(parts != null, "网格缺少 parts");
        for (String key : parts.keySet()) {
            require(PARTS.contains(key), "网格出现未知部位 " + key + "；渲染器只画 " + PARTS + "，多出来的部件会静默消失");
        }
        int quadTotal = 0;
        for (String part : PARTS) {
            JsonElement element = parts.get(part);
            require(element != null && element.isJsonArray(), part + " 必须是数组（手臂可以为空）");
            JsonArray quads = element.getAsJsonArray();
            if (PlateArmorMesh.PART_BODY.equals(part)) {
                require(quads.size() > 0, "body 不能为空");
            }
            for (int quad = 0; quad < quads.size(); quad++) {
                verifyQuad(part + " #" + quad, quads.get(quad));
            }
            if (quads.size() > 0) {
                verifyPartAnchor(part, bounds(quads));
            }
            quadTotal += quads.size();
        }

        // 与 PlateArmorItem.getArmorTexture 的拼法一致；构建期 JVM 里构造不了 Item，只能在这里复述一遍路径。
        String texturePath = "/assets/" + ArmorerMod.MODID + "/textures/models/armor/" + label + "_layer_1.png";
        BufferedImage image = loadImage(texturePath);
        // 尺寸是网格 uv 归一化时的分母，对不上就整件错位。
        require(image.getWidth() == textureSize[0] && image.getHeight() == textureSize[1],
                "网格声明贴图 " + textureSize[0] + "x" + textureSize[1] + "，实际 PNG 是 "
                        + image.getWidth() + "x" + image.getHeight());
        // 导出端丢掉了整块透明的面，留下的每个面在同一次导出的贴图里都至少有一个不透明像素。
        for (String part : PARTS) {
            JsonArray quads = parts.getAsJsonArray(part);
            for (int quad = 0; quad < quads.size(); quad++) {
                require(hasOpaqueTexel(image, quads.get(quad).getAsJsonArray()),
                        part + " #" + quad + " 的贴图矩形里一个不透明像素都没有；网格和贴图不是同一次导出的结果，"
                                + "回本体重跑 export.mjs 后成对同步");
            }
        }

        // 客户端真正使用的解析器必须接受每一件打包的网格，否则那件护甲在客户端会退回原版模型。
        PlateArmorMesh mesh;
        try (InputStream input = openResource(meshPath, "插板护甲网格");
             Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            mesh = PlateArmorMesh.parse(reader, label);
        } catch (JsonParseException exception) {
            throw new MeshContractViolation("网格被客户端解析器拒绝：" + exception.getMessage());
        } catch (IOException exception) {
            throw new UncheckedIOException("读取插板护甲网格失败：" + meshPath, exception);
        }
        require(mesh.quadCount() == quadTotal,
                "解析后的四边形数 " + mesh.quadCount() + " 与文件里的 " + quadTotal + " 不符");
    }

    private static void verifyQuad(String label, JsonElement element) {
        require(element.isJsonArray() && element.getAsJsonArray().size() == PlateArmorMesh.QUAD_FLOATS,
                label + " 必须恰好是 " + PlateArmorMesh.QUAD_FLOATS + " 个数");
        JsonArray values = element.getAsJsonArray();
        double[] quad = new double[PlateArmorMesh.QUAD_FLOATS];
        for (int index = 0; index < quad.length; index++) {
            JsonElement value = values.get(index);
            require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                            && Double.isFinite(value.getAsDouble()),
                    label + " 第 " + index + " 项不是有限数：" + value);
            quad[index] = value.getAsDouble();
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * PlateArmorMesh.VERTEX_FLOATS;
            double x = quad[base];
            double y = quad[base + 1];
            double z = quad[base + 2];
            require(Math.abs(x) <= ENVELOPE_X && y >= ENVELOPE_MIN_Y && y <= ENVELOPE_MAX_Y
                            && Math.abs(z) <= ENVELOPE_Z,
                    label + " 顶点 " + vertex + " 坐标 (" + x + ", " + y + ", " + z + ") 越出部位粗外框");
            double u = quad[base + 3];
            double v = quad[base + 4];
            require(u >= -UV_TOLERANCE && u <= 1.0D + UV_TOLERANCE
                            && v >= -UV_TOLERANCE && v <= 1.0D + UV_TOLERANCE,
                    label + " 顶点 " + vertex + " 的 uv (" + u + ", " + v + ") 越出 0 至 1");
        }

        // uv 必须是按 (u2,v1)(u1,v1)(u1,v2)(u2,v2) 排的正向矩形，顺序一乱贴图就转向或镜像。
        double u0 = quad[3];
        double v0 = quad[4];
        double u1 = quad[PlateArmorMesh.VERTEX_FLOATS + 3];
        double v1 = quad[PlateArmorMesh.VERTEX_FLOATS + 4];
        double u2 = quad[2 * PlateArmorMesh.VERTEX_FLOATS + 3];
        double v2 = quad[2 * PlateArmorMesh.VERTEX_FLOATS + 4];
        double u3 = quad[3 * PlateArmorMesh.VERTEX_FLOATS + 3];
        double v3 = quad[3 * PlateArmorMesh.VERTEX_FLOATS + 4];
        require(v0 == v1 && u1 == u2 && v2 == v3 && u3 == u0 && u0 > u1 && v2 > v1,
                label + " 的 uv 不是 (u2,v1)(u1,v1)(u1,v2)(u2,v2) 顺序的正向矩形：(" + u0 + "," + v0 + ") ("
                        + u1 + "," + v1 + ") (" + u2 + "," + v2 + ") (" + u3 + "," + v3 + ")");

        int n = PlateArmorMesh.NORMAL_OFFSET;
        double nx = quad[n];
        double ny = quad[n + 1];
        double nz = quad[n + 2];
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        require(Math.abs(length - 1.0D) <= NORMAL_TOLERANCE, label + " 的法线长度 " + length + " 不是单位长度");

        // 几何法线 (v1-v0)×(v2-v1)：同时要求法线垂直于面、并且与顶点绕序同向（原版 Polygon 的约定）。
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
        require(area > 1.0E-9D, label + " 是零面积的面");
        double agreement = (cx * nx + cy * ny + cz * nz) / (area * length);
        require(agreement >= MIN_NORMAL_AGREEMENT,
                label + " 的法线与顶点绕序算出的几何法线不一致（点积 " + agreement + "）");
    }

    /** 部位锚点：拦挂点被重复烘焙、左右臂写反、单位错；阈值见类注释。 */
    private static void verifyPartAnchor(String part, double[] box) {
        String where = part + " 包围盒 x " + box[MIN_X] + " 至 " + box[MAX_X] + "，y " + box[MIN_Y] + " 至 " + box[MAX_Y];
        switch (part) {
            case PlateArmorMesh.PART_BODY -> require(
                    box[MIN_X] <= -4.0D && box[MAX_X] >= 4.0D && box[MIN_Y] <= 1.0D && box[MAX_Y] >= 10.0D,
                    where + " 没盖住躯干（要求 minX ≤ -4、maxX ≥ 4、minY ≤ 1、maxY ≥ 10）；单位错了？");
            case PlateArmorMesh.PART_RIGHT_ARM -> require(
                    box[MIN_X] <= -3.0D && box[MAX_X] >= -1.0D && box[MAX_X] <= 2.0D,
                    where + " 不贴着手臂局部坐标 x -3 至 1（要求 minX ≤ -3、-1 ≤ maxX ≤ 2）；"
                            + "挂点 (-5,2,0) 被烘焙进顶点，或与 left_arm 写反了？");
            case PlateArmorMesh.PART_LEFT_ARM -> require(
                    box[MAX_X] >= 3.0D && box[MIN_X] >= -2.0D && box[MIN_X] <= 1.0D,
                    where + " 不贴着手臂局部坐标 x -1 至 3（要求 maxX ≥ 3、-2 ≤ minX ≤ 1）；"
                            + "挂点 (5,2,0) 被烘焙进顶点，或与 right_arm 写反了？");
            default -> throw new IllegalStateException("未知部位 " + part);
        }
    }

    /** 一个部位全部顶点的包围盒：[minX, minY, minZ, maxX, maxY, maxZ]。 */
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

    /** 四边形 uv 矩形覆盖的贴图像素里是否至少有一个不透明（alpha 非 0）。 */
    private static boolean hasOpaqueTexel(BufferedImage image, JsonArray quad) {
        int width = image.getWidth();
        int height = image.getHeight();
        double uLow = quad.get(PlateArmorMesh.VERTEX_FLOATS + 3).getAsDouble() * width;
        double vLow = quad.get(PlateArmorMesh.VERTEX_FLOATS + 4).getAsDouble() * height;
        double uHigh = quad.get(3).getAsDouble() * width;
        double vHigh = quad.get(2 * PlateArmorMesh.VERTEX_FLOATS + 4).getAsDouble() * height;
        // uv 已校验落在 0 至 1 ± 1e-6，乘上贴图尺寸后可能越界不到一个像素，这里收回贴图范围内。
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

    private static int[] textureSize(JsonObject root) {
        JsonElement element = root.get("textureSize");
        require(element != null && element.isJsonArray() && element.getAsJsonArray().size() == 2,
                "网格 textureSize 必须是 [宽, 高]");
        JsonArray size = element.getAsJsonArray();
        double width = size.get(0).getAsDouble();
        double height = size.get(1).getAsDouble();
        require(width == Math.rint(width) && height == Math.rint(height), "textureSize 必须是整数，实得 " + size);
        return new int[]{(int) width, (int) height};
    }

    private static JsonObject loadJson(String path) {
        try (InputStream input = openResource(path, "插板护甲网格")) {
            JsonElement parsed = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            require(parsed.isJsonObject(), "网格文件顶层必须是 JSON 对象：" + path);
            return parsed.getAsJsonObject();
        } catch (JsonParseException exception) {
            throw new MeshContractViolation("网格不是合法 JSON：" + path + "：" + exception.getMessage());
        } catch (IOException exception) {
            throw new UncheckedIOException("读取插板护甲网格失败：" + path, exception);
        }
    }

    private static BufferedImage loadImage(String path) {
        try (InputStream input = openResource(path, "插板护甲穿戴贴图")) {
            BufferedImage image = ImageIO.read(input);
            require(image != null, "无法解码插板护甲穿戴贴图：" + path);
            return image;
        } catch (IOException exception) {
            throw new UncheckedIOException("读取插板护甲穿戴贴图失败：" + path, exception);
        }
    }

    private static InputStream openResource(String path, String what) {
        InputStream input = PlateArmorMeshVerifier.class.getResourceAsStream(path);
        require(input != null, "缺少" + what + "：" + path);
        return input;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new MeshContractViolation(message);
        }
    }

    /** 资源违反网格契约；只有它会被汇总，校验器自身的 bug 与 IO 故障照常抛出让构建失败。 */
    private static final class MeshContractViolation extends RuntimeException {
        MeshContractViolation(String message) {
            super(message);
        }
    }
}
