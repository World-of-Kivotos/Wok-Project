package com.miningdim.power;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * 电力方块 LIT 显示态的统一收口。两条纪律都是被真机现象逼出来的：
 *
 * 一、迟滞。LIT 表达的是"这台设备在运转"，不是"这一 tick 恰好有电"。供电落在临界时设备会攒够一 tick
 * 的量跑一格进度、跑完又不够，若 LIT 直接等于当 tick 的供电判定，它就每一两 tick 翻一次 —— 玩家看到的
 * 就是方块在抽搐。宽限期只覆盖"缺电停顿"这类瞬态；真正停机（无配方、产物槽满）必须立刻熄灭，否则灯会
 * 骗人。
 *
 * 二、不通知邻居。LIT 是纯视觉属性，只用 {@link Block#UPDATE_CLIENTS}。原先走 flag 3 带上 UPDATE_NEIGHBORS，
 * 每次翻转都会触发相邻线缆的 neighborChanged -> markEndpointsDirty，把电网端点重扫拖进每 tick 结算路径；
 * 设备一抽搐，代价就摊到整张网上。
 */
public final class PowerLitDisplay {

    /** 熄灭前的宽限 tick 数。1 秒：长到盖住供电临界的锯齿，短到停机后不会让玩家觉得灯卡住。 */
    public static final int GRACE_TICKS = 20;

    private PowerLitDisplay() {
    }

    /** 把目标显示态写进 blockstate；与当前一致时不写，避免无意义的方块更新。 */
    public static void apply(Level level, BlockPos pos, BlockState state, BooleanProperty lit, boolean active) {
        if (level == null || state.getValue(lit) == active) {
            return;
        }
        level.setBlock(pos, state.setValue(lit, active), Block.UPDATE_CLIENTS);
    }
}
