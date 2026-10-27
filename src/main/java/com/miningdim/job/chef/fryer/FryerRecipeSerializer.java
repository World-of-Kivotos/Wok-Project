package com.miningdim.job.chef.fryer;

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
    @Override public FryerRecipe fromJson(ResourceLocation id, JsonObject json) {
        JsonArray array = GsonHelper.getAsJsonArray(json, "ingredients");
        if (array.isEmpty() || array.size() > 6) throw new IllegalArgumentException("Fryer needs 1..6 ingredients: " + id);
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
                GsonHelper.getAsInt(json, "star"), GsonHelper.getAsInt(json, "motion", 4), GsonHelper.getAsInt(json, "cookTime", 200));
    }

    @Override public FryerRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 1 || size > 6) throw new IllegalArgumentException("Invalid fryer ingredient list");
        List<FryerRecipe.Input> inputs = new ArrayList<>();
        for (int i = 0; i < size; i++) inputs.add(new FryerRecipe.Input(Ingredient.fromNetwork(buf), buf.readVarInt()));
        Ingredient container = Ingredient.fromNetwork(buf);
        int count = buf.readVarInt(); ItemStack result = buf.readItem();
        return new FryerRecipe(id, inputs, container, count, result, buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    @Override public void toNetwork(FriendlyByteBuf buf, FryerRecipe recipe) {
        buf.writeVarInt(recipe.inputs().size());
        for (FryerRecipe.Input input : recipe.inputs()) { input.ingredient().toNetwork(buf); buf.writeVarInt(input.count()); }
        recipe.containerIngredient().toNetwork(buf); buf.writeVarInt(recipe.containerCount()); buf.writeItem(recipe.result());
        buf.writeVarInt(recipe.star()); buf.writeVarInt(recipe.motion()); buf.writeVarInt(recipe.cookTime());
    }
}
