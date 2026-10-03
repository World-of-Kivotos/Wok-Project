package com.miningdim.job.engineer.armor.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.Reader;
import java.util.Set;

/**
 * 一件插板护甲离线烘焙好的穿戴网格，对应 assets/miningdim/armor_meshes/plate_armor_&lt;id&gt;.json。
 *
 * <p>数据由 tools/plate_armor/export.mjs 从预览设计（armors/&lt;key&gt;.js 的 M 档）直接导出，游戏里看到的就是预览里
 * 看到的：每个四边形已经按原版 ModelPart.Polygon 的顶点顺序展开，旋转盒子的位置与法线也已离线算好，渲染时只做
 * 部位位姿变换，不再有任何几何推导。</p>
 *
 * <p>每个部位的四边形连续压在一个 float 数组里，每个四边形 {@link #QUAD_FLOATS} 个数：
 * 四个顶点各 x、y、z、u、v（部位局部坐标，单位为皮肤像素，y 朝下、正面为 -z；u、v 已除以贴图尺寸，落在 0 至 1，
 * v 朝下），最后是一条单位法线。手臂部位可以为空数组，body 必须非空（导出端遇到空 body 直接报错）。</p>
 *
 * <p>本类只依赖 Gson，不引用任何客户端类：GameTest 在专用服务端进程里会直接调用 {@link #parse} 校验打包进 JAR 的
 * 网格，保证客户端解析器与导出格式不会悄悄分叉。虽然放在 client 包里，也不要在这里引入 net.minecraft.client 下的类，
 * 否则服务端 GameTest 加载本类时会因分端剥离直接崩溃（clientMeshParserAcceptsEveryShippedMesh 会最先报出来）。</p>
 */
public final class PlateArmorMesh {

    /** 当前唯一支持的网格文件格式版本。 */
    public static final int FORMAT = 1;
    /** 每个四边形占用的 float 个数：4 × (x, y, z, u, v) + (nx, ny, nz)。 */
    public static final int QUAD_FLOATS = 23;
    /** 一个顶点占用的 float 个数。 */
    public static final int VERTEX_FLOATS = 5;
    /** 法线在四边形内的起始下标。 */
    public static final int NORMAL_OFFSET = 4 * VERTEX_FLOATS;

    public static final String PART_BODY = "body";
    public static final String PART_RIGHT_ARM = "right_arm";
    public static final String PART_LEFT_ARM = "left_arm";
    /** 网格只允许这三个部位；头、帽子层与双腿保持为空。 */
    public static final Set<String> PART_NAMES = Set.of(PART_BODY, PART_RIGHT_ARM, PART_LEFT_ARM);

    /** u、v 取值的容差：导出时取到 1e-8 并朝面内取整，本就落在 0 至 1 之内，这里只吸收别的工具写文件时的舍入误差。 */
    private static final double UV_TOLERANCE = 1.0E-6D;
    /** 法线长度的容差：分量四舍五入到 1e-4，长度误差远小于该值。 */
    private static final double NORMAL_TOLERANCE = 1.0E-3D;

    private static final float[] EMPTY = new float[0];

    private final String item;
    private final int textureWidth;
    private final int textureHeight;
    private final float[] body;
    private final float[] rightArm;
    private final float[] leftArm;

    private PlateArmorMesh(String item, int textureWidth, int textureHeight,
                           float[] body, float[] rightArm, float[] leftArm) {
        this.item = item;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.body = body;
        this.rightArm = rightArm;
        this.leftArm = leftArm;
    }

    /**
     * 解析并校验一个网格文件。
     *
     * @param reader       网格 JSON
     * @param expectedItem 期望的物品注册名（不含命名空间），如 plate_armor_jaypc_olive
     * @throws JsonParseException 文件不是合法 JSON，或违反网格数据契约（消息里写明哪一项、哪个部位、第几个四边形）
     */
    public static PlateArmorMesh parse(Reader reader, String expectedItem) {
        JsonElement parsed = JsonParser.parseReader(reader);
        if (!parsed.isJsonObject()) {
            throw new JsonParseException("网格文件顶层必须是 JSON 对象");
        }
        JsonObject root = parsed.getAsJsonObject();

        int format = requireInt(root, "format");
        if (format != FORMAT) {
            throw new JsonParseException("不支持的网格格式 " + format + "，当前只认 " + FORMAT);
        }
        JsonElement itemElement = root.get("item");
        if (itemElement == null || !itemElement.isJsonPrimitive() || !itemElement.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("缺少字符串字段 item");
        }
        String item = itemElement.getAsString();
        if (!item.equals(expectedItem)) {
            throw new JsonParseException("网格声明的物品是 " + item + "，但按文件名应为 " + expectedItem);
        }

        JsonElement sizeElement = root.get("textureSize");
        if (sizeElement == null || !sizeElement.isJsonArray() || sizeElement.getAsJsonArray().size() != 2) {
            throw new JsonParseException("textureSize 必须是 [宽, 高] 两个整数");
        }
        JsonArray size = sizeElement.getAsJsonArray();
        int textureWidth = requirePositiveInt(size.get(0), "textureSize[0]");
        int textureHeight = requirePositiveInt(size.get(1), "textureSize[1]");

        JsonElement partsElement = root.get("parts");
        if (partsElement == null || !partsElement.isJsonObject()) {
            throw new JsonParseException("缺少对象字段 parts");
        }
        JsonObject parts = partsElement.getAsJsonObject();
        for (String key : parts.keySet()) {
            if (!PART_NAMES.contains(key)) {
                // 未知部位会被渲染器静默丢掉，宁可整件退回原版模型也不要让设计里的部件悄悄消失。
                throw new JsonParseException("未知部位 " + key + "，只允许 " + PART_NAMES);
            }
        }
        float[] body = parsePart(parts, PART_BODY);
        if (body.length == 0) {
            throw new JsonParseException("body 部位不能为空");
        }
        return new PlateArmorMesh(item, textureWidth, textureHeight,
                body, parsePart(parts, PART_RIGHT_ARM), parsePart(parts, PART_LEFT_ARM));
    }

    private static float[] parsePart(JsonObject parts, String name) {
        JsonElement element = parts.get(name);
        if (element == null) {
            return EMPTY;
        }
        if (!element.isJsonArray()) {
            throw new JsonParseException(name + " 必须是四边形数组");
        }
        JsonArray quads = element.getAsJsonArray();
        float[] packed = new float[quads.size() * QUAD_FLOATS];
        for (int quad = 0; quad < quads.size(); quad++) {
            JsonElement quadElement = quads.get(quad);
            if (!quadElement.isJsonArray() || quadElement.getAsJsonArray().size() != QUAD_FLOATS) {
                throw new JsonParseException(name + " 第 " + quad + " 个四边形必须恰好是 " + QUAD_FLOATS + " 个数");
            }
            JsonArray values = quadElement.getAsJsonArray();
            int base = quad * QUAD_FLOATS;
            for (int index = 0; index < QUAD_FLOATS; index++) {
                double value = requireFinite(values.get(index), name, quad, index);
                int slot = index % VERTEX_FLOATS;
                if (index < NORMAL_OFFSET && slot >= 3
                        && (value < -UV_TOLERANCE || value > 1.0D + UV_TOLERANCE)) {
                    throw new JsonParseException(name + " 第 " + quad + " 个四边形的 uv 越出 0 至 1：" + value);
                }
                packed[base + index] = (float) value;
            }
            float nx = packed[base + NORMAL_OFFSET];
            float ny = packed[base + NORMAL_OFFSET + 1];
            float nz = packed[base + NORMAL_OFFSET + 2];
            double length = Math.sqrt((double) nx * nx + (double) ny * ny + (double) nz * nz);
            if (Math.abs(length - 1.0D) > NORMAL_TOLERANCE) {
                throw new JsonParseException(name + " 第 " + quad + " 个四边形的法线不是单位长度：" + length);
            }
        }
        return packed;
    }

    private static double requireFinite(JsonElement element, String part, int quad, int index) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(part + " 第 " + quad + " 个四边形的第 " + index + " 项不是数字");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) {
            throw new JsonParseException(part + " 第 " + quad + " 个四边形的第 " + index + " 项不是有限数");
        }
        return value;
    }

    private static int requireInt(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("缺少整数字段 " + key);
        }
        return exactInt(element.getAsJsonPrimitive(), key);
    }

    private static int requirePositiveInt(JsonElement element, String label) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(label + " 必须是整数");
        }
        int value = exactInt(element.getAsJsonPrimitive(), label);
        if (value <= 0) {
            throw new JsonParseException(label + " 必须为正：" + value);
        }
        return value;
    }

    private static int exactInt(JsonPrimitive primitive, String label) {
        double value = primitive.getAsDouble();
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new JsonParseException(label + " 必须是整数：" + primitive);
        }
        return (int) value;
    }

    public String item() {
        return item;
    }

    public int textureWidth() {
        return textureWidth;
    }

    public int textureHeight() {
        return textureHeight;
    }

    /** 躯干部位的四边形，按 {@link #QUAD_FLOATS} 个一组连续存放；调用方不得修改。 */
    public float[] body() {
        return body;
    }

    /** 右臂（穿戴者右侧）部位的四边形，坐标为手臂局部坐标；调用方不得修改。 */
    public float[] rightArm() {
        return rightArm;
    }

    /** 左臂（穿戴者左侧）部位的四边形，坐标为手臂局部坐标；调用方不得修改。 */
    public float[] leftArm() {
        return leftArm;
    }

    /** 三个部位合计的四边形数量。 */
    public int quadCount() {
        return (body.length + rightArm.length + leftArm.length) / QUAD_FLOATS;
    }
}
