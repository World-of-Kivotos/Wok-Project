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

/** 无物料输入的空分配方；菜单模式选择决定使用哪一条数据包结果。 */
public final class AirSeparatingRecipe implements Recipe<Container> {

    private final ResourceLocation id;
    private final AirSeparationMode mode;
    private final ItemStack result;

    public AirSeparatingRecipe(ResourceLocation id, AirSeparationMode mode, ItemStack result) {
        this.id = Objects.requireNonNull(id, "id");
        this.mode = Objects.requireNonNull(mode, "mode");
        if (result.isEmpty()) {
            throw new IllegalArgumentException("air separating recipe result must not be empty: " + id);
        }
        this.result = result.copy();
    }

    public AirSeparationMode mode() {
        return mode;
    }

    public ItemStack result() {
        return result.copy();
    }

    @Override
    public boolean matches(Container container, Level level) {
        return container.isEmpty();
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
        return NonNullList.create();
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return PowerMachineRegistry.AIR_SEPARATING_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return PowerMachineRegistry.AIR_SEPARATING_TYPE.get();
    }

    public static final class Serializer implements RecipeSerializer<AirSeparatingRecipe> {

        @Override
        public AirSeparatingRecipe fromJson(ResourceLocation recipeId, JsonObject json) {
            AirSeparationMode mode = AirSeparationMode.byId(GsonHelper.getAsString(json, "mode"));
            ItemStack result = ShapedRecipe.itemStackFromJson(GsonHelper.getAsJsonObject(json, "result"));
            return new AirSeparatingRecipe(recipeId, mode, result);
        }

        @Override
        public AirSeparatingRecipe fromNetwork(ResourceLocation recipeId, FriendlyByteBuf buffer) {
            AirSeparationMode mode = AirSeparationMode.byId(buffer.readUtf());
            ItemStack result = buffer.readItem();
            return new AirSeparatingRecipe(recipeId, mode, result);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buffer, AirSeparatingRecipe recipe) {
            buffer.writeUtf(recipe.mode.id());
            buffer.writeItem(recipe.result);
        }
    }
}
