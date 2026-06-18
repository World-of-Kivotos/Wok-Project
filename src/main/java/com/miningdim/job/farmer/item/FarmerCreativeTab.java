package com.miningdim.job.farmer.item;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.farmer.FarmerTier;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 农夫专属创造模式标签页 (实现手册 "新 CreativeModeTab": 禁改中央 ModCreativeTabs, 子系统自持)。
 * 收纳种子、收获物、五档耕地 BlockItem。
 */
public final class FarmerCreativeTab {

    private FarmerCreativeTab() {
    }

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB, MiningConstants.MODID);

    public static final RegistryObject<CreativeModeTab> FARMER_TAB =
            TABS.register("farmer", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.miningdim.farmer"))
                    .icon(() -> new ItemStack(FarmerItems.FARMER_WHEAT.get()))
                    .displayItems((params, output) -> {
                        output.accept(FarmerItems.FARMER_SEED.get());
                        output.accept(FarmerItems.FARMER_WHEAT.get());
                        for (FarmerTier tier : FarmerTier.values()) {
                            output.accept(FarmerItems.farmlandItem(tier).get());
                        }
                    })
                    .build());

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
