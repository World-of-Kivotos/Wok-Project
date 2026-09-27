package com.miningdim.job.munitions.block;

/**
 * 枪械组装台的枪床: 运行时把台里那把枪的真实 TACZ 模型侧躺在前排胶垫上, 这里是摆放它要用的几何常量与尺寸曲线常量。
 * <p>
 * 由 tools/gunsmith_workstation/generate_assembly_bench.mjs 整个写出 (与方块模型、机械臂程序同一份几何), 不要手改;
 * 生成器还把同一组数值导出给 JS 预览 (GUN_BED)。摆放规则在 {@link GunsmithBenchGunLayout}, 与 JS 预览逐条一致。
 * 刻意不依赖任何 Minecraft 类, 客户端渲染器与 GameTest 都能直接用。
 * <p>
 * 坐标系: 朝北放置时的整台 (2x2) 局部像素, x 东、y 上、z 南, 原点在主格西北下角 (与 {@link GunsmithArmProgram} 相同)。
 * 床面上 x ∈ [BUTT_X - MAX_LENGTH, BUTT_X]、z ∈ [AXIS_Z - HALF_WIDTH, AXIS_Z + HALF_WIDTH]、y ∈ [TOP_Y, TOP_Y + 6]
 * 不放任何方块元素, 床面 (y = TOP_Y) 在 z 向前后各比这个范围宽出 0.25, 都由生成器校验。
 */
public final class GunsmithGunBed {

    /** 枪托端 (东, +x) 抵住托底挡块胶垫处的 x: 枪的包围盒最大 x 放在这里, 枪口朝西。 */
    public static final float BUTT_X = 27.5F;
    /** 床面 (胶垫顶面) 的 y: 枪的包围盒最小 y 放在这里。 */
    public static final float TOP_Y = 11.0F;
    /** 枪的包围盒在 z 向 (横跨枪床) 的中心。 */
    public static final float AXIS_Z = 11.75F;
    /** 渲染长度上限 (px), 即尺寸曲线的封顶值。 */
    public static final float MAX_LENGTH = 23.2F;
    /** 平躺的枪在 z 向可用的最大半宽 (即枪剖面高度的一半, px); 剖面高度超过 2 * HALF_WIDTH 时整把枪再按它缩小。等于机械臂扫描用的隐形枪体包络的 z 半宽, 所以任何枪都在包络的 z 范围里。 */
    public static final float HALF_WIDTH = 3.75F;
    /** 尺寸曲线: 渲染长度 = min(MAX_LENGTH, SIZE_A * L^SIZE_P), L 为枪在 TACZ FIXED 定位系里的长度 (未缩放 px)。 */
    public static final float SIZE_A = 1.864F;
    /** 尺寸曲线的指数, 见 {@link #SIZE_A}。 */
    public static final float SIZE_P = 0.657F;
    /** 枪口参考点左侧比右侧厚出这么多 (未缩放 px) 就左侧朝上 (例如侧插弹匣), 否则右侧朝上。 */
    public static final float SIDE_UP_THRESHOLD = 1.5F;
    /** 机械臂避让用的隐形枪体包络的厚度 (px): 关键帧表的安装点把零件放在 TOP_Y + ENVELOPE_THICKNESS 上, 运行时再按台上的枪下沉 (见 {@link GunsmithArmProgram#MAX_PLACE_DROP})。 */
    public static final float ENVELOPE_THICKNESS = 2.25F;

    private GunsmithGunBed() {
    }
}
