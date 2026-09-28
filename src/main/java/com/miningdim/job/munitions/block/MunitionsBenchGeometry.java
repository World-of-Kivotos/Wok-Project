package com.miningdim.job.munitions.block;

/**
 * 军火台 WIDE 布局 (弹药流水线) 的几何常量: 轮廓箱 (静态件 + 运动件)、模型高度、运动件的活动范围、冲压火花的位置,
 * 弹药箱计数屏 (COUNTER_*) 的显示面、布局与每档颜色, 以及运行灯效 (LIGHT_*) 的目标面、时间、透明度与每档颜色。
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
            {9.75F, 11.5F, 6.75F, 11.25F, 16.0F, 10.5F}, // chute: 落壳管
            {1.5F, 8.0F, 10.5F, 8.0F, 16.5F, 14.0F}, // turret: 装填塔
            {1.75F, 15.0F, 5.75F, 7.25F, 16.0F, 10.5F}, // arm: 装填塔横梁
    };

    /**
     * 运动件的轮廓箱 (主格局部像素, 朝北): 每个件在整个循环与待机里扫过的范围, 切到本格; 被别的盒子包住的已并进去。
     * 只进轮廓不进碰撞, 让皮带上的弹、冲头、底火冲杆与装药管都点得中台子 (否则右键会穿过它们打到后面的方块)。
     */
    public static final float[][] MAIN_PART_BOXES = {
            {13.75F, 9.0F, 6.75F, 16.0F, 11.5F, 8.25F}, // round_powder + round_powder_charged: 运动件扫过的范围
            {9.75F, 9.0F, 6.75F, 15.25F, 13.0F, 8.25F}, // round_seat_tipped + round_seat: 运动件扫过的范围
            {5.75F, 4.25F, 6.75F, 11.25F, 13.0F, 8.25F}, // drop: 运动件扫过的范围
            {14.0F, 14.75F, 7.0F, 15.0F, 22.25F, 8.0F}, // ram_rod: 运动件扫过的范围
            {13.25F, 13.0F, 6.25F, 15.75F, 18.5F, 8.75F}, // ram_die + ram_die_hot: 运动件扫过的范围
            {14.0F, 11.5F, 7.0F, 15.0F, 16.75F, 8.0F}, // ram_bullet: 运动件扫过的范围
    };
    /** 副格的运动件轮廓箱 (副格局部像素, 朝北), 用法同 {@link #MAIN_PART_BOXES}。 */
    public static final float[][] EXTENSION_PART_BOXES = {
            {5.75F, 9.0F, 6.75F, 11.25F, 11.5F, 8.25F}, // round_in: 运动件扫过的范围
            {1.75F, 9.0F, 6.75F, 7.25F, 11.5F, 8.25F}, // round_prime: 运动件扫过的范围
            {0.0F, 9.0F, 6.75F, 3.25F, 11.5F, 8.25F}, // round_powder + round_powder_charged: 运动件扫过的范围
            {6.0F, 11.5F, 7.0F, 7.0F, 16.5F, 8.0F}, // prime_rod: 运动件扫过的范围
            {2.0F, 11.5F, 7.0F, 3.0F, 16.5F, 8.0F}, // powder_tube: 运动件扫过的范围
    };

    /** 运动件 (方块实体渲染器画的件) 在整个循环与待机里扫过的范围, 整台坐标 {x, y, z} (px)。 */
    public static final float[] PARTS_MIN = {5.75F, 4.25F, 6.25F};
    public static final float[] PARTS_MAX = {27.25F, 22.25F, 8.75F};
    /**
     * 方块实体渲染包围盒的顶 (px): 方块实体渲染器画的东西 —— 运动件 (最高 PARTS_MAX[1]) 与运行灯效的覆盖层 (闪耀宝石顶面浮出后
     * 最高, 见 LIGHT_*) —— 的最高点, 向上取整到 0.25 px。低了的话画面里只剩宝石时整个方块实体被视锥剔掉, 那一帧的灯效就没了。
     */
    public static final float RENDER_TOP_PX = 24.25F;

    /** 冲压火花的位置 (整台坐标 = 主格局部坐标, px): 压弹头位那发的壳口, 冲头在 MunitionsBenchProgram.STRIKE_TICK 压到这里。 */
    public static final float SPARK_X = 14.5F;
    public static final float SPARK_Y = 11.5F;
    public static final float SPARK_Z = 7.5F;

    /** 皮带面 (px): 皮带上的弹都站在这个高度上。 */
    public static final float BELT_TOP_PX = 9.0F;
    /** 弹位中心 x (整台坐标, px), 从入口到出弹: 入口 / 底火 / 装药 / 压弹头 / 出弹, 相邻两位差一个 MunitionsBenchProgram.BELT_PITCH。 */
    public static final float[] SLOT_X_PX = {26.5F, 22.5F, 18.5F, 14.5F, 10.5F};
    /** 弹位中心 z (px)。 */
    public static final float SLOT_Z_PX = 7.5F;
    /** 一发弹的壳高 / 弹头 (被甲 + 弹尖) 高 (px); 壳口 = BELT_TOP_PX + ROUND_CASE_HEIGHT_PX = SPARK_Y。 */
    public static final float ROUND_CASE_HEIGHT_PX = 2.5F;
    public static final float ROUND_BULLET_HEIGHT_PX = 1.5F;
    /**
     * 静止位 (程序的 y 偏移为 0) 时运动件的下端 (px): 冲头夹着的弹头底、底火冲杆底、装药管底;
     * 加上 MunitionsBenchProgram 的 ramY / primeY / powderY 就是当时的位置 (冲压时刻弹头底 = 壳口, 两根杆下探到底 = 壳口)。
     */
    public static final float RAM_BULLET_REST_BOTTOM_PX = 15.25F;
    public static final float PRIME_ROD_REST_BOTTOM_PX = 13.0F;
    public static final float POWDER_TUBE_REST_BOTTOM_PX = 13.0F;

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

    // ---- 运行灯效 (MunitionsBenchLights): 工作时在静态灯带上叠一层随程序动作的发光四边形, 按档位解锁 ----
    // 与 tools/munitions_bench/lights.mjs 同一份 (生成器写出后解析回来核对, 并按生成的 JSON 核对每个目标面); 画法在 MunitionsBenchLights。
    /** 覆盖层 α 低于这个不画 (rendertype_text_background.fsh 的 discard 阈值; 每个顶点都要 ≥ 它)。 */
    public static final double LIGHT_ALPHA_CUTOFF = 0.1;
    /** 相机离主格中心超过这么多格不画 (= COUNTER_MAX_DISTANCE_BLOCKS); 运行呼吸从 LIGHT_FADE_START_BLOCKS 起渐隐。 */
    public static final float LIGHT_MAX_DISTANCE_BLOCKS = 24.0F;
    public static final float LIGHT_FADE_START_BLOCKS = 20.0F;
    /** 一帧最多几个覆盖四边形 (MunitionsBenchLights.Frame 的定长数组; 生成器逐 0.25 tick 核对六档都不超过它)。 */
    public static final int LIGHT_MAX_QUADS = 32;
    /** 工位灯亮度低于这个就不单独切段 (并进呼吸段)。 */
    public static final double LIGHT_LED_MERGE_EPSILON = 0.00390625;
    /**
     * 覆盖层贴的静态元素面 (整台像素, 朝北), 下标 = MunitionsBenchLights 的目标下标。面 = 原版 Direction.get3DDataValue() (下 0 上 1 北 2 南 3 西 4 东 5);
     * 矩形 {x0, y0, z0, x1, y1, z1} (法线轴上两值相同 = 面所在的平面); 胀 {x0, x1, y0, y1, z0, z1} = 这条边往外胀 LIFT (相邻两个覆盖面在棱上接成壳);
     * 浮出 = 沿面外法线离开面的距离 (px); 明暗 = 元素的 shade 标志 (渲染器按 level.getShade(面方向, 它) 乘颜色)。
     */
    public static final int[] LIGHT_TARGET_FACES = {2, 1, 2, 2, 1, 4, 5, 2, 2, 1, 4, 5, 2, 3, 5, 4, 1};
    public static final float[][] LIGHT_TARGET_RECTS_PX = {
            {8.5F, 7.0F, 0.0F, 31.5F, 7.5F, 0.0F}, // 0 strip_n: strip.north
            {8.5F, 7.5F, 0.0F, 31.5F, 7.5F, 0.5F}, // 1 strip_u: strip.up
            {8.0F, 9.0F, 9.5F, 31.0F, 10.0F, 9.5F}, // 2 rail_b_n: rail_b.north
            {12.0F, 19.0F, 3.25F, 17.0F, 19.5F, 3.25F}, // 3 crown_n: crown_light.north
            {12.0F, 19.5F, 3.25F, 17.0F, 19.5F, 3.75F}, // 4 crown_u: crown_light.up
            {12.0F, 19.0F, 3.25F, 12.0F, 19.5F, 3.75F}, // 5 crown_w: crown_light.west
            {17.0F, 19.0F, 3.25F, 17.0F, 19.5F, 3.75F}, // 6 crown_e: crown_light.east
            {0.5F, 2.5F, 1.0F, 1.0F, 3.0F, 1.0F}, // 7 can_n_open: can_strip.north
            {1.0F, 2.5F, 1.0F, 8.0F, 3.0F, 1.0F}, // 8 can_n_under: can_strip.north
            {0.5F, 3.0F, 1.0F, 1.0F, 3.0F, 1.5F}, // 9 can_u: can_strip.up
            {0.5F, 2.5F, 1.0F, 0.5F, 3.0F, 1.5F}, // 10 can_w: can_strip.west
            {8.0F, 2.5F, 1.0F, 8.0F, 3.0F, 1.5F}, // 11 can_e: can_strip.east
            {14.0F, 23.0F, 7.0F, 15.0F, 24.0F, 7.0F}, // 12 gem_n: gem.north
            {14.0F, 23.0F, 8.0F, 15.0F, 24.0F, 8.0F}, // 13 gem_s: gem.south
            {15.0F, 23.0F, 7.0F, 15.0F, 24.0F, 8.0F}, // 14 gem_e: gem.east
            {14.0F, 23.0F, 7.0F, 14.0F, 24.0F, 8.0F}, // 15 gem_w: gem.west
            {14.0F, 24.0F, 7.0F, 15.0F, 24.0F, 8.0F}, // 16 gem_u: gem.up
    };
    public static final int[][] LIGHT_TARGET_GROW = {
            {0, 0, 0, 1, 0, 0}, // 0 strip_n
            {0, 0, 0, 0, 1, 0}, // 1 strip_u
            {0, 0, 0, 0, 0, 0}, // 2 rail_b_n
            {1, 1, 0, 1, 0, 0}, // 3 crown_n
            {1, 1, 0, 0, 1, 0}, // 4 crown_u
            {0, 0, 0, 1, 1, 0}, // 5 crown_w
            {0, 0, 0, 1, 1, 0}, // 6 crown_e
            {1, 0, 0, 1, 0, 0}, // 7 can_n_open
            {0, 1, 0, 0, 0, 0}, // 8 can_n_under
            {1, 0, 0, 0, 1, 0}, // 9 can_u
            {0, 0, 0, 1, 1, 0}, // 10 can_w
            {0, 0, 0, 0, 1, 0}, // 11 can_e
            {1, 1, 0, 1, 0, 0}, // 12 gem_n
            {1, 1, 0, 1, 0, 0}, // 13 gem_s
            {0, 0, 0, 1, 1, 1}, // 14 gem_e
            {0, 0, 0, 1, 1, 1}, // 15 gem_w
            {1, 1, 0, 0, 1, 1}, // 16 gem_u
    };
    public static final float[] LIGHT_TARGET_LIFT_PX = {0.125F, 0.125F, 0.125F, 0.125F, 0.125F, 0.125F, 0.125F, 0.125F, 0.125F, 0.125F, 0.125F, 0.125F, 0.03125F, 0.03125F, 0.03125F, 0.03125F, 0.03125F};
    public static final boolean[] LIGHT_TARGET_SHADED = {true, true, true, true, true, true, true, true, true, true, true, true, false, false, false, false, false};
    /** 目标组 (下标 = MunitionsBenchLights.GROUP_*: 前沿灯带 / 后护栏背光 / 压机横梁灯条 / 弹药箱下灯带 / 闪耀宝石), 每组一起亮的目标。 */
    public static final int[][] LIGHT_GROUPS = {
            {0, 1}, // strip
            {2}, // rail
            {3, 4, 5, 6}, // crown
            {7, 8, 9, 10, 11}, // can
            {12, 13, 14, 15, 16}, // gem
    };
    /** 工位指示灯 (前沿灯带上、各弹位正下方一段 LIGHT_LED_WIDTH_PX 宽; 按 x 从小到大 = 出弹 / 压弹头 / 装药 / 底火 / 入口) 的中心 x 与脉冲峰 (循环 tick)。 */
    public static final float[] LIGHT_LED_X_PX = {10.5F, 14.5F, 18.5F, 22.5F, 26.5F};
    public static final double[] LIGHT_LED_PEAK_TICKS = {35.0, 10.0, 5.0, 0.0, 38.0};
    public static final float LIGHT_LED_WIDTH_PX = 1.5F;
    /** 脉冲 {缓入 tick, 衰减 tick} / {峰 tick, 缓入, 衰减}: 峰前二次缓入, 峰后二次衰减 (MunitionsBenchLights.pulse)。 */
    public static final double[] LIGHT_LED_PULSE = {1.0, 5.0};
    public static final double[] LIGHT_STRIKE_PULSE = {10.0, 1.0, 6.0};
    public static final double[] LIGHT_DROP_PULSE = {35.0, 2.0, 8.0};
    public static final double LIGHT_DROP_ALPHA = 0.9;
    public static final double[] LIGHT_GEM_PULSE = {10.0, 1.0, 8.0};
    public static final double[] LIGHT_GEM_ECHO_PULSE = {35.0, 1.0, 6.0};
    public static final double LIGHT_GEM_ECHO_LEVEL = 0.8;
    /** 满仓提示 (待机): 客户端时钟每 LIGHT_FULL_BLINK_PERIOD_TICKS 一次梯形闪, 拐点 {起, 全亮, 开始暗, 灭} (tick)。 */
    public static final int LIGHT_FULL_BLINK_PERIOD_TICKS = 40;
    public static final double[] LIGHT_FULL_BLINK_RAMP = {0.0, 4.0, 16.0, 20.0};
    public static final double LIGHT_FULL_ALPHA = 0.95;
    /** 运行呼吸: 周期 (tick) 与 α {最低, 最高} (只往亮的一侧)。 */
    public static final int LIGHT_BREATH_PERIOD_TICKS = 80;
    public static final double[] LIGHT_BREATH_ALPHA = {0.12, 0.32};
    /** 皮带追光: 包络拐点 {淡入起, 淡入止, 淡出起, 淡出止} (循环 tick)、图案速度 (× 皮带位移)、彗尾长 (px)、α {头, 暗槽}。 */
    public static final double[] LIGHT_CHASE_WINDOW = {9.0, 12.0, 23.0, 27.0};
    public static final double LIGHT_CHASE_SPEED = 1.0;
    public static final double LIGHT_CHASE_TAIL_PX = 2.5;
    public static final double[] LIGHT_CHASE_ALPHA = {0.9, 0.6};
    /** 每个效果从哪一档起有 (下标 = MunitionsBenchLights.EFFECT_*: 追光 / 呼吸 / 工位灯 / 冲压 / 落箱 / 满仓 / 宝石)。 */
    public static final int[] LIGHT_UNLOCK_TIERS = {3, 2, 0, 0, 0, 0, 5};
    /**
     * 颜色 0xRRGGBB, 下标 [档位 0..5][角色 = MunitionsBenchLights.ROLE_*: 工位灯 / 冲压回落色 / 落箱 / 满仓琥珀 / 宝石白闪 / 宝石回响 /
     * 呼吸 / 追光头 / 追光暗槽], 由 tiers.mjs 的档位灯色推出 (lights.mjs lightPalette)。
     */
    public static final int[][] LIGHT_COLOURS = {
            {0xE1FFFD, 0xC8FFFC, 0xE4FFFE, 0xFFB234, 0xEFFFFE, 0xC8FFFC, 0xC8FFFC, 0xD6FFFD, 0x31838D}, // 档位 0
            {0xDBF3E3, 0xBEEACC, 0xDFF5E6, 0xFFB234, 0xECF9F0, 0xBEEACC, 0xBEEACC, 0xCEEFD9, 0x447F5D}, // 档位 1
            {0xDCEAFA, 0xC0D9F6, 0xE0ECFB, 0xFFB234, 0xECF4FC, 0xC0D9F6, 0xC0D9F6, 0xD0E3F8, 0x476A93}, // 档位 2
            {0xECE0F8, 0xDDC7F3, 0xEEE3F9, 0xFFB234, 0xF5EEFB, 0xDDC7F3, 0xDDC7F3, 0xE6D5F6, 0x6C5390}, // 档位 3
            {0xF8DBDD, 0xF3BDC1, 0xF9DEE0, 0xFFB234, 0xFBEBEC, 0xF3BDC1, 0xF3BDC1, 0xF6CED1, 0x88464F}, // 档位 4
            {0xF7E2FF, 0xF0CAFF, 0xF8E5FF, 0xFFB234, 0xFBEFFF, 0xECCF87, 0xF0CAFF, 0xF3E0B1, 0x84569F}, // 档位 5
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
