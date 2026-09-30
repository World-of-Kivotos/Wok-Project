package com.miningdim.district.mixin.create;

import com.miningdim.district.guard.create.CreateGuards;
import com.miningdim.district.guard.create.CreateHookTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * C1 (设计文档 22.6): 机器拆方块的总闸。BlockHelper.destroyBlockAs 只在传入的玩家非空时才发 BreakEvent, 而每个机器调用方
 * (钻头、锯、压路机、收割机, 锯伐树逐根) 传的都是 null。
 */
@Pseudo
@Mixin(targets = CreateHookTargets.BLOCK_HELPER, remap = false)
public abstract class BlockHelperMixin {

    @Inject(method = CreateHookTargets.DESTROY_BLOCK_AS, at = @At("HEAD"), cancellable = true, remap = false)
    private static void miningdim$guardDistrict(Level level, BlockPos pos, Player player, ItemStack usedTool,
                                                float effectChance, Consumer<ItemStack> droppedItemCallback,
                                                CallbackInfo callback) {
        if (!CreateGuards.mayDestroy(level, pos, player)) {
            callback.cancel();
        }
    }
}
