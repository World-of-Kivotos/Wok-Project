package com.miningdim.job.munitions.block;

/**
 * 军火台 WIDE 布局 (弹药流水线) 的几何常量: 轮廓箱 (静态件 + 运动件)、模型高度、运动件的活动范围、冲压火花的位置,
 * 以及弹药箱计数屏 (COUNTER_*) 的显示面、布局与每档颜色。
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
            {0.5F, 8.25F, 10.0F, 8.5F, 17.25F, 14.25F}, // lid: 掀开的箱盖 (计数屏)
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

    // ---- 弹药箱计数屏 (方案 C): 掀开的箱盖内面一块凹窗 (上 满度条, 下 4x7 大字发数), 口径是箱身正面的黄漆模板字 ----
    // 布局与 tools/munitions_bench/counter.mjs 同一份 (生成器写出后解析回来核对); 字形、排版、格式在 MunitionsBenchCounter。
    /** 布局单位 (px): 1 qt = 0.25 px, 即箱盖 4 倍贴图的一个贴图像素。下面的 *_QT 都以它为单位, 面上 u 向右 (北面 = -x)、v 向下。 */
    public static final float COUNTER_QT_PX = 0.25F;
    /** 字浮在窗面 / 箱身外的距离 (px, 1/128 格): 离面够远, 渲染器画字的距离内 24 位深度不会闪; 仍在框条前沿之后。 */
    public static final float COUNTER_LIFT_PX = 0.125F;
    /** 屏窗凹进框条前沿的深度 (px)。 */
    public static final float COUNTER_RECESS_PX = 0.25F;
    /** 相机离主格中心超过这么多格就不画字 (读不出, 也避开远处的深度精度)。 */
    public static final float COUNTER_MAX_DISTANCE_BLOCKS = 24.0F;
    /** 窗面 (箱盖里凹进去的那块本体的北面) 左上角, 整台坐标 (px, 朝北, 箱盖旋转之前); 从正面看的左 = 东 = +x。 */
    public static final float[] COUNTER_WINDOW_FACE_TOP_LEFT = {8.5F, 17.5F, 10.25F};
    /** 箱盖内面的尺寸 {宽, 高} (qt)。 */
    public static final int[] COUNTER_LID_FACE_QT = {32, 36};
    /** 箱盖的旋转 (与静态 JSON 的 can_lid 元素相同): 绕 x 轴 +COUNTER_LID_ROTATION_X_DEGREES 度, 原点 (px)。 */
    public static final float[] COUNTER_LID_ROTATION_ORIGIN = {4.5F, 8.5F, 10.5F};
    public static final float COUNTER_LID_ROTATION_X_DEGREES = 22.5F;
    /** 屏窗 {x, y, w, h} (qt, 箱盖内面左上角起)。 */
    public static final int[] COUNTER_WINDOW_QT = {4, 10, 24, 12};
    /** 满度条 {x, y, w, h} (qt): 亮的格数 = MunitionsBenchCounter.barCells, 从左往右。 */
    public static final int[] COUNTER_BAR_QT = {5, 11, 22, 2};
    /** 发数 {x, y, w} (qt): 4x7 字, 右对齐在 [x, x + w) 里; 字体一个像素占 COUNTER_COUNT_TEXEL_QT 个 qt。 */
    public static final int[] COUNTER_COUNT_QT = {5, 14, 22};
    public static final int COUNTER_COUNT_TEXEL_QT = 1;
    /** 箱身正面 (口径模板字) 左上角 (px, 不旋转, 不凹) 与尺寸 {宽, 高} (qt)。 */
    public static final float[] COUNTER_STENCIL_FACE_TOP_LEFT = {8.0F, 8.5F, 1.0F};
    public static final int[] COUNTER_STENCIL_FACE_QT = {28, 22};
    /** 口径 3x5 模板字: 顶行 y (qt), 水平居中在箱身正面; 字体一个像素占 COUNTER_STENCIL_TEXEL_QT 个 qt。 */
    public static final int COUNTER_STENCIL_Y_QT = 8;
    public static final int COUNTER_STENCIL_TEXEL_QT = 1;
    /**
     * 颜色 0xRRGGBB, 下标 [档位 0..5][角色 × 2 + (待机 ? 1 : 0)], 角色 = MunitionsBenchCounter.ROLE_* (发数 / 空箱的 0 / 满度条 /
     * 满仓的满度条 / 箱身模板字), 由 tiers.mjs 的档位灯色推出 (counter.mjs counterColours)。
     */
    public static final int[][] COUNTER_COLOURS = {
            {0x60F4F0, 0x50CECF, 0x2A8F9B, 0x2B808C, 0x24C8D6, 0x26AFBD, 0xFFB234, 0xE29228, 0xF4C22C, 0xF4C22C}, // 档位 0
            {0x73D291, 0x63B680, 0x458F61, 0x42805B, 0x54C879, 0x4EAF6F, 0xFFB234, 0xE29228, 0xF4C22C, 0xF4C22C}, // 档位 1
            {0x78AEEC, 0x6897CE, 0x4973A6, 0x456994, 0x5A9CE8, 0x538BCC, 0xFFB234, 0xE29228, 0xF4C22C, 0xF4C22C}, // 档位 2
            {0xB686E6, 0x9D75C9, 0x7856A1, 0x6C5191, 0xA66CE0, 0x9263C5, 0xFFB234, 0xE29228, 0xF4C22C, 0xF4C22C}, // 档位 3
            {0xE67179, 0xC7636B, 0x9B464F, 0x8A434C, 0xE0525C, 0xC34D57, 0xFFB234, 0xE29228, 0xF4C22C, 0xF4C22C}, // 档位 4
            {0xDE8CFF, 0xC07ADF, 0x965BB4, 0x8554A1, 0xD773FF, 0xBB69DF, 0xFFB234, 0xE29228, 0xF4C22C, 0xF4C22C}, // 档位 5
    };

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
