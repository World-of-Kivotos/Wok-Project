package com.miningdim.job.munitions;

import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.EconomyLedger;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.job.IJobService;
import com.miningdim.job.JobId;
import com.miningdim.job.JobProgress;
import com.miningdim.job.JobServices;
import com.miningdim.job.munitions.block.MunitionsBenchBlock;
import com.miningdim.job.munitions.block.MunitionsBenchBlockEntity;
import com.miningdim.job.munitions.menu.MunitionsBenchMenu;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 军火商核心逻辑 GameTest (Munitions_Job_DesignSpec 四/五/六/七/九章测试断言)。断言具体业务数值 (删被测核心逻辑
 * 测试必挂, 禁 is-not-null 弱校验; 含边界值)。
 *
 * compileOnly 铁律 (本任务硬约束): 全部断言只触纯逻辑业务结果 (产多少发/什么口径/扣多少料/扣多少工费/给多少经验),
 * 绝不进物化路径 ({@link MunitionsAmmoFactory#materialize} / TACZ {@code AmmoItemBuilder}) —— dev GameTest 运行期
 * TACZ 未加载, 进物化即 NoClassDefFoundError。BE 用例只调 {@link MunitionsBenchBlockEntity#trySelectCaliber} 与
 * {@link MunitionsBenchBlockEntity#settleForOwner}, 二者的产能/料/工费/经验路径全程纯逻辑; 物化点
 * refreshOutputStack 在 TACZ 未加载时 materialize 短路返回 EMPTY (输出槽留空), 不抛, 故 BE 结算可安全跑。
 *
 * 数值断言均以 {@link MunitionsConfig} 默认值为准 (C6: GameTest 用 config 默认真值)。纯逻辑用 template = "empty"
 * (职业框架已建 data/miningdim/structures/empty.nbt)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MunitionsGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "munitions";

    /**
     * 批前钩子: 绑定 MunitionsConfig 默认值 (本子系统集成阶段才接进 MiningDim, runGameTestServer 时其 SERVER spec
     * 未经 Forge 加载; 不绑定则 dev 环境下 ConfigValue.get() 抛 IllegalStateException)。
     */
    @BeforeBatch(batch = BATCH)
    public static void beforeMunitionsBatch(ServerLevel level) {
        MunitionsConfig.ensureLoadedForTest();
    }

    // ============================================================
    // 6.1 产能曲线查表 (台数/速率/缓冲逐级精确)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void capacityCurveLookups(GameTestHelper helper) {
        // L1 地基: 1 台 / 50 发每时 / 500 缓冲。
        helper.assertTrue(MunitionsLevels.tableCount(1) == 1, "L1 tableCount must be 1");
        helper.assertTrue(MunitionsLevels.ratePerTable(1) == 50, "L1 rate must be 50/hr");
        helper.assertTrue(MunitionsLevels.bufferPerTable(1) == 500, "L1 buffer must be 500");

        // L10 毕业: 6 台 / 210 发每时 / 4000 缓冲 (任务锚点)。
        helper.assertTrue(MunitionsLevels.tableCount(10) == 6, "L10 tableCount must be 6");
        helper.assertTrue(MunitionsLevels.ratePerTable(10) == 210, "L10 rate must be 210/hr");
        helper.assertTrue(MunitionsLevels.bufferPerTable(10) == 4000, "L10 buffer must be 4000");

        // clampLevel 防越界查表 (0 -> L1 下界; 99 -> L10 上界)。删 clampLevel 这两断言越界崩或读错档。
        helper.assertTrue(MunitionsLevels.tableCount(0) == MunitionsLevels.tableCount(1),
                "level below MIN clamps to L1 capacity");
        helper.assertTrue(MunitionsLevels.bufferPerTable(99) == MunitionsLevels.bufferPerTable(10),
                "level above MAX clamps to L10 capacity");
        helper.succeed();
    }

    // ============================================================
    // 6.1 口径等级门 + 四章 L6 提炼解锁
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void caliberAndRefineGates(GameTestHelper helper) {
        // 步枪 unlockLevel = L3: L1 锁, L2 锁, L3 解锁 (等级门)。
        helper.assertFalse(MunitionsLevels.isCaliberUnlocked(1, MunitionsCaliber.RIFLE),
                "L1 player CANNOT use RIFLE caliber (rifle gated to L3)");
        helper.assertFalse(MunitionsLevels.isCaliberUnlocked(2, MunitionsCaliber.RIFLE),
                "L2 still cannot use RIFLE (needs L3)");
        helper.assertTrue(MunitionsLevels.isCaliberUnlocked(3, MunitionsCaliber.RIFLE),
                "L3 unlocks RIFLE caliber");
        // 手枪 L1 / 特种 L10 端点。
        helper.assertTrue(MunitionsLevels.isCaliberUnlocked(1, MunitionsCaliber.PISTOL),
                "PISTOL unlocked from L1");
        helper.assertFalse(MunitionsLevels.isCaliberUnlocked(9, MunitionsCaliber.SPECIAL),
                "SPECIAL gated to L10, L9 cannot use it");
        helper.assertTrue(MunitionsLevels.isCaliberUnlocked(10, MunitionsCaliber.SPECIAL),
                "L10 unlocks SPECIAL (graduation caliber)");

        // highestUnlockedCaliber 单调: L1 仅手枪, L5 到战斗机枪 (L5 门), L10 到特种。
        helper.assertTrue(MunitionsLevels.highestUnlockedCaliber(1) == MunitionsCaliber.PISTOL,
                "L1 highest unlocked is PISTOL");
        helper.assertTrue(MunitionsLevels.highestUnlockedCaliber(5) == MunitionsCaliber.BATTLE,
                "L5 highest unlocked is BATTLE (battle rifle gate)");
        helper.assertTrue(MunitionsLevels.highestUnlockedCaliber(10) == MunitionsCaliber.SPECIAL,
                "L10 highest unlocked is SPECIAL");

        // 四章 L6 提炼利润质变线: L5 未解锁, L6 解锁。
        helper.assertFalse(MunitionsLevels.isRefineUnlocked(5), "L5 has NOT unlocked propellant refining");
        helper.assertTrue(MunitionsLevels.isRefineUnlocked(6), "L6 unlocks refining (profit inflection)");
        helper.succeed();
    }

    // ============================================================
    // 口径枚举: 序号往返 + 真 TACZ 默认弹药 path + 缩产系数 (无 TACZ 触达)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void caliberEnumMapping(GameTestHelper helper) {
        // byIndex/index 往返自洽 (NBT/ContainerData 同步基石)。
        for (MunitionsCaliber c : MunitionsCaliber.values()) {
            helper.assertTrue(MunitionsCaliber.byIndex(c.index()) == c,
                    "byIndex(index) round-trips " + c);
        }
        // 越界回退 PISTOL (防数组越界崩, 不掩盖业务)。
        helper.assertTrue(MunitionsCaliber.byIndex(-1) == MunitionsCaliber.PISTOL,
                "negative index falls back to PISTOL");
        helper.assertTrue(MunitionsCaliber.byIndex(999) == MunitionsCaliber.PISTOL,
                "out-of-range index falls back to PISTOL");

        // 真默认枪包弹药 path (data/tacz/index/ammo/<path>.json; 仅字符串, 不构造 ResourceLocation / 触 com.tacz.*)。
        helper.assertTrue("762x39".equals(MunitionsCaliber.RIFLE.defaultAmmoPath()),
                "RIFLE default ammo path is 762x39, got " + MunitionsCaliber.RIFLE.defaultAmmoPath());
        helper.assertTrue("556x45".equals(MunitionsCaliber.RIFLE_556.defaultAmmoPath()),
                "RIFLE_556 default ammo path is 556x45, got " + MunitionsCaliber.RIFLE_556.defaultAmmoPath());
        helper.assertTrue(MunitionsCaliber.RIFLE_556.category() == MunitionsCaliber.Category.RIFLE,
                "RIFLE_556 appears under rifle ammo category");
        helper.assertTrue("40mm".equals(MunitionsCaliber.EXPLOSIVE.defaultAmmoPath()),
                "EXPLOSIVE default ammo path is 40mm, got " + MunitionsCaliber.EXPLOSIVE.defaultAmmoPath());
        helper.assertTrue("9mm".equals(MunitionsCaliber.PISTOL.defaultAmmoPath()),
                "PISTOL default ammo path is 9mm");
        helper.assertTrue(MunitionsCaliber.TACZ_NAMESPACE.equals("tacz"),
                "caliber namespace is constant tacz");

        // 解锁等级与 6.1 等级门一致 (枚举绑定真源)。
        helper.assertTrue(MunitionsCaliber.PISTOL.unlockLevel() == 1, "PISTOL unlock L1");
        helper.assertTrue(MunitionsCaliber.RIFLE.unlockLevel() == 3, "RIFLE unlock L3");
        helper.assertTrue(MunitionsCaliber.SPECIAL.unlockLevel() == 10, "SPECIAL unlock L10");

        // 缩产系数: 步枪基准 1.0; 高阶弹 (反器材/爆炸) 严格 < 步枪 (单发料重出弹少)。
        helper.assertTrue(MunitionsCaliber.RIFLE.yieldFactor() == 1.0,
                "RIFLE yield factor is baseline 1.0");
        helper.assertTrue(MunitionsCaliber.ANTI_MATERIEL.yieldFactor() < MunitionsCaliber.RIFLE.yieldFactor(),
                "ANTI_MATERIEL yield factor strictly below rifle (shrink)");
        helper.assertTrue(MunitionsCaliber.EXPLOSIVE.yieldFactor() < MunitionsCaliber.RIFLE.yieldFactor(),
                "EXPLOSIVE yield factor strictly below rifle (shrink)");
        helper.succeed();
    }

    // ============================================================
    // 单批口径实发数 (四章配方): L5 步枪直造 40, L6 步枪提炼 70 (利润质变线) + 高阶弹缩产
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void roundsPerBatchRefineInflection(GameTestHelper helper) {
        // L5 步枪 (提炼未解锁): 直造基准 40 × 1.0 = 40。
        helper.assertTrue(MunitionsProduction.roundsPerBatch(MunitionsCaliber.RIFLE, 5) == 40,
                "L5 rifle batch is direct 40 rounds (refine not yet unlocked)");
        // L6 步枪 (提炼解锁): 提炼基准 70 × 1.0 = 70 (翻倍利润质变线)。
        helper.assertTrue(MunitionsProduction.roundsPerBatch(MunitionsCaliber.RIFLE, 6) == 70,
                "L6 rifle batch jumps to refined 70 rounds (profit inflection)");
        // 删 isRefineUnlocked 分支 (恒用 DIRECT 40) -> L6 步枪仍 40, 上面 ==70 断言挂。

        // 高阶弹缩产: 同 L6 提炼基准 70, 反器材 (0.25) / 爆炸 (0.15) 严格 < 步枪 70。
        int rifleL6 = MunitionsProduction.roundsPerBatch(MunitionsCaliber.RIFLE, 6);
        int antiL6 = MunitionsProduction.roundsPerBatch(MunitionsCaliber.ANTI_MATERIEL, 6);
        int explosiveL6 = MunitionsProduction.roundsPerBatch(MunitionsCaliber.EXPLOSIVE, 6);
        helper.assertTrue(antiL6 < rifleL6,
                "ANTI_MATERIEL batch (" + antiL6 + ") strictly fewer than rifle (" + rifleL6 + ")");
        helper.assertTrue(explosiveL6 < rifleL6,
                "EXPLOSIVE batch (" + explosiveL6 + ") strictly fewer than rifle (" + rifleL6 + ")");
        // 精确缩产: floor(70 × 0.25) = 17; floor(70 × 0.15) = 10。删缩产 (恒用基准) 此两断言挂。
        helper.assertTrue(antiL6 == (int) Math.floor(70 * MunitionsCaliber.ANTI_MATERIEL.yieldFactor()),
                "ANTI_MATERIEL L6 = floor(70 * 0.25) = 17, got " + antiL6);
        helper.assertTrue(explosiveL6 == (int) Math.floor(70 * MunitionsCaliber.EXPLOSIVE.yieldFactor()),
                "EXPLOSIVE L6 = floor(70 * 0.15) = 10, got " + explosiveL6);
        // 缩产后至少 1 发 (有料即产, 不静默吞 0)。
        helper.assertTrue(MunitionsProduction.roundsPerBatch(MunitionsCaliber.EXPLOSIVE, 5) >= 1,
                "batch floor is >=1 round (never silently zero)");
        helper.succeed();
    }

    // ============================================================
    // 速率->tick 换算 (11.3): ceil(ticksPerRateHour / rate), >=1
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ticksPerRoundCeil(GameTestHelper helper) {
        int hourTicks = MunitionsConfig.TICKS_PER_RATE_HOUR.get(); // 72000
        // L1 rate 50: ceil(72000 / 50) = 1440 tick/发。
        int expectL1 = (hourTicks + 50 - 1) / 50;
        helper.assertTrue(MunitionsProduction.ticksPerRound(1) == expectL1,
                "L1 ticksPerRound = ceil(72000/50) = " + expectL1 + ", got " + MunitionsProduction.ticksPerRound(1));
        helper.assertTrue(expectL1 == 1440, "ticksPerRound L1 numeric anchor = 1440");
        // L10 rate 210: ceil(72000 / 210) = 343 (向上取整, 71820/210=342 余 -> 343)。
        int expectL10 = (hourTicks + 210 - 1) / 210;
        helper.assertTrue(MunitionsProduction.ticksPerRound(10) == expectL10,
                "L10 ticksPerRound = ceil(72000/210) = " + expectL10);
        helper.assertTrue(expectL10 == 343, "ticksPerRound L10 numeric anchor = 343 (ceil, not floor 342)");
        // 高速率永不到 0 (>=1 下界, 防瞬产/0 除)。
        helper.assertTrue(MunitionsProduction.ticksPerRound(10) >= 1, "ticksPerRound floored to >=1");
        helper.succeed();
    }

    // ============================================================
    // 理论产能 (五章 总产能 = 台数 × 速率): capacity = floor(elapsed/ticksPerRound) × tableCount
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void theoreticalRoundsCapacity(GameTestHelper helper) {
        int level = 1;
        int perRound = MunitionsProduction.ticksPerRound(level); // 1440
        // 恰好 10 发的时间 × 3 台 = 30 发 (台数线性乘子)。
        long elapsed = perRound * 10L;
        helper.assertTrue(MunitionsProduction.theoreticalRounds(elapsed, 1, level) == 10L,
                "1 table over 10-round time yields 10 rounds");
        helper.assertTrue(MunitionsProduction.theoreticalRounds(elapsed, 3, level) == 30L,
                "3 tables over same time yields 3x = 30 rounds (capacity = per-table × tableCount). "
                        + "Delete the × tableCount and this must fail.");
        // 不足一发的时间 -> 0 (向下取整, 不产半发)。
        helper.assertTrue(MunitionsProduction.theoreticalRounds(perRound - 1, 5, level) == 0L,
                "sub-one-round time yields 0 (floor, no fractional rounds)");
        // 边界: 0 流逝 / 0 台 -> 0。
        helper.assertTrue(MunitionsProduction.theoreticalRounds(0L, 4, level) == 0L, "0 elapsed -> 0");
        helper.assertTrue(MunitionsProduction.theoreticalRounds(elapsed, 0, level) == 0L, "0 tables -> 0");
        helper.succeed();
    }

    // ============================================================
    // 工费 (九章 sink): floor(rounds × 1.5) 经 ×10 锚价整数化为 floor(rounds × 15 / 10)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void workFeeIntegerized(GameTestHelper helper) {
        // 精确 CP: 10 发 -> 15; 100 发 -> 150; 1 发 -> floor(1.5) = 1; 7 发 -> floor(10.5) = 10。
        helper.assertTrue(MunitionsProduction.workFee(10) == 15L, "10 rounds work fee = 15 CP (1.5/round)");
        helper.assertTrue(MunitionsProduction.workFee(100) == 150L, "100 rounds work fee = 150 CP");
        helper.assertTrue(MunitionsProduction.workFee(1) == 1L, "1 round = floor(1.5) = 1 CP");
        helper.assertTrue(MunitionsProduction.workFee(7) == 10L, "7 rounds = floor(7*15/10) = floor(10.5) = 10 CP");
        helper.assertTrue(MunitionsProduction.workFee(3) == 4L, "3 rounds = floor(3*15/10) = floor(4.5) = 4 CP");
        // 边界: 0 发 0 工费。
        helper.assertTrue(MunitionsProduction.workFee(0) == 0L, "0 rounds = 0 fee");
        // 删 /10 (恒按 ×10 锚价不还原) -> 10 发会变 150, 上面 ==15 断言挂。
        helper.succeed();
    }

    // ============================================================
    // 产弹经验 (七章 谁产谁得): floor(rounds × perRoundMilli / 1000)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void produceXpByRounds(GameTestHelper helper) {
        int perMilli = MunitionsConfig.PRODUCE_XP_PER_ROUND_MILLI.get(); // 1000 = 1 xp/round
        // perMilli 1000: 每发 1 经验 -> N 发 = N 经验。
        helper.assertTrue(MunitionsProduction.produceXp(40) == (long) 40 * perMilli / 1000L,
                "40 rounds produce xp = floor(40 * perMilli / 1000)");
        helper.assertTrue(MunitionsProduction.produceXp(40) == 40L, "40 rounds = 40 raw xp at default 1000 milli");
        helper.assertTrue(MunitionsProduction.produceXp(0) == 0L, "0 rounds = 0 xp");
        // 更多发更多经验 (单调; 谁产谁得按发数线性)。
        helper.assertTrue(MunitionsProduction.produceXp(70) > MunitionsProduction.produceXp(40),
                "more rounds yield strictly more raw xp (70 > 40)");
        helper.succeed();
    }

    // ============================================================
    // 离线追算核心 settle (五章): rounds == batches × roundsPerBatch; 三门取最小 (时间/缓冲/料)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void settleClampedByMaterial(GameTestHelper helper) {
        // 料瓶颈: 充裕时间 + 充裕缓冲, 但四件套只够 2 批。
        int level = 5; // 直造 40 发/批 (步枪)。
        int perBatch = MunitionsProduction.roundsPerBatch(MunitionsCaliber.RIFLE, level); // 40
        long bigElapsed = MunitionsProduction.ticksPerRound(level) * 100_000L; // 远超需求。
        int bigBuffer = 100_000;
        // 备料 = 2 批 x 各件单批 cost (对默认 cost 变更鲁棒, propellantCost 默认 2 -> 备 4)。
        MunitionsProduction.Result r = MunitionsProduction.settle(
                MunitionsCaliber.RIFLE, level, 1, bigElapsed, bigBuffer,
                2 * MunitionsConfig.RECIPE_PRIMER_COST.get(),
                2 * MunitionsConfig.RECIPE_CASING_COST.get(),
                2 * MunitionsConfig.RECIPE_BULLET_HEAD_COST.get(),
                2 * MunitionsConfig.RECIPE_PROPELLANT_COST.get(), Integer.MAX_VALUE);
        helper.assertTrue(r.batchesConsumed() == 2, "material caps to exactly 2 batches, got " + r.batchesConsumed());
        helper.assertTrue(r.roundsProduced() == 2 * perBatch,
                "rounds == batches × roundsPerBatch = 2*40 = 80, got " + r.roundsProduced());
        helper.assertTrue(r.primerConsumed() == 2 * MunitionsConfig.RECIPE_PRIMER_COST.get(),
                "primer consumed = 2 batches, got " + r.primerConsumed());
        helper.assertTrue(r.casingConsumed() == 2 * MunitionsConfig.RECIPE_CASING_COST.get(),
                "casing consumed = 2 batches, got " + r.casingConsumed());
        helper.assertTrue(r.bulletHeadConsumed() == 2 * MunitionsConfig.RECIPE_BULLET_HEAD_COST.get(),
                "bullet head consumed = 2 batches, got " + r.bulletHeadConsumed());
        helper.assertTrue(r.propellantConsumed() == 2 * MunitionsConfig.RECIPE_PROPELLANT_COST.get(),
                "propellant consumed = 2 batches, got " + r.propellantConsumed());
        // 工费/经验与实产发数挂钩: 80 发 -> floor(80*1.5)=120 CP; 80 raw xp。
        helper.assertTrue(r.workFeeCredits() == MunitionsProduction.workFee(80),
                "work fee tracks produced rounds (80 -> 120 CP)");
        helper.assertTrue(r.workFeeCredits() == 120L, "80 rounds work fee numeric = 120 CP");
        helper.assertTrue(r.rawXp() == MunitionsProduction.produceXp(80), "raw xp tracks produced rounds (80)");
        helper.succeed();
    }

    /**
     * 电力门。电不足不是停产而是减产, 这是"电不够就一颗一颗慢慢造"的落点。
     * 同时钉死"每批电费与口径无关": 电价按步枪当量收, 单批实发数又正好乘了同一个 yieldFactor,
     * 两者约掉。若改成按实发数收电, 大口径的每 FE 产出会是小口径的十倍, 小口径彻底死掉。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void settleClampedByPowerAndCostIsCaliberIndependent(GameTestHelper helper) {
        int level = 1;
        long bigElapsed = 20L * MunitionsConfig.TICKS_PER_RATE_HOUR.get();
        int feePerBatch = MunitionsProduction.feCostPerBatch(level);
        helper.assertTrue(feePerBatch == MunitionsConfig.DIRECT_ROUNDS_PER_BATCH.get()
                        * MunitionsConfig.FE_PER_RIFLE_EQUIVALENT_ROUND.get(),
                "每批电费必须等于基础批发数乘每发电价, 得到 " + feePerBatch);

        MunitionsProduction.Result twoBatches = MunitionsProduction.settle(
                MunitionsCaliber.RIFLE, level, 1, bigElapsed, 100_000,
                9999, 9999, 9999, 9999, feePerBatch * 2);
        helper.assertTrue(twoBatches.batchesConsumed() == 2,
                "两批的电只能产两批, 得到 " + twoBatches.batchesConsumed());
        helper.assertTrue(twoBatches.feConsumed() == feePerBatch * 2,
                "扣电必须等于批数乘每批电费, 得到 " + twoBatches.feConsumed());

        MunitionsProduction.Result shortOfOne = MunitionsProduction.settle(
                MunitionsCaliber.RIFLE, level, 1, bigElapsed, 100_000,
                9999, 9999, 9999, 9999, feePerBatch - 1);
        helper.assertFalse(shortOfOne.produced(),
                "不足一批的电必须一发不产, 得到 " + shortOfOne.roundsProduced());
        helper.assertTrue(shortOfOne.feConsumed() == 0, "不产就不得扣电");

        // 同等级同电量下, 不同口径消耗的电必须完全一致 —— 大口径只是发数少、每发更贵。
        MunitionsProduction.Result rifle = MunitionsProduction.settle(
                MunitionsCaliber.RIFLE, level, 1, bigElapsed, 100_000,
                9999, 9999, 9999, 9999, feePerBatch);
        MunitionsProduction.Result antiMateriel = MunitionsProduction.settle(
                MunitionsCaliber.ANTI_MATERIEL, level, 1, bigElapsed, 100_000,
                9999, 9999, 9999, 9999, feePerBatch);
        helper.assertTrue(rifle.feConsumed() == antiMateriel.feConsumed(),
                "同等级每批电费必须与口径无关, 步枪 " + rifle.feConsumed()
                        + " 反器材 " + antiMateriel.feConsumed());
        helper.assertTrue(rifle.roundsProduced() > antiMateriel.roundsProduced(),
                "同样一批电, 大口径的发数必须更少(缩产系数), 步枪 " + rifle.roundsProduced()
                        + " 反器材 " + antiMateriel.roundsProduced());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void settleClampedByBufferAndTime(GameTestHelper helper) {
        int level = 5;
        int perBatch = MunitionsProduction.roundsPerBatch(MunitionsCaliber.RIFLE, level); // 40

        // 缓冲瓶颈: 料/时间充裕, 但缓冲只剩 100 发 -> 落整到 2 批 (80 发), 第 3 批 (>100) 不产。
        int bufferRemaining = 100;
        long bigElapsed = MunitionsProduction.ticksPerRound(level) * 100_000L;
        MunitionsProduction.Result byBuffer = MunitionsProduction.settle(
                MunitionsCaliber.RIFLE, level, 1, bigElapsed, bufferRemaining, 9999, 9999, 9999, 9999, Integer.MAX_VALUE);
        helper.assertTrue(byBuffer.batchesConsumed() == bufferRemaining / perBatch,
                "buffer 100 / 40-per-batch floors to 2 batches, got " + byBuffer.batchesConsumed());
        helper.assertTrue(byBuffer.roundsProduced() == 2 * perBatch,
                "buffer-clamped to 80 rounds (does not overfill the 100-round space), got "
                        + byBuffer.roundsProduced());
        helper.assertTrue(byBuffer.roundsProduced() <= bufferRemaining,
                "produced never exceeds buffer remaining (buffer-full stops production)");

        // 时间瓶颈: 料/缓冲充裕, 但流逝只够 1 批的步枪当量时间 -> 1 批 (40 发)。
        long oneBatchTime = MunitionsProduction.ticksPerRound(level) * (long) perBatch; // 恰好 40 发时间。
        MunitionsProduction.Result byTime = MunitionsProduction.settle(
                MunitionsCaliber.RIFLE, level, 1, oneBatchTime, 9999, 9999, 9999, 9999, 9999, Integer.MAX_VALUE);
        helper.assertTrue(byTime.batchesConsumed() == 1,
                "elapsed sufficient for exactly 1 batch yields 1 batch, got " + byTime.batchesConsumed());
        helper.assertTrue(byTime.roundsProduced() == perBatch, "time-clamped to 1 batch = 40 rounds");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void settleNonProductiveBoundaries(GameTestHelper helper) {
        int level = 5;
        long bigElapsed = MunitionsProduction.ticksPerRound(level) * 100_000L;
        // 未选口径 (null) -> NONE。
        helper.assertTrue(MunitionsProduction.settle(null, level, 1, bigElapsed, 9999, 9999, 9999, 9999, 9999, Integer.MAX_VALUE)
                == MunitionsProduction.Result.NONE, "null caliber forfeits (NONE)");
        // 缓冲已满 (剩 0) -> NONE。
        helper.assertFalse(MunitionsProduction.settle(MunitionsCaliber.RIFLE, level, 1, bigElapsed, 0, 9999, 9999, 9999, 9999, Integer.MAX_VALUE)
                .produced(), "buffer full (remaining 0) produces nothing");
        // 料不足一批 -> NONE。
        helper.assertFalse(MunitionsProduction.settle(MunitionsCaliber.RIFLE, level, 1, bigElapsed, 9999, 0, 9999, 9999, 9999, Integer.MAX_VALUE)
                .produced(), "missing primer produces nothing (先查后扣, 不白产)");
        // 0 流逝 -> NONE (未达时间不产)。
        helper.assertFalse(MunitionsProduction.settle(MunitionsCaliber.RIFLE, level, 1, 0L, 9999, 9999, 9999, 9999, 9999, Integer.MAX_VALUE)
                .produced(), "0 elapsed produces nothing");
        // 时间不足一整批 (1 发的时间, 但一批要 40 发) -> NONE (按整批走料)。
        long subBatchTime = MunitionsProduction.ticksPerRound(level) * 1L;
        helper.assertFalse(MunitionsProduction.settle(MunitionsCaliber.RIFLE, level, 1, subBatchTime, 9999, 9999, 9999, 9999, 9999, Integer.MAX_VALUE)
                .produced(), "time for <1 full batch produces nothing (整批走料, 不产半批)");
        helper.succeed();
    }

    // ============================================================
    // 放置计数持久层 (5/10.5 台数上限校验基石): benchCount/increment/decrement
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void savedDataBenchCount(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        MunitionsSavedData data = MunitionsSavedData.get(overworld);
        UUID who = UUID.randomUUID(); // 全新 UUID 防跨测试串扰。

        helper.assertTrue(data.benchCount(who) == 0, "unknown player has 0 benches");
        helper.assertTrue(data.increment(who) == 1, "first placement -> count 1");
        helper.assertTrue(data.increment(who) == 2, "second placement -> count 2");
        helper.assertTrue(data.benchCount(who) == 2, "benchCount reads back 2");
        helper.assertTrue(data.decrement(who) == 1, "break one -> count 1");
        helper.assertTrue(data.decrement(who) == 0, "break last -> count 0 (entry pruned)");
        helper.assertTrue(data.benchCount(who) == 0, "pruned player reads 0 again");
        // 计数不低于 0 (破坏多于放置不变负)。
        helper.assertTrue(data.decrement(who) == 0, "decrement below 0 clamps to 0 (no negative count)");
        helper.succeed();
    }

    // ============================================================
    // BE 选口径服务端权威等级门 (6.1): 拒未解锁口径 + 拒缓冲口径冲突 (用 MockGameTestPlayers)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchSelectCaliberLevelGate(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        IJobService prevJob = swapJob(new FixedLevelJobService(3)); // L3: 步枪解锁, 狙击 (L6) 不解锁。
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);

            // L3 选步枪 (unlock L3): 接受。
            helper.assertTrue(be.trySelectCaliber(MunitionsCaliber.RIFLE, player),
                    "L3 player selects RIFLE (unlock L3) accepted");
            helper.assertTrue(be.selectedCaliber() == MunitionsCaliber.RIFLE, "selected caliber persisted as RIFLE");

            // L3 选狙击 (unlock L6): 服务端重校等级门拒绝, 选中口径不变 (不信客户端置灰)。
            helper.assertFalse(be.trySelectCaliber(MunitionsCaliber.SNIPER, player),
                    "L3 player CANNOT select SNIPER (gated to L6) - server rejects");
            helper.assertTrue(be.selectedCaliber() == MunitionsCaliber.RIFLE,
                    "rejected selection leaves prior caliber unchanged");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
        }
    }

    // ============================================================
    // BE 选口径缓冲冲突拒切 (防混口径堆叠): 缓冲非空且口径不同时拒绝换口径
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchSelectCaliberBufferMismatchRejected(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        // L4: 步枪 (L3) 与霰弹 (L4) 均解锁, 排除等级门干扰, 单验缓冲冲突门。
        IJobService prevJob = swapJob(new FixedLevelJobService(4));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            helper.assertTrue(be.trySelectCaliber(MunitionsCaliber.RIFLE, player), "select RIFLE at L4 accepted");

            // 经 NBT 注入缓冲非空 (50 发步枪): 模拟已产步枪弹未取。
            seedBuffer(be, MunitionsCaliber.RIFLE, 50);

            // 缓冲是步枪且非空, 换霰弹 (虽 L4 已解锁) 被拒 (须先取空缓冲再换), 选中口径仍步枪。
            helper.assertFalse(be.trySelectCaliber(MunitionsCaliber.SHOTGUN, player),
                    "switching caliber with non-empty buffer of a different caliber is rejected (no mixed stacking)");
            helper.assertTrue(be.selectedCaliber() == MunitionsCaliber.RIFLE,
                    "rejected caliber switch leaves RIFLE selected");
            // 同口径 (步枪) 重选恒可 (不视为冲突)。
            helper.assertTrue(be.trySelectCaliber(MunitionsCaliber.RIFLE, player),
                    "re-selecting the SAME buffered caliber is always accepted");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
        }
    }

    // ============================================================
    // BE 离线追算端到端 (五/七/九章): 在线主人一次性补产 -> 扣料 + 入缓冲 + 工费销毁 (按发数) + 谁产谁得经验
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchSettleChargesFeeAndGrantsXp(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService(5); // L5: 步枪直造 40/批。
        IJobService prevJob = swapJob(job);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            helper.assertTrue(be.trySelectCaliber(MunitionsCaliber.RIFLE, player), "select RIFLE at L5");

            // 备料 2 批四件套 -> 应产恰好 80 发 (料瓶颈, 时间/缓冲充裕)。
            stockParts(be, 2);

            // 预存余额够付 80 发工费 (120 CP); 余额充足时先查后扣放行本批。
            ledger.credit(player.getUUID(), Currency.CREDIT, 1000L);

            // 把 lastSettleTick 回拨到远古 (经 NBT 注入), 使本次 settle 的 elapsed 远超需求 -> 离线一次性补产
            // (确定性: 不依赖 GameTest 世界时钟推进, elapsed = now - 远古 >> 任何门槛, 实产受料瓶颈夹到 2 批)。
            backdateSettleTick(be, helper, MunitionsProduction.ticksPerRound(5) * 100_000L);
            be.settleForOwner(player);

            // 实产 80 发入缓冲 (料瓶颈夹到 2 批)。
            helper.assertTrue(be.bufferedRounds() == 80,
                    "online owner catch-up produces exactly 80 rounds (2 batches of 40), got " + be.bufferedRounds());
            // 真扣料: 四件套全部归零 (整批走料)。
            assertPartCounts(helper, be, 0);
            // 工费销毁入账 (sink): 余额按发数扣 120 CP (1000 -> 880)。
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 880L,
                    "work fee sink destroys 120 CP for 80 rounds (1000-120=880), got "
                            + ledger.balance(player.getUUID(), Currency.CREDIT));
            // 谁产谁得: 经框架 grantXp 给在线主人, 原始经验 = 80 发 (按发数)。
            helper.assertTrue(job.grantXpCalls == 1, "grantXp called exactly once for the producing settle");
            helper.assertTrue(job.lastJob == JobId.MUNITIONS, "xp credited to MUNITIONS job");
            helper.assertTrue(job.lastRawXp == 80L,
                    "raw xp granted equals produced rounds (80), got " + job.lastRawXp);
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    /**
     * 已移除的主人 (死亡到重生之间的旧实体) 一律不结算。
     *
     * 2026-08-18 真服连环崩服回归: 台主一死, 旧 ServerPlayer 被标记 KILLED 且 capability 被 invalidate,
     * 但仍留在 PlayerList 里被军火台取到, 结算读职业等级即抛 IllegalStateException 崩掉服务端 tick。
     * 本例断言"已移除即不结算": 备了料、时间也回拨到远古 (不设防必产 80 发), 主人已移除则一发不产、
     * 料不扣、经验不给。删掉 settleForOwner 里的 isRemoved 守卫, 这三条断言全挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchSkipsSettleForRemovedOwner(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService(5);
        IJobService prevJob = swapJob(job);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            helper.assertTrue(be.trySelectCaliber(MunitionsCaliber.RIFLE, player), "select RIFLE at L5");
            stockParts(be, 2);
            ledger.credit(player.getUUID(), Currency.CREDIT, 1000L);
            backdateSettleTick(be, helper, MunitionsProduction.ticksPerRound(5) * 100_000L);

            // 玩家死亡: 旧实体被标记移除 (Forge 随即失效其 capability), 但对象本身仍可被持有与传入。
            player.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
            be.settleForOwner(player);

            helper.assertTrue(be.bufferedRounds() == 0,
                    "已移除的主人不得产出, 实得 " + be.bufferedRounds());
            assertPartCounts(helper, be, 2);
            helper.assertTrue(job.grantXpCalls == 0,
                    "已移除的主人不得入账经验, 实得 " + job.grantXpCalls + " 次");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 1000L,
                    "已移除的主人不得扣工费, 实得 " + ledger.balance(player.getUUID(), Currency.CREDIT));
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchSettleForfeitsBatchWhenBalanceShort(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService(5);
        IJobService prevJob = swapJob(job);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            be.trySelectCaliber(MunitionsCaliber.RIFLE, player);
            stockParts(be, 2);
            // 余额仅 10 CP, 不够 80 发的 120 CP 工费 -> 本批作废 (扣不动则料不扣、缓冲不增、经验不给)。
            ledger.credit(player.getUUID(), Currency.CREDIT, 10L);

            backdateSettleTick(be, helper, MunitionsProduction.ticksPerRound(5) * 100_000L);
            be.settleForOwner(player);

            helper.assertTrue(be.bufferedRounds() == 0,
                    "insufficient balance forfeits the batch: no rounds buffered, got " + be.bufferedRounds());
            assertPartCounts(helper, be, 2);
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 10L,
                    "balance untouched when charge fails (transaction-safe, no partial sink)");
            helper.assertTrue(job.grantXpCalls == 0, "no xp granted when batch forfeited (no production)");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    // ============================================================
    // BE 工费扣不动时保留时间戳, 余额补足后一次性补产整段离线窗口 (munitions-01 回归)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchSettlePreservesWindowWhenFeeFails(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService(5); // L5: 步枪直造 40/批。
        IJobService prevJob = swapJob(job);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            be.trySelectCaliber(MunitionsCaliber.RIFLE, player);
            // 备料 2 批 (14 铜 / 32 火药) -> 跨整段窗口期望产 80 发 (料瓶颈, 时间/缓冲充裕)。
            stockParts(be, 2);

            // 把 lastSettleTick 回拨到远古, 使本次 settle 的 elapsed 远超需求 (整段离线窗口)。记下回拨后的旧时间戳。
            backdateSettleTick(be, helper, MunitionsProduction.ticksPerRound(5) * 100_000L);
            long settleTickBeforeShortBalance = readSettleTick(be);

            // 第一次结算: 余额仅 10 CP, 不够 80 发的 120 CP 工费 -> 工费扣不动, 本批作废。
            ledger.credit(player.getUUID(), Currency.CREDIT, 10L);
            be.settleForOwner(player);

            // munitions-01 核心断言: 工费扣不动时时间戳必须保持旧值 (本段 elapsed 窗口未作废, 留待下次再追);
            // 缓冲为 0、料未扣、余额未动、未给经验。删掉 "扣费成功后才推进时间戳" 修复 -> 时间戳被提前推进到 now,
            // 此断言 (== 旧值) 必挂。
            helper.assertTrue(readSettleTick(be) == settleTickBeforeShortBalance,
                    "fee charge failure must NOT advance lastSettleTick (offline window retained for retry), expected "
                            + settleTickBeforeShortBalance + " got " + readSettleTick(be));
            helper.assertTrue(be.bufferedRounds() == 0,
                    "fee-failed settle buffers nothing, got " + be.bufferedRounds());
            assertPartCounts(helper, be, 2);
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 10L,
                    "balance untouched when fee charge fails");
            helper.assertTrue(job.grantXpCalls == 0, "no xp granted when fee charge fails");

            // 补足余额后第二次结算: 因时间戳被保留, elapsed 仍为整段离线窗口 -> 一次性补产跨整窗的期望 80 发
            // (料瓶颈夹到 2 批), 工费 120 CP 扣成功 (1000+10 -> 890), 经验 80 一次性入主人。
            ledger.credit(player.getUUID(), Currency.CREDIT, 1000L); // 余额 1010 CP, 够付 120。
            be.settleForOwner(player);

            helper.assertTrue(be.bufferedRounds() == 80,
                    "after balance restored, the retained elapsed window is settled in one shot to 80 rounds, got "
                            + be.bufferedRounds());
            assertPartCounts(helper, be, 0);
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 890L,
                    "work fee 120 CP charged once on the retained window (1010-120=890), got "
                            + ledger.balance(player.getUUID(), Currency.CREDIT));
            helper.assertTrue(job.grantXpCalls == 1, "xp granted exactly once on the successful catch-up settle");
            helper.assertTrue(job.lastRawXp == 80L,
                    "catch-up raw xp equals the full-window produced rounds (80), got " + job.lastRawXp);
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    // ============================================================
    // BE 输出槽 Shift 整栈取弹端到端回收缓冲 (munitions-output): 经 Menu.quickMoveStack 模拟 Shift 取整栈,
    // 断言 bufferedRounds 按真实取走发数精确回收 (非据基类传入的移除后残留 EMPTY 栈结算)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchOutputShiftTakeRecyclesBuffer(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        // L5: 步枪解锁 (RIFLE 门 L3), 排除等级门干扰; settle 在无料时不产, 不污染本测的缓冲种子。
        IJobService prevJob = swapJob(new FixedLevelJobService(5));
        try {
            // --- 用例 A: 整栈取走 == 缓冲发数 (取单栈上限 64 内的 48, 保整栈一次性移走) -> bufferedRounds 归零 + bufferedCaliber 清空 ---
            MunitionsBenchBlockEntity beFull = newBench(helper, player);
            MunitionsBenchMenu menuFull = openBenchMenu(beFull, player);

            // 经 NBT 注入缓冲 48 发步枪 (权威发数, 单栈内取整栈); 输出槽物化为等量占位栈 (dev 无 TACZ, 不走真物化路径,
            // 占位 ItemStack 模拟主人在线访问帧 refreshOutputStack 物化出的可视弹栈)。注: 缓冲若超单栈上限(64)需分多次取,
            // 由用例 B 覆盖; 本例验单栈内整取的全额回收。
            seedBuffer(beFull, MunitionsCaliber.RIFLE, 48);
            beFull.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT,
                    new ItemStack(ModMunitionsItems.PRIMER.get(), 48));
            helper.assertTrue(beFull.bufferedRounds() == 48, "seeded buffer is 48 rounds before Shift-take");

            // Shift 取整栈 (走 AbstractMiningMenu.quickMoveStack): 基类把整栈移入玩家背包后, 传给 OutputSlot.onTake
            // 的是移除后残留 EMPTY 栈。修复前据此残留栈结算 -> onOutputTaken 首行 isEmpty 即 return, 缓冲永不回收。
            ItemStack moved = menuFull.quickMoveStack(player, MunitionsBenchBlockEntity.SLOT_OUTPUT);

            helper.assertTrue(moved.getCount() == 48,
                    "Shift moved the entire 48-count output stack into player inventory, got " + moved.getCount());
            helper.assertTrue(beFull.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT).isEmpty(),
                    "output slot is emptied after full Shift-take (no item duplication left behind)");
            // munitions-output 核心断言: 取走整栈 48 发 -> bufferedRounds 精确回收到 0 (让出空间继续产)。
            // 删 "据快照差值算真实取走量" 修复 (退回据基类传入的残留 EMPTY 栈) -> onOutputTaken 据 EMPTY 短路,
            // bufferedRounds 仍为 48, 此断言 (==0) 必挂。
            helper.assertTrue(beFull.bufferedRounds() == 0,
                    "Shift-taking the full stack recycles all 48 buffered rounds to 0 (buffer freed), got "
                            + beFull.bufferedRounds());

            // --- 用例 B: 取走量 < 缓冲发数 (单栈 64 < 缓冲 100) -> 精确减 64, 余 36 (证非 "粗暴归零" 短路) ---
            MunitionsBenchBlockEntity bePartial = newBench(helper, player);
            MunitionsBenchMenu menuPartial = openBenchMenu(bePartial, player);
            seedBuffer(bePartial, MunitionsCaliber.RIFLE, 100);
            bePartial.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT,
                    new ItemStack(ModMunitionsItems.PRIMER.get(), 64));

            ItemStack movedPartial = menuPartial.quickMoveStack(player, MunitionsBenchBlockEntity.SLOT_OUTPUT);

            helper.assertTrue(movedPartial.getCount() == 64,
                    "Shift moved the 64-count visualized stack, got " + movedPartial.getCount());
            // 真实取走量 64 从权威 100 发缓冲精确扣减 -> 余 36 (非 0; 证按真实取走量结算而非整批清零)。
            helper.assertTrue(bePartial.bufferedRounds() == 36,
                    "taking 64 of 100 buffered rounds leaves exactly 36, got " + bePartial.bufferedRounds());
            helper.succeed();
        } finally {
            restoreJob(prevJob);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchSettleNoMaterialNoProduction(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService(5);
        IJobService prevJob = swapJob(job);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            be.trySelectCaliber(MunitionsCaliber.RIFLE, player);
            ledger.credit(player.getUUID(), Currency.CREDIT, 1000L);
            // 空料槽: 即便时间充裕也不产 (料门 0 批)。
            backdateSettleTick(be, helper, MunitionsProduction.ticksPerRound(5) * 100_000L);
            be.settleForOwner(player);

            helper.assertTrue(be.bufferedRounds() == 0, "no material -> no rounds buffered");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 1000L,
                    "no production means no work-fee sink (balance unchanged)");
            helper.assertTrue(job.grantXpCalls == 0, "no production means no xp grant");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void doubleBlockBenchDropsExactlyOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MunitionsBenchBlock bench = (MunitionsBenchBlock) ModMunitionsBlocks.MUNITIONS_BENCH_HIGH.get();
        BlockPos mainPos = helper.absolutePos(new BlockPos(0, 1, 0));
        BlockState mainState = bench.defaultBlockState()
                .setValue(MunitionsBenchBlock.FACING, Direction.NORTH)
                .setValue(MunitionsBenchBlock.PART, MunitionsBenchBlock.Part.MAIN)
                .setValue(MunitionsBenchBlock.ACTIVE, false);
        BlockPos extensionPos = MunitionsBenchBlock.extensionPos(mainPos, mainState);

        placeBenchPair(level, mainPos, extensionPos, mainState);
        level.destroyBlock(extensionPos, true);
        assertBenchRemoved(helper, level, mainPos, extensionPos, "extension-first destruction");
        assertSingleBenchDrop(helper, level, mainPos, extensionPos, "extension-first destruction");
        clearBenchDrops(level, mainPos, extensionPos);

        placeBenchPair(level, mainPos, extensionPos, mainState);
        level.destroyBlock(mainPos, true);
        assertBenchRemoved(helper, level, mainPos, extensionPos, "main-first destruction");
        assertSingleBenchDrop(helper, level, mainPos, extensionPos, "main-first destruction");
        helper.succeed();
    }

    private static void placeBenchPair(ServerLevel level, BlockPos mainPos, BlockPos extensionPos,
                                       BlockState mainState) {
        level.setBlock(mainPos, mainState, Block.UPDATE_CLIENTS);
        level.setBlock(extensionPos,
                mainState.setValue(MunitionsBenchBlock.PART, MunitionsBenchBlock.Part.EXTENSION),
                Block.UPDATE_CLIENTS);
    }

    private static void assertBenchRemoved(GameTestHelper helper, ServerLevel level, BlockPos mainPos,
                                           BlockPos extensionPos, String path) {
        helper.assertTrue(level.getBlockState(mainPos).isAir() && level.getBlockState(extensionPos).isAir(),
                path + " must remove both bench blocks");
    }

    private static void assertSingleBenchDrop(GameTestHelper helper, ServerLevel level, BlockPos mainPos,
                                              BlockPos extensionPos, String path) {
        int count = benchDrops(level, mainPos, extensionPos).stream()
                .mapToInt(entity -> entity.getItem().getCount())
                .sum();
        helper.assertTrue(count == 1, path + " must drop exactly one bench item, got " + count);
    }

    private static void clearBenchDrops(ServerLevel level, BlockPos mainPos, BlockPos extensionPos) {
        benchDrops(level, mainPos, extensionPos).forEach(ItemEntity::discard);
    }

    private static List<ItemEntity> benchDrops(ServerLevel level, BlockPos mainPos, BlockPos extensionPos) {
        AABB bounds = new AABB(mainPos, extensionPos.offset(1, 1, 1)).inflate(2.0D);
        return level.getEntitiesOfClass(ItemEntity.class, bounds,
                entity -> entity.getItem().is(ModMunitionsItems.MUNITIONS_BENCH_HIGH_ITEM.get()));
    }

    // ============================================================
    // 手动制作路径 (双模式; 审查 M-1/M-2/M-3/M-5 回归)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void manualCraftAtomicSettlement(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService(5); // L5: 步枪直造 40/批。
        IJobService prevJob = swapJob(job);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            helper.assertTrue(be.trySelectCaliber(MunitionsCaliber.RIFLE, player), "select RIFLE at L5");
            stockParts(be, 1);
            ledger.credit(player.getUUID(), Currency.CREDIT, 1000L);

            // 开工帧只校验不扣料 (M-2): 开工成功后四件套仍原样在槽。
            helper.assertTrue(be.tryStartCraft(player), "owner starts a manual craft");
            assertPartCounts(helper, be, 1);

            // 取消零损失 (M-2): 材料原样, 缓冲/余额分文不动。
            helper.assertTrue(be.cancelCraft(player), "owner cancels the running craft");
            assertPartCounts(helper, be, 1);
            helper.assertTrue(be.bufferedRounds() == 0, "cancel leaves buffer untouched");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 1000L,
                    "cancel charges nothing");

            // 完成帧原子结算: 重新开工, 回拨 craftingStartTick 使批已到期, settleForOwner 走手动完成分支
            // -> 扣料 + 入缓冲 + 工费 + 经验一次落账 (删 finishActiveCraft 的 consume/charge 任一环此组断言必挂)。
            helper.assertTrue(be.tryStartCraft(player), "restart the craft");
            backdateCraftStart(be, helper);
            be.settleForOwner(player);
            helper.assertTrue(be.bufferedRounds() == 40,
                    "finished manual batch buffers exactly 40 rounds, got " + be.bufferedRounds());
            assertPartCounts(helper, be, 0);
            long expectedBalance = 1000L - MunitionsProduction.workFee(40);
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == expectedBalance,
                    "work fee for 40 rounds charged exactly once on completion, got "
                            + ledger.balance(player.getUUID(), Currency.CREDIT));
            helper.assertTrue(job.lastRawXp == 40L, "raw xp equals produced rounds (40)");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void manualCraftForfeitsWithoutMaterialsButKeepsThem(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService(5);
        IJobService prevJob = swapJob(job);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            be.trySelectCaliber(MunitionsCaliber.RIFLE, player);
            stockParts(be, 1);
            // 余额不足 40 发工费 (60 CP): 完成帧扣费失败 -> 本批作废, 但材料分文不扣 ("扣不动则料不扣")。
            ledger.credit(player.getUUID(), Currency.CREDIT, 10L);
            helper.assertTrue(be.tryStartCraft(player), "start with insufficient balance (charge deferred)");
            backdateCraftStart(be, helper);
            be.settleForOwner(player);
            helper.assertTrue(be.bufferedRounds() == 0, "failed fee forfeits the batch: nothing buffered");
            assertPartCounts(helper, be, 1);
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 10L,
                    "failed charge leaves balance untouched");
            helper.assertFalse(be.saveWithoutMetadata().getBoolean("CraftingActive"),
                    "failed batch stops the machine (no continuous spin)");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void manualCraftOwnerOnly(GameTestHelper helper) {
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer stranger = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService(5);
        IJobService prevJob = swapJob(job);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, owner);
            be.trySelectCaliber(MunitionsCaliber.RIFLE, owner);
            stockParts(be, 1);
            // 未上锁也不放行 (M-3): 开工/连续开关/取消全部限台主, 产量/工费/经验天然同源 owner。
            helper.assertFalse(be.tryStartCraft(stranger), "stranger cannot start on an unlocked bench");
            helper.assertFalse(be.toggleContinuousCrafting(stranger), "stranger cannot toggle continuous mode");
            helper.assertTrue(be.tryStartCraft(owner), "owner starts");
            helper.assertFalse(be.cancelCraft(stranger), "stranger cannot cancel the owner batch");
            helper.assertTrue(be.cancelCraft(owner), "owner cancels");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchTierClampsEffectiveLevel(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService(6); // L6: 狙击解锁线。
        IJobService prevJob = swapJob(job);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            // MEDIUM 台 maxEffectiveLevel=4: L6 玩家被钳到 L4, SNIPER (L6 门) 拒选; RIFLE (L3 门) 放行。
            // 删 effectiveLevelFor 的 Math.min 钳制, SNIPER 断言必挂。
            MunitionsBenchBlockEntity medium =
                    newBench(helper, player, ModMunitionsBlocks.MUNITIONS_BENCH_MEDIUM.get());
            helper.assertFalse(medium.trySelectCaliber(MunitionsCaliber.SNIPER, player),
                    "medium bench clamps L6 owner to L4: sniper rejected");
            helper.assertTrue(medium.trySelectCaliber(MunitionsCaliber.RIFLE, player),
                    "rifle (L3 gate) still selectable under the clamp");
            // 旧注册名恢复全档 (M-5 存量兼容): 同一 L6 玩家在旧台 SNIPER 放行 (回归降档必挂)。
            MunitionsBenchBlockEntity legacy =
                    newBench(helper, player, ModMunitionsBlocks.MUNITIONS_BENCH.get());
            helper.assertTrue(legacy.trySelectCaliber(MunitionsCaliber.SNIPER, player),
                    "legacy munitions_bench keeps full capability for existing benches");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void quickMoveNeverMergesIntoOutputSlot(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService(5);
        IJobService prevJob = swapJob(job);
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            MunitionsBenchMenu menu = openBenchMenu(be, player);
            // 构造 M-7 场景 (注入放在开 menu 之后, 防 onAccess 的输出刷新覆盖注入): 输出槽注入与玩家手中
            // 同种的物品 (测试注入绕过 mayPlace), 料槽占满 -> vanilla moveItemStackTo 的合并分支若目标区间含
            // 输出槽, 会把玩家的 8 个并进输出槽 (随后被 refreshOutputStack 覆盖销毁)。修复后目标区间止步
            // 输出槽, 输出槽数量必须保持 8。
            be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT,
                    new ItemStack(ModMunitionsItems.PRIMER.get(), 8));
            be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER,
                    new ItemStack(ModMunitionsItems.PRIMER.get(), 64));
            player.getInventory().setItem(0, new ItemStack(ModMunitionsItems.PRIMER.get(), 8));
            int playerSlotIndex = -1;
            for (int i = 5; i < menu.slots.size(); i++) {
                if (menu.slots.get(i).getItem().is(ModMunitionsItems.PRIMER.get())) {
                    playerSlotIndex = i;
                    break;
                }
            }
            helper.assertTrue(playerSlotIndex >= 0, "player primer stack visible in menu");
            menu.quickMoveStack(player, playerSlotIndex);
            helper.assertTrue(be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT)
                            .getCount() == 8,
                    "shift-clicked primers must NOT merge into the output slot (kept 8), got "
                            + be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT).getCount());
            helper.succeed();
        } finally {
            restoreJob(prevJob);
        }
    }

    // ============================================================
    // Full_Repo_Audit_2026-08 补测: F001 输出槽分栈/写入不变量, F009 台数按台主回收, F010 选口径限台主,
    // F015 4->5 槽迁移, F049 被动产线驱动 ACTIVE, F051 反漏斗只写
    // ============================================================

    /**
     * F001 (Critical): 输出槽必须按 TACZ 弹自己的单栈上限分栈, 不能把 480 发权威缓冲整坨怼进一个栈。
     * dev 无 TACZ, 默认 materializer 恒返 EMPTY, 结构上测不到这条 —— 注入返回原版物品 (真实 getMaxStackSize)
     * 的替身撬开这条盲区 (compileOnly 铁律不破: 替身只返回原版 ItemStack, 不触 com.tacz.*)。
     *
     * 删 refreshOutputStack 里的 Math.min 分栈 -> 输出槽 count 会变成 480, 第一条 ==64 的断言必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchOutputStackSplitsToItemMaxThenRefillsAfterPartialTake(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MunitionsBenchBlockEntity be = newBench(helper, player);
        try {
            MunitionsAmmoFactory.registerMaterializer((caliber, count) ->
                    new ItemStack(ModMunitionsItems.PRIMER.get(), count));

            seedBuffer(be, MunitionsCaliber.RIFLE, 480);
            be.onAccess(player);

            ItemStack output = be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT);
            helper.assertTrue(output.getCount() == 64,
                    "output slot splits to the item's own stack limit (64), not the full 480 buffered, got "
                            + output.getCount());
            helper.assertTrue(output.is(ModMunitionsItems.PRIMER.get()), "output slot holds the materialized item");
            helper.assertTrue(be.bufferedRounds() == 480,
                    "authoritative bufferedRounds is untouched by the visual split, got " + be.bufferedRounds());

            MunitionsBenchMenu menu = openBenchMenu(be, player);
            ItemStack moved = menu.quickMoveStack(player, MunitionsBenchBlockEntity.SLOT_OUTPUT);

            helper.assertTrue(moved.getCount() == 64,
                    "shift-take moved the full 64-count visualized stack, got " + moved.getCount());
            helper.assertTrue(be.bufferedRounds() == 416,
                    "taking 64 of 480 buffered rounds leaves exactly 416, got " + be.bufferedRounds());
            helper.assertTrue(be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT).getCount() == 64,
                    "output slot is refilled to the item max (64) out of the remaining 416-round buffer, got "
                            + be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT).getCount());
            helper.succeed();
        } finally {
            MunitionsAmmoFactory.resetMaterializer();
        }
    }

    /**
     * 复核 (blocker, 取代原 benchLegacyMigrationRejectsUnknownSlotCountAndOversizedOutputWrite): 旧版对"未知
     * 槽数"与"输出槽超栈"两种损坏都直接在 load() 里 throw, 而 BlockEntity.loadStatic 会把 load() 抛出的
     * Throwable 吞掉并丢弃整台 BE (forge-1.20.1-47.4.20 sources 核实: catch Throwable 后 return null, 之后
     * getBlockEntity(CHECK) 不会补建) —— owner/缓冲/四格料全部消失, 比它原本要防的"抹平库存"更狠, 且旧版
     * 测试只验证了"抛了", 没验证"抛完这台还在不在", 把这个回归锁成了契约。改为两种损坏都不再抛: 未知槽数把
     * 原内容进待掉落队列 + 重置库存形状; 超栈输出槽按物品自报上限落一栈、余量进待掉落队列。
     *
     * 断言链: (1) load() 不抛; (2) owner 在两种损坏后都还在 (证明 BE 没被 Forge 整体丢弃); (3) 未知槽数场景
     * 库存重置为空的当前形状, 原有的 1 个铁锭堆叠经首个 serverTick 精确吐成掉落物; (4) 超栈场景输出槽被钳到
     * 底火单栈上限 64, 溢出的 36 发经首个 serverTick 精确吐成掉落物。
     *
     * 删 migrateInventoryShape 的未知槽数分支或 settleLegacyOutputStack 的钳位逻辑 (退回 throw / 不钳直接落槽)
     * -> 对应断言必挂 (要么 load() 重新抛出, 要么输出槽出现超过 64 的堆叠)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchLegacyMigrationDegradesInsteadOfDiscardingTheWholeBlockEntity(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        // 未知槽数 (3, 既非当前 5 槽也非旧档 4 槽) 必须保留 BE (owner 还在), 原内容进待掉落队列而不是抛异常丢整台。
        MunitionsBenchBlockEntity beUnknownSize = newBench(helper, player);
        UUID unknownSizeOwner = beUnknownSize.owner();
        ItemStackHandler unknownSizeFixture = new ItemStackHandler(3);
        unknownSizeFixture.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 5));
        net.minecraft.nbt.CompoundTag unknownSizeTag = beUnknownSize.saveWithoutMetadata();
        unknownSizeTag.put("Inv", unknownSizeFixture.serializeNBT());
        beUnknownSize.load(unknownSizeTag); // 必须不抛。
        helper.assertTrue(unknownSizeOwner != null && unknownSizeOwner.equals(beUnknownSize.owner()),
                "unknown slot count must not wipe the owner (BE must survive, not get discarded by loadStatic)");
        helper.assertTrue(
                beUnknownSize.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER).isEmpty()
                        && beUnknownSize.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_CASING).isEmpty()
                        && beUnknownSize.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_BULLET_HEAD)
                                .isEmpty()
                        && beUnknownSize.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PROPELLANT)
                                .isEmpty()
                        && beUnknownSize.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT).isEmpty(),
                "inventory shape must be reset to empty current-layout slots after an unknown slot count");
        beUnknownSize.serverTick();
        AABB unknownSizeDropBox = new AABB(beUnknownSize.getBlockPos()).inflate(2.0D);
        List<ItemEntity> unknownSizeDrops =
                helper.getLevel().getEntitiesOfClass(ItemEntity.class, unknownSizeDropBox);
        helper.assertTrue(unknownSizeDrops.size() == 1 && unknownSizeDrops.get(0).getItem().getCount() == 5
                        && unknownSizeDrops.get(0).getItem().is(Items.IRON_INGOT),
                "the unknown-shape inventory's only stack (5 iron ingots) must be queued for drop, not vanished");
        // newBench 固定用 (0,1,0), 第二段场景的方块会摆在同一个绝对坐标: 先把这批掉落物清场, 不然第二段的
        // dropBox 查询 (同一位置) 会把这批铁锭也算进去, 误判超栈溢出份数。
        unknownSizeDrops.forEach(net.minecraft.world.entity.Entity::discard);

        // 旧档输出槽超栈 (100 > 底火单栈上限 64) 必须按上限落槽 + 余量排队掉落, 不能在 load() 里抛出丢整台。
        MunitionsBenchBlockEntity beOversizedOutput = newBench(helper, player);
        UUID oversizedOutputOwner = beOversizedOutput.owner();
        ItemStackHandler oversizedOutputFixture = new ItemStackHandler(4);
        oversizedOutputFixture.setStackInSlot(3, new ItemStack(ModMunitionsItems.PRIMER.get(), 100));
        net.minecraft.nbt.CompoundTag oversizedOutputTag = beOversizedOutput.saveWithoutMetadata();
        oversizedOutputTag.put("Inv", oversizedOutputFixture.serializeNBT());
        beOversizedOutput.load(oversizedOutputTag); // 必须不抛。
        helper.assertTrue(oversizedOutputOwner != null && oversizedOutputOwner.equals(beOversizedOutput.owner()),
                "an oversized legacy output stack must not wipe the owner (BE must survive)");
        ItemStack settledOutput =
                beOversizedOutput.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT);
        helper.assertTrue(settledOutput.is(ModMunitionsItems.PRIMER.get()) && settledOutput.getCount() == 64,
                "legacy output is clamped to the item's own stack limit (64), got " + settledOutput);
        beOversizedOutput.serverTick();
        AABB oversizedOutputDropBox = new AABB(beOversizedOutput.getBlockPos()).inflate(2.0D);
        List<ItemEntity> oversizedOutputDrops =
                helper.getLevel().getEntitiesOfClass(ItemEntity.class, oversizedOutputDropBox);
        int overflowTotal = 0;
        for (ItemEntity entity : oversizedOutputDrops) {
            helper.assertTrue(entity.getItem().is(ModMunitionsItems.PRIMER.get()),
                    "unexpected overflow drop item " + entity.getItem());
            overflowTotal += entity.getItem().getCount();
        }
        helper.assertTrue(overflowTotal == 36,
                "the 36-round overflow (100 - 64 clamped) must be queued for drop, got " + overflowTotal);
        helper.succeed();
    }

    /**
     * F015 (4->5 槽迁移): 旧档发射药/输出原样搬到新槽位, 类型未知的 legacy 0/1 进待掉落队列而不是被静默销毁,
     * 首个 serverTick 把队列精确吐成两件世界掉落物 (逐件核对物品与数量, 不只数个数)。
     *
     * 删掉待掉落队列 (migrateLegacyFourSlot 里 pendingLegacyDrops.add 那两处) -> 掉落物断言 (==2) 必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchLegacyFourSlotMigrationRestoresPropellantAndOutputThenDropsTheRest(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MunitionsBenchBlockEntity be = newBench(helper, player);

        ItemStackHandler legacy = new ItemStackHandler(4);
        legacy.setStackInSlot(0, new ItemStack(Items.COPPER_INGOT, 7));
        legacy.setStackInSlot(1, new ItemStack(Items.GUNPOWDER, 16));
        legacy.setStackInSlot(2, new ItemStack(ModMunitionsItems.PROPELLANT.get(), 5));
        legacy.setStackInSlot(3, new ItemStack(ModMunitionsItems.PRIMER.get(), 3));
        net.minecraft.nbt.CompoundTag tag = be.saveWithoutMetadata();
        tag.put("Inv", legacy.serializeNBT());
        be.load(tag);

        ItemStack propellant = be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PROPELLANT);
        helper.assertTrue(propellant.is(ModMunitionsItems.PROPELLANT.get()) && propellant.getCount() == 5,
                "legacy slot 2 (propellant) migrates to the new SLOT_PROPELLANT unchanged (5), got " + propellant);
        ItemStack output = be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT);
        helper.assertTrue(output.is(ModMunitionsItems.PRIMER.get()) && output.getCount() == 3,
                "legacy slot 3 (old output) migrates to the new SLOT_OUTPUT unchanged (3), got " + output);
        helper.assertTrue(be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER).isEmpty()
                        && be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_CASING).isEmpty()
                        && be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_BULLET_HEAD).isEmpty(),
                "type-unknown legacy slots 0/1 (copper/gunpowder) must NOT leak into any new input slot");

        be.serverTick(); // 冲掉待掉落队列。

        AABB dropBox = new AABB(be.getBlockPos()).inflate(2.0D);
        List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, dropBox);
        helper.assertTrue(drops.size() == 2,
                "exactly 2 legacy stacks (copper ingot + gunpowder) are queued for drop, got " + drops.size());
        int copperCount = 0;
        int gunpowderCount = 0;
        for (ItemEntity entity : drops) {
            ItemStack stack = entity.getItem();
            if (stack.is(Items.COPPER_INGOT)) {
                copperCount += stack.getCount();
            } else if (stack.is(Items.GUNPOWDER)) {
                gunpowderCount += stack.getCount();
            } else {
                helper.fail("unexpected legacy drop item " + stack);
            }
        }
        helper.assertTrue(copperCount == 7, "dropped copper ingots must total exactly 7, got " + copperCount);
        helper.assertTrue(gunpowderCount == 16, "dropped gunpowder must total exactly 16, got " + gunpowderCount);
        helper.succeed();
    }

    /**
     * F049: 被动产线 (非手动开工) 也必须驱动方块 ACTIVE 状态。备 2 批料, 只回拨 1 批的时间 (时间瓶颈), 结算后
     * 恰产 1 批、还剩 1 批料且缓冲有空间 -> 机器仍要亮灯。清空四个料槽再结算一次 -> 无料可产, 机器必须灭灯。
     *
     * 删 canAccumulateProduction 的新判据 (退回旧的直接复用 craftingActive) -> 被动产线从不置位
     * craftingActive, canAccumulateProduction 恒 false, 第一条 ACTIVE==true 断言必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void passiveProductionDrivesActiveBlockState(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        IJobService prevJob = swapJob(new FixedLevelJobService(5));
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            be.trySelectCaliber(MunitionsCaliber.RIFLE, player);
            stockParts(be, 2);
            ledger.credit(player.getUUID(), Currency.CREDIT, 1000L);

            int perBatch = MunitionsProduction.roundsPerBatch(MunitionsCaliber.RIFLE, 5);
            long oneBatchTime = MunitionsProduction.ticksPerRound(5) * (long) perBatch;
            backdateSettleTick(be, helper, oneBatchTime);
            be.settleForOwner(player);

            helper.assertTrue(be.bufferedRounds() == perBatch,
                    "sanity: exactly one batch produced (time-clamped), got " + be.bufferedRounds());
            assertPartCounts(helper, be, 1);
            BlockPos benchPos = be.getBlockPos();
            helper.assertTrue(helper.getLevel().getBlockState(benchPos).getValue(MunitionsBenchBlock.ACTIVE),
                    "bench with remaining material and buffer room must be ACTIVE after a productive settle");

            be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER, ItemStack.EMPTY);
            be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_CASING, ItemStack.EMPTY);
            be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_BULLET_HEAD, ItemStack.EMPTY);
            be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_PROPELLANT, ItemStack.EMPTY);
            backdateSettleTick(be, helper, 1L);
            be.settleForOwner(player);
            helper.assertFalse(helper.getLevel().getBlockState(benchPos).getValue(MunitionsBenchBlock.ACTIVE),
                    "bench with no material left must go inactive (ACTIVE=false) after settling");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    /**
     * 复核 (major, F049 同源): 台主持续在线时 serverTick 每 tick 都追算一次, elapsed 恒为 1 tick, 单 tick 换算
     * 的理论发数不够一整批。旧判据 (canAccumulateProduction 的料/缓冲/口径三道静态门) 在这种"料齐但流逝时间
     * 不够" 的状态下恒真, 会把机器点成常亮却一发都出不来。本用例只回拨 1 tick (远不够一整批), 断言这个真实
     * 的"在线空转"状态下 ACTIVE 必须是 false、且确实没有任何产出。
     *
     * 退回旧判据 (canAccumulateProduction 而非 result.produced() 驱动 active) -> ACTIVE 断言必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void passiveProductionStaysInactiveWhenElapsedTimeIsTooShortForABatch(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        IJobService prevJob = swapJob(new FixedLevelJobService(5));
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            be.trySelectCaliber(MunitionsCaliber.RIFLE, player);
            stockParts(be, 2);
            ledger.credit(player.getUUID(), Currency.CREDIT, 1000L);

            // 料/缓冲/口径三道静态门全过, 但只给 1 tick 流逝 (远不够一整批所需的上万 tick) —— 这正是台主持续
            // 在线时 serverTick 每 tick 都会遇到的真实状态。
            backdateSettleTick(be, helper, 1L);
            be.settleForOwner(player);

            helper.assertTrue(be.bufferedRounds() == 0,
                    "sanity: 1 elapsed tick cannot complete a batch, got " + be.bufferedRounds());
            assertPartCounts(helper, be, 2);
            BlockPos benchPos = be.getBlockPos();
            helper.assertFalse(helper.getLevel().getBlockState(benchPos).getValue(MunitionsBenchBlock.ACTIVE),
                    "bench with materials ready but insufficient elapsed time must stay inactive, "
                            + "not falsely light up as if producing");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    /**
     * 复核 (major, F049 同源): 扣费失败分支曾经每次都 {@code nextWeldSoundTick = 0L; setMachineActive(false);},
     * 而工费失败不推进 lastSettleTick (先查后扣, 扣不动则本批作废但保留流逝窗口), 于是台主持续在线、始终缺钱时,
     * 每 tick 都会重演 "重新点亮 -> 立刻拉黑" 的循环, 音效节流被清零后每 tick 都重新达标, 造成音效轰炸 + 方块
     * 更新包风暴。本用例模拟连续两次 "料/缓冲/时间都够, 唯独差信用点" 的结算, 断言 ACTIVE 在两次失败结算之间
     * 保持不变 (不闪烁), 且材料/缓冲分文不动 (扣不动则本批真的作废); 随后补上信用点, 断言产出最终按
     * "下次再追" 的契约正常完成, 证明这不是简单粗暴地让失败分支永久生效, 而是保留了正确的重试语义。
     *
     * 删掉本轮修复 (在扣费失败分支重新加回 nextWeldSoundTick=0L / setMachineActive(false)) -> 第二次结算后的
     * ACTIVE 稳定性断言必挂 (会先被拉黑, 与第一次的 ACTIVE=true 矛盾, 断言即为"两次读到的状态相同"因而失败)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void passiveProductionKeepsActiveStableAcrossRepeatedFeeFailures(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        IJobService prevJob = swapJob(new FixedLevelJobService(5));
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger)); // 玩家账户 0 CP, 工费必扣不动。
        try {
            MunitionsBenchBlockEntity be = newBench(helper, player);
            be.trySelectCaliber(MunitionsCaliber.RIFLE, player);
            stockParts(be, 3);

            int perBatch = MunitionsProduction.roundsPerBatch(MunitionsCaliber.RIFLE, 5);
            long oneBatchTime = MunitionsProduction.ticksPerRound(5) * (long) perBatch;
            BlockPos benchPos = be.getBlockPos();

            backdateSettleTick(be, helper, oneBatchTime);
            be.settleForOwner(player);
            helper.assertTrue(be.bufferedRounds() == 0,
                    "sanity: no CP means the fee charge fails, nothing produced yet, got " + be.bufferedRounds());
            assertPartCounts(helper, be, 3);
            boolean activeAfterFirstFailure =
                    helper.getLevel().getBlockState(benchPos).getValue(MunitionsBenchBlock.ACTIVE);
            helper.assertTrue(activeAfterFirstFailure,
                    "a settle that is only blocked on the CP fee (material/buffer/time all satisfied) must stay lit");

            // 再来一次"缺钱失败", 模拟台主持续在线时下一 tick 重演同样的判定 (材料/缓冲/时间照常满足)。
            backdateSettleTick(be, helper, oneBatchTime);
            be.settleForOwner(player);
            helper.assertTrue(be.bufferedRounds() == 0, "still no CP: still nothing produced");
            assertPartCounts(helper, be, 3);
            boolean activeAfterSecondFailure =
                    helper.getLevel().getBlockState(benchPos).getValue(MunitionsBenchBlock.ACTIVE);
            helper.assertTrue(activeAfterSecondFailure == activeAfterFirstFailure,
                    "ACTIVE must not flicker between repeated fee failures (was " + activeAfterFirstFailure
                            + ", now " + activeAfterSecondFailure + ")");

            // 补上信用点, 证明 "扣不动则本批作废, 下次再追" 的契约仍然成立 —— 不是把失败分支焊死成永久生效。
            ledger.credit(player.getUUID(), Currency.CREDIT, 1000L);
            backdateSettleTick(be, helper, oneBatchTime);
            be.settleForOwner(player);
            helper.assertTrue(be.bufferedRounds() == perBatch,
                    "once CP is available, the retained production window must still complete, got "
                            + be.bufferedRounds());
            assertPartCounts(helper, be, 2);
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    /**
     * F051: getCapability(ITEM_HANDLER) 只暴露反漏斗包装 —— 漏斗那种只走 extractItem/insertItem 的调用方,
     * extract 恒空 (反抽不走), insert 照常成功 (塞料不受影响)。
     *
     * 删 InsertOnlyRangedWrapper 对 extractItem 的覆写 (RangedWrapper 恢复默认双向代理) -> extractItem 会真的
     * 抽走底火, 第一条 isEmpty() 断言必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void inputCapabilityIsInsertOnlyAgainstHoppers(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MunitionsBenchBlockEntity be = newBench(helper, player);
        be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER,
                new ItemStack(ModMunitionsItems.PRIMER.get(), 8));

        IItemHandler h = be.getCapability(ForgeCapabilities.ITEM_HANDLER, null)
                .orElseThrow(() -> new IllegalStateException("munitions bench must expose an ITEM_HANDLER capability"));

        ItemStack extracted = h.extractItem(0, 64, false);
        helper.assertTrue(extracted.isEmpty(),
                "hopper-side extractItem must be blocked (anti-hopper), got " + extracted.getCount() + " extracted");
        helper.assertTrue(be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER).getCount() == 8,
                "primer count must be untouched by the blocked extraction, got "
                        + be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER).getCount());

        ItemStack insertLeftover = h.insertItem(0, new ItemStack(ModMunitionsItems.PRIMER.get(), 4), false);
        helper.assertTrue(insertLeftover.isEmpty(),
                "hopper-side insertItem into the primer slot must succeed, leftover=" + insertLeftover.getCount());
        helper.assertTrue(be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER).getCount() == 12,
                "primer count after inserting 4 more must be 12, got "
                        + be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER).getCount());
        helper.succeed();
    }

    /**
     * F010: 选口径服务端权威归属门 —— 路人不能改台主的选中口径, 也不能借这一次点击把台主攒下的离线时间窗抹掉
     * (trySelectCaliber 接受时会把 lastSettleTick 推到 now, 拒绝路径绝不能有这个副作用)。
     *
     * 删 trySelectCaliber 里的 isOwner 门 -> 路人的 PISTOL 请求会被接受, 时间戳被推进到 now、selectedCaliber
     * 被改成 PISTOL, 前两条断言必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void trySelectCaliberRejectsNonOwnerAndPreservesSettleWindow(GameTestHelper helper) {
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer stranger = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        IJobService prevJob = swapJob(new FixedLevelJobService(5));
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEco = swapEconomy(freshEconomy(ledger));
        try {
            MunitionsBenchBlockEntity be = newBench(helper, owner);
            helper.assertTrue(be.trySelectCaliber(MunitionsCaliber.RIFLE, owner), "owner selects RIFLE at L5");

            int perBatch = MunitionsProduction.roundsPerBatch(MunitionsCaliber.RIFLE, 5);
            long window = MunitionsProduction.ticksPerRound(5) * (long) perBatch; // 恰好一批的流逝量。
            backdateSettleTick(be, helper, window);
            long settleTickAfterBackdate = readSettleTick(be);

            helper.assertFalse(be.trySelectCaliber(MunitionsCaliber.PISTOL, stranger),
                    "a stranger cannot switch caliber on someone else's bench");
            helper.assertTrue(readSettleTick(be) == settleTickAfterBackdate,
                    "rejected stranger selection must NOT touch lastSettleTick (owner's offline window preserved), "
                            + "expected " + settleTickAfterBackdate + " got " + readSettleTick(be));
            helper.assertTrue(be.selectedCaliber() == MunitionsCaliber.RIFLE,
                    "rejected stranger selection leaves RIFLE selected (not PISTOL, not null)");

            // 台主自己结算一次: 证明整段回拨窗口确实还在, 没被路人那次被拒的调用抹掉。
            stockParts(be, 1);
            ledger.credit(owner.getUUID(), Currency.CREDIT, 1000L);
            be.settleForOwner(owner);
            helper.assertTrue(be.bufferedRounds() == perBatch,
                    "owner settle after the rejected stranger call still catches up the full retained window ("
                            + perBatch + " rounds), got " + be.bufferedRounds());
            helper.succeed();
        } finally {
            restoreJob(prevJob);
            restoreEconomy(prevEco);
        }
    }

    /**
     * F009: 台数计数的回收必须按台主 (BE 持有的 owner), 而不是破坏者。用 level.destroyBlock (不带玩家上下文)
     * 覆盖爆炸/指令这类没有 PlayerInteractEvent/BreakEvent 的破坏路径。
     *
     * 删 MunitionsBenchBlock.onRemove 里的回收逻辑 -> owner 的计数在破坏后仍是 1, 第一条 ==0 断言必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchRemovalRecoversCountForOwnerNotBreaker(GameTestHelper helper) {
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer breaker = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MunitionsSavedData savedData = MunitionsSavedData.get(owner.server.overworld());
        try {
            helper.assertTrue(savedData.increment(owner.getUUID()) == 1, "seed: owner placed 1 bench");

            MunitionsBenchBlockEntity be = newBench(helper, owner);
            BlockPos benchPos = be.getBlockPos();
            helper.getLevel().destroyBlock(benchPos, false);

            helper.assertTrue(savedData.benchCount(owner.getUUID()) == 0,
                    "destroying the bench through a no-player path (explosion/command) still recovers the owner's "
                            + "count to 0, got " + savedData.benchCount(owner.getUUID()));
            helper.assertTrue(savedData.benchCount(breaker.getUUID()) == 0,
                    "the breaker is not the owner and placed nothing, so their count stays 0, got "
                            + savedData.benchCount(breaker.getUUID()));
            helper.succeed();
        } finally {
            // decrement 天然钳零 (见 MunitionsSavedData), 无论断言前半程 onRemove 是否已经归零, 这里都安全地
            // 把种子计数收干净, 不给同批次其它用例留下污染的 SavedData。
            savedData.decrement(owner.getUUID());
        }
    }

    /**
     * 复核 (blocker, F009 同源): 撞放置上限被 EntityMultiPlaceEvent 取消后, ForgeHooks.onPlaceItemIntoWorld
     * 会把已捕获的两半快照 restore 回 AIR (level.restoringBlockSnapshots=true 期间), 触发的正是
     * MunitionsBenchBlock.onRemove 同一条路径 —— 但这次放置从未 increment 过 (event 在 increment 之前就
     * setCanceled)。若 onRemove 照常 decrement, 每撞一次上限就能白送自己一格额度, 可无限刷台。
     *
     * 用 restoringBlockSnapshots 直接驱动 (对齐 vanilla Block.popResource/popExperience 同一套跳过纪律的验证
     * 手法), 不必手搭一整条 BlockItem.place 流水线: 先用真实 increment 记一台已放置的台, 再在
     * restoringBlockSnapshots=true 期间移除它 (模拟快照回滚), 断言计数不受影响; 随后在正常状态下 (标志位为
     * false) 移除同一台, 断言计数照常回收, 证明新增的判据只挡快照回滚这一种场景, 不影响真实破坏路径。
     *
     * 删 onRemove 里新加的 {@code !level.restoringBlockSnapshots} 判据 -> 第一条 (回滚期不扣) 断言必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchRemovalDuringSnapshotRestoreDoesNotRecoverCount(GameTestHelper helper) {
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MunitionsSavedData savedData = MunitionsSavedData.get(owner.server.overworld());
        try {
            helper.assertTrue(savedData.increment(owner.getUUID()) == 1,
                    "seed: owner has 1 genuinely counted placement (mirrors a real MunitionsSystem.onBenchPlace)");

            MunitionsBenchBlockEntity be = newBench(helper, owner);
            BlockPos benchPos = be.getBlockPos();
            net.minecraft.world.level.Level level = helper.getLevel();

            // 模拟 ForgeHooks.onPlaceItemIntoWorld 取消放置后回滚快照的那个窗口: 此时的 setBlock(AIR) 与真实
            // 破坏走的是同一个 LevelChunk.setBlockState -> oldState.onRemove 路径。
            level.restoringBlockSnapshots = true;
            level.setBlock(benchPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            level.restoringBlockSnapshots = false;

            helper.assertTrue(savedData.benchCount(owner.getUUID()) == 1,
                    "a block update during snapshot restore (cancelled placement rollback) must NOT decrement "
                            + "a count that was never incremented for it, got "
                            + savedData.benchCount(owner.getUUID()));

            // 正常破坏路径不受影响: 上面的 restore 已把该位置清成 AIR, 在同一相对坐标重新放一台 (newBench 固定
            // 用 (0,1,0)), 在标志位为 false 时正常移除, 计数照常回收到 0 —— 证明新判据只挡快照回滚这一种场景。
            newBench(helper, owner);
            helper.getLevel().destroyBlock(benchPos, false);
            helper.assertTrue(savedData.benchCount(owner.getUUID()) == 0,
                    "a genuine destroy outside snapshot-restore still recovers the count normally, got "
                            + savedData.benchCount(owner.getUUID()));
            helper.succeed();
        } finally {
            savedData.decrement(owner.getUUID());
        }
    }

    // ---- 测试辅助 ----

    /**
     * 把开工中的批次 craftingStartTick 经 NBT 注入回拨到远古, 使下一次 settleForOwner 判定本批已到期
     * (与 backdateSettleTick 同一确定性手段; 开工状态 CraftingActive/CraftingCaliber 随 NBT 往返原样保留)。
     */
    private static void backdateCraftStart(MunitionsBenchBlockEntity be, GameTestHelper helper) {
        net.minecraft.nbt.CompoundTag tag = be.saveWithoutMetadata();
        tag.putLong("CraftingStartTick", helper.getLevel().getGameTime() - 10_000_000L);
        be.load(tag);
    }

    /** 在 helper 世界 (0,1,0) 放一个军火台 BE, 设主人为 player, 锚定首帧时间戳并返回。 */
    private static MunitionsBenchBlockEntity newBench(GameTestHelper helper, ServerPlayer owner) {
        return newBench(helper, owner, ModMunitionsBlocks.MUNITIONS_BENCH_HIGH.get());
    }

    private static MunitionsBenchBlockEntity newBench(GameTestHelper helper, ServerPlayer owner,
                                                      net.minecraft.world.level.block.Block block) {
        BlockPos rel = new BlockPos(0, 1, 0);
        helper.setBlock(rel, block);
        BlockPos abs = helper.absolutePos(rel);
        net.minecraft.world.level.block.entity.BlockEntity raw = helper.getLevel().getBlockEntity(abs);
        if (!(raw instanceof MunitionsBenchBlockEntity be)) {
            throw new IllegalStateException("munitions bench BE not present at " + abs);
        }
        be.setOwner(owner.getUUID());
        // 军械台现在吃电: 除电力门本身的用例外, 其余用例关注的是料/时间/缓冲/权限等维度,
        // 故这里默认把内部缓冲充满, 免得每个既有用例都要重复一遍充电样板。
        be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
                .ifPresent(storage -> storage.receiveEnergy(Integer.MAX_VALUE, false));
        return be;
    }

    /**
     * 在 owner 上为给定军火台 BE 打开一个真 {@link MunitionsBenchMenu} (经其构造器从 level@pos 解析回该 BE),
     * 供 quickMoveStack 端到端走基类 Shift 移物路径 (munitions-output 输出槽回收缓冲断言)。窗口 id 任意 (1)。
     */
    private static MunitionsBenchMenu openBenchMenu(MunitionsBenchBlockEntity be, ServerPlayer owner) {
        return new MunitionsBenchMenu(1, owner.getInventory(), be.getBlockPos());
    }

    /**
     * 把 BE 的 lastSettleTick 经 NBT 注入回拨到 (当前 gameTime - ticksAgo), 使下一次 settleForOwner 的 elapsed
     * 恰为 ticksAgo。GameTest 世界主时钟不可在单测内直控, 故用 BlockEntity 持久化往返注入时间戳是唯一确定性手段:
     * saveWithoutMetadata() 取全状态 (含 owner/选中口径/料槽), 仅改 LastSettleTick 再 load 回, 其余状态原样保留。
     */
    private static void backdateSettleTick(MunitionsBenchBlockEntity be, GameTestHelper helper, long ticksAgo) {
        long now = helper.getLevel().getGameTime();
        net.minecraft.nbt.CompoundTag tag = be.saveWithoutMetadata();
        tag.putLong("LastSettleTick", now - ticksAgo);
        // 注入 "已锚定首帧" 标志, 使下一次 settle 直接按回拨流逝量补产, 不被首帧初始化门吞掉 (与 ticker 是否已跑过解耦,
        // 确定性: 测试不依赖 BE 在测试体执行前是否恰好 tick 过一次)。
        tag.putBoolean("SettleInitialized", true);
        be.load(tag);
    }

    /** 备料 batches 批: 每槽塞 batches x 单批 cost (对 config 默认 cost 变更鲁棒, propellantCost 默认 2)。 */
    private static void stockParts(MunitionsBenchBlockEntity be, int batches) {
        be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER,
                new ItemStack(ModMunitionsItems.PRIMER.get(), batches * MunitionsConfig.RECIPE_PRIMER_COST.get()));
        be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_CASING,
                new ItemStack(ModMunitionsItems.CASING.get(), batches * MunitionsConfig.RECIPE_CASING_COST.get()));
        be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_BULLET_HEAD,
                new ItemStack(ModMunitionsItems.BULLET_HEAD.get(),
                        batches * MunitionsConfig.RECIPE_BULLET_HEAD_COST.get()));
        be.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_PROPELLANT,
                new ItemStack(ModMunitionsItems.PROPELLANT.get(),
                        batches * MunitionsConfig.RECIPE_PROPELLANT_COST.get()));
    }

    /** 断言各槽剩余恰为 expectedBatches 批的备料量 (0 = 整批走料全消耗)。 */
    private static void assertPartCounts(GameTestHelper helper, MunitionsBenchBlockEntity be, int expectedBatches) {
        helper.assertTrue(be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER).getCount()
                        == expectedBatches * MunitionsConfig.RECIPE_PRIMER_COST.get(),
                "primer count expected " + expectedBatches + " batches");
        helper.assertTrue(be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_CASING).getCount()
                        == expectedBatches * MunitionsConfig.RECIPE_CASING_COST.get(),
                "casing count expected " + expectedBatches + " batches");
        helper.assertTrue(be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_BULLET_HEAD).getCount()
                        == expectedBatches * MunitionsConfig.RECIPE_BULLET_HEAD_COST.get(),
                "bullet head count expected " + expectedBatches + " batches");
        helper.assertTrue(be.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PROPELLANT).getCount()
                        == expectedBatches * MunitionsConfig.RECIPE_PROPELLANT_COST.get(),
                "propellant count expected " + expectedBatches + " batches");
    }

    private static long readSettleTick(MunitionsBenchBlockEntity be) {
        return be.saveWithoutMetadata().getLong("LastSettleTick");
    }

    /** 经 NBT 注入缓冲态 (已产某口径若干发未取), 测换口径冲突门 (BufferedRounds/BufferedCaliber 持久键)。 */
    private static void seedBuffer(MunitionsBenchBlockEntity be, MunitionsCaliber caliber, int rounds) {
        net.minecraft.nbt.CompoundTag tag = be.saveWithoutMetadata();
        tag.putInt("BufferedRounds", rounds);
        tag.putInt("BufferedCaliber", caliber.index());
        be.load(tag);
    }

    private static IJobService swapJob(IJobService fake) {
        IJobService prev;
        try {
            prev = JobServices.jobService();
        } catch (IllegalStateException notRegistered) {
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

    /** 真 EconomyService (内存账本 + AbuseGuard + 惰性玩家态解析器); tryCharge 走真 sink 语义。 */
    private static IEconomyService freshEconomy(EconomyLedger ledger) {
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        Function<UUID, PlayerAbuseState> resolver = id -> states.computeIfAbsent(id, k -> new PlayerAbuseState());
        return new EconomyService(ledger, new AbuseGuard(), resolver);
    }

    /** 定级职业门面替身 (level/grantXp 不计数, 仅供 trySelectCaliber 的等级门读取)。 */
    private static final class FixedLevelJobService implements IJobService {
        private final int level;

        FixedLevelJobService(int level) {
            this.level = level;
        }

        @Override
        public int level(Player player, JobId job) {
            return level;
        }

        @Override
        public long totalXp(Player player, JobId job) {
            return 0L;
        }

        @Override
        public long grantXp(Player player, JobId job, long rawXp) {
            return rawXp;
        }

        @Override
        public JobProgress progress(Player player, JobId job) {
            throw new UnsupportedOperationException("not exercised by munitions caliber-gate tests");
        }
    }

    /** 记录 grantXp 调用的职业门面替身 (settle 谁产谁得断言用); level 给定值供产能查表。 */
    private static final class RecordingJobService implements IJobService {
        private final int level;
        int grantXpCalls = 0;
        JobId lastJob = null;
        long lastRawXp = Long.MIN_VALUE;

        RecordingJobService(int level) {
            this.level = level;
        }

        @Override
        public int level(Player player, JobId job) {
            return level;
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
            throw new UnsupportedOperationException("not exercised by munitions settle tests");
        }
    }
}
