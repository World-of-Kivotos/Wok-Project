package com.miningdim.champion;

import java.util.Objects;

/**
 * 位移/击退/击飞/换位落点安全判定的纯逻辑核 (ChampionStarAffix spec 9.3 / 红线6; 批4 波0 服务端权威守卫)。
 *
 * 本类完全脱离 Forge/Minecraft 类型, 只接受 {@link ColumnProbe} 抽象方块查询, 便于纯逻辑 GameTest 精确钉死曲线
 * 与边界, 由上层 handler 用真实 Level 适配 probe 后在服务端主线程调用 (写位移经 server.execute, 不在本类内做)。
 *
 * 设计红线: 以【向下落脚柱】为主防线, 邻柱只做 {@value #EDGE_MARGIN} 格的贴边检查 (hazard 窗口 + 虚空边缘)。严禁球形半径判定 —— 岩浆密布
 * 的矿洞里球形半径会让技能近乎永不触发, 等于把词条强度归零。摔落伤害/高度差不在本规则内, 由各消费方自行限高。
 */
public final class SafeLandingRules {

    /** 落点下方 hazard 禁区深度 (格): 用于水平邻柱贴边检查的竖直窗口高度 [y-此值, y]。 */
    public static final int HAZARD_SCAN_DEPTH = 4;

    /** 水平安全边距 (Chebyshev 格): 落点四周此范围内的邻柱不得贴岩浆/火边缘。 */
    public static final int EDGE_MARGIN = 2;

    /** clamp 回退采样步长 (格): 沿位移向量按此步进回退找首个安全落点。 */
    public static final double CLAMP_STEP = 0.5D;

    private SafeLandingRules() {
    }

    /**
     * 落点柱与邻柱查询抽象。坐标为方块坐标 (整数); 上层用真实 Level 的 BlockState 适配这三个谓词。
     */
    public interface ColumnProbe {

        /** 该方块是否为 lava/flowing_lava/fire/magma_block/#fire 等致死危险块。 */
        boolean isHazard(int x, int y, int z);

        /** 该方块是否为可站立的固体落脚面 (能挡住其下的 hazard 并承托玩家)。 */
        boolean isFooting(int x, int y, int z);

        /** 维度最低建筑高度 (向下扫描的下界; 扫到此值仍无落脚面即判虚空)。 */
        int minY();
    }

    /**
     * 判定方块坐标 (x,y,z) 是否为安全落点。
     *
     * <p>规则0 落点容身 (审查修复): 落点本体两格 —— 脚 (x,y,z) 与头 (x,y+1,z) —— 必须既非 hazard 也非实心落脚块。
     * hazard 即落进火/岩浆本体 (规则1 从 y-1 起扫, 曾漏检本体格); 实心即被 clamp 塞进地形窒息 (spec 9.4 "禁塞墙",
     * 红线 6 要防的位移洗白环境致死)。以 isFooting 为"实心不可容身"代理: sturdy 顶面块必挡玩家身体。
     *
     * <p>规则1 落点柱 (主防线): 从 (x, y-1) 向下逐格扫到 {@link ColumnProbe#minY()} ——
     * <ul>
     *   <li>先遇 hazard (落脚面之上先撞岩浆/火) -> 不安全: 玩家坠落即入火海;</li>
     *   <li>先遇 footing -> 通过第一关, 并【就此封顶停扫】: 落脚面之下被挡住的 hazard 无害;</li>
     *   <li>扫到 minY 仍无 footing -> 虚空, 不安全。</li>
     * </ul>
     * 因是"向下第一命中"语义, {@value #HAZARD_SCAN_DEPTH} 格禁区天然成立: footing 浅于 4 格时只查到 footing 为止
     * (其下的 hazard 被落脚面挡住不查), footing 深于 4 格时前 4 格必为空气 (有 hazard 早已在此前命中返回)。
     *
     * <p>规则2 水平边距: 对 Chebyshev 距离 1..{@value #EDGE_MARGIN} 的每根邻柱 ——
     * <ul>
     *   <li>2a 贴边 hazard: 竖直窗口 [y-{@value #HAZARD_SCAN_DEPTH}, y] 内探 hazard, 有则不安全 (防落在岩浆池贴边);</li>
     *   <li>2b 虚空边缘 (审查修复, spec 9.3 原文"水平距任何岩浆/【虚空】边缘 >=2 格"): 邻柱从 y-1 下探到 minY 须有
     *       柱底 (footing, 或更深处 hazard 亦算"非虚空"封底 —— 深处 hazard 的贴边判死只限 2a 窗口), 通底无底即贴
     *       虚空边缘, 不安全。下探遇首个 footing/hazard 即止, 常规地形数格内终止, 真虚空柱才扫到 minY。</li>
     * </ul>
     * 方形邻域天然满足 Chebyshev<=边距, 不做任何球形半径。
     *
     * <p>摔落伤害/高度差不在本规则内, 由消费方各自限高。
     *
     * @param probe 方块查询
     * @param x     落点方块 X
     * @param y     落点方块 Y (玩家脚所在方块层, 落脚面在其下 y-1)
     * @param z     落点方块 Z
     * @return 是否为安全落点
     */
    public static boolean isSafeLanding(ColumnProbe probe, int x, int y, int z) {
        Objects.requireNonNull(probe, "probe");
        final int minY = probe.minY();

        // 规则0: 落点本体脚/头两格容身 (非 hazard 且非实心)。
        if (probe.isHazard(x, y, z) || probe.isFooting(x, y, z)
                || probe.isHazard(x, y + 1, z) || probe.isFooting(x, y + 1, z)) {
            return false;
        }

        // 规则1: 向下第一命中。footing 封顶其下不再下探, 故 hazard 禁区"只查到 footing 为止"自动满足。
        boolean footingFound = false;
        for (int yy = y - 1; yy >= minY; yy--) {
            if (probe.isFooting(x, yy, z)) {
                footingFound = true;
                break;
            }
            if (probe.isHazard(x, yy, z)) {
                return false;
            }
        }
        if (!footingFound) {
            return false;
        }

        // 规则2: 水平邻柱检查。窗口下界钳到 minY, 不探到世界外; 中心柱 (dx=dz=0) 跳过 (已由规则0/1 校验,
        // 且其下被 footing 挡住的 hazard 不能因此判死)。
        final int scanTop = y;
        final int scanBottom = Math.max(minY, y - HAZARD_SCAN_DEPTH);
        for (int dx = -EDGE_MARGIN; dx <= EDGE_MARGIN; dx++) {
            for (int dz = -EDGE_MARGIN; dz <= EDGE_MARGIN; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                final int nx = x + dx;
                final int nz = z + dz;
                // 2a: 窗口内贴边 hazard 判死 (窗口整段都查, 含被邻柱自身 footing 盖住的份 —— 宁严勿漏)。
                for (int yy = scanBottom; yy <= scanTop; yy++) {
                    if (probe.isHazard(nx, yy, nz)) {
                        return false;
                    }
                }
                // 2b: 邻柱通底虚空 = 贴虚空边缘判死。首个 footing/hazard 即"有底"止扫。
                boolean bottomed = false;
                for (int yy = y - 1; yy >= minY; yy--) {
                    if (probe.isFooting(nx, yy, nz) || probe.isHazard(nx, yy, nz)) {
                        bottomed = true;
                        break;
                    }
                }
                if (!bottomed) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 从 to 沿位移向量向 from 回退采样, 找首个安全落点。
     *
     * <p>沿参数化直线 to->from 按 {@value #CLAMP_STEP} 步进 (t 从 0 在 to 到 1 在 from), 每步取所在方块坐标,
     * 相邻重复方块去重后调 {@link #isSafeLanding}, 返回首个安全者; 采样含 from 自身方块 (t=1)。全程无安全点返回
     * null (= DENY, 消费方据此仅结算伤害、取消位移)。
     *
     * <p>去重仅比对相邻步: 直线上各坐标的方块索引随 t 单调, 离开某方块后不会再回到它, 故重复必相邻。
     *
     * @param probe 方块查询
     * @param fromX 位移起点 (安全原点) X, 世界坐标
     * @param fromY 位移起点 Y
     * @param fromZ 位移起点 Z
     * @param toX   位移终点 (待校验落点) X, 世界坐标
     * @param toY   位移终点 Y
     * @param toZ   位移终点 Z
     * @return 首个安全落点的方块坐标 {x,y,z}; 全程无安全点返回 null
     */
    public static int[] clampTowardOrigin(ColumnProbe probe,
                                          double fromX, double fromY, double fromZ,
                                          double toX, double toY, double toZ) {
        Objects.requireNonNull(probe, "probe");
        final double dx = fromX - toX;
        final double dy = fromY - toY;
        final double dz = fromZ - toZ;
        final double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // steps>=1 保证 from==to 退化时仍采样一次且 t=i/steps 不除零; ceil 保证末步恰命中 from。
        int steps = (int) Math.ceil(dist / CLAMP_STEP);
        if (steps < 1) {
            steps = 1;
        }

        int lastBx = Integer.MIN_VALUE;
        int lastBy = Integer.MIN_VALUE;
        int lastBz = Integer.MIN_VALUE;
        boolean hasLast = false;
        for (int i = 0; i <= steps; i++) {
            final double t = (double) i / steps;
            final int bx = (int) Math.floor(toX + dx * t);
            final int by = (int) Math.floor(toY + dy * t);
            final int bz = (int) Math.floor(toZ + dz * t);
            if (hasLast && bx == lastBx && by == lastBy && bz == lastBz) {
                continue;
            }
            lastBx = bx;
            lastBy = by;
            lastBz = bz;
            hasLast = true;
            if (isSafeLanding(probe, bx, by, bz)) {
                return new int[]{bx, by, bz};
            }
        }
        return null;
    }
}
