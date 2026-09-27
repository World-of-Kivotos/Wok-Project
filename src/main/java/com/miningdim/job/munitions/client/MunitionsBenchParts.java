package com.miningdim.job.munitions.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.block.MunitionsBenchProgram;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import java.util.EnumSet;

/**
 * 军火台 (WIDE 布局) 的运动件: 皮带上的弹、出弹、压弹头冲头 (连杆 / 冷热两个压模 / 夹着的弹头)、底火冲杆、装药管。
 * 静态机身是区块网格里的 JSON 模型 (munitions_bench*_line_*.json); 这些件由主格的方块实体渲染器按
 * {@link MunitionsBenchProgram} 的姿态每帧画, 待机时也画 (停在待机布局)。运动件与档位无关, 六档共用一张贴图 {@link #TEXTURE}。
 * <p>
 * 由 tools/munitions_bench/generate_munitions_bench.mjs 整个写出 (与方块模型同一份场景), 不要手改。
 * <p>
 * 坐标系: 朝北放置时的整台局部像素, x 东、y 上、z 南, 原点在主格西北下角 (主格 x 0..16 在玩家右手, 副格 x 16..32 在左手)。
 * 件按 4 倍尺寸建 (运动件有 0.25 / 0.75 px 的尺寸、贴图 2-4 texel/px, 原版盒式 UV 按 1 texel/单位算), 渲染时缩回 {@link #PART_SCALE}。
 * 每个件的 PartPose 偏移 = 它在待机布局里的枢轴 (px) × {@link #UNITS_PER_PX}, y 取反 (ModelPart 的 y 朝下)。
 * <p>
 * 渲染器 (主格方块实体) 的变换:
 * <pre>
 * poseStack.translate(0.5, 0.0, 0.5);
 * poseStack.mulPose(Axis.YP.rotationDegrees(rot));   // NORTH 0, EAST -90, SOUTH 180, WEST 90 (与方块状态的 y 旋转同向)
 * poseStack.translate(-0.5, 0.0, -0.5);
 * Matrix3f normal = new Matrix3f(poseStack.last().normal());
 * poseStack.scale(PART_SCALE, -PART_SCALE, PART_SCALE);
 * poseStack.last().normal().set(normal).scale(1.0F, -1.0F, 1.0F);   // 法线矩阵自己设, 见下
 * MunitionsBenchParts.applyPose(root, pose);             // pose = MunitionsBenchProgram.sample(...) 或 idle(...)
 * </pre>
 * 1.20.1 的 {@code PoseStack.scale} 在三个缩放之积为负 (一个轴取负) 时把法线矩阵算坏: {@code Mth.fastInvCubeRoot} 不收负数,
 * 法线被放大约 1e25 倍, 写进顶点时每个分量截成 ±1, 实体光照随视角乱跳 (三轴同为负也一样)。diag(s, -s, s) 的逆转置
 * 与 diag(1, -1, 1) 同向, 所以把缩放前的法线矩阵右乘 diag(1, -1, 1) 即是正确的单位法线矩阵。
 * 然后逐个画 {@link #PARTS} (root 本身不画, 隐藏的件 render 直接跳过): {@link #FULL_BRIGHT} 里的件用
 * {@code LightTexture.pack(max(方块光, FULL_BRIGHT_LIGHT), max(天空光, FULL_BRIGHT_LIGHT))}, 其余用方块实体的 packedLight。
 * y 翻转后面的绕序反了, 用 {@code RenderType.entityCutoutNoCull(TEXTURE)} (与组装台机械臂相同); 不剔除背面, 所以件上看得见的面
 * 都要建上 (缺一个面就能看进件里, 对拍脚本的"背面检查"会报出来)。
 */
public final class MunitionsBenchParts {

    public static final ResourceLocation TEXTURE = new ResourceLocation(MiningConstants.MODID, "textures/entity/munitions_bench_parts.png");
    /** ModelPart 单位 → 像素的缩放 (件按 4 倍尺寸建)。 */
    public static final float PART_SCALE = 0.25F;
    public static final float UNITS_PER_PX = 4.0F;
    public static final int TEXTURE_WIDTH = 128;
    public static final int TEXTURE_HEIGHT = 64;
    /** 压模热度 (Pose.dieHeat) 到这个值就画热压模 {@link #RAM_DIE_HOT}, 否则画冷压模 {@link #RAM_DIE}。 */
    public static final float HOT_THRESHOLD = 0.5F;
    /** {@link #FULL_BRIGHT} 里的件至少按这个方块光 / 天空光等级画 (与方案预览里热压模的自发光等级相同)。 */
    public static final int FULL_BRIGHT_LIGHT = 12;

    /** 入口位的空壳 (料斗落壳管正下方), 随皮带走。 */
    public static final String ROUND_IN = "round_in";
    /** 底火位的空壳, 随皮带走。 */
    public static final String ROUND_PRIME = "round_prime";
    /** 装药位的壳, 壳口还空着 (Pose.powderCharged 为假时画)。 */
    public static final String ROUND_POWDER = "round_powder";
    /** 装药位的壳, 壳口已是灰色发射药 (Pose.powderCharged 为真时画)。 */
    public static final String ROUND_POWDER_CHARGED = "round_powder_charged";
    /** 压弹头位的装药壳, 还没压弹头 (Pose.seated 为假时画)。 */
    public static final String ROUND_SEAT = "round_seat";
    /** 压弹头位那发, 已压上弹头 (Pose.seated 为真时画)。 */
    public static final String ROUND_SEAT_TIPPED = "round_seat_tipped";
    /** 皮带末端的整发弹 (出弹): 随皮带走到末端, 再沿 dropY 掉进弹药箱。 */
    public static final String DROP = "drop";
    /** 压弹头冲头的连杆 (静止时整根藏在压机横梁里)。 */
    public static final String RAM_ROD = "ram_rod";
    /** 压模, 冷 (镀铬)。 */
    public static final String RAM_DIE = "ram_die";
    /** 压模, 热 (自发光): 冲头到底前后代替冷压模。 */
    public static final String RAM_DIE_HOT = "ram_die_hot";
    /** 冲头夹着的下一颗弹头 (被甲 + 弹尖), 刚压完回位的两帧不夹。 */
    public static final String RAM_BULLET = "ram_bullet";
    /** 底火冲杆 (装填塔横梁东端, 弹位 x 22.5)。 */
    public static final String PRIME_ROD = "prime_rod";
    /** 装药管 (装填塔横梁西端, 弹位 x 18.5)。 */
    public static final String POWDER_TUBE = "powder_tube";

    /** 全部运动件 (画的顺序)。 */
    public static final String[] PARTS = {
            ROUND_IN,
            ROUND_PRIME,
            ROUND_POWDER,
            ROUND_POWDER_CHARGED,
            ROUND_SEAT,
            ROUND_SEAT_TIPPED,
            DROP,
            RAM_ROD,
            RAM_DIE,
            RAM_DIE_HOT,
            RAM_BULLET,
            PRIME_ROD,
            POWDER_TUBE,
    };
    /** 自发光的件 (见 {@link #FULL_BRIGHT_LIGHT})。 */
    public static final String[] FULL_BRIGHT = {RAM_DIE_HOT};

    private MunitionsBenchParts() {
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild(ROUND_IN,
                CubeListBuilder.create()
                        .texOffs(64, 0).addBox(-4.0F, -14.0F, -4.0F, 8.0F, 14.0F, 8.0F, EnumSet.of(Direction.DOWN, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH)),
                PartPose.offset(106.0F, -36.0F, 30.0F));
        root.addOrReplaceChild(ROUND_PRIME,
                CubeListBuilder.create()
                        .texOffs(64, 0).addBox(-4.0F, -14.0F, -4.0F, 8.0F, 14.0F, 8.0F, EnumSet.of(Direction.DOWN, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH)),
                PartPose.offset(90.0F, -36.0F, 30.0F));
        root.addOrReplaceChild(ROUND_POWDER,
                CubeListBuilder.create()
                        .texOffs(64, 0).addBox(-4.0F, -14.0F, -4.0F, 8.0F, 14.0F, 8.0F, EnumSet.of(Direction.DOWN, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH)),
                PartPose.offset(74.0F, -36.0F, 30.0F));
        root.addOrReplaceChild(ROUND_POWDER_CHARGED,
                CubeListBuilder.create()
                        .texOffs(32, 0).addBox(-4.0F, -14.0F, -4.0F, 8.0F, 14.0F, 8.0F, EnumSet.of(Direction.DOWN, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH)),
                PartPose.offset(74.0F, -36.0F, 30.0F));
        root.addOrReplaceChild(ROUND_SEAT,
                CubeListBuilder.create()
                        .texOffs(32, 0).addBox(-4.0F, -14.0F, -4.0F, 8.0F, 14.0F, 8.0F, EnumSet.of(Direction.DOWN, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH)),
                PartPose.offset(58.0F, -36.0F, 30.0F));
        root.addOrReplaceChild(ROUND_SEAT_TIPPED,
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-4.0F, -14.0F, -4.0F, 8.0F, 14.0F, 8.0F, EnumSet.of(Direction.DOWN, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH))
                        .texOffs(24, 39).addBox(-3.0F, -19.0F, -3.0F, 6.0F, 5.0F, 6.0F, EnumSet.of(Direction.DOWN, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH))
                        .texOffs(48, 39).addBox(-2.0F, -23.0F, -2.0F, 4.0F, 4.0F, 4.0F, EnumSet.of(Direction.DOWN, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH)),
                PartPose.offset(58.0F, -36.0F, 30.0F));
        root.addOrReplaceChild(DROP,
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-4.0F, -14.0F, -4.0F, 8.0F, 14.0F, 8.0F, EnumSet.of(Direction.DOWN, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH))
                        .texOffs(24, 39).addBox(-3.0F, -19.0F, -3.0F, 6.0F, 5.0F, 6.0F, EnumSet.of(Direction.DOWN, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH))
                        .texOffs(48, 39).addBox(-2.0F, -23.0F, -2.0F, 4.0F, 4.0F, 4.0F, EnumSet.of(Direction.DOWN, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH)),
                PartPose.offset(42.0F, -36.0F, 30.0F));
        root.addOrReplaceChild(RAM_ROD,
                CubeListBuilder.create()
                        .texOffs(112, 22).addBox(-2.0F, -8.0F, -2.0F, 4.0F, 8.0F, 4.0F, EnumSet.of(Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH)),
                PartPose.offset(58.0F, -74.0F, 30.0F));
        root.addOrReplaceChild(RAM_DIE,
                CubeListBuilder.create()
                        .texOffs(0, 22).addBox(-5.0F, -7.0F, -5.0F, 10.0F, 7.0F, 10.0F, EnumSet.of(Direction.DOWN, Direction.UP, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH)),
                PartPose.offset(58.0F, -67.0F, 30.0F));
        root.addOrReplaceChild(RAM_DIE_HOT,
                CubeListBuilder.create()
                        .texOffs(40, 22).addBox(-5.0F, -7.0F, -5.0F, 10.0F, 7.0F, 10.0F, EnumSet.of(Direction.DOWN, Direction.UP, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH)),
                PartPose.offset(58.0F, -67.0F, 30.0F));
        root.addOrReplaceChild(RAM_BULLET,
                CubeListBuilder.create()
                        .texOffs(0, 39).addBox(-3.0F, -5.0F, -3.0F, 6.0F, 5.0F, 6.0F, EnumSet.of(Direction.DOWN, Direction.UP, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH))
                        .texOffs(48, 39).addBox(-2.0F, -9.0F, -2.0F, 4.0F, 4.0F, 4.0F, EnumSet.of(Direction.DOWN, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH)),
                PartPose.offset(58.0F, -58.0F, 30.0F));
        root.addOrReplaceChild(PRIME_ROD,
                CubeListBuilder.create()
                        .texOffs(80, 22).addBox(-2.0F, -10.0F, -2.0F, 4.0F, 10.0F, 4.0F, EnumSet.of(Direction.DOWN, Direction.UP, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH)),
                PartPose.offset(90.0F, -56.0F, 30.0F));
        root.addOrReplaceChild(POWDER_TUBE,
                CubeListBuilder.create()
                        .texOffs(96, 22).addBox(-2.0F, -10.0F, -2.0F, 4.0F, 10.0F, 4.0F, EnumSet.of(Direction.DOWN, Direction.UP, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH)),
                PartPose.offset(74.0F, -56.0F, 30.0F));
        return LayerDefinition.create(mesh, 128, 64);
    }

    /**
     * 按程序姿态摆放各运动件 (改 x / y 偏移与 visible), 每帧画之前调一次。root = {@code createBodyLayer().bakeRoot()}。
     * 皮带上的弹与出弹沿 x 走 beltX; 出弹另沿 y 走 dropY; 冲头三件 + 夹着的弹头沿 y 走 ramY; 底火冲杆 / 装药管各走 primeY / powderY。
     */
    public static void applyPose(ModelPart root, MunitionsBenchProgram.Pose pose) {
        boolean hot = pose.dieHeat >= HOT_THRESHOLD;
        place(root, ROUND_IN, pose.beltX, 0.0F, true);
        place(root, ROUND_PRIME, pose.beltX, 0.0F, true);
        place(root, ROUND_POWDER, pose.beltX, 0.0F, !pose.powderCharged);
        place(root, ROUND_POWDER_CHARGED, pose.beltX, 0.0F, pose.powderCharged);
        place(root, ROUND_SEAT, pose.beltX, 0.0F, !pose.seated);
        place(root, ROUND_SEAT_TIPPED, pose.beltX, 0.0F, pose.seated);
        place(root, DROP, pose.beltX, pose.dropY, true);
        place(root, RAM_ROD, 0.0F, pose.ramY, true);
        place(root, RAM_DIE, 0.0F, pose.ramY, !hot);
        place(root, RAM_DIE_HOT, 0.0F, pose.ramY, hot);
        place(root, RAM_BULLET, 0.0F, pose.ramY, pose.ramBulletVisible);
        place(root, PRIME_ROD, 0.0F, pose.primeY, true);
        place(root, POWDER_TUBE, 0.0F, pose.powderY, true);
    }

    /** 把一个件从静止位平移 (dx, dy) px 并设显隐; ModelPart 的 y 朝下, 所以 y 取反。 */
    private static void place(ModelPart root, String name, float dx, float dy, boolean visible) {
        ModelPart part = root.getChild(name);
        PartPose rest = part.getInitialPose();
        part.x = rest.x + dx * UNITS_PER_PX;
        part.y = rest.y - dy * UNITS_PER_PX;
        part.z = rest.z;
        part.visible = visible;
    }
}
