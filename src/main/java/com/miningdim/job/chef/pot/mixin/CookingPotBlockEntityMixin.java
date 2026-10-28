package com.miningdim.job.chef.pot.mixin;

import com.miningdim.job.chef.pot.PotBridge;
import com.miningdim.job.chef.pot.PotCraftService;
import com.miningdim.job.chef.pot.PotInventoryHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;

@Mixin(CookingPotBlockEntity.class)
public abstract class CookingPotBlockEntityMixin implements PotBridge {
    @Unique private PotCraftService.PotState wok$productionState;
    @Shadow(remap = false) @Final private ItemStackHandler inventory;
    @Shadow(remap = false) private boolean checkNewRecipe;
    @Shadow(remap = false) private ItemStack mealContainerStack;
    @Shadow(remap = false) private int cookTime;
    private CookingPotBlockEntity wok$self() { return (CookingPotBlockEntity) (Object) this; }
    @Override public PotCraftService.PotState wok$productionState() {
        if (wok$productionState == null) wok$productionState = new PotCraftService.PotState();
        return wok$productionState;
    }
    @Override public ItemStackHandler wok$rawInventory() { return inventory; }
    @Override public void wok$setRecipeDirty() { checkNewRecipe = true; }
    @Override public void wok$setMealContainer(ItemStack container) { mealContainerStack = container; }
    @Override public ItemStack wok$mealContainer() { return mealContainerStack; }
    @Override public void wok$setCookTime(int time) { cookTime = time; }
    @Override public void wok$notifyInventoryChanged() {
        wok$self().setChanged();
        if (wok$self().getLevel() != null) {
            wok$self().getLevel().sendBlockUpdated(wok$self().getBlockPos(), wok$self().getBlockState(), wok$self().getBlockState(), 2);
        }
        PotCraftService.changed(wok$self());
    }

    @Inject(method = "createHandler()Lnet/minecraftforge/items/ItemStackHandler;", at = @At("HEAD"), cancellable = true, remap = false)
    private void wok$handler(CallbackInfoReturnable<ItemStackHandler> callback) {
        callback.setReturnValue(new PotInventoryHandler(wok$self()));
    }
    @Inject(method = "load(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"), remap = true)
    private void wok$load(CompoundTag tag, CallbackInfo callback) { PotCraftService.onLoad(wok$self(), tag); }
    @Inject(method = "saveAdditional(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"), remap = true)
    private void wok$save(CompoundTag tag, CallbackInfo callback) { PotCraftService.onSave(wok$self(), tag); }
    @Inject(method = "getInventory()Lnet/minecraftforge/items/ItemStackHandler;", at = @At("HEAD"), remap = false)
    private void wok$inventory(CallbackInfoReturnable<ItemStackHandler> callback) { PotCraftService.ensureHydrated(wok$self()); }
    @Inject(method = "useHeldItemOnMeal(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), cancellable = true, remap = false)
    private void wok$serveBegin(ItemStack held, CallbackInfoReturnable<ItemStack> callback) {
        if (!PotCraftService.canServe(wok$self())) callback.setReturnValue(ItemStack.EMPTY);
        else PotCraftService.beginBatch(wok$self());
    }
    @Inject(method = "useHeldItemOnMeal(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;", at = @At("RETURN"), remap = false)
    private void wok$serveEnd(ItemStack held, CallbackInfoReturnable<ItemStack> callback) { PotCraftService.endBatch(wok$self()); }
    @Inject(method = "getDroppableInventory()Lnet/minecraft/core/NonNullList;", at = @At("HEAD"), remap = false)
    private void wok$break(CallbackInfoReturnable<?> callback) { PotCraftService.beforeRemoval(wok$self()); }
    @Inject(method = "writeMeal(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;", at = @At("HEAD"), remap = false)
    private void wok$package(CompoundTag tag, CallbackInfoReturnable<CompoundTag> callback) { PotCraftService.beforePackaging(wok$self()); }
    @Inject(method = "writeMeal(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;", at = @At("RETURN"), remap = false)
    private void wok$packagePending(CompoundTag tag, CallbackInfoReturnable<CompoundTag> callback) { PotCraftService.onSave(wok$self(), callback.getReturnValue()); }
    @Inject(method = "setRemoved()V", at = @At("TAIL"), remap = true)
    private void wok$removed(CallbackInfo callback) { PotCraftService.removed(wok$self()); }
    @Inject(method = "cookingTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lvectorwing/farmersdelight/common/block/entity/CookingPotBlockEntity;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void wok$tickBegin(Level level, BlockPos pos, BlockState blockState, CookingPotBlockEntity pot, CallbackInfo callback) {
        if (level.isClientSide) return;
        if (PotCraftService.tick(pot)) callback.cancel();
        else PotCraftService.beginBatch(pot);
    }
    @Inject(method = "cookingTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lvectorwing/farmersdelight/common/block/entity/CookingPotBlockEntity;)V", at = @At("RETURN"), remap = false)
    private static void wok$tickEnd(Level level, BlockPos pos, BlockState blockState, CookingPotBlockEntity pot, CallbackInfo callback) {
        if (!level.isClientSide && !PotCraftService.locked(pot)) PotCraftService.endBatch(pot);
    }
}
