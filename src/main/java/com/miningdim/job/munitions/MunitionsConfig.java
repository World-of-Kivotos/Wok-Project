package com.miningdim.job.munitions;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprint;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPartRarity;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * 军火商 SERVER 级配置 spec 持有者 (Munitions_Job_DesignSpec 十/C6 硬约束: 全部平衡数值进 ForgeConfigSpec,
 * 业务类内硬编码字面量即缺陷)。覆盖: 产能曲线 (台数/每台速率/缓冲, 6.1) / 双推进剂配方 (直造 7 铜 16 火药 -> 40 发,
 * 提炼 -> 70 发, 四章) / 工费 (1.5 CP/发, 整数化为 ×10 锚价 = 15/10 发, 九章 sink) / 各口径商店价与售价及缩产系数
 * (6.3) / 速率到 tick 换算 (PENDING 11.3) / 台子与图纸的系统采购价 (6.4, [shop] 段) 的唯一数据源。
 *
 * 本任务铁律: 不修改中央 config.MiningServerConfig。故军火商自带独立 SERVER spec (文件 miningdim-munitions.toml),
 * 由 {@link MunitionsSystem#register} 经 ModLoadingContext.registerConfig 注册。业务经 *.get() 实时读取不缓存。
 *
 * 经济铁律 (Munitions_Job_DesignSpec 九 / 十一 PENDING 4): 工费销毁 = 弹药链唯一信用点 sink; 卖弹是 P2P 非 faucet。
 * 工费 1.5 CP/发 与 {@link com.miningdim.economy.IEconomyService#tryCharge} 收 long 整数冲突: 用 ×10 锚价整数化
 * (WORK_FEE_PER_TEN_ROUNDS = 15, 即每 10 发扣 15 CP), 在批结算点按产弹量聚合扣费, 永不对单发传小数。
 */
public final class MunitionsConfig {

    public static final ForgeConfigSpec SPEC;

    // ---- 6.1 产能曲线: 制造台数 (L1-10) ----
    public static final ForgeConfigSpec.IntValue TABLE_COUNT_L1;
    public static final ForgeConfigSpec.IntValue TABLE_COUNT_L2;
    public static final ForgeConfigSpec.IntValue TABLE_COUNT_L3;
    public static final ForgeConfigSpec.IntValue TABLE_COUNT_L4;
    public static final ForgeConfigSpec.IntValue TABLE_COUNT_L5;
    public static final ForgeConfigSpec.IntValue TABLE_COUNT_L6;
    public static final ForgeConfigSpec.IntValue TABLE_COUNT_L7;
    public static final ForgeConfigSpec.IntValue TABLE_COUNT_L8;
    public static final ForgeConfigSpec.IntValue TABLE_COUNT_L9;
    public static final ForgeConfigSpec.IntValue TABLE_COUNT_L10;

    // ---- 6.1 每台速率 (发/时·步枪当量; L1-10) ----
    public static final ForgeConfigSpec.IntValue RATE_L1;
    public static final ForgeConfigSpec.IntValue RATE_L2;
    public static final ForgeConfigSpec.IntValue RATE_L3;
    public static final ForgeConfigSpec.IntValue RATE_L4;
    public static final ForgeConfigSpec.IntValue RATE_L5;
    public static final ForgeConfigSpec.IntValue RATE_L6;
    public static final ForgeConfigSpec.IntValue RATE_L7;
    public static final ForgeConfigSpec.IntValue RATE_L8;
    public static final ForgeConfigSpec.IntValue RATE_L9;
    public static final ForgeConfigSpec.IntValue RATE_L10;

    // ---- 6.1 缓冲/台 (发; L1-10) ----
    public static final ForgeConfigSpec.IntValue BUFFER_L1;
    public static final ForgeConfigSpec.IntValue BUFFER_L2;
    public static final ForgeConfigSpec.IntValue BUFFER_L3;
    public static final ForgeConfigSpec.IntValue BUFFER_L4;
    public static final ForgeConfigSpec.IntValue BUFFER_L5;
    public static final ForgeConfigSpec.IntValue BUFFER_L6;
    public static final ForgeConfigSpec.IntValue BUFFER_L7;
    public static final ForgeConfigSpec.IntValue BUFFER_L8;
    public static final ForgeConfigSpec.IntValue BUFFER_L9;
    public static final ForgeConfigSpec.IntValue BUFFER_L10;

    // ---- 四章: 单批弹药零件配方 (底火 + 弹壳 + 弹头 + 发射药) ----
    public static final ForgeConfigSpec.IntValue RECIPE_PRIMER_COST;
    public static final ForgeConfigSpec.IntValue RECIPE_CASING_COST;
    public static final ForgeConfigSpec.IntValue RECIPE_BULLET_HEAD_COST;
    public static final ForgeConfigSpec.IntValue RECIPE_PROPELLANT_COST;
    /** 直造 (L1-5) 单批产出步枪弹基准发数。 */
    public static final ForgeConfigSpec.IntValue FE_PER_RIFLE_EQUIVALENT_ROUND;
    public static final ForgeConfigSpec.IntValue BENCH_ENERGY_CAPACITY;
    public static final ForgeConfigSpec.IntValue DIRECT_ROUNDS_PER_BATCH;
    /** 提炼 (L6+) 单批产出步枪弹基准发数 (翻倍, 利润质变线)。 */
    public static final ForgeConfigSpec.IntValue REFINED_ROUNDS_PER_BATCH;
    /** 解锁提炼 (发射药翻倍) 的军火商等级 (六章 L6)。 */
    public static final ForgeConfigSpec.IntValue REFINE_UNLOCK_LEVEL;

    // ---- 3A 章: 枪匠冲压子系统总开关 (WIP) ----
    /**
     * 枪匠冲压 (gunsmith press) 功能门 (审查 C-2/G-1~G-4): 子系统属 3A 章试作 —— 输入材料物品未注册、
     * 生存获取链未落地、TACZ 加伤数值未过战力评审, 默认关闭。启用前置: 完善分支补齐材料校验/归属门控/
     * 原子结算/破坏掉落/测试, 且加伤系数过经济与战力总表评审。
     */
    public static final ForgeConfigSpec.BooleanValue GUNSMITH_ENABLED;

    /**
     * 爆头品质复利帽 (审查 TACZ-BAL-1): 只钳枪机伤害品质系数 x 枪管爆头品质系数的复利, 双传奇 1.50 x 1.50 = 2.25
     * 被钳到 1.8。势力组件的伤害与爆头倍率在帽外等比作用于躯干和爆头，避免高伤组件把爆头倍率反解到 1 以下。
     * 本帽只管品质复利, PvP 爆头发数另行约束; 装配预览、成品 tooltip 与 WebUI 显示的爆头倍率都已带本帽。
     */
    public static final ForgeConfigSpec.DoubleValue GUNSMITH_HEADSHOT_DAMAGE_CAP;

    /**
     * 整枪伤害乘子总帽 (审查 27): GunsmithGunStats.damage() = 单件品质系数 (上限 1.50) x 各组件伤害乘数连乘。
     * 本帽是 PvE 的"品质 x 组件"帽, TaCZ 配件在帽外: 默认 2.25 下 AK 首段 9 x 2.25 = 20.25, PvP 发数另行约束,
     * 不靠本帽。红东高压导气与赤雪-A 枪机已互斥, 新装配最高连乘为传奇枪机 1.50 x 传奇红东 1.60 = 2.40;
     * 互斥落地前装出的双加伤 AK 可达 1.50 x 1.60 x 1.25 = 3.00, 同样被本帽钳住。
     * {@link #GUNSMITH_HEADSHOT_DAMAGE_CAP} 只钳品质复利, 两帽各管一段串联生效, 调参时须同盘看。
     */
    public static final ForgeConfigSpec.DoubleValue GUNSMITH_DAMAGE_MULTIPLIER_CAP;

    // ---- 3A 章: 枪匠品质等级门 + 装配等级门 + 经济 sink (F048 补齐, 数值随 6.1 口径解锁曲线初定, 待主控事后调) ----
    /** 各品质档的军火商解锁等级 (对齐 6.1 口径解锁曲线: 手枪 L1 ... 特种弹 L10 毕业; 传奇零件与特种弹同为 L10 毕业档)。 */
    public static final ForgeConfigSpec.IntValue QUALITY_UNLOCK_COMMON;
    public static final ForgeConfigSpec.IntValue QUALITY_UNLOCK_IMPROVED;
    public static final ForgeConfigSpec.IntValue QUALITY_UNLOCK_MILSPEC;
    public static final ForgeConfigSpec.IntValue QUALITY_UNLOCK_PRECISION;
    public static final ForgeConfigSpec.IntValue QUALITY_UNLOCK_LEGENDARY;
    /** 单次冲压的信用点工费基数; 实扣 = 基数 x GunsmithPartQuality.materialMultiplier() (1/2/4/7/10), 与弹药链工费同为销毁型 sink。 */
    public static final ForgeConfigSpec.IntValue PRESS_WORK_FEE_CREDITS;
    /** 解锁枪械装配的军火商等级。 */
    public static final ForgeConfigSpec.IntValue ASSEMBLY_UNLOCK_LEVEL;
    /** 单次装配的信用点工费 (销毁型 sink, 与冲压工费独立结算)。 */
    public static final ForgeConfigSpec.IntValue ASSEMBLY_WORK_FEE_CREDITS;
    /** 解锁枪械维修的军火商等级 (审查 24: 维修与装配同在装配台但门槛独立, 维修是低阶服务先于装配开放)。 */
    public static final ForgeConfigSpec.IntValue REPAIR_UNLOCK_LEVEL;
    /** 单次维修的信用点工费 (销毁型 sink, 与装配工费独立结算; 维修还额外吃掉一件替换零件与永久最大耐久)。 */
    public static final ForgeConfigSpec.IntValue REPAIR_WORK_FEE_CREDITS;

    // ---- 3A 章: 组件稀有度加价与等级门 (审查 29: 冲压原本只看部件类型与品质, 势力/特殊组件与基础组件同价) ----
    /** 各稀有度档的冲压工费加价倍率 (乘在品质工费之上; STANDARD 即基础组件, 保持 1.0 基准)。 */
    public static final ForgeConfigSpec.DoubleValue PRESS_RARITY_FEE_MULTIPLIER_STANDARD;
    public static final ForgeConfigSpec.DoubleValue PRESS_RARITY_FEE_MULTIPLIER_MODIFIED;
    public static final ForgeConfigSpec.DoubleValue PRESS_RARITY_FEE_MULTIPLIER_SPECIAL;
    public static final ForgeConfigSpec.DoubleValue PRESS_RARITY_FEE_MULTIPLIER_ADVANCED;
    public static final ForgeConfigSpec.DoubleValue PRESS_RARITY_FEE_MULTIPLIER_PROTOTYPE;
    /** 各稀有度档组件解锁冲压所需的军火商等级 (与品质门同一条 1/4/6/8/10 曲线, 不新增锚点)。 */
    public static final ForgeConfigSpec.IntValue RARITY_UNLOCK_STANDARD;
    public static final ForgeConfigSpec.IntValue RARITY_UNLOCK_MODIFIED;
    public static final ForgeConfigSpec.IntValue RARITY_UNLOCK_SPECIAL;
    public static final ForgeConfigSpec.IntValue RARITY_UNLOCK_ADVANCED;
    public static final ForgeConfigSpec.IntValue RARITY_UNLOCK_PROTOTYPE;

    // ---- 枪械耐久：按枪匠平台分类，每次维修永久降低最大耐久 ----
    public static final ForgeConfigSpec.IntValue GUN_DURABILITY_AR;
    public static final ForgeConfigSpec.IntValue GUN_DURABILITY_AK;
    public static final ForgeConfigSpec.IntValue GUN_DURABILITY_PISTOL;
    public static final ForgeConfigSpec.IntValue GUN_DURABILITY_BULLPUP;
    public static final ForgeConfigSpec.IntValue GUN_DURABILITY_MARKSMAN;
    public static final ForgeConfigSpec.IntValue GUN_DURABILITY_SNIPER;
    public static final ForgeConfigSpec.IntValue GUN_DURABILITY_MACHINE_GUN;
    public static final ForgeConfigSpec.IntValue GUN_DURABILITY_SHOTGUN;
    public static final ForgeConfigSpec.IntValue GUN_DURABILITY_SMG;

    public static final ForgeConfigSpec.DoubleValue GUN_REPAIR_LOSS_AR;
    public static final ForgeConfigSpec.DoubleValue GUN_REPAIR_LOSS_AK;
    public static final ForgeConfigSpec.DoubleValue GUN_REPAIR_LOSS_PISTOL;
    public static final ForgeConfigSpec.DoubleValue GUN_REPAIR_LOSS_BULLPUP;
    public static final ForgeConfigSpec.DoubleValue GUN_REPAIR_LOSS_MARKSMAN;
    public static final ForgeConfigSpec.DoubleValue GUN_REPAIR_LOSS_SNIPER;
    public static final ForgeConfigSpec.DoubleValue GUN_REPAIR_LOSS_MACHINE_GUN;
    public static final ForgeConfigSpec.DoubleValue GUN_REPAIR_LOSS_SHOTGUN;
    public static final ForgeConfigSpec.DoubleValue GUN_REPAIR_LOSS_SMG;
    public static final ForgeConfigSpec.DoubleValue GUN_REPAIR_MINIMUM_RATIO;

    // ---- TaCZ 子弹爆炸: 配件开启的爆炸当量帽 + 子弹爆炸对玩家的伤害 (不受 gunsmithEnabled 门控) ----
    /**
     * 配件开启的爆炸 (HE 等弹头改装; 枪原生不爆炸) 每颗弹丸的爆炸伤害上限比例 (无量纲):
     * 上限 = min(缓存模板伤害, 枪原生子弹伤害 ÷ max(1, 弹丸数) × 本比例), 即每颗弹丸的爆炸不超过它自己的直伤。
     * 设 0 即关闭配件爆炸; RPG7、M320 这类原生爆炸武器不受影响。落点见 HeExplosionCapHandler。
     */
    public static final ForgeConfigSpec.DoubleValue HE_EXPLOSION_PER_PROJECTILE_CAP_RATIO;

    /**
     * TaCZ 子弹爆炸 (直接实体是 TaCZ 子弹、带爆炸标签、攻击者是玩家) 对玩家的伤害系数 (无量纲):
     * 0 = 直接取消 (射手自伤与队友误伤归零), 1 = TaCZ 原值。
     * 落点见 BulletExplosionPlayerDamageHandler。
     */
    public static final ForgeConfigSpec.DoubleValue BULLET_EXPLOSION_SCALE;

    // ---- 九章: 工费 sink (1.5 CP/发, ×10 锚价整数化为 15/10 发) ----
    /** 每 10 发产弹扣的信用点工费 (整数化锚价; 实发 1.5/发 = 15/10 发, 销毁 = sink)。 */
    public static final ForgeConfigSpec.IntValue WORK_FEE_PER_TEN_ROUNDS;

    // ---- 11.3 PENDING: 速率到 tick 换算 (每台每多少 tick 产 1 发步枪当量) ----
    /**
     * 速率表是 "发/时·步枪当量" (6.1); 离线追算需把它换算成每发耗时 tick。本值是 "1 小时折算多少 tick" 的基准
     * (默认 72000 = 现实 1 小时实时, 非 MC 游戏日)。每发 tick = TICKS_PER_RATE_HOUR / ratePerTable。
     * PENDING 11.3: 生成耗时绝对值上线标定; 此处给保守初值, config 可调。
     */
    public static final ForgeConfigSpec.IntValue TICKS_PER_RATE_HOUR;

    // ---- 6.3 各口径商店价 / 军火商售价 (售价 = 商店 75%) + 缩产系数 ----
    // 缩产系数 = 单发料重导致的出弹数缩放 (步枪基准 1.0; 高阶弹 < 1.0, 四章 "单发料重出弹数按比例减")。
    public static final ForgeConfigSpec.IntValue SHOP_PRICE_PISTOL;
    public static final ForgeConfigSpec.IntValue SELL_PRICE_PISTOL;
    public static final ForgeConfigSpec.DoubleValue YIELD_FACTOR_PISTOL;

    public static final ForgeConfigSpec.IntValue SHOP_PRICE_RIFLE;
    public static final ForgeConfigSpec.IntValue SELL_PRICE_RIFLE;
    public static final ForgeConfigSpec.DoubleValue YIELD_FACTOR_RIFLE;

    public static final ForgeConfigSpec.IntValue SHOP_PRICE_BATTLE;
    public static final ForgeConfigSpec.IntValue SELL_PRICE_BATTLE;
    public static final ForgeConfigSpec.DoubleValue YIELD_FACTOR_BATTLE;

    public static final ForgeConfigSpec.IntValue SHOP_PRICE_SHOTGUN;
    public static final ForgeConfigSpec.IntValue SELL_PRICE_SHOTGUN;
    public static final ForgeConfigSpec.DoubleValue YIELD_FACTOR_SHOTGUN;

    public static final ForgeConfigSpec.IntValue SHOP_PRICE_SNIPER;
    public static final ForgeConfigSpec.IntValue SELL_PRICE_SNIPER;
    public static final ForgeConfigSpec.DoubleValue YIELD_FACTOR_SNIPER;

    public static final ForgeConfigSpec.IntValue SHOP_PRICE_BIG_PISTOL;
    public static final ForgeConfigSpec.IntValue SELL_PRICE_BIG_PISTOL;
    public static final ForgeConfigSpec.DoubleValue YIELD_FACTOR_BIG_PISTOL;

    public static final ForgeConfigSpec.IntValue SHOP_PRICE_ANTI_MATERIEL;
    public static final ForgeConfigSpec.IntValue SELL_PRICE_ANTI_MATERIEL;
    public static final ForgeConfigSpec.DoubleValue YIELD_FACTOR_ANTI_MATERIEL;

    public static final ForgeConfigSpec.IntValue SHOP_PRICE_EXPLOSIVE;
    public static final ForgeConfigSpec.IntValue SELL_PRICE_EXPLOSIVE;
    public static final ForgeConfigSpec.DoubleValue YIELD_FACTOR_EXPLOSIVE;

    public static final ForgeConfigSpec.IntValue SHOP_PRICE_SPECIAL;
    public static final ForgeConfigSpec.IntValue SELL_PRICE_SPECIAL;
    public static final ForgeConfigSpec.DoubleValue YIELD_FACTOR_SPECIAL;

    // ---- 七章: 产弹经验 (谁产谁得, 按产出弹量给原始经验; 框架管衰减/翻日/软上限) ----
    /** 每发步枪当量产出给的原始经验 (×千分位避免每发 < 1; 实际入账 = floor(rounds × perRoundXp / 1000))。 */
    public static final ForgeConfigSpec.IntValue PRODUCE_XP_PER_ROUND_MILLI;

    // ---- 6.4 系统采购: 军火商面板向系统购买台子与图纸的信用点售价 (销毁型 sink) ----
    // 等级门不在这里: 它由台档/品质/装配/口径这些既有配置推导 (见 MunitionsShop), 单开一组键只会与推导源漂移。
    /** 旧注册名 munitions_bench (存量兼容的全档台, 有效等级上限 L10)。 */
    public static final ForgeConfigSpec.IntValue SHOP_BENCH_LEGACY_PRICE;
    public static final ForgeConfigSpec.IntValue SHOP_BENCH_MEDIUM_PRICE;
    public static final ForgeConfigSpec.IntValue SHOP_BENCH_HIGH_PRICE;
    public static final ForgeConfigSpec.IntValue SHOP_BENCH_SUPERIOR_PRICE;
    public static final ForgeConfigSpec.IntValue SHOP_BENCH_TRANSCENDENT_PRICE;
    public static final ForgeConfigSpec.IntValue SHOP_BENCH_RADIANT_PRICE;
    public static final ForgeConfigSpec.IntValue SHOP_GUNSMITH_PRESS_PRICE;
    public static final ForgeConfigSpec.IntValue SHOP_GUNSMITH_ASSEMBLY_BENCH_PRICE;
    /** 每张枪匠图纸一个键 ([shop.blueprints] 段, 键名 = 枚举名小写); 按枚举遍历生成, 新增图纸自动长出新键。 */
    private static final Map<GunsmithBlueprint, ForgeConfigSpec.IntValue> SHOP_BLUEPRINT_PRICES;

    /** 系统采购价的取值上界: 高于它的数已经没有经济意义, 只会是手滑多打了几个 0。 */
    private static final int SHOP_PRICE_MAX = 100_000_000;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("capacity");
        b.comment("6.1 capacity curve: manufacturing tables owned per level (向系统购买, 受等级上限约束).");
        TABLE_COUNT_L1 = b.defineInRange("tableCountL1", 1, 0, 64);
        TABLE_COUNT_L2 = b.defineInRange("tableCountL2", 1, 0, 64);
        TABLE_COUNT_L3 = b.defineInRange("tableCountL3", 2, 0, 64);
        TABLE_COUNT_L4 = b.defineInRange("tableCountL4", 2, 0, 64);
        TABLE_COUNT_L5 = b.defineInRange("tableCountL5", 3, 0, 64);
        TABLE_COUNT_L6 = b.defineInRange("tableCountL6", 3, 0, 64);
        TABLE_COUNT_L7 = b.defineInRange("tableCountL7", 4, 0, 64);
        TABLE_COUNT_L8 = b.defineInRange("tableCountL8", 4, 0, 64);
        TABLE_COUNT_L9 = b.defineInRange("tableCountL9", 5, 0, 64);
        TABLE_COUNT_L10 = b.defineInRange("tableCountL10", 6, 0, 64);

        b.comment("6.1 per-table production rate (rounds/hour, rifle-equivalent).");
        RATE_L1 = b.defineInRange("rateL1", 50, 1, 1000000);
        RATE_L2 = b.defineInRange("rateL2", 65, 1, 1000000);
        RATE_L3 = b.defineInRange("rateL3", 80, 1, 1000000);
        RATE_L4 = b.defineInRange("rateL4", 95, 1, 1000000);
        RATE_L5 = b.defineInRange("rateL5", 110, 1, 1000000);
        RATE_L6 = b.defineInRange("rateL6", 130, 1, 1000000);
        RATE_L7 = b.defineInRange("rateL7", 150, 1, 1000000);
        RATE_L8 = b.defineInRange("rateL8", 170, 1, 1000000);
        RATE_L9 = b.defineInRange("rateL9", 190, 1, 1000000);
        RATE_L10 = b.defineInRange("rateL10", 210, 1, 1000000);

        b.comment("6.1 buffer per table (rounds); buffer full stops production (天然离线产量上限).");
        BUFFER_L1 = b.defineInRange("bufferL1", 500, 1, 10000000);
        BUFFER_L2 = b.defineInRange("bufferL2", 650, 1, 10000000);
        BUFFER_L3 = b.defineInRange("bufferL3", 800, 1, 10000000);
        BUFFER_L4 = b.defineInRange("bufferL4", 1000, 1, 10000000);
        BUFFER_L5 = b.defineInRange("bufferL5", 1300, 1, 10000000);
        BUFFER_L6 = b.defineInRange("bufferL6", 1600, 1, 10000000);
        BUFFER_L7 = b.defineInRange("bufferL7", 2000, 1, 10000000);
        BUFFER_L8 = b.defineInRange("bufferL8", 2500, 1, 10000000);
        BUFFER_L9 = b.defineInRange("bufferL9", 3200, 1, 10000000);
        BUFFER_L10 = b.defineInRange("bufferL10", 4000, 1, 10000000);
        b.pop();

        b.push("recipe");
        // 四件套成本对齐设计文档四章 "7 铜 + 16 火药 -> 40 发" (审查 M-4): 合成表 底火=2铜/弹壳=3铜/弹头=2铜
        // (每批各 1, 共 7 铜) + 发射药=8火药 (每批 2, 共 16 火药)。改配方或本组 cost 必须同步核对经济总表。
        b.comment("4. ammunition parts recipe: primer + casing + bullet head + propellant."
                + " Batch cost mirrors design spec 7 copper + 16 gunpowder -> 40 rounds.");
        RECIPE_PRIMER_COST = b.comment("Primers consumed per production batch (1 primer = 2 copper)")
                .defineInRange("primerCost", 1, 1, 64);
        RECIPE_CASING_COST = b.comment("Casings consumed per production batch (1 casing = 3 copper)")
                .defineInRange("casingCost", 1, 1, 64);
        RECIPE_BULLET_HEAD_COST = b.comment("Bullet heads consumed per production batch (1 head = 2 copper)")
                .defineInRange("bulletHeadCost", 1, 1, 64);
        RECIPE_PROPELLANT_COST = b.comment("Propellant consumed per production batch (1 propellant = 8 gunpowder)")
                .defineInRange("propellantCost", 2, 1, 64);
        GUNSMITH_ENABLED = b.comment("Enable the gunsmith press subsystem (WIP chapter 3A; keep false until"
                        + " material items, survival chain, gating, damage coefficients and economy sink pass review)")
                .define("gunsmithEnabled", false);
        GUNSMITH_HEADSHOT_DAMAGE_CAP = b.comment("Quality-compounding headshot cap: clamps only bolt-quality x"
                        + " barrel-headshot-quality (legendary 1.50 x 1.50 = 2.25 is clamped to 1.8); faction component"
                        + " damage and headshot bonuses stay outside this cap. PvP headshot kill counts are constrained"
                        + " separately; 2.25 disables this cap.")
                .defineInRange("gunsmithHeadshotDamageCap", 1.8D, 1.0D, 2.25D);
        GUNSMITH_DAMAGE_MULTIPLIER_CAP = b.comment("整枪伤害乘子总帽 (无量纲倍率): 钳住 品质系数 x 各组件伤害乘数",
                        "的连乘结果, 是 PvE 的品质 x 组件帽, TaCZ 配件在帽外。默认 2.25 = 品质上限 1.50 x 单件强力组件",
                        "1.50, AK47 首段 9 x 2.25 = 20.25; PvP 发数另行约束, 不靠本帽。上界 4.0 高于当前链路可达的",
                        "3.00 (互斥前装出的双加伤 AK), 设到上界等于关掉总帽。")
                .defineInRange("gunsmithDamageMultiplierCap", 2.25D, 1.0D, 4.0D);
        FE_PER_RIFLE_EQUIVALENT_ROUND = b.comment(
                        "FE charged per rifle-equivalent round. Power is billed on the rifle-equivalent",
                        "count rather than the actual round count, so a batch costs the same regardless of",
                        "caliber: a large caliber yields fewer rounds but each costs proportionally more.",
                        "Billing per actual round instead would make 50BMG ten times more power-efficient",
                        "than rifle ammo and kill every small caliber.")
                .defineInRange("fePerRifleEquivalentRound", 100_000, 0, 100_000_000);
        BENCH_ENERGY_CAPACITY = b.comment(
                        "Internal FE buffer of a munitions bench. It bounds how much offline production can",
                        "be caught up in one settlement: the grid does not run while chunks are unloaded, so",
                        "whatever was buffered before logout is all the power the catch-up can spend.")
                .defineInRange("benchEnergyCapacity", 32_000_000, 1, 2_000_000_000);
        DIRECT_ROUNDS_PER_BATCH = b.comment("Rounds per batch via direct crafting (L1-5, half yield)")
                .defineInRange("directRoundsPerBatch", 40, 1, 100000);
        REFINED_ROUNDS_PER_BATCH = b.comment("Rounds per batch via refining into propellant (L6+, double yield, profit inflection)")
                .defineInRange("refinedRoundsPerBatch", 70, 1, 100000);
        REFINE_UNLOCK_LEVEL = b.comment("Munitions level that unlocks propellant refining (6.1: L6)")
                .defineInRange("refineUnlockLevel", 6, 1, 10);
        b.pop();

        b.push("gunDurability");
        b.comment("Maximum durability of a newly assembled gun, measured in successful shots."
                + " Shotgun pellet count does not multiply wear; burst fire consumes one point per round.");
        GUN_DURABILITY_AR = b.defineInRange("arMaximum", 2000, 1, 10000000);
        GUN_DURABILITY_AK = b.defineInRange("akMaximum", 2400, 1, 10000000);
        GUN_DURABILITY_PISTOL = b.defineInRange("pistolMaximum", 1400, 1, 10000000);
        GUN_DURABILITY_BULLPUP = b.defineInRange("bullpupMaximum", 1900, 1, 10000000);
        GUN_DURABILITY_MARKSMAN = b.defineInRange("marksmanMaximum", 1000, 1, 10000000);
        GUN_DURABILITY_SNIPER = b.defineInRange("sniperMaximum", 800, 1, 10000000);
        GUN_DURABILITY_MACHINE_GUN = b.defineInRange("machineGunMaximum", 3500, 1, 10000000);
        GUN_DURABILITY_SHOTGUN = b.defineInRange("shotgunMaximum", 600, 1, 10000000);
        GUN_DURABILITY_SMG = b.defineInRange("smgMaximum", 2500, 1, 10000000);

        b.comment("Permanent maximum-durability loss per repair, expressed as a fraction of the gun's"
                + " original maximum. The loss is deterministic and platform-specific.");
        GUN_REPAIR_LOSS_AR = b.defineInRange("arRepairLoss", 0.10D, 0.001D, 0.90D);
        GUN_REPAIR_LOSS_AK = b.defineInRange("akRepairLoss", 0.08D, 0.001D, 0.90D);
        GUN_REPAIR_LOSS_PISTOL = b.defineInRange("pistolRepairLoss", 0.10D, 0.001D, 0.90D);
        GUN_REPAIR_LOSS_BULLPUP = b.defineInRange("bullpupRepairLoss", 0.10D, 0.001D, 0.90D);
        GUN_REPAIR_LOSS_MARKSMAN = b.defineInRange("marksmanRepairLoss", 0.12D, 0.001D, 0.90D);
        GUN_REPAIR_LOSS_SNIPER = b.defineInRange("sniperRepairLoss", 0.08D, 0.001D, 0.90D);
        GUN_REPAIR_LOSS_MACHINE_GUN = b.defineInRange("machineGunRepairLoss", 0.12D, 0.001D, 0.90D);
        GUN_REPAIR_LOSS_SHOTGUN = b.defineInRange("shotgunRepairLoss", 0.08D, 0.001D, 0.90D);
        GUN_REPAIR_LOSS_SMG = b.defineInRange("smgRepairLoss", 0.12D, 0.001D, 0.90D);
        GUN_REPAIR_MINIMUM_RATIO = b.comment("Lowest maintainable maximum as a fraction of the original maximum."
                        + " At this floor the gun can no longer be repaired.")
                .defineInRange("minimumRemainingRatio", 0.30D, 0.01D, 1.0D);
        b.pop();

        b.push("explosion");
        b.comment("TaCZ 子弹爆炸平衡 (对全部 TaCZ 枪生效, 不受 gunsmithEnabled 门控)。");
        HE_EXPLOSION_PER_PROJECTILE_CAP_RATIO = b.comment(
                        "配件开启的爆炸 (HE 等弹头改装, 枪原生不爆炸) 每颗弹丸的爆炸伤害上限比例:",
                        "上限 = min(模板爆炸伤害, 枪原生子弹伤害 / max(1, 弹丸数) x 本比例)。默认 1.0 = 每颗弹丸的爆炸",
                        "不超过它自己的直伤 (M1014 每颗上限 5.0, 一发合计 40); 0 = 关闭配件爆炸。模板值是",
                        "天花板, 比例再大每颗爆炸也不超过模板值。",
                        "RPG7、M320 等原生爆炸武器不受影响。")
                .defineInRange("heExplosionPerProjectileCapRatio", 1.0D, 0.0D, 20.0D);
        BULLET_EXPLOSION_SCALE = b.comment(
                        "TaCZ 子弹爆炸 (直接实体是 TaCZ 子弹、爆炸标签、攻击者是玩家) 对玩家的伤害系数。",
                        "默认 0 = 直接取消: 射手自伤与队友误伤归零。",
                        "1 = TaCZ 原值; 原生爆炸武器 (RPG7 等) 同样适用。对非玩家目标不生效, PvE 不变。")
                .defineInRange("bulletExplosionScale", 0.0D, 0.0D, 1.0D);
        b.pop();

        b.push("workFee");
        b.comment("9. work-fee sink: 1.5 CP destroyed per round (×10 anchor integerized = 15 per 10 rounds). 弹药链唯一信用点 sink.");
        WORK_FEE_PER_TEN_ROUNDS = b.comment("Credits charged (destroyed) per 10 rounds produced; 1.5/round = 15/10 rounds")
                .defineInRange("perTenRounds", 15, 0, 100000);
        b.pop();

        b.push("gunsmith");
        b.comment("3A. gunsmith quality unlock levels + assembly unlock level + work-fee sinks (F048). Aligned to the"
                + " 6.1 caliber unlock curve (pistol L1 ... special-round L10 graduation); legendary parts share the"
                + " L10 graduation tier with special rounds.");
        QUALITY_UNLOCK_COMMON = b.defineInRange("qualityUnlockCommon", 1, 1, 10);
        QUALITY_UNLOCK_IMPROVED = b.defineInRange("qualityUnlockImproved", 4, 1, 10);
        QUALITY_UNLOCK_MILSPEC = b.defineInRange("qualityUnlockMilspec", 6, 1, 10);
        QUALITY_UNLOCK_PRECISION = b.defineInRange("qualityUnlockPrecision", 8, 1, 10);
        QUALITY_UNLOCK_LEGENDARY = b.defineInRange("qualityUnlockLegendary", 10, 1, 10);
        PRESS_WORK_FEE_CREDITS = b.comment("Base credits charged (destroyed) per press run; actual charge ="
                        + " base x GunsmithPartQuality.materialMultiplier() (1/2/4/7/10). Sink mirrors the"
                        + " ammunition chain's per-round work fee.")
                .defineInRange("pressWorkFeeCredits", 200, 0, 1000000);
        ASSEMBLY_UNLOCK_LEVEL = b.defineInRange("assemblyUnlockLevel", 5, 1, 10);
        ASSEMBLY_WORK_FEE_CREDITS = b.defineInRange("assemblyWorkFeeCredits", 5000, 0, 100000000);
        REPAIR_UNLOCK_LEVEL = b.comment("解锁枪械维修的军火商等级 (1-10)。默认 4, 比装配 (L5) 早一级开放:",
                        "维修是修别人的枪的低阶服务, 但仍须有门, 否则 L1 号就能开免费修枪铺架空耐久 sink。")
                .defineInRange("repairUnlockLevel", 4, 1, 10);
        REPAIR_WORK_FEE_CREDITS = b.comment("单次维修销毁的信用点工费。默认 1500: 一次维修另吃一件替换零件与永久最大",
                        "耐久 (8%-12%), 总代价须明显低于重造整枪 (装配 5000 + 六件零件), 否则玩家宁可弃枪重造,",
                        "耐久系统失去意义; 设 0 等于关掉维修 sink。")
                .defineInRange("repairWorkFeeCredits", 1500, 0, 100000000);
        b.comment("3A. 组件稀有度加价倍率 (无量纲): 实扣冲压工费 = pressWorkFeeCredits x 品质材料倍率 x 本倍率,",
                "向上取整。势力/特殊组件与基础组件同价即定价缺陷 (审查 29); 材料侧不加价, 因为料槽单槽 64 上限",
                "已被传奇品质吃满, 再乘倍率会让高稀有度组件直接无法冲压。");
        PRESS_RARITY_FEE_MULTIPLIER_STANDARD = b.defineInRange("pressRarityFeeMultiplierStandard", 1.0D, 1.0D, 20.0D);
        PRESS_RARITY_FEE_MULTIPLIER_MODIFIED = b.defineInRange("pressRarityFeeMultiplierModified", 1.5D, 1.0D, 20.0D);
        PRESS_RARITY_FEE_MULTIPLIER_SPECIAL = b.defineInRange("pressRarityFeeMultiplierSpecial", 2.5D, 1.0D, 20.0D);
        PRESS_RARITY_FEE_MULTIPLIER_ADVANCED = b.defineInRange("pressRarityFeeMultiplierAdvanced", 4.0D, 1.0D, 20.0D);
        PRESS_RARITY_FEE_MULTIPLIER_PROTOTYPE = b.defineInRange("pressRarityFeeMultiplierPrototype", 6.0D, 1.0D, 20.0D);
        b.comment("3A. 各稀有度档组件解锁冲压所需的军火商等级 (1-10), 与品质门共用 1/4/6/8/10 曲线。");
        RARITY_UNLOCK_STANDARD = b.defineInRange("rarityUnlockStandard", 1, 1, 10);
        RARITY_UNLOCK_MODIFIED = b.defineInRange("rarityUnlockModified", 4, 1, 10);
        RARITY_UNLOCK_SPECIAL = b.defineInRange("rarityUnlockSpecial", 6, 1, 10);
        RARITY_UNLOCK_ADVANCED = b.defineInRange("rarityUnlockAdvanced", 8, 1, 10);
        RARITY_UNLOCK_PROTOTYPE = b.defineInRange("rarityUnlockPrototype", 10, 1, 10);
        b.pop();

        b.push("timing");
        b.comment("11.3 PENDING: rate (rounds/hour) -> tick conversion. ticksPerRoundForTable = ticksPerRateHour / ratePerTable.");
        TICKS_PER_RATE_HOUR = b.comment("Real ticks mapped to one rate-hour (default 72000 = one real hour; not MC day). Calibrated on live server.")
                .defineInRange("ticksPerRateHour", 72000, 20, 172800000);
        b.pop();

        b.push("calibers");
        b.comment("6.3 per-caliber shop price / munitions sell price (=75% shop) / yield factor (rifle baseline 1.0; 高阶弹单发料重缩产 < 1.0). Values are ×10 anchored credits (11.4).");

        SHOP_PRICE_PISTOL = b.defineInRange("pistolShopPrice", 10, 1, 1000000);
        SELL_PRICE_PISTOL = b.defineInRange("pistolSellPrice", 8, 1, 1000000);
        YIELD_FACTOR_PISTOL = b.defineInRange("pistolYieldFactor", 1.0, 0.01, 10.0);

        SHOP_PRICE_RIFLE = b.defineInRange("rifleShopPrice", 20, 1, 1000000);
        SELL_PRICE_RIFLE = b.defineInRange("rifleSellPrice", 15, 1, 1000000);
        YIELD_FACTOR_RIFLE = b.defineInRange("rifleYieldFactor", 1.0, 0.01, 10.0);

        SHOP_PRICE_BATTLE = b.defineInRange("battleShopPrice", 30, 1, 1000000);
        SELL_PRICE_BATTLE = b.defineInRange("battleSellPrice", 23, 1, 1000000);
        YIELD_FACTOR_BATTLE = b.defineInRange("battleYieldFactor", 0.7, 0.01, 10.0);

        SHOP_PRICE_SHOTGUN = b.defineInRange("shotgunShopPrice", 35, 1, 1000000);
        SELL_PRICE_SHOTGUN = b.defineInRange("shotgunSellPrice", 26, 1, 1000000);
        YIELD_FACTOR_SHOTGUN = b.defineInRange("shotgunYieldFactor", 0.6, 0.01, 10.0);

        SHOP_PRICE_SNIPER = b.defineInRange("sniperShopPrice", 80, 1, 1000000);
        SELL_PRICE_SNIPER = b.defineInRange("sniperSellPrice", 60, 1, 1000000);
        YIELD_FACTOR_SNIPER = b.defineInRange("sniperYieldFactor", 0.4, 0.01, 10.0);

        SHOP_PRICE_BIG_PISTOL = b.defineInRange("bigPistolShopPrice", 60, 1, 1000000);
        SELL_PRICE_BIG_PISTOL = b.defineInRange("bigPistolSellPrice", 45, 1, 1000000);
        YIELD_FACTOR_BIG_PISTOL = b.defineInRange("bigPistolYieldFactor", 0.5, 0.01, 10.0);

        SHOP_PRICE_ANTI_MATERIEL = b.defineInRange("antiMaterielShopPrice", 200, 1, 1000000);
        SELL_PRICE_ANTI_MATERIEL = b.defineInRange("antiMaterielSellPrice", 150, 1, 1000000);
        YIELD_FACTOR_ANTI_MATERIEL = b.defineInRange("antiMaterielYieldFactor", 0.25, 0.01, 10.0);

        SHOP_PRICE_EXPLOSIVE = b.comment("Explosive shop price; spec range 400-800, midpoint default")
                .defineInRange("explosiveShopPrice", 600, 1, 1000000);
        SELL_PRICE_EXPLOSIVE = b.comment("Explosive sell price; spec range 300-600, midpoint default")
                .defineInRange("explosiveSellPrice", 450, 1, 1000000);
        YIELD_FACTOR_EXPLOSIVE = b.defineInRange("explosiveYieldFactor", 0.15, 0.01, 10.0);

        // 特种弹 (L10 毕业档): spec 6.3 未单列价格, 暂沿用狙击档作占位 (PENDING 11.2 逐口径定)。
        SHOP_PRICE_SPECIAL = b.comment("PENDING 11.2: special-round shop price未单列于6.3, 暂沿用狙击档占位")
                .defineInRange("specialShopPrice", 80, 1, 1000000);
        SELL_PRICE_SPECIAL = b.defineInRange("specialSellPrice", 60, 1, 1000000);
        YIELD_FACTOR_SPECIAL = b.defineInRange("specialYieldFactor", 0.4, 0.01, 10.0);
        b.pop();

        b.push("xp");
        b.comment("7. produce-xp (谁产谁得, raw xp by rounds produced; framework applies daily softcap decay).");
        PRODUCE_XP_PER_ROUND_MILLI = b.comment("Raw xp per rifle-equivalent round, in milli-units (effective = floor(rounds × this / 1000))")
                .defineInRange("perRoundMilli", 1000, 0, 1000000);
        b.pop();

        // 段注释必须挂在 push 上: 先 push 再 comment 会被下一个键自己的 comment 覆盖掉 (Builder 只留最后一次)。
        b.comment("6.4 系统采购: 平板军火商页向系统购买军火台、枪匠冲压机、枪匠装配台与枪匠图纸的信用点售价 (整数 CP)。",
                "扣费经 IEconomyService.tryCharge 直接销毁, 不转入任何玩家 (信用点 sink); 0 = 免费。",
                "等级门不在本段配置, 由既有键推导: 军火台按台档有效等级上限, 冲压机取品质解锁等级最低档,",
                "装配台取 min(assemblyUnlockLevel, repairUnlockLevel), 图纸取 max(assemblyUnlockLevel, 弹药口径解锁等级)。",
                "各默认值的出处与推法见 Munitions_Job_DesignSpec 6.4; 标 PROVISIONAL 的是设计文档里没有锚价的暂定值。")
                .push("shop");
        // 军火台六档: PROVISIONAL, 设计文档只说"向系统购买 (信用点 sink + 进阶目标)", 没给价。推法统一为
        // "该档有效等级上限处 6.2 表的每台日净收入 x 3 天回本", 取整到千位; 3 天回本本身是暂定假设。
        SHOP_BENCH_MEDIUM_PRICE = b.comment("munitions_bench_medium (effective cap L4). PROVISIONAL: L4 net 9,400/day / 2 tables"
                        + " x 3-day payback = 14,100 -> 15,000")
                .defineInRange("munitionsBenchMediumPrice", 15_000, 0, SHOP_PRICE_MAX);
        SHOP_BENCH_HIGH_PRICE = b.comment("munitions_bench_high (effective cap L6). PROVISIONAL: L6 net 53,800/day / 3 tables"
                        + " x 3-day payback = 53,800 -> 55,000")
                .defineInRange("munitionsBenchHighPrice", 55_000, 0, SHOP_PRICE_MAX);
        SHOP_BENCH_SUPERIOR_PRICE = b.comment("munitions_bench_superior (effective cap L8). PROVISIONAL: L8 net 94,500/day"
                        + " / 4 tables x 3-day payback = 70,875 -> 70,000")
                .defineInRange("munitionsBenchSuperiorPrice", 70_000, 0, SHOP_PRICE_MAX);
        SHOP_BENCH_TRANSCENDENT_PRICE = b.comment("munitions_bench_transcendent (effective cap L9). PROVISIONAL: L9 net"
                        + " 130,900/day / 5 tables x 3-day payback = 78,540 -> 80,000")
                .defineInRange("munitionsBenchTranscendentPrice", 80_000, 0, SHOP_PRICE_MAX);
        SHOP_BENCH_RADIANT_PRICE = b.comment("munitions_bench_radiant (effective cap L10). PROVISIONAL: L10 net 174,500/day"
                        + " / 6 tables x 3-day payback = 87,250 -> 90,000")
                .defineInRange("munitionsBenchRadiantPrice", 90_000, 0, SHOP_PRICE_MAX);
        SHOP_BENCH_LEGACY_PRICE = b.comment("munitions_bench (legacy registry name, full range L1-L10, bought at L10)."
                        + " Functionally identical to the radiant bench, so it defaults to the same price")
                .defineInRange("munitionsBenchPrice", 90_000, 0, SHOP_PRICE_MAX);
        SHOP_GUNSMITH_PRESS_PRICE = b.comment("gunsmith_press. PROVISIONAL: 100 x pressWorkFeeCredits (200) = 20,000")
                .defineInRange("gunsmithPressPrice", 20_000, 0, SHOP_PRICE_MAX);
        SHOP_GUNSMITH_ASSEMBLY_BENCH_PRICE = b.comment("gunsmith_assembly_bench. PROVISIONAL: 10 x assemblyWorkFeeCredits"
                        + " (5,000) = 50,000")
                .defineInRange("gunsmithAssemblyBenchPrice", 50_000, 0, SHOP_PRICE_MAX);

        b.comment("One key per gunsmith blueprint (lower-case enum name). Defaults follow the one-off gun price anchors of",
                "服务器经济系统设计文档 8.3 (midpoint of each range), picked by the blueprint's ammo caliber tier:",
                "pistol/SMG 2-5万 -> 35,000; rifle 8-15万 -> 115,000; shotgun 12-20万 -> 160,000; sniper 25-40万 -> 325,000.",
                "Blueprints are not consumed by assembly, so one purchase serves every later assembly.")
                .push("blueprints");
        Map<GunsmithBlueprint, ForgeConfigSpec.IntValue> blueprintPrices = new EnumMap<>(GunsmithBlueprint.class);
        for (GunsmithBlueprint blueprint : GunsmithBlueprint.values()) {
            blueprintPrices.put(blueprint, b.defineInRange(blueprint.name().toLowerCase(Locale.ROOT),
                    defaultBlueprintPrice(blueprint), 0, SHOP_PRICE_MAX));
        }
        SHOP_BLUEPRINT_PRICES = Collections.unmodifiableMap(blueprintPrices);
        b.pop();
        b.pop();

        SPEC = b.build();
    }

    /** 某张枪匠图纸的系统采购价配置项 ([shop.blueprints] 段; 6.4)。 */
    public static ForgeConfigSpec.IntValue shopBlueprintPrice(GunsmithBlueprint blueprint) {
        ForgeConfigSpec.IntValue value = SHOP_BLUEPRINT_PRICES.get(blueprint);
        if (value == null) {
            throw new IllegalStateException("No shop price key for gunsmith blueprint " + blueprint);
        }
        return value;
    }

    /**
     * 图纸默认售价 = 8.3 单把枪价区间的中值, 按图纸弹药的口径档归类 (6.4)。
     *
     * 这里刻意按图纸逐个列而不是调 {@code blueprint.ammoCaliber()}: 本方法跑在本类静态初始化里, 而
     * {@link MunitionsCaliber} 的静态初始化会反过来读本类的 SHOP_PRICE_* 字段, 两边互等时读到的是尚未赋值的
     * null。归类是否与口径表一致由 {@code MunitionsShopGameTests} 逐张对账。无 default 的 switch 让新增图纸
     * 不补一行就编译不过。
     */
    private static int defaultBlueprintPrice(GunsmithBlueprint blueprint) {
        return switch (blueprint) {
            // 手枪/SMG 档 (9mm / .45 ACP): 8.3 "手枪/SMG 2-5 万" 中值。
            case M1911, UMP45, UZI, HK_MP5A5, STERLING, MPX -> 35_000;
            // 步枪档 (5.56 / 7.62x39): 8.3 "步枪 8-15 万" 中值。
            case M4A1, M16A1, M16A4, HK416D, SPR15HB, AK47, RPK, TYPE_81 -> 115_000;
            // 霰弹档 (12g): 8.3 "霰弹/战斗 12-20 万" 中值。
            case M870, M1887_LONG, KSG, M1014 -> 160_000;
            // 狙击档 (.30-06 / 7.92x57 / .303): 8.3 "狙击 25-40 万" 中值。
            case KAR98K, SMLE_III, M700 -> 325_000;
        };
    }

    /** 整枪伤害乘子总帽 (审查 27; 钳 GunsmithGunStats.damage() 的连乘结果)。 */
    public static double gunsmithDamageMultiplierCap() {
        return GUNSMITH_DAMAGE_MULTIPLIER_CAP.get();
    }

    /** 解锁枪械维修的军火商等级 (审查 24)。 */
    public static int repairUnlockLevel() {
        return REPAIR_UNLOCK_LEVEL.get();
    }

    /** 单次维修销毁的信用点工费 (审查 24)。 */
    public static long repairWorkFeeCredits() {
        return REPAIR_WORK_FEE_CREDITS.get();
    }

    /** 某稀有度档的冲压工费加价倍率 (审查 29)。 */
    public static double pressRarityFeeMultiplier(GunsmithPartRarity rarity) {
        return switch (rarity) {
            case STANDARD -> PRESS_RARITY_FEE_MULTIPLIER_STANDARD.get();
            case MODIFIED -> PRESS_RARITY_FEE_MULTIPLIER_MODIFIED.get();
            case SPECIAL -> PRESS_RARITY_FEE_MULTIPLIER_SPECIAL.get();
            case ADVANCED -> PRESS_RARITY_FEE_MULTIPLIER_ADVANCED.get();
            case PROTOTYPE -> PRESS_RARITY_FEE_MULTIPLIER_PROTOTYPE.get();
        };
    }

    /** 某稀有度档组件解锁冲压所需的军火商等级 (审查 29)。 */
    public static int rarityUnlockLevel(GunsmithPartRarity rarity) {
        return switch (rarity) {
            case STANDARD -> RARITY_UNLOCK_STANDARD.get();
            case MODIFIED -> RARITY_UNLOCK_MODIFIED.get();
            case SPECIAL -> RARITY_UNLOCK_SPECIAL.get();
            case ADVANCED -> RARITY_UNLOCK_ADVANCED.get();
            case PROTOTYPE -> RARITY_UNLOCK_PROTOTYPE.get();
        };
    }

    /**
     * 单次冲压实扣的信用点工费 = 基数 x 品质材料倍率 x 稀有度加价倍率。工费是销毁型 sink, 浮点零头一律向上取整,
     * 免得玩家靠倍率档位白拿折扣。冲压台与展示面板共用本方法, 防两处各算一套后漂移。
     */
    public static long pressWorkFeeCredits(GunsmithPartQuality quality, GunsmithPartRarity rarity) {
        double fee = (double) PRESS_WORK_FEE_CREDITS.get() * quality.materialMultiplier()
                * pressRarityFeeMultiplier(rarity);
        return (long) Math.ceil(fee);
    }

    /**
     * GameTest 专用: 若 SPEC 尚未被 Forge 加载 (本子系统在集成阶段才接进 MiningDim, runGameTestServer 时配置未注册),
     * 用一份填满默认值的内存 config 绑定 SPEC, 使各 {@code .get()} 返回 spec 默认值。集成接线后 Forge 以真实 toml
     * 覆盖此绑定 (isLoaded 已 true 时本方法直接返回, 不覆盖运行期配置)。
     *
     * 仅供 {@code MunitionsGameTests} 调用; 生产路径由 MunitionsSystem.register 经 ModLoadingContext 加载, 不走此。
     */
    public static void ensureLoadedForTest() {
        if (SPEC.isLoaded()) {
            return;
        }
        CommentedConfig config = CommentedConfig.inMemory();
        SPEC.correct(config);
        SPEC.setConfig(config);
    }

    private MunitionsConfig() {
    }
}
