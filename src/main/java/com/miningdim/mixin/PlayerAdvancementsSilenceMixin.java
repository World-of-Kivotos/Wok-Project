package com.miningdim.mixin;

import com.miningdim.core.AdvancementSilence;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.network.chat.Component;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 静默授予进度 ({@link AdvancementSilence}) 的两处抑制, 只在开关打开时生效, 平时与原版逐字相同。
 *
 * <ol>
 *   <li>{@code award} 里进度刚完成时的全服公告 ({@code PlayerList#broadcastSystemMessage}) 跳过。</li>
 *   <li>开关打开期间有进度完成 (原版在完成的那一刻调 {@code markForVisibilityUpdate}) 就记下; 下一次
 *       {@code flushDirty} 若本该发增量包, 改为重建可见集合并发重置包: 清空已下发的可见集合、把全部相关树根交给原版的
 *       可见性重算, 于是原版自己把可见进度与其进度装进一个 reset=true 的包 —— 客户端对重置包不弹 Toast。
 *       第一个包 (登录) 本来就是重置包, 那时只清掉记号。</li>
 * </ol>
 * 只读核心模块的开关, 不引用任何玩法模块。
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsSilenceMixin {

    @Shadow
    @Final
    private Map<Advancement, AdvancementProgress> progress;

    @Shadow
    @Final
    private Set<Advancement> visible;

    @Shadow
    @Final
    private Set<Advancement> rootsToUpdate;

    @Shadow
    private boolean isFirstPacket;

    /** 自上次下发以来是否有进度在静默状态下完成。 */
    @Unique
    private boolean miningdim$silentlyCompleted;

    @Redirect(method = "award", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void miningdim$announceUnlessSilenced(PlayerList playerList, Component message, boolean overlay) {
        if (!AdvancementSilence.active()) {
            playerList.broadcastSystemMessage(message, overlay);
        }
    }

    @Inject(method = "award", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/PlayerAdvancements;markForVisibilityUpdate(Lnet/minecraft/advancements/Advancement;)V"))
    private void miningdim$noteSilentCompletion(Advancement advancement, String criterion,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (AdvancementSilence.active()) {
            this.miningdim$silentlyCompleted = true;
        }
    }

    @Inject(method = "flushDirty", at = @At("HEAD"))
    private void miningdim$resendAsResetAfterSilentCompletion(ServerPlayer player, CallbackInfo ci) {
        if (!this.miningdim$silentlyCompleted) {
            return;
        }
        this.miningdim$silentlyCompleted = false;
        if (this.isFirstPacket) {
            return;
        }
        Set<Advancement> roots = new HashSet<>();
        for (Advancement advancement : this.visible) {
            roots.add(advancement.getRoot());
        }
        for (Advancement advancement : this.progress.keySet()) {
            roots.add(advancement.getRoot());
        }
        this.visible.clear();
        this.rootsToUpdate.addAll(roots);
        this.isFirstPacket = true;
    }
}
