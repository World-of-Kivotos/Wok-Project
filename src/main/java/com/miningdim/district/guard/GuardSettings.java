package com.miningdim.district.guard;

import com.miningdim.district.DistrictConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * 守卫的配置 (miningdim-district.toml 的 [district.guards] 与 [district.createBan], 设计文档 22.1、22.20), 开服读一次, 不可变。
 * GameTest 每轮清掉 serverconfig, 用例直接构造。
 *
 * @param crossPlot       地块边界守卫 (22.9) 的急停开关
 * @param createMachinery 机械动力的机器改不了自管区方块 (22.6) 的急停开关; 玩家的放置禁令与 denyUse 不受它影响
 * @param personalClaims  个人圈地限制 (22.20) 的急停开关: 关着时 F1、F2 一律放行, 已有个人领地的清单与报告照常
 * @param namespaces      算作机械动力的命名空间
 * @param denyBlocks      没有方块实体、但按机器拦的方块
 * @param allowBlocks     有方块实体、但按装饰放行的方块
 * @param denyUse         绑定前就在区内的机器: 非 OP 能不能右键操作 (22.8)
 * @param problems        名单里被忽略的项 (格式不对、命名空间不对、方块不存在), 给 /district status 看
 */
public record GuardSettings(boolean crossPlot, boolean createMachinery, boolean personalClaims,
                            Set<String> namespaces, Set<ResourceLocation> denyBlocks, Set<ResourceLocation> allowBlocks,
                            boolean denyUse, List<String> problems) {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");

    /**
     * 默认算作机械动力的命名空间 (22.4): {@code create}, 以及整合包的机械动力重打包登记机器方块用的 {@code ignored_void}
     * (2026-09-30 服主定, 23.11)。{@code ignored_void} 不是 mod id, 名单里写在它下面的方块不核对存在与否。
     */
    public static final List<String> DEFAULT_NAMESPACES = List.of("create", "ignored_void");

    /** 没有方块实体、但有机械行为的 4 个 (22.4): 装置上的犁、活塞杆、控制铁轨、装置用的红石触点。 */
    public static final List<String> DEFAULT_DENY_BLOCKS = List.of("create:mechanical_plough",
            "create:piston_extension_pole", "create:controller_rail", "create:redstone_contact");

    /** 有方块实体、但只是装饰的 9 个 (22.4): 仿制台阶与面板、五种推拉门、告示板、剪贴板。 */
    public static final List<String> DEFAULT_ALLOW_BLOCKS = List.of("create:copycat_step", "create:copycat_panel",
            "create:andesite_door", "create:brass_door", "create:copper_door", "create:train_door",
            "create:framed_glass_door", "create:placard", "create:clipboard");

    public GuardSettings {
        namespaces = Set.copyOf(namespaces);
        denyBlocks = Set.copyOf(denyBlocks);
        allowBlocks = Set.copyOf(allowBlocks);
        problems = List.copyOf(problems);
    }

    /** 代码默认值 (三个开关都开, 默认名单)。方块存在与否按当前注册表核对。 */
    public static GuardSettings defaults() {
        return parse(true, true, DEFAULT_NAMESPACES, DEFAULT_DENY_BLOCKS, DEFAULT_ALLOW_BLOCKS, true,
                GuardSettings::namespaceLoaded, GuardSettings::blockRegistered);
    }

    /** 从已加载的 miningdim-district.toml 读 (只在 ServerStarting、功能打开时调)。读不到时按默认值, 记 WARN。 */
    public static GuardSettings fromConfig() {
        try {
            GuardSettings settings = parse(DistrictConfig.GUARD_CROSS_PLOT.get(),
                    DistrictConfig.GUARD_CREATE_MACHINERY.get(), DistrictConfig.CREATE_NAMESPACES.get(),
                    DistrictConfig.CREATE_DENY_BLOCKS.get(), DistrictConfig.CREATE_ALLOW_BLOCKS.get(),
                    DistrictConfig.CREATE_DENY_USE.get(), GuardSettings::namespaceLoaded,
                    GuardSettings::blockRegistered)
                    .withPersonalClaims(DistrictConfig.GUARD_PERSONAL_CLAIMS.get());
            for (String problem : settings.problems()) {
                LOGGER.warn("[miningdim] miningdim-district.toml [district.createBan]: {} (ignored)", problem);
            }
            return settings;
        } catch (IllegalStateException notLoaded) {
            LOGGER.warn("[miningdim] miningdim-district.toml guard sections are not loaded; using the defaults");
            return defaults();
        }
    }

    /**
     * 解析两张名单。写错的项记进 problems 并忽略: 格式不对; 命名空间不在 namespaces 里 (那样写了也不起作用); 命名空间的
     * mod 装着、方块却不存在。命名空间的 mod 没装时不核对方块存在与否 (例如没装机械动力的开发运行时), 名单照常保留。
     * 个人圈地限制的开关取默认值 (开), 要别的值用 {@link #withPersonalClaims}。
     */
    public static GuardSettings parse(boolean crossPlot, boolean createMachinery, List<? extends String> namespaces,
                                      List<? extends String> deny, List<? extends String> allow, boolean denyUse,
                                      Predicate<String> namespaceLoaded, Predicate<ResourceLocation> blockExists) {
        List<String> problems = new ArrayList<>();
        Set<String> spaces = new LinkedHashSet<>();
        for (String raw : namespaces) {
            String space = raw == null ? "" : raw.trim();
            if (space.isEmpty() || !ResourceLocation.isValidNamespace(space)) {
                problems.add("namespaces: \"" + raw + "\" 不是合法的命名空间");
            } else {
                spaces.add(space);
            }
        }
        Set<ResourceLocation> denied = readList("denyBlocks", deny, spaces, namespaceLoaded, blockExists, problems);
        Set<ResourceLocation> allowed = readList("allowBlocks", allow, spaces, namespaceLoaded, blockExists,
                problems);
        return new GuardSettings(crossPlot, createMachinery, true, spaces, denied, allowed, denyUse, problems);
    }

    private static Set<ResourceLocation> readList(String name, List<? extends String> entries, Set<String> spaces,
                                                  Predicate<String> namespaceLoaded,
                                                  Predicate<ResourceLocation> blockExists, List<String> problems) {
        Set<ResourceLocation> out = new LinkedHashSet<>();
        for (String raw : entries) {
            String text = raw == null ? "" : raw.trim();
            ResourceLocation id = text.indexOf(':') > 0 ? ResourceLocation.tryParse(text) : null;
            if (id == null) {
                problems.add(name + ": \"" + raw + "\" 格式不对 (应为 命名空间:方块名)");
                continue;
            }
            if (!spaces.contains(id.getNamespace())) {
                problems.add(name + ": " + id + " 的命名空间不在 namespaces 里, 写了也不起作用");
                continue;
            }
            if (namespaceLoaded.test(id.getNamespace()) && !blockExists.test(id)) {
                problems.add(name + ": 方块 " + id + " 不存在");
                continue;
            }
            out.add(id);
        }
        return out;
    }

    /** 命名空间按字母排好、逗号分隔 (开服日志与 /district status 用; 集合本身不保序)。 */
    public String namespacesText() {
        return namespaces.isEmpty() ? "-" : String.join(", ", new TreeSet<>(namespaces));
    }

    /** 换一个地块边界开关 (测试)。 */
    public GuardSettings withCrossPlot(boolean value) {
        return new GuardSettings(value, createMachinery, personalClaims, namespaces, denyBlocks, allowBlocks, denyUse,
                problems);
    }

    /** 换一个机器拦截开关 (测试)。 */
    public GuardSettings withCreateMachinery(boolean value) {
        return new GuardSettings(crossPlot, value, personalClaims, namespaces, denyBlocks, allowBlocks, denyUse,
                problems);
    }

    /** 换一个个人圈地限制的急停开关 (开服读配置, 测试)。 */
    public GuardSettings withPersonalClaims(boolean value) {
        return new GuardSettings(crossPlot, createMachinery, value, namespaces, denyBlocks, allowBlocks, denyUse,
                problems);
    }

    /** 换一个 denyUse (测试)。 */
    public GuardSettings withDenyUse(boolean value) {
        return new GuardSettings(crossPlot, createMachinery, personalClaims, namespaces, denyBlocks, allowBlocks, value,
                problems);
    }

    /** minecraft 与 forge 恒在; 其余看 ModList (还没建好时按没装)。 */
    public static boolean namespaceLoaded(String namespace) {
        if ("minecraft".equals(namespace) || "forge".equals(namespace)) {
            return true;
        }
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(namespace);
    }

    public static boolean blockRegistered(ResourceLocation id) {
        return ForgeRegistries.BLOCKS.containsKey(id);
    }
}
