package com.miningdim.freshness.mixin.compat;

import com.miningdim.freshness.FreshnessClockData;
import com.miningdim.freshness.FreshnessFdMachines;
import com.miningdim.freshness.FreshnessFdTargets;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FD 厨锅 的「放着不加热也照常烂」(Spoilage_Granary_DesignSpec「可选配置」#15)。
 *
 * <p>机器里的东西不在任何观察点上: 不补这一处, 把肉放进 厨锅 就等于放进冰箱。接法是它每 tick 都会跑的
 * {@code cookingTick} (静态方法), 在 HEAD 先把内部物品栏结算一遍 —— 与"取出来时才发现烂了"不同, 这里的
 * 判定是**在机器里就发生**的。</p>
 */
@Pseudo
@Mixin(targets = FreshnessFdTargets.POT, remap = false)
public abstract class PotSettleMixin {

    @Inject(method = "cookingTick", at = @At("HEAD"), remap = false)
    private static void miningdim$settleStoredFood(Level level, BlockPos pos, BlockState state,
                                                   vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity machine, CallbackInfo callback) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        FreshnessFdMachines.settle(machine.getInventory(), FreshnessClockData.nowMinute(serverLevel), null);
    }
}