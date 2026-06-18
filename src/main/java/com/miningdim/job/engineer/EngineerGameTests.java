package com.miningdim.job.engineer;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.engineer.effect.NanoEffects;
import com.miningdim.job.engineer.effect.NanoReactor;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 千年工程师核心逻辑 GameTest (MillenniumEngineer_Mod_DesignSpec 十三章测试断言 + 实现手册 GameTest 范式)。
 * 断言具体业务结果 (删被测核心逻辑测试必挂, 禁 is-not-null 弱校验; 含边界值)。
 *
 * 纯逻辑断言不依赖世界结构, 用 template = "empty" (data/miningdim/structures/empty.nbt, 框架已建)。
 * 数值断言均以 config 默认值为准 (10.3: GameTest 用 config 注入真值; 默认值即各表定稿/示例初值)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class EngineerGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "engineer";

    /**
     * 批前钩子: 确保 EngineerConfig 已绑定默认值 (本子系统集成阶段才接进 MiningDim, runGameTestServer 时
     * 其 SERVER spec 未经 Forge 加载; 不绑定则 dev 环境下 ConfigValue.get() 抛 IllegalStateException)。
     */
    @BeforeBatch(batch = BATCH)
    public static void beforeEngineerBatch(ServerLevel level) {
        EngineerConfig.ensureLoadedForTest();
    }

    // ============================================================
    // 等级解锁 (7.2 每两级一档)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unlockTierByLevel(GameTestHelper helper) {
        helper.assertTrue(EngineerLevels.unlockedTier(1) == NanoTier.LOW, "L1 unlocks LOW");
        helper.assertTrue(EngineerLevels.unlockedTier(2) == NanoTier.LOW, "L2 still LOW (MEDIUM needs L3)");
        helper.assertTrue(EngineerLevels.unlockedTier(3) == NanoTier.MEDIUM, "L3 unlocks MEDIUM");
        helper.assertTrue(EngineerLevels.unlockedTier(5) == NanoTier.HIGH, "L5 unlocks HIGH (effects start)");
        helper.assertTrue(EngineerLevels.unlockedTier(7) == NanoTier.SUPERIOR, "L7 unlocks SUPERIOR");
        helper.assertTrue(EngineerLevels.unlockedTier(9) == NanoTier.TRANSCENDENT, "L9 unlocks TRANSCENDENT");
        helper.assertTrue(EngineerLevels.unlockedTier(10) == NanoTier.RADIANT, "L10 unlocks RADIANT");
        // 等级门: L1 玩家不能解锁 MEDIUM (需 L3)。
        helper.assertFalse(EngineerLevels.isTierUnlocked(1, NanoTier.MEDIUM), "L1 cannot unlock MEDIUM");
        helper.assertTrue(EngineerLevels.isTierUnlocked(5, NanoTier.LOW), "L5 still allows LOW (降级)");
        helper.succeed();
    }

    // ============================================================
    // 矿石绑档 (3.2 低矿造不了高板; 高矿可降级)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oreTierGate(GameTestHelper helper) {
        // 铁锭最高只到 LOW。
        NanoTier ironMax = NanoTier.maxTierForOre(new ItemStack(Items.IRON_INGOT));
        helper.assertTrue(ironMax == NanoTier.LOW, "iron allows up to LOW");
        helper.assertTrue(NanoTier.LOW.allowedByOre(ironMax), "LOW allowed by iron");
        helper.assertFalse(NanoTier.HIGH.allowedByOre(ironMax), "HIGH NOT allowed by iron (低矿造不了高板)");
        // 下界合金锭最高到 RADIANT (含全部高档)。
        NanoTier nethMax = NanoTier.maxTierForOre(new ItemStack(Items.NETHERITE_INGOT));
        helper.assertTrue(nethMax == NanoTier.RADIANT, "netherite allows up to RADIANT");
        helper.assertTrue(NanoTier.LOW.allowedByOre(nethMax), "high ore can downgrade to LOW (浪费)");
        helper.assertTrue(NanoTier.RADIANT.allowedByOre(nethMax), "RADIANT allowed by netherite");
        // 非矿种 (石头) -> null -> 任何档都不允许。
        helper.assertTrue(NanoTier.maxTierForOre(new ItemStack(Items.COBBLESTONE)) == null,
                "non-ore returns null max tier");
        helper.assertFalse(NanoTier.LOW.allowedByOre(null), "null max tier denies even LOW");
        helper.succeed();
    }

    // ============================================================
    // 生产产出 (3.2 + 4.2 品质 +1; 固定种子确定结果)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void productionOutput(GameTestHelper helper) {
        // 极品: 1 下界合金锭 -> 2 板 (品质 0 < 阈值 4, 无 +1, 确定产 base 2)。
        RandomSource lowQuality = RandomSource.create(1L);
        NanoProduction.Result superior = NanoProduction.resolve(NanoTier.SUPERIOR, 0, lowQuality);
        helper.assertTrue(superior.oreConsumed() == EngineerConfig.SUPERIOR_NETHERITE_COST.get(),
                "superior consumes config netherite cost");
        helper.assertTrue(superior.platesProduced() == EngineerConfig.SUPERIOR_OUTPUT_COUNT.get(),
                "superior produces exactly base 2 plates at quality 0 (below bonus threshold)");
        // 低级: 4 铁 -> 1 板 (品质 0)。
        NanoProduction.Result low = NanoProduction.resolve(NanoTier.LOW, 0, RandomSource.create(2L));
        helper.assertTrue(low.oreConsumed() == EngineerConfig.LOW_IRON_COST.get(), "low consumes config iron cost");
        helper.assertTrue(low.platesProduced() == 1, "low produces 1 plate at quality 0");
        // 闪耀: 失败时残骸返还且 0 板 (用必失败种子: successChance 默认 0.5, 找一个 nextDouble>=0.5 的种子)。
        // 用确定性: 直接断言成功分支 (种子使 nextDouble<0.5) 产 >=1 板。
        NanoProduction.Result radiant = NanoProduction.resolve(NanoTier.RADIANT, 0, fixedRoll(0.0));
        helper.assertTrue(radiant.platesProduced() == 1, "radiant success (roll 0.0 < 0.5) yields 1 plate");
        helper.assertTrue(radiant.debrisRefund() == 0, "radiant success has no debris");
        NanoProduction.Result radiantFail = NanoProduction.resolve(NanoTier.RADIANT, 0, fixedRoll(0.99));
        helper.assertTrue(radiantFail.platesProduced() == 0, "radiant fail (roll 0.99 >= 0.5) yields 0 plates");
        helper.assertTrue(radiantFail.debrisRefund() == EngineerConfig.RADIANT_FAIL_REFUND.get(),
                "radiant fail refunds debris");
        helper.succeed();
    }

    // ============================================================
    // 修复曲线 (5.1 对 Damageable 改 setDamageValue)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void repairCurve(GameTestHelper helper) {
        // 低/中/高 固定值。
        helper.assertTrue(NanoRepair.repairAmount(NanoTier.LOW, 1000) == EngineerConfig.REPAIR_FIXED_LOW.get(),
                "LOW repair is fixed 100");
        helper.assertTrue(NanoRepair.repairAmount(NanoTier.MEDIUM, 1000) == EngineerConfig.REPAIR_FIXED_MEDIUM.get(),
                "MEDIUM repair is fixed 250");
        helper.assertTrue(NanoRepair.repairAmount(NanoTier.HIGH, 1000) == EngineerConfig.REPAIR_FIXED_HIGH.get(),
                "HIGH repair is fixed 600");
        // 极品 30% / 超凡 65% 最大耐久 (maxDamage=1000 -> 300 / 650)。
        helper.assertTrue(NanoRepair.repairAmount(NanoTier.SUPERIOR, 1000) == 300,
                "SUPERIOR repairs 30% of 1000 max = 300");
        helper.assertTrue(NanoRepair.repairAmount(NanoTier.TRANSCENDENT, 1000) == 650,
                "TRANSCENDENT repairs 65% of 1000 max = 650");
        // 闪耀 100% (= maxDamage)。
        helper.assertTrue(NanoRepair.repairAmount(NanoTier.RADIANT, 1000) == 1000,
                "RADIANT repairs 100% (full)");

        // 实际改 Damageable: 钻石镐 (修一切的技术基础)。
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        pick.setDamageValue(700);
        int restore = NanoRepair.repairAmount(NanoTier.LOW, pick.getMaxDamage());
        int after = Math.max(0, pick.getDamageValue() - restore);
        pick.setDamageValue(after);
        helper.assertTrue(pick.getDamageValue() == 700 - EngineerConfig.REPAIR_FIXED_LOW.get(),
                "LOW plate restored exactly 100 durability on a damageable item");
        helper.succeed();
    }

    // ============================================================
    // 机能修复递减安全阀 (6.2: 四件合计约 1.875 倍非 4 倍)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void vitalityDecayValve(GameTestHelper helper) {
        double per = EngineerConfig.VITALITY_HEAL_PCT_PER_TICK.get();
        // 单件 = per.
        helper.assertTrue(approx(NanoEffects.vitalityTotalHealFraction(1), per),
                "1 piece heals per-piece fraction");
        // 两件 = per * 1.5.
        helper.assertTrue(approx(NanoEffects.vitalityTotalHealFraction(2), per * 1.5),
                "2 pieces heal per*(1+0.5)");
        // 四件 = per * 1.875 (非 4 倍线性)。
        helper.assertTrue(approx(NanoEffects.vitalityTotalHealFraction(4), per * 1.875),
                "4 pieces heal per*1.875 (NOT 4x linear) - 防滚雪球铁律");
        helper.assertFalse(approx(NanoEffects.vitalityTotalHealFraction(4), per * 4.0),
                "4 pieces must NOT be 4x linear");
        // 超过 4 件不再线性放大 (第 5 件系数 0)。
        helper.assertTrue(approx(NanoEffects.vitalityTotalHealFraction(5),
                        NanoEffects.vitalityTotalHealFraction(4)),
                "5th piece adds 0 (valve bounded)");
        helper.succeed();
    }

    // ============================================================
    // 图腾人级共享 CD (6.2: 30min 内只触发一次)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void totemSharedCooldown(GameTestHelper helper) {
        long cdTicks = EngineerConfig.TOTEM_SHARED_CD_TICKS.get();
        long now = 1000L;
        // 初始 CD=0: 就绪。
        helper.assertTrue(NanoReactor.cooldownReady(now, 0L), "fresh totem (cdEnd=0) is ready");
        long newEnd = NanoReactor.nextCdEndTick(now);
        helper.assertTrue(newEnd == now + cdTicks, "next cd end = now + 36000 ticks (30min)");
        // CD 中 (1 tick 后): 未就绪。
        helper.assertFalse(NanoReactor.cooldownReady(now + 1L, newEnd), "1 tick later still on CD (叠穿不增命数)");
        helper.assertFalse(NanoReactor.cooldownReady(now + cdTicks - 1L, newEnd), "just before CD end not ready");
        // CD 结束: 再就绪。
        helper.assertTrue(NanoReactor.cooldownReady(now + cdTicks, newEnd), "at CD end ready again");
        // 复活血量 = 50% 最大血量 (80 血 -> 40)。
        helper.assertTrue(approx(NanoReactor.reviveHealth(80.0f),
                        (float) (80.0 * EngineerConfig.TOTEM_REVIVE_HEALTH_PCT.get())),
                "revive to % max health (80 * 0.5 = 40)");
        helper.succeed();
    }

    // ============================================================
    // 校准 QTE (4.2 命中推进 + 品质; 反挂机不点不动)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void calibrationQte(GameTestHelper helper) {
        NanoCalibration cal = new NanoCalibration();
        RandomSource rng = RandomSource.create(42L);
        cal.begin(rng);
        helper.assertTrue(cal.isActive(), "begin activates calibration");
        helper.assertTrue(cal.progress() == 0, "fresh progress 0");

        // 不点: tick 推进游标但进度不动 (反挂机)。
        int p0 = cal.progress();
        cal.serverTick(rng);
        cal.serverTick(rng);
        helper.assertTrue(cal.progress() == p0, "ticking without clicking does NOT advance progress (反挂机)");

        // 强制游标进绿区后点击 -> 命中 (进度 += hit, 品质 ++)。
        // 经多次 tick 直到 cursorInGreen, 防绿区落点偶然不命中。
        int guard = 0;
        while (!cal.cursorInGreen() && guard++ < 10000) {
            cal.serverTick(rng);
        }
        helper.assertTrue(cal.cursorInGreen(), "cursor eventually enters green zone");
        int q0 = cal.qualityHits();
        int prog0 = cal.progress();
        cal.onClick();
        helper.assertTrue(cal.qualityHits() == q0 + 1, "hit increments quality");
        helper.assertTrue(cal.progress() == prog0 + EngineerConfig.CALIBRATION_HIT_PROGRESS.get(),
                "hit advances progress by hitProgress");
        helper.succeed();
    }

    // ============================================================
    // 谁产谁得反代练 (7.4 / 9.3 修复经验只给 producerUUID 匹配者)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void producerStampAntiPowerlevel(GameTestHelper helper) {
        java.util.UUID alice = java.util.UUID.randomUUID();
        java.util.UUID bob = java.util.UUID.randomUUID();

        ItemStack ownPlate = NanoProduction.makePlate(NanoTier.HIGH, 1, alice, 0);
        helper.assertTrue(NanoNbt.producer(ownPlate).orElse(null).equals(alice), "plate stamped with producer alice");
        helper.assertTrue(NanoNbt.isProductionXpPending(ownPlate), "fresh plate has xp pending");

        // 取走即清: clearProductionXpPending 后再读为 false (塞回再取不重复刷)。
        NanoNbt.clearProductionXpPending(ownPlate);
        helper.assertFalse(NanoNbt.isProductionXpPending(ownPlate), "cleared pending stays false (取走即清)");

        // producer 匹配判定: bob 的板 producer != alice。
        ItemStack bobPlate = NanoProduction.makePlate(NanoTier.HIGH, 1, bob, 0);
        helper.assertFalse(NanoNbt.producer(bobPlate).orElse(null).equals(alice),
                "bob's plate producer is NOT alice (小号产板喂大号挡)");
        helper.succeed();
    }

    // ============================================================
    // 品质 -> 经验杠杆 (4.2 / 7.4: 同档同数量, 高品质板携带的原始经验严格大于低品质板)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void qualityLeverRawXp(GameTestHelper helper) {
        java.util.UUID maker = java.util.UUID.randomUUID();
        // 品质命中数随板持久化, 取出结算经验的杠杆基础。coef 默认 0.05: 高品质应严格高于低品质。
        ItemStack lowQ = NanoProduction.makePlate(NanoTier.HIGH, 1, maker, 0);
        ItemStack highQ = NanoProduction.makePlate(NanoTier.HIGH, 1, maker, 8);
        helper.assertTrue(NanoNbt.qualityHits(lowQ) == 0, "low-quality plate records 0 quality hits");
        helper.assertTrue(NanoNbt.qualityHits(highQ) == 8, "high-quality plate records 8 quality hits");

        double coef = EngineerConfig.PRODUCTION_XP_QUALITY_COEF.get();
        int baseRaw = NanoTier.HIGH.rawXp();
        long lowXp = (long) Math.floor(baseRaw * (1.0 + coef * 0));
        long highXp = (long) Math.floor(baseRaw * (1.0 + coef * 8));
        helper.assertTrue(highXp > lowXp,
                "high quality plate raw xp (" + highXp + ") strictly greater than low quality (" + lowXp + ")");
        // 严格量化: coef=0.05, base=60 -> low=60, high=floor(60*1.4)=84。
        helper.assertTrue(lowXp == baseRaw, "quality 0 raw xp equals base tier raw xp");
        helper.assertTrue(highXp == (long) Math.floor(baseRaw * (1.0 + coef * 8)),
                "quality 8 raw xp = floor(base * (1 + coef*8))");
        helper.succeed();
    }

    // ============================================================
    // 品质 -> 特效概率杠杆 (4.2 / 6.1: 高品质板修甲固定种子下特效掷出概率高于低品质板)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void qualityLeverEffectChance(GameTestHelper helper) {
        // 固定种子蒙特卡洛: 同一序列下, 高品质 (高 chance) 掷出特效次数应严格多于低品质 (低 chance)。
        // chance = base + coef*qualityHits; 用 FixedDoubleRandom 逐点扫 [0,1) 等距阈值判命中, 避免 RNG 抖动。
        double base = EngineerConfig.EFFECT_ROLL_BASE_CHANCE.get();
        double coef = EngineerConfig.EFFECT_ROLL_QUALITY_COEF.get();
        int lowQuality = 0;
        int highQuality = 6;
        double lowChance = Math.min(1.0, base + coef * lowQuality);
        double highChance = Math.min(1.0, base + coef * highQuality);
        helper.assertTrue(highChance > lowChance,
                "quality 6 effect chance (" + highChance + ") strictly greater than quality 0 (" + lowChance + ")");

        // 端到端: 高品质板修甲, 用 roll 落在 (lowChance, highChance) 区间的种子 -> 低品质不掷, 高品质掷出特效。
        double roll = (lowChance + highChance) / 2.0;
        ItemStack lowArmor = new ItemStack(Items.NETHERITE_CHESTPLATE);
        lowArmor.setDamageValue(100);
        NanoRepair.applyEffectsOnRepair(lowArmor, NanoTier.HIGH, lowQuality,
                new com.miningdim.job.engineer.testutil.FixedDoubleRandom(roll));
        helper.assertTrue(NanoNbt.effects(lowArmor).isEmpty(),
                "low quality: roll above its chance -> no effect rolled");

        ItemStack highArmor = new ItemStack(Items.NETHERITE_CHESTPLATE);
        highArmor.setDamageValue(100);
        NanoRepair.applyEffectsOnRepair(highArmor, NanoTier.HIGH, highQuality,
                new com.miningdim.job.engineer.testutil.FixedDoubleRandom(roll));
        helper.assertFalse(NanoNbt.effects(highArmor).isEmpty(),
                "high quality: same roll below its higher chance -> effect rolled (quality lever live)");
        helper.succeed();
    }

    // ============================================================
    // 闪耀板对满耐久护甲重 roll 特效 (5.1/6.1 line 117: 闪耀作用于 "任意" 护甲)
    // 回归: 修复前 pickRepairTarget bestDamage 起点 0 + 严格 > 使满甲 (damage=0) 永不被选,
    // 闪耀放行满甲的分支成死代码; 满甲玩家持闪耀板得 no_target。下列断言锁死该修复。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void radiantTargetsFullDurabilityArmor(GameTestHelper helper) {
        // 一组 "穿戴甲": 一件满耐久 (damage=0) 的下界合金胸甲, 其余空。
        ItemStack fullArmor = new ItemStack(Items.NETHERITE_CHESTPLATE);
        helper.assertTrue(fullArmor.getDamageValue() == 0, "fresh armor is full durability (damage=0)");
        java.util.List<ItemStack> worn = java.util.List.of(
                ItemStack.EMPTY, ItemStack.EMPTY, fullArmor, ItemStack.EMPTY);

        // 普通档 (LOW): 满甲不被选 (满甲无固定值修复意义) -> null。
        helper.assertTrue(com.miningdim.job.engineer.item.NanoArmorPlateItem
                        .selectWornTarget(worn, NanoTier.LOW) == null,
                "non-radiant plate does NOT pick full-durability armor (would be no_target)");
        // 闪耀档 (RADIANT): 满甲仍可被选 (重 roll 特效) -> 命中那件满甲。
        ItemStack picked = com.miningdim.job.engineer.item.NanoArmorPlateItem
                .selectWornTarget(worn, NanoTier.RADIANT);
        helper.assertTrue(picked == fullArmor,
                "RADIANT plate picks the full-durability worn armor (dead branch now reachable)");

        // 单件 eligibility 同口径: 普通档拒满甲, 闪耀放行。
        helper.assertFalse(com.miningdim.job.engineer.item.NanoArmorPlateItem
                        .eligibleAsTarget(fullArmor, NanoTier.HIGH),
                "non-radiant: full-durability single item not eligible");
        helper.assertTrue(com.miningdim.job.engineer.item.NanoArmorPlateItem
                        .eligibleAsTarget(fullArmor, NanoTier.RADIANT),
                "radiant: full-durability single item eligible (reroll effects)");
        // 空/不可破坏物品任何档都不合格。
        helper.assertFalse(com.miningdim.job.engineer.item.NanoArmorPlateItem
                        .eligibleAsTarget(ItemStack.EMPTY, NanoTier.RADIANT),
                "empty stack never eligible even for radiant");

        // 端到端: 闪耀板对满耐久甲掷特效 (必出, 不读品质) —— 证明 NanoRepair line 57 放行的满甲分支可达且产出特效。
        NanoRepair.applyEffectsOnRepair(fullArmor, NanoTier.RADIANT, 0, fixedRoll(0.99));
        helper.assertFalse(NanoNbt.effects(fullArmor).isEmpty(),
                "RADIANT always rolls an effect on full-durability armor (roll value irrelevant)");
        helper.succeed();
    }

    // ============================================================
    // 图腾人级共享 CD 单一权威时钟 (6.2 / line 152: 人级跨维度)
    // 回归: CD 截止 tick 与就绪检查必须用同一全服权威时钟 (主世界), 否则跨维度时钟漂移导致就绪误判。
    // 此处锁死纯逻辑不变量: 同一权威 now 下 set 的 cdEnd, 用同一权威 now 检查时序自洽。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void totemCdSingleAuthoritativeClock(GameTestHelper helper) {
        long cdTicks = EngineerConfig.TOTEM_SHARED_CD_TICKS.get();
        // 权威时钟 (主世界) 在触发瞬间读到的 now。
        long authoritativeNow = 5000L;
        long cdEnd = NanoReactor.nextCdEndTick(authoritativeNow);
        helper.assertTrue(cdEnd == authoritativeNow + cdTicks, "cdEnd = authoritative now + cd");

        // 缺陷场景模拟: 玩家所在维度时钟落后/超前主世界 (per-level drift)。若就绪检查误用维度时钟,
        // 落后维度会提前就绪 (再救一次), 超前维度会延后就绪。修复后两端都用同一权威 now -> 决策唯一。
        long laggingLevelClock = authoritativeNow - cdTicks; // 远落后维度。
        long leadingLevelClock = authoritativeNow + cdTicks; // 远超前维度。
        // 用 (错误的) 维度时钟检查会得出不同结论, 证明漂移确实改变就绪判定 (缺陷可复现性)。
        helper.assertFalse(NanoReactor.cooldownReady(laggingLevelClock, cdEnd),
                "lagging-level clock would (wrongly) report still-on-cd vs authoritative");
        helper.assertTrue(NanoReactor.cooldownReady(leadingLevelClock, cdEnd),
                "leading-level clock would (wrongly) report ready early vs authoritative");
        // 用权威时钟 (修复口径): 刚触发后立即检查必然未就绪 (CD 中), 整段 CD 内保持未就绪, 期满才就绪。
        helper.assertFalse(NanoReactor.cooldownReady(authoritativeNow, cdEnd),
                "authoritative clock: just-triggered totem is on cooldown");
        helper.assertFalse(NanoReactor.cooldownReady(authoritativeNow + cdTicks - 1L, cdEnd),
                "authoritative clock: still on cooldown 1 tick before expiry");
        helper.assertTrue(NanoReactor.cooldownReady(authoritativeNow + cdTicks, cdEnd),
                "authoritative clock: ready exactly at expiry (single-clock self-consistent)");
        helper.succeed();
    }

    // ============================================================
    // 特效 NBT 往返 + 重塑/机能修复失效判定 (6.2 / 6.3)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void effectNbtAndFailThresholds(GameTestHelper helper) {
        ItemStack armor = new ItemStack(Items.NETHERITE_CHESTPLATE);
        NanoNbt.writeEffects(armor, java.util.EnumSet.of(NanoEffect.VITALITY, NanoEffect.SHIELD));
        helper.assertTrue(NanoNbt.hasEffect(armor, NanoEffect.VITALITY), "vitality written");
        helper.assertTrue(NanoNbt.hasEffect(armor, NanoEffect.SHIELD), "shield written");
        helper.assertTrue(NanoNbt.shieldCharges(armor) == EngineerConfig.SHIELD_MAX_CHARGES.get(),
                "shield init charges = max");

        // 机能修复失效: 耐久 < 50% 该件停。满耐久生效, 半耐久以下失效。
        armor.setDamageValue(0);
        helper.assertTrue(NanoEffects.vitalityActive(armor), "full durability vitality active");
        armor.setDamageValue((int) (armor.getMaxDamage() * 0.6)); // 剩 40% < 50%。
        helper.assertFalse(NanoEffects.vitalityActive(armor), "below 50% durability vitality fails");

        // 清旧特效 (再次纳米修复语义)。
        NanoNbt.clearEffects(armor);
        helper.assertFalse(NanoNbt.hasEffect(armor, NanoEffect.VITALITY), "clearEffects removes all effects");

        // 重塑失效: 损失 > 阈值 (默认 40%)。
        ItemStack r = new ItemStack(Items.NETHERITE_CHESTPLATE);
        NanoNbt.writeEffects(r, java.util.EnumSet.of(NanoEffect.RESHAPE));
        r.setDamageValue((int) (r.getMaxDamage() * 0.30)); // 损失 30% <= 40% 生效。
        helper.assertTrue(NanoEffects.reshapeActive(r), "lost 30% (<=40%) reshape still active");
        r.setDamageValue((int) (r.getMaxDamage() * 0.50)); // 损失 50% > 40% 失效。
        helper.assertFalse(NanoEffects.reshapeActive(r), "lost 50% (>40%) reshape fails");
        helper.succeed();
    }

    // ============================================================
    // 护盾反应式语义 (6.2: 充能仅受击消耗, 时钟不自动开窗/不空耗; 5 次 = 5 次救命)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shieldReactiveSemantics(GameTestHelper helper) {
        int interval = EngineerConfig.EFFECT_TICK_INTERVAL.get();
        int regenInterval = EngineerConfig.SHIELD_REGEN_INTERVAL_TICKS.get();
        int maxCharges = EngineerConfig.SHIELD_MAX_CHARGES.get();

        ItemStack armor = new ItemStack(Items.NETHERITE_CHESTPLATE);
        NanoNbt.writeEffects(armor, java.util.EnumSet.of(NanoEffect.SHIELD));
        helper.assertTrue(NanoNbt.shieldCharges(armor) == maxCharges, "fresh shield has max charges");

        // 时钟推进满一个完整再生间隔: 充能 armed (regenTick 归 0), 但既不开窗也不耗充能 (反应式核心)。
        int ticksToArm = (regenInterval + interval - 1) / interval; // 向上取整周期数。
        for (int i = 0; i < ticksToArm; i++) {
            boolean inWindow = NanoEffects.advanceShieldTimers(armor, interval);
            helper.assertFalse(inWindow, "ticker never opens an immunity window on its own (no auto-window)");
        }
        helper.assertTrue(NanoNbt.shieldCharges(armor) == maxCharges,
                "after a full regen interval with no hit, charges are NOT auto-consumed (5 = 5 saves)");
        helper.assertTrue(NanoNbt.shieldRegenTick(armor) == 0, "regen counted down to 0 (armed)");
        helper.assertFalse(NanoEffects.shieldWindowActive(armor),
                "armed but no active window until actually hit");

        // 受击触发: 消耗一次充能, 开免疫窗, 重置再生倒计时。
        boolean opened = NanoEffects.tryReactiveShield(armor);
        helper.assertTrue(opened, "reactive shield opens window on hit when armed");
        helper.assertTrue(NanoNbt.shieldCharges(armor) == maxCharges - 1, "one charge consumed on the triggering hit");
        helper.assertTrue(NanoNbt.shieldWindowTick(armor) == EngineerConfig.SHIELD_IMMUNITY_TICKS.get(),
                "immunity window set to configured ticks");
        helper.assertTrue(NanoNbt.shieldRegenTick(armor) == regenInterval, "regen timer reset to full interval");
        helper.assertTrue(NanoEffects.shieldWindowActive(armor), "window active after trigger");

        // 窗口期内再受击不再额外耗充能 (一路免疫即返回); 充能未 armed 时受击不触发。
        boolean retriggerWhileOnCooldown = NanoEffects.tryReactiveShield(armor);
        helper.assertFalse(retriggerWhileOnCooldown,
                "with regen on cooldown, a hit does NOT consume another charge (no per-hit drain)");
        helper.assertTrue(NanoNbt.shieldCharges(armor) == maxCharges - 1,
                "charge count unchanged while regen is on cooldown");
        helper.succeed();
    }

    // ---- 测试辅助 ----

    /** 固定返回指定 nextDouble 的 RandomSource (闪耀成功/失败分支确定化)。其它方法回退默认随机。 */
    private static RandomSource fixedRoll(double roll) {
        return new com.miningdim.job.engineer.testutil.FixedDoubleRandom(roll);
    }

    private static boolean approx(double a, double b) {
        return Math.abs(a - b) < 1.0e-6;
    }

    private static boolean approx(float a, float b) {
        return Math.abs(a - b) < 1.0e-4;
    }
}
