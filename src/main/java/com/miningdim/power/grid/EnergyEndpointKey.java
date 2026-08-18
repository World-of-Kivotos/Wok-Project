package com.miningdim.power.grid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Objects;

/**
 * 一条线缆与外部能量能力相接的唯一端点。方向属于键的一部分，使同一方块的多个能源面不会互相覆盖。
 */
public record EnergyEndpointKey(BlockPos pos, Direction direction) implements Comparable<EnergyEndpointKey> {

    public EnergyEndpointKey {
        pos = Objects.requireNonNull(pos, "pos").immutable();
        direction = Objects.requireNonNull(direction, "direction");
    }

    @Override
    public int compareTo(EnergyEndpointKey other) {
        int x = Integer.compare(pos.getX(), other.pos.getX());
        if (x != 0) {
            return x;
        }
        int y = Integer.compare(pos.getY(), other.pos.getY());
        if (y != 0) {
            return y;
        }
        int z = Integer.compare(pos.getZ(), other.pos.getZ());
        if (z != 0) {
            return z;
        }
        return Integer.compare(direction.get3DDataValue(), other.direction.get3DDataValue());
    }
}
