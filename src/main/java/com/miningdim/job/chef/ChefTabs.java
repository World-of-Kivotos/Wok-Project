package com.miningdim.job.chef;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 厨师专属创造物品栏 (任务约定: 不碰中央 ModCreativeTabs, 自建专属标签页)。放 5 档调味台 BlockItem。
 *
 * 标题键 itemGroup.miningdim_chef (lang 由集成合并)。1.20.1 用 DeferredRegister(Registries.CREATIVE_MODE_TAB)。
 */
public final class ChefTabs {

    private ChefTabs() {
    }

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MiningConstants.MODID);

    public static final RegistryObject<CreativeModeTab> CHEF_TAB = TABS.register("miningdim_chef",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.miningdim_chef"))
                    .icon(() -> new ItemStack(ChefItems.SEASONING_TABLE_RADIANT.get()))
                    .displayItems((params, output) -> {
                        output.accept(ChefItems.SEASONING_TABLE_LOW.get());
                        output.accept(ChefItems.SEASONING_TABLE_MEDIUM.get());
                        output.accept(ChefItems.SEASONING_TABLE_HIGH.get());
                        output.accept(ChefItems.SEASONING_TABLE_EXTRAORDINARY.get());
                        output.accept(ChefItems.SEASONING_TABLE_RADIANT.get());
                    })
                    .build());

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
