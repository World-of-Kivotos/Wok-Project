package com.miningdim.champion.reward;

import com.miningdim.champion.ChampionConfig;
import com.miningdim.champion.StarRank;

import java.util.List;

/**
 * 精英怪击杀奖励池标定 (ChampionStarAffix spec 第十一章奖励与经济闸)。按初始星级把固定信用点总池 (raw)
 * 与青辉石 PvE 掉落量标定: 星级越高池越大。产出 raw 由 {@code ContributionPool.distribute} 按净伤占比瓜分后
 * 逐玩家喂 {@code grantDaily} 并入信用点衰减主闸 (不自开印钞口)。
 *
 * 信用点池 = 星级查表 × 缩放旋钮 (方案 D6): 1-5★ 保持 600×星; 6-10★ 按"4 人传奇 M4A1 击杀该星中位精英的
 * 弹药+枪匠磨损成本 × 0.9" 标定为 4,200 / 9,400 / 16,500 / 27,400 / 45,000 (抽样噪声约 ±1%, 取方案值)。
 * 表与缩放经 miningdim-champion.toml 的 [reward] 段暴露 ({@link ChampionConfig}); 本类同时保留带显式参数的
 * 纯函数重载供 GameTest 断言, 不读配置。
 *
 * 口径硬约束: 本表按战斗线最终管线标定, 与血量/减伤强耦合, 前提有两条, 缺一不可:
 *  - HE 当量帽与配件爆炸归子弹桶 (方案 C1、C2), 与本表同批生效;
 *  - 新星表 + 精英接口修正 (方案 B1、A2)。
 * 两者任一未上线, 测试端和生产服须先把 reward.creditPoolByStar 手改回 [600,1200,...,6000], 上线后再恢复默认。
 * 战斗线再改 6-10★ 的血量或减伤时必须按同一公式重算后改配置, 不能沿用旧表, 否则超发或欠发成倍偏离。
 */
public final class ChampionReward {

    private ChampionReward() {
    }

    /**
     * 1-5★ 每星信用点池基数 (600/星)。6★ 起改查 {@link #DEFAULT_CREDIT_POOL_BY_STAR}, 本常量只剩两处用途:
     * 1-5★ 表项的来源, 以及特勤击杀经验每星基数 (60 = 本值的 1/10, 见 AgentKillXp) 的锚点。
     */
    public static final long CREDIT_POOL_PER_STAR = 600L;

    /** 各星信用点固定池内置表 (raw, 下标 0 = 1★; miningdim-champion.toml 的 reward.creditPoolByStar 默认值)。 */
    public static final List<Long> DEFAULT_CREDIT_POOL_BY_STAR = List.of(
            CREDIT_POOL_PER_STAR, 2L * CREDIT_POOL_PER_STAR, 3L * CREDIT_POOL_PER_STAR,
            4L * CREDIT_POOL_PER_STAR, 5L * CREDIT_POOL_PER_STAR,
            4_200L, 9_400L, 16_500L, 27_400L, 45_000L);

    /** 信用点池整体缩放默认值 (reward.creditPoolScale)。 */
    public static final double DEFAULT_CREDIT_POOL_SCALE = 1.0D;

    /**
     * 特勤加强奖励系数默认值 (reward.agentBonusRate, 方案 D4): 加成 = 本人实际分到的池份额 × 本系数 × 等级倍率。
     * 放在奖池类而非特勤模块, 是因为它是按池份额折算的比例 (改池即等比改加成), 且配置文件归精英怪模块。
     */
    public static final double DEFAULT_AGENT_BONUS_RATE = 0.2D;

    /** 青辉石掉落起始星级 (config 默认 6★; 1-5★ 不掉青辉石)。 */
    public static final int AZURE_MIN_STAR = StarRank.CUSTOM_BLOOD_POOL_MIN_STAR;

    /** 6★ 青辉石基础掉落量; 每高一星 +2 (6★=2 … 10★=10)。本表只给"单次击杀掉落量", 每人每日青辉石产出硬上限
     *  并入经济层 {@code EconomyService.grantAzureDaily} (azure_faucet 键, 经济文档 8.5 战斗 faucet 必须并入每人
     *  每日上限; 上限值见 {@code EconomyConstants.AZURE_DAILY_FAUCET_CAP})。 */
    public static final long AZURE_BASE_AT_MIN_STAR = 2L;
    public static final long AZURE_PER_STAR_ABOVE_MIN = 2L;

    /**
     * 某星级击杀的固定信用点总池 (raw), 实时读 miningdim-champion.toml 的池表与缩放。瓜分前的池总量, 合格者按净伤
     * 占比分得后逐人经 grantDaily 入主闸 (实发随当日累计衰减)。
     *
     * @param star 初始星级 (1-10)
     * @return 固定信用点总池 raw
     */
    public static long creditPoolRaw(int star) {
        return creditPoolRaw(star, ChampionConfig.creditPoolByStar(), ChampionConfig.creditPoolScale());
    }

    /**
     * 纯函数版池查表: round(table[star-1] × scale)。
     *
     * @param star  初始星级 (1-10)
     * @param table 10 项池表 (下标 0 = 1★, 各项 &gt;=0)
     * @param scale 缩放 (&gt;=0)
     * @return 固定信用点总池 raw
     */
    public static long creditPoolRaw(int star, List<Long> table, double scale) {
        requireStar(star);
        if (table == null || table.size() != StarRank.MAX_STAR) {
            throw new IllegalArgumentException("credit pool table must have " + StarRank.MAX_STAR + " entries");
        }
        if (!(scale >= 0.0D) || Double.isInfinite(scale)) {
            throw new IllegalArgumentException("credit pool scale must be finite and >= 0, got " + scale);
        }
        long base = table.get(star - 1);
        if (base < 0L) {
            throw new IllegalArgumentException("credit pool entry for star " + star + " must be >= 0, got " + base);
        }
        return Math.round(base * scale);
    }

    /** 该星是否掉青辉石 (≥6★)。 */
    public static boolean dropsAzure(int star) {
        requireStar(star);
        return star >= AZURE_MIN_STAR;
    }

    /**
     * 某星级青辉石掉落量 (6★+): base + (star-6) × perStar; &lt;6★ 返 0。本法只算"单次击杀掉落量", 每人每日产出硬上限
     * 由经济层 {@code EconomyService.grantAzureDaily} 截断 (azure_faucet 键; 本层不做日累计)。
     *
     * @param star 初始星级 (1-10)
     * @return 青辉石掉落量 (&lt;6★ = 0)
     */
    public static long azureDrop(int star) {
        requireStar(star);
        if (star < AZURE_MIN_STAR) {
            return 0L;
        }
        return AZURE_BASE_AT_MIN_STAR + (long) (star - AZURE_MIN_STAR) * AZURE_PER_STAR_ABOVE_MIN;
    }

    private static void requireStar(int star) {
        if (star < StarRank.MIN_STAR || star > StarRank.MAX_STAR) {
            throw new IllegalArgumentException("star out of [1,10]: " + star);
        }
    }
}
