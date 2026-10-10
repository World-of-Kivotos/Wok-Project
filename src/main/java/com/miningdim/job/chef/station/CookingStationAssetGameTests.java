package com.miningdim.job.chef.station;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.chef.ChefBlocks;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.RegistryObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 九台烹饪台的资源契约: 方块状态 JSON 与注册的方块状态一一对应, 每条变体指向存在的模型并按朝向转 y;
 * 模型沿 parent 链解析得出元素, 每个面的 #贴图变量都解析得到本 MOD 自己的贴图文件; 物品模型同样能解析。
 *
 * 缘由: runGameTestServer 是纯服务端进程, 从不加载 blockstates/ 与 models/。以前这层只靠 station-preview 的
 * check.mjs (临时目录, 用完就删) 查过; 有人把属性名改回 active、给托架换回跨 MOD 贴图 (农夫乐事 1.3.x 已改名) 或
 * 拼错贴图名, 服务端测试照样全绿, 客户端却是紫黑格。这里只读 classpath 上的 JSON 与 PNG, 不需要客户端。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class CookingStationAssetGameTests {

    private static final String EMPTY = "empty";
    private static final String NAMESPACE = MiningConstants.MODID + ":";
    /** 根模型只允许继承原版这一个空壳 (只带 display), 元素与贴图都在本 MOD 的模型里。 */
    private static final String VANILLA_ROOT = "minecraft:block/block";
    /** 原版 BlockElement 的坐标范围与允许的旋转角。 */
    private static final double MIN_EXTENT = -16.0D;
    private static final double MAX_EXTENT = 32.0D;
    private static final Set<Double> LEGAL_ROTATION_ANGLES = Set.of(-45.0D, -22.5D, 0.0D, 22.5D, 45.0D);

    private CookingStationAssetGameTests() {
    }

    /**
     * 每台的每种方块状态都恰好有一条变体 (键按属性名排序拼成 name=value,..., 与文件里的写法一致), 不多不少;
     * 变体只绕 y 转 (北 0 / 东 90 / 南 180 / 西 270), 不开 uvlock, 指向的模型完整可解析。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = CookingStationGameTests.BATCH)
    public static void blockstatesCoverEveryStateAndResolveToCompleteModels(GameTestHelper helper) {
        for (RegistryObject<Block> entry : ChefBlocks.COOKING_STATIONS) {
            String id = entry.getId().getPath();
            JsonObject variants = CookingStationGameTests
                    .loadJsonResource("/assets/miningdim/blockstates/" + id + ".json").getAsJsonObject("variants");
            helper.assertTrue(variants != null, id + " 的方块状态必须按属性分变体 (variants)");
            List<BlockState> states = entry.get().getStateDefinition().getPossibleStates();
            Set<String> expectedKeys = new HashSet<>();
            for (BlockState state : states) {
                String key = variantKey(state);
                expectedKeys.add(key);
                helper.assertTrue(variants.has(key), id + " 的方块状态缺变体 \"" + key + "\" (客户端会显示成紫黑格)");
                JsonObject variant = variants.getAsJsonObject(key);
                int expectedY = 90 * ((state.getValue(CookingStationBlock.FACING).get2DDataValue() + 2) & 3);
                int y = variant.has("y") ? variant.get("y").getAsInt() : 0;
                helper.assertTrue(y == expectedY && !variant.has("x") && !variant.has("uvlock"),
                        id + " 的 \"" + key + "\" 应只绕 y 转 " + expectedY + " 度且不开 uvlock, 实为 " + variant);
                String model = variant.get("model").getAsString();
                helper.assertTrue(model.startsWith(NAMESPACE + "block/"), id + " 的 \"" + key + "\" 应指向本 MOD 的方块模型: " + model);
                assertModelResolves(helper, id + " 的 \"" + key + "\"", model);
            }
            Set<String> extra = new HashSet<>(variants.keySet());
            extra.removeAll(expectedKeys);
            helper.assertTrue(extra.isEmpty(), id + " 的方块状态里有对不上任何状态的变体 (属性名或取值写错了?): " + extra);
        }
        helper.succeed();
    }

    /** 九个物品模型存在, 并能沿 parent 链解析出元素与贴图。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = CookingStationGameTests.BATCH)
    public static void everyStationItemModelResolves(GameTestHelper helper) {
        for (RegistryObject<Block> entry : ChefBlocks.COOKING_STATIONS) {
            String id = entry.getId().getPath();
            assertModelResolves(helper, id + " 的物品模型", NAMESPACE + "item/" + id);
        }
        helper.succeed();
    }

    /** 方块状态变体键: 属性按名字排序, name=value 用逗号连接 (与原版 blockstate 文件和 F3 的写法一致)。 */
    private static String variantKey(BlockState state) {
        return state.getValues().keySet().stream()
                .sorted(Comparator.comparing(Property::getName))
                .map(property -> property.getName() + "=" + valueName(state, property))
                .collect(Collectors.joining(","));
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /**
     * 沿 parent 链解析一个本 MOD 的模型: 链上每个本 MOD 模型文件都存在, 链尾只能是原版的 block/block;
     * 子模型的 textures 覆盖父模型, elements 取离叶子最近的一份且不能为空; 每个元素坐标在原版范围内、旋转角合法;
     * 每个面的贴图和 particle 经 #变量 解析后都是本 MOD 的贴图, 且对应的 PNG 存在。
     */
    private static void assertModelResolves(GameTestHelper helper, String label, String modelId) {
        Map<String, String> textures = new HashMap<>();
        JsonArray elements = null;
        String current = modelId;
        Set<String> visited = new HashSet<>();
        while (current != null && current.startsWith(NAMESPACE)) {
            helper.assertTrue(visited.add(current), label + " 的模型 parent 链成环: " + current);
            String path = "/assets/miningdim/models/" + current.substring(NAMESPACE.length()) + ".json";
            helper.assertTrue(resourceExists(path), label + " 引用的模型文件不存在: " + path);
            JsonObject model = CookingStationGameTests.loadJsonResource(path);
            if (model.has("textures")) {
                for (Map.Entry<String, JsonElement> texture : model.getAsJsonObject("textures").entrySet()) {
                    textures.putIfAbsent(texture.getKey(), texture.getValue().getAsString());
                }
            }
            if (elements == null && model.has("elements")) {
                elements = model.getAsJsonArray("elements");
            }
            current = model.has("parent") ? model.get("parent").getAsString() : null;
        }
        helper.assertTrue(VANILLA_ROOT.equals(current),
                label + " 的模型链应止于 " + VANILLA_ROOT + ", 实为 " + current + " (从 " + modelId + " 出发)");
        helper.assertTrue(elements != null && elements.size() > 0, label + " 的模型链里没有元素: " + modelId);

        assertTextureResolves(helper, label + " (" + modelId + ") 的 particle", textures, "#particle");
        for (int index = 0; index < elements.size(); index++) {
            JsonObject element = elements.get(index).getAsJsonObject();
            String where = label + " (" + modelId + ") 元素 " + index
                    + (element.has("name") ? " \"" + element.get("name").getAsString() + "\"" : "");
            for (String corner : new String[]{"from", "to"}) {
                JsonArray xyz = element.getAsJsonArray(corner);
                for (int axis = 0; axis < 3; axis++) {
                    double value = xyz.get(axis).getAsDouble();
                    helper.assertTrue(value >= MIN_EXTENT && value <= MAX_EXTENT,
                            where + " 的 " + corner + " 越出原版范围 [-16, 32]: " + value);
                }
            }
            if (element.has("rotation")) {
                double angle = element.getAsJsonObject("rotation").get("angle").getAsDouble();
                helper.assertTrue(LEGAL_ROTATION_ANGLES.contains(angle), where + " 的旋转角不合法: " + angle);
            }
            JsonObject faces = element.getAsJsonObject("faces");
            helper.assertTrue(faces != null && faces.size() > 0, where + " 没有面");
            for (Map.Entry<String, JsonElement> face : faces.entrySet()) {
                helper.assertTrue(Direction.byName(face.getKey()) != null, where + " 有不存在的面 " + face.getKey());
                String texture = face.getValue().getAsJsonObject().get("texture").getAsString();
                assertTextureResolves(helper, where + " 的 " + face.getKey() + " 面", textures, texture);
            }
        }
    }

    private static void assertTextureResolves(GameTestHelper helper, String where, Map<String, String> textures,
                                              String reference) {
        String resolved = reference;
        Set<String> seen = new HashSet<>();
        while (resolved != null && resolved.startsWith("#")) {
            helper.assertTrue(seen.add(resolved), where + " 的贴图变量成环: " + reference);
            resolved = textures.get(resolved.substring(1));
        }
        helper.assertTrue(resolved != null, where + " 的贴图变量 " + reference + " 没有定义 (客户端会显示成紫黑格)");
        helper.assertTrue(resolved.startsWith(NAMESPACE + "block/"), where + " 引用了别的命名空间的贴图 " + resolved
                + ": 跨 MOD 引用会随对方改名失效 (农夫乐事 1.3.x 就改过托架贴图名), 要用本 MOD 自己的拷贝");
        String png = "/assets/miningdim/textures/" + resolved.substring(NAMESPACE.length()) + ".png";
        helper.assertTrue(resourceExists(png), where + " 的贴图文件不存在: " + png);
    }

    private static boolean resourceExists(String path) {
        try (InputStream in = CookingStationAssetGameTests.class.getResourceAsStream(path)) {
            return in != null;
        } catch (IOException exception) {
            return false;
        }
    }
}
