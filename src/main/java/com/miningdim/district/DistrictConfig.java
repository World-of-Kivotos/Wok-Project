package com.miningdim.district;

import com.miningdim.district.guard.GuardSettings;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * 自管区服务端配置 (miningdim-district.toml, 设计文档 20.9、22.1 与 22.20)。由 {@link DistrictSystem} 走标准的 registerConfig 注册。
 *
 * <p>只在 ServerStarting 读一次, 改了要重启: 功能开关牵涉绑定服务、选网关、开服备份与对账, 热切换的中间状态不值得
 * 处理。GameTest 每轮都清掉 miningdim-*.toml, 所以在 GameTest 服务端上恒为 OFF; 需要生产路径的用例自己翻
 * {@link DistrictFeature#forceForTest}。
 *
 * <p>{@code [district.guards]} 与 {@code [district.createBan]} 两节 (22.1) 只在功能打开时读, 读出来的是不可变的
 * {@link GuardSettings}; 名单里写错的项记 WARN 并忽略, /district status 列出来。
 */
public final class DistrictConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLED;

    public static final ForgeConfigSpec.BooleanValue GUARD_CROSS_PLOT;
    public static final ForgeConfigSpec.BooleanValue GUARD_CREATE_MACHINERY;
    public static final ForgeConfigSpec.BooleanValue GUARD_PERSONAL_CLAIMS;

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> CREATE_NAMESPACES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> CREATE_DENY_BLOCKS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> CREATE_ALLOW_BLOCKS;
    public static final ForgeConfigSpec.BooleanValue CREATE_DENY_USE;

    private DistrictConfig() {
    }

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("district");
        ENABLED = builder.comment(
                        "Master switch of the self-governed district feature (wok-district). false: the module stays "
                                + "off (no services bound, Flan is never touched, the tablet shows no entry).",
                        "true: goes live when Flan 1.20.1-1.11.16 is installed and passes the startup self-check; "
                                + "otherwise runs degraded (see the server log and /district status).",
                        "Restart the server after changing it.")
                .define("enabled", false);

        builder.push("guards");
        GUARD_CROSS_PLOT = builder.comment(
                        "Plot boundary guards inside a district: pistons, fluids, dispensers and droppers, falling "
                                + "blocks, sponges and lava ignition cannot cross from one plot (or the public area) "
                                + "into another (docs/District_Backend_Design.md 22.9).",
                        "Emergency switch; restart the server after changing it.")
                .define("crossPlot", true);
        GUARD_CREATE_MACHINERY = builder.comment(
                        "Create (mechanical) machinery cannot change blocks inside a district: drills, saws, "
                                + "deployers, harvesters, ploughs, rollers, contraptions, schematicannons, hose "
                                + "pulleys and open-ended pipes (22.6).",
                        "The placement ban for players (22.5) and denyUse (22.8) are not affected by this switch.",
                        "Emergency switch; restart the server after changing it.")
                .define("createMachinery", true);
        GUARD_PERSONAL_CLAIMS = builder.comment(
                        "Personal claim limit (22.20): inside a district and within 8 blocks around it, players (OPs "
                                + "excepted) cannot create personal Flan claims or resize one into that zone.",
                        "false is only an emergency switch: every claim goes through again; /district personalclaims "
                                + "and the reports keep working.",
                        "Emergency switch; restart the server after changing it.")
                .define("personalClaims", true);
        builder.pop();

        builder.push("createBan");
        CREATE_NAMESPACES = builder.comment(
                        "Block namespaces that count as Create. Add the namespace of a Create add-on here once it is "
                                + "installed.",
                        "Default: create, plus ignored_void (the namespace the modpack's Create build registers its "
                                + "machine blocks under). An existing file keeps the list it already has; add "
                                + "ignored_void by hand there (docs/District_Backend_Design.md 22.4).")
                .defineListAllowEmpty(List.of("namespaces"), () -> GuardSettings.DEFAULT_NAMESPACES,
                        DistrictConfig::isString);
        CREATE_DENY_BLOCKS = builder.comment(
                        "Blocks without a block entity that still behave like machines; placed like machines are "
                                + "(banned inside districts and within 8 blocks around them).")
                .defineListAllowEmpty(List.of("denyBlocks"), () -> GuardSettings.DEFAULT_DENY_BLOCKS,
                        DistrictConfig::isString);
        CREATE_ALLOW_BLOCKS = builder.comment(
                        "Blocks with a block entity that are only decorative; placeable like other decorative "
                                + "Create blocks.")
                .defineListAllowEmpty(List.of("allowBlocks"), () -> GuardSettings.DEFAULT_ALLOW_BLOCKS,
                        DistrictConfig::isString);
        CREATE_DENY_USE = builder.comment(
                        "Create machines that were already inside a district when it was bound: non-OP players "
                                + "cannot right-click them (22.8). Sneak + Create wrench (to take them down) and "
                                + "breaking by hand stay allowed.")
                .define("denyUse", true);
        builder.pop();

        builder.pop();
        SPEC = builder.build();
    }

    /** 名单元素只要求是字符串: 格式不对的项由 {@link GuardSettings#parse} 报出来并忽略, 不让 Forge 把整张名单重置成默认值。 */
    private static boolean isString(Object value) {
        return value instanceof String;
    }
}
