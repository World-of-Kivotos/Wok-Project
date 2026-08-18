package com.miningdim.core;

import com.google.gson.JsonObject;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * mods.toml 依赖声明 / affix_setting datapack 兼容性断言 (Full_Repo_Audit_2026-08 F061/F062)。
 *
 * F061: mods.toml 新增的 tacz/champions 两个 [[dependencies.miningdim]] 块 (mandatory/ordering/side/
 * versionRange) 只有在被删除或改错时才会在装了对应 mod 的 dev GameTest 服务端表现出来 (FML 依赖校验发生在
 * 数据包加载之前), 因此这里直接用 ModList/IModInfo 读取运行期解析后的 ModVersion 结构断言字段值, 而不是
 * 等一次真实的加载失败。
 *
 * F062: data/miningdim/affix_setting/ 下的 35 个 JSON 把 type 写成 champions:&lt;自研词条名&gt;, 但自研
 * 词条自 b7736a4 起已不再注册进 Champions 的 AffixRegistry (com.miningdim.champion 包内零
 * top.theillusivec4 import), 这些文件恒为死数据: 仍会被 Champions 的 AffixDataLoader 解析并随
 * SPacketSyncAffixSetting 全量发给登录客户端, 但无任何一处消费。相邻的 data/champions/affix_setting/
 * 16 个 enable:false 覆盖文件是禁用原版词条的唯一活链路, 与前者只差一个命名空间, 必须分开断言防止被
 * 误删/误改。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ModDependencyDeclarationGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "compat_declarations";
    private static final Set<String> JADE_CONFIG_TRANSLATION_KEYS = Set.of(
            "config.jade.plugin_miningdim.power_cable",
            "config.jade.plugin_miningdim.power_generator",
            "config.jade.plugin_miningdim.power_purifier",
            "config.jade.plugin_miningdim.power_air_separator",
            "config.jade.plugin_miningdim.power_low_temperature_controller");

    /** F062 反例锚点: champions:affix_setting 下应恰好是这 16 个原版词条禁用覆盖文件, 一个不多一个不少。 */
    private static final Set<String> EXPECTED_CHAMPIONS_AFFIX_PATHS = new TreeSet<>(List.of(
            "affix_setting/adaptable.json",
            "affix_setting/arctic.json",
            "affix_setting/dampening.json",
            "affix_setting/desecrating.json",
            "affix_setting/enkindling.json",
            "affix_setting/hasty.json",
            "affix_setting/infested.json",
            "affix_setting/knocking.json",
            "affix_setting/lively.json",
            "affix_setting/magnetic.json",
            "affix_setting/molten.json",
            "affix_setting/paralyzing.json",
            "affix_setting/plagued.json",
            "affix_setting/reflective.json",
            "affix_setting/shielding.json",
            "affix_setting/wounding.json"));

    // ============================================================
    // F061: mods.toml 里 tacz/champions 两个 [[dependencies.miningdim]] 块的字段与版本区间
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void modsTomlDeclaresTaczOptionalDependencyWithCorrectRange(GameTestHelper helper) {
        // 反例: 把 mods.toml 第 59-64 行的 tacz 块整段删掉, findDependency 返回空, 此处必挂。
        IModInfo.ModVersion tacz = findDependency("tacz");

        helper.assertTrue(!tacz.isMandatory(),
                "mods.toml 的 tacz 依赖必须 mandatory=false (两个 mod 都不在的服务端不应拒绝启动), 实际读到 mandatory=true");
        helper.assertTrue(tacz.getOrdering() == IModInfo.Ordering.AFTER,
                "mods.toml 的 tacz 依赖必须 ordering=AFTER (FMLCommonSetupEvent 里读 TACZ 静态注册表前置需先初始化), "
                        + "实际读到 " + tacz.getOrdering());
        helper.assertTrue(tacz.getSide() == IModInfo.DependencySide.BOTH,
                "mods.toml 的 tacz 依赖必须 side=BOTH, 实际读到 " + tacz.getSide());

        // 反例: 把下界改回 [1.1.8-hotfix,1.1.9) (Maven 未知限定符 "hotfix" 排在 release 之后,
        // 会把普通 1.1.8 版本挡在区间外), containsVersion("1.1.8") 必挂。
        VersionRange range = tacz.getVersionRange();
        helper.assertTrue(range.containsVersion(new DefaultArtifactVersion("1.1.8")),
                "tacz versionRange " + range + " 应包含普通版本 1.1.8, 实际不包含 (下界被 hotfix 限定符误挡)");
        helper.assertTrue(range.containsVersion(new DefaultArtifactVersion("1.1.8-hotfix")),
                "tacz versionRange " + range + " 应包含 libs/ 下实际部署的 1.1.8-hotfix 构建, 实际不包含");
        helper.assertTrue(!range.containsVersion(new DefaultArtifactVersion("1.1.7")),
                "tacz versionRange " + range + " 不应包含区间外的旧版本 1.1.7, 实际包含 (下界写错)");
        helper.assertTrue(!range.containsVersion(new DefaultArtifactVersion("1.1.9")),
                "tacz versionRange " + range + " 上界必须开区间排除 1.1.9, 实际包含 (上界写错)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void modsTomlDeclaresChampionsOptionalDependencyWithCorrectRange(GameTestHelper helper) {
        // 反例: 把 mods.toml 第 71-76 行的 champions 块整段删掉, findDependency 返回空, 此处必挂。
        IModInfo.ModVersion champions = findDependency("champions");

        helper.assertTrue(!champions.isMandatory(),
                "mods.toml 的 champions 依赖必须 mandatory=false, 实际读到 mandatory=true");
        helper.assertTrue(champions.getOrdering() == IModInfo.Ordering.AFTER,
                "mods.toml 的 champions 依赖必须 ordering=AFTER (Champions 侧注册表需先于本 mod 初始化), "
                        + "实际读到 " + champions.getOrdering());
        // side=BOTH 而非 SERVER: 整合包单人/局域网集成服务端 Dist 为 CLIENT, 写 SERVER 会让
        // DependencySide.isCorrectSide 在客户端跳过校验, 反例 = 把 side 改成 SERVER, 此处必挂。
        helper.assertTrue(champions.getSide() == IModInfo.DependencySide.BOTH,
                "mods.toml 的 champions 依赖必须 side=BOTH, 实际读到 " + champions.getSide());

        VersionRange range = champions.getVersionRange();
        helper.assertTrue(range.containsVersion(new DefaultArtifactVersion("1.20.1-2.1.10.2")),
                "champions versionRange " + range + " 应包含 libs/ 下实际部署的 1.20.1-2.1.10.2, 实际不包含");
        helper.assertTrue(!range.containsVersion(new DefaultArtifactVersion("1.20.1-2.1.11")),
                "champions versionRange " + range + " 上界必须开区间排除 1.20.1-2.1.11, 实际包含 (上界写错)");
        helper.assertTrue(!range.containsVersion(new DefaultArtifactVersion("1.20.1-2.1.10.1")),
                "champions versionRange " + range + " 不应包含区间外的旧版本 1.20.1-2.1.10.1, 实际包含 (下界写错)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void modsTomlDeclaresJeiAndJadeOptionalCompatibilityDependencies(GameTestHelper helper) {
        // JEI: 必须是区间而非精确锁定。曾锁死在编译锚 [15.20.0.135], 玩家装的 15.20.0.129 直接把客户端
        // 挡在加载阶段外 (2026-08-19 真机崩溃)。mandatory=false 只管"没装", 装了仍强制校验范围。
        IModInfo.ModVersion jei = findDependency("jei");
        helper.assertTrue(!jei.isMandatory(),
                "mods.toml 的 jei 依赖必须 mandatory=false, 实际读到 mandatory=true");
        helper.assertTrue(jei.getOrdering() == IModInfo.Ordering.AFTER,
                "mods.toml 的 jei 依赖必须 ordering=AFTER, 实际读到 " + jei.getOrdering());
        helper.assertTrue(jei.getSide() == IModInfo.DependencySide.CLIENT,
                "mods.toml 的 jei 依赖 side 应为 CLIENT, 实际读到 " + jei.getSide());

        VersionRange jeiRange = jei.getVersionRange();
        helper.assertTrue(jeiRange.containsVersion(new DefaultArtifactVersion("15.20.0.129")),
                "jei versionRange " + jeiRange + " 必须包含玩家在装的 15.20.0.129 (锁死编译锚曾导致客户端起不来)");
        helper.assertTrue(jeiRange.containsVersion(new DefaultArtifactVersion("15.20.0.135")),
                "jei versionRange " + jeiRange + " 必须包含编译锚 15.20.0.135, 实际不包含");
        helper.assertTrue(!jeiRange.containsVersion(new DefaultArtifactVersion("16.0.0.0")),
                "jei versionRange " + jeiRange + " 上界必须开区间排除 16.x (跨主版本 API 无保证), 实际包含");

        // Jade 反之必须保持精确锁定: 版本串 11.13.2+forge 带 + 号, Maven 解析不出数字段, 整串退化为
        // qualifier, 任何数字区间都不会命中 —— 这里若被"顺手"改成区间, 装了 Jade 的客户端会全数被挡。
        assertExactOptionalCompatibilityDependency(helper, findDependency("jade"), "jade", "11.13.2+forge",
                IModInfo.DependencySide.BOTH);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void jadeProvidersHaveRequiredConfigTranslations(GameTestHelper helper) throws IOException {
        for (String language : List.of("en_us", "zh_cn")) {
            JsonObject translations = readAssetJson("assets/" + MiningConstants.MODID + "/lang/" + language + ".json");
            for (String key : JADE_CONFIG_TRANSLATION_KEYS) {
                helper.assertTrue(translations.has(key) && translations.get(key).isJsonPrimitive()
                                && !translations.get(key).getAsString().isBlank(),
                        language + " 缺少 Jade 组件配置翻译或翻译值为空: " + key);
            }
        }
        helper.succeed();
    }

    private static void assertExactOptionalCompatibilityDependency(GameTestHelper helper, IModInfo.ModVersion dependency,
                                                                    String modId, String version,
                                                                    IModInfo.DependencySide expectedSide) {
        helper.assertTrue(!dependency.isMandatory(),
                "mods.toml 的 " + modId + " 依赖必须 mandatory=false, 实际读到 mandatory=true");
        helper.assertTrue(dependency.getOrdering() == IModInfo.Ordering.AFTER,
                "mods.toml 的 " + modId + " 依赖必须 ordering=AFTER, 实际读到 " + dependency.getOrdering());
        helper.assertTrue(dependency.getSide() == expectedSide,
                "mods.toml 的 " + modId + " 依赖 side 应为 " + expectedSide + ", 实际读到 " + dependency.getSide());

        VersionRange range = dependency.getVersionRange();
        DefaultArtifactVersion exactVersion = new DefaultArtifactVersion(version);
        helper.assertTrue(range.getRestrictions().size() == 1
                        && range.getRestrictions().get(0).isLowerBoundInclusive()
                        && range.getRestrictions().get(0).isUpperBoundInclusive()
                        && exactVersion.equals(range.getRestrictions().get(0).getLowerBound())
                        && exactVersion.equals(range.getRestrictions().get(0).getUpperBound()),
                "mods.toml 的 " + modId + " versionRange 必须精确锁定 " + version + ", 实际读到 " + range);
    }

    /** 反例已在各调用点标注: 找不到指定 modId 的依赖声明时直接判该 GameTest 失败, 而不是返回 null 让 NPE 掩盖真实原因。 */
    private static IModInfo.ModVersion findDependency(String modId) {
        Optional<? extends net.minecraftforge.fml.ModContainer> container =
                ModList.get().getModContainerById(MiningConstants.MODID);
        if (container.isEmpty()) {
            throw new AssertionError("ModList 找不到 " + MiningConstants.MODID + " 自身的 ModContainer");
        }
        for (IModInfo.ModVersion dep : container.get().getModInfo().getDependencies()) {
            if (dep.getModId().equals(modId)) {
                return dep;
            }
        }
        throw new AssertionError("mods.toml 的 [[dependencies." + MiningConstants.MODID + "]] 里找不到 modId=\""
                + modId + "\" 的依赖声明");
    }

    private static JsonObject readAssetJson(String path) throws IOException {
        InputStream stream = ModDependencyDeclarationGameTests.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) {
            throw new AssertionError("运行时 classpath 找不到资源: " + path);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return GsonHelper.parse(reader);
        }
    }

    // ============================================================
    // F062: data/miningdim/affix_setting 死数据必须被清空, champions 侧禁用覆盖必须原样保留
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void noDeadMiningdimAffixSettings(GameTestHelper helper) {
        Map<ResourceLocation, Resource> found = listAffixSettingResources(helper);

        List<ResourceLocation> deadMiningdimEntries = new ArrayList<>();
        for (ResourceLocation rl : found.keySet()) {
            if (rl.getNamespace().equals(MiningConstants.MODID)) {
                deadMiningdimEntries.add(rl);
            }
        }

        // 反例: 把 data/miningdim/affix_setting/ 下任意一个 JSON 还原回去, deadMiningdimEntries 非空, 此处必挂。
        helper.assertTrue(deadMiningdimEntries.isEmpty(),
                "data/miningdim/affix_setting/ 下不应残留任何 miningdim: 命名空间的声明 (F062: 自研词条系统"
                        + "自 b7736a4 起已不再注册进 Champions, 这些 JSON 恒为死数据, 只会被 Champions 的"
                        + "AffixDataLoader 白解析一遍并随握手包发给客户端), 实际残留 " + deadMiningdimEntries.size()
                        + " 个: " + deadMiningdimEntries);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void championsAffixOverridesStillBanVanillaSixteen(GameTestHelper helper) throws IOException {
        Map<ResourceLocation, Resource> found = listAffixSettingResources(helper);

        Map<String, Resource> championsEntries = new TreeMap<>();
        for (Map.Entry<ResourceLocation, Resource> entry : found.entrySet()) {
            if (entry.getKey().getNamespace().equals("champions")) {
                championsEntries.put(entry.getKey().getPath(), entry.getValue());
            }
        }

        // 数量与集合都断: 少一个 (如误删 arctic.json) 或多一个都必须挂, 不能只看 size。
        helper.assertTrue(championsEntries.size() == EXPECTED_CHAMPIONS_AFFIX_PATHS.size(),
                "champions:affix_setting 应恰好 16 个原版词条禁用覆盖文件, 实际 " + championsEntries.size()
                        + " 个: " + championsEntries.keySet());
        helper.assertTrue(championsEntries.keySet().equals(EXPECTED_CHAMPIONS_AFFIX_PATHS),
                "champions:affix_setting 文件名集合应恰好等于 16 个原版词条名单, 实际 = " + championsEntries.keySet()
                        + ", 期望 = " + EXPECTED_CHAMPIONS_AFFIX_PATHS);

        // 逐个打开资源, 断言 enable 字段确为 false (反例: 把 arctic.json 的 enable 改成 true, 此处必挂)。
        for (Map.Entry<String, Resource> entry : championsEntries.entrySet()) {
            try (BufferedReader reader = entry.getValue().openAsReader()) {
                JsonObject obj = GsonHelper.parse(reader);
                boolean enabled = GsonHelper.getAsBoolean(obj, "enable");
                helper.assertTrue(!enabled,
                        "champions:" + entry.getKey() + " 必须 enable=false (禁用原版词条的唯一活链路), 实际读到 enable=true");
            }
        }
        helper.succeed();
    }

    private static Map<ResourceLocation, Resource> listAffixSettingResources(GameTestHelper helper) {
        ResourceManager rm = helper.getLevel().getServer().getResourceManager();
        return rm.listResources("affix_setting", rl -> rl.getPath().endsWith(".json"));
    }
}
