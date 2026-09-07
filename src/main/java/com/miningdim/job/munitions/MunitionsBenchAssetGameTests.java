package com.miningdim.job.munitions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 军火台 GeckoLib 资产的契约测试。
 *
 * 缘由: runGameTestServer 是纯服务端进程, 从不加载 {@code models/item}, 所以六个物品模型的合法性在
 * "1443 tests passed" 里一个字节都没被验过 —— 曾经有一个 {@code to.x = 32.12} 的 element 越过原版
 * {@code BlockElement.MAX_EXTENT} 一路进了 PR, 客户端一加载资源包该档图标就退化成缺失模型。这些断言
 * 全部只读 JSON 与资源存在性, 不需要客户端, 但能把那类缺陷挡在服务端质量门上。
 *
 * 期望边界取原版常量, 不从产物反推; 骨骼名与动画名取自 Java 侧真正引用它们的地方。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MunitionsBenchAssetGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "munitions_bench_assets";

    /** 渲染器与动画控制器实际引用的骨骼; 少一根就是模型与代码对不上。 */
    private static final List<String> REQUIRED_BONES = List.of("root", "body", "press", "ram", "carousel", "drawer");
    /** {@code MunitionsBenchBlockEntity} 里 RawAnimation 常量引用的动画名。 */
    private static final List<String> REQUIRED_ANIMATIONS = List.of("machine.idle", "machine.production");
    /** 原版 {@code BlockElement.Deserializer} 只接受这几个旋转角。 */
    private static final Set<Double> LEGAL_ROTATION_ANGLES = Set.of(-45.0D, -22.5D, 0.0D, 22.5D, 45.0D);

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
                JsonObject faces = element.getAsJsonObject("faces");
                helper.assertTrue(faces != null && faces.size() >= 1 && faces.size() <= 6,
                        benchId + " element " + index + " 的 faces 数量必须在 1-6, 实得 "
                                + (faces == null ? "null" : faces.size()));
                for (String face : faces.keySet()) {
                    JsonArray uv = faces.getAsJsonObject(face).getAsJsonArray("uv");
                    for (int axis = 0; axis < uv.size(); axis++) {
                        double value = uv.get(axis).getAsDouble();
                        helper.assertTrue(value >= 0.0D && value <= 16.0D,
                                benchId + " element " + index + " 的 " + face + " 面 uv 必须落在 0-16, 实得 "
                                        + value);
                    }
                }
                if (element.has("rotation")) {
                    double angle = element.getAsJsonObject("rotation").get("angle").getAsDouble();
                    helper.assertTrue(LEGAL_ROTATION_ANGLES.contains(angle),
                            benchId + " element " + index + " 的 rotation.angle=" + angle
                                    + " 不在原版允许的 -45/-22.5/0/22.5/45 之内");
                }
            }
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

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyBenchGeometryDeclaresTheBonesAndTexturesTheRendererNeeds(GameTestHelper helper) {
        Set<String> identifiers = new HashSet<>();
        for (String benchId : MunitionsBenchAssets.TIER_IDS) {
            JsonObject geometry = MunitionsBenchAssets.geometry(benchId);
            JsonObject description = geometry.getAsJsonObject("description");
            String identifier = description.get("identifier").getAsString();
            helper.assertTrue(identifier.startsWith("geometry.miningdim.munitions_bench")
                            && identifiers.add(identifier),
                    benchId + " 的 geo identifier 必须是 miningdim 命名空间下的唯一值, 实得 " + identifier);

            Set<String> bones = new HashSet<>();
            JsonArray boneArray = geometry.getAsJsonArray("bones");
            for (int index = 0; index < boneArray.size(); index++) {
                JsonObject bone = boneArray.get(index).getAsJsonObject();
                String name = bone.get("name").getAsString();
                helper.assertTrue(bones.add(name), benchId + " 的 geo 有重名骨骼: " + name);
                if (bone.has("parent")) {
                    helper.assertTrue(bones.contains(bone.get("parent").getAsString()),
                            benchId + " 的骨骼 " + name + " 引用了不存在或后声明的父骨骼 "
                                    + bone.get("parent").getAsString());
                }
            }
            for (String required : REQUIRED_BONES) {
                helper.assertTrue(bones.contains(required),
                        benchId + " 的 geo 缺少渲染器与动画要用的骨骼 " + required);
            }

            // 贴图路径是 MunitionsBenchGeoModel 用字符串拼出来的, 拼错只在客户端首次渲染时才炸。
            String suffix = benchId.substring("munitions_bench".length());
            String texture = "/assets/miningdim/textures/block/munitions_bench_geo" + suffix + ".png";
            helper.assertTrue(MunitionsBenchAssets.resourceExists(texture),
                    benchId + " 缺少 GeoModel 按后缀拼出来的贴图 " + texture);

            int textureWidth = description.get("texture_width").getAsInt();
            int textureHeight = description.get("texture_height").getAsInt();
            JsonObject itemModel = MunitionsBenchAssets.itemModel(benchId);
            JsonArray itemTextureSize = itemModel.getAsJsonArray("texture_size");
            helper.assertTrue(itemTextureSize.get(0).getAsInt() == textureWidth
                            && itemTextureSize.get(1).getAsInt() == textureHeight,
                    benchId + " 物品模型的 texture_size 必须与 geo 的 texture_width/height 一致, 否则 uv 错位");
            helper.assertTrue(("miningdim:block/munitions_bench_geo" + suffix).equals(
                            itemModel.getAsJsonObject("textures").get("atlas").getAsString()),
                    benchId + " 物品模型必须绑定同档的 geo 图集");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchAnimationDeclaresExactlyTheClipsTheControllerPlays(GameTestHelper helper) {
        JsonObject animations = MunitionsBenchAssets
                .loadJson("/assets/miningdim/animations/block/munitions_bench.animation.json")
                .getAsJsonObject("animations");
        for (String required : REQUIRED_ANIMATIONS) {
            helper.assertTrue(animations.has(required),
                    "动画文件缺少 MunitionsBenchBlockEntity 引用的 " + required);
        }
        helper.assertTrue(animations.size() == REQUIRED_ANIMATIONS.size(),
                "动画文件里有没人播放的孤儿动画: " + animations.keySet());

        Set<String> boneNames = new HashSet<>(REQUIRED_BONES);
        for (String clip : animations.keySet()) {
            JsonObject bones = animations.getAsJsonObject(clip).getAsJsonObject("bones");
            for (String bone : bones.keySet()) {
                helper.assertTrue(boneNames.contains(bone),
                        "动画 " + clip + " 驱动了 geo 里不存在的骨骼 " + bone);
            }
        }

        // 弹盘的连续旋转由渲染器直接驱动骨骼, 不能再由动画通道抢写, 否则两边打架。
        for (String clip : animations.keySet()) {
            helper.assertTrue(!animations.getAsJsonObject(clip).getAsJsonObject("bones").has("carousel"),
                    "动画 " + clip + " 不得驱动 carousel 骨骼: 该骨骼的角度由 MunitionsBenchRenderer 维护");
        }

        // 待机动画必须真能看见。0.08 模型单位 = 1/200 格, 连一个像素都不到, 等于没有待机表现。
        JsonObject idleRam = animations.getAsJsonObject("machine.idle")
                .getAsJsonObject("bones").getAsJsonObject("ram").getAsJsonObject("position");
        double amplitude = 0.0D;
        for (String keyframe : idleRam.keySet()) {
            JsonArray vector = idleRam.get(keyframe).isJsonArray()
                    ? idleRam.getAsJsonArray(keyframe)
                    : idleRam.getAsJsonObject(keyframe).getAsJsonArray("vector");
            amplitude = Math.max(amplitude, Math.abs(vector.get(1).getAsDouble()));
        }
        helper.assertTrue(amplitude >= 0.25D,
                "machine.idle 的振幅 " + amplitude + " 模型单位不足 1/4 像素, 玩家看不见待机动画");
        helper.succeed();
    }

    private static double[] triple(JsonArray array) {
        return new double[]{array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble()};
    }
}
