package com.miningdim.job.munitions.block;

import org.jetbrains.annotations.Nullable;

/**
 * 枪械组装台台面上那把真枪的摆放规则: 枪在 TACZ FIXED 定位系里的包围盒 → 侧躺在枪床上的变换。
 * <p>
 * 与 tools/gunsmith_workstation 的 JS 预览 (raster.mjs 的 layoutGunOnBed) 逐步对应, 改一边必须改另一边;
 * 常量都读 {@link GunsmithGunBed} (生成器写出、JS 预览 import 同一组数值)。刻意不依赖任何 Minecraft / TaCZ 类:
 * 客户端的 TaCZ 渲染助手按它摆枪, GameTest 在没有 TaCZ 的服务端直接测它。
 * <p>
 * 输入: 枪在 FIXED 定位系里画出来的方块 (裸枪显示 stack 下 TACZ 真正画的那些, 按骨骼结构判定, 规则见客户端
 * GunsmithBenchGunRenderer) 的包围盒 B (未缩放 px; 枪口 -X, 枪顶 +Y, 枪的左侧 +Z), 以及枪口参考点
 * (muzzle_pos / muzzle_flash / muzzle_default 中第一个存在的) 在同一坐标系里的 z0。
 * <ol>
 *     <li>L = B.maxX - B.minX; 非正或非有限值 → 不画。</li>
 *     <li>T = min(MAX_LENGTH, SIZE_A * L^SIZE_P), k = T / L; 剖面高度 k * (B.maxY - B.minY) 超过 2 * HALF_WIDTH 时
 *     k 再压到正好 2 * HALF_WIDTH / (B.maxY - B.minY)。</li>
 *     <li>(B.maxZ - z0) - (z0 - B.minZ) &gt; SIDE_UP_THRESHOLD (左侧比右侧厚, 例如侧插弹匣) → 左侧朝上, 否则右侧朝上;
 *     没有枪口节点时 z0 取 B 的 z 中心。</li>
 *     <li>旋转 R (定位系 → 枪床): 右侧朝上 (X, Y, Z) → (X, -Z, Y), 枪顶朝 +z (机械臂)、握把与弹匣朝玩家;
 *     左侧朝上 (X, Y, Z) → (X, Z, -Y)。两者都是绕 +X 的真旋转 (±90°), 不含镜像。</li>
 *     <li>平移 t: 枪床坐标 = t + k * R * q, 使变换后的包围盒最大 x = BUTT_X、最小 y = TOP_Y、z 中心 = AXIS_Z。</li>
 * </ol>
 * 放件下沉 (机械臂在两个安装点把零件放到真枪上, 见 {@link GunsmithArmProgram#sample(float, float, float, GunsmithArmProgram.Pose)}):
 * <ol>
 *     <li>每个画出来的方块取 FIXED 定位系里的 AABB (8 个原始角点变换后的包围盒, 不含 inflate), 经上面的摆放变换得台上 AABB
 *     (R 只换轴 / 取反、k &gt; 0, 所以这就是精确的像)。</li>
 *     <li>安装点 s 的枪顶 gunTop = 台上 AABB 与该点携带件底面范围严格重叠
 *     (maxX &gt; MIN_X &amp;&amp; minX &lt; MAX_X &amp;&amp; maxZ &gt; MIN_Z &amp;&amp; minZ &lt; MAX_Z) 的方块里最大的 AABB 顶;
 *     没有重叠的方块时取 TOP_Y。</li>
 *     <li>下沉量 = clamp(BOTTOM_Y - gunTop - PLACE_CLEARANCE, 0, MAX_PLACE_DROP); 台上不画枪时取 MAX_PLACE_DROP。</li>
 * </ol>
 * 内部按 double 算 (JS 预览是 double), 结果给 PoseStack 用才收成 float。
 */
public final class GunsmithBenchGunLayout {

    /** 没有枪口参考节点时传给 {@link #place} 的 boreZ: 退回包围盒的 z 中心。 */
    public static final float NO_BORE = Float.NaN;

    /** 枪侧躺时朝上的那一侧。 */
    public enum Side {
        /** 右侧朝上: (X, Y, Z) → (X, -Z, Y), 即绕 +X 转 +90°。 */
        RIGHT_UP(90.0F),
        /** 左侧朝上: (X, Y, Z) → (X, Z, -Y), 即绕 +X 转 -90°。 */
        LEFT_UP(-90.0F);

        private final float xRotationDegrees;

        Side(float xRotationDegrees) {
            this.xRotationDegrees = xRotationDegrees;
        }

        /** R 写成绕 +X 轴的右手旋转角 (度), 渲染器直接交给 Axis.XP.rotationDegrees。 */
        public float xRotationDegrees() {
            return xRotationDegrees;
        }
    }

    /**
     * 一把枪的摆法: 枪床坐标 (朝北整台局部像素) = (translateX, translateY, translateZ) + scale * R(side) * q,
     * q 是 FIXED 定位系里的点 (未缩放 px)。length = L、targetLength = T (尺寸曲线给的长度, 被 HALF_WIDTH 压过时
     * 实际渲染长度 scale * length 会比它短), 与 JS 预览的 L / T / k / t 同名同义。
     */
    public record Placement(Side side, float length, float targetLength, float scale,
                            float translateX, float translateY, float translateZ) {

        /** R 的 3x3 行主序矩阵, 与 JS 预览的 R 数组逐项相同。 */
        public float[] rotation() {
            return side == Side.RIGHT_UP
                    ? new float[]{1.0F, 0.0F, 0.0F, 0.0F, 0.0F, -1.0F, 0.0F, 1.0F, 0.0F}
                    : new float[]{1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, -1.0F, 0.0F};
        }

        /** FIXED 定位系里的点 (未缩放 px) → 枪床坐标 (px), out = {x, y, z}。 */
        public float[] toBench(float x, float y, float z, float[] out) {
            float benchY = side == Side.RIGHT_UP ? -z : z;
            float benchZ = side == Side.RIGHT_UP ? y : -y;
            out[0] = translateX + scale * x;
            out[1] = translateY + scale * benchY;
            out[2] = translateZ + scale * benchZ;
            return out;
        }
    }

    private GunsmithBenchGunLayout() {
    }

    /**
     * 按上面的规则摆一把枪; 返回 null 表示不画 (长度非正, 或包围盒有非有限值 —— 空包围盒是 ±∞, 也落在这里)。
     *
     * @param boreZ 枪口参考点的 z (FIXED 定位系, 未缩放 px); 没有枪口节点时传 {@link #NO_BORE}
     * @throws IllegalArgumentException y 或 z 方向 min &gt; max: 包围盒是调用方自己量的, 反了就是量错了
     */
    @Nullable
    public static Placement place(float minX, float minY, float minZ,
                                  float maxX, float maxY, float maxZ, float boreZ) {
        double length = (double) maxX - minX;
        if (!(length > 0.0D) || !Double.isFinite(length)
                || !Float.isFinite(minY) || !Float.isFinite(maxY) || !Float.isFinite(minZ) || !Float.isFinite(maxZ)) {
            return null;
        }
        if (minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("inverted gun bounds: y " + minY + ".." + maxY
                    + ", z " + minZ + ".." + maxZ);
        }
        double height = (double) maxY - minY;
        double target = Math.min(GunsmithGunBed.MAX_LENGTH,
                GunsmithGunBed.SIZE_A * Math.pow(length, GunsmithGunBed.SIZE_P));
        double scale = target / length;
        if (scale * height > 2.0D * GunsmithGunBed.HALF_WIDTH) {
            scale = 2.0D * GunsmithGunBed.HALF_WIDTH / height;
        }

        double bore = Float.isNaN(boreZ) ? ((double) minZ + maxZ) / 2.0D : boreZ;
        Side side = ((double) maxZ - bore) - (bore - minZ) > GunsmithGunBed.SIDE_UP_THRESHOLD
                ? Side.LEFT_UP : Side.RIGHT_UP;

        // R 只把 X 原样留下, 所以 x 与 FIXED 系同向; y、z 分别取包围盒的 ∓Z / ±Y 两端。
        double centreY = ((double) minY + maxY) / 2.0D;
        double translateX = GunsmithGunBed.BUTT_X - scale * maxX;
        double translateY;
        double translateZ;
        if (side == Side.RIGHT_UP) {
            translateY = GunsmithGunBed.TOP_Y + scale * maxZ;
            translateZ = GunsmithGunBed.AXIS_Z - scale * centreY;
        } else {
            translateY = GunsmithGunBed.TOP_Y - scale * minZ;
            translateZ = GunsmithGunBed.AXIS_Z + scale * centreY;
        }
        return new Placement(side, (float) length, (float) target, (float) scale,
                (float) translateX, (float) translateY, (float) translateZ);
    }

    /**
     * 台上这把枪在两个安装点的放件下沉量, out = {枪机安装点, 枪托件安装点} (px), 直接交给机械臂的 sample。
     *
     * @param placement 这把枪的摆法; null = 台上不画枪 (空台、加载中、失败、没装 TaCZ), 两处都取 MAX_PLACE_DROP,
     *                  零件落到空床面上方 PLACE_CLEARANCE
     * @param cubeBoxes 画出来的方块在 FIXED 定位系里的 AABB, 每个方块 6 个数 {minX, minY, minZ, maxX, maxY, maxZ} (未缩放 px)
     * @param cubeCount 方块个数 (cubeBoxes 可以比 6 * cubeCount 长)
     */
    public static float[] placeDrops(@Nullable Placement placement, float[] cubeBoxes, int cubeCount, float[] out) {
        if (placement == null) {
            out[0] = GunsmithArmProgram.MAX_PLACE_DROP;
            out[1] = GunsmithArmProgram.MAX_PLACE_DROP;
            return out;
        }
        out[0] = placeDrop(GunsmithArmProgram.BOLT_PLACE_BOTTOM_Y, stationTop(placement, cubeBoxes, cubeCount,
                GunsmithArmProgram.BOLT_PLACE_MIN_X, GunsmithArmProgram.BOLT_PLACE_MAX_X,
                GunsmithArmProgram.BOLT_PLACE_MIN_Z, GunsmithArmProgram.BOLT_PLACE_MAX_Z));
        out[1] = placeDrop(GunsmithArmProgram.STOCK_PLACE_BOTTOM_Y, stationTop(placement, cubeBoxes, cubeCount,
                GunsmithArmProgram.STOCK_PLACE_MIN_X, GunsmithArmProgram.STOCK_PLACE_MAX_X,
                GunsmithArmProgram.STOCK_PLACE_MIN_Z, GunsmithArmProgram.STOCK_PLACE_MAX_Z));
        return out;
    }

    /**
     * 安装点底面范围 (枪床 px) 下方的枪顶: 台上 AABB 与范围严格重叠的方块里最大的 AABB 顶 y; 没有重叠的方块时为 TOP_Y。
     * 台上 AABB 是 FIXED 系 AABB 在摆放变换下的像 (R 只换轴 / 取反, 逐轴取两端即可, 不必变换 8 个角)。
     *
     * @param cubeBoxes 同 {@link #placeDrops}
     */
    public static float stationTop(Placement placement, float[] cubeBoxes, int cubeCount,
                                   float minX, float maxX, float minZ, float maxZ) {
        double k = placement.scale();
        double tx = placement.translateX();
        double ty = placement.translateY();
        double tz = placement.translateZ();
        boolean rightUp = placement.side() == Side.RIGHT_UP;
        double top = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < cubeCount; i++) {
            int o = 6 * i;
            double benchMinX = tx + k * cubeBoxes[o];
            double benchMaxX = tx + k * cubeBoxes[o + 3];
            // 右侧朝上 (X, Y, Z) → (X, -Z, Y); 左侧朝上 (X, Y, Z) → (X, Z, -Y)
            double benchMaxY = rightUp ? ty - k * cubeBoxes[o + 2] : ty + k * cubeBoxes[o + 5];
            double benchMinZ = rightUp ? tz + k * cubeBoxes[o + 1] : tz - k * cubeBoxes[o + 4];
            double benchMaxZ = rightUp ? tz + k * cubeBoxes[o + 4] : tz - k * cubeBoxes[o + 1];
            if (benchMaxX > minX && benchMinX < maxX && benchMaxZ > minZ && benchMinZ < maxZ) {
                top = Math.max(top, benchMaxY);
            }
        }
        return top == Double.NEGATIVE_INFINITY ? GunsmithGunBed.TOP_Y : (float) top;
    }

    /**
     * 放件下沉量 = clamp(bottomY - gunTop - PLACE_CLEARANCE, 0, MAX_PLACE_DROP): 零件底面落到枪顶上方 PLACE_CLEARANCE,
     * 最多降到空床面上方 PLACE_CLEARANCE。NaN 按 0 (不下沉, 零件停在包络顶面上, 不会穿进任何东西)。
     *
     * @param bottomY 安装点未下沉时携带件底面的 y (BOLT_PLACE_BOTTOM_Y / STOCK_PLACE_BOTTOM_Y)
     * @param gunTop  {@link #stationTop} 的结果
     */
    public static float placeDrop(float bottomY, float gunTop) {
        double drop = (double) bottomY - gunTop - GunsmithArmProgram.PLACE_CLEARANCE;
        if (!(drop > 0.0D)) {
            return 0.0F;
        }
        return (float) Math.min(drop, GunsmithArmProgram.MAX_PLACE_DROP);
    }
}
