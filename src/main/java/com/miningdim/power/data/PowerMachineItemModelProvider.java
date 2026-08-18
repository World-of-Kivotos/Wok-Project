package com.miningdim.power.data;

import com.miningdim.core.MiningConstants;
import net.minecraft.data.PackOutput;
import net.minecraftforge.client.model.generators.ItemModelProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

/** 提纯链中间物使用原版扁平物品模型，机器物品复用其方块模型。 */
final class PowerMachineItemModelProvider extends ItemModelProvider {

    PowerMachineItemModelProvider(PackOutput output, ExistingFileHelper existingFiles) {
        super(output, MiningConstants.MODID, existingFiles);
    }

    @Override
    protected void registerModels() {
        for (String itemId : new String[]{
                "deoxidized_copper_ingot", "phosphorus_deoxidized_copper_ingot", "ofc_copper_ingot",
                "ofe_copper_ingot", "gold_4n_ingot", "argon_canister", "liquid_nitrogen_canister"}) {
            withExistingParent(itemId, mcLoc("item/generated"))
                    .texture("layer0", modLoc("item/" + itemId));
        }
        withExistingParent("metallurgic_purifier", modLoc("block/metallurgic_purifier"));
        withExistingParent("air_separation_unit", modLoc("block/air_separation_unit"));
    }

    @Override
    public String getName() {
        return "能源加工机器物品模型: " + MiningConstants.MODID;
    }
}
