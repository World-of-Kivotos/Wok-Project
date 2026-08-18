package com.miningdim.power.compat.jei;

import com.miningdim.power.PowerMachineConfig;
import com.miningdim.power.PowerMachineRegistry;
import com.miningdim.power.machine.MetallurgicPurifyingRecipe;
import com.miningdim.power.machine.PurifyingRuntime;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.crafting.Ingredient;

/** JEI 中的提纯工序展示；能耗数字每帧从服务端同步配置读取。 */
public final class MetallurgicPurifyingJeiCategory implements IRecipeCategory<MetallurgicPurifyingRecipe> {

    private final IDrawable icon;
    private final IDrawable arrow;
    private final IDrawable plus;

    public MetallurgicPurifyingJeiCategory(IGuiHelper guiHelper) {
        this.icon = guiHelper.createDrawableItemLike(PowerMachineRegistry.PURIFIER_ITEM.get());
        this.arrow = guiHelper.getRecipeArrow();
        this.plus = guiHelper.getRecipePlusSign();
    }

    @Override
    public RecipeType<MetallurgicPurifyingRecipe> getRecipeType() {
        return PowerJeiRecipeTypes.METALLURGIC_PURIFYING;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("block.miningdim.metallurgic_purifier");
    }

    @Override
    public int getWidth() {
        return PowerJeiTheme.WIDTH;
    }

    @Override
    public int getHeight() {
        return PowerJeiTheme.HEIGHT;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, MetallurgicPurifyingRecipe recipe, IFocusGroup focuses) {
        Ingredient base = recipe.getIngredients().get(0);
        Ingredient infusion = recipe.getIngredients().get(1);
        builder.addInputSlot(PowerJeiTheme.FIRST_INPUT_X, PowerJeiTheme.SLOT_Y)
                .setStandardSlotBackground()
                .addIngredients(base);
        builder.addInputSlot(PowerJeiTheme.SECOND_INPUT_X, PowerJeiTheme.SLOT_Y)
                .setStandardSlotBackground()
                .addIngredients(infusion);
        builder.addOutputSlot(PowerJeiTheme.OUTPUT_X, PowerJeiTheme.SLOT_Y)
                .setOutputSlotBackground()
                .addItemStack(recipe.result());
    }

    @Override
    public void draw(MetallurgicPurifyingRecipe recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics,
                     double mouseX, double mouseY) {
        PurifyingRuntime runtime = PowerMachineConfig.purifying(recipe.profile());
        long totalFe = Math.multiplyExact((long) runtime.durationTicks(), runtime.fePerTick());
        PowerJeiTheme.drawBackground(guiGraphics);
        PowerJeiTheme.drawPurifierFlow(guiGraphics, arrow, plus);
        PowerJeiTheme.drawRows(guiGraphics,
                Component.translatable("jei.miningdim.power.infusion", runtime.infusionUnits()),
                Component.translatable("jei.miningdim.power.duration", runtime.durationTicks()),
                Component.translatable("jei.miningdim.power.fe_per_tick", runtime.fePerTick()),
                Component.translatable("jei.miningdim.power.total_fe", totalFe));
    }
}
