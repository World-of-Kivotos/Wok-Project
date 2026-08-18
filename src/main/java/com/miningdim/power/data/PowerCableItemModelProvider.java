package com.miningdim.power.data;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.PowerRegistry;
import com.miningdim.power.cable.ConductorMaterial;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.model.generators.ItemModelProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

/** P1 线缆使用独立扁平图标，导线统一使用共享线卷基底。 */
final class PowerCableItemModelProvider extends ItemModelProvider {

    PowerCableItemModelProvider(PackOutput output, ExistingFileHelper existingFiles) {
        super(output, MiningConstants.MODID, existingFiles);
    }

    @Override
    protected void registerModels() {
        for (ConductorMaterial material : PowerRegistry.CABLES.keySet()) {
            withExistingParent(material.blockId(), mcLoc("item/generated"))
                    .texture("layer0", modLoc("item/" + material.blockId()));
            getBuilder(material.id() + "_wire")
                    .parent(new net.minecraftforge.client.model.generators.ModelFile.UncheckedModelFile("item/generated"))
                    .texture("layer0", new ResourceLocation(MiningConstants.MODID, "item/wire_base"));
        }
    }

    @Override
    public String getName() {
        return "能源线缆物品模型: " + MiningConstants.MODID;
    }
}
