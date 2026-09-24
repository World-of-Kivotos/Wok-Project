package com.miningdim.job.fisher.journal;

import com.miningdim.job.fisher.size.FishRecord;
import com.miningdim.job.fisher.size.FishingRecords;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class FishingJournalService {
    private FishingJournalService() {
    }

    public static boolean collect(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!FishingJournalCatalog.INSTANCE.contains(itemId)) {
            return false;
        }
        return FishingJournalSavedData.get(player.serverLevel()).collect(player.getUUID(), itemId);
    }

    public static boolean scanInventory(ServerPlayer player) {
        boolean changed = false;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            changed |= collect(player, player.getInventory().getItem(slot));
        }
        changed |= collect(player, player.containerMenu.getCarried());
        return changed;
    }

    public static void observeMenu(ServerPlayer player, AbstractContainerMenu menu) {
        menu.addSlotListener(new ContainerListener() {
            @Override
            public void slotChanged(AbstractContainerMenu changedMenu, int slot, ItemStack stack) {
                // 每次点击结束都会通知槽位监听器，捕获同一 tick 内拿起又放回的鱼。
                // 箱子/交易结果的展示槽不属于玩家持物，只检查玩家背包和实际鼠标持物。
                if (scanInventory(player)) {
                    FishingJournalNetwork.send(player, snapshot(player), false);
                }
            }

            @Override
            public void dataChanged(AbstractContainerMenu changedMenu, int field, int value) {
                // 熔炉进度等整数属性不代表物品所有权变化。
            }
        });
    }

    public static FishingJournalSnapshot snapshot(ServerPlayer player) {
        var entries = FishingJournalCatalog.INSTANCE.entries();
        Set<ResourceLocation> collected = new HashSet<>(
                FishingJournalSavedData.get(player.serverLevel()).collected(player.getUUID()));
        // 已移除数据包或可选 MOD 的历史记录仍存盘，但不计入当前可见目录的进度。
        collected.removeIf(id -> !FishingJournalCatalog.INSTANCE.contains(id));
        Map<ResourceLocation, FishRecord> records = new HashMap<>(FishingRecords.all(player));
        records.keySet().removeIf(id -> !FishingJournalCatalog.INSTANCE.contains(id));
        return new FishingJournalSnapshot(entries, collected, records);
    }

    public static void open(ServerPlayer player) {
        scanInventory(player);
        FishingJournalNetwork.send(player, snapshot(player), true);
    }
}
