package com.miningdim.job.chef.station;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * 九台烹饪台工作中 (lit=true) 的客户端粒子, 只用原版粒子。
 *
 * 出生点逐台取自 station-preview 各方案 meta.json 的说明, 按 facing=north 的模型像素坐标 (0..16, 可越界)
 * 书写, 由 {@link #emit} 随朝向旋转到世界坐标; 速度的水平分量同样旋转。方案说明里平视时粒子是远处认出
 * "正在炸/烤"的主要信号, 所以每次 animateTick 都出一小簇 (原版只对玩家附近的方块随机调用 animateTick,
 * 两三格内约每秒两次)。待机 (lit=false) 一律不出粒子, 由 {@link CookingStationBlock} 把关。
 *
 * 蒸汽用 POOF 而不用 CLOUD: 原版 CLOUD 是 PlayerCloudParticle, 两格内有玩家时会被拽到玩家脚底的高度,
 * 人站在灶台前看到的"蒸汽"反而往下沉。冷气用会缓缓下落的 SNOWFLAKE。
 */
final class StationParticles {

    /** 方块 animateTick 的粒子回调; facing 已从方块状态取出。 */
    @FunctionalInterface
    interface Emitter {
        void animate(Level level, BlockPos pos, Direction facing, RandomSource random);
    }

    /** 油星: 小而亮的黄点 (炸锅三台共用)。 */
    private static final DustParticleOptions OIL_SPARK = new DustParticleOptions(new Vector3f(1.0F, 0.86F, 0.36F), 0.55F);

    /** 木质备餐台: 方案没有要求粒子, 工作状态只靠菜刀摇切的动画贴图表现。 */
    static final Emitter NONE = (level, pos, facing, random) -> {
    };

    /**
     * 炉上油锅: 油面 y≈7, 油星和白色蒸汽, 炸篮范围 (工作中模型 x4..12, z5..12) 里更密。
     * 放在营火托架上时模型坐标不变, 同一套出生点照用。
     */
    static final Emitter STOVETOP_FRYER = (level, pos, facing, random) -> {
        for (int i = 0; i < 3; i++) {
            emit(level, pos, facing, 4.0D + random.nextDouble() * 8.0D, 7.2D, 5.0D + random.nextDouble() * 7.0D,
                    OIL_SPARK, 0.0D, 0.0D, 0.0D);
        }
        emit(level, pos, facing, 3.0D + random.nextDouble() * 10.0D, 7.2D, 3.0D + random.nextDouble() * 10.0D,
                OIL_SPARK, 0.0D, 0.0D, 0.0D);
        if (random.nextInt(2) == 0) {
            emit(level, pos, facing, 5.0D + random.nextDouble() * 6.0D, 7.5D, 6.0D + random.nextDouble() * 5.0D,
                    ParticleTypes.POOF, 0.0D, 0.02D, 0.0D);
        }
    };

    /** 商用炸炉: 油星从两只炸篮的油面 (4, 10.2, 7.5) / (12, 10.2, 7.5), 热气从背板顶排烟格栅 (8, 16, 14.5)。 */
    static final Emitter COMMERCIAL_FRYER = (level, pos, facing, random) -> {
        for (int i = 0; i < 2; i++) {
            double basketX = random.nextBoolean() ? 4.0D : 12.0D;
            emit(level, pos, facing, basketX + (random.nextDouble() - 0.5D) * 4.0D, 10.2D,
                    7.5D + (random.nextDouble() - 0.5D) * 6.0D, OIL_SPARK, 0.0D, 0.0D, 0.0D);
        }
        if (random.nextInt(2) == 0) {
            emit(level, pos, facing, 8.0D + (random.nextDouble() - 0.5D) * 10.0D, 16.0D, 14.5D,
                    ParticleTypes.POOF, 0.0D, 0.02D, 0.0D);
        }
    };

    /**
     * 中式炸灶 (方案说明未给坐标, 按模型取): 铁锅油面 y=13 (锅沿 y15, 油面低 2 像素) 冒油星和蒸汽;
     * 灶口 (x5..11, 炭床 y1..2) 窜火苗, 门楣下偶尔一缕烟。
     */
    static final Emitter CHINESE_FRYER = (level, pos, facing, random) -> {
        for (int i = 0; i < 2; i++) {
            emit(level, pos, facing, 4.0D + random.nextDouble() * 8.0D, 13.2D, 4.0D + random.nextDouble() * 8.0D,
                    OIL_SPARK, 0.0D, 0.0D, 0.0D);
        }
        if (random.nextInt(2) == 0) {
            emit(level, pos, facing, 5.0D + random.nextDouble() * 6.0D, 13.5D, 5.0D + random.nextDouble() * 6.0D,
                    ParticleTypes.POOF, 0.0D, 0.02D, 0.0D);
        }
        if (random.nextInt(3) == 0) {
            emit(level, pos, facing, 6.0D + random.nextDouble() * 4.0D, 2.5D, 1.5D,
                    ParticleTypes.SMALL_FLAME, 0.0D, 0.01D, 0.0D);
        }
        if (random.nextInt(6) == 0) {
            emit(level, pos, facing, 8.0D, 6.0D, -0.2D, ParticleTypes.SMOKE, 0.0D, 0.02D, -0.01D);
        }
    };

    /** 砖砌烤炉: 烟囱冒烟 (说明给的出生点 (0.5, 1.27, 0.75) 格 = (8, 20.3, 12) 像素), 炉口偶尔蹦火星。 */
    static final Emitter BRICK_OVEN = (level, pos, facing, random) -> {
        emit(level, pos, facing, 7.0D + random.nextDouble() * 2.0D, 20.3D, 11.0D + random.nextDouble() * 2.0D,
                ParticleTypes.SMOKE, 0.0D, 0.04D, 0.0D);
        if (random.nextInt(4) == 0) {
            emit(level, pos, facing, 8.0D, 20.5D, 12.0D, ParticleTypes.LARGE_SMOKE, 0.0D, 0.03D, 0.0D);
        }
        if (random.nextInt(8) == 0) {
            emit(level, pos, facing, 6.0D + random.nextDouble() * 4.0D, 6.0D, 0.5D, ParticleTypes.LAVA, 0.0D, 0.0D, 0.0D);
        }
    };

    /** 铸铁烤箱灶: 背板顶排气栅 (8, 16, 14.5) 出热气, 门顶排气缝 (8, 9.5, -0.2) 偶尔向前冒一缕。 */
    static final Emitter CAST_IRON_RANGE = (level, pos, facing, random) -> {
        if (random.nextInt(3) != 0) {
            emit(level, pos, facing, 8.0D + (random.nextDouble() - 0.5D) * 10.0D, 16.0D, 14.5D,
                    ParticleTypes.SMOKE, 0.0D, 0.03D, 0.0D);
        }
        if (random.nextInt(8) == 0) {
            emit(level, pos, facing, 8.0D + (random.nextDouble() - 0.5D) * 8.0D, 9.5D, -0.2D,
                    ParticleTypes.POOF, 0.0D, 0.0D, -0.02D);
        }
    };

    /** 吊炉 (说明只写了"烟口冒烟、炉口偶尔飘火星"): 烟口顶 (8, 16, 8), 拱形炉口炭床前沿 (8, 3, 1)。 */
    static final Emitter HANGING_OVEN = (level, pos, facing, random) -> {
        emit(level, pos, facing, 7.0D + random.nextDouble() * 2.0D, 16.0D, 7.0D + random.nextDouble() * 2.0D,
                ParticleTypes.SMOKE, 0.0D, 0.04D, 0.0D);
        if (random.nextInt(4) == 0) {
            emit(level, pos, facing, 8.0D, 16.2D, 8.0D, ParticleTypes.LARGE_SMOKE, 0.0D, 0.03D, 0.0D);
        }
        if (random.nextInt(8) == 0) {
            emit(level, pos, facing, 6.0D + random.nextDouble() * 4.0D, 3.0D, 1.0D, ParticleTypes.LAVA, 0.0D, 0.0D, 0.0D);
        }
    };

    /**
     * 冷藏备餐台: 掀盖后从食材格 (8, 14, 11) 冒冷气, 冷柜门缝 (门把手一侧, 约 (10, 2.5, -0.1)) 偶尔飘一缕;
     * 两处都往下沉。
     */
    static final Emitter REFRIGERATED_PREP = (level, pos, facing, random) -> {
        if (random.nextInt(2) == 0) {
            emit(level, pos, facing, 8.0D + (random.nextDouble() - 0.5D) * 10.0D, 14.0D,
                    11.0D + (random.nextDouble() - 0.5D) * 6.0D, ParticleTypes.SNOWFLAKE, 0.0D, -0.01D, 0.0D);
        }
        if (random.nextInt(10) == 0) {
            emit(level, pos, facing, 10.0D, 2.5D, -0.1D, ParticleTypes.SNOWFLAKE, 0.0D, -0.01D, -0.01D);
        }
    };

    /** 中式案台: 蒸笼热气从笼盖翘起的西沿 (10, 21, 12) 附近往上飘。 */
    static final Emitter CHINESE_PREP = (level, pos, facing, random) -> {
        if (random.nextInt(3) != 0) {
            emit(level, pos, facing, 10.0D + random.nextDouble(), 21.0D, 12.0D + (random.nextDouble() - 0.5D) * 3.0D,
                    ParticleTypes.POOF, 0.0D, 0.02D, 0.0D);
        }
    };

    private StationParticles() {
    }

    /** 以 facing=north 的模型像素坐标放一颗粒子: 坐标与水平速度都按朝向旋转 (见 {@link #rotate})。 */
    static void emit(Level level, BlockPos pos, Direction facing, double px, double py, double pz,
                     ParticleOptions particle, double vx, double vy, double vz) {
        Vec3 at = rotate(facing, px, py, pz);
        Vec3 velocity = rotateVector(facing, vx, vy, vz);
        level.addParticle(particle,
                pos.getX() + at.x / 16.0D, pos.getY() + at.y / 16.0D, pos.getZ() + at.z / 16.0D,
                velocity.x, velocity.y, velocity.z);
    }

    /**
     * north 朝向的像素坐标转到给定朝向: 每个四分之一圈按俯视顺时针 (x, z) -> (16 - z, x),
     * 与 blockstate 的 y=90/180/270 及 {@link StationShape} 的形状旋转同一口径。
     */
    static Vec3 rotate(Direction facing, double px, double py, double pz) {
        double x = px;
        double z = pz;
        for (int turn = 0; turn < quarterTurns(facing); turn++) {
            double previousX = x;
            x = 16.0D - z;
            z = previousX;
        }
        return new Vec3(x, py, z);
    }

    private static Vec3 rotateVector(Direction facing, double vx, double vy, double vz) {
        double x = vx;
        double z = vz;
        for (int turn = 0; turn < quarterTurns(facing); turn++) {
            double previousX = x;
            x = -z;
            z = previousX;
        }
        return new Vec3(x, vy, z);
    }

    /** north=0, east=1, south=2, west=3 (2D 数据值是 south=0, west=1, north=2, east=3)。 */
    private static int quarterTurns(Direction facing) {
        return (facing.get2DDataValue() + 2) & 3;
    }
}
