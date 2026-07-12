package com.miningdim.trap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 调试用静态陷阱落点核心 (供 {@code /mining trap place} 命令与 GameTest 直调, 不依赖命令上下文)。
 *
 * 与生产落点 ({@link TrapDisguiseConverter} 区块加载时批量转换) 分离: 本类只做"指定坐标放一块伪装矿石 + 登记陷阱身份"
 * 的单点操作, 用于运维/联调手动布一颗陷阱验证触发/探测/连锁全链路。写入口径与转换器一致 —— 世界里放的是真原版伪装矿石
 * ({@link TrapDisguise#isDisguiseOre} 认可的方块), 陷阱身份 (坐标 -> {@link StaticTrapKind}) 只进 {@link TrapRegistry},
 * 故放下的陷阱同样协议级不可识破。
 */
public final class TrapDebugPlacement {

    private TrapDebugPlacement() {
    }

    /**
     * 在 pos 放一块伪装矿石 skin 并把该坐标登记为 kind 陷阱。仅服务端主线程调用 (世界写 + SavedData 写)。
     *
     * skin 必须属 {@link TrapDisguise#disguiseBlocks} 伪装矿石集合 —— 否则放下的方块会被 {@link StaticTrapTrigger}
     * 的幽灵守卫在挖到时判为幽灵条目而不触发 (陷阱无效)。故此处以 {@link TrapDisguise#isDisguiseOre} 为契约前置校验,
     * 非法 skin 直接抛 {@link IllegalArgumentException} (调用方是命令入口层 / 测试, 由其捕获转失败文案或断言; 不静默放行)。
     *
     * @param level 承载 {@link TrapRegistry} 的矿洞服务端世界
     * @param pos   落点 (通常为玩家准星指向的方块)
     * @param kind  陷阱种类
     * @param skin  伪装矿石方块态 (须为合法伪装矿石)
     * @return 实际写入的伪装矿石方块态 (供命令回显 / 测试断言)
     */
    public static BlockState place(ServerLevel level, BlockPos pos, StaticTrapKind kind, BlockState skin) {
        if (!TrapDisguise.isDisguiseOre(skin)) {
            throw new IllegalArgumentException(
                    "trap skin " + skin.getBlock() + " is not a disguise ore (must be one of TrapDisguise.disguiseBlocks)");
        }
        level.setBlock(pos, skin, Block.UPDATE_ALL);
        TrapRegistry.get(level).put(pos.immutable(), kind);
        return skin;
    }
}
