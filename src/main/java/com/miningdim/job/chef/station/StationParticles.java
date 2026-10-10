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
 * 书写。发射器只往 {@link Sink} 里报"north 坐标系下的像素坐标与速度", 不碰世界; 由 {@link #into} 给出的
 * Sink 随朝向旋转到世界坐标再 addParticle (速度的水平分量同样旋转)。这样 GameTest 能换一个收集用的 Sink,
 * 直接核对各台的出生点 (例如往下落的冷气不能出生在碰撞箱里, 否则会被卡住看不见)。
 *
 * 方案说明里平视时粒子是远处认出"正在炸/烤"的主要信号, 所以每次 animateTick 都出一小簇 (原版只对玩家
 * 附近的方块随机调用 animateTick, 两三格内约每秒两次)。待机 (lit=false) 一律不出粒子, 由
 * {@link CookingStationBlock} 把关。
 *
 * 蒸汽用 POOF 而不用 CLOUD: 原版 CLOUD 是 PlayerCloudParticle, 两格内有玩家时会被拽到玩家脚底的高度,
 * 人站在灶台前看到的"蒸汽"反而往下沉。冷气用会缓缓下落的 SNOWFLAKE。
 */
final class StationParticles {

    /** 一颗粒子: 坐标是 facing=north 的模型像素坐标, 速度是 north 坐标系下每 tick 的格数。 */
    @FunctionalInterface
    interface Sink {
        void add(ParticleOptions particle, double px, double py, double pz, double vx, double vy, double vz);
    }

    /** 方块 animateTick 的粒子回调: 往 sink 里报这一次要出的粒子。 */
    @FunctionalInterface
    interface Emitter {
        void animate(Sink sink, RandomSource random);
    }

    /** 油星: 小而亮的黄点 (炸锅三台共用)。 */
    private static final DustParticleOptions OIL_SPARK = new DustParticleOptions(new Vector3f(1.0F, 0.86F, 0.36F), 0.55F);

    /** 木质备餐台: 方案没有要求粒子, 工作状态只靠菜刀摇切的动画贴图表现。 */
    static final Emitter NONE = (sink, random) -> {
    };

    /**
     * 炉上油锅: 油面 y≈7, 油星和白色蒸汽, 炸篮范围 (工作中模型 x4..12, z5..12) 里更密。
     * 放在营火托架上时模型坐标不变, 同一套出生点照用。
     */
    static final Emitter STOVETOP_FRYER = (sink, random) -> {
        for (int i = 0; i < 3; i++) {
            sink.add(OIL_SPARK, 4.0D + random.nextDouble() * 8.0D, 7.2D, 5.0D + random.nextDouble() * 7.0D,
                    0.0D, 0.0D, 0.0D);
        }
        sink.add(OIL_SPARK, 3.0D + random.nextDouble() * 10.0D, 7.2D, 3.0D + random.nextDouble() * 10.0D,
                0.0D, 0.0D, 0.0D);
        if (random.nextInt(2) == 0) {
            sink.add(ParticleTypes.POOF, 5.0D + random.nextDouble() * 6.0D, 7.5D, 6.0D + random.nextDouble() * 5.0D,
                    0.0D, 0.02D, 0.0D);
        }
    };

    /** 商用炸炉: 油星从两只炸篮的油面 (4, 10.2, 7.5) / (12, 10.2, 7.5), 热气从背板顶排烟格栅 (8, 16, 14.5)。 */
    static final Emitter COMMERCIAL_FRYER = (sink, random) -> {
        for (int i = 0; i < 2; i++) {
            double basketX = random.nextBoolean() ? 4.0D : 12.0D;
            sink.add(OIL_SPARK, basketX + (random.nextDouble() - 0.5D) * 4.0D, 10.2D,
                    7.5D + (random.nextDouble() - 0.5D) * 6.0D, 0.0D, 0.0D, 0.0D);
        }
        if (random.nextInt(2) == 0) {
            sink.add(ParticleTypes.POOF, 8.0D + (random.nextDouble() - 0.5D) * 10.0D, 16.0D, 14.5D,
                    0.0D, 0.02D, 0.0D);
        }
    };

    /**
     * 中式炸灶 (方案说明未给坐标, 按模型取): 铁锅油面 y=13 (锅沿 y15, 油面低 2 像素) 冒油星和蒸汽;
     * 灶口 (x5..11, 炭床 y1..2) 窜火苗, 门楣下偶尔一缕烟。
     */
    static final Emitter CHINESE_FRYER = (sink, random) -> {
        for (int i = 0; i < 2; i++) {
            sink.add(OIL_SPARK, 4.0D + random.nextDouble() * 8.0D, 13.2D, 4.0D + random.nextDouble() * 8.0D,
                    0.0D, 0.0D, 0.0D);
        }
        if (random.nextInt(2) == 0) {
            sink.add(ParticleTypes.POOF, 5.0D + random.nextDouble() * 6.0D, 13.5D, 5.0D + random.nextDouble() * 6.0D,
                    0.0D, 0.02D, 0.0D);
        }
        if (random.nextInt(3) == 0) {
            sink.add(ParticleTypes.SMALL_FLAME, 6.0D + random.nextDouble() * 4.0D, 2.5D, 1.5D, 0.0D, 0.01D, 0.0D);
        }
        if (random.nextInt(6) == 0) {
            sink.add(ParticleTypes.SMOKE, 8.0D, 6.0D, -0.2D, 0.0D, 0.02D, -0.01D);
        }
    };

    /** 砖砌烤炉: 烟囱冒烟 (说明给的出生点 (0.5, 1.27, 0.75) 格 = (8, 20.3, 12) 像素), 炉口偶尔蹦火星。 */
    static final Emitter BRICK_OVEN = (sink, random) -> {
        sink.add(ParticleTypes.SMOKE, 7.0D + random.nextDouble() * 2.0D, 20.3D, 11.0D + random.nextDouble() * 2.0D,
                0.0D, 0.04D, 0.0D);
        if (random.nextInt(4) == 0) {
            sink.add(ParticleTypes.LARGE_SMOKE, 8.0D, 20.5D, 12.0D, 0.0D, 0.03D, 0.0D);
        }
        if (random.nextInt(8) == 0) {
            sink.add(ParticleTypes.LAVA, 6.0D + random.nextDouble() * 4.0D, 6.0D, 0.5D, 0.0D, 0.0D, 0.0D);
        }
    };

    /** 铸铁烤箱灶: 背板顶排气栅 (8, 16, 14.5) 出热气, 门顶排气缝 (8, 9.5, -0.2) 偶尔向前冒一缕。 */
    static final Emitter CAST_IRON_RANGE = (sink, random) -> {
        if (random.nextInt(3) != 0) {
            sink.add(ParticleTypes.SMOKE, 8.0D + (random.nextDouble() - 0.5D) * 10.0D, 16.0D, 14.5D,
                    0.0D, 0.03D, 0.0D);
        }
        if (random.nextInt(8) == 0) {
            sink.add(ParticleTypes.POOF, 8.0D + (random.nextDouble() - 0.5D) * 8.0D, 9.5D, -0.2D,
                    0.0D, 0.0D, -0.02D);
        }
    };

    /** 吊炉 (说明只写了"烟口冒烟、炉口偶尔飘火星"): 烟口顶 (8, 16, 8), 拱形炉口炭床前沿 (8, 3, 1)。 */
    static final Emitter HANGING_OVEN = (sink, random) -> {
        sink.add(ParticleTypes.SMOKE, 7.0D + random.nextDouble() * 2.0D, 16.0D, 7.0D + random.nextDouble() * 2.0D,
                0.0D, 0.04D, 0.0D);
        if (random.nextInt(4) == 0) {
            sink.add(ParticleTypes.LARGE_SMOKE, 8.0D, 16.2D, 8.0D, 0.0D, 0.03D, 0.0D);
        }
        if (random.nextInt(8) == 0) {
            sink.add(ParticleTypes.LAVA, 6.0D + random.nextDouble() * 4.0D, 3.0D, 1.0D, 0.0D, 0.0D, 0.0D);
        }
    };

    /**
     * 冷藏备餐台: 掀盖后食材格 (说明给的 (8, 14, 11)) 冒冷气, 冷柜门缝 (门把手一侧, 约 (10, 2.5, -0.1)) 偶尔飘一缕;
     * 两处都往下沉。
     *
     * 食材格那一簇不能照说明的坐标原样出: (8, 14, 11) 落在碰撞箱后半块 box(0,12,7,16,16,16) 里, SNOWFLAKE 有物理
     * 碰撞, 往下一落就撞上 y12 的下一层盒子、被标成 stoppedByCollision, 卡在冷藏槽模型里看不见。所以出生点抬到
     * 冷藏槽顶面 (y16) 以上, 同一片食材格范围 (x3..13, z9..14), 再给一点往前 (-z) 的漂移, 让冷气越过 z7 的台沿,
     * 沉到 y12 的前半台面上, "往下沉"才看得见。具体数值待实机微调。
     */
    static final Emitter REFRIGERATED_PREP = (sink, random) -> {
        if (random.nextInt(2) == 0) {
            sink.add(ParticleTypes.SNOWFLAKE, 8.0D + (random.nextDouble() - 0.5D) * 10.0D, 16.2D,
                    9.0D + random.nextDouble() * 5.0D, 0.0D, -0.005D, -0.03D);
        }
        if (random.nextInt(10) == 0) {
            sink.add(ParticleTypes.SNOWFLAKE, 10.0D, 2.5D, -0.1D, 0.0D, -0.01D, -0.01D);
        }
    };

    /** 中式案台: 蒸笼热气从笼盖翘起的西沿 (10, 21, 12) 附近往上飘。 */
    static final Emitter CHINESE_PREP = (sink, random) -> {
        if (random.nextInt(3) != 0) {
            sink.add(ParticleTypes.POOF, 10.0D + random.nextDouble(), 21.0D, 12.0D + (random.nextDouble() - 0.5D) * 3.0D,
                    0.0D, 0.02D, 0.0D);
        }
    };

    private StationParticles() {
    }

    /** 往世界里放粒子的 Sink: north 像素坐标与水平速度按 facing 旋转 (见 {@link #rotate}) 后 addParticle。 */
    static Sink into(Level level, BlockPos pos, Direction facing) {
        return (particle, px, py, pz, vx, vy, vz) -> {
            Vec3 at = rotate(facing, px, py, pz);
            Vec3 velocity = rotateVector(facing, vx, vy, vz);
            level.addParticle(particle,
                    pos.getX() + at.x / 16.0D, pos.getY() + at.y / 16.0D, pos.getZ() + at.z / 16.0D,
                    velocity.x, velocity.y, velocity.z);
        };
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

    static Vec3 rotateVector(Direction facing, double vx, double vy, double vz) {
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
