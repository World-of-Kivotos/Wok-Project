package com.miningdim.district.mixin.create;

import com.miningdim.district.guard.create.CreateGuards;
import com.miningdim.district.guard.create.CreateHookTargets;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * C19 (设计文档 22.6): 土豆加农炮。PotatoProjectileEntity.onHitBlock 调射弹类型的 onBlockHit, 数据驱动的动作 (放南瓜、西瓜,
 * 种土豆、胡萝卜等) 直接 setBlock 或生成下落的方块, 不发事件; Flan 的射弹规则只管末影珍珠、标靶等少数几种方块。挂在
 * 类型上, 附属 mod 加的动作一并覆盖。落点受保护 → 返回 false: 射弹照原版掉落或回收物品。
 */
@Pseudo
@Mixin(targets = CreateHookTargets.POTATO_TYPE, remap = false)
public abstract class PotatoProjectileTypeMixin {

    @Inject(method = CreateHookTargets.POTATO_ON_BLOCK_HIT, at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$guardDistrict(LevelAccessor level, ItemStack stack, BlockHitResult hit,
                                         CallbackInfoReturnable<Boolean> callback) {
        if (!CreateGuards.potatoMayHitBlock(level, hit)) {
            callback.setReturnValue(false);
        }
    }
}
