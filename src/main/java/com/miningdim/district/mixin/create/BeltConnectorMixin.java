package com.miningdim.district.mixin.create;

import com.miningdim.district.guard.create.CreateGuards;
import com.miningdim.district.guard.create.CreateHookTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * C15 (设计文档 22.6): 传送带。BeltConnectorItem.createBelts 沿途逐格 destroyBlock 挡路的方块再直接放传送带, 不发任何事件;
 * 玩家连接 (useOn) 与蓝图炮打印 (LaunchedItem$ForBelt.place, 长度来自蓝图里的方块实体数据) 都走它。
 * <ul>
 *   <li>useOn 的 HEAD 清线程局部; useOn 里唯一一次 canConnect 之前判定玩家这一条: 非 OP、碰到禁放区 → 回 FAIL (不扣
 *       物品) 并发动作栏提示; OP 记下这一条。useOn 是 Minecraft 方法的覆写, jar 里是 SRG 名, 见
 *       {@link CreateHookTargets#BELT_USE_ON}。</li>
 *   <li>createBelts 的 HEAD: 这一条的框碰到禁放区就整条不铺 (OP 刚在 useOn 里放行的同一条除外)。</li>
 * </ul>
 */
@Pseudo
@Mixin(targets = CreateHookTargets.BELT_CONNECTOR, remap = false)
public abstract class BeltConnectorMixin {

    @Inject(method = CreateHookTargets.BELT_USE_ON, at = @At("HEAD"), remap = false)
    private void miningdim$beginUse(UseOnContext context, CallbackInfoReturnable<InteractionResult> callback) {
        CreateGuards.beltUseStarted();
    }

    @Inject(method = CreateHookTargets.BELT_USE_ON,
            at = @At(value = "INVOKE", target = CreateHookTargets.BELT_CAN_CONNECT, remap = false),
            cancellable = true, remap = false)
    private void miningdim$guardBanArea(UseOnContext context, CallbackInfoReturnable<InteractionResult> callback) {
        if (CreateGuards.beltConnectBlocked(context.getLevel(), context.getPlayer(), context.getItemInHand(),
                context.getClickedPos())) {
            callback.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = CreateHookTargets.CREATE_BELTS, at = @At("HEAD"), cancellable = true, remap = false)
    private static void miningdim$guardDistrict(Level level, BlockPos start, BlockPos end, CallbackInfo callback) {
        if (!CreateGuards.beltMayCreate(level, start, end)) {
            callback.cancel();
        }
    }
}
