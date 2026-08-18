package com.miningdim.power.compat.jei;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.PowerMachineRegistry;
import com.miningdim.power.machine.AirSeparatingRecipe;
import com.miningdim.power.machine.MetallurgicPurifyingRecipe;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** JEI 存在时由其插件扫描器发现；核心电力系统不依赖此类。 */
@JeiPlugin
public final class PowerJeiPlugin implements IModPlugin {

    private static final ResourceLocation PLUGIN_UID = new ResourceLocation(MiningConstants.MODID, "power");

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_UID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        IGuiHelper guiHelper = registration.getJeiHelpers().getGuiHelper();
        registration.addRecipeCategories(
                new MetallurgicPurifyingJeiCategory(guiHelper),
                new AirSeparatingJeiCategory(guiHelper));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            throw new IllegalStateException("JEI registered power recipes before a client level was available");
        }
        registration.addRecipes(PowerJeiRecipeTypes.METALLURGIC_PURIFYING,
                level.getRecipeManager().getAllRecipesFor(PowerMachineRegistry.METALLURGIC_PURIFYING_TYPE.get()));
        registration.addRecipes(PowerJeiRecipeTypes.AIR_SEPARATING,
                level.getRecipeManager().getAllRecipesFor(PowerMachineRegistry.AIR_SEPARATING_TYPE.get()));
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(PowerMachineRegistry.PURIFIER_ITEM.get(),
                PowerJeiRecipeTypes.METALLURGIC_PURIFYING);
        registration.addRecipeCatalyst(PowerMachineRegistry.AIR_SEPARATOR_ITEM.get(),
                PowerJeiRecipeTypes.AIR_SEPARATING);
    }
}
