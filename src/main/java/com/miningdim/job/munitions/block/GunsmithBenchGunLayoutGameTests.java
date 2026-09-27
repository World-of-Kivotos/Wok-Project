package com.miningdim.job.munitions.block;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.Arrays;

/**
 * {@link GunsmithBenchGunLayout} 与 {@link GunsmithArmProgram} 放件下沉的纯数学用例 (服务端跑, 不需要 TaCZ)。
 * <p>
 * 包围盒 {minX, minY, minZ, maxX, maxY, maxZ} 与枪口 z 取自测试端实装枪包里这几把枪的高模在 FIXED 定位系里的实测值
 * (按骨骼文件逐方块量, 四舍五入到 0.01 px; 当时含配件转接件子树, 渲染端现在按裸枪不画它们, M4A1 实际量到的会短一些,
 * 这里只当样本数值用); 期望值是按同一套规则在 JS 里用 double 独立算出 (或手算) 的字面量, 不是拿被测代码算的。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GunsmithBenchGunLayoutGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "gunsmith_bench_gun_layout";
    private static final float EPSILON = 1.0E-3F;
    /** 机械臂位置的容差 (px): 反解在 double 里做、结果存 float, 误差在 1e-6 量级。 */
    private static final float ARM_EPSILON = 1.0E-4F;

    private static final float[] M1911 = {-5.54F, -5.25F, -1.33F, 5.06F, 1.3F, 0.27F, -0.63F};
    private static final float[] UZI = {-12.76F, -8.55F, -2.08F, 9.8F, 2.39F, 0.24F, -0.95F};
    private static final float[] UMP45 = {-13.37F, -9.88F, -2.57F, 20.06F, 3.26F, 0.9F, -1.1F};
    private static final float[] M4A1 = {-20.15F, -7.23F, -3.6F, 19.15F, 4.69F, -0.06F, -1.56F};
    private static final float[] KAR98K = {-26.85F, -6.85F, -3.06F, 26.24F, 2.38F, -0.08F, -1.04F};
    private static final float[] M1887_LONG = {-35.04F, -8.81F, -2.11F, 30.8F, 2.37F, 0.36F, -0.88F};
    /** 侧插弹匣向枪的左侧 (+Z) 伸出约 6.6 px。 */
    private static final float[] STERLING = {-16.7F, -6.42F, -2.49F, 19.15F, 2.46F, 6.56F, -1.05F};
    /** 压宽用例 (不带枪口 z): L = 10、剖面 20 px 高, 两侧一样厚 → 右侧朝上; k 压到 2 * HALF_WIDTH / 20 = 0.375。 */
    private static final float[] CLAMP_RIGHT = {-5.0F, -10.0F, -1.0F, 5.0F, 10.0F, 1.0F};
    /** 同一把, 左侧 (+Z) 厚出 5 px → 左侧朝上 (枪口 z 取 0)。 */
    private static final float[] CLAMP_LEFT = {-5.0F, -10.0F, -1.0F, 5.0F, 10.0F, 6.0F};

    private GunsmithBenchGunLayoutGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sizeCurveGivesPackGunsTheirBenchLengthsAndCapsLongGuns(GameTestHelper helper) {
        assertBedConstantsMatchExpectations(helper);
        assertSize(helper, "M1911", place(M1911), 10.6F, 8.79168F, 0.829404F);
        assertSize(helper, "UZI", place(UZI), 22.56F, 14.440748F, 0.640104F);
        assertSize(helper, "UMP45", place(UMP45), 33.43F, 18.69836F, 0.559329F);
        assertSize(helper, "M4A1", place(M4A1), 39.3F, 20.795138F, 0.529138F);
        // 长枪都封顶在 MAX_LENGTH: 渲染长度一样, 只是缩得更狠
        assertSize(helper, "KAR98K", place(KAR98K), 53.09F, 23.2F, 0.436994F);
        assertSize(helper, "M1887_LONG", place(M1887_LONG), 65.84F, 23.2F, 0.352369F);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sideMagazineGunLiesLeftSideUpAndOthersRightSideUp(GameTestHelper helper) {
        assertBedConstantsMatchExpectations(helper);
        for (float[] gun : new float[][]{M1911, UZI, UMP45, M4A1, KAR98K, M1887_LONG}) {
            helper.assertTrue(place(gun).side() == GunsmithBenchGunLayout.Side.RIGHT_UP,
                    "a gun that is not thicker on its left must lie right side up");
        }
        GunsmithBenchGunLayout.Placement sterling = place(STERLING);
        helper.assertTrue(sterling.side() == GunsmithBenchGunLayout.Side.LEFT_UP,
                "the side-magazine STL must lie left side up so the magazine points up instead of into the bed");
        assertTranslation(helper, "STL", sterling, 17.042571F, 12.359739F, 10.668762F);
        assertBenchBox(helper, "STL", sterling, STERLING, 7.923039F, 11.0F, 9.325406F, 27.5F, 15.942022F, 14.174594F);

        // z0 驱动朝向: 没有枪口节点时退回包围盒 z 中心, 两侧一样厚 → 右侧朝上
        GunsmithBenchGunLayout.Placement noBore = GunsmithBenchGunLayout.place(STERLING[0], STERLING[1], STERLING[2],
                STERLING[3], STERLING[4], STERLING[5], GunsmithBenchGunLayout.NO_BORE);
        helper.assertTrue(noBore != null && noBore.side() == GunsmithBenchGunLayout.Side.RIGHT_UP,
                "without a muzzle node the bore falls back to the box centre, which is never lopsided");

        // 阈值是严格大于: 左侧恰好厚 SIDE_UP_THRESHOLD 仍是右侧朝上, 再多一点就翻面
        GunsmithBenchGunLayout.Placement atThreshold = GunsmithBenchGunLayout.place(-10.0F, -3.0F, -1.0F,
                10.0F, 3.0F, 2.5F, 0.0F);
        GunsmithBenchGunLayout.Placement pastThreshold = GunsmithBenchGunLayout.place(-10.0F, -3.0F, -1.0F,
                10.0F, 3.0F, 2.5F, -0.01F);
        helper.assertTrue(atThreshold != null && atThreshold.side() == GunsmithBenchGunLayout.Side.RIGHT_UP,
                "a left side exactly SIDE_UP_THRESHOLD thicker must stay right side up");
        helper.assertTrue(pastThreshold != null && pastThreshold.side() == GunsmithBenchGunLayout.Side.LEFT_UP,
                "a left side more than SIDE_UP_THRESHOLD thicker must flip left side up");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rightSideUpRotationPutsGunTopTowardArmAndMuzzleWest(GameTestHelper helper) {
        GunsmithBenchGunLayout.Placement m4 = place(M4A1);
        float[] muzzle = m4.toBench(M4A1[0], 0.0F, 0.0F, new float[3]);
        float[] butt = m4.toBench(M4A1[3], 0.0F, 0.0F, new float[3]);
        float[] top = m4.toBench(0.0F, M4A1[4], 0.0F, new float[3]);
        float[] grip = m4.toBench(0.0F, M4A1[1], 0.0F, new float[3]);
        float[] leftSide = m4.toBench(0.0F, 0.0F, 1.0F, new float[3]);
        float[] rightSide = m4.toBench(0.0F, 0.0F, -1.0F, new float[3]);
        helper.assertTrue(muzzle[0] < butt[0], "the muzzle must point west (-x), the butt east against the stop");
        helper.assertTrue(top[2] > grip[2], "right side up: the gun top must face +z (the arm), the grip the player");
        helper.assertTrue(rightSide[1] > leftSide[1], "right side up: the gun's right side must face up");
        assertArrayClose(helper, "R right side up", m4.rotation(), new float[]{1, 0, 0, 0, 0, -1, 0, 1, 0});
        helper.assertTrue(GunsmithBenchGunLayout.Side.RIGHT_UP.xRotationDegrees() == 90.0F,
                "right side up is +90 degrees about +X");

        GunsmithBenchGunLayout.Placement sterling = place(STERLING);
        float[] magazine = sterling.toBench(0.0F, 0.0F, STERLING[5], new float[3]);
        float[] sterlingTop = sterling.toBench(0.0F, STERLING[4], 0.0F, new float[3]);
        float[] sterlingGrip = sterling.toBench(0.0F, STERLING[1], 0.0F, new float[3]);
        helper.assertTrue(magazine[1] > sterling.translateY(), "left side up: the side magazine (+Z) must point up");
        helper.assertTrue(sterlingTop[2] < sterlingGrip[2], "left side up: the gun top faces -z, the grip +z");
        assertArrayClose(helper, "R left side up", sterling.rotation(), new float[]{1, 0, 0, 0, 0, 1, 0, -1, 0});
        helper.assertTrue(GunsmithBenchGunLayout.Side.LEFT_UP.xRotationDegrees() == -90.0F,
                "left side up is -90 degrees about +X");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void translationAnchorsButtStopBedSurfaceAndAxisOnBothSides(GameTestHelper helper) {
        assertBedConstantsMatchExpectations(helper);
        GunsmithBenchGunLayout.Placement m1911 = place(M1911);
        assertTranslation(helper, "M1911", m1911, 23.303217F, 11.223939F, 13.388072F);
        assertBenchBox(helper, "M1911", m1911, M1911, 18.70832F, 11.0F, 9.033703F, 27.5F, 12.327046F, 14.466297F);
        GunsmithBenchGunLayout.Placement m4 = place(M4A1);
        assertTranslation(helper, "M4A1", m4, 17.367F, 10.968252F, 12.422006F);
        assertBenchBox(helper, "M4A1", m4, M4A1, 6.704862F, 11.0F, 8.596335F, 27.5F, 12.87315F, 14.903665F);
        GunsmithBenchGunLayout.Placement kar98k = place(KAR98K);
        assertBenchBox(helper, "KAR98K", kar98k, KAR98K, 4.3F, 11.0F, 9.733274F, 27.5F, 12.302241F, 13.766726F);

        // 与具体数值无关的约束: 两种朝向都把枪托顶在 BUTT_X、枪身落在 TOP_Y、横向居中在 AXIS_Z
        for (float[] gun : new float[][]{M1911, UZI, UMP45, M4A1, KAR98K, M1887_LONG, STERLING}) {
            float[] box = benchBox(place(gun), gun);
            assertClose(helper, "butt at BUTT_X", box[3], GunsmithGunBed.BUTT_X);
            assertClose(helper, "resting on TOP_Y", box[1], GunsmithGunBed.TOP_Y);
            assertClose(helper, "centred on AXIS_Z", (box[2] + box[5]) / 2.0F, GunsmithGunBed.AXIS_Z);
            helper.assertTrue(box[3] - box[0] <= GunsmithGunBed.MAX_LENGTH + EPSILON,
                    "no gun may be longer than MAX_LENGTH on the bed");
            helper.assertTrue(box[5] - box[2] <= 2.0F * GunsmithGunBed.HALF_WIDTH + EPSILON,
                    "no gun profile may be wider than 2 * HALF_WIDTH across the bed");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tallProfileIsClampedToTheBedWidth(GameTestHelper helper) {
        assertBedConstantsMatchExpectations(helper);
        // L = 10 → T ≈ 8.4615, k ≈ 0.846; 20 px 高的剖面会占 16.9 px 宽, 超过 2 * HALF_WIDTH = 7.5, k 压到 7.5 / 20
        GunsmithBenchGunLayout.Placement right = clampRight();
        helper.assertTrue(right.side() == GunsmithBenchGunLayout.Side.RIGHT_UP,
                "the clamp case must still be placed right side up");
        assertClose(helper, "clamped k", right.scale(), 0.375F);
        assertClose(helper, "curve T before the clamp", right.targetLength(), 8.461472F);
        assertClose(helper, "clamped rendered length", right.scale() * right.length(), 3.75F);
        assertTranslation(helper, "clamp right", right, 25.625F, 11.375F, 11.75F);
        float[] rightBox = benchBox(right, CLAMP_RIGHT);
        assertClose(helper, "clamped profile width", rightBox[5] - rightBox[2], 2.0F * GunsmithGunBed.HALF_WIDTH);
        assertBenchBox(helper, "clamp right", right, CLAMP_RIGHT, 23.75F, 11.0F, 8.0F, 27.5F, 11.75F, 15.5F);

        GunsmithBenchGunLayout.Placement left = clampLeft();
        helper.assertTrue(left.side() == GunsmithBenchGunLayout.Side.LEFT_UP,
                "the lopsided clamp case must lie left side up");
        assertClose(helper, "clamped k (left)", left.scale(), 0.375F);
        assertTranslation(helper, "clamp left", left, 25.625F, 11.375F, 11.75F);
        assertBenchBox(helper, "clamp left", left, CLAMP_LEFT, 23.75F, 11.0F, 8.0F, 27.5F, 13.625F, 15.5F);
        helper.succeed();
    }

    /**
     * 安装点枪顶: 只算台上 AABB 与范围严格重叠的方块 (贴边不算), 取其中最高的; 没有重叠就是 TOP_Y。
     * 用压宽的那两个摆法 (k = 0.375, t = (25.625, 11.375, 11.75)) 和手搭的方块, 范围取整数 x (24, 26)、z (12, 14),
     * 贴边的方块能正好落在边上; 期望值逐个手算 (注释里是台上 AABB)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void stationTopTakesTheHighestCubeStrictlyOverlappingTheFootprint(GameTestHelper helper) {
        assertBedConstantsMatchExpectations(helper);
        GunsmithBenchGunLayout.Placement right = clampRight();
        float[] rightCubes = {
                -5.0F, -10.0F, -1.0F, 5.0F, 10.0F, 1.0F,   // 枪身: x 23.75..27.5, y 11..11.75, z 8..15.5 → 重叠
                -5.0F, -10.0F, -5.0F, 5.0F, -8.0F, -1.0F,  // 更高但在范围外: z 8..8.75, 顶 13.25 → 不算
                -5.0F, 6.0F, -6.0F, 5.0F, 8.0F, -1.0F,     // z 14..14.75, 贴着范围的 z 上沿 → 不算 (顶 13.625)
                1.0F, -10.0F, -7.0F, 5.0F, 10.0F, -1.0F,   // x 26..27.5, 贴着范围的 x 上沿 → 不算 (顶 14)
                0.9F, -2.0F, -4.0F, 5.0F, 2.0F, -1.0F,     // x 25.9625..27.5、z 11..12.5, 部分重叠 → 顶 12.875
        };
        assertClose(helper, "right side up: highest strictly overlapping cube",
                GunsmithBenchGunLayout.stationTop(right, rightCubes, 5, 24.0F, 26.0F, 12.0F, 14.0F), 12.875F);
        assertClose(helper, "only the gun body overlaps",
                GunsmithBenchGunLayout.stationTop(right, rightCubes, 4, 24.0F, 26.0F, 12.0F, 14.0F), 11.75F);
        assertClose(helper, "no overlapping cube leaves the bare bed",
                GunsmithBenchGunLayout.stationTop(right, rightCubes, 5, 19.0F, 21.0F, 12.0F, 14.0F),
                GunsmithGunBed.TOP_Y);
        assertClose(helper, "no cube at all leaves the bare bed",
                GunsmithBenchGunLayout.stationTop(right, rightCubes, 0, 24.0F, 26.0F, 12.0F, 14.0F),
                GunsmithGunBed.TOP_Y);

        // 左侧朝上 (X, Y, Z) → (X, Z, -Y): 台上 y 来自 +Z、台上 z 来自 -Y
        GunsmithBenchGunLayout.Placement left = clampLeft();
        float[] leftCubes = {
                -5.0F, 4.0F, -1.0F, 5.0F, 10.0F, 6.0F,     // z 8..10.25, 顶 13.625 → 不算
                -5.0F, -10.0F, 0.0F, 5.0F, -6.0F, 6.0F,    // z 14..15.5, 贴着范围的 z 上沿 → 不算
                -5.0F, -2.0F, -1.0F, 5.0F, 2.0F, 3.0F,     // z 11..12.5, y 11..12.5 → 重叠
        };
        assertClose(helper, "left side up: highest strictly overlapping cube",
                GunsmithBenchGunLayout.stationTop(left, leftCubes, 3, 24.0F, 26.0F, 12.0F, 14.0F), 12.5F);
        helper.succeed();
    }

    /** 下沉量 = clamp(BOTTOM_Y - gunTop - PLACE_CLEARANCE, 0, MAX_PLACE_DROP), 台上不画枪时两处都是 MAX_PLACE_DROP。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void placeDropKeepsTheClearanceAndClampsToZeroAndTheEmptyBed(GameTestHelper helper) {
        assertArmPlaceConstantsMatchExpectations(helper);
        float bolt = GunsmithArmProgram.BOLT_PLACE_BOTTOM_Y;
        float stock = GunsmithArmProgram.STOCK_PLACE_BOTTOM_Y;
        assertDrop(helper, "gun top 12: part bottom lands PLACE_CLEARANCE above it",
                GunsmithBenchGunLayout.placeDrop(bolt, 12.0F), 1.2592F);
        assertDrop(helper, "gun top 12.5 under the stock part",
                GunsmithBenchGunLayout.placeDrop(stock, 12.5F), 0.7594F);
        assertDrop(helper, "gun top exactly PLACE_CLEARANCE below the part: no drop",
                GunsmithBenchGunLayout.placeDrop(bolt, 13.2592F), 0.0F);
        assertDrop(helper, "gun top inside the clearance: clamped to 0",
                GunsmithBenchGunLayout.placeDrop(bolt, 13.27F), 0.0F);
        assertDrop(helper, "gun top above the envelope part: never lifts, clamped to 0",
                GunsmithBenchGunLayout.placeDrop(bolt, 14.0F), 0.0F);
        assertDrop(helper, "bare bed under the bolt part: exactly MAX_PLACE_DROP",
                GunsmithBenchGunLayout.placeDrop(bolt, GunsmithGunBed.TOP_Y), 2.2592F);
        assertDrop(helper, "bare bed under the stock part: 2.2594 clamped to MAX_PLACE_DROP",
                GunsmithBenchGunLayout.placeDrop(stock, GunsmithGunBed.TOP_Y), 2.2592F);
        assertDrop(helper, "below the bed: clamped to MAX_PLACE_DROP",
                GunsmithBenchGunLayout.placeDrop(bolt, 5.0F), 2.2592F);
        assertDrop(helper, "NaN gun top: no drop",
                GunsmithBenchGunLayout.placeDrop(bolt, Float.NaN), 0.0F);

        float[] drops = GunsmithBenchGunLayout.placeDrops(null, new float[0], 0, new float[]{-1.0F, -1.0F});
        assertDrop(helper, "no gun drawn: bolt part goes onto the empty bed", drops[0], 2.2592F);
        assertDrop(helper, "no gun drawn: stock part goes onto the empty bed", drops[1], 2.2592F);

        // 压宽摆法的短枪 (台上 x 23.75..27.5): 枪机安装点 (x 19.45..20.55) 下面是空床, 枪托件安装点下面最高的方块顶 12.875
        float[] cubes = {
                -5.0F, -10.0F, -1.0F, 5.0F, 10.0F, 1.0F,   // x 23.75..27.5, z 8..15.5, 顶 11.75
                0.9F, -2.0F, -4.0F, 5.0F, 2.0F, -1.0F,     // x 25.9625..27.5, z 11..12.5, 顶 12.875
        };
        GunsmithBenchGunLayout.placeDrops(clampRight(), cubes, 2, drops);
        assertDrop(helper, "short gun: nothing under the bolt part, it goes onto the bed", drops[0], 2.2592F);
        assertDrop(helper, "short gun: stock part lands on the 12.875 cube", drops[1], 0.3844F);
        GunsmithBenchGunLayout.placeDrops(clampRight(), cubes, 1, drops);
        assertDrop(helper, "short gun body only: stock part lands on the 11.75 top", drops[1], 1.5094F);
        helper.succeed();
    }

    /**
     * 机械臂的放件下沉 ({@link GunsmithArmProgram#sample(float, float, float, GunsmithArmProgram.Pose)}): 安装点的低位行
     * (枪机 50-69、枪托件 117-136) 零件竖直下沉该安装点的下沉量, 另一个安装点的下沉量不起作用; 下探 (46 → 50、113 → 117)
     * 与抬起 (69 → 73、136 → 140) 两端各按自己的下沉量、按 smoothstep 过渡: 走到 1/4、1/2、3/4 处分别下沉
     * 0.15625、0.5、0.84375 倍 (抬起反过来), x/z 始终不变; 这两个窗口之外 (含窗口端点) 每 1/4 tick 都与不下沉逐位相同。
     * 下沉量夹到 [0, MAX_PLACE_DROP], 非正或 NaN 按 0。期望值只来自下沉量与 smoothstep 本身, 不依赖关键帧数值。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void armProgramLowersThePartByItsOwnStationDropOnly(GameTestHelper helper) {
        assertArmProgramTimingMatchesExpectations(helper);
        float max = GunsmithArmProgram.MAX_PLACE_DROP;
        for (float d : new float[]{0.5F, max}) {
            for (float tick : new float[]{52.0F, 56.0F, 64.0F}) {
                assertLowered(helper, "bolt station, tick " + tick + ", drop " + d, tick, d, 0.0F, d);
                assertLowered(helper, "bolt station ignores the stock drop, tick " + tick, tick, d, max, d);
            }
            for (float tick : new float[]{123.0F, 129.0F}) {
                assertLowered(helper, "stock station, tick " + tick + ", drop " + d, tick, 0.0F, d, d);
                assertLowered(helper, "stock station ignores the bolt drop, tick " + tick, tick, max, d, d);
            }
            // 1/2 处查得出 "两端用同一个下沉量", 1/4、3/4 处才查得出 "两端取平均" 与 "按 s 线性过渡"
            for (float other : new float[]{0.0F, max}) {
                assertEasedLowering(helper, "down onto the bolt station", 46.0F, true, true, d, other);
                assertEasedLowering(helper, "back up from the bolt station", 69.0F, true, false, d, other);
                assertEasedLowering(helper, "down onto the stock station", 113.0F, false, true, d, other);
                assertEasedLowering(helper, "back up from the stock station", 136.0F, false, false, d, other);
            }
        }

        float[][] dropPairs = {{0.5F, 0.5F}, {max, max}, {5.0F, 5.0F}, {max, 0.0F}, {0.0F, max}, {-1.0F, Float.NaN}};
        GunsmithArmProgram.Pose expected = new GunsmithArmProgram.Pose();
        GunsmithArmProgram.Pose actual = new GunsmithArmProgram.Pose();
        for (int quarter = -4; quarter <= 4 * GunsmithArmProgram.CYCLE_TICKS + 4; quarter++) {
            float tick = quarter / 4.0F;
            // 两个安装点的下探..抬起窗口之外 (取件、转运、返回待机, 以及把别的行误标成安装点行): 任何下沉量都不改姿态
            float inCycle = ((tick % GunsmithArmProgram.CYCLE_TICKS) + GunsmithArmProgram.CYCLE_TICKS)
                    % GunsmithArmProgram.CYCLE_TICKS;
            if (!(inCycle > 46.0F && inCycle < 73.0F) && !(inCycle > 113.0F && inCycle < 140.0F)) {
                GunsmithArmProgram.sample(tick, 0.0F, 0.0F, expected);
                for (float[] pair : dropPairs) {
                    assertSamePose(helper, "tick " + tick + " (outside both station windows) with drops " + pair[0] + "/"
                            + pair[1], expected, GunsmithArmProgram.sample(tick, pair[0], pair[1], actual));
                }
            }

            // 整轮: 夹取范围、不带下沉量的重载, 以及下沉只改大臂/小臂角
            GunsmithArmProgram.sample(tick, max, max, expected);
            assertSamePose(helper, "drops of 5 clamp to MAX_PLACE_DROP at tick " + tick, expected,
                    GunsmithArmProgram.sample(tick, 5.0F, 5.0F, actual));
            GunsmithArmProgram.sample(tick, max, 0.0F, expected);
            assertSamePose(helper, "drops 5 / NaN clamp to MAX_PLACE_DROP / 0 at tick " + tick, expected,
                    GunsmithArmProgram.sample(tick, 5.0F, Float.NaN, actual));

            GunsmithArmProgram.sample(tick, 0.0F, 0.0F, expected);
            assertSamePose(helper, "NaN drops count as 0 at tick " + tick, expected,
                    GunsmithArmProgram.sample(tick, Float.NaN, Float.NaN, actual));
            assertSamePose(helper, "negative drops count as 0 at tick " + tick, expected,
                    GunsmithArmProgram.sample(tick, -1.0F, -1.0F, actual));
            assertSamePose(helper, "sample(tick, out) is sample(tick, 0, 0, out) at tick " + tick, expected,
                    GunsmithArmProgram.sample(tick, actual));

            GunsmithArmProgram.sample(tick, max, max, actual);
            helper.assertTrue(same(actual.yaw, expected.yaw) && same(actual.toolSpin, expected.toolSpin)
                            && same(actual.claw, expected.claw) && actual.payload == expected.payload
                            && actual.spark == expected.spark,
                    "a place drop may only change the arm joints, not yaw / tool / claw / payload / spark, at tick "
                            + tick);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void degenerateBoxesAreNotRenderedAndInvertedBoxesFailLoudly(GameTestHelper helper) {
        helper.assertTrue(GunsmithBenchGunLayout.place(0.0F, -1.0F, -1.0F, 0.0F, 1.0F, 1.0F, 0.0F) == null,
                "a zero-length gun must not be rendered");
        helper.assertTrue(GunsmithBenchGunLayout.place(3.0F, -1.0F, -1.0F, -3.0F, 1.0F, 1.0F, 0.0F) == null,
                "a negative-length gun must not be rendered");
        float inf = Float.POSITIVE_INFINITY;
        helper.assertTrue(GunsmithBenchGunLayout.place(inf, inf, inf, -inf, -inf, -inf, GunsmithBenchGunLayout.NO_BORE)
                == null, "an empty box (no visible cube) must not be rendered");
        helper.assertTrue(GunsmithBenchGunLayout.place(Float.NaN, -1.0F, -1.0F, 5.0F, 1.0F, 1.0F, 0.0F) == null,
                "a NaN length must not be rendered");
        helper.assertTrue(GunsmithBenchGunLayout.place(-5.0F, -1.0F, -1.0F, 5.0F, 1.0F, inf, 0.0F) == null,
                "a box with an infinite side must not be rendered");
        boolean threw = false;
        try {
            GunsmithBenchGunLayout.place(-5.0F, 1.0F, -1.0F, 5.0F, -1.0F, 1.0F, 0.0F);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw, "an inverted y range is a measuring bug and must throw");
        helper.succeed();
    }

    /** 字面量期望值是按这组枪床常量算的; 生成器改了常量就得重算期望值, 在这里先报出来, 不要让下面的数值断言去猜原因。 */
    private static void assertBedConstantsMatchExpectations(GameTestHelper helper) {
        helper.assertTrue(GunsmithGunBed.BUTT_X == 27.5F && GunsmithGunBed.TOP_Y == 11.0F
                        && GunsmithGunBed.AXIS_Z == 11.75F && GunsmithGunBed.MAX_LENGTH == 23.2F
                        && GunsmithGunBed.HALF_WIDTH == 3.75F && GunsmithGunBed.SIZE_A == 1.864F
                        && GunsmithGunBed.SIZE_P == 0.657F && GunsmithGunBed.SIDE_UP_THRESHOLD == 1.5F,
                "GunsmithGunBed constants changed; re-derive the literal expectations in this class");
    }

    /** 下沉量的字面量期望值按这组机械臂安装点常量算; 生成器重新反解了安装点就得重算。 */
    private static void assertArmPlaceConstantsMatchExpectations(GameTestHelper helper) {
        assertBedConstantsMatchExpectations(helper);
        helper.assertTrue(GunsmithArmProgram.BOLT_PLACE_MIN_X == 19.4493F
                        && GunsmithArmProgram.BOLT_PLACE_MAX_X == 20.5496F
                        && GunsmithArmProgram.BOLT_PLACE_MIN_Z == 12.2001F
                        && GunsmithArmProgram.BOLT_PLACE_MAX_Z == 14.3003F
                        && GunsmithArmProgram.BOLT_PLACE_BOTTOM_Y == 13.2792F
                        && GunsmithArmProgram.STOCK_PLACE_MIN_X == 23.9501F
                        && GunsmithArmProgram.STOCK_PLACE_MAX_X == 26.0802F
                        && GunsmithArmProgram.STOCK_PLACE_MIN_Z == 12.1699F
                        && GunsmithArmProgram.STOCK_PLACE_MAX_Z == 14.33F
                        && GunsmithArmProgram.STOCK_PLACE_BOTTOM_Y == 13.2794F
                        && GunsmithArmProgram.MAX_PLACE_DROP == 2.2592F
                        && GunsmithArmProgram.PLACE_CLEARANCE == 0.02F,
                "GunsmithArmProgram place constants changed; re-derive the literal drop expectations in this class");
    }

    /**
     * 下沉用例里的 tick 是按这张程序表挑的 (点焊起点 52/58/119/125、156 停回待机; 枪机安装点 46 起下探、50-69 低位、73 抬完,
     * 枪托件安装点 113 / 117-136 / 140); 生成器改了程序节奏就得重挑。安装点行的起止只能从下沉的效果上看: 下探起点与抬起终点
     * 不下沉, 低位段两端整段下沉。
     */
    private static void assertArmProgramTimingMatchesExpectations(GameTestHelper helper) {
        helper.assertTrue(GunsmithArmProgram.CYCLE_TICKS == 160 && GunsmithArmProgram.PARKED_TICK == 156
                        && GunsmithArmProgram.nextWeldTickAfter(0L) == 52L
                        && GunsmithArmProgram.nextWeldTickAfter(52L) == 58L
                        && GunsmithArmProgram.nextWeldTickAfter(58L) == 119L
                        && GunsmithArmProgram.nextWeldTickAfter(119L) == 125L
                        && GunsmithArmProgram.nextWeldTickAfter(125L) == 212L,
                "GunsmithArmProgram timing changed; re-pick the ticks in the arm place-drop test");
        float max = GunsmithArmProgram.MAX_PLACE_DROP;
        float[] want = {0.0F, max, max, 0.0F};
        float[] bolt = {loweringAt(46.0F, max, 0.0F), loweringAt(50.0F, max, 0.0F), loweringAt(69.0F, max, 0.0F),
                loweringAt(73.0F, max, 0.0F)};
        float[] stock = {loweringAt(113.0F, 0.0F, max), loweringAt(117.0F, 0.0F, max), loweringAt(136.0F, 0.0F, max),
                loweringAt(140.0F, 0.0F, max)};
        boolean same = true;
        for (int i = 0; i < want.length; i++) {
            same &= Math.abs(bolt[i] - want[i]) <= ARM_EPSILON && Math.abs(stock[i] - want[i]) <= ARM_EPSILON;
        }
        helper.assertTrue(same, "arm place drop is off at the station row edges: at MAX_PLACE_DROP the bolt part should be "
                + "lowered 0 / MAX / MAX / 0 at ticks 46/50/69/73 (got " + Arrays.toString(bolt) + ") and the stock part at "
                + "113/117/136/140 (got " + Arrays.toString(stock) + "); if the generator retimed the station rows, re-pick "
                + "the ticks in the arm place-drop test, otherwise GunsmithArmProgram.sample applies the drops wrongly");
    }

    /**
     * 下探或抬起 (from → from + 4) 的 1/4、1/2、3/4 处: 该安装点的下沉量按 smoothstep 过渡 (e = 0.15625 / 0.5 / 0.84375),
     * 下探下沉 d * e, 抬起下沉 d * (1 - e); other 是另一个安装点的下沉量, 不起作用。
     */
    private static void assertEasedLowering(GameTestHelper helper, String what, float from, boolean boltStation,
                                            boolean descent, float d, float other) {
        float[] eased = {0.15625F, 0.5F, 0.84375F};
        for (int step = 1; step <= eased.length; step++) {
            float tick = from + step;
            float e = eased[step - 1];
            assertLowered(helper, what + ", tick " + tick + ", drop " + d + ", other station " + other, tick,
                    boltStation ? d : other, boltStation ? other : d, d * (descent ? e : 1.0F - e));
        }
    }

    /** 零件接触点 (= 零件底面中心) 带下沉量时比不带时低 expectedLowering, x/z 不变。 */
    private static void assertLowered(GameTestHelper helper, String what, float tick, float boltDrop, float stockDrop,
                                      float expectedLowering) {
        float[] flat = GunsmithArmProgram.contactPosition(
                GunsmithArmProgram.sample(tick, 0.0F, 0.0F, new GunsmithArmProgram.Pose()), new float[3]);
        float[] lowered = GunsmithArmProgram.contactPosition(
                GunsmithArmProgram.sample(tick, boltDrop, stockDrop, new GunsmithArmProgram.Pose()), new float[3]);
        assertArmClose(helper, what + ": x", lowered[0], flat[0]);
        assertArmClose(helper, what + ": lowered by", flat[1] - lowered[1], expectedLowering);
        assertArmClose(helper, what + ": z", lowered[2], flat[2]);
    }

    /** 零件接触点带下沉量时比不带时低多少 (px)。 */
    private static float loweringAt(float tick, float boltDrop, float stockDrop) {
        float flat = GunsmithArmProgram.contactPosition(
                GunsmithArmProgram.sample(tick, 0.0F, 0.0F, new GunsmithArmProgram.Pose()), new float[3])[1];
        float lowered = GunsmithArmProgram.contactPosition(
                GunsmithArmProgram.sample(tick, boltDrop, stockDrop, new GunsmithArmProgram.Pose()), new float[3])[1];
        return flat - lowered;
    }

    private static void assertArmClose(GameTestHelper helper, String what, float actual, float expected) {
        helper.assertTrue(Math.abs(actual - expected) <= ARM_EPSILON,
                what + ": expected " + expected + ", got " + actual);
    }

    /** 逐位相同 (同一条计算路径, 不给容差)。 */
    private static void assertSamePose(GameTestHelper helper, String what, GunsmithArmProgram.Pose expected,
                                       GunsmithArmProgram.Pose actual) {
        helper.assertTrue(same(actual.yaw, expected.yaw) && same(actual.upperArm, expected.upperArm)
                        && same(actual.forearm, expected.forearm) && same(actual.toolSpin, expected.toolSpin)
                        && same(actual.claw, expected.claw) && actual.payload == expected.payload
                        && actual.spark == expected.spark,
                what + ": pose differs (upperArm " + actual.upperArm + " vs " + expected.upperArm + ", forearm "
                        + actual.forearm + " vs " + expected.forearm + ")");
    }

    private static boolean same(float a, float b) {
        return Float.floatToIntBits(a) == Float.floatToIntBits(b);
    }

    private static GunsmithBenchGunLayout.Placement clampRight() {
        GunsmithBenchGunLayout.Placement placement = GunsmithBenchGunLayout.place(CLAMP_RIGHT[0], CLAMP_RIGHT[1],
                CLAMP_RIGHT[2], CLAMP_RIGHT[3], CLAMP_RIGHT[4], CLAMP_RIGHT[5], GunsmithBenchGunLayout.NO_BORE);
        if (placement == null) {
            throw new IllegalStateException("the clamp case must be placeable");
        }
        return placement;
    }

    private static GunsmithBenchGunLayout.Placement clampLeft() {
        GunsmithBenchGunLayout.Placement placement = GunsmithBenchGunLayout.place(CLAMP_LEFT[0], CLAMP_LEFT[1],
                CLAMP_LEFT[2], CLAMP_LEFT[3], CLAMP_LEFT[4], CLAMP_LEFT[5], 0.0F);
        if (placement == null) {
            throw new IllegalStateException("the lopsided clamp case must be placeable");
        }
        return placement;
    }

    /** 下沉量用更紧的容差: 期望值是四位小数的常量相减, float 误差在 1e-6 量级。 */
    private static void assertDrop(GameTestHelper helper, String what, float actual, float expected) {
        helper.assertTrue(Math.abs(actual - expected) <= 1.0E-5F, what + ": expected " + expected + ", got " + actual);
    }

    private static GunsmithBenchGunLayout.Placement place(float[] gun) {
        GunsmithBenchGunLayout.Placement placement =
                GunsmithBenchGunLayout.place(gun[0], gun[1], gun[2], gun[3], gun[4], gun[5], gun[6]);
        if (placement == null) {
            throw new IllegalStateException("a real pack gun box must be placeable");
        }
        return placement;
    }

    /** 包围盒 8 个角经 toBench 之后的包围盒 {minX, minY, minZ, maxX, maxY, maxZ} (枪床 px)。 */
    private static float[] benchBox(GunsmithBenchGunLayout.Placement placement, float[] gun) {
        float[] box = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        float[] p = new float[3];
        for (int i = 0; i < 8; i++) {
            placement.toBench((i & 1) == 0 ? gun[0] : gun[3], (i & 2) == 0 ? gun[1] : gun[4],
                    (i & 4) == 0 ? gun[2] : gun[5], p);
            for (int a = 0; a < 3; a++) {
                box[a] = Math.min(box[a], p[a]);
                box[a + 3] = Math.max(box[a + 3], p[a]);
            }
        }
        return box;
    }

    private static void assertSize(GameTestHelper helper, String gun, GunsmithBenchGunLayout.Placement placement,
                                   float length, float target, float scale) {
        assertClose(helper, gun + " L", placement.length(), length);
        assertClose(helper, gun + " T", placement.targetLength(), target);
        assertClose(helper, gun + " k", placement.scale(), scale);
        assertClose(helper, gun + " rendered length", placement.scale() * placement.length(), target);
    }

    private static void assertTranslation(GameTestHelper helper, String gun, GunsmithBenchGunLayout.Placement placement,
                                          float x, float y, float z) {
        assertClose(helper, gun + " t.x", placement.translateX(), x);
        assertClose(helper, gun + " t.y", placement.translateY(), y);
        assertClose(helper, gun + " t.z", placement.translateZ(), z);
    }

    private static void assertBenchBox(GameTestHelper helper, String gun, GunsmithBenchGunLayout.Placement placement,
                                       float[] fixedBox, float minX, float minY, float minZ,
                                       float maxX, float maxY, float maxZ) {
        assertArrayClose(helper, gun + " bench box", benchBox(placement, fixedBox),
                new float[]{minX, minY, minZ, maxX, maxY, maxZ});
    }

    private static void assertArrayClose(GameTestHelper helper, String what, float[] actual, float[] expected) {
        helper.assertTrue(actual.length == expected.length, what + ": length " + actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertClose(helper, what + "[" + i + "]", actual[i], expected[i]);
        }
    }

    private static void assertClose(GameTestHelper helper, String what, float actual, float expected) {
        helper.assertTrue(Math.abs(actual - expected) <= EPSILON, what + ": expected " + expected + ", got " + actual);
    }
}
