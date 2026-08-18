package com.miningdim.power.data;

import com.miningdim.core.MiningConstants;
import net.minecraft.data.PackOutput;
import net.minecraftforge.client.model.generators.ItemModelProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

/** 终局控制器与超导加工中间物均使用独立原版扁平物品模型。 */
final class PowerEndgameItemModelProvider extends ItemModelProvider {

    PowerEndgameItemModelProvider(PackOutput output, ExistingFileHelper existingFiles) {
        super(output, MiningConstants.MODID, existingFiles);
    }

    @Override
    protected void registerModels() {
        generatedItem("low_temperature_controller");
        generatedItem("graphene_sheet");
        generatedItem("superconductor_precursor");
        generatedItem("nbti_conductor");
        generatedItem("ybco_tape");
    }

    @Override
    public String getName() {
        return "终局能源物品模型: " + MiningConstants.MODID;
    }

    private void generatedItem(String itemId) {
        withExistingParent(itemId, mcLoc("item/generated"))
                .texture("layer0", modLoc("item/" + itemId));
    }
}
