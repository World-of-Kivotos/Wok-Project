package com.miningdim.job.munitions.block;

/**
 * 军火台 WIDE 布局 (弹药流水线) 的几何常量: 轮廓箱 (静态件 + 运动件)、模型高度、运动件的活动范围、冲压火花的位置。
 * <p>
 * 由 tools/munitions_bench/generate_munitions_bench.mjs 按方块模型的同一份场景整个写出, 不要手改。
 * 刻意不依赖任何 Minecraft 类, 方块、方块实体、渲染器与 GameTest 都能直接用。
 * <p>
 * 坐标系: 朝北放置时的像素, x 东、y 上、z 南, 正面 z = 0。整台 (whole-machine) 坐标的原点在主格西北下角:
 * 主格 x 0..16 在站在正面的玩家右手边, 副格 x 16..32 在左手边。每格的碰撞箱用它自己的局部坐标 (0..16),
 * 副格局部 x = 整台 x - 16。别的朝向按 {@link #rotated} 绕格子中心转 (与方块状态的 y 旋转同向)。
 */
public final class MunitionsBenchGeometry {

    /** 台面 (钢台面顶面) 的 y (px); 台面以上都是设备。 */
    public static final float BODY_TOP_PX = 8.0F;
    /** 各档静态方块模型 (两格、待机与工作) 的最高点 (px), 下标 = 档位 0..5 (普通..闪耀), 与 MunitionsBenchAssets.TIER_IDS 同序。 */
    public static final float[] MODEL_TOP_PX = {22.5F, 22.5F, 22.5F, 22.5F, 22.5F, 24.0F};
    /** 静态轮廓箱 (两格) 的最高点 (px), 即主格碰撞柱的高度。 */
    public static final float SHAPE_TOP_PX = 22.5F;

    /**
     * 主格静态件的轮廓箱 (主格局部像素, 朝北), 每个 {x0, y0, z0, x1, y1, z1}。贴着静态的大块 (柜体台面、弹药箱与箱盖、皮带、
     * 压机、料斗、装填塔); 台面上的托盘、控制台、箱里冒出的弹头、各档加件的小灯与宝石是饰件, 不进轮廓。
     * 轮廓 (选择框 / 右键命中) = 这些盒子 + {@link #MAIN_PART_BOXES}; 碰撞是整格一块实心柱, 高到这些盒子的最高点
     * (见 MunitionsBenchBlock: 台面只有 8 px, 贴着模型的碰撞会让玩家一步跨上台面、站进运动件中间)。
     */
    public static final float[][] MAIN_BOXES = {
            {0.0F, 0.0F, 0.0F, 16.0F, 8.0F, 16.0F}, // body: 底座 + 柜体 + 台面
            {1.0F, 3.0F, 0.5F, 8.0F, 8.5F, 10.0F}, // can: 弹药箱
            {1.0F, 8.5F, 10.0F, 8.0F, 17.25F, 14.0F}, // lid: 掀开的箱盖
            {1.0F, 8.0F, 12.0F, 7.5F, 11.5F, 15.5F}, // spare: 备用弹药箱
            {8.0F, 8.0F, 5.0F, 16.0F, 10.5F, 10.0F}, // belt: 皮带 + 护栏
            {12.0F, 8.0F, 4.25F, 13.0F, 18.5F, 5.25F}, // press_posts_front: 压机前立柱
            {12.0F, 8.0F, 9.75F, 13.0F, 18.5F, 10.75F}, // press_posts_back: 压机后立柱
            {11.5F, 18.5F, 3.25F, 16.0F, 22.5F, 11.25F}, // press_head: 压机横梁 + 法兰 + 液压缸
    };
    /** 副格静态件的轮廓箱 (副格局部像素, 朝北; 副格局部 x = 整台 x - 16), 用法同 {@link #MAIN_BOXES}。 */
    public static final float[][] EXTENSION_BOXES = {
            {0.0F, 0.0F, 0.0F, 16.0F, 8.0F, 16.0F}, // body: 底座 + 柜体 + 台面
            {0.0F, 8.0F, 5.0F, 15.0F, 10.5F, 10.0F}, // belt: 皮带 + 护栏
            {0.0F, 8.0F, 4.25F, 1.0F, 18.5F, 5.25F}, // press_posts_front: 压机前立柱
            {0.0F, 8.0F, 9.75F, 1.0F, 18.5F, 10.75F}, // press_posts_back: 压机后立柱
            {0.0F, 18.5F, 3.25F, 1.5F, 20.5F, 11.25F}, // press_head: 压机横梁 + 法兰 + 液压缸
            {8.5F, 8.0F, 10.0F, 15.5F, 16.0F, 15.5F}, // hopper: 弹壳料斗
            {9.75F, 12.5F, 6.75F, 11.25F, 16.0F, 10.5F}, // chute: 落壳管
            {1.5F, 8.0F, 10.5F, 8.0F, 16.5F, 14.0F}, // turret: 装填塔
            {1.75F, 15.0F, 5.75F, 7.25F, 16.0F, 10.5F}, // arm: 装填塔横梁
    };

    /**
     * 运动件的轮廓箱 (主格局部像素, 朝北): 每个件在整个循环与待机里扫过的范围, 切到本格; 被别的盒子包住的已并进去。
     * 只进轮廓不进碰撞, 让皮带上的弹、冲头、底火冲杆与装药管都点得中台子 (否则右键会穿过它们打到后面的方块)。
     */
    public static final float[][] MAIN_PART_BOXES = {
            {13.5F, 9.0F, 6.5F, 16.0F, 12.5F, 8.5F}, // round_powder + round_powder_charged: 运动件扫过的范围
            {9.5F, 9.0F, 6.5F, 15.5F, 14.75F, 8.5F}, // round_seat_tipped + round_seat: 运动件扫过的范围
            {5.5F, 2.5F, 6.5F, 11.5F, 14.75F, 8.5F}, // drop: 运动件扫过的范围
            {14.0F, 16.5F, 7.0F, 15.0F, 20.5F, 8.0F}, // ram_rod: 运动件扫过的范围
            {13.25F, 14.75F, 6.25F, 15.75F, 18.5F, 8.75F}, // ram_die + ram_die_hot: 运动件扫过的范围
            {13.75F, 12.5F, 6.75F, 15.25F, 16.75F, 8.25F}, // ram_bullet: 运动件扫过的范围
    };
    /** 副格的运动件轮廓箱 (副格局部像素, 朝北), 用法同 {@link #MAIN_PART_BOXES}。 */
    public static final float[][] EXTENSION_PART_BOXES = {
            {5.5F, 9.0F, 6.5F, 11.5F, 12.5F, 8.5F}, // round_in: 运动件扫过的范围
            {1.5F, 9.0F, 6.5F, 7.5F, 12.5F, 8.5F}, // round_prime: 运动件扫过的范围
            {0.0F, 9.0F, 6.5F, 3.5F, 12.5F, 8.5F}, // round_powder + round_powder_charged: 运动件扫过的范围
            {6.0F, 12.5F, 7.0F, 7.0F, 16.5F, 8.0F}, // prime_rod: 运动件扫过的范围
            {2.0F, 12.5F, 7.0F, 3.0F, 16.5F, 8.0F}, // powder_tube: 运动件扫过的范围
    };

    /** 运动件 (方块实体渲染器画的件) 在整个循环与待机里扫过的范围, 整台坐标 {x, y, z} (px)。 */
    public static final float[] PARTS_MIN = {5.5F, 2.5F, 6.25F};
    public static final float[] PARTS_MAX = {27.5F, 20.5F, 8.75F};
    /** 运动件的最高点 (px), 方块实体渲染包围盒至少要到这里。 */
    public static final float RENDER_TOP_PX = 20.5F;

    /** 冲压火花的位置 (整台坐标 = 主格局部坐标, px): 压弹头位那发的壳口, 冲头在 MunitionsBenchProgram.STRIKE_TICK 压到这里。 */
    public static final float SPARK_X = 14.5F;
    public static final float SPARK_Y = 12.5F;
    public static final float SPARK_Z = 7.5F;

    /** 运动件贴图 textures/entity/munitions_bench_parts.png 的尺寸 (与 MunitionsBenchParts 的 LayerDefinition 相同)。 */
    public static final int PARTS_TEXTURE_WIDTH = 128;
    public static final int PARTS_TEXTURE_HEIGHT = 64;

    private MunitionsBenchGeometry() {
    }

    /**
     * 把一个格子局部的朝北盒子 {x0, y0, z0, x1, y1, z1} 转到别的朝向: quarterTurns = 俯视顺时针转的 90° 次数
     * (NORTH 0, EAST 1, SOUTH 2, WEST 3, 与方块状态的 "y": 90 / 180 / 270 相同), 绕格子中心 (8, 8) 转, 返回新数组。
     */
    public static float[] rotated(float[] box, int quarterTurns) {
        float x0 = box[0];
        float z0 = box[2];
        float x1 = box[3];
        float z1 = box[5];
        for (int i = 0; i < Math.floorMod(quarterTurns, 4); i++) {
            // (x, z) → (16 - z, x): 北面 (z = 0) 转到东面 (x = 16)
            float nx0 = 16.0F - z1;
            float nx1 = 16.0F - z0;
            z0 = x0;
            z1 = x1;
            x0 = nx0;
            x1 = nx1;
        }
        return new float[]{x0, box[1], z0, x1, box[4], z1};
    }
}
