package com.miningdim.job.miner;

import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.EconomyWalletData;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.economy.ShopPriceTable;
import com.miningdim.economy.EconomyConstants.HighValueOre;
import com.miningdim.job.IJobService;
import com.miningdim.job.JobId;
import com.miningdim.job.JobProgress;
import com.miningdim.job.JobServices;
import com.miningdim.ore.OreType;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 矿工职业核心逻辑 GameTest (断言具体业务结果, 删被测核心逻辑测试必挂; 禁 is-not-null 弱校验; 含边界值)。
 *
 * 覆盖: 挖速封顶、省耐久封顶、时运封顶、难度门控边界、减 danger 满级封底、矿脉抗性陷阱专属、连锁白名单/硬排除、
 * 自动熔炼 1:1、探测可探矿种里程碑、陷阱探测致死门控; 以及复审缺陷闭合的回归断言 (删修复测试必挂):
 *  - 连锁/隧道经济计数回放按产出物个数 (方案 B) 经货币门面入账 (反通胀第一道硬约束, 非 debug-log/计数 0);
 *  - 时运额外掉落随连带产出进经济计数 (时运计入隐藏软上限, 非死代码);
 *  - 矿脉时运按期望确定性追加额外掉落 + 等级门控 (L1-3 死 / L4+ 活);
 *  - AFK 冻结态不计挖矿经验 (第九章反挂机红线), 解冻后正常计经验;
 *  - 卖矿真发钱接线 (第十一章决策 3, 闭合"矿工挖矿零收入" Major): settleOreSale 经主闸真入钱包 (此前只计数从不结算),
 *    首档满额落账 + 撞档后按主闸 0.6 几何衰减 (反通胀北极星: 吞吐只能更快撞顶不能突破);
 *  - 连锁回放产出也并入同一主闸真发钱 (replayEconomyOreCount -> recordMinedOreDrops 逐产出物 settleOreSale),
 *    与单块卖矿共享 credit_faucet/60000 档天花板, 撞档同样衰减 (杜绝连锁产出绕过统一封顶的印钞口)。
 *
 * 货币门面/职业门面经测试替身 ({@link RecordingEconomy}/{@link RecordingJobService}) 注入定位器后断言矿工侧接线
 * (真实计数/衰减逻辑在 economy 子系统 EconomyGameTests 覆盖)。时运随机性用定值 {@link FixedRandom} 消除, 确定可测。
 *
 * 用 template = "empty" (data/miningdim/structures/empty.nbt 空模板); 纯逻辑断言不依赖结构。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MinerGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "miner";

    private static final double EPS = 1.0e-9D;

    // ============================================================
    // 被动: 挖速封顶 +110% / 省耐久封顶 30%
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void digSpeedCap(GameTestHelper helper) {
        // L1 = +15% (倍率 1.15); L10 = +110% (倍率 2.10), 不超 +110%。
        helper.assertTrue(approx(MinerSkills.digSpeedMultiplier(1), 1.15D), "L1 dig speed must be 1.15 (+15%)");
        helper.assertTrue(approx(MinerSkills.digSpeedMultiplier(10), 2.10D), "L10 dig speed must be 2.10 (+110%)");
        helper.assertTrue(MinerSkills.digSpeedMultiplier(10) <= 2.10D + EPS, "dig speed never exceeds +110%");
        // 逐级单调递增 (解锁后逐级变强)。
        helper.assertTrue(MinerSkills.digSpeedMultiplier(5) > MinerSkills.digSpeedMultiplier(4),
                "dig speed strictly grows per level");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void durabilitySaveCap(GameTestHelper helper) {
        helper.assertTrue(approx(MinerSkills.durabilitySaveChance(1), 0.05D), "L1 durability save = 0.05");
        helper.assertTrue(approx(MinerSkills.durabilitySaveChance(10), 0.30D), "L10 durability save = 0.30");
        helper.assertTrue(MinerSkills.durabilitySaveChance(10) <= 0.30D + EPS, "durability save never exceeds 0.30");
        helper.succeed();
    }

    // ============================================================
    // 时运 B: 额外掉落期望 +8% -> +50%
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fortuneExpectancy(GameTestHelper helper) {
        // 未解锁 (L1-3) 无额外掉落。
        helper.assertTrue(approx(MinerSkills.fortuneExtraExpectancy(3), 0.0D), "fortune locked below L4 -> 0");
        helper.assertTrue(approx(MinerSkills.fortuneExtraExpectancy(4), 0.08D), "L4 fortune = +8%");
        helper.assertTrue(approx(MinerSkills.fortuneExtraExpectancy(10), 0.50D), "L10 fortune = +50%");
        helper.succeed();
    }

    // ============================================================
    // 难度门控: L4 Medium / L8 Hard / L1-3 Easy (含边界)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void difficultyGateBoundaries(GameTestHelper helper) {
        // Easy 任意等级可进。
        helper.assertTrue(MinerLevelGate.canEnter(1, Difficulty.EASY), "L1 enters Easy");
        // Medium 边界: L3 拒, L4 恰好放行。
        helper.assertFalse(MinerLevelGate.canEnter(3, Difficulty.MEDIUM), "L3 rejected from Medium");
        helper.assertTrue(MinerLevelGate.canEnter(4, Difficulty.MEDIUM), "L4 exactly enters Medium");
        // Hard 边界: L7 拒, L8 恰好放行; L3 进 Hard 被拒。
        helper.assertFalse(MinerLevelGate.canEnter(7, Difficulty.HARD), "L7 rejected from Hard");
        helper.assertTrue(MinerLevelGate.canEnter(8, Difficulty.HARD), "L8 exactly enters Hard");
        helper.assertFalse(MinerLevelGate.canEnter(3, Difficulty.HARD), "L3 rejected from Hard");
        // 门槛值。
        helper.assertTrue(MinerLevelGate.minLevelFor(Difficulty.MEDIUM) == 4, "Medium gate = L4");
        helper.assertTrue(MinerLevelGate.minLevelFor(Difficulty.HARD) == 8, "Hard gate = L8");
        helper.succeed();
    }

    // ============================================================
    // 减 danger 满级封底 0.6x + 未解锁不减
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dangerTimeFactorFloor(GameTestHelper helper) {
        // 未解锁 (L1-3): 系数 1.0 (不减压力)。
        helper.assertTrue(approx(MinerSkills.dangerTimeFactor(3), 1.0D), "danger resist locked below L4 -> 1.0");
        helper.assertTrue(approx(MinerSkills.dangerTimeFactor(4), 0.85D), "L4 danger time factor = 0.85");
        helper.assertTrue(approx(MinerSkills.dangerTimeFactor(10), 0.60D), "L10 danger time factor = 0.60 (floor)");
        // 红线封底: 满级也不低于 0.6, 且不为 0 (不实质免疫压力)。
        helper.assertTrue(MinerSkills.dangerTimeFactor(10) >= 0.60D - EPS, "danger factor never below 0.6 floor");
        helper.assertTrue(MinerSkills.dangerTimeFactor(10) > 0.0D, "danger factor never clamps to 0");
        helper.succeed();
    }

    // ============================================================
    // 矿脉抗性: 陷阱专属来源减伤封顶 35% + 非陷阱来源零减免
    // 第七章降级路径 (miner-01 闭合): 专属源缺失时按环境陷阱伤类型集合识别 (落石/岩浆/着火/非玩家爆炸…),
    // 矿洞内 L5+ 真实减伤; 战斗向来源 (近战/远程/玩家 TNT) 与非陷阱环境伤 (摔落) 仍零减免 (守不漂战斗力红线)。
    // 删 isTrapSource 的环境伤识别 (回到恒 false) -> 减伤又失效 -> 本测试的减伤断言全挂。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void veinResistTrapOnly(GameTestHelper helper) {
        // 减伤比阶梯。
        helper.assertTrue(approx(MinerSkills.trapDamageReduction(4), 0.0D), "vein resist locked below L5 -> 0");
        helper.assertTrue(approx(MinerSkills.trapDamageReduction(5), 0.10D), "L5 vein resist = 10%");
        helper.assertTrue(approx(MinerSkills.trapDamageReduction(10), 0.35D), "L10 vein resist = 35% cap");
        helper.assertTrue(MinerSkills.trapDamageReduction(10) <= 0.35D + EPS, "vein resist never exceeds 35%");

        ServerPlayer mock = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        net.minecraft.world.damagesource.DamageSources sources = helper.getLevel().damageSources();

        // ---- 降级路径生效: 环境陷阱伤被识别为陷阱来源, L5+ 在矿洞内真实减伤 (miner-01 核心) ----
        helper.assertTrue(MinerSurvival.isTrapSource(sources.lava()), "lava is a trap-equivalent source (fallback path)");
        helper.assertTrue(MinerSurvival.isTrapSource(sources.fallingBlock(mock)),
                "falling block (rockfall) is a trap-equivalent source");
        helper.assertTrue(MinerSurvival.isTrapSource(sources.inFire()), "fire is a trap-equivalent source");
        // explosion(null, null) -> DamageTypes.EXPLOSION (苦力怕/床/陷阱 TNT, 非玩家归因)。
        helper.assertTrue(MinerSurvival.isTrapSource(sources.explosion(null, null)),
                "non-player explosion (creeper/trap TNT) is a trap-equivalent source");

        // L10 岩浆 20 伤 -> 减 35% -> 净 13 (减伤真生效, 非原样 20)。删降级识别则恒 20 必挂。
        float lavaL10 = MinerSurvival.reducedDamage(10, sources.lava(), 20.0f);
        helper.assertTrue(approx(lavaL10, 13.0f), "L10 lava 20 dmg reduced 35% -> 13 (vein resist now live)");
        // L5 落石 20 伤 -> 减 10% -> 净 18 (解锁级边界)。
        float rockL5 = MinerSurvival.reducedDamage(5, sources.fallingBlock(mock), 20.0f);
        helper.assertTrue(approx(rockL5, 18.0f), "L5 falling-block 20 dmg reduced 10% -> 18");
        // 二次同类伤 (反应窗承诺的净伤下降): 矿洞内 L10 玩家连续吃岩浆, 每笔都被减到 13 (净伤稳定下降, 非原样 20)。
        float lavaSecondHit = MinerSurvival.reducedDamage(10, sources.lava(), 20.0f);
        helper.assertTrue(approx(lavaSecondHit, 13.0f), "follow-up same-type trap hit also nets reduced 13 (sustained buffer)");

        // ---- 等级门控红线: L4 未解锁陷阱伤也零减免 (即便是陷阱来源) ----
        float lavaL4 = MinerSurvival.reducedDamage(4, sources.lava(), 20.0f);
        helper.assertTrue(approx(lavaL4, 20.0f), "L4 (vein resist locked) lava reduced by 0% even though it is a trap source");

        // ---- 不漂战斗力红线: 战斗/玩家 TNT/非陷阱环境伤 不被识别为陷阱来源 -> L10 也零减免 ----
        // 玩家点燃的 TNT (explosion(player, player) -> PLAYER_EXPLOSION): 潜在 PvP, 不软化。
        helper.assertFalse(MinerSurvival.isTrapSource(sources.explosion(mock, mock)),
                "player-attributed explosion (TNT) is NOT a trap source (PvP attrition red line)");
        float playerTnt = MinerSurvival.reducedDamage(10, sources.explosion(mock, mock), 20.0f);
        helper.assertTrue(approx(playerTnt, 20.0f), "player TNT damage reduced by 0% at L10 (no combat softening)");
        // 近战战斗伤: 不是陷阱来源, 零减免。
        helper.assertFalse(MinerSurvival.isTrapSource(sources.mobAttack(mock)),
                "mob melee attack is NOT a trap source (combat red line)");
        float mobMelee = MinerSurvival.reducedDamage(10, sources.mobAttack(mock), 20.0f);
        helper.assertTrue(approx(mobMelee, 20.0f), "mob melee damage reduced by 0% at L10");
        // 摔落: 环境伤但非陷阱型 (不在降级集合), 零减免 (证明集合非"凡环境伤皆减")。
        helper.assertFalse(MinerSurvival.isTrapSource(sources.fall()), "fall damage is NOT a trap source");
        float fallDmg = MinerSurvival.reducedDamage(10, sources.fall(), 12.0f);
        helper.assertTrue(approx(fallDmg, 12.0f), "non-trap (fall) damage reduced by 0% at L10");
        helper.succeed();
    }

    // ============================================================
    // 连锁白名单 / 硬排除
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chainWhitelistAndExclude(GameTestHelper helper) {
        // 白名单: 石/深板岩/煤/铁/铜 放行。
        helper.assertTrue(ChainMiningEngine.isWhitelisted(Blocks.STONE), "stone is chain-whitelisted");
        helper.assertTrue(ChainMiningEngine.isWhitelisted(Blocks.DEEPSLATE), "deepslate is chain-whitelisted");
        helper.assertTrue(ChainMiningEngine.isWhitelisted(Blocks.IRON_ORE), "iron ore is chain-whitelisted");
        helper.assertTrue(ChainMiningEngine.isWhitelisted(Blocks.DEEPSLATE_COPPER_ORE), "deepslate copper whitelisted");
        helper.assertTrue(ChainMiningEngine.isWhitelisted(Blocks.COAL_ORE), "coal ore is chain-whitelisted");
        // 硬排除: 钻石/金/残骸/绿宝石 物理排除 (连锁停在边界)。
        helper.assertTrue(ChainMiningEngine.isHardExcluded(Blocks.DIAMOND_ORE), "diamond is hard-excluded");
        helper.assertTrue(ChainMiningEngine.isHardExcluded(Blocks.DEEPSLATE_DIAMOND_ORE), "deepslate diamond excluded");
        helper.assertTrue(ChainMiningEngine.isHardExcluded(Blocks.GOLD_ORE), "gold is hard-excluded");
        helper.assertTrue(ChainMiningEngine.isHardExcluded(Blocks.ANCIENT_DEBRIS), "ancient debris is hard-excluded");
        helper.assertTrue(ChainMiningEngine.isHardExcluded(Blocks.EMERALD_ORE), "emerald is hard-excluded");
        // 互斥: 高价矿不在白名单; 普通石不在排除名单。
        helper.assertFalse(ChainMiningEngine.isWhitelisted(Blocks.DIAMOND_ORE), "diamond NOT in chain whitelist");
        helper.assertFalse(ChainMiningEngine.isHardExcluded(Blocks.STONE), "stone NOT in hard-exclude");
        // 默认拒绝: 既非白名单也非排除的方块 (如基岩) 不连锁。
        helper.assertFalse(ChainMiningEngine.isWhitelisted(Blocks.BEDROCK), "bedrock not whitelisted (default deny)");
        helper.succeed();
    }

    // ============================================================
    // 连锁产出物个数统计 (方案 B 计数口径)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chainDropCount(GameTestHelper helper) {
        // 方案 B: 计 "产出物个数" 而非 "块数"。一组掉落 [3 铁锭, 2 煤] = 5 个产出物。
        List<ItemStack> drops = List.of(new ItemStack(Items.RAW_IRON, 3), new ItemStack(Items.COAL, 2));
        helper.assertTrue(ChainMiningEngine.countDropItems(drops) == 5, "drop item count must be 3+2=5 (scheme B)");
        // 空掉落 -> 0。
        helper.assertTrue(ChainMiningEngine.countDropItems(List.of()) == 0, "empty drops count to 0");
        helper.succeed();
    }

    // ============================================================
    // 探矿可探矿种里程碑 (L3 铁/煤 -> L6 +钻 -> L8 +金/残骸)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oreScanMilestones(GameTestHelper helper) {
        // 未解锁 (L2): 空集。
        helper.assertTrue(OreScanService.allowedOres(2).isEmpty(), "ore scan locked below L3 -> empty allowed set");
        // L3: 铁/煤, 无钻无金。
        Set<OreType> l3 = OreScanService.allowedOres(3);
        helper.assertTrue(l3.contains(OreType.IRON) && l3.contains(OreType.COAL), "L3 scans iron + coal");
        helper.assertFalse(l3.contains(OreType.DIAMOND), "L3 does NOT scan diamond");
        helper.assertFalse(l3.contains(OreType.GOLD), "L3 does NOT scan gold");
        // L6: +钻, 仍无金/残骸。
        Set<OreType> l6 = OreScanService.allowedOres(6);
        helper.assertTrue(l6.contains(OreType.DIAMOND), "L6 adds diamond");
        helper.assertFalse(l6.contains(OreType.GOLD), "L6 still no gold");
        helper.assertFalse(l6.contains(OreType.ANCIENT_DEBRIS), "L6 still no debris");
        // L8: +金 +残骸。
        Set<OreType> l8 = OreScanService.allowedOres(8);
        helper.assertTrue(l8.contains(OreType.GOLD), "L8 adds gold");
        helper.assertTrue(l8.contains(OreType.ANCIENT_DEBRIS), "L8 adds ancient debris");
        // 探矿半径阶梯 6 -> 16。
        helper.assertTrue(MinerSkills.oreScanRadius(3) == 6, "L3 ore scan radius = 6");
        helper.assertTrue(MinerSkills.oreScanRadius(10) == 16, "L10 ore scan radius = 16");
        helper.succeed();
    }

    // ============================================================
    // 探矿改扫真实世界 (C2 / economy-01 闭合 "探矿失效"): OreScanService.scanWorld 逐格读真实方块态, 经
    // OreType.fromBlock 还原矿种, 只收球内确有命中的坐标。删世界扫描 (回到死体素表 cachedPlacement) -> 恒空返 -> 必挂。
    //
    // 用 DIAMOND-only 可探集合隔离矿种优先序 (避免测试世界里偶发铁/煤命中干扰), 直接验证 "世界读取 + fromBlock +
    // 球半径几何" 三件核心: 球内放的 DIAMOND_ORE 全部命中; 球外的不返回; allowedOres 空 (L2) 时短路空返。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oreScanReadsRealWorldBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(0, 1, 0));
        int radius = 4;

        // 球内放 3 颗钻石矿 (含深板岩变体, 验证 fromBlock 两变体都映射 DIAMOND), 全在半径内。
        BlockPos in1 = center.offset(1, 0, 0);
        BlockPos in2 = center.offset(0, 1, -1);
        BlockPos in3 = center.offset(-1, 0, 1); // 用深板岩变体
        level.setBlock(in1, Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(in2, Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(in3, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        // 球外放 1 颗 (欧氏距离 > radius): 必须不被收。dx=5 > 4 -> 5^2=25 > 16。
        BlockPos out = center.offset(5, 0, 0);
        level.setBlock(out, Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);

        Set<OreType> diamondOnly = EnumSet.of(OreType.DIAMOND);
        List<BlockPos> hits = OreScanService.scanWorld(level, center, radius, diamondOnly);

        // 球内 3 颗全部命中 (深板岩变体经 fromBlock 同映射 DIAMOND -> 也收到)。
        helper.assertTrue(hits.contains(in1), "scanWorld returns in-sphere diamond at " + in1);
        helper.assertTrue(hits.contains(in2), "scanWorld returns in-sphere diamond at " + in2);
        helper.assertTrue(hits.contains(in3), "scanWorld returns in-sphere deepslate-diamond at " + in3 + " (fromBlock maps both variants)");
        helper.assertTrue(hits.size() == 3, "exactly the 3 in-sphere diamonds are returned, got " + hits.size());
        // 球外那颗不在结果里 (半径几何真生效, 非全图下发)。
        helper.assertFalse(hits.contains(out), "out-of-sphere diamond at " + out + " is NOT returned (radius gate live)");

        // fromBlock 反查正确性 (C2 与 C1 共用映射): 石质/深板岩两变体都还原 DIAMOND, 非矿方块还原 null。
        helper.assertTrue(OreType.fromBlock(Blocks.DIAMOND_ORE) == OreType.DIAMOND, "fromBlock(DIAMOND_ORE) = DIAMOND");
        helper.assertTrue(OreType.fromBlock(Blocks.DEEPSLATE_DIAMOND_ORE) == OreType.DIAMOND, "fromBlock(DEEPSLATE_DIAMOND_ORE) = DIAMOND");
        helper.assertTrue(OreType.fromBlock(Blocks.STONE) == null, "fromBlock(STONE) = null (not an ore)");

        // L2 玩家 (allowedOres 空) -> scanWorld 空集语义短路, 即便球内有矿也不下发 (探矿未解锁)。
        Set<OreType> lockedL2 = OreScanService.allowedOres(2);
        helper.assertTrue(lockedL2.isEmpty(), "L2 allowedOres empty (scan locked below L3)");
        helper.assertTrue(OreScanService.scanWorld(level, center, radius, lockedL2).isEmpty(),
                "L2 (empty allowed set) scans nothing even with diamonds present in the sphere");

        // L6 可探集合含 DIAMOND (探矿里程碑): 用 L6 集合在同球扫到的钻石数 >= 上面 DIAMOND-only 的命中 (无遗漏)。
        Set<OreType> l6 = OreScanService.allowedOres(6);
        helper.assertTrue(l6.contains(OreType.DIAMOND), "L6 allowed set includes diamond");
        List<BlockPos> l6Hits = OreScanService.scanWorld(level, center, radius, l6);
        // 球内仅放了钻石 (无铁/煤), 故 L6 优先序最终落到 DIAMOND, 命中同样 3 颗。
        helper.assertTrue(l6Hits.size() == 3 && l6Hits.contains(in1) && l6Hits.contains(in3),
                "L6 scan over the same sphere still finds the 3 diamonds (single-ore preference falls through to diamond)");
        helper.succeed();
    }

    // ============================================================
    // C1: 残骸/绿宝石 ore feature 数据正确性 + 挂入 Hard (保持 Hard 无 copper/coal)。
    // GameTest 内 force-load 矿洞 Hard 区块跑真实 feature 放置不可行 (概率性 + 跨维度), 故退而验数据正确性:
    //   1. 两个新 placed_feature JSON 解析加载成功 (在 PLACED_FEATURE 注册表内; 解析失败则数据包加载报错, 注册表无此键);
    //   2. OreType.fromBlock 能把残骸/绿宝石 (含深板岩变体) 还原成矿种 (探矿/铺矿共用映射);
    //   3. mining_hard biome 的地下矿阶段 (UNDERGROUND_ORES) 引用了两个新 placed_feature, 且仍不含 copper/coal (Hard 无铜回归)。
    // 删 mining_hard.json 的两行追加 -> 断言 3 必挂; 删 placed_feature JSON -> 断言 1 必挂 (注册表缺键); 删 fromBlock 残骸/绿宝石映射 -> 断言 2 必挂。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ancientDebrisAndEmeraldFeaturesWiredIntoHard(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();

        // 1. 两个新 placed_feature 已被数据包加载 (JSON 解析成功的硬证据: 注册表含键)。
        Registry<PlacedFeature> placed = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
        ResourceLocation debrisPlaced = new ResourceLocation(MiningConstants.MODID, "ore_ancient_debris");
        ResourceLocation emeraldPlaced = new ResourceLocation(MiningConstants.MODID, "ore_emerald");
        helper.assertTrue(placed.containsKey(debrisPlaced),
                "placed_feature miningdim:ore_ancient_debris loaded (configured+placed JSON parsed)");
        helper.assertTrue(placed.containsKey(emeraldPlaced),
                "placed_feature miningdim:ore_emerald loaded (configured+placed JSON parsed)");

        // 2. fromBlock 还原残骸/绿宝石 (含深板岩变体), 取代死体素表 (探矿 + 铺矿共用)。
        helper.assertTrue(OreType.fromBlock(Blocks.ANCIENT_DEBRIS) == OreType.ANCIENT_DEBRIS,
                "fromBlock(ANCIENT_DEBRIS) = ANCIENT_DEBRIS");
        helper.assertTrue(OreType.fromBlock(Blocks.EMERALD_ORE) == OreType.EMERALD,
                "fromBlock(EMERALD_ORE) = EMERALD");
        helper.assertTrue(OreType.fromBlock(Blocks.DEEPSLATE_EMERALD_ORE) == OreType.EMERALD,
                "fromBlock(DEEPSLATE_EMERALD_ORE) = EMERALD (deepslate variant)");

        // 3. mining_hard biome 的地下矿阶段引用两个新 feature, 且仍无 copper/coal (Hard 无铜回归)。
        Biome hard = level.registryAccess().registryOrThrow(Registries.BIOME)
                .getOrThrow(Difficulty.HARD.biomeKey());
        Set<ResourceLocation> hardFeatureIds = new HashSet<>();
        for (HolderSet<PlacedFeature> step : hard.getGenerationSettings().features()) {
            for (Holder<PlacedFeature> holder : step) {
                holder.unwrapKey().ifPresent(key -> hardFeatureIds.add(key.location()));
            }
        }
        helper.assertTrue(hardFeatureIds.contains(debrisPlaced),
                "mining_hard references miningdim:ore_ancient_debris in its generation features");
        helper.assertTrue(hardFeatureIds.contains(emeraldPlaced),
                "mining_hard references miningdim:ore_emerald in its generation features");
        // Hard 无铜无煤回归 (保持设计: Hard 不出 copper/coal 这两个低价矿)。
        helper.assertFalse(hardFeatureIds.contains(new ResourceLocation("minecraft", "ore_copper")),
                "mining_hard must NOT contain copper ore feature (Hard no-copper)");
        helper.assertFalse(hardFeatureIds.contains(new ResourceLocation("minecraft", "ore_copper_large")),
                "mining_hard must NOT contain large copper ore feature");
        helper.assertFalse(hardFeatureIds.contains(new ResourceLocation("minecraft", "ore_coal_upper")),
                "mining_hard must NOT contain upper coal ore feature (Hard no-coal)");
        helper.assertFalse(hardFeatureIds.contains(new ResourceLocation("minecraft", "ore_coal_lower")),
                "mining_hard must NOT contain lower coal ore feature (Hard no-coal)");
        helper.succeed();
    }

    // ============================================================
    // 陷阱探测致死门控 (L5 仅非致死 / L8 含致死)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void trapScanLethalGate(GameTestHelper helper) {
        helper.assertFalse(TrapScanService.includeLethal(5), "L5 trap scan excludes lethal traps");
        helper.assertFalse(TrapScanService.includeLethal(7), "L7 trap scan still excludes lethal");
        helper.assertTrue(TrapScanService.includeLethal(8), "L8 trap scan includes lethal (TNT/lava)");
        // 半径/CD 阶梯。
        helper.assertTrue(MinerSkills.trapScanRadius(5) == 6, "L5 trap scan radius = 6");
        helper.assertTrue(MinerSkills.trapScanRadius(10) == 12, "L10 trap scan radius = 12");
        helper.assertTrue(MinerSkills.trapScanCooldownTicks(5) == 4800, "L5 trap scan CD = 240s (4800 tick)");
        helper.assertTrue(MinerSkills.trapScanCooldownTicks(10) == 3000, "L10 trap scan CD = 150s (3000 tick)");
        helper.succeed();
    }

    // ============================================================
    // 自动熔炼 1:1 不增量 (铁/铜 L6, 金 L8)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void autoSmeltOneToOne(GameTestHelper helper) {
        var level = helper.getLevel();
        // L6: 原铁矿 1 个 -> 铁锭 1 个 (1:1, 非 2)。
        ItemStack rawIron = new ItemStack(Items.RAW_IRON, 1);
        ItemStack ironResult = AutoCollectSmelt.smeltResult(level, rawIron, 6);
        helper.assertTrue(ironResult.is(Items.IRON_INGOT), "L6 smelts raw iron to iron ingot");
        helper.assertTrue(ironResult.getCount() == 1, "L6 smelt is 1:1 (1 raw iron -> 1 ingot, NOT 2)");
        // L6 多个: 3 个原铁 -> 3 个铁锭 (等量)。
        ItemStack threeRaw = new ItemStack(Items.RAW_IRON, 3);
        ItemStack threeResult = AutoCollectSmelt.smeltResult(level, threeRaw, 6);
        helper.assertTrue(threeResult.getCount() == 3, "L6 smelt preserves count (3 -> 3)");
        // L6: 金未解锁 (需 L8), 原金矿原样返回 (不熔炼)。
        ItemStack rawGoldAtL6 = AutoCollectSmelt.smeltResult(level, new ItemStack(Items.RAW_GOLD, 1), 6);
        helper.assertTrue(rawGoldAtL6.is(Items.RAW_GOLD), "L6 does NOT smelt gold (locked until L8)");
        // L8: 原金矿 1 -> 金锭 1。
        ItemStack rawGoldAtL8 = AutoCollectSmelt.smeltResult(level, new ItemStack(Items.RAW_GOLD, 1), 8);
        helper.assertTrue(rawGoldAtL8.is(Items.GOLD_INGOT), "L8 smelts raw gold to gold ingot");
        helper.assertTrue(rawGoldAtL8.getCount() == 1, "L8 gold smelt is 1:1");
        // 未解锁基础熔炼 (L5): 原样返回。
        ItemStack noSmelt = AutoCollectSmelt.smeltResult(level, new ItemStack(Items.RAW_IRON, 1), 5);
        helper.assertTrue(noSmelt.is(Items.RAW_IRON), "L5 does NOT smelt (base smelt unlock is L6)");
        helper.succeed();
    }

    // ============================================================
    // 充能池 / CD 阶梯 (连锁/隧道/探矿/脱险/降压)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chargeAndCooldownLadders(GameTestHelper helper) {
        // 连锁充能池 16 -> 48; 未解锁 (L1) 为 0。
        helper.assertTrue(MinerSkills.chainChargePool(1) == 0, "chain pool locked at L1 -> 0");
        helper.assertTrue(MinerSkills.chainChargePool(2) == 16, "L2 chain pool = 16");
        helper.assertTrue(MinerSkills.chainChargePool(10) == 48, "L10 chain pool = 48");
        // 隧道 CD 600 -> 400; 未解锁 (L8) 为 MAX。
        helper.assertTrue(MinerSkills.tunnelCooldownTicks(8) == Integer.MAX_VALUE, "tunnel locked below L9");
        helper.assertTrue(MinerSkills.tunnelCooldownTicks(9) == 600, "L9 tunnel CD = 30s (600 tick)");
        helper.assertTrue(MinerSkills.tunnelCooldownTicks(10) == 400, "L10 tunnel CD = 20s (400 tick)");
        // 矿探 CD 6000 -> 3600。
        helper.assertTrue(MinerSkills.oreScanCooldownTicks(3) == 6000, "L3 ore scan CD = 300s");
        helper.assertTrue(MinerSkills.oreScanCooldownTicks(10) == 3600, "L10 ore scan CD = 180s");
        // 脱险 CD 9600 -> 6000; 降压 CD 6000 -> 4200。
        helper.assertTrue(MinerSkills.evacuateCooldownTicks(7) == 9600, "L7 evacuate CD = 8 min");
        helper.assertTrue(MinerSkills.evacuateCooldownTicks(10) == 6000, "L10 evacuate CD = 5 min");
        helper.assertTrue(MinerSkills.decoyCooldownTicks(9) == 6000, "L9 decoy CD = 5 min");
        helper.assertTrue(MinerSkills.decoyCooldownTicks(10) == 4200, "L10 decoy CD = 3.5 min");
        helper.succeed();
    }

    // ============================================================
    // 充能消耗 (连锁逐块扣 1)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chargeConsume(GameTestHelper helper) {
        MinerChargeState state = new MinerChargeState();
        state.setCharge(16, 16);
        helper.assertTrue(state.currentCharge() == 16, "charge set to 16");
        int consumed = state.consumeCharge(10);
        helper.assertTrue(consumed == 10, "consumed 10");
        helper.assertTrue(state.currentCharge() == 6, "6 charge left");
        // 超额消耗只扣剩余。
        int consumed2 = state.consumeCharge(20);
        helper.assertTrue(consumed2 == 6, "over-consume takes only remaining 6");
        helper.assertTrue(state.currentCharge() == 0, "charge depleted to 0");
        helper.succeed();
    }

    // ============================================================
    // 省耐久同 tick 回补: 命中后净零耐久损耗; 换栈/未扣不回补 (C4)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void durabilitySaveNetZero(GameTestHelper helper) {
        MinerChargeState state = new MinerChargeState();
        // 抢拍一把已损 5 点的镐 (扣前 damageValue=5), 模拟 vanilla mineBlock 又扣 1 -> 6, 同 tick 末核对应回补到 5。
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        pick.setDamageValue(5);
        state.armDurabilitySave(pick, 5);
        helper.assertTrue(state.hasArmedDurabilitySave(), "durability save armed");
        pick.setDamageValue(6); // vanilla 本 tick 扣了 1 点。
        int restored = state.consumeDurabilitySave(pick);
        helper.assertTrue(restored == 1, "restored exactly 1 durability point (net-zero break)");
        helper.assertTrue(pick.getDamageValue() == 5, "tool damage rolled back to pre-break value 5");
        helper.assertFalse(state.hasArmedDurabilitySave(), "arm cleared after consume");

        // vanilla 本 tick 未扣耐久 (如 Unbreaking 省扣): damageValue 未上升 -> 不回补 (不臆造净增益)。
        ItemStack pick2 = new ItemStack(Items.IRON_PICKAXE);
        pick2.setDamageValue(3);
        state.armDurabilitySave(pick2, 3);
        int restored2 = state.consumeDurabilitySave(pick2);
        helper.assertTrue(restored2 == 0, "no restore when vanilla did not damage the tool");
        helper.assertTrue(pick2.getDamageValue() == 3, "tool damage unchanged when not damaged this tick");

        // 同 tick 内换栈 (主手已不是抢拍的 stack): 不回补 (不误改无关物品)。
        ItemStack pick3 = new ItemStack(Items.IRON_PICKAXE);
        pick3.setDamageValue(10);
        state.armDurabilitySave(pick3, 10);
        pick3.setDamageValue(11);
        ItemStack swapped = new ItemStack(Items.DIAMOND_PICKAXE);
        int restored3 = state.consumeDurabilitySave(swapped);
        helper.assertTrue(restored3 == 0, "no restore when main hand stack swapped within tick");
        helper.assertTrue(pick3.getDamageValue() == 11, "armed tool untouched after swap (not restored, not over-restored)");
        helper.succeed();
    }

    // ============================================================
    // 脱险读条受伤即打断 (C6 护栏: 不能当 PvP 逃跑后门)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void evacuateInterruptedOnHurt(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MinerChargeState state = new MinerChargeState();
        // 起读条 (在起点)。
        state.beginEvacuateChannel(100L, player.getX(), player.getY(), player.getZ());
        helper.assertTrue(state.evacuating(), "channel started, evacuating() true");
        // 受伤打断 (走真实 interrupt 路径的 state 重载, 不依赖子系统注册)。
        MinerActions.interruptEvacuateOnHurt(player, state);
        helper.assertFalse(state.evacuating(), "hurt interrupts channel: evacuating() false");
        // 打断只取消读条, 不进 CD (脱险 CD 仅在 executeEvacuate 成功撤离后起): EVACUATE 仍立即可用。
        helper.assertTrue(state.cooldownReady(MinerSkill.EVACUATE, 200L), "interrupt does not start evacuate CD");
        helper.succeed();
    }

    // ============================================================
    // 连锁/隧道经济计数回放: 连带产出按产出物个数回放进货币门面 (反通胀第一道硬约束, 第十章第一条)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chainReplayCountsProducedItems(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingEconomy eco = new RecordingEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            MinerSystem sys = new MinerSystem();
            Block diamond = Blocks.DIAMOND_ORE;

            // 连带产出 [4 钻石, 2 钻石] = 6 个产出物 (方案 B 按个数, 非 1 块): 必须以 6 回放进当日矿物计数,
            // 严禁 debug-log-only / 计数 0 (印钞口)。
            List<ItemStack> drops = List.of(new ItemStack(Items.DIAMOND, 4), new ItemStack(Items.DIAMOND, 2));
            sys.replayEconomyOreCount(player, diamond, drops);
            helper.assertTrue(eco.recordCalls == 1, "replay must call economy facade recordMinedOreDrops exactly once");
            helper.assertTrue(eco.lastBlock == diamond, "replay forwards the broken block (diamond) for ore classification");
            helper.assertTrue(eco.lastProducedCount == 6,
                    "replay forwards produced ITEM count 6 (4+2), not block count 1, not 0 (anti-inflation scheme B)");

            // 空/零产出不回放 (countDropItems<=0 短路, 不打扰门面)。
            sys.replayEconomyOreCount(player, diamond, List.of());
            helper.assertTrue(eco.recordCalls == 1, "empty drops must not call the economy facade (no spurious count)");
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 时运随连带产出进经济计数: 时运额外掉落使回放个数 > 基础产出物个数 (方案 B, 时运计入隐藏软上限)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chainReplayIncludesFortuneExtras(GameTestHelper helper) {
        // 满级时运期望 0.5, roll 强制为 0 (< 0.5) -> 每个基础产出物各得 1 个额外。10 个基础 -> 10 额外 -> 共 20 个,
        // 经济回放个数必须是含时运的 20 (而非基础 10), 否则时运额外产出绕过隐藏软上限 = 反通胀缺口。
        List<ItemStack> base = List.of(new ItemStack(Items.RAW_IRON, 10));
        List<ItemStack> withFortune = MinerFortune.withFortuneExtras(base, 10, new FixedRandom(0.0D));
        int augmented = ChainMiningEngine.countDropItems(withFortune);
        helper.assertTrue(augmented == 20, "L10 fortune (E=0.5, roll=0) doubles 10 base items to 20 produced items");

        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingEconomy eco = new RecordingEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            new MinerSystem().replayEconomyOreCount(player, Blocks.DIAMOND_ORE, withFortune);
            helper.assertTrue(eco.lastProducedCount == 20,
                    "the fortune-augmented produced count (20) is what gets replayed into the daily ore count");
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 矿脉时运额外掉落 (方案 B): 期望 -> 额外个数 (确定性) + 等级门控 (L1-3 死, L4+ 活)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fortuneExtraDropsApplied(GameTestHelper helper) {
        // 确定性额外个数: roll < frac -> 每个基础产出物多 1; roll >= frac -> 0。E=0.5, base=4。
        helper.assertTrue(MinerFortune.extraDropCount(4, 0.5D, 0.0D) == 4, "roll 0 < 0.5 -> 4 base items each +1 = 4 extra");
        helper.assertTrue(MinerFortune.extraDropCount(4, 0.5D, 0.49D) == 4, "roll just below frac -> still 4 extra");
        helper.assertTrue(MinerFortune.extraDropCount(4, 0.5D, 0.5D) == 0, "roll == frac (not <) -> 0 extra");
        helper.assertTrue(MinerFortune.extraDropCount(4, 0.5D, 0.99D) == 0, "roll above frac -> 0 extra");
        // 期望 0 (未解锁) / base 0: 恒 0 额外。
        helper.assertTrue(MinerFortune.extraDropCount(4, 0.0D, 0.0D) == 0, "zero expectancy -> 0 extra");
        helper.assertTrue(MinerFortune.extraDropCount(0, 0.5D, 0.0D) == 0, "zero base -> 0 extra");

        // 等级门控经 withFortuneExtras (取真实 fortuneExtraExpectancy): L3 锁死 -> 无额外 (RNG 无关);
        // L4 解锁 + roll 0 -> 有额外。删 fortuneExtraExpectancy 修复 (回到永远 0) 时 L4 分支必挂。
        List<ItemStack> oneStack = List.of(new ItemStack(Items.RAW_IRON, 3));
        List<ItemStack> lockedL3 = MinerFortune.withFortuneExtras(oneStack, 3, new FixedRandom(0.0D));
        helper.assertTrue(ChainMiningEngine.countDropItems(lockedL3) == 3, "L3 fortune locked: no extra drops (still 3)");
        List<ItemStack> unlockedL4 = MinerFortune.withFortuneExtras(oneStack, 4, new FixedRandom(0.0D));
        helper.assertTrue(ChainMiningEngine.countDropItems(unlockedL4) > 3,
                "L4 fortune unlocked with roll 0: extra drops appended (> 3 base items)");
        helper.succeed();
    }

    // ============================================================
    // 反挂机: AFK 冻结态不计挖矿经验 (第九章红线); 解冻后正常计经验
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void afkFrozenBlocksMiningXp(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingEconomy eco = new RecordingEconomy();
        RecordingJobService job = new RecordingJobService();
        IEconomyService prevEco = swapEconomy(eco);
        IJobService prevJob = swapJob(job);
        try {
            MinerSystem sys = new MinerSystem();

            // 冻结态: 不发经验, 返回 false (上游 onBlockBreak 据此短路连锁/省耐久)。
            eco.afkFrozen = true;
            boolean granted = sys.grantMiningXpUnlessAfk(player);
            helper.assertFalse(granted, "AFK-frozen miner gets no mining XP (returns false)");
            helper.assertTrue(job.grantXpCalls == 0, "AFK-frozen: grantXp must NOT be called (anti-idle red line)");

            // 解冻态: 发一份经验, 返回 true。
            eco.afkFrozen = false;
            boolean granted2 = sys.grantMiningXpUnlessAfk(player);
            helper.assertTrue(granted2, "unfrozen miner gets mining XP (returns true)");
            helper.assertTrue(job.grantXpCalls == 1, "unfrozen: grantXp called exactly once");
            helper.assertTrue(job.lastJob == JobId.MINER, "XP granted to the MINER job track");
            helper.assertTrue(job.lastRawXp > 0L, "a positive raw XP amount is granted per block");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    // ============================================================
    // 卖矿真发钱 (第十一章决策 3, 闭合"矿工挖矿零收入" Major): EconomySystem.onBlockBreak 接线后挖一颗高价矿
    // 经 settleOreSale 真入钱包 (此前只 recordMinedOre 计数从不结算 = 零收入); 首档满额, 撞档后主闸 0.6 衰减。
    //
    // 用真实 EconomyService + EconomyWalletData + AbuseGuard (onBlockBreak 与连锁回放都经此实现 settleOreSale),
    // 走 settleOreSale 这一条 onBlockBreak 实际调用的发钱路径并断言钱包余额真增长 (删 grantDaily 入账则余额恒 0 必挂)。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oreSaleCreditsPlayerThroughMainFaucet(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EconomyWalletData ledger = new EconomyWalletData();
        EconomyService eco = new EconomyService(ledger, new AbuseGuard(), newAbuseStateResolver());

        // 钻石锚价 500 (经济文档 8.1 ×10 锚); 首颗 (n=1 <= 软上限 64) 逐矿毛值 = 500 全额, 主闸首档 (累计毛收入 0) 系数 1.0。
        double base = ShopPriceTable.ORE_BASE_DIAMOND;
        helper.assertTrue(base == 500.0D, "diamond anchor base price is 500 (economy spec 8.1)");

        long beforeBalance = ledger.balance(player.getUUID(), Currency.CREDIT);
        helper.assertTrue(beforeBalance == 0L, "fresh wallet starts at 0 credit (pre-sale, the old 'zero income' state)");

        // onBlockBreak 对高价矿调用的正是 settleOreSale(player, ore, countSoFar, oreBasePrice(ore)); 首颗钻石 countSoFar=1。
        long credited = eco.settleOreSale(player, HighValueOre.DIAMOND, 1, base);
        helper.assertTrue(credited == 500L,
                "first diamond sale credits the full first-band amount 500 (per-ore full 500, main faucet band0 x1.0)");

        // Major 闭合的硬证据: 钱包真的增加了 500 (settleOreSale 经 grantDaily 真入账, 不再只计数). 删发钱逻辑 -> 余额恒 0 必挂。
        helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 500L,
                "selling one diamond actually credits 500 to the wallet (closes 'miner mining zero income' Major)");

        // 收购价递减地板已降到 1% (第十一章决策 1: 0.25 -> 0.01): 深档单颗钻石毛值 floor(500*0.01)=5, 仍 >= 1 (不被早返吞)。
        // 用一个全新玩家避开上面已累计的主闸毛收入, 隔离断言"逐矿 1% 地板"这一层 (与主闸衰减层解耦)。
        AbuseGuard guard = new AbuseGuard();
        double deepUnit = guard.buyPrice(HighValueOre.DIAMOND, 64 + 100_000, base);
        helper.assertTrue(approx(deepUnit, 5.0D),
                "deep over-softcap per-ore diamond unit floors to 1%: 500*0.01 = 5 (decision 1, was 125 at 0.25)");
        helper.succeed();
    }

    // ============================================================
    // 卖矿撞主闸后衰减 (反通胀北极星: 收入靠衰减主闸封顶, 吞吐只能更快撞顶不能突破)。
    // 把主闸毛收入累计预推到第 1 档边界 (60000) 后, 同一颗 500 毛值的钻石只到手 floor(500*0.6)=300 (band1 x0.6, 非首档 500)。
    // 删主闸逐档积分 (faucetCreditAfterDecayExact 的 band-walking) -> 仍按 band0 全额发 500 -> 本断言必挂。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oreSaleDecaysAfterMainFaucetBandCollision(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EconomyWalletData ledger = new EconomyWalletData();
        EconomyService eco = new EconomyService(ledger, new AbuseGuard(), newAbuseStateResolver());

        long tier = EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER; // 60000 每档毛收入
        helper.assertTrue(tier == 60_000L, "main faucet band size is 60000 gross income per band (decision 2)");

        // 预推: 经同一 credit_faucet 键发满整整一档毛收入 (60000), 当日累计原始毛收入指针推到 band1 起点。首档全额 60000 落账。
        long band0 = eco.grantDaily(player, tier, EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY, tier);
        helper.assertTrue(band0 == tier, "filling exactly one band (60000) credits in full at band0 x1.0");

        // 现在卖一颗钻石 (毛值 500): 累计毛收入指针在 [60000, 60500] 全落第 1 档, 系数 max(1%, 0.6^1)=0.6 -> 到手 floor(500*0.6)=300。
        // settleOreSale 与上面的预推 grant 共用同一 (player, credit_faucet) 累计计数器, 故撞进同一衰减档 (卖矿并入统一主闸)。
        long afterCollision = eco.settleOreSale(player, HighValueOre.DIAMOND, 1, ShopPriceTable.ORE_BASE_DIAMOND);
        helper.assertTrue(afterCollision == 300L,
                "a diamond sale landing in main-faucet band1 nets floor(500*0.6)=300, not the band0 full 500 (income capped by decay)");

        // 撞档后到手 (300) 严格小于首档同一颗钻石到手 (500): 吞吐再高也只是更快撞向 15 万渐近线, 不能突破封顶。
        helper.assertTrue(afterCollision < 500L,
                "post-collision diamond income (300) is strictly below the first-band income (500) (anti-inflation north star)");

        // 钱包真增长 = 首档 60000 + 撞档 300 (主闸把两笔并入同一玩家当日衰减带, 共享天花板)。
        helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 60_300L,
                "wallet holds band0 grant 60000 + band1 diamond 300 = 60300 (shared per-player daily faucet)");
        helper.succeed();
    }

    // ============================================================
    // 连锁回放产出也真发钱且受主闸封顶 (第十一章决策 3 单一发钱出口 + 反通胀第一道硬约束)。
    // MinerSystem.replayEconomyOreCount -> EconomyServices.economyService().recordMinedOreDrops -> 逐产出物 settleOreSale,
    // 与单块卖矿共享同一 credit_faucet 主闸: 3 颗钻石产出在首档各 500 -> 钱包 +1500; 撞第 1 档后各 300 -> +900 (受封顶)。
    // 删 recordMinedOreDrops 内的逐产出物 settleOreSale 循环 -> 钱包恒 0 必挂; 删主闸衰减 -> 撞档仍 1500 必挂。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chainReplaySettlesHighValueThroughSharedFaucet(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        states.put(player.getUUID(), new PlayerAbuseState());
        EconomyWalletData ledger = new EconomyWalletData();
        // 注入真实门面到定位器, 使 MinerSystem.replayEconomyOreCount 经 EconomyServices 取到它 (端到端连锁发钱接线)。
        EconomyService eco = new EconomyService(ledger, new AbuseGuard(), states::get);
        IEconomyService prev = swapEconomy(eco);
        try {
            MinerSystem sys = new MinerSystem();

            // 首档: 连带产出 3 颗钻石 (方案 B 按产出物个数), 经 replayEconomyOreCount 回放。每颗 countSoFar=1..3 均 <= 软上限 64,
            // 逐矿毛值各 500, 主闸首档 (累计毛收入 < 60000) 各全额 -> 钱包 +500*3=1500。证明连锁产出真发钱 (非 debug-log / 计数 0)。
            List<ItemStack> band0Drops = List.of(new ItemStack(Items.DIAMOND, 3));
            sys.replayEconomyOreCount(player, Blocks.DIAMOND_ORE, band0Drops);
            helper.assertTrue(states.get(player.getUUID()).dailyOreCount(HighValueOre.DIAMOND) == 3,
                    "chain replay records 3 produced diamonds into the daily ore count (scheme B by item count)");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 1_500L,
                    "chain-replayed high-value produced units actually pay: 3 diamonds x 500 (band0) = 1500 credited");

            // 撞档: 再把主闸毛收入累计推到刚过第 1 档边界, 然后连锁回放 3 颗钻石 -> 每颗落第 1 档系数 0.6, 各到手 floor(500*0.6)=300。
            // 当前主闸累计毛收入 = 1500 (上一步 3*500); 再发 (60000-1500)=58500 把累计精确推到 60000 (band1 起点), band0 内全额落账。
            long fill = eco.grantDaily(player, 60_000L - 1_500L,
                    EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY, EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER);
            helper.assertTrue(fill == 58_500L, "topping up to the band boundary credits the remaining band0 room in full (58500)");
            long balanceAtBoundary = ledger.balance(player.getUUID(), Currency.CREDIT);
            helper.assertTrue(balanceAtBoundary == 60_000L, "wallet sits exactly at one full band of credited income (60000)");

            sys.replayEconomyOreCount(player, Blocks.DIAMOND_ORE, List.of(new ItemStack(Items.DIAMOND, 3)));
            helper.assertTrue(states.get(player.getUUID()).dailyOreCount(HighValueOre.DIAMOND) == 6,
                    "second chain replay accumulates the daily ore count to 6 (3 + 3)");
            // 撞档后 3 颗钻石各只到手 300 (band1 x0.6) -> 钱包仅 +900 (60000 -> 60900), 远低于首档同样 3 颗的 +1500 (受主闸封顶)。
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 60_900L,
                    "chain replay in band1 nets 3 x floor(500*0.6)=900 (60000 -> 60900), capped by the SAME main faucet as single-block sale");
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    private static boolean approx(double a, double b) {
        return Math.abs(a - b) < 1.0e-6D;
    }

    // ============================================================
    // 定位器 swap/restore: 注入测试替身, 测试后还原启动期绑定的真实门面 (GameTest 在已启动服务端跑, 真实门面
    // 可能已注入; 直接 reset 会让后续 economy/job 依赖测试取门面时 IllegalStateException, 故保存并还原原值)。
    // ============================================================

    private static IEconomyService swapEconomy(IEconomyService fake) {
        IEconomyService prev = EconomyServices.isRegistered() ? EconomyServices.economyService() : null;
        EconomyServices.registerEconomyService(fake);
        return prev;
    }

    private static void restoreEconomy(IEconomyService prev) {
        if (prev != null) {
            EconomyServices.registerEconomyService(prev);
        } else {
            EconomyServices.reset();
        }
    }

    private static IJobService swapJob(IJobService fake) {
        IJobService prev = null;
        try {
            prev = JobServices.jobService();
        } catch (IllegalStateException notRegistered) {
            // 未注册时 jobService() 抛 (无 isRegistered 谓词); 视为无前值, 测试后 reset。
            prev = null;
        }
        JobServices.registerJobService(fake);
        return prev;
    }

    private static void restoreJob(IJobService prev) {
        if (prev != null) {
            JobServices.registerJobService(prev);
        } else {
            JobServices.reset();
        }
    }

    /**
     * 测试用 {@link PlayerAbuseState} 解析器 (与 {@link com.miningdim.economy.EconomySystem#playerState} 同纪律:
     * 未知 UUID 惰性建态而非返回 null), 供真实 {@link EconomyService} 的 settleOreSale/recordMinedOreDrops 取同一玩家态。
     * 每次调用返回独立 map 的解析器, 保证测试间不串态。
     */
    private static Function<UUID, PlayerAbuseState> newAbuseStateResolver() {
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        return id -> states.computeIfAbsent(id, k -> new PlayerAbuseState());
    }

    // ============================================================
    // 测试替身 (fakes): 记录调用的货币门面 / 职业门面 + 定值 RandomSource
    // ============================================================

    /** 记录 recordMinedOreDrops / isAfkFrozen 调用的货币门面替身; 其余方法在本测试不触达, 调用即编程错抛。 */
    private static final class RecordingEconomy implements IEconomyService {
        int recordCalls = 0;
        Block lastBlock = null;
        int lastProducedCount = Integer.MIN_VALUE;
        boolean afkFrozen = false;

        @Override
        public int recordMinedOreDrops(ServerPlayer player, Block block, int producedCount) {
            recordCalls++;
            lastBlock = block;
            lastProducedCount = producedCount;
            return producedCount;
        }

        @Override
        public boolean isAfkFrozen(ServerPlayer player) {
            return afkFrozen;
        }

        @Override
        public long creditBalance(ServerPlayer player) {
            throw new UnsupportedOperationException("not exercised by miner wiring tests");
        }

        @Override
        public long heartstoneBalance(ServerPlayer player) {
            throw new UnsupportedOperationException("not exercised by miner wiring tests");
        }

        @Override
        public boolean tryCharge(ServerPlayer player, Currency currency, long amount) {
            throw new UnsupportedOperationException("not exercised by miner wiring tests");
        }

        @Override
        public void grant(ServerPlayer player, Currency currency, long amount) {
            throw new UnsupportedOperationException("not exercised by miner wiring tests");
        }

        @Override
        public boolean tryChargeDaily(ServerPlayer player, Currency currency, long amount, String dailyKey, long dailyCap) {
            throw new UnsupportedOperationException("not exercised by miner wiring tests");
        }

        @Override
        public long settleOreSale(ServerPlayer player, HighValueOre ore, int countSoFar, double basePrice) {
            throw new UnsupportedOperationException("not exercised by miner wiring tests");
        }

        @Override
        public long grantDaily(ServerPlayer player, long rawCredit, String faucetKey, long dailyCap) {
            throw new UnsupportedOperationException("not exercised by miner wiring tests");
        }

        @Override
        public long grantAzureDaily(ServerPlayer player, long amount, long dailyCap) {
            throw new UnsupportedOperationException("not exercised by miner wiring tests");
        }
    }

    /** 记录 grantXp 调用的职业门面替身; level/totalXp 给固定值供其它读法, progress 不触达。 */
    private static final class RecordingJobService implements IJobService {
        int grantXpCalls = 0;
        JobId lastJob = null;
        long lastRawXp = Long.MIN_VALUE;

        @Override
        public int level(Player player, JobId job) {
            return 1;
        }

        @Override
        public long totalXp(Player player, JobId job) {
            return 0L;
        }

        @Override
        public long grantXp(Player player, JobId job, long rawXp) {
            grantXpCalls++;
            lastJob = job;
            lastRawXp = rawXp;
            return rawXp;
        }

        @Override
        public JobProgress progress(Player player, JobId job) {
            throw new UnsupportedOperationException("not exercised by miner wiring tests");
        }
    }

    /**
     * 定值 RandomSource: nextDouble 恒返回构造时给定值, 使时运额外掉落判定确定可测 (不引入随机性)。
     * 其余方法本测试不触达, 调用即编程错抛 (不静默返默认值掩盖误用)。
     */
    private static final class FixedRandom implements RandomSource {
        private final double value;

        FixedRandom(double value) {
            this.value = value;
        }

        @Override
        public double nextDouble() {
            return value;
        }

        @Override
        public RandomSource fork() {
            throw new UnsupportedOperationException("FixedRandom: not exercised");
        }

        @Override
        public PositionalRandomFactory forkPositional() {
            throw new UnsupportedOperationException("FixedRandom: not exercised");
        }

        @Override
        public void setSeed(long seed) {
            throw new UnsupportedOperationException("FixedRandom: not exercised");
        }

        @Override
        public int nextInt() {
            throw new UnsupportedOperationException("FixedRandom: not exercised");
        }

        @Override
        public int nextInt(int bound) {
            throw new UnsupportedOperationException("FixedRandom: not exercised");
        }

        @Override
        public long nextLong() {
            throw new UnsupportedOperationException("FixedRandom: not exercised");
        }

        @Override
        public boolean nextBoolean() {
            throw new UnsupportedOperationException("FixedRandom: not exercised");
        }

        @Override
        public float nextFloat() {
            throw new UnsupportedOperationException("FixedRandom: not exercised");
        }

        @Override
        public double nextGaussian() {
            throw new UnsupportedOperationException("FixedRandom: not exercised");
        }
    }
}
