package com.miningdim.power.data;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.mineral.PowerMineral;
import com.miningdim.power.mineral.PowerMineralRegistry;
import net.minecraft.data.PackOutput;
import net.minecraftforge.client.model.generators.ItemModelProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

/** 原矿与锭分别引用同名手绘物品纹理。 */
final class PowerMineralItemModelProvider extends ItemModelProvider {

    PowerMineralItemModelProvider(PackOutput output, ExistingFileHelper existingFiles) {
        super(output, MiningConstants.MODID, existingFiles);
    }

    @Override
    protected void registerModels() {
        for (PowerMineral mineral : PowerMineral.values()) {
            withExistingParent(mineral.rawMaterialId(), mcLoc("item/generated"))
                    .texture("layer0", modLoc("item/" + mineral.rawMaterialId()));
            if (mineral.hasIngot()) {
                withExistingParent(mineral.ingotId(), mcLoc("item/generated"))
                        .texture("layer0", modLoc("item/" + mineral.ingotId()));
            }
        }
    }
}
