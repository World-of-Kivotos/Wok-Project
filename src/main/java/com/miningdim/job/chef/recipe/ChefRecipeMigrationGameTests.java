package com.miningdim.job.chef.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.chef.fryer.FryerRecipe;
import com.miningdim.job.chef.fryer.FryerRecipeRegistry;
import com.miningdim.job.chef.station.CookingStationBlock.Kind;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChefRecipeMigrationGameTests {
    private ChefRecipeMigrationGameTests() {}
    private static ResourceLocation id(String id) { return new ResourceLocation(id); }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static final Map<ResourceLocation, ChefRecipeMigration.Dish> DISHES = Map.of(
            id("minecraft:cookie"), new ChefRecipeMigration.Dish(2, 2, 0, 1, 0, false),
            id("minecraft:bread"), new ChefRecipeMigration.Dish(3, 3, 0, 0, 5, false));

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_migration")
    public static void realResourcesAreMigrated(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        var cake = manager.byKey(id("minecraft:cake")).orElseThrow();
        helper.assertTrue(cake instanceof FryerRecipe recipe && recipe.station() == Kind.BAKING_OVEN
                && recipe.result().is(Items.CAKE), "Vanilla cake is a real baking recipe");
        if (ModList.get().isLoaded("farmersdelight")) {
            var salad = manager.byKey(id("farmersdelight:fruit_salad")).orElseThrow();
            helper.assertTrue(salad instanceof FryerRecipe recipe && recipe.station() == Kind.PREP_COUNTER
                    && id("farmersdelight:fruit_salad").equals(ForgeRegistries.ITEMS.getKey(recipe.result().getItem())), "Farmer's Delight fruit salad is a real prep recipe");
            var rebuilt = manager.byKey(id("farmersdelight:apple_pie_from_slices")).orElseThrow();
            helper.assertTrue(rebuilt.getType() == net.minecraft.world.item.crafting.RecipeType.CRAFTING,
                    "Real apple pie reassembly stays out of the cooking and XP path");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_migration")
    public static void conditionalShapedInputsAndCountsSurvive(GameTestHelper helper) {
        ResourceLocation key = id("test:seven_inputs");
        JsonObject source = json("""
                {"type":"forge:conditional","conditions":[{"type":"forge:true"}],"recipes":[
                  {"conditions":[{"type":"forge:true"}],"recipe":{"type":"minecraft:crafting_shaped",
                    "pattern":["abc","def","gga"],"key":{
                      "a":{"item":"minecraft:wheat"},"b":{"item":"minecraft:sugar"},
                      "c":{"item":"minecraft:egg"},"d":{"item":"minecraft:apple"},
                      "e":{"item":"minecraft:carrot"},"f":{"item":"minecraft:potato"},
                      "g":[{"item":"minecraft:beetroot"},{"item":"minecraft:pumpkin"}]},
                    "result":{"item":"minecraft:cookie","count":3}}},
                  {"conditions":[{"type":"forge:false"}],"recipe":{"type":"minecraft:smelting",
                    "ingredient":{"item":"minecraft:wheat"},"result":"minecraft:bread","cookingtime":90}}
                ]}
                """);
        Map<ResourceLocation, JsonElement> migrated = ChefRecipeMigration.migrate(Map.of(key, source), DISHES);
        JsonObject wrapper = migrated.get(key).getAsJsonObject();
        JsonArray branches = wrapper.getAsJsonArray("recipes");
        JsonObject first = branches.get(0).getAsJsonObject().getAsJsonObject("recipe");
        JsonObject second = branches.get(1).getAsJsonObject().getAsJsonObject("recipe");
        helper.assertTrue(wrapper.get("conditions").equals(source.get("conditions"))
                && branches.get(0).getAsJsonObject().get("conditions").equals(source.getAsJsonArray("recipes").get(0).getAsJsonObject().get("conditions"))
                && second.get("type").getAsString().equals("miningdim:preparing") && second.get("cookTime").getAsInt() == 90,
                "Outer and branch conditions and second branch time survive");
        FryerRecipe recipe = FryerRecipeRegistry.recipeSerializer(Kind.BAKING_OVEN).fromJson(key, first);
        helper.assertTrue(recipe.station() == Kind.BAKING_OVEN && recipe.inputs().size() == 7
                && recipe.inputs().get(0).count() == 2 && recipe.inputs().get(6).count() == 2
                && recipe.inputs().get(6).ingredient().test(new ItemStack(Items.PUMPKIN))
                && recipe.result().getCount() == 3, "Seven distinct inputs retain repeated counts and alternatives");
        helper.assertTrue(ChefRecipeMigration.migrate(migrated, DISHES).equals(migrated), "Repeated reload conversion is idempotent");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_migration")
    public static void containersOutputsAndRemoval(GameTestHelper helper) {
        ResourceLocation key = id("test:multi");
        JsonObject source = json("""
                {"type":"farmersdelight:cooking","conditions":[{"type":"forge:true"}],
                 "ingredients":[{"item":"minecraft:apple"},{"item":"minecraft:apple"},{"tag":"forge:milk"}],
                 "container":{"item":"minecraft:bowl","count":2},
                 "result":{"item":"minecraft:bread","count":2,"nbt":"{CustomModelData:123}"},"cookingtime":400}
                """);
        ResourceLocation removed = id("casualness_delight:crafting_shaped/deep_frying_pan");
        ResourceLocation unrelated = id("test:other_pan");
        Map<ResourceLocation, JsonElement> result = ChefRecipeMigration.migrate(Map.of(key, source, removed, source, unrelated,
                json("{\"type\":\"minecraft:crafting_shapeless\",\"result\":{\"item\":\"minecraft:stick\"}}")), DISHES);
        JsonObject migrated = result.get(key).getAsJsonObject();
        FryerRecipe recipe = FryerRecipeRegistry.recipeSerializer(Kind.PREP_COUNTER).fromJson(key, migrated);
        helper.assertTrue(recipe.inputs().size() == 2 && recipe.inputs().get(0).count() == 2
                && recipe.inputs().get(0).ingredient().test(new ItemStack(Items.APPLE))
                && recipe.containerCount() == 2 && recipe.containerMatches(new ItemStack(Items.BOWL))
                && recipe.result().getCount() == 2 && recipe.result().getTag().getInt("CustomModelData") == 123
                && recipe.cookTime() == 400, "Input counts, container, output NBT/count and source time survive");
        helper.assertTrue(!result.containsKey(removed) && result.containsKey(unrelated)
                && migrated.get("conditions").equals(source.get("conditions")), "Only exact removed IDs disappear; conditions survive");
        boolean rejected = false;
        try { ChefRecipeMigration.migrate(Map.of(key, json("{\"type\":\"example:unsupported\",\"result\":{\"item\":\"minecraft:bread\"}}")), DISHES); }
        catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "Unsupported source for a target dish must fail reload");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_migration")
    public static void ownSlicesStayCraftingAcrossNamesShapesAndConditions(GameTestHelper helper) throws Exception {
        try (var reader = new InputStreamReader(ChefRecipeMigrationGameTests.class.getResourceAsStream(
                "/data/miningdim/chef/production-dishes.json"), StandardCharsets.UTF_8)) {
            var dishes = ChefRecipeMigration.readTable(JsonParser.parseReader(reader).getAsJsonObject());
            int checked = 0;
            for (var entry : dishes.entrySet()) {
                ResourceLocation slice = entry.getValue().reassemblyItem();
                if (slice == null) continue;
                int count = entry.getKey().equals(id("minecraft:cake")) ? 7 : 4;
                JsonObject shaped = json("{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"xx\",\"xx\"],\"key\":{\"x\":{\"item\":\"" + slice
                        + "\"}},\"result\":{\"item\":\"" + entry.getKey() + "\"}}");
                JsonObject shapeless = json("{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[],\"result\":{\"item\":\"" + entry.getKey() + "\"}}");
                for (int i = 0; i < count; i++) shapeless.getAsJsonArray("ingredients").add(json("{\"item\":\"" + slice + "\"}"));
                JsonObject wrapper = json("{\"type\":\"forge:conditional\",\"recipes\":[{\"conditions\":[{\"type\":\"forge:true\"}],\"recipe\":{}}]}");
                wrapper.getAsJsonArray("recipes").get(0).getAsJsonObject().add("recipe", shapeless);
                var sources = Map.<ResourceLocation, JsonElement>of(id("regression:renamed_shaped"), shaped, id("regression:renamed_conditional"), wrapper);
                helper.assertTrue(ChefRecipeMigration.migrate(sources, dishes).equals(sources),
                        "Own-slice reassembly keeps original crafting and conditions even with renamed IDs: " + entry.getKey());
                checked++;
            }
            helper.assertTrue(checked == 11, "All eleven audited whole/slice pairs are protected");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_migration")
    public static void freshInputsAndOtherDishesWithSlicesStillMigrate(GameTestHelper helper) {
        var pie = id("minecraft:pumpkin_pie");
        var dishes = Map.of(pie, new ChefRecipeMigration.Dish(4, 2, 2, 2, 0, false, id("farmersdelight:pumpkin_pie_slice")),
                id("farmersdelight:cod_roll"), new ChefRecipeMigration.Dish(3, 3, 1, 0, 3, false));
        JsonObject fresh = json("{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"minecraft:pumpkin\"},{\"item\":\"minecraft:sugar\"},{\"item\":\"minecraft:egg\"}],\"result\":{\"item\":\"minecraft:pumpkin_pie\"}}");
        JsonObject other = json("{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"farmersdelight:cod_slice\"},{\"item\":\"farmersdelight:cod_slice\"},{\"item\":\"farmersdelight:cooked_rice\"}],\"result\":{\"item\":\"farmersdelight:cod_roll\",\"count\":2}}");
        var migrated = ChefRecipeMigration.migrate(Map.of(id("regression:fresh"), fresh, id("regression:other_dish"), other), dishes);
        helper.assertTrue(migrated.get(id("regression:fresh")).getAsJsonObject().get("type").getAsString().equals("miningdim:baking")
                && migrated.get(id("regression:other_dish")).getAsJsonObject().get("type").getAsString().equals("miningdim:preparing"),
                "Fresh pie ingredients and slices used for a different dish remain productive recipes");
        helper.succeed();
    }
}
