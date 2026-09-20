package com.miningdim.job.munitions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 军火台资产的只读访问层。
 *
 * 存在的理由是让断言能拿到一个独立于被测 Java 常量的真相源: 碰撞箱高度、正面外伸这些数字本来就是照着
 * geo 模型量出来的, 测试若直接抄 {@code MunitionsBenchBlock} 里的常量, 就只是把实现复述一遍, 改错了
 * 也不会挂。这里直接读打进 JAR 的 geo/物品模型 JSON。
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

    /** 原版 {@code BlockElement} 的坐标硬边界; 越界会让整个物品模型退化成缺失模型。 */
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

    public static JsonObject geometry(String benchId) {
        return loadJson("/assets/miningdim/geo/block/" + benchId + ".geo.json")
                .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
    }

    public static JsonObject itemModel(String benchId) {
        return loadJson("/assets/miningdim/models/item/" + benchId + ".json");
    }

    /**
     * 整台机器 (geo 里全部骨骼的全部 cube) 的包围盒, 单位是模型像素, 坐标系是 Bedrock 的
     * (一格 = 16, 原点在方块中心)。
     *
     * 刻意取全部骨骼而不是只取 body: 调用方要的是碰撞箱与轮廓形状该覆盖到哪里, 而外伸的出料抽屉
     * (drawer 骨骼) 正是轮廓必须包住的那一截。只统计 body 会让轮廓断言少算这一截, 抽屉再伸出去也没人拦。
     *
     * @return {@code [minX, minY, minZ, maxX, maxY, maxZ]}
     */
    public static double[] geometryBoundsPixels(String benchId) {
        JsonArray bones = geometry(benchId).getAsJsonArray("bones");
        double[] bounds = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        boolean found = false;
        for (int index = 0; index < bones.size(); index++) {
            JsonObject bone = bones.get(index).getAsJsonObject();
            if (!bone.has("cubes")) {
                continue;
            }
            JsonArray cubes = bone.getAsJsonArray("cubes");
            for (int cubeIndex = 0; cubeIndex < cubes.size(); cubeIndex++) {
                JsonObject cube = cubes.get(cubeIndex).getAsJsonObject();
                JsonArray origin = cube.getAsJsonArray("origin");
                JsonArray size = cube.getAsJsonArray("size");
                found = true;
                for (int axis = 0; axis < 3; axis++) {
                    double low = origin.get(axis).getAsDouble();
                    double high = low + size.get(axis).getAsDouble();
                    bounds[axis] = Math.min(bounds[axis], low);
                    bounds[axis + 3] = Math.max(bounds[axis + 3], high);
                }
            }
        }
        if (!found) {
            throw new IllegalStateException(benchId + " 的 geo 模型里一个 cube 都没有");
        }
        return bounds;
    }
}
