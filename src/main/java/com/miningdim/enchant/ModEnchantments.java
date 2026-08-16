package com.miningdim.enchant;

import com.miningdim.core.MiningConstants;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 本 mod 自研附魔的 DeferredRegister holder (范式同 {@code ModItems}/{@code ModBlocks})。 */
public final class ModEnchantments {

    public static final DeferredRegister<Enchantment> ENCHANTMENTS =
            DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, MiningConstants.MODID);

    /** 金钱修补: 花信用点自动补耐久, 与原版经济修补互斥, 只由任务奖励发书。 */
    public static final RegistryObject<Enchantment> MONEY_MENDING =
            ENCHANTMENTS.register("money_mending", MoneyMendingEnchantment::new);

    private ModEnchantments() {
    }

    public static void register(IEventBus modBus) {
        ENCHANTMENTS.register(modBus);
    }
}
