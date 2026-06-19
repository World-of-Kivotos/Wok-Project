package com.miningdim.job.brewer;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 酿酒师专属创造物品栏 (不碰中央 ModCreativeTabs; 同厨师/塔罗各自建页范式)。放干小麦 + 九种酒。
 * 标题键 itemGroup.miningdim_brewer。1.20.1 用 DeferredRegister(Registries.CREATIVE_MODE_TAB)。
 */
public final class BrewerTab {

    private BrewerTab() {
    }

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MiningConstants.MODID);

    public static final RegistryObject<CreativeModeTab> BREWER_TAB = TABS.register("miningdim_brewer",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.miningdim_brewer"))
                    .icon(() -> new ItemStack(BrewerItems.itemFor(WineType.WHISKEY)))
                    .displayItems((params, output) -> {
                        output.accept(BrewerItems.DRIED_WHEAT.get());
                        for (WineType type : WineType.values()) {
                            output.accept(BrewerItems.itemFor(type));
                        }
                    })
                    .build());

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
