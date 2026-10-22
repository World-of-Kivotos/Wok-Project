package com.miningdim.freshness.mixin.compat;

import com.miningdim.freshness.FreshMerge;
import com.miningdim.freshness.FreshnessCategories;
import com.miningdim.freshness.FreshnessClockData;
import com.miningdim.freshness.FreshnessConfig;
import com.miningdim.freshness.FreshnessData;
import com.miningdim.freshness.FreshnessFdTargets;
import com.miningdim.freshness.FreshnessProcessing;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Pseudo
@Mixin(targets = FreshnessFdTargets.POT, remap = false)
public abstract class CookingPotMixin {

    @Shadow @Final private ItemStackHandler inventory;
    @Shadow(remap = false) public abstract boolean isContainerValid(ItemStack stack);
    @Unique private ItemStack miningdim$producedMeal = ItemStack.EMPTY;

    @Inject(method = FreshnessFdTargets.POT_PROCESS, at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$refuseRottenInputs(CallbackInfoReturnable<Boolean> callback) {
        if (FreshnessConfig.replacementEnabled() && levelOf() instanceof ServerLevel serverLevel
                && FreshnessProcessing.anyRotten(inputs(), FreshnessClockData.nowMinute(serverLevel))) {
            callback.setReturnValue(false);
        }
    }

    // FD 1.2.9 的首个 ItemStack 局部变量是实际生成的配方结果; 此时六格原料尚未消耗。
    // 复制结果避免改到配方模板, 只在烹饪真正完成时继承一次, 与后续是否用碗装无关。
    @ModifyVariable(method = FreshnessFdTargets.POT_PROCESS, at = @At("STORE"), ordinal = 0, remap = false)
    private ItemStack miningdim$inheritProducedMeal(ItemStack result) {
        ItemStack product = result.copy();
        if (FreshnessConfig.stampingEnabled() && levelOf() instanceof ServerLevel serverLevel) {
            FreshnessProcessing.inherit(product, inputs(), FreshnessProcessing.HEAT_BONUS,
                    FreshnessClockData.nowMinute(serverLevel));
        }
        miningdim$producedMeal = product;
        return product;
    }

    @Redirect(method = {"canCook", FreshnessFdTargets.POT_PROCESS, FreshnessFdTargets.POT_MOVE_MEAL, "useStoredContainersOnMeal"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameTags(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z",
            remap = true), remap = false)
    private boolean miningdim$compareMealData(ItemStack existing, ItemStack incoming) {
        return FreshnessConfig.replacementEnabled() && FreshnessCategories.spoils(incoming)
                ? FreshnessData.sameExceptFreshness(existing, incoming) : ItemStack.isSameItemSameTags(existing, incoming);
    }

    @Redirect(method = FreshnessFdTargets.POT_PROCESS, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;grow(I)V", remap = true), remap = false)
    private void miningdim$appendProducedMeal(ItemStack existing, int count) {
        if (FreshnessConfig.stampingEnabled()) {
            FreshMerge.preferOlderBatch(existing, miningdim$producedMeal);
        }
        existing.grow(count);
    }

    @Inject(method = FreshnessFdTargets.POT_MOVE_MEAL, at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$moveMealWithData(CallbackInfo callback) {
        moveMeal(false, callback);
    }

    @Inject(method = "useStoredContainersOnMeal", at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$fillContainersWithData(CallbackInfo callback) {
        moveMeal(true, callback);
    }

    @Unique private void moveMeal(boolean withContainers, CallbackInfo callback) {
        ItemStack meal = inventory.getStackInSlot(FreshnessFdTargets.POT_MEAL_SLOT);
        if (!FreshnessConfig.replacementEnabled() || !(levelOf() instanceof ServerLevel)
                || !FreshnessCategories.spoils(meal)) {
            return;
        }
        // ON 接管整个移动, NBT 不兼容时也必须停止, 不能再落回 FD 的 item-only 合并。
        callback.cancel();
        ItemStack output = inventory.getStackInSlot(FreshnessFdTargets.POT_OUTPUT_SLOT);
        ItemStack containers = inventory.getStackInSlot(7);
        if (withContainers && !isContainerValid(containers)) {
            return;
        }
        if (!output.isEmpty() && !FreshnessData.sameExceptFreshness(output, meal)) {
            return;
        }
        int moved = Math.min(meal.getCount(), meal.getMaxStackSize() - output.getCount());
        if (withContainers) {
            moved = Math.min(moved, containers.getCount());
        }
        if (moved == 0) {
            return;
        }
        if (output.isEmpty()) {
            inventory.setStackInSlot(FreshnessFdTargets.POT_OUTPUT_SLOT, meal.split(moved));
        } else {
            FreshMerge.preferOlderBatch(output, meal);
            output.grow(moved);
            meal.shrink(moved);
            inventory.setStackInSlot(FreshnessFdTargets.POT_OUTPUT_SLOT, output);
        }
        if (withContainers) {
            containers.shrink(moved);
            inventory.setStackInSlot(7, containers);
        }
        inventory.setStackInSlot(FreshnessFdTargets.POT_MEAL_SLOT, meal);
    }

    @Unique private List<ItemStack> inputs() {
        List<ItemStack> ingredients = new ArrayList<>(FreshnessFdTargets.POT_INPUT_SLOTS);
        for (int slot = 0; slot < FreshnessFdTargets.POT_INPUT_SLOTS; slot++) {
            ingredients.add(inventory.getStackInSlot(slot).copy());
        }
        return ingredients;
    }

    @Unique private Level levelOf() {
        return ((net.minecraft.world.level.block.entity.BlockEntity) (Object) this).getLevel();
    }
}
