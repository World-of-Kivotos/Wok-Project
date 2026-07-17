package com.miningdim.job.engineer;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.engineer.armor.PlateArmorVariant;
import com.miningdim.job.engineer.shield.PlasmaShieldVariant;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 工程师专属创造物品栏 (任务: 如需创造物品栏, 在自己 package 新建专属 CreativeModeTab, 不改中央
 * registry.ModCreativeTabs)。放入六档护甲板 + 六档生产台。
 *
 * 标题键 itemGroup.miningdim_engineer 由集成阶段并入共享 lang (langEntries 返回)。
 */
public final class ModEngineerTab {

    private ModEngineerTab() {
    }

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MiningConstants.MODID);

    public static final RegistryObject<CreativeModeTab> ENGINEER_TAB = TABS.register("miningdim_engineer",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.miningdim_engineer"))
                    .icon(() -> new ItemStack(ModEngineerItems.plate(NanoTier.RADIANT).get()))
                    .displayItems((params, output) -> {
                        for (NanoTier tier : NanoTier.values()) {
                            output.accept(ModEngineerItems.plate(tier).get());
                        }
                        for (NanoTier tier : NanoTier.values()) {
                            output.accept(ModEngineerItems.tableItem(tier).get());
                        }
                        for (PlasmaShieldVariant variant : PlasmaShieldVariant.values()) {
                            output.accept(ModEngineerItems.plasmaShield(variant).get());
                        }
                        for (PlateArmorVariant variant : PlateArmorVariant.values()) {
                            output.accept(ModEngineerItems.plateArmor(variant).get());
                        }
                    })
                    .build());

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
