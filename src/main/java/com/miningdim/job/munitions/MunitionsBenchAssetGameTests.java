package com.miningdim.job.munitions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.block.MunitionsBenchBlock;
import com.miningdim.job.munitions.block.MunitionsBenchCounter;
import com.miningdim.job.munitions.block.MunitionsBenchGeometry;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 军火台资产 (方块状态、静态方块模型、物品模型、贴图) 的契约测试。
 *
 * 缘由: runGameTestServer 是纯服务端进程, 从不加载 {@code models/} 与 {@code blockstates/}, 所以这些 JSON 的合法性在
 * "N tests passed" 里一个字节都没被验过 —— 曾经有一个 {@code to.x = 32.12} 的 element 越过原版
 * {@code BlockElement.MAX_EXTENT} 一路进了 PR, 客户端一加载资源包该档图标就退化成缺失模型。这些断言全部只读 JSON
 * 与资源存在性, 不需要客户端, 但能把那类缺陷挡在服务端质量门上。
 *
 * 期望边界取原版常量; 方块状态要覆盖的属性组合取自真正注册的方块 ({@code getPossibleStates()}), 不从产物反推;
 * LEGACY 老台子的模型映射按改版前 (git HEAD 上无 layout 键的那版) 的规则逐条核对。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MunitionsBenchAssetGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "munitions_bench_assets";

    /** 原版 {@code BlockElement.Deserializer} 只接受这几个旋转角。 */
    private static final Set<Double> LEGAL_ROTATION_ANGLES = Set.of(-45.0D, -22.5D, 0.0D, 22.5D, 45.0D);
    /** 单个静态模型文件的 element 上限 (与枪匠工位同一预算; 生成器也按它校验)。 */
    private static final int MAX_ELEMENTS_PER_MODEL = 90;
    /** 静态模型的图集与破坏粒子贴图尺寸 (生成器的画布), 与运动件贴图 {@link MunitionsBenchGeometry#PARTS_TEXTURE_WIDTH}。 */
    private static final int ATLAS_SIZE = 128;
    private static final int PARTICLE_SIZE = 16;
    private static final String[] PARTS = {"main", "extension"};

    private MunitionsBenchAssetGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyBenchItemModelStaysInsideVanillaElementBounds(GameTestHelper helper) {
        for (String benchId : MunitionsBenchAssets.TIER_IDS) {
            JsonObject model = MunitionsBenchAssets.itemModel(benchId);
            JsonArray elements = model.getAsJsonArray("elements");
            helper.assertTrue(elements != null && elements.size() > 0,
                    benchId + " 物品模型必须含 elements");
            for (int index = 0; index < elements.size(); index++) {
                JsonObject element = elements.get(index).getAsJsonObject();
                double[] from = triple(element.getAsJsonArray("from"));
                double[] to = triple(element.getAsJsonArray("to"));
                for (int axis = 0; axis < 3; axis++) {
                    assertInBounds(helper, benchId, index, "from", axis, from[axis]);
                    assertInBounds(helper, benchId, index, "to", axis, to[axis]);
                    helper.assertTrue(to[axis] >= from[axis],
                            benchId + " element " + index + " 的 to 必须不小于 from, 轴 " + axis);
                }
                assertFaces(helper, benchId + " 物品模型 element " + index, element);
                assertRotation(helper, benchId + " 物品模型 element " + index, element);
            }
            // 物品模型借用同档方块模型的图集: 名字拼错只在客户端首次渲染时才会变成紫黑格。
            assertTextureBindings(helper, benchId + " 物品模型", model, benchId);
        }
        helper.succeed();
    }

    private static void assertInBounds(GameTestHelper helper, String benchId, int index,
                                       String field, int axis, double value) {
        helper.assertTrue(value >= MunitionsBenchAssets.MIN_EXTENT && value <= MunitionsBenchAssets.MAX_EXTENT,
                benchId + " element " + index + " 的 " + field + " 轴 " + axis + " = " + value
                        + " 越出原版 BlockElement 的 [" + MunitionsBenchAssets.MIN_EXTENT + ", "
                        + MunitionsBenchAssets.MAX_EXTENT + "]; 原版会抛 JsonParseException 并把整个"
                        + "物品模型换成缺失模型, 而服务端 GameTest 永远看不到");
    }

    /**
     * 每一档、每一种属性组合 (朝向 × 格 × 布局 × 工作) 都要恰好命中一条变体, 指向存在的模型文件:
     * LEGACY 老台子仍是改版前的整格模型 ({@code <档>_<格>[_active]}, 旋转规则不变), WIDE 是弹药流水线的
     * {@code <档>_line_<格>[_active]}; 两种布局都按朝向转 y (北 0 / 东 90 / 南 180 / 西 270)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyBenchBlockstateResolvesEveryStateToTheRightModel(GameTestHelper helper) {
        for (String benchId : MunitionsBenchAssets.TIER_IDS) {
            Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(MiningConstants.MODID, benchId));
            helper.assertTrue(block instanceof MunitionsBenchBlock, benchId + " 必须注册成军火台方块");
            JsonObject variants = MunitionsBenchAssets.blockstate(benchId).getAsJsonObject("variants");
            helper.assertTrue(variants != null && !variants.has(""), benchId + " 的方块状态必须按属性分变体");
            List<BlockState> states = block.getStateDefinition().getPossibleStates();
            helper.assertTrue(states.size() == 32,
                    benchId + " 应有 4 朝向 × 2 格 × 2 布局 × 2 工作 = 32 种状态, 实得 " + states.size());
            for (BlockState state : states) {
                List<JsonObject> matches = new ArrayList<>();
                for (Map.Entry<String, JsonElement> variant : variants.entrySet()) {
                    if (variantMatches(helper, block, benchId, variant.getKey(), state)) {
                        matches.add(variant.getValue().getAsJsonObject());
                    }
                }
                helper.assertTrue(matches.size() == 1,
                        benchId + " 的状态 " + state + " 必须恰好命中一条变体, 实得 " + matches.size());
                JsonObject variant = matches.get(0);

                boolean wide = state.getValue(MunitionsBenchBlock.LAYOUT) == MunitionsBenchBlock.Layout.WIDE;
                String part = state.getValue(MunitionsBenchBlock.PART).getSerializedName();
                boolean active = state.getValue(MunitionsBenchBlock.ACTIVE);
                String expectedModel = wide
                        ? MunitionsBenchAssets.lineModelName(benchId, part, active)
                        : benchId + "_" + part + (active ? "_active" : "");
                String model = variant.get("model").getAsString();
                helper.assertTrue(("miningdim:block/" + expectedModel).equals(model),
                        benchId + " 的状态 " + state + " 应指向 miningdim:block/" + expectedModel + ", 实得 " + model
                                + (wide ? "" : "; LEGACY 老台子必须原样保留改版前的模型"));
                helper.assertTrue(MunitionsBenchAssets.resourceExists(MunitionsBenchAssets.blockModelPath(expectedModel)),
                        benchId + " 的状态 " + state + " 指向的模型文件不存在: " + expectedModel);

                int expectedY = 90 * MunitionsBenchBlock.quarterTurns(state.getValue(MunitionsBenchBlock.FACING));
                int y = variant.has("y") ? variant.get("y").getAsInt() : 0;
                helper.assertTrue(y == expectedY && !variant.has("x"),
                        benchId + " 的状态 " + state + " 应只绕 y 转 " + expectedY + " 度, 实得 y=" + y
                                + (variant.has("x") ? " 且带 x 旋转" : ""));
            }
        }
        helper.succeed();
    }

    private static boolean variantMatches(GameTestHelper helper, Block block, String benchId, String key,
                                          BlockState state) {
        for (String condition : key.split(",")) {
            String[] pair = condition.split("=", 2);
            Property<?> property = pair.length == 2 ? block.getStateDefinition().getProperty(pair[0]) : null;
            helper.assertTrue(property != null, benchId + " 的变体键 \"" + key + "\" 里有方块不认识的属性: " + condition);
            if (!valueName(state, property).equals(pair[1])) {
                return false;
            }
        }
        return true;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /**
     * WIDE 的 24 个静态模型: 每格一个文件, 坐标不出自己的格子 (x/z 0..16, y 0..32), 不超过 element 预算,
     * 面只贴同档的图集, 发光面的 forge_data 光照在 0..15; 各档的最高点就是 {@link MunitionsBenchGeometry#MODEL_TOP_PX}
     * (碰撞箱与渲染包围盒都按它定高度, 两边对不上说明生成器与 Java 常量分叉了)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyLineModelStaysInsideItsCellAndTheElementBudget(GameTestHelper helper) {
        for (int tier = 0; tier < MunitionsBenchAssets.TIER_IDS.length; tier++) {
            String benchId = MunitionsBenchAssets.TIER_IDS[tier];
            double top = 0.0D;
            for (String part : PARTS) {
                for (boolean active : new boolean[]{false, true}) {
                    String name = MunitionsBenchAssets.lineModelName(benchId, part, active);
                    JsonObject model = MunitionsBenchAssets.blockModel(name);
                    JsonArray elements = model.getAsJsonArray("elements");
                    helper.assertTrue(elements != null && elements.size() > 0
                                    && elements.size() <= MAX_ELEMENTS_PER_MODEL,
                            name + " 必须有 1.." + MAX_ELEMENTS_PER_MODEL + " 个 element, 实得 "
                                    + (elements == null ? "null" : elements.size()));
                    assertTextureBindings(helper, name, model, benchId);
                    for (int index = 0; index < elements.size(); index++) {
                        JsonObject element = elements.get(index).getAsJsonObject();
                        double[] from = triple(element.getAsJsonArray("from"));
                        double[] to = triple(element.getAsJsonArray("to"));
                        for (int axis = 0; axis < 3; axis++) {
                            double max = axis == 1 ? 32.0D : 16.0D;
                            helper.assertTrue(from[axis] >= 0.0D && to[axis] <= max && to[axis] >= from[axis],
                                    name + " element " + index + " 轴 " + axis + " 的 [" + from[axis] + ", " + to[axis]
                                            + "] 必须落在格子里 [0, " + max + "]: 每格的模型只画自己那一格");
                        }
                        top = Math.max(top, to[1]);
                        assertFaces(helper, name + " element " + index, element);
                        assertRotation(helper, name + " element " + index, element);
                    }
                }
            }
            helper.assertTrue(Math.abs(top - MunitionsBenchGeometry.MODEL_TOP_PX[tier]) < 1.0E-6D,
                    benchId + " 静态模型的最高点 " + top + " px 与 MunitionsBenchGeometry.MODEL_TOP_PX["
                            + tier + "] = " + MunitionsBenchGeometry.MODEL_TOP_PX[tier] + " 对不上");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchTexturesHaveTheSizesTheirModelsAssume(GameTestHelper helper) {
        for (String benchId : MunitionsBenchAssets.TIER_IDS) {
            assertImageSize(helper, "/assets/miningdim/textures/block/" + benchId + "_atlas.png",
                    ATLAS_SIZE, ATLAS_SIZE);
            assertImageSize(helper, "/assets/miningdim/textures/block/" + benchId + "_particle.png",
                    PARTICLE_SIZE, PARTICLE_SIZE);
        }
        // 运动件的盒式 UV 按 LayerDefinition 声明的尺寸算 (MunitionsBenchParts 与 Geometry 同一个生成器写出),
        // PNG 实际尺寸对不上时所有运动件的贴图整体错位, 而这种错位在服务端一个字节都看不出来。
        assertImageSize(helper, "/assets/miningdim/textures/entity/munitions_bench_parts.png",
                MunitionsBenchGeometry.PARTS_TEXTURE_WIDTH, MunitionsBenchGeometry.PARTS_TEXTURE_HEIGHT);
        helper.succeed();
    }

    /**
     * 弹药箱计数屏 (方案 C) 的常量 {@link MunitionsBenchGeometry} COUNTER_* 与每一档、待机与工作的主格静态模型对得上:
     * 箱盖窗后那块本体 (can_lid) 的北面就是 COUNTER_WINDOW_FACE_TOP_LEFT 起、COUNTER_WINDOW_QT 大的屏窗, 绕 x 转
     * COUNTER_LID_ROTATION_X_DEGREES (原点 COUNTER_LID_ROTATION_ORIGIN); 四条框条 (can_lid_t/b/l/r) 从窗面往前
     * COUNTER_RECESS_PX 起、同一个旋转, 合起来正好是 COUNTER_LID_FACE_QT; 箱身 (can) 的北面就是 COUNTER_STENCIL_FACE_*。
     * 满度条与发数框都在窗里、互不重叠; 箱盖 8 px 宽、0.75 px 厚 (方案 C)。对不上说明渲染器会把字画到模型外面去。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void counterConstantsMatchTheLidWindowAndTheCanFront(GameTestHelper helper) {
        double qt = MunitionsBenchGeometry.COUNTER_QT_PX;
        float[] tl = MunitionsBenchGeometry.COUNTER_WINDOW_FACE_TOP_LEFT;
        int[] window = MunitionsBenchGeometry.COUNTER_WINDOW_QT;
        int[] face = MunitionsBenchGeometry.COUNTER_LID_FACE_QT;
        float[] origin = MunitionsBenchGeometry.COUNTER_LID_ROTATION_ORIGIN;
        float[] stencil = MunitionsBenchGeometry.COUNTER_STENCIL_FACE_TOP_LEFT;
        int[] stencilSize = MunitionsBenchGeometry.COUNTER_STENCIL_FACE_QT;
        for (String benchId : MunitionsBenchAssets.TIER_IDS) {
            for (boolean active : new boolean[]{false, true}) {
                String name = MunitionsBenchAssets.lineModelName(benchId, "main", active);
                JsonObject model = MunitionsBenchAssets.blockModel(name);
                JsonObject body = element(helper, model, name, "can_lid");
                double[] from = triple(body.getAsJsonArray("from"));
                double[] to = triple(body.getAsJsonArray("to"));
                assertNear(helper, name + " can_lid (window body) north face", new double[]{
                                tl[0] - (window[0] + window[2]) * qt, tl[1] - (window[1] + window[3]) * qt, tl[2],
                                tl[0] - window[0] * qt, tl[1] - window[1] * qt},
                        new double[]{from[0], from[1], from[2], to[0], to[1]});
                assertLidRotation(helper, name + " can_lid", body, origin);
                double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                double back = -Double.MAX_VALUE;
                for (String strip : new String[]{"can_lid_t", "can_lid_b", "can_lid_l", "can_lid_r"}) {
                    JsonObject element = element(helper, model, name, strip);
                    double[] sFrom = triple(element.getAsJsonArray("from"));
                    double[] sTo = triple(element.getAsJsonArray("to"));
                    helper.assertTrue(Math.abs(sFrom[2] - (tl[2] - MunitionsBenchGeometry.COUNTER_RECESS_PX)) < 1.0E-6D,
                            name + " " + strip + " must start " + MunitionsBenchGeometry.COUNTER_RECESS_PX
                                    + " px in front of the window face, got z " + sFrom[2]);
                    assertLidRotation(helper, name + " " + strip, element, origin);
                    minX = Math.min(minX, sFrom[0]);
                    minY = Math.min(minY, sFrom[1]);
                    maxX = Math.max(maxX, sTo[0]);
                    maxY = Math.max(maxY, sTo[1]);
                    back = Math.max(back, sTo[2]);
                }
                assertNear(helper, name + " lid face (frame strips)", new double[]{tl[0], tl[1], face[0], face[1]},
                        new double[]{maxX, maxY, (maxX - minX) / qt, (maxY - minY) / qt});
                helper.assertTrue(Math.abs((maxX - minX) - 8.0D) < 1.0E-6D
                                && Math.abs(back - (tl[2] - MunitionsBenchGeometry.COUNTER_RECESS_PX) - 0.75D) < 1.0E-6D,
                        name + " option C: the lid is 8 px wide and 0.75 px thick, got " + (maxX - minX) + " x "
                                + (back - (tl[2] - MunitionsBenchGeometry.COUNTER_RECESS_PX)));

                JsonObject can = element(helper, model, name, "can");
                double[] cFrom = triple(can.getAsJsonArray("from"));
                double[] cTo = triple(can.getAsJsonArray("to"));
                helper.assertFalse(can.has("rotation"), name + " the can front must not turn");
                assertNear(helper, name + " can north face (calibre stencil)",
                        new double[]{stencil[0], stencil[1], stencil[2], stencilSize[0], stencilSize[1]},
                        new double[]{cTo[0], cTo[1], cFrom[2], (cTo[0] - cFrom[0]) / qt, (cTo[1] - cFrom[1]) / qt});
            }
        }
        int[] bar = MunitionsBenchGeometry.COUNTER_BAR_QT;
        int[] count = MunitionsBenchGeometry.COUNTER_COUNT_QT;
        int countHeight = MunitionsBenchCounter.FONT_4X7.height * MunitionsBenchGeometry.COUNTER_COUNT_TEXEL_QT;
        helper.assertTrue(inside(bar[0], bar[1], bar[2], bar[3], window) && inside(count[0], count[1], count[2], countHeight, window)
                        && bar[1] + bar[3] <= count[1],
                "the fill bar (on top) and the count box must sit inside the window without overlapping");
        helper.assertTrue(inside(window[0], window[1], window[2], window[3], new int[]{0, 0, face[0], face[1]}),
                "the window must sit inside the lid face");
        helper.succeed();
    }

    private static JsonObject element(GameTestHelper helper, JsonObject model, String modelName, String elementName) {
        for (JsonElement element : model.getAsJsonArray("elements")) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("name") && elementName.equals(object.get("name").getAsString())) {
                return object;
            }
        }
        helper.fail(modelName + " has no element named " + elementName);
        throw new IllegalStateException("unreachable");
    }

    private static void assertLidRotation(GameTestHelper helper, String label, JsonObject element, float[] origin) {
        JsonObject rotation = element.getAsJsonObject("rotation");
        helper.assertTrue(rotation != null && "x".equals(rotation.get("axis").getAsString())
                        && Math.abs(rotation.get("angle").getAsDouble() - MunitionsBenchGeometry.COUNTER_LID_ROTATION_X_DEGREES) < 1.0E-6D,
                label + " must turn about x by COUNTER_LID_ROTATION_X_DEGREES, got " + rotation);
        double[] at = triple(rotation.getAsJsonArray("origin"));
        assertNear(helper, label + " rotation origin", new double[]{origin[0], origin[1], origin[2]}, at);
    }

    private static void assertNear(GameTestHelper helper, String label, double[] expected, double[] actual) {
        for (int i = 0; i < expected.length; i++) {
            helper.assertTrue(Math.abs(expected[i] - actual[i]) < 1.0E-6D,
                    label + ": expected " + java.util.Arrays.toString(expected) + ", model has "
                            + java.util.Arrays.toString(actual));
        }
    }

    private static boolean inside(int x, int y, int w, int h, int[] box) {
        return x >= box[0] && y >= box[1] && x + w <= box[0] + box[2] && y + h <= box[1] + box[3];
    }

    /** 军火台不再走 GeckoLib: 旧的骨骼模型、动画与调色板图集都不该再打进 JAR (GeckoLib 本身还留给厨师调味台)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void noBenchGeckoLibAssetsRemain(GameTestHelper helper) {
        List<String> leftovers = new ArrayList<>();
        for (String benchId : MunitionsBenchAssets.TIER_IDS) {
            String suffix = benchId.substring("munitions_bench".length());
            leftovers.add("/assets/miningdim/geo/block/" + benchId + ".geo.json");
            leftovers.add("/assets/miningdim/textures/block/munitions_bench_geo" + suffix + ".png");
        }
        leftovers.add("/assets/miningdim/geo/block/munitions_bench_legacy_empty.geo.json");
        leftovers.add("/assets/miningdim/animations/block/munitions_bench.animation.json");
        for (String path : leftovers) {
            helper.assertFalse(MunitionsBenchAssets.resourceExists(path),
                    "军火台已改为静态模型 + 方块实体渲染器, GeckoLib 资产不该再存在: " + path);
        }
        helper.succeed();
    }

    private static void assertTextureBindings(GameTestHelper helper, String label, JsonObject model, String benchId) {
        JsonObject textures = model.getAsJsonObject("textures");
        helper.assertTrue(textures != null, label + " 必须声明 textures");
        String atlas = "miningdim:block/" + benchId + "_atlas";
        String particle = "miningdim:block/" + benchId + "_particle";
        helper.assertTrue(textures.has("atlas") && atlas.equals(textures.get("atlas").getAsString()),
                label + " 必须绑定同档图集 " + atlas);
        helper.assertTrue(textures.has("particle") && particle.equals(textures.get("particle").getAsString()),
                label + " 的破坏粒子必须是同档的 " + particle);
        for (String texture : new String[]{atlas, particle}) {
            String path = "/assets/miningdim/textures/block/" + texture.substring("miningdim:block/".length()) + ".png";
            helper.assertTrue(MunitionsBenchAssets.resourceExists(path), label + " 引用的贴图不存在: " + path);
        }
    }

    private static void assertFaces(GameTestHelper helper, String label, JsonObject element) {
        JsonObject faces = element.getAsJsonObject("faces");
        helper.assertTrue(faces != null && faces.size() >= 1 && faces.size() <= 6,
                label + " 的 faces 数量必须在 1-6, 实得 " + (faces == null ? "null" : faces.size()));
        for (String faceName : faces.keySet()) {
            helper.assertTrue(Direction.byName(faceName) != null, label + " 有不存在的面 " + faceName);
            JsonObject face = faces.getAsJsonObject(faceName);
            helper.assertTrue("#atlas".equals(face.get("texture").getAsString()),
                    label + " 的 " + faceName + " 面必须贴 #atlas");
            JsonArray uv = face.getAsJsonArray("uv");
            helper.assertTrue(uv != null && uv.size() == 4, label + " 的 " + faceName + " 面必须有 4 个 uv 值");
            for (int axis = 0; axis < uv.size(); axis++) {
                double value = uv.get(axis).getAsDouble();
                helper.assertTrue(value >= 0.0D && value <= 16.0D,
                        label + " 的 " + faceName + " 面 uv 必须落在 0-16, 实得 " + value);
            }
            if (face.has("forge_data")) {
                JsonObject data = face.getAsJsonObject("forge_data");
                for (String key : new String[]{"block_light", "sky_light"}) {
                    if (data.has(key)) {
                        int light = data.get(key).getAsInt();
                        helper.assertTrue(light >= 0 && light <= 15,
                                label + " 的 " + faceName + " 面 forge_data." + key + " = " + light + " 不在 0..15");
                    }
                }
            }
        }
    }

    private static void assertRotation(GameTestHelper helper, String label, JsonObject element) {
        if (element.has("rotation")) {
            double angle = element.getAsJsonObject("rotation").get("angle").getAsDouble();
            helper.assertTrue(LEGAL_ROTATION_ANGLES.contains(angle),
                    label + " 的 rotation.angle=" + angle + " 不在原版允许的 -45/-22.5/0/22.5/45 之内");
        }
    }

    private static void assertImageSize(GameTestHelper helper, String path, int width, int height) {
        helper.assertTrue(MunitionsBenchAssets.resourceExists(path), "缺少军火台贴图 " + path);
        BufferedImage image = MunitionsBenchAssets.loadImage(path);
        helper.assertTrue(image.getWidth() == width && image.getHeight() == height,
                path + " 应是 " + width + "x" + height + ", 实际 " + image.getWidth() + "x" + image.getHeight());
    }

    private static double[] triple(JsonArray array) {
        return new double[]{array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble()};
    }
}
