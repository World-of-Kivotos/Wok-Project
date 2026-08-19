package com.miningdim.mixin;

import com.miningdim.core.MiningServices;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 放宽<b>仅创造飞行状态</b>下的原版 "moved too quickly" 校验。
 *
 * <p>原版 {@code handleMovePlayer} 的判据是 {@code d10 - d9 > f2 * i}: d10 为本次位移的平方, d9 为服务端
 * 侧 {@code getDeltaMovement().lengthSqr()} 的预期值, f2 取 100 (鞘翅滑翔取 300), i 为自上 tick 起收到的
 * 移动包数。因此 f2 是<b>距离的平方</b>, 100 即 10 格/tick。
 *
 * <p>创造飞行的推进由客户端 {@code Abilities.flyingSpeed} 驱动, 服务端的 deltaMovement 基本停留在 0, 于是
 * d9 约等于 0, 判据退化成"位移平方是否超过 100"。一旦飞行速度被调到 10 格/tick 以上 (本服用 KubeJS 脚本
 * 提速), 每个移动包都会触发, 服务端随即 {@code teleport} 把玩家拽回原位 —— 表现为高速飞行时不断往回扯。
 * 2026-08-19 实测位移达 17.6 格/tick, 四分钟内触发 39 次。
 *
 * <p>这里只在 {@code Abilities.flying} 为真时把 f2 换成配置值的平方; 走路、疾跑、鞘翅、载具、被击退等所有
 * 其它状态一律拿到原版的 100, 校验强度不变。创造飞行本身已是特权状态 (仅创造模式或被授予 mayfly 的玩家),
 * 放宽它不构成新的作弊面, 而超出配置上限的瞬移仍会被拦下。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MoveSpeedCheckMixin {

    @Shadow
    public ServerPlayer player;

    @ModifyConstant(method = "handleMovePlayer", constant = @Constant(floatValue = 100.0F))
    private float miningdim$widenCreativeFlightLimit(float vanillaLimit) {
        if (!this.player.getAbilities().flying) {
            return vanillaLimit;
        }
        int blocksPerTick = MiningServices.config().creativeFlightMaxBlocksPerTick();
        return (float) blocksPerTick * (float) blocksPerTick;
    }
}
