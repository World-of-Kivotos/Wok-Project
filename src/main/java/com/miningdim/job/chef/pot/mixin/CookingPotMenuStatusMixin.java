package com.miningdim.job.chef.pot.mixin;

import com.miningdim.job.chef.pot.PotCraftService;
import com.miningdim.job.chef.pot.PotMenuStatus;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import vectorwing.farmersdelight.common.block.entity.container.CookingPotMenu;

@Mixin(CookingPotMenu.class)
public abstract class CookingPotMenuStatusMixin extends RecipeBookMenu<RecipeWrapper> implements PotMenuStatus {
    @Shadow(remap = false) @Final public CookingPotBlockEntity blockEntity;
    @Unique private DataSlot wok$busy;
    @Unique private DataSlot wok$progress;
    @Unique private DataSlot[] wok$cost;

    private CookingPotMenuStatusMixin() { super(null, 0); }

    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lvectorwing/farmersdelight/common/block/entity/CookingPotBlockEntity;Lnet/minecraft/world/inventory/ContainerData;)V", at = @At("TAIL"), remap = false)
    private void wok$addStatus(CallbackInfo callback) {
        wok$busy = DataSlot.standalone();
        wok$progress = DataSlot.standalone();
        wok$cost = new DataSlot[4];
        for (int i = 0; i < wok$cost.length; i++) wok$cost[i] = DataSlot.standalone();
        addDataSlot(wok$busy);
        addDataSlot(wok$progress);
        for (DataSlot part : wok$cost) addDataSlot(part);
    }

    @Override
    public void broadcastChanges() {
        if (!blockEntity.getLevel().isClientSide) {
            PotCraftService.Status status = PotCraftService.status(blockEntity);
            wok$busy.set(status.busy() ? 1 : 0);
            wok$progress.set(status.progress());
            long cost = status.cost();
            for (int i = 0; i < wok$cost.length; i++) wok$cost[i].set((int) ((cost >>> (i * 16)) & 0xFFFF));
        }
        super.broadcastChanges();
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (player.level().isClientSide) { super.clicked(slotId, button, clickType, player); return; }
        PotCraftService.ensureHydrated(blockEntity);
        if (PotCraftService.locked(blockEntity)) return;
        PotCraftService.beginBatch(blockEntity);
        try { super.clicked(slotId, button, clickType, player); }
        finally { PotCraftService.changed(blockEntity); PotCraftService.endBatch(blockEntity); }
    }

    @Override
    public void handlePlacement(boolean placeAll, Recipe<?> recipe, ServerPlayer player) {
        PotCraftService.ensureHydrated(blockEntity);
        if (PotCraftService.locked(blockEntity)) return;
        PotCraftService.beginBatch(blockEntity);
        try { super.handlePlacement(placeAll, recipe, player); }
        finally { PotCraftService.changed(blockEntity); PotCraftService.endBatch(blockEntity); }
    }
    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true, remap = true)
    private void wok$guardQuickMove(Player player, int index, CallbackInfoReturnable<ItemStack> callback) {
        if (!player.level().isClientSide) {
            PotCraftService.ensureHydrated(blockEntity);
            if (PotCraftService.locked(blockEntity)) callback.setReturnValue(ItemStack.EMPTY);
            else PotCraftService.beginBatch(blockEntity);
        }
    }
    @Inject(method = "quickMoveStack", at = @At("RETURN"), remap = true)
    private void wok$commitQuickMove(Player player, int index, CallbackInfoReturnable<ItemStack> callback) {
        if (!player.level().isClientSide && !PotCraftService.locked(blockEntity)) {
            PotCraftService.changed(blockEntity);
            PotCraftService.endBatch(blockEntity);
        }
    }

    @Inject(method = "clearCraftingContent", at = @At("HEAD"), cancellable = true, remap = true)
    private void wok$guardRecipeClear(CallbackInfo callback) {
        if (!blockEntity.getLevel().isClientSide) {
            PotCraftService.ensureHydrated(blockEntity);
            if (PotCraftService.locked(blockEntity)) callback.cancel();
        }
    }
    @Override public boolean wok$busy() { return wok$busy.get() != 0; }
    @Override public int wok$progress() { return wok$progress.get(); }
    @Override public long wok$cost() {
        long cost = 0;
        for (int i = 0; i < wok$cost.length; i++) cost |= (long) (wok$cost[i].get() & 0xFFFF) << (i * 16);
        return cost;
    }
}
