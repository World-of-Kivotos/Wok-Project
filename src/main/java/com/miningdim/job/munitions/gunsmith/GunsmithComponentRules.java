package com.miningdim.job.munitions.gunsmith;

import com.miningdim.core.MiningConstants;
import net.minecraft.util.GsonHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 枪匠组件平衡规则的运行期快照。每次 datapack 重载都以完整不可变快照原子替换。
 */
public final class GunsmithComponentRules {

    /**
     * 内置默认值直接解析 jar 里的 data/miningdim/gunsmith/components/*.json, 不在 Java 里另抄一份平衡表。
     * 两份真源没有任何用例比对时必然漂移: 只改 Java 时全库 GameTest 仍全绿(断言跑在 datapack 快照上),
     * 玩家却会在收到登录同步前的 tooltip 与装配预览里看到另一套数值。
     */
    private static final Map<GunsmithPartVariant, GunsmithComponentRule> BUILT_IN = readBuiltIn();

    private static volatile Map<GunsmithPartVariant, GunsmithComponentRule> current = BUILT_IN;

    private GunsmithComponentRules() {
    }

    public static GunsmithComponentRule get(GunsmithPartVariant variant) {
        GunsmithComponentRule rule = current.get(Objects.requireNonNull(variant, "variant"));
        if (rule == null) {
            throw new IllegalStateException("Gunsmith component rule is not loaded: " + variant.id());
        }
        return rule;
    }

    public static Map<GunsmithPartVariant, GunsmithComponentRule> snapshot() {
        return current;
    }

    public static void install(Map<GunsmithPartVariant, GunsmithComponentRule> rules) {
        Objects.requireNonNull(rules, "rules");
        EnumMap<GunsmithPartVariant, GunsmithComponentRule> checked = new EnumMap<>(GunsmithPartVariant.class);
        for (GunsmithPartVariant variant : GunsmithPartVariant.values()) {
            GunsmithComponentRule rule = rules.get(variant);
            if (rule == null) {
                throw new IllegalArgumentException("Missing gunsmith component rule: " + variant.id());
            }
            checked.put(variant, rule);
        }
        if (rules.size() != checked.size()) {
            throw new IllegalArgumentException("Gunsmith component rules contain unknown entries");
        }
        current = Map.copyOf(checked);
    }

    /** jar 内置规则; 也是 datapack 覆盖文件不可用时的回退来源。 */
    public static Map<GunsmithPartVariant, GunsmithComponentRule> defaults() {
        return BUILT_IN;
    }

    private static Map<GunsmithPartVariant, GunsmithComponentRule> readBuiltIn() {
        EnumMap<GunsmithPartVariant, GunsmithComponentRule> builtIn =
                new EnumMap<>(GunsmithPartVariant.class);
        // BASE 是"无特殊组件"的单位规则, 没有也不允许有对应 JSON。
        builtIn.put(GunsmithPartVariant.BASE, GunsmithComponentRule.identity());
        for (GunsmithPartVariant variant : GunsmithPartVariant.values()) {
            if (variant != GunsmithPartVariant.BASE) {
                builtIn.put(variant, readBuiltIn(variant));
            }
        }
        return Map.copyOf(builtIn);
    }

    private static GunsmithComponentRule readBuiltIn(GunsmithPartVariant variant) {
        String path = "/data/" + MiningConstants.MODID + "/gunsmith/components/" + variant.id() + ".json";
        try (InputStream stream = GunsmithComponentRules.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing built-in gunsmith component rule: " + path);
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return GunsmithComponentRule.fromJson(GsonHelper.parse(reader));
            }
        } catch (IOException failure) {
            // 内置资源读不出来属打包事故, 静态初始化阶段无法抛受检异常, 原样带因由上抛。
            throw new IllegalStateException("Failed to read built-in gunsmith component rule: " + path, failure);
        }
    }
}
