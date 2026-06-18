package com.miningdim.job.miner;

import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import com.miningdim.ore.OreType;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;

/**
 * 矿工职业核心逻辑 GameTest (断言具体业务结果, 删被测核心逻辑测试必挂; 禁 is-not-null 弱校验; 含边界值)。
 *
 * 覆盖契约 tests 字段的可纯逻辑断言项 (世界依赖项如连锁 destroyBlock / 经济计数回放须 economy 接线后补,
 * 见 notes): 挖速封顶、省耐久封顶、时运封顶、难度门控边界、减 danger 满级封底、矿脉抗性陷阱专属、
 * 连锁白名单/硬排除、自动熔炼 1:1、探测可探矿种里程碑、陷阱探测致死门控。
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
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
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

    private static boolean approx(double a, double b) {
        return Math.abs(a - b) < 1.0e-6D;
    }
}
