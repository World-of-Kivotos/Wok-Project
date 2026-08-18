package com.miningdim.power.machine;

import com.google.gson.JsonObject;
import com.miningdim.power.PowerMachineRegistry;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;

import java.util.Objects;

/** 数据包定义物料事实、配置定义运行成本的冶金灌注配方。 */
public final class MetallurgicPurifyingRecipe implements Recipe<Container> {

    private final ResourceLocation id;
    private final PurifyingProfile profile;
    private final Ingredient base;
    private final ResourceLocation infusionType;
    private final Ingredient infusion;
    private final ItemStack result;

    public MetallurgicPurifyingRecipe(ResourceLocation id, PurifyingProfile profile, Ingredient base,
                                      ResourceLocation infusionType, Ingredient infusion, ItemStack result) {
        this.id = Objects.requireNonNull(id, "id");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.base = requireNonEmptyIngredient(base, "base");
        this.infusionType = Objects.requireNonNull(infusionType, "infusionType");
        this.infusion = requireNonEmptyIngredient(infusion, "infusion");
        if (result.isEmpty()) {
            throw new IllegalArgumentException("purifying recipe result must not be empty: " + id);
        }
        this.result = result.copy();
    }

    public PurifyingProfile profile() {
        return profile;
    }

    public ResourceLocation infusionType() {
        return infusionType;
    }

    public boolean matchesBase(ItemStack stack) {
        return base.test(stack);
    }

    public boolean matchesInfusion(ItemStack stack) {
        return infusion.test(stack);
    }

    public ItemStack result() {
        return result.copy();
    }

    @Override
    public boolean matches(Container container, Level level) {
        return container.getContainerSize() > 0 && matchesBase(container.getItem(0));
    }

    @Override
    public ItemStack assemble(Container container, RegistryAccess registryAccess) {
        return result();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return false;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess registryAccess) {
        return result();
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        return NonNullList.of(Ingredient.EMPTY, base, infusion);
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return PowerMachineRegistry.METALLURGIC_PURIFYING_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return PowerMachineRegistry.METALLURGIC_PURIFYING_TYPE.get();
    }

    private static Ingredient requireNonEmptyIngredient(Ingredient ingredient, String name) {
        Ingredient resolved = Objects.requireNonNull(ingredient, name);
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("purifying recipe " + name + " ingredient must not be empty");
        }
        return resolved;
    }

    public static final class Serializer implements RecipeSerializer<MetallurgicPurifyingRecipe> {

        @Override
        public MetallurgicPurifyingRecipe fromJson(ResourceLocation recipeId, JsonObject json) {
            PurifyingProfile profile = PurifyingProfile.byId(GsonHelper.getAsString(json, "profile"));
            Ingredient base = Ingredient.fromJson(GsonHelper.getAsJsonObject(json, "base"), false);
            JsonObject infusionJson = GsonHelper.getAsJsonObject(json, "infusion");
            ResourceLocation infusionType = new ResourceLocation(GsonHelper.getAsString(infusionJson, "type"));
            Ingredient infusion = Ingredient.fromJson(GsonHelper.getAsJsonObject(infusionJson, "ingredient"), false);
            ItemStack result = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json, "result"));
            return new MetallurgicPurifyingRecipe(recipeId, profile, base, infusionType, infusion, result);
        }

        @Override
        public MetallurgicPurifyingRecipe fromNetwork(ResourceLocation recipeId, FriendlyByteBuf buffer) {
            PurifyingProfile profile = PurifyingProfile.byId(buffer.readUtf());
            Ingredient base = Ingredient.fromNetwork(buffer);
            ResourceLocation infusionType = buffer.readResourceLocation();
            Ingredient infusion = Ingredient.fromNetwork(buffer);
            ItemStack result = buffer.readItem();
            return new MetallurgicPurifyingRecipe(recipeId, profile, base, infusionType, infusion, result);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buffer, MetallurgicPurifyingRecipe recipe) {
            buffer.writeUtf(recipe.profile.id());
            recipe.base.toNetwork(buffer);
            buffer.writeResourceLocation(recipe.infusionType);
            recipe.infusion.toNetwork(buffer);
            buffer.writeItem(recipe.result);
        }
    }
}
