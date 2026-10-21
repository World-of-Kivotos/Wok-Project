package com.miningdim.freshness.mixin;

import com.miningdim.freshness.FreshnessMergeHooks;
import com.miningdim.freshness.FreshnessConfig;
import com.miningdim.freshness.FreshnessClockData;
import com.miningdim.freshness.FreshnessSourceContext;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拾取与入包路径的同种合并 (Spoilage_Granary_DesignSpec mixin 清单 #7、#8)。
 *
 * 只动私有查找与私有的按格追加, **不动** {@code getSlotWithRemainingSpace} 与 {@code hasRemainingSpaceForItem}:
 * 那两个是公开/半公开的语义, 别的模组拿到格子后会自己 grow, 改了会把它们一起带偏。
 *
 * <ul>
 *   <li>{@code findSlotMatchingUnusedItem}: 原版只找「完全相同」的格子; 这里在 HEAD 先找「只差新鲜度且
 *       落在合并窗口内」的格子, 找到就直接返回, 覆盖「拾取进背包」与 {@code Player.addItem}。</li>
 *   <li>{@code addResource(int, ItemStack)}: 原版不比较数据就往目标格里塞, 所以在 HEAD 先把两堆的章
 *       改成合并后的样子 (件数仍由原版搬), 覆盖 {@code /give} 与指定槽位的入包路径。</li>
 * </ul>
 */
@Mixin(Inventory.class)
public abstract class InventoryMixin {

    @org.spongepowered.asm.mixin.Shadow
    @org.spongepowered.asm.mixin.Final
    public net.minecraft.world.entity.player.Player player;

    @Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"))
    private void freshness$inheritServedPortion(int index, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (FreshnessConfig.stampingEnabled() && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            FreshnessSourceContext.inheritInto(stack, FreshnessClockData.nowMinute(serverPlayer.serverLevel()));
        }
    }


    @Inject(method = "findSlotMatchingUnusedItem", at = @At("HEAD"), cancellable = true)
    private void freshness$findMergeableSlot(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        int slot = FreshnessMergeHooks.findMergeableSlot((Inventory) (Object) this, stack);
        if (slot >= 0) {
            cir.setReturnValue(slot);
        }
    }

    @Inject(method = "addResource(ILnet/minecraft/world/item/ItemStack;)I", at = @At("HEAD"))
    private void freshness$prepareAppend(int index, ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        FreshnessMergeHooks.prepareAppend((Inventory) (Object) this, index, stack);
    }
}
