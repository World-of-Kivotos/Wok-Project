package com.miningdim.job.munitions;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 军火商 SERVER 级配置 spec 持有者 (Munitions_Job_DesignSpec 十/C6 硬约束: 全部平衡数值进 ForgeConfigSpec,
 * 业务类内硬编码字面量即缺陷)。覆盖: 产能曲线 (台数/每台速率/缓冲, 6.1) / 双推进剂配方 (直造 7 铜 16 火药 -> 40 发,
 * 提炼 -> 70 发, 四章) / 工费 (1.5 CP/发, 整数化为 ×10 锚价 = 15/10 发, 九章 sink) / 各口径商店价与售价及缩产系数
 * (6.3) / 速率到 tick 换算 (PENDING 11.3) 的唯一数据源。
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
     * 爆头等效伤害倍率总帽 (审查 TACZ-BAL-1): TACZ 爆头伤害 = 距离伤害 x 爆头倍率, 枪匠对二者各乘品质系数后,
     * 爆头处复利最高 1.5 x 1.5 = 2.25。此帽把 damage x headshot 的复利钳住, 只压爆头不动躯干每-stat 帽。
     * 默认取保守初值, 待真服对 80 血目标实测致死阈值后调定。
     */
    public static final ForgeConfigSpec.DoubleValue GUNSMITH_HEADSHOT_DAMAGE_CAP;

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
                        + " material items, survival chain, gating and damage coefficients pass review)")
                .define("gunsmithEnabled", false);
        GUNSMITH_HEADSHOT_DAMAGE_CAP = b.comment("Cap on the compounded headshot-equivalent damage multiplier"
                        + " (damage coeff x headshot coeff). WIP conservative default pending live tuning against"
                        + " the 80-HP server; 2.25 restores the uncapped legendary+legendary compound.")
                .defineInRange("gunsmithHeadshotDamageCap", 1.8D, 1.0D, 2.25D);
        DIRECT_ROUNDS_PER_BATCH = b.comment("Rounds per batch via direct crafting (L1-5, half yield)")
                .defineInRange("directRoundsPerBatch", 40, 1, 100000);
        REFINED_ROUNDS_PER_BATCH = b.comment("Rounds per batch via refining into propellant (L6+, double yield, profit inflection)")
                .defineInRange("refinedRoundsPerBatch", 70, 1, 100000);
        REFINE_UNLOCK_LEVEL = b.comment("Munitions level that unlocks propellant refining (6.1: L6)")
                .defineInRange("refineUnlockLevel", 6, 1, 10);
        b.pop();

        b.push("workFee");
        b.comment("9. work-fee sink: 1.5 CP destroyed per round (×10 anchor integerized = 15 per 10 rounds). 弹药链唯一信用点 sink.");
        WORK_FEE_PER_TEN_ROUNDS = b.comment("Credits charged (destroyed) per 10 rounds produced; 1.5/round = 15/10 rounds")
                .defineInRange("perTenRounds", 15, 0, 100000);
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

        SPEC = b.build();
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
