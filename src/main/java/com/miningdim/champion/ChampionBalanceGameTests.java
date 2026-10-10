package com.miningdim.champion;

import com.miningdim.champion.ChampionDamageReduction.DamageCategory;
import com.miningdim.champion.aggregate.RetaliationAggregator;
import com.miningdim.champion.bloodpool.BloodPool;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.champion.integration.ChampionBloodPoolHandler;
import com.miningdim.champion.integration.ChampionPromoter;
import com.miningdim.champion.integration.ChampionSelfEffectHandler;
import com.miningdim.champion.integration.TaczAttachmentExplosionProbe;
import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 精英批次 (2026-09 调参方案 A1-A6 + B1-B3 + C2) 回归: 星表按约 4 人小队重标定后的血池分界、易燃再生停回 3s、
 * 6★+ 穿甲段分流、每矿区世界 BOSS 同时存活上限、复合装甲同 tick 同类别只叠 1 层、48 格扫描外照常结算自身效果、
 * 反震 5 格半径、配件开启的子弹爆炸归子弹桶。
 *
 * 纯逻辑断言直接调 {@link ChampionDamageReduction} / {@link CompositeArmorRampTracker} / {@link ChampionSpawnPolicy} /
 * {@link ChampionSelfBuffValues}; 实体级断言走真实冠军实体 (尸壳: 不会在日光下自燃, 免得着火伤害混进受击序列)。
 * dev GameTest 不加载 TaCZ, TaCZ 的 tacz:bullet* 伤害类型用独立 Holder.Reference 构造 (只绑 ResourceKey, 受击
 * handler 只读 key 与标签), 直接喂给一个未注册的 {@link ChampionBloodPoolHandler} 实例, 不经事件总线。
 *
 * template = "empty", batch = "champion_balance"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionBalanceGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_balance";
    private static final double EPS = 1e-6D;

    // ============================================================
    // B1-B3 星表: 4 人小队标定 + 血池分界
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void starTableFollowsFourPlayerCalibration(GameTestHelper helper) {
        // 1-2★ 保持旧值 (用户拍板: 给还没有枪的新手留低血), 3-5★ 按单人 "N★ 约打 N 匣" 反推。
        helper.assertTrue(StarRank.STAR_1.baseEffectiveHp() == 135.0D && StarRank.STAR_2.baseEffectiveHp() == 225.0D,
                "1-2★ 基础血保持 135 / 225");
        helper.assertTrue(StarRank.STAR_3.baseEffectiveHp() == 920.0D
                        && StarRank.STAR_4.baseEffectiveHp() == 1_050.0D
                        && StarRank.STAR_5.baseEffectiveHp() == 1_170.0D,
                "3-5★ 基础血 920 / 1050 / 1170");

        // 6-10★ 标定式回代: 基础血 ≈ 58.65 x 4 人 x 0.6 x 目标墙钟 T ÷ 典型换算比 (spec 6.1)。58.65 = 普通 M4A1
        // 8 伤 x 30 发 ÷ (30 x 60/810 + 1.87s 战术换弹); 换算比是穿甲分流等接口修正后典型有效血 / 基础血的模拟值。
        // 改星表不重标定 (或反之) 本条即挂; 容差 1.5% 吸收取整到百位与模拟噪声。
        double sustainedDps = 8.0D * 30.0D / (30.0D * 60.0D / 810.0D + 1.87D);
        double[] targetWallClock = {25.0D, 56.0D, 100.0D, 165.0D, 270.0D};
        double[] typicalRatio = {0.636D, 0.732D, 0.899D, 0.993D, 1.214D};
        for (int i = 0; i < 5; i++) {
            StarRank rank = StarRank.ofStar(6 + i);
            double calibrated = sustainedDps * 4.0D * 0.6D * targetWallClock[i] / typicalRatio[i];
            double relative = Math.abs(rank.baseEffectiveHp() - calibrated) / calibrated;
            helper.assertTrue(relative < 0.015D, (6 + i) + "★ 基础血 " + rank.baseEffectiveHp()
                    + " 与标定式 " + Math.round(calibrated) + " 偏差 " + relative + " 超过 1.5%");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void lowStarBloodPoolFollowsConvertedHpNotStar(GameTestHelper helper) {
        // 4-5★ 基础血已过 1024: 裸怪建池; 花点买词条后有效血回落到 1024 以内的仍走 vanilla。6★ 起按星级恒建池。
        Map<AffixDef, AffixQuality> regen = Map.of(AffixDef.REGEN_TISSUE, AffixQuality.COMMON);
        double bare4 = ChampionHpConversion.convertedEffectiveHp(StarRank.STAR_4, Map.of());
        double regen4 = ChampionHpConversion.convertedEffectiveHp(StarRank.STAR_4, regen);
        double expectedRegen4 = 1_050.0D * (0.35D + 0.65D * Math.pow(49.0D / 55.0D, 1.5D));
        helper.assertTrue(Math.abs(regen4 - expectedRegen4) < EPS && regen4 > 941.0D && regen4 < 942.0D,
                "4★ + 再生组织普通 = 1050 x 0.8966 ≈ 941.4, 实得 " + regen4);
        helper.assertTrue(ChampionPromoter.requiresBloodPool(StarRank.STAR_4, bare4), "4★ 裸怪 1050 破 1024 建池");
        helper.assertTrue(!ChampionPromoter.requiresBloodPool(StarRank.STAR_4, regen4), "4★ + 再生组织 941 不建池");
        helper.assertTrue(ChampionPromoter.requiresBloodPool(StarRank.STAR_5,
                        ChampionHpConversion.convertedEffectiveHp(StarRank.STAR_5, Map.of())),
                "5★ 裸怪 1170 破 1024 建池");
        helper.assertTrue(!ChampionPromoter.requiresBloodPool(StarRank.STAR_3,
                        ChampionHpConversion.convertedEffectiveHp(StarRank.STAR_3, Map.of())),
                "3★ 裸怪 920 不建池");
        helper.assertTrue(ChampionPromoter.requiresBloodPool(StarRank.STAR_6,
                        ChampionHpConversion.convertedEffectiveHp(StarRank.STAR_6, Map.of(
                                AffixDef.COMPOSITE_ARMOR, AffixQuality.RARE))),
                "6★ 按星级恒建池");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gigantismKeepsNominalBonusOnRecalibratedTable(GameTestHelper helper) {
        // ChampionHpConversion 类注释、spec 6.1 与玩家手册 2.3 引用的数字按新星表钉死: 巨大化点数豁免换血惩罚, 恒为
        // 名义 +X%。10★ 闪耀 = 31,400 x 2.8 = 87,920 (≈87,900; 旧表 73,000 时 ≈204,400)。若体型点数重新计入惩罚,
        // 闪耀花 78 点 -> 31,400 x 0.835 x 2.8 ≈ 73,400, 本条即挂。
        double giant10 = ChampionHpConversion.convertedEffectiveHp(StarRank.STAR_10,
                Map.of(AffixDef.GIGANTISM, AffixQuality.LEGENDARY));
        helper.assertTrue(Math.abs(giant10 - 31_400.0D * 2.8D) < EPS && Math.round(giant10 / 100.0D) == 879L,
                "10★ 巨大化闪耀 = 31400 x 2.8 ≈ 87,900, 实得 " + giant10);

        // 3★ 巨大化 (普通 920 x 1.3 = 1196 / 中级 920 x 1.5 = 1380) 换算后破 1024: 1-5★ 按换算血建池 (手册 1.1 / 2.3)。
        double giant3Common = ChampionHpConversion.convertedEffectiveHp(StarRank.STAR_3,
                Map.of(AffixDef.GIGANTISM, AffixQuality.COMMON));
        double giant3Uncommon = ChampionHpConversion.convertedEffectiveHp(StarRank.STAR_3,
                Map.of(AffixDef.GIGANTISM, AffixQuality.UNCOMMON));
        helper.assertTrue(Math.abs(giant3Common - 1_196.0D) < EPS && Math.abs(giant3Uncommon - 1_380.0D) < EPS,
                "3★ 巨大化 普通 1196 / 中级 1380, 实得 " + giant3Common + " / " + giant3Uncommon);
        helper.assertTrue(ChampionPromoter.requiresBloodPool(StarRank.STAR_3, giant3Common)
                        && ChampionPromoter.requiresBloodPool(StarRank.STAR_3, giant3Uncommon),
                "3★ 巨大化换算后破 1024 建池");
        helper.succeed();
    }

    // ============================================================
    // A1 易燃再生停回 3s
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void flammableRegenPauseCoversReloadGap(GameTestHelper helper) {
        helper.assertTrue(ChampionSelfBuffValues.FLAMMABLE_REGEN_PAUSE_TICKS == 60L
                        && ChampionSelfBuffValues.FLAMMABLE_REGEN_PAUSE_TICKS
                        == ChampionDamageReduction.COMPOSITE_RAMP_RESET_TICKS,
                "易燃再生停回 60 tick, 与复合装甲无伤重置窗对齐");
        // M4A1 战术换弹约 1.94s (39 tick): 旧 30 tick 窗下换弹期间就开始回血, 新窗下不回。
        helper.assertTrue(!ChampionSelfBuffValues.flammableRegenReady(1_000L, 1_000L - 39L),
                "换弹 39 tick 的空窗不再触发易燃再生");
        helper.assertTrue(!ChampionSelfBuffValues.flammableRegenReady(1_000L, 1_000L - 59L), "59 tick 仍停回");
        helper.assertTrue(ChampionSelfBuffValues.flammableRegenReady(1_000L, 1_000L - 60L), "恰 60 tick 可回");
        helper.succeed();
    }

    // ============================================================
    // A2 6★+ 穿甲段分流
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void armorPierceSegmentClassification(GameTestHelper helper) {
        helper.assertTrue(ChampionDamageReduction.ARMOR_PIERCE_AFFIX_BYPASS == 1.0D, "穿甲豁免旋钮默认 1.0");
        helper.assertTrue(ChampionDamageReduction.isArmorPierceSegment("tacz", "bullet_ignore_armor", false),
                "tacz:bullet_ignore_armor 是穿甲段");
        helper.assertTrue(ChampionDamageReduction.isArmorPierceSegment("tacz", "bullet_void_ignore_armor", false),
                "tacz:bullet_void_ignore_armor 是穿甲段");
        helper.assertTrue(!ChampionDamageReduction.isArmorPierceSegment("tacz", "bullet", false),
                "tacz:bullet 普通段不是穿甲段");
        helper.assertTrue(ChampionDamageReduction.isArmorPierceSegment("tacz", "bullet", true),
                "兜底: TACZ 子弹带 bypasses_armor 标签也按穿甲段处理 (改名但保留标签)");
        helper.assertTrue(!ChampionDamageReduction.isArmorPierceSegment("minecraft", "magic", true),
                "兜底只限 TACZ 子弹: 原版魔法伤害即便无视护甲也不是穿甲段");

        helper.assertTrue(ChampionDamageReduction.armorPierceBypassApplies(6, true), "6★ 穿甲段启用豁免");
        helper.assertTrue(ChampionDamageReduction.armorPierceBypassApplies(10, true), "10★ 穿甲段启用豁免");
        helper.assertTrue(!ChampionDamageReduction.armorPierceBypassApplies(5, true), "5★ 不改 (走原版护甲)");
        helper.assertTrue(!ChampionDamageReduction.armorPierceBypassApplies(8, false), "普通段不豁免");
        helper.assertTrue(ChampionDamageReduction.pierceBypassedRate(0.40D, true) == 0.0D,
                "旋钮 1.0: 护甲类减伤率在穿甲段归零");
        helper.assertTrue(ChampionDamageReduction.pierceBypassedRate(0.40D, false) == 0.40D, "未豁免原样返回");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void armorPierceSegmentSkipsArmorAffixesOnSixStarPlus(GameTestHelper helper) {
        // 8★ 复合 EPIC (上限 0.65, 每层 0.13) + 超高分子 EPIC (0.30); 5★ 复合 RARE (0.55, 每层 0.11) + 超高分子 RARE (0.22)。
        Husk eight = spawnChampion(helper, new BlockPos(0, 1, 0), 8, Map.of(
                AffixDef.COMPOSITE_ARMOR, AffixQuality.EPIC, AffixDef.UHMWPE_ARMOR, AffixQuality.EPIC), 10_000.0D, true);
        Husk five = spawnChampion(helper, new BlockPos(3, 1, 0), 5, Map.of(
                AffixDef.COMPOSITE_ARMOR, AffixQuality.RARE, AffixDef.UHMWPE_ARMOR, AffixQuality.RARE), 600.0D, false);
        if (eight == null || five == null) {
            return;
        }
        UUID eightId = eight.getUUID();
        BloodPool pool = BloodPoolRegistry.get(eightId);
        ChampionBloodPoolHandler handler = new ChampionBloodPoolHandler();
        DamageSource normal = taczBulletSource(helper, "bullet");
        DamageSource pierce = taczBulletSource(helper, "bullet_ignore_armor");

        // 本 tick: 8★ 只挨一段穿甲段 -> 三项护甲类减伤全豁免 (keep 1.0), 且不叠复合层。
        double dealtPierce = hitPool(handler, eight, pool, pierce, 100.0F);
        helper.assertTrue(Math.abs(dealtPierce - 100.0D) < 1e-3D,
                "8★ 穿甲段不吃复合/超高分子: 100 伤全额落池, 实得 " + dealtPierce);

        // 5★ 穿甲段照旧吃全部减伤 (1-5★ 不改): 复合 1 层 0.11 + 超高分子 0.22 -> keep 0.89 x 0.78 = 0.6942。
        LivingHurtEvent lowStar = new LivingHurtEvent(five, pierce, 100.0F);
        handler.onLivingHurt(lowStar);
        helper.assertTrue(Math.abs(lowStar.getAmount() - 69.42F) < 1e-3F,
                "5★ 穿甲段照吃护甲类减伤 = 69.42, 实得 " + lowStar.getAmount());

        helper.runAfterDelay(1L, () -> {
            try {
                // 下一 tick 普通段: 上一 tick 的穿甲段若叠过层, 这里会是第 2 层 (0.26); 实为第 1 层 -> 0.87 x 0.70。
                double dealtNormal = hitPool(handler, eight, pool, normal, 100.0F);
                helper.assertTrue(Math.abs(dealtNormal - 60.9D) < 1e-3D,
                        "穿甲段不叠复合层: 下一 tick 普通段是第 1 层 = 60.9, 实得 " + dealtNormal);
                // 同 tick 穿甲段: 只读现有层数, 旋钮 1.0 下仍全额。
                double dealtPierce2 = hitPool(handler, eight, pool, pierce, 100.0F);
                helper.assertTrue(Math.abs(dealtPierce2 - 100.0D) < 1e-3D,
                        "同 tick 穿甲段仍全额, 实得 " + dealtPierce2);
                // 同 tick 再来一段普通段: 同类别同 tick 只叠 1 层, 仍是 60.9。
                double dealtNormal2 = hitPool(handler, eight, pool, normal, 100.0F);
                helper.assertTrue(Math.abs(dealtNormal2 - 60.9D) < 1e-3D,
                        "同 tick 同类别不再叠层, 实得 " + dealtNormal2);
                helper.succeed();
            } finally {
                BloodPoolRegistry.remove(eightId);
            }
        });
    }

    // ============================================================
    // A3 每矿区世界 BOSS 同时存活上限
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void worldBossRollIsCappedPerRegion(GameTestHelper helper) {
        helper.assertTrue(ChampionSpawnPolicy.WORLD_BOSS_MIN_STAR == 8
                        && ChampionSpawnPolicy.MAX_ALIVE_WORLD_BOSS_PER_INSTANCE == 1,
                "世界 BOSS = ≥8★, 每矿区同时 1 只");
        helper.assertTrue(!ChampionSpawnPolicy.isWorldBossStar(7) && ChampionSpawnPolicy.isWorldBossStar(8),
                "7★ 不算世界 BOSS, 8★ 算");

        // 已满额: HARD 全部落回 [5,7], 且 5/6/7 都掷得到。
        RandomSource rng = RandomSource.create(20260927L);
        boolean[] seen = new boolean[11];
        for (int i = 0; i < 3_000; i++) {
            int star = ChampionSpawnPolicy.rollStar(Difficulty.HARD, rng, () -> 1);
            helper.assertTrue(star >= 5 && star <= 7, "满额时 HARD 掷星必须落在 [5,7], 实得 " + star);
            seen[star] = true;
        }
        helper.assertTrue(seen[5] && seen[6] && seen[7], "满额重掷覆盖 5/6/7★");

        // 未满额: 照常掷到 8-10★; 计数只在掷出 ≥8★ 时才取 (惰性), 次数恰等于结果 ≥8★ 的次数。
        AtomicInteger calls = new AtomicInteger();
        int worldBosses = 0;
        for (int i = 0; i < 3_000; i++) {
            int star = ChampionSpawnPolicy.rollStar(Difficulty.HARD, rng, () -> {
                calls.incrementAndGet();
                return 0;
            });
            helper.assertTrue(star >= 5 && star <= 10, "HARD 掷星越界: " + star);
            if (star >= 8) {
                worldBosses++;
            }
        }
        helper.assertTrue(worldBosses > 0, "未满额时仍掷得出世界 BOSS");
        helper.assertTrue(calls.get() == worldBosses,
                "存活计数只在掷出 ≥8★ 时取: 调用 " + calls.get() + " 次, ≥8★ " + worldBosses + " 次");

        // MEDIUM 上限 6★, 永不触发计数。
        AtomicInteger mediumCalls = new AtomicInteger();
        for (int i = 0; i < 500; i++) {
            int star = ChampionSpawnPolicy.rollStar(Difficulty.MEDIUM, rng, () -> {
                mediumCalls.incrementAndGet();
                return 5;
            });
            helper.assertTrue(star >= 3 && star <= 6, "MEDIUM 掷星越界: " + star);
        }
        helper.assertTrue(mediumCalls.get() == 0, "MEDIUM 掷不出世界 BOSS, 不应取存活计数");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void worldBossCountOnlySeesLiveLoadedRegionMembers(GameTestHelper helper) {
        Husk alive8 = spawnChampion(helper, new BlockPos(0, 1, 0), 8, Map.of(), 15_500.0D, true);
        Husk dying9 = spawnChampion(helper, new BlockPos(2, 1, 0), 9, Map.of(), 23_400.0D, true);
        Husk seven = spawnChampion(helper, new BlockPos(4, 1, 0), 7, Map.of(), 10_800.0D, true);
        Husk elsewhere10 = spawnChampion(helper, new BlockPos(6, 1, 0), 10, Map.of(), 31_400.0D, true);
        if (alive8 == null || dying9 == null || seven == null || elsewhere10 == null) {
            return;
        }
        UUID stale = UUID.randomUUID(); // 残留的在册条目: 取不到实体 (模拟区块卸载后留下的池子)。
        BloodPoolRegistry.install(stale, 20_000.0D);
        Set<UUID> region = new HashSet<>(Set.of(alive8.getUUID(), dying9.getUUID(), seven.getUUID(), stale));
        try {
            dying9.setHealth(0.0F); // 已死未清: isAlive() 为假, 不计入。
            int count = ChampionPromoter.countAliveWorldBosses(helper.getLevel(), null,
                    living -> region.contains(living.getUUID()));
            helper.assertTrue(count == 1,
                    "只数存活 + 已加载 + ≥8★ + 同区域: 8★ 一只 (9★ 已死、7★ 不够星、残留条目取不到、10★ 不同区域), 实得 " + count);
            int excludingSelf = ChampionPromoter.countAliveWorldBosses(helper.getLevel(), alive8.getUUID(),
                    living -> region.contains(living.getUUID()));
            helper.assertTrue(excludingSelf == 0, "排除自身后为 0, 实得 " + excludingSelf);

            Husk second8 = spawnChampion(helper, new BlockPos(8, 1, 0), 8, Map.of(), 15_500.0D, true);
            if (second8 == null) {
                return;
            }
            region.add(second8.getUUID());
            int two = ChampionPromoter.countAliveWorldBosses(helper.getLevel(), null,
                    living -> region.contains(living.getUUID()));
            helper.assertTrue(two == 2, "同区域第二只存活 8★ 计入, 实得 " + two);
            BloodPoolRegistry.remove(second8.getUUID());
        } finally {
            BloodPoolRegistry.remove(stale);
            BloodPoolRegistry.remove(alive8.getUUID());
            BloodPoolRegistry.remove(dying9.getUUID());
            BloodPoolRegistry.remove(seven.getUUID());
            BloodPoolRegistry.remove(elsewhere10.getUUID());
        }
        helper.succeed();
    }

    // ============================================================
    // A4 复合装甲同 tick 同类别只叠 1 层
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void compositeRampStacksOncePerTickPerCategory(GameTestHelper helper) {
        // 霰弹一枪 9 颗弹丸同 tick 落地: 只叠 1 层 (旧实现第一枪就叠满 5 层)。
        CompositeArmorRampTracker shotgun = new CompositeArmorRampTracker();
        for (int pellet = 0; pellet < 9; pellet++) {
            helper.assertTrue(shotgun.onHit(DamageCategory.BULLET, 100L) == 1, "同 tick 第 " + (pellet + 1) + " 颗仍是 1 层");
        }
        helper.assertTrue(shotgun.onHit(DamageCategory.BULLET, 101L) == 2, "下一 tick 才叠到 2 层");

        // 同 tick 换类别仍照常清桶; 清完回到原类别从 1 层重爬。
        helper.assertTrue(shotgun.onHit(DamageCategory.EXPLOSION, 101L) == 1, "同 tick 换类别: 爆炸桶 1 层");
        helper.assertTrue(shotgun.stacksOf(DamageCategory.BULLET) == 0, "换类别清空子弹桶");
        helper.assertTrue(shotgun.onHit(DamageCategory.BULLET, 101L) == 1, "同 tick 换回子弹: 从 1 层重爬");
        helper.assertTrue(shotgun.onHit(DamageCategory.BULLET, 102L) == 2, "下一 tick 2 层");

        // 只读 peek: 不叠层、不刷新重置窗; 过 3s 无伤视为 0。
        CompositeArmorRampTracker peeked = new CompositeArmorRampTracker();
        helper.assertTrue(peeked.peek(DamageCategory.BULLET, 0L) == 0, "从未受击 peek = 0");
        peeked.onHit(DamageCategory.BULLET, 200L);
        helper.assertTrue(peeked.peek(DamageCategory.BULLET, 200L) == 1 && peeked.peek(DamageCategory.MELEE, 200L) == 0,
                "peek 只读当前类别层数");
        helper.assertTrue(peeked.peek(DamageCategory.BULLET, 259L) == 1 && peeked.peek(DamageCategory.BULLET, 260L) == 0,
                "peek 按 3s 无伤窗判过期");
        helper.assertTrue(peeked.lastHitTick() == 200L, "peek 不刷新受击时刻");
        helper.assertTrue(peeked.onHit(DamageCategory.BULLET, 201L) == 2, "peek 不占层: 下一 tick 正常叠到 2");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void compositeRampOncePerTickThroughLiveHurtChain(GameTestHelper helper) {
        // 走真事件总线 (已注册的 ChampionBloodPoolHandler): 8★ 复合 EPIC, 同 tick 两次近战各 100。
        Husk champion = spawnChampion(helper, new BlockPos(0, 1, 0), 8,
                Map.of(AffixDef.COMPOSITE_ARMOR, AffixQuality.EPIC), 10_000.0D, true);
        Husk attacker = helper.spawn(EntityType.HUSK, new BlockPos(3, 1, 0));
        if (champion == null) {
            return;
        }
        UUID championId = champion.getUUID();
        try {
            DamageSource melee = helper.getLevel().damageSources().mobAttack(attacker);
            champion.invulnerableTime = 0;
            champion.hurt(melee, 100.0F);
            champion.invulnerableTime = 0;
            champion.hurt(melee, 100.0F);
            // 两击都是第 1 层 (0.13): 87 + 87 = 174; 旧实现第二击叠到第 2 层 (0.26) 只扣 87 + 74 = 161。
            double remaining = BloodPoolRegistry.get(championId).currentHp();
            helper.assertTrue(Math.abs(remaining - (10_000.0D - 174.0D)) < 1e-3D,
                    "同 tick 两次近战都按第 1 层结算, 池剩 9826, 实得 " + remaining);
        } finally {
            BloodPoolRegistry.remove(championId);
        }
        helper.succeed();
    }

    // ============================================================
    // A5 48 格扫描外照常结算自身效果
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void outsideScanPredicate(GameTestHelper helper) {
        long now = 10_000L;
        helper.assertTrue(ChampionSelfBuffValues.settlesOutsideScan(now, now - 5L, now - 5L, true),
                "受过伤 + 未满血 + TTL 内 -> 结算");
        helper.assertTrue(!ChampionSelfBuffValues.settlesOutsideScan(now, Long.MIN_VALUE, now - 5L, true),
                "从未受伤不结算");
        helper.assertTrue(!ChampionSelfBuffValues.settlesOutsideScan(now, now - 5L, now - 5L, false), "满血不结算");
        helper.assertTrue(ChampionSelfBuffValues.settlesOutsideScan(now, now - 6_000L, now - 6_000L, true),
                "恰 TTL 6000 tick 仍结算");
        helper.assertTrue(!ChampionSelfBuffValues.settlesOutsideScan(now, now - 6_001L, now - 6_001L, true),
                "超 TTL 不结算");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void woundedChampionRegensOutsideScanRange(GameTestHelper helper) {
        // 6★ 易燃再生普通 (8 HP/s), 池 5000 扣到 4000; 未注册的 handler 实例 + 空扫描集 = 模拟冠军在 48 格外。
        Husk champion = spawnChampion(helper, new BlockPos(0, 1, 0), 6,
                Map.of(AffixDef.FLAMMABLE_REGEN, AffixQuality.COMMON), 5_000.0D, true);
        if (champion == null) {
            return;
        }
        UUID id = champion.getUUID();
        try {
            BloodPool pool = BloodPoolRegistry.get(id);
            pool.applyDamage(1_000.0D);
            ChampionSelfEffectHandler handler = new ChampionSelfEffectHandler();
            long hurtTick = champion.level().getGameTime();
            handler.onChampionHurt(new LivingHurtEvent(champion, helper.getLevel().damageSources().generic(), 1.0F));

            var server = helper.getLevel().getServer();
            int settled = handler.settleUnscannedChampions(server, Set.of(), hurtTick + 59L);
            helper.assertTrue(settled == 1 && Math.abs(pool.currentHp() - 4_000.0D) < EPS,
                    "受伤后 59 tick: 格外照常结算但易燃再生仍停回, 池 4000, 实得 " + pool.currentHp());
            settled = handler.settleUnscannedChampions(server, Set.of(), hurtTick + 60L);
            helper.assertTrue(settled == 1 && Math.abs(pool.currentHp() - 4_008.0D) < EPS,
                    "受伤后 60 tick: 格外回一整秒 8 HP, 池 4008, 实得 " + pool.currentHp());
            settled = handler.settleUnscannedChampions(server, Set.of(id), hurtTick + 80L);
            helper.assertTrue(settled == 0 && Math.abs(pool.currentHp() - 4_008.0D) < EPS,
                    "本轮已被近场扫描结算过的不重复结算");
            settled = handler.settleUnscannedChampions(server, Set.of(), hurtTick + 6_001L);
            helper.assertTrue(settled == 0 && Math.abs(pool.currentHp() - 4_008.0D) < EPS,
                    "超 5min TTL 不再结算");
            pool.heal(10_000.0D);
            settled = handler.settleUnscannedChampions(server, Set.of(), hurtTick + 100L);
            helper.assertTrue(settled == 0, "满血后格外不再结算");
        } finally {
            BloodPoolRegistry.remove(id);
        }
        helper.succeed();
    }

    // ============================================================
    // A6 反震 5 格半径
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void thornsRadiusGeometry(GameTestHelper helper) {
        helper.assertTrue(ChampionSelfBuffValues.THORNS_RADIUS_BLOCKS == 5.0D, "反震半径 5 格");
        helper.assertTrue(ChampionSelfBuffValues.thornsInRange(5.0D) && !ChampionSelfBuffValues.thornsInRange(5.001D),
                "恰 5 格仍反, 超过即不反");
        // 点在箱内 = 0; 箱外按各轴超出量求欧氏距离 (到碰撞箱外沿, 不是到中心)。
        helper.assertTrue(ChampionSelfBuffValues.distanceToBox(0.0D, 1.0D, 0.0D,
                -0.3D, 0.0D, -0.3D, 0.3D, 1.95D, 0.3D) == 0.0D, "箱内距离 0");
        helper.assertTrue(Math.abs(ChampionSelfBuffValues.distanceToBox(5.3D, 1.0D, 0.0D,
                -0.3D, 0.0D, -0.3D, 0.3D, 1.95D, 0.3D) - 5.0D) < EPS, "正侧方 5.3 格对 0.3 半宽箱 = 5.0");
        helper.assertTrue(Math.abs(ChampionSelfBuffValues.distanceToBox(3.3D, 5.95D, 0.0D,
                -0.3D, 0.0D, -0.3D, 0.3D, 1.95D, 0.3D) - 5.0D) < EPS, "斜上方 (3,4) 超出量 = 5.0");
        // 巨大化冠军碰撞箱更大, 同一站位离外沿更近。
        helper.assertTrue(ChampionSelfBuffValues.distanceToBox(5.3D, 1.0D, 0.0D,
                -1.2D, 0.0D, -1.2D, 1.2D, 4.0D, 1.2D) < 5.0D, "大碰撞箱: 同一站位落入半径");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void thornsIgnoresDistantAttackerWithoutSpendingCooldown(GameTestHelper helper) {
        Husk champion = spawnChampion(helper, new BlockPos(0, 1, 0), 6,
                Map.of(AffixDef.THORNS, AffixQuality.RARE), 5_000.0D, true);
        if (champion == null) {
            return;
        }
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        UUID playerId = player.getUUID();
        try {
            ChampionSelfEffectHandler handler = new ChampionSelfEffectHandler();
            DamageSource shot = helper.getLevel().damageSources().playerAttack(player);

            // 10 格外开枪: 不反伤, 也不建该玩家的反伤聚合器。
            player.setPos(champion.getX() + 10.0D, champion.getY(), champion.getZ());
            handler.onChampionHurt(new LivingHurtEvent(champion, shot, 5.0F));
            helper.assertTrue(!ChampionEffectRegistries.hasRetaliation(playerId), "10 格外不反伤");

            // 同 tick 贴身再打: 上一击没消耗内 CD, 这一击照常反伤 (反震 RARE 5% x 攻击者 20 血 = 1.0)。
            player.setPos(champion.getX() + 1.0D, champion.getY(), champion.getZ());
            handler.onChampionHurt(new LivingHurtEvent(champion, shot, 5.0F));
            helper.assertTrue(ChampionEffectRegistries.hasRetaliation(playerId), "贴身受击触发反震");
            RetaliationAggregator agg = ChampionEffectRegistries.retaliationFor(playerId, player.getMaxHealth());
            double reflected = agg.windowAccumulated();
            helper.assertTrue(Math.abs(reflected - 0.05D * player.getMaxHealth()) < EPS,
                    "超距那一击没占内 CD: 贴身这一击反伤 5% maxHP, 实得 " + reflected);

            // 同 tick 第三击: 内 CD 已被贴身那一击消耗, 不再反伤。
            handler.onChampionHurt(new LivingHurtEvent(champion, shot, 5.0F));
            helper.assertTrue(Math.abs(agg.windowAccumulated() - reflected) < EPS, "内 CD 内不再反伤");
        } finally {
            ChampionEffectRegistries.clearAll(playerId);
            BloodPoolRegistry.remove(champion.getUUID());
        }
        helper.succeed();
    }

    // ============================================================
    // C2 配件开启的子弹爆炸归子弹桶
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void attachmentBulletExplosionJoinsBulletBucket(GameTestHelper helper) {
        helper.assertTrue(ChampionDamageReduction.categorize(true, false, false, false) == DamageCategory.BULLET,
                "子弹 -> 子弹桶");
        helper.assertTrue(ChampionDamageReduction.categorize(false, true, true, false) == DamageCategory.BULLET,
                "配件开启的子弹爆炸 -> 子弹桶");
        helper.assertTrue(ChampionDamageReduction.categorize(false, true, false, false) == DamageCategory.EXPLOSION,
                "原生爆炸 (RPG7/M320/手雷) -> 爆炸桶");
        helper.assertTrue(ChampionDamageReduction.categorize(false, false, true, true) == DamageCategory.MELEE,
                "非爆炸时配件标记无意义: 近战仍归近战");
        helper.assertTrue(ChampionDamageReduction.categorize(false, false, false, false) == DamageCategory.OTHER,
                "其余归 OTHER");

        // "直伤 -> 配件爆炸" 同 tick 交替: 归同桶后逐 tick 叠满 5 层; 原生爆炸与子弹交替仍互清。
        CompositeArmorRampTracker merged = new CompositeArmorRampTracker();
        CompositeArmorRampTracker split = new CompositeArmorRampTracker();
        DamageCategory directHit = ChampionDamageReduction.categorize(true, false, false, false);
        DamageCategory heBlast = ChampionDamageReduction.categorize(false, true, true, false);
        DamageCategory nativeBlast = ChampionDamageReduction.categorize(false, true, false, false);
        int mergedLayers = 0;
        int splitLayers = 0;
        for (long tick = 0L; tick < 10L; tick++) {
            merged.onHit(directHit, tick);
            mergedLayers = merged.onHit(heBlast, tick);
            split.onHit(directHit, tick);
            splitLayers = split.onHit(nativeBlast, tick);
        }
        helper.assertTrue(mergedLayers == ChampionDamageReduction.COMPOSITE_RAMP_STEPS,
                "10 次 子弹->配件爆炸 后复合叠满 5 层, 实得 " + mergedLayers);
        helper.assertTrue(splitLayers == 1, "原生爆炸与子弹交替仍互相清桶, 实得 " + splitLayers);

        // dev 不加载 TaCZ: 探针走未加载分支, 对任何爆炸都返回否, 且不触发 com.tacz.* 类加载。
        DamageSource vanillaBlast = helper.getLevel().damageSources().explosion(null, null);
        helper.assertTrue(!TaczAttachmentExplosionProbe.isAttachmentBulletExplosion(vanillaBlast),
                "直接实体不是 TaCZ 子弹 (或未装 TaCZ) 的爆炸不算配件爆炸");
        helper.assertTrue(!TaczAttachmentExplosionProbe.isAttachmentBulletExplosion(
                        helper.getLevel().damageSources().generic()), "非爆炸恒否");
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    /** 生成尸壳并盖章为指定星级冠军 (直写 capability, 不经点数换算); withPool 为真时按 hp 建满血影子血池。 */
    private static Husk spawnChampion(GameTestHelper helper, BlockPos pos, int star,
                                      Map<AffixDef, AffixQuality> affixes, double hp, boolean withPool) {
        Husk husk = helper.spawn(EntityType.HUSK, pos);
        MiningChampionData data = MiningChampions.get(husk).orElse(null);
        if (data == null) {
            helper.fail("husk must have champion_data capability attached (MiningChampions.onAttachCapabilities)");
            return null;
        }
        Map<AffixDef, AffixQuality> copy = new EnumMap<>(AffixDef.class);
        copy.putAll(affixes);
        data.promote(star, copy, hp);
        if (withPool) {
            BloodPoolRegistry.install(husk.getUUID(), hp);
        }
        return husk;
    }

    /**
     * 构造 TaCZ 子弹伤害源 (dev 不加载 TaCZ, 注册表里没有 tacz:bullet*): 独立 Holder.Reference 只绑 ResourceKey,
     * 受击 handler 只读 key (namespace/path) 与标签 (空集), 不读 DamageType 值。
     */
    private static DamageSource taczBulletSource(GameTestHelper helper, String path) {
        Registry<DamageType> registry = helper.getLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation("tacz", path));
        Holder<DamageType> holder = Holder.Reference.createStandAlone(registry.holderOwner(), key);
        return new DamageSource(holder);
    }

    /** 对血池冠军直调 handler 受击一次, 返回本次实际扣掉的池血 (按前后差计, 不受其它来源先前扣血影响)。 */
    private static double hitPool(ChampionBloodPoolHandler handler, Husk champion, BloodPool pool,
                                  DamageSource source, float amount) {
        double before = pool.currentHp();
        handler.onLivingHurt(new LivingHurtEvent(champion, source, amount));
        return before - pool.currentHp();
    }
}
