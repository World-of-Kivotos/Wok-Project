package com.miningdim.freshness.mixin;

import com.miningdim.freshness.FreshnessMergeHooks;
import net.minecraft.recipebook.ServerPlaceRecipe;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 配方书往格子里放料时, 格子取**最老**的那批 (Spoilage_Granary_DesignSpec mixin 清单 #3、反漏洞 #9)。
 *
 * 不处理的话, 新的章会吸收一整组老货: 老货被当成新的, 等于用配方书给存货续命。这里在追加之前把两边的
 * 章统一到较老的一批 (剩余取较少、停表取较大), 原版随后搬进去的那一件就跟着老批次的年龄走。
 */
@Mixin(ServerPlaceRecipe.class)
public abstract class ServerPlaceRecipeMixin {

    @Inject(method = "moveItemToGrid", at = @At("HEAD"))
    private void freshness$preferOlderBatch(Slot slot, ItemStack stack, CallbackInfo ci) {
        FreshnessMergeHooks.prepareRecipeAppend(slot.getItem(), stack);
    }
}
