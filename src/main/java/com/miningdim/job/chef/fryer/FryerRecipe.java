package com.miningdim.job.chef.fryer;

import net.minecraft.core.NonNullList;
import com.miningdim.job.chef.station.CookingStationBlock.Kind;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

/** 单份无序配方；最大流避免重叠 tag 或重复原料被贪心匹配吞掉。 */
public final class FryerRecipe implements Recipe<SimpleContainer> {
    public static final int INPUT_SLOTS = 9, CONTAINER_SLOT = 9, SEASONING_SLOT = 10,
            OUTPUT_SLOT = 11, INVENTORY_SLOTS = 12;
    public record Input(Ingredient ingredient, int count) {
        public Input {
            if (ingredient.isEmpty() || count < 1 || count > 64) throw new IllegalArgumentException("Invalid fryer ingredient quantity");
        }
    }

    private final ResourceLocation id;
    private final List<Input> inputs;
    private final Ingredient container;
    private final int containerCount;
    private final ItemStack result;
    private final int star, motion, cookTime;
    private final Kind station;
    private final int flips, beats;
    private final boolean drink;

    public FryerRecipe(ResourceLocation id, List<Input> inputs, Ingredient container, int containerCount,
                       ItemStack result, int star, int motion, int cookTime) {
        this(id, inputs, container, containerCount, result, star, motion, cookTime, Kind.DEEP_FRYER, 0, 0, false);
    }

    public FryerRecipe(ResourceLocation id, List<Input> inputs, Ingredient container, int containerCount,
                       ItemStack result, int star, int motion, int cookTime, Kind station,
                       int flips, int beats, boolean drink) {
        if (inputs.isEmpty() || inputs.size() > INPUT_SLOTS || result.isEmpty() || result.getCount() > result.getMaxStackSize()
                || star < 1 || star > 7 || motion < 0 || motion > 4 || cookTime < 1 || cookTime > 72000
                || containerCount < 0 || containerCount > 64 || (container.isEmpty() != (containerCount == 0))
                || station == null || (station == Kind.BAKING_OVEN ? flips < 1 || flips > 8 : flips != 0)
                || (station == Kind.PREP_COUNTER ? beats < 3 || beats > 8 : beats != 0)
                || (drink && station != Kind.PREP_COUNTER)) {
            throw new IllegalArgumentException("Invalid fryer recipe " + id);
        }
        this.id = id; this.inputs = List.copyOf(inputs); this.container = container;
        this.containerCount = containerCount; this.result = result.copy();
        this.star = star; this.motion = motion; this.cookTime = cookTime;
        this.station = station; this.flips = flips; this.beats = beats; this.drink = drink;
    }

    /** 返回各输入槽实际消耗数；不匹配返回 null，不修改容器。 */
    public int[] allocation(SimpleContainer inv) {
        if (inv.getContainerSize() < CONTAINER_SLOT + 1 || (containerCount > 0 && (!container.test(inv.getItem(CONTAINER_SLOT))
                || inv.getItem(CONTAINER_SLOT).getCount() < containerCount))) return null;
        int n = inputs.size(), inputBase = INPUT_SLOTS + 1, sink = inputBase + n, vertices = sink + 1, total = 0;
        int[][] residual = new int[vertices][vertices];
        for (int slot = 0; slot < INPUT_SLOTS; slot++) {
            ItemStack stack = inv.getItem(slot);
            residual[0][slot + 1] = stack.getCount();
            boolean accepted = stack.isEmpty();
            for (int i = 0; i < n; i++) if (inputs.get(i).ingredient().test(stack)) {
                residual[slot + 1][inputBase + i] = stack.getCount(); accepted = true;
            }
            if (!accepted) return null;
        }
        for (int i = 0; i < n; i++) {
            residual[inputBase + i][sink] = inputs.get(i).count(); total += inputs.get(i).count();
        }
        int flow = 0;
        while (flow < total) {
            int[] parent = new int[vertices]; Arrays.fill(parent, -1); parent[0] = 0;
            ArrayDeque<Integer> queue = new ArrayDeque<>(); queue.add(0);
            while (!queue.isEmpty() && parent[sink] < 0) {
                int u = queue.remove();
                for (int v = 1; v < vertices; v++) if (parent[v] < 0 && residual[u][v] > 0) {
                    parent[v] = u; queue.add(v);
                }
            }
            if (parent[sink] < 0) return null;
            int amount = total - flow;
            for (int v = sink; v != 0; v = parent[v]) amount = Math.min(amount, residual[parent[v]][v]);
            for (int v = sink; v != 0; v = parent[v]) {
                int u = parent[v]; residual[u][v] -= amount; residual[v][u] += amount;
            }
            flow += amount;
        }
        int[] taken = new int[CONTAINER_SLOT + 1];
        for (int slot = 0; slot < INPUT_SLOTS; slot++) taken[slot] = inv.getItem(slot).getCount() - residual[0][slot + 1];
        taken[CONTAINER_SLOT] = containerCount;
        return taken;
    }

    public List<Input> inputs() { return inputs; }
    public Ingredient containerIngredient() { return container; }
    public int containerCount() { return containerCount; }
    public boolean containerMatches(ItemStack stack) { return containerCount > 0 && container.test(stack); }
    public ItemStack result() { return result.copy(); }
    public int star() { return star; }
    public int motion() { return motion; }
    public int cookTime() { return cookTime; }
    public Kind station() { return station; }
    public int flips() { return flips; }
    public int beats() { return beats; }
    public boolean drink() { return drink; }
    @Override public boolean matches(SimpleContainer inv, Level level) { return allocation(inv) != null; }
    @Override public ItemStack assemble(SimpleContainer inv, RegistryAccess access) { return result(); }
    @Override public boolean canCraftInDimensions(int width, int height) { return width * height >= inputs.size(); }
    @Override public ItemStack getResultItem(RegistryAccess access) { return result(); }
    @Override public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        for (Input input : inputs) list.add(input.ingredient());
        return list;
    }
    @Override public ResourceLocation getId() { return id; }
    @Override public RecipeSerializer<?> getSerializer() { return FryerRecipeRegistry.recipeSerializer(station); }
    @Override public RecipeType<?> getType() { return FryerRecipeRegistry.recipeType(station); }
}
