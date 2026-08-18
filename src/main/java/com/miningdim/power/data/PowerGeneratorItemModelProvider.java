package com.miningdim.power.data;

import com.miningdim.core.MiningConstants;
import net.minecraft.data.PackOutput;
import net.minecraftforge.client.model.generators.ItemModelProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

/** 发电机耗材使用独立的原版扁平物品模型。 */
final class PowerGeneratorItemModelProvider extends ItemModelProvider {

    PowerGeneratorItemModelProvider(PackOutput output, ExistingFileHelper existingFiles) {
        super(output, MiningConstants.MODID, existingFiles);
    }

    @Override
    protected void registerModels() {
        generatedItem("industrial_fuel_core");
        generatedItem("modern_fuel_core");
        generatedItem("future_fuel_core");
        generatedItem("nichrome_fuse");
    }

    private void generatedItem(String itemId) {
        withExistingParent(itemId, mcLoc("item/generated"))
                .texture("layer0", modLoc("item/" + itemId));
    }

    @Override
    public String getName() {
        return "能源发电机物品模型: " + MiningConstants.MODID;
    }
}
