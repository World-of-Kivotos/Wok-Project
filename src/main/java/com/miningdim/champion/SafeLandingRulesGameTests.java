package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Set;

/**
 * 落点安全判定纯逻辑 GameTest (ChampionStarAffix spec 9.3 / 红线6; 批4 波0)。
 *
 * 逐条钉死 {@link SafeLandingRules#isSafeLanding} 的落点容身双格 (规则0) + 落点柱主防线 (规则1) + 邻柱贴边窗口
 * 与虚空边缘 (规则2a/2b), 以及 {@link SafeLandingRules#clampTowardOrigin} 的回退采样 (删任一条规则或改错边界必挂):
 * 覆盖脚/头格 hazard 与实心、各深度藏 lava、footing 封顶遮蔽、深 footing 前 4 格干净、通底虚空、孤柱虚空边缘、
 * 邻柱 Chebyshev 1/2 拒 3 通、窗口竖直上下界、clamp 命中与全程 DENY。mock 用内部类 {@link MockColumn} 显式摆放
 * hazard/footing, {@code floor} 铺全图地板供安全用例满足邻柱有底。
 *
 * template = "empty", batch = "champion_safe_landing"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class SafeLandingRulesGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_safe_landing";
    private static final int MIN_Y = -64;

    /**
     * 显式摆放 hazard/footing 的方块查询 mock; 未摆放的方块既非 hazard 也非 footing (= 空气)。
     * {@link #floor(int)} 铺一层全图地板面 (供需要"四周有底"的安全用例满足规则2b 邻柱虚空检查)。
     * isFooting 排除 hazard 格, 镜像真实适配层 KnockbackSafetyGuard.probeOf 的"hazard 优先非落脚"语义。
     */
    private static final class MockColumn implements SafeLandingRules.ColumnProbe {
        private final Set<String> hazards = new HashSet<>();
        private final Set<String> footings = new HashSet<>();
        private final int minY;
        private Integer floorY;

        MockColumn(int minY) {
            this.minY = minY;
        }

        MockColumn hazard(int x, int y, int z) {
            hazards.add(key(x, y, z));
            return this;
        }

        MockColumn footing(int x, int y, int z) {
            footings.add(key(x, y, z));
            return this;
        }

        /** 全图铺一层 y=该值的地板面 (任意 x,z 皆 footing, hazard 格除外)。 */
        MockColumn floor(int y) {
            this.floorY = y;
            return this;
        }

        @Override
        public boolean isHazard(int x, int y, int z) {
            return hazards.contains(key(x, y, z));
        }

        @Override
        public boolean isFooting(int x, int y, int z) {
            if (isHazard(x, y, z)) {
                return false; // 镜像适配层语义: hazard 优先, 岩浆块等绝不作落脚面。
            }
            return footings.contains(key(x, y, z)) || (floorY != null && y == floorY);
        }

        @Override
        public int minY() {
            return minY;
        }

        private static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void flatGroundIsSafe(GameTestHelper helper) {
        // 平地: 全图地板在 y-1, 无任何 hazard -> 安全 (地板面同时满足规则2b 邻柱有底)。
        MockColumn probe = new MockColumn(MIN_Y).floor(-1);
        helper.assertTrue(SafeLandingRules.isSafeLanding(probe, 0, 0, 0),
                "平地 (全图地板在 y-1, 无 hazard) 判安全");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void lavaAtEachDepthOneToFourRejects(GameTestHelper helper) {
        // 落点柱先遇 hazard 即拒: 深度 1..4 各藏一格 lava (深处铺地板排除虚空干扰), 每档都必须判死。
        for (int d = 1; d <= 4; d++) {
            MockColumn probe = new MockColumn(MIN_Y).hazard(0, -d, 0).floor(-10);
            helper.assertFalse(SafeLandingRules.isSafeLanding(probe, 0, 0, 0),
                    "落点下第 " + d + " 格藏 lava (落脚面之上先撞) 判不安全");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void footingAtDepthTwoMasksLavaBelow(GameTestHelper helper) {
        // footing 在 y-2 封顶, 其下 y-3 的 lava 被落脚面挡住 -> 安全 (中心柱遮蔽 hazard 不判死; 深处地板给邻柱有底)。
        MockColumn probe = new MockColumn(MIN_Y).footing(0, -2, 0).hazard(0, -3, 0).floor(-6);
        helper.assertTrue(SafeLandingRules.isSafeLanding(probe, 0, 0, 0),
                "footing 在第 2 格封顶, 其下 lava 被挡 -> 安全");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void footingDeeperThanFourWithCleanColumnPasses(GameTestHelper helper) {
        // 地板深于 4 格 (在 y-6), y-1..y-5 全空气无 hazard -> 落脚面搜索不受 4 格限而深探, 判安全。
        MockColumn probe = new MockColumn(MIN_Y).floor(-6);
        helper.assertTrue(SafeLandingRules.isSafeLanding(probe, 0, 0, 0),
                "footing 深于 4 格且前 4 格干净 -> 安全");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void landingCellHazardOrSolidRejects(GameTestHelper helper) {
        // 规则0 落点容身双格 (审查修复钉死): 脚/头两格藏 hazard 或实心块都必须判死 —— 规则1 从 y-1 起扫、
        // 规则2 跳过中心柱, 删规则0 则以下四例全部误判安全 (火坐落点上被瞬移进火 / clamp 塞进实心窒息)。
        MockColumn fireAtFeet = new MockColumn(MIN_Y).floor(-1).hazard(0, 0, 0);
        helper.assertFalse(SafeLandingRules.isSafeLanding(fireAtFeet, 0, 0, 0),
                "落点本体格 (脚) 藏火 -> 不安全");

        MockColumn fireAtHead = new MockColumn(MIN_Y).floor(-1).hazard(0, 1, 0);
        helper.assertFalse(SafeLandingRules.isSafeLanding(fireAtHead, 0, 0, 0),
                "头顶格藏 hazard -> 不安全");

        MockColumn solidAtFeet = new MockColumn(MIN_Y).floor(-1).footing(0, 0, 0);
        helper.assertFalse(SafeLandingRules.isSafeLanding(solidAtFeet, 0, 0, 0),
                "落点本体格为实心 -> 不安全 (禁塞墙)");

        MockColumn solidAtHead = new MockColumn(MIN_Y).floor(-1).footing(0, 1, 0);
        helper.assertFalse(SafeLandingRules.isSafeLanding(solidAtHead, 0, 0, 0),
                "头顶格为实心 -> 不安全 (卡头窒息)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void neighborVoidEdgeRejectsBeyondMarginPasses(GameTestHelper helper) {
        // 规则2b 邻柱虚空边缘 (审查修复钉死, spec 9.3 "水平距任何岩浆/虚空边缘 >=2 格"):
        // 只有中心柱有 footing、四周通底虚空 = 孤柱贴虚空边缘 -> 判死。
        MockColumn lonePillar = new MockColumn(MIN_Y).footing(0, -1, 0);
        helper.assertFalse(SafeLandingRules.isSafeLanding(lonePillar, 0, 0, 0),
                "孤柱四周通底虚空 (Chebyshev<=2 邻柱无底) -> 不安全");

        // Chebyshev<=2 的 5x5 平台有底, Chebyshev=3 环外虚空 -> 安全 (虚空在边距外不管, 严禁扩到 3)。
        MockColumn platform = new MockColumn(MIN_Y);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                platform.footing(dx, -1, dz);
            }
        }
        helper.assertTrue(SafeLandingRules.isSafeLanding(platform, 0, 0, 0),
                "5x5 平台 (边距内邻柱皆有底), 边距外虚空 -> 安全");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void voidColumnRejects(GameTestHelper helper) {
        // 中心柱通底无 footing, 扫到 minY 仍无落脚面 -> 虚空判不安全。
        MockColumn probe = new MockColumn(MIN_Y);
        helper.assertFalse(SafeLandingRules.isSafeLanding(probe, 0, 0, 0),
                "通底虚空 (无 footing 扫到 minY) 判不安全");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void neighborHazardChebyshevOneAndTwoRejectThreePasses(GameTestHelper helper) {
        // 边距=2 的边界钉死: 中心柱安全前提下 (全图地板供邻柱有底), 邻柱 Chebyshev 1/2 藏 hazard 判死, 3 判活。
        MockColumn cheb1 = new MockColumn(MIN_Y).floor(-1).hazard(1, -1, 0);
        helper.assertFalse(SafeLandingRules.isSafeLanding(cheb1, 0, 0, 0),
                "邻柱 Chebyshev 1 藏 hazard -> 不安全");

        MockColumn cheb2 = new MockColumn(MIN_Y).floor(-1).hazard(2, -1, 0);
        helper.assertFalse(SafeLandingRules.isSafeLanding(cheb2, 0, 0, 0),
                "邻柱 Chebyshev 2 藏 hazard -> 不安全 (边距边界内)");

        MockColumn cheb3 = new MockColumn(MIN_Y).floor(-1).hazard(3, -1, 0);
        helper.assertTrue(SafeLandingRules.isSafeLanding(cheb3, 0, 0, 0),
                "邻柱 Chebyshev 3 藏 hazard -> 安全 (超出边距, 严禁球形半径扩到 3)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void neighborHazardChebyshevTwoDiagonalRejects(GameTestHelper helper) {
        // 对角 (2,2) 的 Chebyshev = max(2,2) = 2, 仍在方形邻域内 -> 判死 (证实用 Chebyshev 而非欧氏/曼哈顿)。
        MockColumn probe = new MockColumn(MIN_Y).floor(-1).hazard(2, -1, 2);
        helper.assertFalse(SafeLandingRules.isSafeLanding(probe, 0, 0, 0),
                "对角邻柱 (2,2) Chebyshev=2 藏 hazard -> 不安全");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void neighborHazardWindowVerticalBounds(GameTestHelper helper) {
        // 邻柱竖直窗口 [y-4, y] 的上下界钉死: 顶界 y=0 与底界 y-4=-4 判死; 窗外 y+1=1 与 y-5=-5 判活。
        MockColumn top = new MockColumn(MIN_Y).floor(-1).hazard(1, 0, 0);
        helper.assertFalse(SafeLandingRules.isSafeLanding(top, 0, 0, 0),
                "邻柱窗口顶界 y 藏 hazard -> 不安全");

        MockColumn bottom = new MockColumn(MIN_Y).floor(-1).hazard(1, -4, 0);
        helper.assertFalse(SafeLandingRules.isSafeLanding(bottom, 0, 0, 0),
                "邻柱窗口底界 y-4 藏 hazard -> 不安全");

        MockColumn above = new MockColumn(MIN_Y).floor(-1).hazard(1, 1, 0);
        helper.assertTrue(SafeLandingRules.isSafeLanding(above, 0, 0, 0),
                "邻柱窗口之上 y+1 藏 hazard -> 安全 (且非中心柱, 不触规则0)");

        MockColumn below = new MockColumn(MIN_Y).floor(-1).hazard(1, -5, 0);
        helper.assertTrue(SafeLandingRules.isSafeLanding(below, 0, 0, 0),
                "邻柱窗口之下 y-5 藏 hazard -> 安全 (深处 hazard 兼作 2b 柱底)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void clampRetreatsToFirstSafeBlock(GameTestHelper helper) {
        // 沿 X 轴从 to=(5.5) 回退到 from=(0.5): 全图地板 + (5,-1,0) 藏 hazard -> x=5 中心柱撞 hazard 判死,
        // x=4/3 因邻柱 Chebyshev<=2 命中该 hazard 判死, 首个安全者是方块 2 (证实回退方向且非直接返 from)。
        MockColumn probe = new MockColumn(MIN_Y).floor(-1).hazard(5, -1, 0);
        int[] r = SafeLandingRules.clampTowardOrigin(probe,
                0.5D, 0.5D, 0.5D, 5.5D, 0.5D, 0.5D);
        helper.assertTrue(r != null, "clamp 应命中安全落点, 非 null");
        helper.assertTrue(r[0] == 2 && r[1] == 0 && r[2] == 0,
                "clamp 从 to 回退首个安全块 = (2,0,0), 实得 (" + r[0] + "," + r[1] + "," + r[2] + ")");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void clampAllHazardReturnsNull(GameTestHelper helper) {
        // 从 to=(3.5) 到 from=(0.5), 方块 0..3 落脚面之上均藏 lava (地板铺底排除虚空因素) -> 全程无安全点 -> null (DENY)。
        MockColumn probe = new MockColumn(MIN_Y).floor(-2)
                .hazard(0, -1, 0).hazard(1, -1, 0).hazard(2, -1, 0).hazard(3, -1, 0);
        int[] r = SafeLandingRules.clampTowardOrigin(probe,
                0.5D, 0.5D, 0.5D, 3.5D, 0.5D, 0.5D);
        helper.assertTrue(r == null, "clamp 全程 hazard 应返 null (DENY)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void clampDegenerateFromEqualsToChecksOwnBlock(GameTestHelper helper) {
        // from==to 退化: 仍采样 from 自身方块一次。安全则返该块, 不安全 (虚空) 则 null。
        MockColumn safe = new MockColumn(MIN_Y).floor(-1);
        int[] hit = SafeLandingRules.clampTowardOrigin(safe,
                0.5D, 0.5D, 0.5D, 0.5D, 0.5D, 0.5D);
        helper.assertTrue(hit != null && hit[0] == 0 && hit[1] == 0 && hit[2] == 0,
                "from==to 且安全 -> 返自身方块 (0,0,0)");

        MockColumn voidCol = new MockColumn(MIN_Y);
        int[] miss = SafeLandingRules.clampTowardOrigin(voidCol,
                0.5D, 0.5D, 0.5D, 0.5D, 0.5D, 0.5D);
        helper.assertTrue(miss == null, "from==to 且虚空 -> null");
        helper.succeed();
    }
}
