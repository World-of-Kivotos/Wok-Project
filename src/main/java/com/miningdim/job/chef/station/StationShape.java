package com.miningdim.job.chef.station;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * 一台烹饪台在四个水平朝向下的选择框 (getShape) 与碰撞箱 (getCollisionShape)。
 *
 * 形状一律按 facing=north 的模型像素坐标书写 (模型正面画在 north, 与农夫乐事炉灶同约定), 构造时一次性
 * 顺时针转出 east/south/west 三份缓存, 与 blockstate 的 y=90/180/270 旋转一一对应; 运行期只查表不再计算。
 * 前后对称的形状用 {@link #symmetric} 声明, 四个朝向共用同一个对象。
 */
final class StationShape {

    private final Map<Direction, VoxelShape> outline;
    private final Map<Direction, VoxelShape> collision;

    private StationShape(Map<Direction, VoxelShape> outline, Map<Direction, VoxelShape> collision) {
        this.outline = outline;
        this.collision = collision;
    }

    /** 选择框与碰撞箱相同、且绕竖轴对称 (四个朝向都一样)。 */
    static StationShape symmetric(VoxelShape shape) {
        Map<Direction, VoxelShape> same = new EnumMap<>(Direction.class);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            same.put(direction, shape);
        }
        return new StationShape(same, same);
    }

    /** 选择框与碰撞箱相同, 按 north 书写、随朝向旋转。 */
    static StationShape rotated(VoxelShape north) {
        Map<Direction, VoxelShape> rotated = rotations(north);
        return new StationShape(rotated, rotated);
    }

    /**
     * 选择框与碰撞箱不同, 两者都随朝向旋转。例: 木质备餐台选择框整格, 碰撞箱整格再并上后沿调料架
     * (可以高出方块顶; 让它不是严格整格, 模型面才不会一律去取邻格的光)。
     */
    static StationShape rotated(VoxelShape northOutline, VoxelShape northCollision) {
        return new StationShape(rotations(northOutline), rotations(northCollision));
    }

    VoxelShape outline(Direction facing) {
        return outline.get(facing);
    }

    VoxelShape collision(Direction facing) {
        return collision.get(facing);
    }

    private static Map<Direction, VoxelShape> rotations(VoxelShape north) {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        VoxelShape current = north.optimize();
        shapes.put(Direction.NORTH, current);
        for (Direction direction : new Direction[]{Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            current = rotateClockwise(current);
            shapes.put(direction, current);
        }
        return shapes;
    }

    /** 俯视顺时针转 90 度: (x, z) -> (1 - z, x), 与 blockstate 的 y+90 一致 (north 面转到 east 面)。 */
    private static VoxelShape rotateClockwise(VoxelShape source) {
        VoxelShape[] result = {Shapes.empty()};
        source.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) ->
                result[0] = Shapes.or(result[0], Shapes.box(
                        1.0D - maxZ, minY, minX,
                        1.0D - minZ, maxY, maxX)));
        return result[0].optimize();
    }
}
