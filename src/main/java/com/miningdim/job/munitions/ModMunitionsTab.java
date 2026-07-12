package com.miningdim.job.munitions;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprint;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprintItem;
import com.miningdim.job.munitions.gunsmith.GunsmithPartItem;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPlatform;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
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

    public static final RegistryObject<CreativeModeTab> GUNSMITH_TAB = TABS.register("miningdim_gunsmith",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.miningdim_gunsmith"))
                    .icon(() -> GunsmithPartItem.createStack(ModMunitionsItems.GUNSMITH_PART.get(),
                            GunsmithPlatform.AR, GunsmithPressPart.CORE, GunsmithPartQuality.LEGENDARY))
                    .displayItems((params, output) -> {
                        // 功能门: 3A WIP 关闭时页签留空, 不发放任何 gunsmith 物品 (displayItems 在世界内构建,
                        // SERVER config 此时已加载可读)。
                        if (!MunitionsConfig.GUNSMITH_ENABLED.get()) {
                            return;
                        }
                        output.accept(ModMunitionsItems.GUNSMITH_PRESS_ITEM.get());
                        output.accept(ModMunitionsItems.GUNSMITH_ASSEMBLY_BENCH_ITEM.get());
                        for (GunsmithBlueprint blueprint : GunsmithBlueprint.values()) {
                            output.accept(GunsmithBlueprintItem.createStack(
                                    ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), blueprint));
                        }
                        GunsmithPartItem.addCreativeStacks(output);
                    })
                    .build());

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
