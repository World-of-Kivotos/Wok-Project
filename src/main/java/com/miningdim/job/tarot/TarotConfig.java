package com.miningdim.job.tarot;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 塔罗师全局旋钮 (TarotReader spec 第七/八/九/十/十三章 PENDING 推荐默认值)。独立 SERVER 级
 * ForgeConfigSpec, 由 {@link TarotSystem} 在 register 内 registerConfig("miningdim-tarot.toml")。
 *
 * 为何不塞进 com.miningdim.config.MiningServerConfig: 该类自述 "abuse 闸门配置不在 IMiningConfig 接口面内,
 * 由反滥用子系统在其引入对应门面时自带 spec 段, 此处不预置无读取方的死键" —— 即工程惯例是 "谁的旋钮谁带 spec
 * 段"。塔罗师照此自带本类, 不污染中央 config (任务铁律: 严禁改中央; 各子系统自持)。
 *
 * 读值纪律 (C6): 业务代码实时 *.get(), 严禁缓存或硬编码同义裸常量。所有牌效逐档数值不在此 (走 datapack JSON,
 * spec 第十一章), 此处只放系统级总闸门 (出率/合成四结果概率/CD/经济/经验/碎片/pity)。
 */
public final class TarotConfig {

    public static final ForgeConfigSpec SPEC;

    // ---- 用牌冷却 (spec 9.5) ----
    /** 全局 GCD (ticks); 1.5s = 30 ticks。 */
    public static final ForgeConfigSpec.IntValue GCD_TICKS;
    /** 功能牌每卡 CD (ticks); ~10s。 */
    public static final ForgeConfigSpec.IntValue CD_UTILITY_TICKS;
    /** 增益牌每卡 CD (ticks); ~25s。 */
    public static final ForgeConfigSpec.IntValue CD_BUFF_TICKS;
    /** 强战斗牌每卡 CD (ticks); ~45s。 */
    public static final ForgeConfigSpec.IntValue CD_COMBAT_TICKS;

    // ---- 单牌原始经验 (spec 9.1) ----
    public static final ForgeConfigSpec.IntValue XP_R;
    public static final ForgeConfigSpec.IntValue XP_SR;
    public static final ForgeConfigSpec.IntValue XP_SSR;
    public static final ForgeConfigSpec.IntValue XP_UR;
    public static final ForgeConfigSpec.IntValue XP_SHINY;
    /** 合成成功额外小额经验 (spec 9.1)。 */
    public static final ForgeConfigSpec.IntValue XP_CRAFT_SUCCESS;

    // ---- 经济: 卡包售价 (spec 第十章; 信用点) ----
    public static final ForgeConfigSpec.IntValue PRICE_COMMON_PACK;
    public static final ForgeConfigSpec.IntValue PRICE_ADVANCED_PACK;
    /** 闪耀卡包青辉石售价 (spec 第七章: 不走信用点, 青辉石高价)。 */
    public static final ForgeConfigSpec.IntValue PRICE_SHINY_PACK_AZURE;
    /** 每日购包上限 (并入 economy UTC 翻日; spec 第十章)。 */
    public static final ForgeConfigSpec.IntValue DAILY_PACK_LIMIT;

    // ---- 卡包出率 (spec 第七章 PENDING 推荐) ----
    /** 高级卡包每张出 SSR 的概率 (其余为 SR)。 */
    public static final ForgeConfigSpec.DoubleValue ADVANCED_SSR_CHANCE;
    /** 高级卡包附带派生卡包的概率 (期望 E<1 几何收敛; spec 第七章)。 */
    public static final ForgeConfigSpec.DoubleValue ADVANCED_DERIVED_CHANCE;
    /** 高级卡包一次产出张数。 */
    public static final ForgeConfigSpec.IntValue ADVANCED_DRAW_COUNT;
    /** pity: 连续 N 个高级包未出 SSR 则下个包保底 SSR (spec 第七章)。 */
    public static final ForgeConfigSpec.IntValue PITY_SSR_PACKS;
    /** 重复牌转塔罗碎片的张数 (spec 第七章: 转化率)。 */
    public static final ForgeConfigSpec.IntValue DUPLICATE_SHARD_REFUND;
    /** 碎片兑换指定牌所需碎片数 (spec 第七/十三章 6: 攒够换指定牌, 给非洲玩家确定性毕业线)。 */
    public static final ForgeConfigSpec.IntValue SHARD_EXCHANGE_COST;

    // ---- 合成四结果概率 (spec 第八章表; 进 config) ----
    // R->SR
    public static final ForgeConfigSpec.DoubleValue CRAFT_R_SUCCESS;
    public static final ForgeConfigSpec.DoubleValue CRAFT_R_REVERSE;
    public static final ForgeConfigSpec.DoubleValue CRAFT_R_SHATTER;
    // SR->SSR
    public static final ForgeConfigSpec.DoubleValue CRAFT_SR_SUCCESS;
    public static final ForgeConfigSpec.DoubleValue CRAFT_SR_REVERSE;
    public static final ForgeConfigSpec.DoubleValue CRAFT_SR_SHATTER;
    // SSR->UR
    public static final ForgeConfigSpec.DoubleValue CRAFT_SSR_SUCCESS;
    public static final ForgeConfigSpec.DoubleValue CRAFT_SSR_REVERSE;
    public static final ForgeConfigSpec.DoubleValue CRAFT_SSR_SHATTER;
    // UR->闪耀 (无逆转)
    public static final ForgeConfigSpec.DoubleValue CRAFT_UR_SUCCESS;
    public static final ForgeConfigSpec.DoubleValue CRAFT_UR_SHATTER;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("cooldown");
        GCD_TICKS = b.comment("Global cooldown between any two card plays, in ticks (spec 9.5: 1.5s = 30)")
                .defineInRange("gcdTicks", 30, 1, 200);
        CD_UTILITY_TICKS = b.comment("Per-card CD for utility cards, in ticks (~10s)")
                .defineInRange("utilityCdTicks", 200, 20, 6000);
        CD_BUFF_TICKS = b.comment("Per-card CD for buff cards, in ticks (~25s)")
                .defineInRange("buffCdTicks", 500, 20, 6000);
        CD_COMBAT_TICKS = b.comment("Per-card CD for strong combat cards, in ticks (~45s)")
                .defineInRange("combatCdTicks", 900, 20, 6000);
        b.pop();

        b.push("experience");
        XP_R = b.comment("Raw xp for playing an R card (spec 9.1)")
                .defineInRange("rawXpR", 8, 0, 100000);
        XP_SR = b.defineInRange("rawXpSr", 16, 0, 100000);
        XP_SSR = b.defineInRange("rawXpSsr", 32, 0, 100000);
        XP_UR = b.defineInRange("rawXpUr", 60, 0, 100000);
        XP_SHINY = b.defineInRange("rawXpShiny", 120, 0, 100000);
        XP_CRAFT_SUCCESS = b.comment("Small bonus raw xp on a successful craft (spec 9.1)")
                .defineInRange("rawXpCraftSuccess", 10, 0, 100000);
        b.pop();

        b.push("economy");
        PRICE_COMMON_PACK = b.comment("Common pack price in CREDIT (spec 10)")
                .defineInRange("commonPackCredit", 200, 0, 10000000);
        PRICE_ADVANCED_PACK = b.comment("Advanced pack price in CREDIT (spec 10)")
                .defineInRange("advancedPackCredit", 1200, 0, 10000000);
        PRICE_SHINY_PACK_AZURE = b.comment("Shiny pack price in AZURE (spec 7: never CREDIT)")
                .defineInRange("shinyPackAzure", 64, 0, 10000000);
        DAILY_PACK_LIMIT = b.comment("Max packs purchasable per UTC day per player (spec 10)")
                .defineInRange("dailyPackLimit", 20, 0, 100000);
        b.pop();

        b.push("gacha");
        ADVANCED_SSR_CHANCE = b.comment("Per-card SSR chance in an advanced pack; remainder is SR (spec 7 PENDING)")
                .defineInRange("advancedSsrChance", 0.20D, 0.0D, 1.0D);
        ADVANCED_DERIVED_CHANCE = b.comment("Chance an advanced pack yields a derived advanced pack (drawCount*chance must stay < 1 for geometric convergence; spec 7; openAdvanced asserts E<1)")
                .defineInRange("advancedDerivedChance", 0.10D, 0.0D, 0.30D);
        ADVANCED_DRAW_COUNT = b.comment("Cards produced per advanced pack (spec 7; drawCount*derivedChance must stay < 1)")
                .defineInRange("advancedDrawCount", 3, 1, 6);
        PITY_SSR_PACKS = b.comment("After this many advanced packs with no SSR, the next pack guarantees one SSR (spec 7)")
                .defineInRange("pitySsrPacks", 10, 1, 1000);
        DUPLICATE_SHARD_REFUND = b.comment("Tarot shards refunded per duplicate card converted (spec 7)")
                .defineInRange("duplicateShardRefund", 1, 0, 64);
        SHARD_EXCHANGE_COST = b.comment("Tarot shards required to exchange for a chosen card (spec 7/13.6: deterministic graduation line)")
                .defineInRange("shardExchangeCost", 40, 1, 100000);
        b.pop();

        // 合成四结果: 大破碎概率 = 1 - 成功 - 逆转 - 破碎 (派生, 不单列, 保证四率和恒为 1)。
        b.push("craft");
        CRAFT_R_SUCCESS = b.comment("R->SR success (spec 8 table)")
                .defineInRange("rToSrSuccess", 0.50D, 0.0D, 1.0D);
        CRAFT_R_REVERSE = b.defineInRange("rToSrReverse", 0.12D, 0.0D, 1.0D);
        CRAFT_R_SHATTER = b.defineInRange("rToSrShatter", 0.28D, 0.0D, 1.0D);
        CRAFT_SR_SUCCESS = b.defineInRange("srToSsrSuccess", 0.40D, 0.0D, 1.0D);
        CRAFT_SR_REVERSE = b.defineInRange("srToSsrReverse", 0.12D, 0.0D, 1.0D);
        CRAFT_SR_SHATTER = b.defineInRange("srToSsrShatter", 0.36D, 0.0D, 1.0D);
        CRAFT_SSR_SUCCESS = b.defineInRange("ssrToUrSuccess", 0.28D, 0.0D, 1.0D);
        CRAFT_SSR_REVERSE = b.defineInRange("ssrToUrReverse", 0.12D, 0.0D, 1.0D);
        CRAFT_SSR_SHATTER = b.defineInRange("ssrToUrShatter", 0.45D, 0.0D, 1.0D);
        CRAFT_UR_SUCCESS = b.comment("UR->Shiny success (no reverse; hardest tier; needs L10)")
                .defineInRange("urToShinySuccess", 0.15D, 0.0D, 1.0D);
        CRAFT_UR_SHATTER = b.defineInRange("urToShinyShatter", 0.55D, 0.0D, 1.0D);
        b.pop();

        SPEC = b.build();
    }

    private TarotConfig() {
    }
}
