package com.miningdim.registry;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 创造模式物品栏 (R4: 入口方块进创造栏)。注册一个 mod 自有标签页 (标题键 itemGroup.miningdim,
 * lang 已有该键), 放入全部 mod 物品 (三入口方块 + 传送门 + 假矿石), 使它们在创造栏可取。
 *
 * 1.20.1 Forge 用 DeferredRegister(Registries.CREATIVE_MODE_TAB) 注册标签页; displayItems 回调内
 * accept 各 BlockItem 即入栏, 无需另订 BuildCreativeModeTabContentsEvent (那是往他人标签页塞物品时才用)。
 */
public final class ModCreativeTabs {

    private ModCreativeTabs() {
    }

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MiningConstants.MODID);

    public static final RegistryObject<CreativeModeTab> MINING_TAB = TABS.register("miningdim",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + MiningConstants.MODID))
                    .icon(() -> new ItemStack(ModBlocks.ENTRANCE_EASY.get()))
                    .displayItems((params, output) -> {
                        output.accept(ModBlocks.ENTRANCE_EASY.get());
                        output.accept(ModBlocks.ENTRANCE_MEDIUM.get());
                        output.accept(ModBlocks.ENTRANCE_HARD.get());
                        output.accept(ModBlocks.MINING_PORTAL.get());
                        output.accept(ModBlocks.FAKE_ORE.get());
                    })
                    .build());

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
