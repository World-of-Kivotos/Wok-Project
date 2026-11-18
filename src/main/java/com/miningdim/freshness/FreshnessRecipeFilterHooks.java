package com.miningdim.freshness;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Set;

/** 在启动和数据包重载时过滤本轮资源的配方，随后由原版同步给玩家。 */
public final class FreshnessRecipeFilterHooks implements ResourceManagerReloadListener {

    private final RecipeManager manager;
    private final RegistryAccess registries;

    private FreshnessRecipeFilterHooks(RecipeManager manager, RegistryAccess registries) {
        this.manager = manager;
        this.registries = registries;
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new FreshnessRecipeFilterHooks(
                event.getServerResources().getRecipeManager(), event.getRegistryAccess()));
    }

    @Override
    public void onResourceManagerReload(ResourceManager resources) {
        // 初次加载资源时 SERVER 配置尚未就绪，由启动回调补做。
        if (FreshnessConfig.SPEC.isLoaded()) {
            applyConfiguredFilter(manager, registries);
        }
    }

    @Override
    public String getName() {
        return "miningdim_freshness_recipe_filter";
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        var server = event.getServer();
        applyConfiguredFilter(server.getRecipeManager(), server.registryAccess());
    }

    private static void applyConfiguredFilter(RecipeManager manager, RegistryAccess registries) {
        if (!FreshnessConfig.replacementEnabled() || !FreshnessConfig.RECIPE_FILTER_ENABLED.get()) {
            return;
        }
        Set<ResourceLocation> whitelist = FreshnessRecipeFilter.parseWhitelist(FreshnessConfig.RECIPE_FILTER_WHITELIST.get());
        Set<ResourceLocation> removed = FreshnessRecipeFilter.collectRemovals(
                manager.getAllRecipesFor(RecipeType.CRAFTING), whitelist, registries);
        if (!removed.isEmpty()) {
            manager.replaceRecipes(manager.getRecipes().stream()
                    .filter(recipe -> !removed.contains(recipe.getId())).toList());
            FreshnessRecipeFilter.logRemoved(removed);
        }
    }
}
