package com.miningdim.district.mixin.world;

import com.miningdim.district.guard.DistrictWorldGuards;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 地块边界: 下落的方块 (设计文档 22.9, 单向口径)。落地时 tick 里直接 setBlock, 没有 Forge 事件; getStartPos() 只是同步
 * 数据、不存盘, 不能用。
 *
 * <ul>
 *   <li>tick 的 HEAD 只记起点: 两个 @Unique 字段记下实体第一次被看到时所在的 X、Z (存坐标不存区域号: 区域号在索引重建后
 *       会变)。起点随实体存盘 ({@link DistrictWorldGuards#FALLING_START_KEY}), 区块卸下再加载不会把起点换成当时的位置。</li>
 *   <li>判定在 tick 里 {@code move(...)} 之后、落地 setBlock 之前 (INVOKE move, 之后): 原版在同一个 tick 里先移动、再按
 *       移动之后的位置落地, 在 HEAD 判定会漏掉"这一 tick 才越过边界并落地"的 (TNT 大炮打到边界那一格、活塞把地上的沙子
 *       推过边界)。横移进别的区域时走原版"放不下就掉成物品"那一支: 按 doEntityDrops 掉一个方块物品 (cancelDrop 的
 *       不掉, 与原版一致), 然后 discard。</li>
 * </ul>
 * 移动之后的可取消注入会在方法里多一个 CallbackInfo 局部变量; 它在原有的 StackMap 帧处被局部捕获的分析丢掉, 不影响
 * Architectury 在 Fallable.onLand 处的 CAPTURE_FAILHARD (阶段 4 在整合包上核对, 22.16 第 1 条)。
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {

    // 原版 disableDrop() 置真 (可疑的沙子与沙砾、砸坏的铁砧): 落地时只碎不掉物品。
    @Shadow
    private boolean cancelDrop;

    @Unique
    private boolean miningdim$startRecorded;

    @Unique
    private int miningdim$startX;

    @Unique
    private int miningdim$startZ;

    @Inject(method = "tick", at = @At("HEAD"))
    private void miningdim$recordStart(CallbackInfo callback) {
        FallingBlockEntity self = (FallingBlockEntity) (Object) this;
        if (!miningdim$startRecorded && !self.level().isClientSide) {
            BlockPos pos = self.blockPosition();
            miningdim$startRecorded = true;
            miningdim$startX = pos.getX();
            miningdim$startZ = pos.getZ();
        }
    }

    @Inject(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/item/FallingBlockEntity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            shift = At.Shift.AFTER), cancellable = true)
    private void miningdim$guardPlotBoundary(CallbackInfo callback) {
        FallingBlockEntity self = (FallingBlockEntity) (Object) this;
        Level level = self.level();
        if (level.isClientSide || !miningdim$startRecorded) {
            return;
        }
        BlockPos pos = self.blockPosition();
        if (!DistrictWorldGuards.fallingBlockMayStay(level, miningdim$startX, miningdim$startZ, pos.getX(),
                pos.getZ())) {
            // 与原版落地那一支同一个前提: cancelDrop 的下落方块本来就不掉物品, 这里也不能凭空掉出一个
            // (可疑的沙子在生存里拿不到物品形态)。
            if (!cancelDrop && self.dropItem && level.getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)) {
                self.spawnAtLocation(self.getBlockState().getBlock());
            }
            self.discard();
            callback.cancel();
        }
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void miningdim$saveStart(CompoundTag tag, CallbackInfo callback) {
        if (miningdim$startRecorded) {
            tag.putIntArray(DistrictWorldGuards.FALLING_START_KEY, new int[]{miningdim$startX, miningdim$startZ});
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void miningdim$loadStart(CompoundTag tag, CallbackInfo callback) {
        if (tag.contains(DistrictWorldGuards.FALLING_START_KEY, Tag.TAG_INT_ARRAY)) {
            int[] start = tag.getIntArray(DistrictWorldGuards.FALLING_START_KEY);
            if (start.length == 2) {
                miningdim$startRecorded = true;
                miningdim$startX = start[0];
                miningdim$startZ = start[1];
            }
        }
    }
}
