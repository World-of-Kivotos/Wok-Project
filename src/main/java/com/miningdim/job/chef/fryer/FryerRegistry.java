package com.miningdim.job.chef.fryer;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.chef.ChefBlocks;
import com.miningdim.job.chef.fryer.client.FryerClient;
import com.miningdim.menu.ModMenus;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;

@Mod.EventBusSubscriber(modid = MiningConstants.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class FryerRegistry {
    private static final ResourceLocation ID = new ResourceLocation(MiningConstants.MODID, "frying");
    private static BlockEntityType<FryerBlockEntity> blockEntityType;
    private static MenuType<FryerMenu> menuType;

    private FryerRegistry() {}

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        event.register(ForgeRegistries.Keys.BLOCK_ENTITY_TYPES, helper -> {
            blockEntityType = BlockEntityType.Builder.of(FryerBlockEntity::new,
                    ChefBlocks.DEEP_FRYER_RUSTIC.get(), ChefBlocks.DEEP_FRYER_STEEL.get(),
                    ChefBlocks.DEEP_FRYER_CHINESE.get(), ChefBlocks.BAKING_OVEN_RUSTIC.get(),
                    ChefBlocks.BAKING_OVEN_STEEL.get(), ChefBlocks.BAKING_OVEN_CHINESE.get(),
                    ChefBlocks.PREP_COUNTER_RUSTIC.get(), ChefBlocks.PREP_COUNTER_STEEL.get(),
                    ChefBlocks.PREP_COUNTER_CHINESE.get()).build(null);
            helper.register(ID, blockEntityType);
        });
        event.register(ForgeRegistries.Keys.MENU_TYPES, helper -> {
            menuType = ModMenus.blockMenuType(FryerMenu::new);
            helper.register(ID, menuType);
        });
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> FryerClient.registerScreens()));
    }

    public static BlockEntityType<FryerBlockEntity> blockEntityType() { return blockEntityType; }
    public static MenuType<FryerMenu> menuType() { return menuType; }
}
