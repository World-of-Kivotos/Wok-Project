package com.miningdim.power.compat.jei;

import com.miningdim.power.PowerMachineConfig;
import com.miningdim.power.PowerMachineRegistry;
import com.miningdim.power.machine.AirSeparatingRecipe;
import com.miningdim.power.machine.AirSeparatingRuntime;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** JEI 中的空分工序展示；能耗数字每帧从服务端同步配置读取。 */
public final class AirSeparatingJeiCategory implements IRecipeCategory<AirSeparatingRecipe> {

    private final IDrawable icon;
    private final IDrawable arrow;

    public AirSeparatingJeiCategory(IGuiHelper guiHelper) {
        this.icon = guiHelper.createDrawableItemLike(PowerMachineRegistry.AIR_SEPARATOR_ITEM.get());
        this.arrow = guiHelper.getRecipeArrow();
    }

    @Override
    public RecipeType<AirSeparatingRecipe> getRecipeType() {
        return PowerJeiRecipeTypes.AIR_SEPARATING;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("block.miningdim.air_separation_unit");
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
    public void setRecipe(IRecipeLayoutBuilder builder, AirSeparatingRecipe recipe, IFocusGroup focuses) {
        builder.addOutputSlot(PowerJeiTheme.OUTPUT_X, PowerJeiTheme.SLOT_Y)
                .setOutputSlotBackground()
                .addItemStack(recipe.result());
    }

    @Override
    public void draw(AirSeparatingRecipe recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics,
                     double mouseX, double mouseY) {
        AirSeparatingRuntime runtime = PowerMachineConfig.airSeparating(recipe.mode());
        long totalFe = Math.multiplyExact((long) runtime.durationTicks(), runtime.fePerTick());
        PowerJeiTheme.drawBackground(guiGraphics);
        PowerJeiTheme.drawAirFlow(guiGraphics, arrow);
        PowerJeiTheme.drawRows(guiGraphics,
                Component.translatable("jei.miningdim.power.mode",
                        Component.translatable("screen.miningdim.air_separation_unit.mode." + recipe.mode().id())),
                Component.translatable("jei.miningdim.power.duration", runtime.durationTicks()),
                Component.translatable("jei.miningdim.power.fe_per_tick", runtime.fePerTick()),
                Component.translatable("jei.miningdim.power.total_fe", totalFe));
    }
}
