package com.miningdim.job.brewer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.brewer.station.BrewRecipes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.UUID;

/**
 * 酿酒师面板的 job.brewer.state WebUiAction (九种酒的永久层数 + 配方表 + 月光词条)。全只读。
 *
 * 前端契约 (webui/src/lib/types.ts): job.brewer.state -&gt;
 * {level,maxLayersPerType,brews[9],moonshinePerks,recipes[9],millisPerVintageYear}。
 *
 * moonshinePerks 刻意提到顶层而不是挂在每行酒上: {@link BrewBuffStore#moonshinePerks(UUID)} 是 per-player
 * 一组, 没有 WineType 维度; 挂进每行酒会在面板上渲染出"伏特加带着月光词条"这种维度错误。
 *
 * 不发 per-配方的陈酿天数: Java 侧根本没有这个量 —— 陈酿是酒窖箱按现实挂钟持续累积年份, 九种酒共用同一套
 * 时钟。改发 {@link BrewerConstants#MILLIS_PER_VINTAGE_YEAR}, 由面板讲清楚陈酿是挂钟制。
 */
public final class BrewerWebUiActions {

    private static final Gson GSON = new Gson();

    private BrewerWebUiActions() {
    }

    /** 把 job.brewer.state 注册进派发器 (由 {@link BrewerSystem#register} 调用一次)。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("job.brewer.state", STATE);
    }

    static final WebUiAction STATE = (sender, payload) -> {
        UUID playerId = sender.getUUID();
        BrewBuffStore store = BrewBuffStore.get(sender.server.overworld());

        JsonObject result = new JsonObject();
        result.addProperty("level", JobServices.jobService().level(sender, JobId.BREWER));
        result.addProperty("maxLayersPerType", BrewerConstants.MAX_LAYERS_PER_TYPE);

        JsonArray brews = new JsonArray();
        JsonArray recipes = new JsonArray();
        for (WineType type : WineType.values()) {
            Item wine = BrewerItems.itemFor(type);
            JsonObject brew = new JsonObject();
            brew.addProperty("wineId", type.id());
            brew.addProperty("itemId", itemId(wine));
            brew.addProperty("descriptionId", wine.getDescriptionId());
            brew.addProperty("permanentStacks", store.layers(playerId, type));
            brews.add(brew);

            JsonArray inputs = new JsonArray();
            for (BrewRecipes.Ingredient ingredient : BrewRecipes.recipeFor(type)) {
                JsonObject input = new JsonObject();
                input.addProperty("itemId", itemId(ingredient.item()));
                input.addProperty("descriptionId", ingredient.item().getDescriptionId());
                input.addProperty("count", ingredient.count());
                inputs.add(input);
            }
            JsonObject recipe = new JsonObject();
            // 不发独立 recipeId: 配方与酒类型是同一个 WineType 枚举, 发两个 id 迟早分叉。
            recipe.addProperty("wineId", type.id());
            recipe.add("inputs", inputs);
            recipes.add(recipe);
        }
        result.add("brews", brews);
        result.add("recipes", recipes);

        JsonArray perks = new JsonArray();
        for (MoonshinePerk perk : store.moonshinePerks(playerId)) {
            JsonObject row = new JsonObject();
            row.addProperty("perkId", perk.id());
            row.addProperty("labelKey", "brewer.moonshine." + perk.id());
            perks.add(row);
        }
        // 未满 5 层月光时是空数组 (词条要满层才一次性固化), 前端据此说明"月光未满层"。
        result.add("moonshinePerks", perks);

        result.addProperty("millisPerVintageYear", BrewerConstants.MILLIS_PER_VINTAGE_YEAR);
        return GSON.toJson(result);
    };

    private static String itemId(Item item) {
        return ForgeRegistries.ITEMS.getKey(item).toString();
    }
}
