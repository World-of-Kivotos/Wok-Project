package com.miningdim.job.chef.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 在配方管理器反序列化之前迁移最终资源，保留资源包覆盖及 Forge 条件。 */
public final class ChefRecipeMigration {
    private static final ResourceLocation TABLE = new ResourceLocation("miningdim", "chef/production-dishes.json");
    private static final Set<ResourceLocation> REMOVED = Set.of(
            new ResourceLocation("casualness_delight", "crafting_shaped/deep_frying_pan"),
            new ResourceLocation("miningdim", "chef/fryer/fried_dumpling"),
            new ResourceLocation("miningdim", "chef/fryer/spring_roll"),
            new ResourceLocation("miningdim", "chef/fryer/plate_of_fried_dumpling"),
            new ResourceLocation("miningdim", "chef/fryer/spring_roll_medley"));

    private ChefRecipeMigration() {}

    record Dish(int star, int station, int motion, int flips, int beats, boolean drink, ResourceLocation reassemblyItem) {
        Dish(int star, int station, int motion, int flips, int beats, boolean drink) {
            this(star, station, motion, flips, beats, drink, null);
        }
    }

    public static Map<ResourceLocation, JsonElement> migrate(Map<ResourceLocation, JsonElement> recipes, ResourceManager resources) {
        try (Reader reader = resources.getResource(TABLE).orElseThrow(() ->
                new IllegalStateException("Missing chef production table " + TABLE)).openAsReader()) {
            Map<ResourceLocation, Dish> dishes = readTable(JsonParser.parseReader(reader).getAsJsonObject());
            Map<ResourceLocation, JsonElement> migrated = migrate(recipes, dishes);
            ChefDishCatalog.publish(dishes);
            return migrated;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read chef production table " + TABLE, e);
        }
    }

    static Map<ResourceLocation, Dish> readTable(JsonObject table) {
        if (GsonHelper.getAsInt(table, "schemaVersion") != 1) throw new IllegalArgumentException("Unsupported chef table schema");
        Map<ResourceLocation, Dish> dishes = new LinkedHashMap<>();
        for (JsonElement element : GsonHelper.getAsJsonArray(table, "dishes")) {
            JsonObject obj = element.getAsJsonObject();
            ResourceLocation id = new ResourceLocation(GsonHelper.getAsString(obj, "id"));
            Dish dish = new Dish(GsonHelper.getAsInt(obj, "star"), GsonHelper.getAsInt(obj, "station"),
                    GsonHelper.getAsInt(obj, "motion"), GsonHelper.getAsInt(obj, "flips"),
                    GsonHelper.getAsInt(obj, "beats"), GsonHelper.getAsBoolean(obj, "drink"),
                    obj.has("reassemblyItem") ? new ResourceLocation(GsonHelper.getAsString(obj, "reassemblyItem")) : null);
            if (dish.star < 1 || dish.star > 7 || dish.station < 0 || dish.station > 3 || dish.motion < 0 || dish.motion > 4
                    || (dish.station == 2 ? dish.flips < 1 || dish.flips > 8 : dish.flips != 0)
                    || (dish.station == 3 ? dish.beats < 3 || dish.beats > 8 : dish.beats != 0)
                    || (dish.drink && dish.station != 3)) throw new IllegalArgumentException("Invalid chef metadata " + id);
            if (dishes.putIfAbsent(id, dish) != null) throw new IllegalArgumentException("Duplicate chef metadata " + id);
        }
        return dishes;
    }

    static Map<ResourceLocation, JsonElement> migrate(Map<ResourceLocation, JsonElement> recipes, Map<ResourceLocation, Dish> dishes) {
        Map<ResourceLocation, JsonElement> result = new LinkedHashMap<>();
        for (var entry : recipes.entrySet()) {
            if (!REMOVED.contains(entry.getKey())) result.put(entry.getKey(), convert(entry.getKey(), entry.getValue(), dishes));
        }
        return result;
    }

    private static JsonElement convert(ResourceLocation id, JsonElement value, Map<ResourceLocation, Dish> dishes) {
        if (!value.isJsonObject()) return value;
        JsonObject source = value.getAsJsonObject();
        String type = GsonHelper.getAsString(source, "type", "");
        if (type.equals("forge:conditional")) {
            JsonObject copy = source.deepCopy();
            for (JsonElement element : GsonHelper.getAsJsonArray(copy, "recipes")) {
                JsonObject branch = element.getAsJsonObject();
                branch.add("recipe", convert(id, branch.get("recipe"), dishes));
            }
            return copy;
        }
        ResourceLocation output = outputId(source.get("result"), dishes);
        if (output == null || type.equals("miningdim:frying") || type.equals("miningdim:baking") || type.equals("miningdim:preparing")) return value;
        if (source.get("result").isJsonArray()) throw new IllegalArgumentException("Unsupported multiple-output chef recipe " + id);
        Dish dish = dishes.get(output);
        // Native cooking and serving remain FD-owned; only the explicit mastery session is intercepted.
        if (dish.station == 0) return value;
        LinkedHashMap<JsonElement, Integer> inputs = new LinkedHashMap<>();
        switch (type) {
            case "minecraft:crafting_shaped" -> {
                JsonObject keys = GsonHelper.getAsJsonObject(source, "key");
                JsonArray pattern = GsonHelper.getAsJsonArray(source, "pattern");
                for (JsonElement row : pattern) for (char key : row.getAsString().toCharArray()) {
                    if (key != ' ') {
                        JsonElement ingredient = keys.get(String.valueOf(key));
                        if (ingredient == null) throw new IllegalArgumentException("Unknown shaped key in " + id + ": " + key);
                        add(inputs, ingredient);
                    }
                }
            }
            case "minecraft:crafting_shapeless", "farmersdelight:cooking" -> {
                for (JsonElement ingredient : GsonHelper.getAsJsonArray(source, "ingredients")) add(inputs, ingredient);
            }
            case "minecraft:smelting", "minecraft:smoking", "minecraft:campfire_cooking", "casualness_delight:deep_frying" ->
                    add(inputs, source.get("ingredient"));
            default -> throw new IllegalArgumentException("Unsupported chef source recipe " + id + " type " + type + " output " + output);
        }
        // 同款切片没有新增食材；保留原制作路径，不能通过掌勺循环获得经验、手熟或成就。
        if (dish.reassemblyItem != null && !inputs.isEmpty()
                && inputs.keySet().stream().allMatch(ingredient -> containsItemOption(ingredient, dish.reassemblyItem))) return value;
        JsonObject container = null;
        if (source.has("container")) {
            JsonObject sourceContainer = GsonHelper.getAsJsonObject(source, "container").deepCopy();
            int count = GsonHelper.getAsInt(sourceContainer, "count", 1);
            sourceContainer.remove("count");
            container = input(sourceContainer, count);
        } else if (type.equals("minecraft:crafting_shaped") || type.equals("minecraft:crafting_shapeless")) {
            JsonObject bowl = new JsonObject(); bowl.addProperty("item", "minecraft:bowl");
            Integer count = inputs.remove(bowl);
            if (count != null) container = input(bowl, count);
        }
        if (inputs.isEmpty() || inputs.size() > 9) throw new IllegalArgumentException("Invalid chef input count in " + id);
        JsonArray ingredients = new JsonArray();
        inputs.forEach((ingredient, count) -> ingredients.add(input(ingredient, count)));
        JsonObject result = new JsonObject();
        result.addProperty("type", switch (dish.station) { case 1 -> "miningdim:frying"; case 2 -> "miningdim:baking"; case 3 -> "miningdim:preparing"; default -> throw new IllegalStateException(); });
        if (source.has("conditions")) result.add("conditions", source.get("conditions").deepCopy());
        result.add("ingredients", ingredients);
        if (container != null) result.add("container", container);
        JsonElement rawResult = source.get("result");
        if (rawResult.isJsonPrimitive()) { JsonObject stack = new JsonObject(); stack.add("item", rawResult.deepCopy()); result.add("result", stack); }
        else result.add("result", rawResult.deepCopy());
        int defaultTime = type.equals("minecraft:smoking") || type.equals("minecraft:campfire_cooking")
                || type.equals("casualness_delight:deep_frying") ? 100 : 200;
        result.addProperty("cookTime", GsonHelper.getAsInt(source, "cookingtime", defaultTime));
        result.addProperty("star", dish.star); result.addProperty("motion", dish.motion);
        result.addProperty("flips", dish.flips); result.addProperty("beats", dish.beats); result.addProperty("drink", dish.drink);
        return result;
    }

    private static void add(Map<JsonElement, Integer> inputs, JsonElement ingredient) {
        if (ingredient == null || ingredient.isJsonNull()) throw new IllegalArgumentException("Missing chef ingredient");
        inputs.merge(ingredient.deepCopy(), 1, Integer::sum);
    }

    private static boolean containsItemOption(JsonElement ingredient, ResourceLocation item) {
        if (ingredient.isJsonArray()) {
            for (JsonElement option : ingredient.getAsJsonArray()) if (containsItemOption(option, item)) return true;
            return false;
        }
        return ingredient.isJsonObject() && ingredient.getAsJsonObject().has("item")
                && item.toString().equals(GsonHelper.getAsString(ingredient.getAsJsonObject(), "item"));
    }

    private static JsonObject input(JsonElement ingredient, int count) {
        if (count < 1 || count > 64) throw new IllegalArgumentException("Invalid chef ingredient count " + count);
        JsonObject result = new JsonObject(); result.add("ingredient", ingredient.deepCopy()); result.addProperty("count", count); return result;
    }

    private static ResourceLocation outputId(JsonElement result, Map<ResourceLocation, Dish> dishes) {
        if (result == null || result.isJsonNull()) return null;
        if (result.isJsonArray()) {
            for (JsonElement element : result.getAsJsonArray()) {
                ResourceLocation found = outputId(element, dishes);
                if (found != null) return found;
            }
            return null;
        }
        String id = result.isJsonPrimitive() && result.getAsJsonPrimitive().isString() ? result.getAsString()
                : result.isJsonObject() && result.getAsJsonObject().has("item") ? GsonHelper.getAsString(result.getAsJsonObject(), "item") : null;
        if (id == null) return null;
        ResourceLocation output = ResourceLocation.tryParse(id);
        return output != null && dishes.containsKey(output) ? output : null;
    }
}
