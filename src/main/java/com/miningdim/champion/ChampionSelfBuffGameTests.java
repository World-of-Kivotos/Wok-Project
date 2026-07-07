package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 精英怪【自身被动词条】(Stage2 批1: 再生组织/易燃再生/反震反伤/高速移动) 纯逻辑 GameTest
 * (ChampionStarAffix spec 7.1/7.3 TDD)。
 *
 * 严禁触 Champions 加载路径 (compileOnly 铁律): 只断言 {@link ChampionSelfBuffValues} 数值折算 + 触发门槛,
 * 与 {@link AffixRoller} 扩容白名单后 roll 仍产合法集合 (每条 ∈ IMPLEMENTED_AFFIXES + {@link PointBudget} 终校验过)。
 * 所有断言为具体业务结果 (删被测折算/门槛/白名单必挂)。真服 (Champions 已加载) 由 {@code ChampionSelfEffectHandler}
 * 每秒扫近玩家冠军施加回血/移速 + 受击点反伤。
 *
 * template = "empty", batch = "champion_selfbuff"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionSelfBuffGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_selfbuff";
    private static final double EPS = 1e-6D;

    // ============================================================
    // 数值折算 (再生 %maxHP·s / FLAT·s / 移速系数 / 反震名义反伤)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void regenTissueScalesWithMaxHp(GameTestHelper helper) {
        // 再生组织 RARE = 0.05/s; 1000 有效血 -> 50 HP/s。
        double heal = ChampionSelfBuffValues.regenTissueHealPerSecond(AffixQuality.RARE, 1000.0D);
        helper.assertTrue(Math.abs(heal - 50.0D) < EPS, "再生组织 RARE 0.05 x 1000 = 50 HP/s");
        // 闪耀 = 0.08/s; 73000 世界BOSS -> 5840 HP/s (仅脱战触发)。
        double legendary = ChampionSelfBuffValues.regenTissueHealPerSecond(AffixQuality.LEGENDARY, 73000.0D);
        helper.assertTrue(Math.abs(legendary - 5840.0D) < EPS, "再生组织 闪耀 0.08 x 73000 = 5840 HP/s");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void flammableRegenIsFlatPerSecond(GameTestHelper helper) {
        // 易燃再生 FLAT: 普通 8 / 高级 30 / 闪耀 90 HP/s (不随血量缩放)。
        helper.assertTrue(ChampionSelfBuffValues.flammableRegenHealPerSecond(AffixQuality.COMMON) == 8.0D,
                "易燃再生 普通 = 8 HP/s FLAT");
        helper.assertTrue(ChampionSelfBuffValues.flammableRegenHealPerSecond(AffixQuality.RARE) == 30.0D,
                "易燃再生 高级 = 30 HP/s FLAT");
        helper.assertTrue(ChampionSelfBuffValues.flammableRegenHealPerSecond(AffixQuality.LEGENDARY) == 90.0D,
                "易燃再生 闪耀 = 90 HP/s FLAT");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sprintBonusPerQuality(GameTestHelper helper) {
        helper.assertTrue(Math.abs(ChampionSelfBuffValues.sprintSpeedBonus(AffixQuality.COMMON) - 0.10D) < EPS,
                "高速移动 普通 = +10%");
        helper.assertTrue(Math.abs(ChampionSelfBuffValues.sprintSpeedBonus(AffixQuality.LEGENDARY) - 0.40D) < EPS,
                "高速移动 闪耀 = +40%");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void thornsReflectLocksToAttackerMaxHp(GameTestHelper helper) {
        // 反震 RARE = 5%; 攻击者 80 血 -> 名义反伤 4.0 HP (未经秒窗封顶)。
        double raw = ChampionSelfBuffValues.thornsReflectRaw(AffixQuality.RARE, 80.0D);
        helper.assertTrue(Math.abs(raw - 4.0D) < EPS, "反震 RARE 0.05 x 攻击者 80 血 = 4.0 名义反伤");
        // 攻击者血越高名义反伤越高 (锁攻击者 maxHP): 200 血 -> 10.0。
        double bigger = ChampionSelfBuffValues.thornsReflectRaw(AffixQuality.RARE, 200.0D);
        helper.assertTrue(Math.abs(bigger - 10.0D) < EPS, "反震名义反伤锁攻击者 maxHP: 200 血 -> 10.0");
        helper.succeed();
    }

    // ============================================================
    // 触发门槛 (脱战窗 / 受伤停回窗 / 反伤内 CD; 含 Long.MIN_VALUE 从未态)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void regenGatesRespectWindows(GameTestHelper helper) {
        // 再生组织脱战窗 100tick: 距上次受伤恰 100 -> 脱战; 99 -> 未脱战; 从未受伤 -> 脱战。
        helper.assertTrue(ChampionSelfBuffValues.isOutOfCombat(200L, 100L), "距受伤 100tick = 脱战");
        helper.assertTrue(!ChampionSelfBuffValues.isOutOfCombat(200L, 101L), "距受伤 99tick != 脱战");
        helper.assertTrue(ChampionSelfBuffValues.isOutOfCombat(200L, Long.MIN_VALUE), "从未受伤 = 脱战");

        // 易燃再生停回窗 30tick: 恰 30 -> 可回; 29 -> 停回。
        helper.assertTrue(ChampionSelfBuffValues.flammableRegenReady(200L, 170L), "距受伤 30tick = 可回");
        helper.assertTrue(!ChampionSelfBuffValues.flammableRegenReady(200L, 171L), "距受伤 29tick = 停回");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void championThornsDamageTypeIsTrueDamage(GameTestHelper helper) {
        // 反震真伤口径 (2026-07-07 用户定向): champion_thorns 伤害类型须注册且入 bypasses_armor +
        // bypasses_enchantments 标签 (删 damage_type JSON 或标签条目 -> 此测试必挂, 反伤退化回被护甲吃掉)。
        Registry<DamageType> reg = helper.getLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        Holder<DamageType> holder = reg.getHolderOrThrow(ChampionDamageTypes.CHAMPION_THORNS);
        helper.assertTrue(holder.is(DamageTypeTags.BYPASSES_ARMOR),
                "champion_thorns must bypass armor (true damage; nominal % is what lands)");
        helper.assertTrue(holder.is(DamageTypeTags.BYPASSES_ENCHANTMENTS),
                "champion_thorns must bypass protection enchants");
        // 保守边界: 不入 bypasses_invulnerability (仍受无敌帧管辖, 非无限穿透; spec 真伤保守铁律)。
        helper.assertTrue(!holder.is(DamageTypeTags.BYPASSES_INVULNERABILITY),
                "champion_thorns must NOT bypass invulnerability frames");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void thornsInternalCooldownGate(GameTestHelper helper) {
        // 反震内 CD 60tick: 恰 60 -> 就绪; 59 -> CD 内; 从未反伤 -> 就绪。
        helper.assertTrue(ChampionSelfBuffValues.thornsReady(200L, 140L), "距反伤 60tick = 就绪");
        helper.assertTrue(!ChampionSelfBuffValues.thornsReady(200L, 141L), "距反伤 59tick = CD 内");
        helper.assertTrue(ChampionSelfBuffValues.thornsReady(200L, Long.MIN_VALUE), "从未反伤 = 就绪");
        helper.succeed();
    }

    // ============================================================
    // 白名单扩容后 roll 合法性 (批1 4 词条已移入 IMPLEMENTED_AFFIXES)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void batch1AffixesInWhitelist(GameTestHelper helper) {
        helper.assertTrue(AffixRoller.IMPLEMENTED_AFFIXES.contains(AffixDef.REGEN_TISSUE), "再生组织 已移入白名单");
        helper.assertTrue(AffixRoller.IMPLEMENTED_AFFIXES.contains(AffixDef.FLAMMABLE_REGEN), "易燃再生 已移入白名单");
        helper.assertTrue(AffixRoller.IMPLEMENTED_AFFIXES.contains(AffixDef.THORNS), "反震 已移入白名单");
        helper.assertTrue(AffixRoller.IMPLEMENTED_AFFIXES.contains(AffixDef.SPRINT), "高速移动 已移入白名单");
        // 巨大化批2 已接 spawn 血池模型 (ChampionHpConversion.sizeMultiplier), 已移入白名单。
        helper.assertTrue(AffixRoller.IMPLEMENTED_AFFIXES.contains(AffixDef.GIGANTISM), "巨大化 批2 已移入白名单");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rollProducesOnlyWhitelistedLegalSets(GameTestHelper helper) {
        // 扩容白名单后, 各星 roll 出的每条词条须 ∈ IMPLEMENTED_AFFIXES 且整组 allocate 不抛 (SPRINT 首次进 roll,
        // 激活机动池跨池互斥路径; 反震/再生纯生存池)。
        int[] stars = {3, 5, 7, 10};
        for (int star : stars) {
            StarRank rank = StarRank.ofStar(star);
            for (long seed = 0; seed < 12; seed++) {
                RandomSource rng = RandomSource.create(seed * 31L + star);
                List<AffixSelection> rolled = AffixRoller.roll(rank, rng);
                for (AffixSelection sel : rolled) {
                    helper.assertTrue(AffixRoller.IMPLEMENTED_AFFIXES.contains(sel.affix()),
                            "star " + star + " rolled non-whitelisted " + sel.affix());
                }
                // 终校验必过 (roller 只产合法集合)。
                PointBudget.allocate(rank, rolled);
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gigantismMutexBlocksSprint(GameTestHelper helper) {
        // 巨大化 互斥全部机动: 巨大化 + 高速移动 同装须被 PointBudget 拒 (spec 第八章)。SPRINT 进白名单后此路径真被激活,
        // 断言互斥仍守住 (删 validateMutex 的巨大化×机动分支必挂)。
        List<AffixSelection> illegal = new ArrayList<>();
        illegal.add(new AffixSelection(AffixDef.GIGANTISM, AffixQuality.COMMON));
        illegal.add(new AffixSelection(AffixDef.SPRINT, AffixQuality.COMMON));
        boolean rejected = false;
        try {
            PointBudget.allocate(StarRank.ofStar(5), illegal);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "巨大化 + 高速移动 须被互斥校验拒 (巨大化互斥全部机动)");
        helper.succeed();
    }
}
