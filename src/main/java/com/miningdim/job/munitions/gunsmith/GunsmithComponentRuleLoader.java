package com.miningdim.job.munitions.gunsmith;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;

/** 加载 data/miningdim/gunsmith/components/*.json 的组件平衡规则。 */
public final class GunsmithComponentRuleLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/gunsmith");
    private static final Gson GSON = new GsonBuilder().create();
    private static final String DIRECTORY = "gunsmith/components";

    /**
     * 文件名到组件的严格映射。刻意不复用 GunsmithPartVariant.byId: 那里保留了 basic /
     * gehenna_high_speed_gas / mk_ax_a_receiver 三个存量物品迁移用的历史别名, 一旦让它们也能当文件名,
     * 同一个组件就会有两个"看起来合法"的规则文件互相覆盖。
     */
    private static final Map<String, GunsmithPartVariant> BY_FILE_NAME = Arrays
            .stream(GunsmithPartVariant.values())
            .collect(Collectors.toUnmodifiableMap(GunsmithPartVariant::id, variant -> variant));

    public GunsmithComponentRuleLoader() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> parsed,
                         ResourceManager manager, ProfilerFiller profiler) {
        EnumMap<GunsmithPartVariant, GunsmithComponentRule> loaded =
                new EnumMap<>(GunsmithPartVariant.class);
        loaded.put(GunsmithPartVariant.BASE, GunsmithComponentRule.identity());

        for (Map.Entry<ResourceLocation, JsonElement> entry : parsed.entrySet()) {
            ResourceLocation file = entry.getKey();
            // vanilla 扫描的是所有 namespace 下的同名目录, 而覆盖内置规则本来就要求沿用 miningdim 的 id。
            // 第三方 datapack 在自己 namespace 下多放一个文件不该让服务端整包加载失败。
            if (!MiningConstants.MODID.equals(file.getNamespace())) {
                LOGGER.warn("[miningdim] ignoring gunsmith component rule outside the {} namespace: {}",
                        MiningConstants.MODID, file);
                continue;
            }
            GunsmithPartVariant variant = BY_FILE_NAME.get(file.getPath());
            if (variant == null) {
                LOGGER.error("[miningdim] ignoring unknown gunsmith component rule file {}:"
                        + " the file name must equal a registered variant id", file);
                continue;
            }
            if (variant == GunsmithPartVariant.BASE) {
                LOGGER.error("[miningdim] ignoring {}: the base gunsmith component rule is the"
                        + " identity rule and cannot be overridden", file);
                continue;
            }
            loaded.put(variant, GunsmithComponentRule.fromJson(
                    GsonHelper.convertToJsonObject(entry.getValue(), "gunsmith component rule")));
        }

        Map<GunsmithPartVariant, GunsmithComponentRule> builtIn = GunsmithComponentRules.defaults();
        for (GunsmithPartVariant variant : GunsmithPartVariant.values()) {
            if (loaded.containsKey(variant)) {
                continue;
            }
            // ResourceManager 对同一个 id 只返回优先级最高的那一份: datapack 一旦覆盖了某个文件而其 JSON
            // 有语法错误, vanilla 的 scanDirectory 只会 LOGGER.error 后丢弃该 id, jar 内置副本也拿不回来。
            // 此处退回内置规则并指名真因 —— 直接抛异常会让开服期整包加载失败(专用服直接退出),
            // 报错还写成"缺少数据包规则", 与真因(解析失败)完全对不上。
            LOGGER.error("[miningdim] gunsmith component rule {} is missing from the datapack snapshot"
                    + " (most likely a JSON parse failure in an override file, see the preceding"
                    + " \"Couldn't parse data file\" line); falling back to the built-in rule", variant.id());
            loaded.put(variant, builtIn.get(variant));
        }
        GunsmithComponentRules.install(loaded);
        LOGGER.info("[miningdim] loaded {} gunsmith component rules", loaded.size());
    }
}
