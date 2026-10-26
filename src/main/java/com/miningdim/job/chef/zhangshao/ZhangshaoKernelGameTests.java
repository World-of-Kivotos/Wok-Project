package com.miningdim.job.chef.zhangshao;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ZhangshaoKernelGameTests {
    private static final String EMPTY = "empty";
    private static final String BATCH = "chef_zhangshao";
    private static final String GOLDEN = "/data/miningdim/zhangshao/golden.json";

    private ZhangshaoKernelGameTests() {}

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void goldenVectorsAndCases(GameTestHelper helper) {
        try (InputStream input = ZhangshaoKernelGameTests.class.getResourceAsStream(GOLDEN)) {
            if (input == null) {
                helper.fail("缺少掌勺 golden.json: " + GOLDEN);
                return;
            }
            JsonObject golden = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            expect(golden.get("simVersion").getAsInt() == ZhangshaoKernel.SIM_VERSION,
                    "golden simVersion 与内核不一致");
            verifyVectors(golden.getAsJsonObject("vectors"));
            JsonArray cases = golden.getAsJsonArray("cases");
            Set<Integer> stations = new HashSet<>();
            ZhangshaoKernel.Trace firstTrace = null;
            int[] firstInputs = null;
            int firstSeed = 0;
            ZhangshaoKernel.Params firstParams = null;
            for (JsonElement element : cases) {
                JsonObject c = element.getAsJsonObject();
                ZhangshaoKernel.Trace trace = verifyCase(c);
                stations.add(c.getAsJsonArray("params").get(1).getAsInt());
                if (firstTrace == null) {
                    firstTrace = trace;
                    firstInputs = expandInputs(c.getAsJsonArray("inputsRle"));
                    firstSeed = c.get("seed").getAsInt();
                    firstParams = new ZhangshaoKernel.Params(toInts(c.getAsJsonArray("params")));
                }
            }
            expect(cases.size() == 15, "golden 应包含 15 个业务 case，实际 " + cases.size());
            expect(stations.size() == 4, "golden 未覆盖四台结果，实际台数 " + stations.size());
            verifyDeterministicReplay(firstParams, firstSeed, firstInputs, firstTrace);
            verifyReservedBitAbandon(firstParams, firstSeed);
            helper.succeed();
        } catch (Throwable failure) {
            helper.fail("掌勺内核 golden 对拍失败: " + failure.getMessage());
        }
    }

    private static void verifyVectors(JsonObject vectors) {
        for (JsonElement element : vectors.getAsJsonArray("mulberry32")) {
            JsonObject vector = element.getAsJsonObject();
            ZhangshaoKernel.Mulberry32 random = new ZhangshaoKernel.Mulberry32(vector.get("state").getAsInt());
            for (JsonElement expected : vector.getAsJsonArray("next")) {
                expect(random.next() == expected.getAsInt(), "Mulberry32 next 向量不一致");
            }
            expect(random.a == vector.get("stateAfter").getAsInt(), "Mulberry32 stateAfter 不一致");
        }
        JsonObject rndRange = vectors.getAsJsonObject("rndRange");
        ZhangshaoKernel.Mulberry32 random = new ZhangshaoKernel.Mulberry32(rndRange.get("state").getAsInt());
        for (JsonElement opElement : rndRange.getAsJsonArray("ops")) {
            JsonArray op = opElement.getAsJsonArray();
            int got;
            switch (op.get(0).getAsString()) {
                case "rnd" -> got = random.rnd(op.get(1).getAsInt());
                case "range" -> got = random.range(op.get(1).getAsInt(), op.get(2).getAsInt());
                case "next" -> got = random.next();
                default -> throw new AssertionError("未知随机向量操作 " + op.get(0));
            }
            expect(got == op.get(op.size() - 1).getAsInt(), "rnd/range 向量不一致");
        }
        for (JsonElement element : vectors.getAsJsonArray("fmix32")) {
            JsonArray pair = element.getAsJsonArray();
            expect(ZhangshaoKernel.fmix32(pair.get(0).getAsInt()) == pair.get(1).getAsInt(),
                    "fmix32 向量不一致");
        }
        for (JsonElement element : vectors.getAsJsonArray("streams")) {
            JsonObject stream = element.getAsJsonObject();
            int seed = stream.get("seed").getAsInt();
            expect(ZhangshaoKernel.fmix32(seed ^ ZhangshaoKernel.SALT_T) == stream.get("rT").getAsInt(),
                    "rT 向量不一致");
            expect(ZhangshaoKernel.fmix32(seed ^ ZhangshaoKernel.SALT_B) == stream.get("rB").getAsInt(),
                    "rB 向量不一致");
            expect(ZhangshaoKernel.fmix32(seed ^ ZhangshaoKernel.SALT_S) == stream.get("rS").getAsInt(),
                    "rS 向量不一致");
        }
        JsonObject fnv = vectors.getAsJsonObject("fnv1a");
        expect(ZhangshaoKernel.hex32(ZhangshaoKernel.fnv1a(toInts(fnv.getAsJsonArray("ints"))))
                        .equals(fnv.get("hash").getAsString()), "fnv1a 向量不一致");
    }

    private static ZhangshaoKernel.Trace verifyCase(JsonObject c) {
        String name = c.get("name").getAsString();
        JsonObject config = c.getAsJsonObject("config");
        ZhangshaoConfig.Options options = new ZhangshaoConfig.Options();
        options.station = config.get("station").getAsInt();
        options.star = config.get("star").getAsInt();
        options.level = config.get("chefLevel").getAsInt();
        if (config.has("motion")) options.motion = config.get("motion").getAsInt();
        if (config.has("mastery")) options.mastery = config.get("mastery").getAsInt();
        if (config.has("batch")) options.batch = config.get("batch").getAsInt();
        if (config.has("flips")) options.flips = config.get("flips").getAsInt();
        if (config.has("beats")) options.beats = config.get("beats").getAsInt();
        if (config.has("drink")) options.drink = config.get("drink").getAsBoolean();
        if (config.has("goldPm")) options.goldPm = config.get("goldPm").getAsInt();
        if (config.has("normalPm")) options.normalPm = config.get("normalPm").getAsInt();

        ZhangshaoConfig serverConfig = new ZhangshaoConfig();
        if (c.has("configOverride")) applyOverride(serverConfig, c.getAsJsonObject("configOverride"));
        int[] expectedParams = toInts(c.getAsJsonArray("params"));
        expect(Arrays.equals(expectedParams, serverConfig.buildParams(options)), name + " 参数块不一致");

        int seed = c.get("seed").getAsInt();
        ZhangshaoKernel.Params params = new ZhangshaoKernel.Params(expectedParams);
        expect(ZhangshaoKernel.hex32(params.hash()).equals(c.get("paramsHash").getAsString()),
                name + " paramsHash 不一致");
        expect(ZhangshaoKernel.hex32(ZhangshaoKernel.hash(ZhangshaoKernel.init(params, seed)))
                        .equals(c.get("initHash").getAsString()), name + " initHash 不一致");

        int[] inputs = expandInputs(c.getAsJsonArray("inputsRle"));
        expect(inputs.length == c.get("inputCount").getAsInt(), name + " inputCount 不一致");
        ZhangshaoKernel.Ext ext = new ZhangshaoKernel.Ext();
        if (c.has("ext") && c.getAsJsonObject("ext").has("gold")) {
            ext.gold = c.getAsJsonObject("ext").get("gold").getAsInt();
        }
        ZhangshaoKernel.Trace trace = ZhangshaoKernel.replay(params, seed, inputs, ext);
        expect(trace.result.ended == 1, name + " 回放未到达终局");
        expect(trace.hashes.equals(toStrings(c.getAsJsonArray("hashes"))), name + " tick 哈希不一致");
        expect(trace.finalHash.equals(c.get("finalHash").getAsString()), name + " finalHash 不一致");
        expect(trace.consumed == inputs.length, name + " consumed 不一致");
        expect(toJson(trace.result.toMap()).equals(c.getAsJsonObject("result")), name + " result 不一致");
        return trace;
    }

    private static void verifyDeterministicReplay(
            ZhangshaoKernel.Params params, int seed, int[] inputs, ZhangshaoKernel.Trace expected) {
        ZhangshaoKernel.Trace replay = ZhangshaoKernel.replay(params, seed, inputs, new ZhangshaoKernel.Ext());
        expect(replay.hashes.equals(expected.hashes), "同 seed 与输入的中间哈希不确定");
        expect(replay.finalHash.equals(expected.finalHash), "同 seed 与输入回放结果不确定");
    }

    private static void verifyReservedBitAbandon(ZhangshaoKernel.Params params, int seed) {
        ZhangshaoKernel.Trace trace = ZhangshaoKernel.replay(params, seed, new int[]{4}, new ZhangshaoKernel.Ext());
        expect(trace.invalid == 1, "保留位非法输入未标记 invalid");
        expect(trace.consumed == 0, "保留位非法输入不应消耗该字节");
        expect(trace.result.end == ZhangshaoKernel.ABANDON, "保留位非法输入未撂勺");
    }

    private static void applyOverride(ZhangshaoConfig config, JsonObject override) {
        for (String key : override.keySet()) {
            switch (key) {
                case "segmentsByStar" -> config.segmentsByStar = toInts(override.getAsJsonArray(key));
                default -> throw new IllegalArgumentException("未知金样本配置覆盖: " + key);
            }
        }
    }

    private static int[] expandInputs(JsonArray runs) {
        ArrayList<Integer> values = new ArrayList<>();
        for (JsonElement element : runs) {
            JsonArray run = element.getAsJsonArray();
            for (int i = 0; i < run.get(0).getAsInt(); i++) values.add(run.get(1).getAsInt());
        }
        int[] result = new int[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }

    private static int[] toInts(JsonArray values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i).getAsInt();
        return result;
    }

    private static ArrayList<String> toStrings(JsonArray values) {
        ArrayList<String> result = new ArrayList<>(values.size());
        for (JsonElement value : values) result.add(value.getAsString());
        return result;
    }

    private static JsonElement toJson(Object value) {
        return new com.google.gson.Gson().toJsonTree(value);
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
