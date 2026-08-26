package com.miningdim.job.munitions.gunsmith;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

/** 加载 data/&lt;namespace&gt;/gunsmith/components/*.json 的组件平衡规则。 */
public final class GunsmithComponentRuleLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/gunsmith");
    private static final Gson GSON = new GsonBuilder().create();
    private static final String DIRECTORY = "gunsmith/components";

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
            GunsmithPartVariant variant;
            try {
                variant = GunsmithPartVariant.byId(entry.getKey().getPath());
            } catch (IllegalArgumentException unknown) {
                throw new IllegalStateException("Unknown gunsmith component rule file: " + entry.getKey(), unknown);
            }
            if (variant == GunsmithPartVariant.BASE) {
                throw new IllegalStateException("Base gunsmith component rule cannot be overridden");
            }
            GunsmithComponentRule previous = loaded.put(variant, GunsmithComponentRule.fromJson(
                    GsonHelper.convertToJsonObject(entry.getValue(), "gunsmith component rule")));
            if (previous != null) {
                throw new IllegalStateException("Duplicate gunsmith component rule: " + variant.id());
            }
        }

        for (GunsmithPartVariant variant : GunsmithPartVariant.values()) {
            if (!loaded.containsKey(variant)) {
                throw new IllegalStateException("Missing gunsmith component datapack rule: " + variant.id());
            }
        }
        GunsmithComponentRules.install(loaded);
        LOGGER.info("[miningdim] loaded {} gunsmith component rules", loaded.size());
    }
}
