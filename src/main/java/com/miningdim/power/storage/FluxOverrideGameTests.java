package com.miningdim.power.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Flux Networks 配方覆盖的资产契约。
 *
 * 覆盖第三方配方靠的是"同命名空间同 recipe id 的文件顶替对方 jar 里的同名文件"，任何一处写错
 * （命名空间写成 miningdim、id 拼错、产物 id 少个 s）都不会报错，只会静默失效——本仓在 champions
 * 词条覆盖上已经栽过一次（F062：35 个文件写进了自己的命名空间，看似生效实为死数据）。故把路径、
 * 产物 id 与门槛材料全部钉成断言。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FluxOverrideGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "flux_override";
    private static final String FLUX_CORE_RECIPE = "/data/fluxnetworks/recipes/fluxcore.json";

    private FluxOverrideGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fluxCoreOverrideSitsInFluxNamespaceAndGatesOnEndgameMaterials(GameTestHelper helper) {
        JsonObject recipe = readJson(FLUX_CORE_RECIPE);

        // 产物必须是 Flux 自己的物品 id (复数 fluxnetworks), 写错一个字母整份覆盖就是死数据。
        JsonObject result = recipe.getAsJsonObject("result");
        helper.assertTrue(result.get("item").getAsString().equals("fluxnetworks:flux_core"),
                "覆盖配方的产物必须是 fluxnetworks:flux_core, 得到 " + result.get("item").getAsString());
        helper.assertTrue(result.get("count").getAsInt() == 1,
                "原配方一次产 4 个, 覆盖后必须降为 1 个以体现终局门槛, 得到 " + result.get("count").getAsInt());

        // 未装 Flux 时该配方必须被条件跳过, 否则日志里会刷未知物品告警。
        helper.assertTrue(recipe.has("conditions"),
                "覆盖配方必须带 forge:mod_loaded 条件, 否则未装 Flux 的环境会报未知物品");
        JsonObject condition = recipe.getAsJsonArray("conditions").get(0).getAsJsonObject();
        helper.assertTrue(condition.get("type").getAsString().equals("forge:mod_loaded")
                        && condition.get("modid").getAsString().equals("fluxnetworks"),
                "加载条件必须精确指向 fluxnetworks");

        // 门槛必须与未来燃料芯同级: 石墨烯 + YBCO 带 + 下界之星, 即推到未来发电机之后。
        JsonObject key = recipe.getAsJsonObject("key");
        helper.assertTrue(key.getAsJsonObject("g").get("item").getAsString()
                        .equals("miningdim:graphene_sheet"), "门槛必须含石墨烯");
        helper.assertTrue(key.getAsJsonObject("y").get("item").getAsString()
                        .equals("miningdim:ybco_tape"), "门槛必须含 YBCO 带");
        helper.assertTrue(key.getAsJsonObject("n").get("item").getAsString()
                        .equals("minecraft:nether_star"), "门槛必须含下界之星");
        helper.succeed();
    }

    private static JsonObject readJson(String path) {
        try (InputStream input = FluxOverrideGameTests.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("missing flux override asset: " + path
                        + " (覆盖文件必须放在对方的命名空间下, 不是 miningdim)");
            }
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException exception) {
            throw new IllegalStateException("unreadable flux override asset: " + path, exception);
        }
    }
}
