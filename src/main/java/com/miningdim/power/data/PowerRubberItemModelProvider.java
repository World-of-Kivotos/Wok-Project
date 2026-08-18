package com.miningdim.power.data;

import com.miningdim.core.MiningConstants;
import net.minecraft.data.PackOutput;
import net.minecraftforge.client.model.generators.ItemModelProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

/** 橡胶方块物品复用方块模型，材料和工具使用现有的独立物品贴图。 */
final class PowerRubberItemModelProvider extends ItemModelProvider {

    PowerRubberItemModelProvider(PackOutput output, ExistingFileHelper existingFiles) {
        super(output, MiningConstants.MODID, existingFiles);
    }

    @Override
    protected void registerModels() {
        withExistingParent("rubber_log", modLoc("block/rubber_log"));
        withExistingParent("rubber_planks", modLoc("block/rubber_planks"));
        withExistingParent("rubber_leaves", modLoc("block/rubber_leaves"));
        withExistingParent("rubber_tree_sapling", modLoc("block/rubber_tree_sapling"));
        withExistingParent("rubber_tapping_knife", mcLoc("item/handheld"))
                .texture("layer0", modLoc("item/rubber_tapping_knife"));
        generated("latex");
        generated("rubber");
        generated("insulation_pvc");
        generated("insulation_pe");
    }

    @Override
    public String getName() {
        return "橡胶物品模型: " + MiningConstants.MODID;
    }

    private void generated(String name) {
        withExistingParent(name, mcLoc("item/generated")).texture("layer0", modLoc("item/" + name));
    }
}
