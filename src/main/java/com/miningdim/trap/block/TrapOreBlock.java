package com.miningdim.trap.block;

import com.miningdim.trap.StaticTrapKind;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * 静态陷阱触发方块 (方案 C, 取代已废弃的离线体素布点)。伪装成石层/矿石, 挖到时由 {@link com.miningdim.trap.StaticTrapTrigger}
 * 按 {@link #KIND} 分发对应陷阱效果 (爆炸/岩浆/落石), 效果一律用矿脉抗性可识别的原版环境伤类型。
 *
 * 单块承载 4 个静态陷阱种类 (KIND 属性), 由四个 {@code minecraft:ore} 型 configured_feature 各自指定一种 KIND 值,
 * 在真实世界石层散布 (同 ore_emerald 的 vanilla-noise 布点)。故区块生成即天然带上陷阱, 无需离线体素表、无需分帧落方块、
 * 重置随区块重生 —— 规避方案 A 的持久化/幂等/线程负担。
 *
 * 无掉落 (properties.noLootTable): 挖到即触发效果, 不进背包 (伪装块不作为物品存在)。硬度 copy STONE (镐可挖)。
 */
public final class TrapOreBlock extends Block {

    /** 静态陷阱种类; 由 configured_feature 布点时指定, 触发/探测据此分发。 */
    public static final EnumProperty<StaticTrapKind> KIND = EnumProperty.create("kind", StaticTrapKind.class);

    public TrapOreBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any().setValue(KIND, StaticTrapKind.FAKE_ORE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(KIND);
    }
}
