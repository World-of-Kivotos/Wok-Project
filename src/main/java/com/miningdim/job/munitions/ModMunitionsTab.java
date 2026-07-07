package com.miningdim.job.munitions;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 军火商专属创造物品栏 (在自己 package 新建专属 CreativeModeTab, 不改中央 registry.ModCreativeTabs)。
 * 放入军火台 BlockItem + 发射药中间品。弹药不进创造栏 (运行期 TACZ 物化, 非注册 Item)。
 */
public final class ModMunitionsTab {

    private ModMunitionsTab() {
    }

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MiningConstants.MODID);

    public static final RegistryObject<CreativeModeTab> MUNITIONS_TAB = TABS.register("miningdim_munitions",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.miningdim_munitions"))
                    .icon(() -> new ItemStack(ModMunitionsItems.MUNITIONS_BENCH_ITEM.get()))
                    .displayItems((params, output) -> {
                        ModMunitionsItems.ALL_BENCH_ITEMS.forEach(item -> output.accept(item.get()));
                        output.accept(ModMunitionsItems.PRIMER.get());
                        output.accept(ModMunitionsItems.CASING.get());
                        output.accept(ModMunitionsItems.BULLET_HEAD.get());
                        output.accept(ModMunitionsItems.PROPELLANT.get());
                    })
                    .build());

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
