package com.miningdim.freshness;

import com.miningdim.menu.FoodStorageSlot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

import java.util.Set;

/** 观察与合并共用真实库存的归属判断。原版容器界面也可能承载插件的展示格。 */
public final class FreshnessContainers {

    private static final Set<String> IRON_CHESTS = Set.of("iron_chest", "gold_chest", "diamond_chest",
            "crystal_chest", "copper_chest", "obsidian_chest", "dirt_chest");

    private FreshnessContainers() {
    }

    public static boolean isStorageSlot(AbstractContainerMenu menu, Slot slot) {
        if (slot instanceof FoodStorageSlot) {
            return true;
        }
        Container inventory = slot.container;
        if (menu instanceof ChestMenu chest && chest.getContainer() == inventory) {
            if (inventory instanceof CompoundContainer pair) {
                return pair.container1 instanceof ChestBlockEntity && pair.container2 instanceof ChestBlockEntity;
            }
            if (inventory instanceof ChestBlockEntity || inventory instanceof BarrelBlockEntity
                    || inventory instanceof PlayerEnderChestContainer) {
                return true;
            }
        }
        if (inventory instanceof ShulkerBoxBlockEntity) {
            return menu instanceof ShulkerBoxMenu;
        }
        if (inventory instanceof DropperBlockEntity) {
            return menu instanceof DispenserMenu;
        }
        if (!(inventory instanceof BlockEntity storage)) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(storage.getType());
        if ("farmersdelight".equals(id.getNamespace())) {
            return menu instanceof ChestMenu chest && chest.getContainer() == inventory
                    && ("cabinet".equals(id.getPath()) || "basket".equals(id.getPath()));
        }
        if ("ironchest".equals(id.getNamespace())) {
            String path = id.getPath();
            String material = path.startsWith("trapped_") ? path.substring("trapped_".length()) : path;
            if (IRON_CHESTS.contains(material)) {
                ResourceLocation menuId = BuiltInRegistries.MENU.getKey(menu.getType());
                return new ResourceLocation("ironchest", material).equals(menuId);
            }
        }
        return false;
    }

    public static boolean isMergeTarget(AbstractContainerMenu menu, Slot slot) {
        if (menu == null || slot == null) {
            return false;
        }
        if (slot instanceof FoodStorageSlot storage) {
            return storage.mayMergeForViewer();
        }
        return slot.container instanceof Inventory || isStorageSlot(menu, slot);
    }
}
