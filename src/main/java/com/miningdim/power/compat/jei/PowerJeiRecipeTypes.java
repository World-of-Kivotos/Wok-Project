package com.miningdim.power.compat.jei;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.machine.AirSeparatingRecipe;
import com.miningdim.power.machine.MetallurgicPurifyingRecipe;
import mezz.jei.api.recipe.RecipeType;

/** JEI 分类 UID 与配方 Java 类型的稳定对应关系。 */
public final class PowerJeiRecipeTypes {

    public static final RecipeType<MetallurgicPurifyingRecipe> METALLURGIC_PURIFYING = RecipeType.create(
            MiningConstants.MODID, "metallurgic_purifying", MetallurgicPurifyingRecipe.class);
    public static final RecipeType<AirSeparatingRecipe> AIR_SEPARATING = RecipeType.create(
            MiningConstants.MODID, "air_separating", AirSeparatingRecipe.class);

    private PowerJeiRecipeTypes() {
    }
}
