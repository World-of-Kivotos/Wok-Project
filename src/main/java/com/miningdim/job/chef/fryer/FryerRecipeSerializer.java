package com.miningdim.job.chef.fryer;

import com.miningdim.job.chef.station.CookingStationBlock.Kind;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.ArrayList;
import java.util.List;

public final class FryerRecipeSerializer implements RecipeSerializer<FryerRecipe> {
    private final Kind station;

    public FryerRecipeSerializer() { this(Kind.DEEP_FRYER); }
    public FryerRecipeSerializer(Kind station) { this.station = java.util.Objects.requireNonNull(station); }

    @Override public FryerRecipe fromJson(ResourceLocation id, JsonObject json) {
        JsonArray array = GsonHelper.getAsJsonArray(json, "ingredients");
        if (array.isEmpty() || array.size() > FryerRecipe.INPUT_SLOTS) throw new IllegalArgumentException("Cooking station needs 1..9 ingredients: " + id);
        List<FryerRecipe.Input> inputs = new ArrayList<>();
        for (var value : array) {
            JsonObject input = value.getAsJsonObject();
            inputs.add(new FryerRecipe.Input(Ingredient.fromJson(input.get("ingredient")), GsonHelper.getAsInt(input, "count", 1)));
        }
        Ingredient container = Ingredient.EMPTY;
        int count = 0;
        if (json.has("container")) {
            JsonObject input = GsonHelper.getAsJsonObject(json, "container");
            container = Ingredient.fromJson(input.get("ingredient")); count = GsonHelper.getAsInt(input, "count", 1);
        }
        return new FryerRecipe(id, inputs, container, count,
                ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json, "result")),
                GsonHelper.getAsInt(json, "star"), GsonHelper.getAsInt(json, "motion", station == Kind.DEEP_FRYER ? 4 : 0),
                GsonHelper.getAsInt(json, "cookTime", 200), station,
                GsonHelper.getAsInt(json, "flips", station == Kind.BAKING_OVEN ? 1 : 0),
                GsonHelper.getAsInt(json, "beats", station == Kind.PREP_COUNTER ? 5 : 0),
                GsonHelper.getAsBoolean(json, "drink", false));
    }

    @Override public FryerRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 1 || size > FryerRecipe.INPUT_SLOTS) throw new IllegalArgumentException("Invalid cooking ingredient list");
        List<FryerRecipe.Input> inputs = new ArrayList<>();
        for (int i = 0; i < size; i++) inputs.add(new FryerRecipe.Input(Ingredient.fromNetwork(buf), buf.readVarInt()));
        Ingredient container = Ingredient.fromNetwork(buf);
        int count = buf.readVarInt(); ItemStack result = buf.readItem();
        return new FryerRecipe(id, inputs, container, count, result, buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                station, buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
    }

    @Override public void toNetwork(FriendlyByteBuf buf, FryerRecipe recipe) {
        if (recipe.station() != station) throw new IllegalArgumentException("Recipe belongs to another cooking station: " + recipe.getId());
        buf.writeVarInt(recipe.inputs().size());
        for (FryerRecipe.Input input : recipe.inputs()) { input.ingredient().toNetwork(buf); buf.writeVarInt(input.count()); }
        recipe.containerIngredient().toNetwork(buf); buf.writeVarInt(recipe.containerCount()); buf.writeItem(recipe.result());
        buf.writeVarInt(recipe.star()); buf.writeVarInt(recipe.motion()); buf.writeVarInt(recipe.cookTime());
        buf.writeVarInt(recipe.flips()); buf.writeVarInt(recipe.beats()); buf.writeBoolean(recipe.drink());
    }
}
