package com.miningdim.job.miner;

import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.EconomyConstants.HighValueOre;
import com.miningdim.job.IJobService;
import com.miningdim.job.JobId;
import com.miningdim.job.JobProgress;
import com.miningdim.job.JobServices;
import com.miningdim.ore.OreType;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;

/**
 * 矿工职业核心逻辑 GameTest (断言具体业务结果, 删被测核心逻辑测试必挂; 禁 is-not-null 弱校验; 含边界值)。
 *
 * 覆盖: 挖速封顶、省耐久封顶、时运封顶、难度门控边界、减 danger 满级封底、矿脉抗性陷阱专属、连锁白名单/硬排除、
 * 自动熔炼 1:1、探测可探矿种里程碑、陷阱探测致死门控; 以及复审缺陷闭合的回归断言 (删修复测试必挂):
 *  - 连锁/隧道经济计数回放按产出物个数 (方案 B) 经货币门面入账 (反通胀第一道硬约束, 非 debug-log/计数 0);
 *  - 时运额外掉落随连带产出进经济计数 (时运计入隐藏软上限, 非死代码);
 *  - 矿脉时运按期望确定性追加额外掉落 + 等级门控 (L1-3 死 / L4+ 活);
 *  - AFK 冻结态不计挖矿经验 (第九章反挂机红线), 解冻后正常计经验。
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
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void veinResistTrapOnly(GameTestHelper helper) {
        // 减伤比阶梯。
        helper.assertTrue(approx(MinerSkills.trapDamageReduction(4), 0.0D), "vein resist locked below L5 -> 0");
        helper.assertTrue(approx(MinerSkills.trapDamageReduction(5), 0.10D), "L5 vein resist = 10%");
        helper.assertTrue(approx(MinerSkills.trapDamageReduction(10), 0.35D), "L10 vein resist = 35% cap");
        helper.assertTrue(MinerSkills.trapDamageReduction(10) <= 0.35D + EPS, "vein resist never exceeds 35%");
        // 非陷阱来源零减免 (红线): isTrapSource 当前对所有真实来源返回 false -> reducedDamage 原样。
        net.minecraft.world.damagesource.DamageSources sources =
                helper.getLevel().damageSources();
        float mobBlast = MinerSurvival.reducedDamage(10, sources.explosion(null, null), 20.0f);
        helper.assertTrue(approx(mobBlast, 20.0f), "non-trap (creeper/explosion) damage reduced by 0% at L10");
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
