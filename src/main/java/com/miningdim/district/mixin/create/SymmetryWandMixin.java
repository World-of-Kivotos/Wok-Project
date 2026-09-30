package com.miningdim.district.mixin.create;

import com.miningdim.district.guard.create.CreateGuards;
import com.miningdim.district.guard.create.CreateHookTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.Set;

/**
 * C16 (设计文档 22.6): 对称之杖。SymmetryHandler 在 BreakEvent (LOWEST) 与放置事件之后调 remove / apply, 对每个镜像位置:
 * remove 直接 setBlock 成空气并掉落 (不发 BreakEvent, Flan 与守卫都看不到), apply 的创造模式那一支直接 setBlock。两个方法里
 * 各有唯一一次 {@code Map.keySet()} (镜像出来的全部位置), 重定向它: 去掉落到别的区域的位置 (单向口径, OP 例外), 拆、掉落、
 * 放一起跳过。不重定向 setBlock: 那样掉落照样发生, 等于复制物品。处理方法额外收目标方法的全部参数 (Mixin 0.8.5 的
 * @Redirect 允许在被调方法的参数之后接目标方法的参数前缀)。
 */
@Pseudo
@Mixin(targets = CreateHookTargets.SYMMETRY_WAND, remap = false)
public abstract class SymmetryWandMixin {

    @Redirect(method = CreateHookTargets.SYMMETRY_REMOVE,
            at = @At(value = "INVOKE", target = CreateHookTargets.MAP_KEY_SET, remap = false), remap = false)
    private static Set<BlockPos> miningdim$guardRemove(Map<BlockPos, BlockState> positions, Level level, ItemStack wand,
                                                       Player player, BlockPos origin) {
        return CreateGuards.symmetryTargets(positions, level, player, origin);
    }

    @Redirect(method = CreateHookTargets.SYMMETRY_APPLY,
            at = @At(value = "INVOKE", target = CreateHookTargets.MAP_KEY_SET, remap = false), remap = false)
    private static Set<BlockPos> miningdim$guardApply(Map<BlockPos, BlockState> positions, Level level, ItemStack wand,
                                                      Player player, BlockPos origin, BlockState state) {
        return CreateGuards.symmetryTargets(positions, level, player, origin);
    }
}
