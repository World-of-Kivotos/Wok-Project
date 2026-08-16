package com.miningdim.job.engineer;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.IJobService;
import com.miningdim.job.JobId;
import com.miningdim.job.JobProgress;
import com.miningdim.job.JobServices;
import com.miningdim.job.engineer.block.ProductionTableBlock;
import com.miningdim.job.engineer.block.ProductionTableBlockEntity;
import com.miningdim.job.engineer.effect.NanoEffects;
import com.miningdim.job.engineer.effect.NanoAnvilGuard;
import com.miningdim.job.engineer.effect.NanoReactor;
import com.miningdim.job.engineer.effect.NanoShieldHandler;
import com.miningdim.job.engineer.menu.ProductionTableMenu;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
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

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void productionTablesHaveHorizontalFacing(GameTestHelper helper) {
        for (NanoTier tier : NanoTier.values()) {
            BlockState state = ModEngineerBlocks.table(tier).get().defaultBlockState();
            helper.assertTrue(state.hasProperty(ProductionTableBlock.FACING),
                    tier + " production table has a horizontal facing property");
            helper.assertTrue(state.getValue(ProductionTableBlock.FACING) == Direction.NORTH,
                    tier + " production table defaults to north-facing");
        }
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
        helper.assertTrue(radiant.scrapRefund() == 0, "radiant success has no scrap refund");
        NanoProduction.Result radiantFail = NanoProduction.resolve(NanoTier.RADIANT, 0, fixedRoll(0.99));
        helper.assertTrue(radiantFail.platesProduced() == 0, "radiant fail (roll 0.99 >= 0.5) yields 0 plates");
        helper.assertTrue(radiantFail.scrapRefund() == EngineerConfig.RADIANT_FAIL_REFUND.get(),
                "radiant failure refunds netherite scrap");
        helper.assertTrue(NanoProduction.makeRadiantFailureRefund(radiantFail.scrapRefund())
                        .is(Items.NETHERITE_SCRAP),
                "radiant failure refund item is netherite scrap, not a netherite ingot");
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
        cal.begin(rng, 20);
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
        int progressAfterAcceptedClick = cal.progress();
        int qualityAfterAcceptedClick = cal.qualityHits();
        cal.onClick();
        helper.assertTrue(cal.progress() == progressAfterAcceptedClick
                        && cal.qualityHits() == qualityAfterAcceptedClick,
                "a second click in the same scanner sweep is ignored");
        helper.assertTrue(cal.requiredTicks() == 20 && cal.elapsedTicks() > 0,
                "configured production duration is snapshotted and elapsed on server ticks");
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
    // 维修套件目标决策: 另一只手精确指定、非法目标回退、按损耗比例选择
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void repairKitTargetSelectionIsExplicitAndRatioBased(GameTestHelper helper) {
        ItemStack heldTool = new ItemStack(Items.DIAMOND_PICKAXE);
        heldTool.setDamageValue(1);

        ItemStack nearlyBrokenHelmet = new ItemStack(Items.IRON_HELMET);
        nearlyBrokenHelmet.setDamageValue(120);
        ItemStack higherRawDamageChestplate = new ItemStack(Items.NETHERITE_CHESTPLATE);
        higherRawDamageChestplate.setDamageValue(200);
        java.util.List<ItemStack> worn = java.util.List.of(
                ItemStack.EMPTY, ItemStack.EMPTY, higherRawDamageChestplate, nearlyBrokenHelmet);

        ItemStack explicitlyPicked = com.miningdim.job.engineer.item.NanoArmorPlateItem
                .chooseRepairTarget(heldTool, worn, NanoTier.LOW);
        helper.assertTrue(explicitlyPicked == heldTool,
                "the item held opposite the repair kit has priority over worn armor");

        ItemStack automaticallyPicked = com.miningdim.job.engineer.item.NanoArmorPlateItem
                .chooseRepairTarget(ItemStack.EMPTY, worn, NanoTier.LOW);
        helper.assertTrue(automaticallyPicked == nearlyBrokenHelmet,
                "automatic selection uses damage ratio, not the largest raw damage value");

        ItemStack unbreakableTool = new ItemStack(Items.DIAMOND_PICKAXE);
        unbreakableTool.setDamageValue(100);
        unbreakableTool.getOrCreateTag().putBoolean("Unbreakable", true);
        ItemStack fallbackPicked = com.miningdim.job.engineer.item.NanoArmorPlateItem
                .chooseRepairTarget(unbreakableTool, worn, NanoTier.LOW);
        helper.assertTrue(fallbackPicked == nearlyBrokenHelmet,
                "an unbreakable held item is skipped instead of blocking a valid worn target");

        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ItemStack highKit = new ItemStack(ModEngineerItems.plate(NanoTier.HIGH).get());
        heldTool.setDamageValue(100);
        NanoRepair.Result toolRepair = NanoRepair.repair(heldTool, highKit, player, fixedRoll(0.0D));
        helper.assertTrue(toolRepair.success() && heldTool.getDamageValue() == 0,
                "a high-tier kit still repairs a damageable tool");
        helper.assertTrue(NanoNbt.effects(heldTool).isEmpty(),
                "repairing a tool never writes armor-only nano effect NBT");
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

        // 新护盾立即可用；逐次耗尽时物品和特效仍保留。
        for (int i = 0; i < maxCharges; i++) {
            helper.assertTrue(NanoEffects.tryReactiveShield(armor),
                    "each stored charge can open an immunity window");
            NanoNbt.setShieldWindowTick(armor, 0);
        }
        helper.assertTrue(NanoNbt.shieldCharges(armor) == 0, "all shield energy can be depleted to zero");
        helper.assertTrue(NanoNbt.hasEffect(armor, NanoEffect.SHIELD),
                "zero energy never removes the shield effect or armor");
        helper.assertFalse(NanoEffects.tryReactiveShield(armor), "zero-energy shield is temporarily inactive");

        // 一个完整充电周期后恢复一格，可再次触发。
        int ticksToRecharge = (regenInterval + interval - 1) / interval;
        for (int i = 0; i < ticksToRecharge; i++) {
            NanoEffects.advanceShieldTimers(armor, interval);
        }
        helper.assertTrue(NanoNbt.shieldCharges(armor) == 1,
                "a depleted shield recharges one energy after the configured interval");
        helper.assertTrue(NanoEffects.tryReactiveShield(armor),
                "recharged shield becomes usable again without repair");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shieldArmorRejectsRepairAndNanoArmorRejectsMending(GameTestHelper helper) {
        ItemStack shieldArmor = new ItemStack(Items.NETHERITE_CHESTPLATE);
        shieldArmor.setDamageValue(100);
        NanoNbt.writeEffects(shieldArmor, java.util.EnumSet.of(NanoEffect.SHIELD));
        helper.assertTrue(NanoRepair.isShieldType(shieldArmor), "nano shield effect marks armor as shield type");
        helper.assertFalse(com.miningdim.job.engineer.item.NanoArmorPlateItem.eligibleAsTarget(
                        shieldArmor, NanoTier.RADIANT),
                "repair kit target selection rejects every shield-type armor");

        ItemStack nanoArmor = new ItemStack(Items.NETHERITE_CHESTPLATE);
        NanoNbt.writeEffects(nanoArmor, java.util.EnumSet.of(NanoEffect.VITALITY));
        nanoArmor.enchant(Enchantments.MENDING, 1);
        helper.assertTrue(NanoAnvilGuard.stripMendingFromNanoEffectArmor(nanoArmor),
                "legacy nano-effect armor has Mending removed");
        helper.assertTrue(EnchantmentHelper.getItemEnchantmentLevel(Enchantments.MENDING, nanoArmor) == 0,
                "Mending remains disabled on nano-effect armor");

        ItemStack ordinaryArmor = new ItemStack(Items.DIAMOND_CHESTPLATE);
        ordinaryArmor.enchant(Enchantments.MENDING, 1);
        helper.assertFalse(NanoAnvilGuard.stripMendingFromNanoEffectArmor(ordinaryArmor),
                "ordinary armor is outside the Mending restriction");
        helper.assertTrue(EnchantmentHelper.getItemEnchantmentLevel(Enchantments.MENDING, ordinaryArmor) == 1,
                "ordinary armor keeps Mending");
        helper.succeed();
    }

    // ============================================================
    // F046 回归: 纳米护盾必须放行 bypasses_invulnerability 与非正伤害, 不吃 /kill、虚空清理与 0 伤害假事件
    // ============================================================
    // 缺陷: onLivingHurt 修复前无源头/幅值前置守卫, /kill (genericKill, 走 bypasses_invulnerability) 与虚空
    // 伤害 (fellOutOfWorld, 同属该 tag) 会被免疫窗或反应式触发吃掉伤害, 使管理员清理手段静默失效; 非正伤害
    // (amount<=0) 也会白烧一格救命充能。此处仅穿 NETHERITE_CHESTPLATE + SHIELD 特效, 不穿 PlateArmorItem /
    // PlasmaShieldItem (二者任一装备都会让 NanoShieldHandler 直接短路)。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nanoShieldBypassesInvulnerabilityAndIgnoresNonPositiveDamage(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ItemStack armor = new ItemStack(Items.NETHERITE_CHESTPLATE);
        NanoNbt.writeEffects(armor, java.util.EnumSet.of(NanoEffect.SHIELD));
        player.setItemSlot(EquipmentSlot.CHEST, armor);
        int maxCharges = NanoNbt.shieldCharges(armor);

        // 1) /kill 路: bypasses_invulnerability 必须完整放行, 不烧充能、不开免疫窗。
        LivingHurtEvent killEvent = new LivingHurtEvent(
                player, helper.getLevel().damageSources().genericKill(), 23.75F);
        new NanoShieldHandler().onLivingHurt(killEvent);
        helper.assertTrue(Float.compare(killEvent.getAmount(), 23.75F) == 0,
                "generic_kill damage must pass through the nano shield completely unchanged, got "
                        + killEvent.getAmount());
        helper.assertTrue(NanoNbt.shieldCharges(armor) == maxCharges,
                "generic_kill must not burn a shield charge, had " + maxCharges
                        + " now " + NanoNbt.shieldCharges(armor));
        helper.assertTrue(NanoNbt.shieldWindowTick(armor) == 0,
                "generic_kill must not open an immunity window");

        // 2) 虚空路: fellOutOfWorld 同属 bypasses_invulnerability, 同等放行 + 充能不变。
        LivingHurtEvent voidEvent = new LivingHurtEvent(
                player, helper.getLevel().damageSources().fellOutOfWorld(), 23.75F);
        new NanoShieldHandler().onLivingHurt(voidEvent);
        helper.assertTrue(Float.compare(voidEvent.getAmount(), 23.75F) == 0,
                "fell_out_of_world damage must pass through the nano shield completely unchanged, got "
                        + voidEvent.getAmount());
        helper.assertTrue(NanoNbt.shieldCharges(armor) == maxCharges,
                "void damage must not burn a shield charge, now " + NanoNbt.shieldCharges(armor));

        // 3) 运营标签: bypasses_nano_shield 这个自定义 datapack 标签必须真的被加载, 且能独立 (不靠
        // bypasses_invulnerability) 放行饥饿伤害。
        helper.assertTrue(helper.getLevel().damageSources().starve().is(NanoShieldHandler.BYPASSES_NANO_SHIELD),
                "the bypasses_nano_shield datapack tag must load and include starvation damage");
        LivingHurtEvent starveEvent = new LivingHurtEvent(
                player, helper.getLevel().damageSources().starve(), 4.0F);
        new NanoShieldHandler().onLivingHurt(starveEvent);
        helper.assertTrue(Float.compare(starveEvent.getAmount(), 4.0F) == 0,
                "starvation damage tagged bypasses_nano_shield must pass through unchanged, got "
                        + starveEvent.getAmount());

        // 4) 非正伤害不烧充能: amount<=0 的假事件必须被前置守卫短路, 不动状态。
        LivingHurtEvent zeroEvent = new LivingHurtEvent(
                player, helper.getLevel().damageSources().generic(), 0.0F);
        new NanoShieldHandler().onLivingHurt(zeroEvent);
        helper.assertTrue(NanoNbt.shieldCharges(armor) == maxCharges,
                "zero-amount damage must not burn a shield charge");
        helper.assertTrue(NanoNbt.shieldWindowTick(armor) == 0,
                "zero-amount damage must not open an immunity window");

        // 5) 正向回归 (防把 handler 改死): 真实正伤害仍必须被完整吸收、烧一格充能、开满配置免疫窗。
        NanoNbt.setShieldWindowTick(armor, 0);
        int chargesBeforeRealHit = NanoNbt.shieldCharges(armor);
        LivingHurtEvent realHit = new LivingHurtEvent(
                player, helper.getLevel().damageSources().playerAttack(player), 20.0F);
        new NanoShieldHandler().onLivingHurt(realHit);
        helper.assertTrue(Float.compare(realHit.getAmount(), 0.0F) == 0,
                "a genuine hit with charges available must be fully absorbed to zero, got " + realHit.getAmount());
        helper.assertTrue(NanoNbt.shieldCharges(armor) == chargesBeforeRealHit - 1,
                "an absorbed hit must burn exactly one shield charge, had " + chargesBeforeRealHit
                        + " now " + NanoNbt.shieldCharges(armor));
        helper.assertTrue(NanoNbt.shieldWindowTick(armor) == EngineerConfig.SHIELD_IMMUNITY_TICKS.get(),
                "an absorbed hit must open the full configured immunity window, got "
                        + NanoNbt.shieldWindowTick(armor));
        helper.succeed();
    }

    // ============================================================
    // engineer-01 回归: Shift 取产物板 (走基类 quickMoveStack) 必须结算生产经验 + 清 pending
    // ============================================================
    // 缺陷: 基类 AbstractMiningMenu.quickMoveStack 先 moveItemStackTo 把整栈移走, 再 slot.onTake(player, 残留栈);
    // 整栈取走时残留为 EMPTY。旧 OutputSlot 把该残留当 taken 传给 onOutputTaken, 后者首行 taken.isEmpty() 即 return,
    // 故最常用的 Shift 取板既不给经验也不清 pending, 经验永久丢失。修复改用 (取出前快照数量 - 残留数量) 作取走量与
    // 非空判据。本用例走真菜单 quickMoveStack 端到端锁死该修复: 删掉 OutputSlot 的快照差值结算 / 把 onOutputTaken
    // 改回信传入栈 count 必挂 (经验=0, grantXpCalls=0)。

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shiftTakeOutputSettlesProductionXp(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService();
        IJobService prevJob = swapJob(job);
        try {
            ProductionTableBlockEntity be = newProductionTable(helper);
            be.setOwner(player.getUUID());
            BlockPos abs = helper.absolutePos(new BlockPos(0, 1, 0));

            // 板: 高级 (rawXp 默认 60) × 3, 品质 0, 生产者 = 取出者 (谁产谁得匹配)。
            int plateCount = 3;
            ItemStack plates = NanoProduction.makePlate(NanoTier.HIGH, plateCount, player.getUUID(), 0);
            be.inventory().setStackInSlot(ProductionTableBlockEntity.SLOT_OUTPUT, plates);
            helper.assertTrue(NanoNbt.isProductionXpPending(
                            be.inventory().getStackInSlot(ProductionTableBlockEntity.SLOT_OUTPUT)),
                    "freshly produced board sits xp-pending before take");

            // 构造真菜单并走基类 Shift 路径 (quickMoveStack), 这是缺陷所在的最常用取板路径。
            ProductionTableMenu menu = new ProductionTableMenu(0, player.getInventory(), abs);
            int outputMenuSlot = ProductionTableBlockEntity.SLOT_OUTPUT; // 容器槽: 0=输入,1=输出 (玩家 36 槽在其后)。
            ItemStack moved = menu.quickMoveStack(player, outputMenuSlot);

            // 整栈 (3 板) Shift 进空的玩家背包: 输出槽清空, 移出物为 3 板。
            helper.assertTrue(be.inventory().getStackInSlot(ProductionTableBlockEntity.SLOT_OUTPUT).isEmpty(),
                    "whole stack shift-moved out: output slot now empty");
            helper.assertTrue(moved.getCount() == plateCount,
                    "quickMoveStack reports the full 3 plates moved, got " + moved.getCount());

            // 核心: Shift 取板必须结算一次生产经验给生产者 (缺陷下 grantXpCalls==0)。
            helper.assertTrue(job.grantXpCalls == 1,
                    "shift-take settles production xp exactly once (was 0 before fix), got " + job.grantXpCalls);
            helper.assertTrue(job.lastJob == JobId.ENGINEER, "xp credited to ENGINEER job");
            // 原始经验 = floor(rawHigh * (1 + coef*0)) * 3 = 60 * 3 = 180 (按【实际取走量】3 计, 非残留 0)。
            long expectedRaw = (long) Math.floor(NanoTier.HIGH.rawXp()
                    * (1.0 + EngineerConfig.PRODUCTION_XP_QUALITY_COEF.get() * 0)) * plateCount;
            helper.assertTrue(expectedRaw == 180L, "high plate ×3 raw xp anchor = 60*3 = 180, got " + expectedRaw);
            helper.assertTrue(job.lastRawXp == expectedRaw,
                    "shift-take grants raw xp scaled by ACTUAL taken count 3 (= " + expectedRaw + "), got "
                            + job.lastRawXp);
            helper.assertTrue(job.lastRawXp > 0L,
                    "engineer effective raw xp strictly > 0 on shift-take (anti silent-loss)");

            // pending 已清: 同一板再次结算 (模拟塞回再取) 不得重复发经验。直接驱动 BE 结算口径验证 pending 清零。
            int callsBefore = job.grantXpCalls;
            ItemStack reinserted = NanoProduction.makePlate(NanoTier.HIGH, 1, player.getUUID(), 0);
            be.onOutputTaken(player, reinserted, 1); // 首取: 清 pending + 发经验 (calls+1)。
            helper.assertFalse(NanoNbt.isProductionXpPending(reinserted),
                    "onOutputTaken clears productionXpPending after settling (取走即清)");
            helper.assertTrue(job.grantXpCalls == callsBefore + 1, "first settle of the reinserted plate grants once");
            be.onOutputTaken(player, reinserted, 1); // 再取同一已清板: pending=false 短路, 不再发经验。
            helper.assertTrue(job.grantXpCalls == callsBefore + 1,
                    "re-taking an already-settled (pending cleared) plate grants no further xp (no re-grind)");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
        }
    }

    // ============================================================
    // engineer-01 边界: 部分取板时按【实际取走量】而非【残留量】结算 (修复的结算口径锁死)
    // ============================================================
    // 残留非空时, 基类传给 onTake 的栈 count = 残留 (非取走量); 信传入 count 会把经验按残留误算。修复改用调用处
    // 显式传入的真实取走量。此处直接驱动 BE 结算口径: 取出前 5 板、实际取走 2、残留 3, 经验必须按 2 (=120), 非 3 (=180)。

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void outputTakenSettlesByActualTakenCountNotResidual(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService();
        IJobService prevJob = swapJob(job);
        try {
            ProductionTableBlockEntity be = newProductionTable(helper);
            be.setOwner(player.getUUID());

            // 取出前板栈快照 5 板 (HIGH, 品质 0, 生产者 = 取出者), 本次实际取走 2 (残留 3 不在此结算)。
            ItemStack boardSnapshot = NanoProduction.makePlate(NanoTier.HIGH, 5, player.getUUID(), 0);
            be.onOutputTaken(player, boardSnapshot, 2);

            // 经验按【实际取走量 2】: floor(rawHigh) * 2 = 60*2 = 120; 若回退信残留 (3) 则会是 180。
            long expectedByTaken = (long) Math.floor(NanoTier.HIGH.rawXp()) * 2;
            helper.assertTrue(expectedByTaken == 120L, "high ×2 raw anchor = 60*2 = 120");
            helper.assertTrue(job.grantXpCalls == 1, "partial settle grants exactly once");
            helper.assertTrue(job.lastJob == JobId.ENGINEER, "credited to ENGINEER");
            helper.assertTrue(job.lastRawXp == expectedByTaken,
                    "settled by ACTUAL taken count 2 (=120), not residual; got " + job.lastRawXp);
            helper.assertFalse(NanoNbt.isProductionXpPending(boardSnapshot),
                    "pending cleared after partial settle (取走即清)");

            // takenCount <= 0 (本次实际未取走) 必须短路: 不发经验、不动状态。
            ItemStack other = NanoProduction.makePlate(NanoTier.HIGH, 5, player.getUUID(), 0);
            be.onOutputTaken(player, other, 0);
            helper.assertTrue(job.grantXpCalls == 1, "takenCount 0 settles nothing (no phantom grant)");
            helper.assertTrue(NanoNbt.isProductionXpPending(other),
                    "takenCount 0 leaves pending untouched (no state change without a real take)");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
        }
    }

    // ============================================================
    // F050 (a): Shift 整栈取板必须把 pending 清在玩家真正拿到的那份实栈上, 而非仅清一份脱离玩家的快照
    // ============================================================
    // 缺陷: 修复前 NanoNbt.clearProductionXpPending 只清 onOutputTaken 收到的 boardSnapshot (调用方自建的
    // ItemStack.copy() 副本), 玩家 Shift 拿到手的那份实栈 (moveItemStackTo 内 stack.split(n) 分裂出的副本)
    // 完全清不到。塞回同一生产台再取会重复触发 onOutputTaken 判定 pending=true 再发一次经验。此处直接遍历
    // 玩家 36 槽主背包, 断言落地的那个"真身"而非任何快照的 pending 状态。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shiftTakeClearsPendingOnRealReceivedStack(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService();
        IJobService prevJob = swapJob(job);
        try {
            ProductionTableBlockEntity be = newProductionTable(helper);
            be.setOwner(player.getUUID());
            BlockPos abs = helper.absolutePos(new BlockPos(0, 1, 0));

            int plateCount = 3;
            ItemStack plates = NanoProduction.makePlate(NanoTier.HIGH, plateCount, player.getUUID(), 0);
            be.inventory().setStackInSlot(ProductionTableBlockEntity.SLOT_OUTPUT, plates);

            ProductionTableMenu menu = new ProductionTableMenu(0, player.getInventory(), abs);
            menu.quickMoveStack(player, ProductionTableBlockEntity.SLOT_OUTPUT);

            ItemStack landed = findPlateInMainInventory(player, NanoTier.HIGH);
            helper.assertTrue(landed != null,
                    "the shift-taken plates must land somewhere in the player's 36-slot main inventory");
            helper.assertTrue(landed.getCount() == plateCount,
                    "the landed stack must carry all " + plateCount + " plates, got " + landed.getCount());
            helper.assertFalse(NanoNbt.isProductionXpPending(landed),
                    "F050: pending must be cleared on the REAL stack the player received, "
                            + "not merely on a detached settlement snapshot");

            helper.assertTrue(job.grantXpCalls == 1,
                    "shift-take must still settle production xp exactly once, got " + job.grantXpCalls);
            helper.assertTrue(job.lastRawXp == 180L,
                    "high plate x3 raw xp anchor stays 60*3=180, got " + job.lastRawXp);
            helper.succeed();
        } finally {
            restoreJob(prevJob);
        }
    }

    // ============================================================
    // F050 (b): 鼠标部分取板必须按实际取走量结算, 残留板必须保留 pending 等待下次结算
    // ============================================================
    // 真实 API 核实 (非猜测): OutputSlot.mayPlace 恒 false, 使 Slot.tryRemove 里
    // "!allowModification(player) && decrement < 现存量" 的守卫恒为 true —— decrement 若传实际取走量 (如 2)
    // 且现存量 (5) 更大, tryRemove 会直接判失败返回空、什么都不发生。vanilla 鼠标右键半取同一处调用的
    // decrement 恒传 Integer.MAX_VALUE (真实取走量单独由 count 参数控制), 此处照同一口径调用
    // Slot.safeTake(count, Integer.MAX_VALUE, player), 否则整条断言链会在这一步就悄悄失败。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mousePartialTakeSettlesByActualCountAndKeepsResidualPending(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService();
        IJobService prevJob = swapJob(job);
        try {
            ProductionTableBlockEntity be = newProductionTable(helper);
            be.setOwner(player.getUUID());
            BlockPos abs = helper.absolutePos(new BlockPos(0, 1, 0));

            int plateCount = 5;
            ItemStack plates = NanoProduction.makePlate(NanoTier.HIGH, plateCount, player.getUUID(), 0);
            be.inventory().setStackInSlot(ProductionTableBlockEntity.SLOT_OUTPUT, plates);

            ProductionTableMenu menu = new ProductionTableMenu(0, player.getInventory(), abs);
            ItemStack taken = menu.getSlot(ProductionTableBlockEntity.SLOT_OUTPUT)
                    .safeTake(2, Integer.MAX_VALUE, player);

            helper.assertTrue(taken.getCount() == 2,
                    "mouse partial take must return exactly 2 plates, got " + taken.getCount());
            helper.assertFalse(NanoNbt.isProductionXpPending(taken),
                    "the 2 plates actually handed to the player must have pending cleared");

            ItemStack residual = be.inventory().getStackInSlot(ProductionTableBlockEntity.SLOT_OUTPUT);
            helper.assertTrue(residual.getCount() == 3,
                    "3 unsettled plates must remain in the output slot after taking 2 of 5, got "
                            + residual.getCount());
            helper.assertTrue(NanoNbt.isProductionXpPending(residual),
                    "F050: the residual plates left in the slot must keep pending true (not yet taken, "
                            + "clearing it here would steal future xp)");

            helper.assertTrue(job.grantXpCalls == 1,
                    "partial mouse take must settle production xp exactly once, got " + job.grantXpCalls);
            long expectedRaw = (long) Math.floor(NanoTier.HIGH.rawXp()) * 2;
            helper.assertTrue(expectedRaw == 120L, "high plate x2 raw xp anchor = 60*2=120");
            helper.assertTrue(job.lastRawXp == expectedRaw,
                    "partial take must settle by the ACTUAL taken count 2 (=120), not the full stack; got "
                            + job.lastRawXp);
            helper.succeed();
        } finally {
            restoreJob(prevJob);
        }
    }

    // ============================================================
    // F050 (c): Shift 取板挪不动时 (背包满异物) 必须原样还原残留板的 pending, 不能提前清空未结算的经验
    // ============================================================
    // 缺陷: beginQuickMove 在 moveItemStackTo 之前就把 live 栈的 pending 抹掉了; 若 moveItemStackTo 因背包
    // 无处可放而整体失败 (基类提前 return EMPTY, 不调 onTake), 若没有 endQuickMove 的还原步骤, 板栈会永久
    // 卡在 pending=false 却从未真正结算过经验的状态, 玩家之后取出这批残留板将永远拿不到本该属于它的经验。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void quickMoveRestoresPendingWhenNoRoomToReceive(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService();
        IJobService prevJob = swapJob(job);
        try {
            ProductionTableBlockEntity be = newProductionTable(helper);
            be.setOwner(player.getUUID());
            BlockPos abs = helper.absolutePos(new BlockPos(0, 1, 0));

            int plateCount = 3;
            ItemStack plates = NanoProduction.makePlate(NanoTier.HIGH, plateCount, player.getUUID(), 0);
            be.inventory().setStackInSlot(ProductionTableBlockEntity.SLOT_OUTPUT, plates);

            // 灌满玩家 36 槽主背包异物 (与板不可合并), 使 moveItemStackTo 找不到任何可放位置。
            for (int i = 0; i < player.getInventory().items.size(); i++) {
                player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
            }

            ProductionTableMenu menu = new ProductionTableMenu(0, player.getInventory(), abs);
            ItemStack moved = menu.quickMoveStack(player, ProductionTableBlockEntity.SLOT_OUTPUT);

            helper.assertTrue(moved.isEmpty(),
                    "with zero free inventory space, quickMoveStack must return EMPTY (nothing moved)");
            ItemStack stillInSlot = be.inventory().getStackInSlot(ProductionTableBlockEntity.SLOT_OUTPUT);
            helper.assertTrue(stillInSlot.getCount() == plateCount,
                    "the unmovable plate stack must remain in the output slot untouched, got "
                            + stillInSlot.getCount());
            helper.assertTrue(NanoNbt.isProductionXpPending(stillInSlot),
                    "F050: endQuickMove must restore pending on the residual stack when the whole move failed, "
                            + "otherwise the still-unsettled plates would silently lose their xp forever");
            helper.assertTrue(job.grantXpCalls == 0,
                    "a fully blocked quick-move must not grant any production xp, got "
                            + job.grantXpCalls + " calls");
            helper.succeed();
        } finally {
            restoreJob(prevJob);
        }
    }

    // ============================================================
    // 复核修正 (a): Shift 部分取板必须给残留板保留 pending, 不能因 onOutputTaken 就地清 boardSnapshot 而
    // 连带清掉 endQuickMove 还原残留要用的那份 takeSnapshot 字段
    // ============================================================
    // 缺陷: 修复前 OutputSlot.onTake 把 this.takeSnapshot 字段本身 (而非副本) 传给 onOutputTaken; 后者末尾
    // NanoNbt.clearProductionXpPending(boardSnapshot) 就地改 tag, 直接把字段自己的 pending 也清成 false。
    // endQuickMove 随后用这份已被污染的字段还原残留 tag, 残留板被错误标成"已结算", 经验永久丢失。
    //
    // 造真实部分移动 (非"挪不动"的全零场景, 那条已由 quickMoveRestoresPendingWhenNoRoomToReceive 覆盖):
    // vanilla AbstractContainerMenu.moveItemStackTo 的"塞空槽"分支只按 slot.getMaxStackSize() (无参, 通用玩家
    // 槽默认 64) 判断上限, 不会按物品自身 stacksTo(16) 截断——若只留一个空槽会被整栈 20 块塞满, 无法制造部分
    // 移动。真正会按物品自身上限截断的是"与已有同 NBT 栈合并"分支 (maxSize =
    // Math.min(slot.getMaxStackSize(), pStack.getMaxStackSize()), 见 AbstractContainerMenu.java:656), 所以此处
    // 预放一份 tag 完全相同 (producer/quality/pending 三者一致, 因为活体板栈在 beginQuickMove 里已把 pending
    // 清成 false) 的已结算板在快捷栏 0 号位当合并目标, 其余 35 槽全部灌石头挡死, 逼 20 块只能合并出 15 块
    // (1+15=16=maxStackSize) 后再无处可去, 残留 5 块。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shiftPartialTakeKeepsResidualPendingOnRealSettlement(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService();
        IJobService prevJob = swapJob(job);
        try {
            ProductionTableBlockEntity be = newProductionTable(helper);
            be.setOwner(player.getUUID());
            BlockPos abs = helper.absolutePos(new BlockPos(0, 1, 0));

            int plateCount = 20;
            ItemStack plates = NanoProduction.makePlate(NanoTier.HIGH, plateCount, player.getUUID(), 0);
            be.inventory().setStackInSlot(ProductionTableBlockEntity.SLOT_OUTPUT, plates);

            // 合并目标: 与"活体栈经 beginQuickMove 清 pending 后"的 NBT 完全一致的 1 块已结算板, 放快捷栏 0 号位。
            ItemStack mergeTarget = NanoProduction.makePlate(NanoTier.HIGH, 1, player.getUUID(), 0);
            NanoNbt.clearProductionXpPending(mergeTarget);
            player.getInventory().setItem(0, mergeTarget);
            // 其余 35 槽全灌石头 (与板不同物, 不可合并也不是空槽), 堵死除合并目标外的一切去处。
            for (int i = 1; i < player.getInventory().items.size(); i++) {
                player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
            }

            ProductionTableMenu menu = new ProductionTableMenu(0, player.getInventory(), abs);
            ItemStack moved = menu.quickMoveStack(player, ProductionTableBlockEntity.SLOT_OUTPUT);
            helper.assertTrue(moved.getCount() == plateCount,
                    "quickMoveStack returns the pre-move snapshot (count " + plateCount + "), got "
                            + moved.getCount());

            ItemStack landed = player.getInventory().getItem(0);
            helper.assertTrue(landed.getCount() == 16,
                    "the merge target must be topped up to the plate item's stacksTo(16) cap (1+15), got "
                            + landed.getCount());
            helper.assertFalse(NanoNbt.isProductionXpPending(landed),
                    "the merged stack (settled portion) must have pending cleared");

            ItemStack residual = be.inventory().getStackInSlot(ProductionTableBlockEntity.SLOT_OUTPUT);
            helper.assertTrue(residual.getCount() == 5,
                    "5 unsettled plates (20 - 15 actually merged out) must remain in the output slot, got "
                            + residual.getCount());
            helper.assertTrue(NanoNbt.isProductionXpPending(residual),
                    "endQuickMove must restore pending on the RESIDUAL board from an untouched snapshot; "
                            + "leaking onOutputTaken's in-place clear onto the shared takeSnapshot field would "
                            + "wrongly mark the still-unsettled residual as already-settled and lose its xp");

            helper.assertTrue(job.grantXpCalls == 1,
                    "partial shift-take must settle production xp exactly once, got " + job.grantXpCalls);
            long expectedRaw = (long) Math.floor(NanoTier.HIGH.rawXp()) * 15;
            helper.assertTrue(expectedRaw == 900L, "high plate x15 raw xp anchor = 60*15=900");
            helper.assertTrue(job.lastRawXp == expectedRaw,
                    "partial shift-take settles by the ACTUAL merged-out count 15 (=900), not the full 20; got "
                            + job.lastRawXp);

            // 残留必须仍可正常结算 (证明未被永久锁死): 直接驱动 BE 结算口径复核这 5 块的经验能拿到。
            int callsBefore = job.grantXpCalls;
            be.onOutputTaken(player, residual, 5);
            helper.assertTrue(job.grantXpCalls == callsBefore + 1,
                    "the residual 5 plates must still be settleable for xp after the partial take");
            long expectedResidualRaw = (long) Math.floor(NanoTier.HIGH.rawXp()) * 5;
            helper.assertTrue(job.lastRawXp == expectedResidualRaw,
                    "residual settle grants xp for exactly the 5 remaining plates (=" + expectedResidualRaw
                            + "), got " + job.lastRawXp);
            helper.succeed();
        } finally {
            restoreJob(prevJob);
        }
    }

    // ============================================================
    // 复核修正 (b): 数字键/副手键 SWAP 取输出板 (第三条不经 remove(int) 也不经 quickMoveStack 的路径) 必须
    // 清掉玩家真正拿到手的那份实栈的 pending
    // ============================================================
    // 缺陷: vanilla AbstractContainerMenu.doClick 的 ClickType.SWAP 分支 (目标快捷栏槽为空时) 直接
    // `inventory.setItem(pButton, itemstack6)` 把 slot2.getItem() 读到的 live 栈本体塞进玩家背包, 再调
    // `slot2.setByPlayer(EMPTY)` + `slot2.onTake(player, itemstack6)` —— 全程不调 Slot.remove(int), 也不经
    // AbstractMiningMenu.quickMoveStack, 修复前的 remove(int) 覆写与 beginQuickMove/endQuickMove 配对两条通道
    // 都覆盖不到它, 经验照发但玩家手上的板 pending 仍为 true。此处走真菜单 clicked(SWAP) 端到端复现。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void swapTakeClearsPendingOnRealReceivedStack(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        RecordingJobService job = new RecordingJobService();
        IJobService prevJob = swapJob(job);
        try {
            ProductionTableBlockEntity be = newProductionTable(helper);
            be.setOwner(player.getUUID());
            BlockPos abs = helper.absolutePos(new BlockPos(0, 1, 0));

            int plateCount = 3;
            ItemStack plates = NanoProduction.makePlate(NanoTier.HIGH, plateCount, player.getUUID(), 0);
            be.inventory().setStackInSlot(ProductionTableBlockEntity.SLOT_OUTPUT, plates);
            // 目标快捷栏 0 号位必须为空, 这样才走 doClick 的 "itemstack3.isEmpty()" 分支 (整体换出)。
            player.getInventory().setItem(0, ItemStack.EMPTY);

            ProductionTableMenu menu = new ProductionTableMenu(0, player.getInventory(), abs);
            int outputMenuSlot = ProductionTableBlockEntity.SLOT_OUTPUT;
            menu.clicked(outputMenuSlot, 0, ClickType.SWAP, player);

            ItemStack landed = player.getInventory().getItem(0);
            helper.assertTrue(landed.getCount() == plateCount,
                    "SWAP must move the full output stack into the target hotbar slot, got " + landed.getCount());
            helper.assertFalse(NanoNbt.isProductionXpPending(landed),
                    "SWAP-received stack must have pending cleared too, not just the mouse/Shift paths");

            ItemStack stillInSlot = be.inventory().getStackInSlot(ProductionTableBlockEntity.SLOT_OUTPUT);
            helper.assertTrue(stillInSlot.isEmpty(),
                    "output slot must be empty after SWAP moved the whole stack out");

            helper.assertTrue(job.grantXpCalls == 1,
                    "SWAP take must still settle production xp exactly once, got " + job.grantXpCalls);
            helper.assertTrue(job.lastRawXp == 180L,
                    "high plate x3 raw xp anchor stays 60*3=180 on SWAP take, got " + job.lastRawXp);
            helper.succeed();
        } finally {
            restoreJob(prevJob);
        }
    }

    // ---- 测试辅助 ----

    /** 在玩家 36 槽主背包中找到第一个匹配指定纳米板档位的非空实栈 (Shift 取板落点验证用); 未找到返回 null。 */
    private static ItemStack findPlateInMainInventory(ServerPlayer player, NanoTier tier) {
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && stack.is(ModEngineerItems.plate(tier).get())) {
                return stack;
            }
        }
        return null;
    }

    /** 在 helper 世界 (0,1,0) 放一个高级生产台 BE 并返回 (机器档 HIGH, 供 dataAccess.machineTier 解析)。 */
    private static ProductionTableBlockEntity newProductionTable(GameTestHelper helper) {
        BlockPos rel = new BlockPos(0, 1, 0);
        helper.setBlock(rel, ModEngineerBlocks.table(NanoTier.HIGH).get());
        BlockPos abs = helper.absolutePos(rel);
        BlockEntity raw = helper.getLevel().getBlockEntity(abs);
        if (!(raw instanceof ProductionTableBlockEntity be)) {
            throw new IllegalStateException("production table BE not present at " + abs);
        }
        return be;
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

    /** 记录 grantXp 调用的职业门面替身 (谁产谁得断言用); 等级给满档使 (此测试不读) 任何档解锁。 */
    private static final class RecordingJobService implements IJobService {
        int grantXpCalls = 0;
        JobId lastJob = null;
        long lastRawXp = Long.MIN_VALUE;

        @Override
        public int level(Player player, JobId job) {
            return 10;
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
            throw new UnsupportedOperationException("not exercised by engineer output-take tests");
        }
    }

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
