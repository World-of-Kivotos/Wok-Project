package com.miningdim.job.munitions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 军火台资产的只读访问层。
 *
 * 存在的理由是让断言能拿到一个独立于被测 Java 常量的真相源: 碰撞箱高度这些数字本来就是照着静态模型量出来的,
 * 测试若直接抄 {@code MunitionsBenchBlock} 里的常量, 就只是把实现复述一遍, 改错了也不会挂。这里直接读打进 JAR 的
 * 方块状态 / 方块模型 / 物品模型 JSON 与贴图。
 */
public final class MunitionsBenchAssets {

    /** 六档军火台的注册 id 后缀, 与 {@code ModMunitionsBlocks} 和资产文件名一一对应。 */
    public static final String[] TIER_IDS = {
            "munitions_bench",
            "munitions_bench_medium",
            "munitions_bench_high",
            "munitions_bench_superior",
            "munitions_bench_transcendent",
            "munitions_bench_radiant",
    };

    /** 原版 {@code BlockElement} 的坐标硬边界; 越界会让整个模型退化成缺失模型。 */
    public static final double MIN_EXTENT = -16.0D;
    public static final double MAX_EXTENT = 32.0D;

    private MunitionsBenchAssets() {
    }

    public static JsonObject loadJson(String path) {
        try (InputStream input = MunitionsBenchAssets.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("找不到军火台资产: " + path);
            }
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException exception) {
            throw new IllegalStateException("读取军火台资产失败: " + path, exception);
        }
    }

    public static boolean resourceExists(String path) {
        try (InputStream input = MunitionsBenchAssets.class.getResourceAsStream(path)) {
            return input != null;
        } catch (IOException exception) {
            throw new IllegalStateException("探测军火台资产失败: " + path, exception);
        }
    }

    public static BufferedImage loadImage(String path) {
        try (InputStream input = MunitionsBenchAssets.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("找不到军火台贴图: " + path);
            }
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new IllegalStateException("无法解码军火台贴图: " + path);
            }
            return image;
        } catch (IOException exception) {
            throw new IllegalStateException("读取军火台贴图失败: " + path, exception);
        }
    }

    public static JsonObject blockstate(String benchId) {
        return loadJson("/assets/miningdim/blockstates/" + benchId + ".json");
    }

    /** @param modelName 不带命名空间与 {@code block/} 前缀的模型名, 例如 {@code munitions_bench_line_main} */
    public static JsonObject blockModel(String modelName) {
        return loadJson(blockModelPath(modelName));
    }

    public static String blockModelPath(String modelName) {
        return "/assets/miningdim/models/block/" + modelName + ".json";
    }

    public static JsonObject itemModel(String benchId) {
        return loadJson("/assets/miningdim/models/item/" + benchId + ".json");
    }

    /** WIDE 布局 (弹药流水线) 某一格的静态模型名: {@code <档>_line_<main|extension>[_active]}。 */
    public static String lineModelName(String benchId, String part, boolean active) {
        return benchId + "_line_" + part + (active ? "_active" : "");
    }

    /**
     * 一个方块模型全部 element 的 from/to 包围盒 (格子局部像素, 0..16 一格)。旋转件按未旋转的 from/to 算:
     * 军火台的旋转件 (箱盖、托盘、屏) 都不是最高点, 也不贴格子边, 不影响调用方要的顶面与占位。
     *
     * @return {@code [minX, minY, minZ, maxX, maxY, maxZ]}
     */
    public static double[] blockModelBoundsPixels(String modelName) {
        JsonArray elements = blockModel(modelName).getAsJsonArray("elements");
        if (elements == null || elements.isEmpty()) {
            throw new IllegalStateException(modelName + " 的方块模型里一个 element 都没有");
        }
        double[] bounds = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int index = 0; index < elements.size(); index++) {
            JsonObject element = elements.get(index).getAsJsonObject();
            JsonArray from = element.getAsJsonArray("from");
            JsonArray to = element.getAsJsonArray("to");
            for (int axis = 0; axis < 3; axis++) {
                bounds[axis] = Math.min(bounds[axis], from.get(axis).getAsDouble());
                bounds[axis + 3] = Math.max(bounds[axis + 3], to.get(axis).getAsDouble());
            }
        }
        return bounds;
    }
}
