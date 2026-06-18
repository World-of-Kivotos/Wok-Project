package com.miningdim.menu;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.Block;

import java.util.function.Predicate;

/**
 * menu 有效性策略 (JobFramework_Shared_Foundation_DesignSpec 第六章: stillValid 防死循环 + 非方块变体)。
 *
 * 两种工厂:
 *  - {@link #ofBlock(ContainerLevelAccess, Block)}: 方块 menu —— 经原版 AbstractContainerMenu.stillValid
 *    检查 "目标位置仍是该方块 且 玩家在 8 格内"; 是 boolean 谓词, 不递归。工程师生产台/塔罗合成台/厨师调味台用。
 *  - {@link #ofRemote(Predicate)}: 非方块 menu (戒指远程开共享背包, 第六章最高危 dupe 模块) —— 无 BlockPos,
 *    以传入谓词 (如 MarriageId 配偶在线 + 同维度距离上限 / 虚拟 owner 校验) 作 stillValid 依据。
 *
 * 二者都返回 boolean, 由 {@link AbstractMiningMenu#stillValid(Player)} 委派, 不引用菜单自身防自递归。
 */
@FunctionalInterface
public interface MenuValidity {

    /** 玩家当前是否仍可保持该 menu 打开 (false 时原版自动关闭界面)。 */
    boolean isValid(Player player);

    /** 方块 menu 交互距离平方上界 (原版 AbstractContainerMenu.stillValid 同值: 8 格 -> 64)。 */
    double BLOCK_REACH_SQR = 64.0D;

    /**
     * 方块 menu 有效性: 复刻原版 AbstractContainerMenu.stillValid(ContainerLevelAccess, Player, Block) 逻辑
     * (该静态方法 protected 不可外部调用): 目标位置仍是该方块 且 玩家中心距方块中心平方 &lt;= 64。
     * 经 {@link ContainerLevelAccess#evaluate} 在方块所在 Level 上求值 (NULL access 返回默认 true)。
     */
    static MenuValidity ofBlock(ContainerLevelAccess access, Block block) {
        if (access == null || block == null) {
            throw new IllegalArgumentException("ContainerLevelAccess and Block must not be null");
        }
        return player -> access.evaluate((level, pos) -> {
            if (!level.getBlockState(pos).is(block)) {
                return false;
            }
            return player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= BLOCK_REACH_SQR;
        }, true);
    }

    /**
     * 非方块 (远程) menu 有效性: 以自定义谓词裁决 (戒指远程开共享背包: 配偶/owner 校验 + 距离上限)。
     * 谓词须自行实现 owner/距离/时序校验 (服务端权威, 第六章/第十章服务端输入校验)。
     */
    static MenuValidity ofRemote(Predicate<Player> predicate) {
        if (predicate == null) {
            throw new IllegalArgumentException("remote validity predicate must not be null");
        }
        return predicate::test;
    }
}
