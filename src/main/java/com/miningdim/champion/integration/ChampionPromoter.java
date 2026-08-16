package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.AffixRoller;
import com.miningdim.champion.AffixSelection;
import com.miningdim.champion.ChampionHpConversion;
import com.miningdim.champion.ChampionSpawnPolicy;
import com.miningdim.champion.ChampionSpawnSeam;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.SizeAffixEligibility;
import com.miningdim.champion.StarRank;
import com.miningdim.champion.bloodpool.BloodPool;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.core.Difficulty;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 精英怪升格实现 (自研冠军系统; ChampionStarAffix spec 第十二章生成接入 + 第二章星级控制 + 第六章血量)。实现
 * 纯逻辑层 {@link ChampionSpawnSeam.Promoter} 接口, 由 {@link com.miningdim.champion.ChampionSystem} 经
 * {@code ChampionSpawnSeam.bind} 注入; 压力子系统 {@code MobPressureSystem.spawnMob} 落地一只怪后经 seam 回调升格。
 *
 * 自研 (取代 Champions ChampionBuilder/IChampion/RankManager): 升格 = 盖章我方 {@link MiningChampionData} capability
 * (星级 + 词条→品质 + 有效血) + 我方接管基础血量 (1-5★ 走 vanilla MAX_HEALTH 属性修饰到星表有效血; 6★+ 或超 1024
 * 走自定义 {@link BloodPool} 影子血池破千, vanilla 血作 ≤1024 渲染镜像)。全程不触任何 top.theillusivec4.champions.*,
 * 故 dev GameTest 可直接对 mock Mob 挂 cap 断言升格链。
 *
 * 升格链路:
 *  1. {@link ChampionSpawnPolicy#shouldPromote} 掷是否升格 (杂兵海点缀精英); 不升格直接返回普通怪。
 *  2. {@link ChampionSpawnPolicy#rollStar} 按难度档掷星 (EASY[1,3]/MEDIUM[3,6]/HARD[5,10])。
 *  3. {@link AffixRoller#roll} 四池点数预算内掷合法词条选择 (纯逻辑, PointBudget 终校验)。
 *  4. {@link MiningChampionData#promote} 盖章 capability (星级 + def→品质映射 + 有效血)。
 *  5. {@link #applyBaseHealth} 接管基础血量 (取代 Champions rank growthFactor)。
 *
 * 异常纪律: 升格失败在本类内 catch RuntimeException + 记日志吞掉, 不向压力子系统冒泡 —— 刷怪不因单只升格失败中断
 * (seam 回调边界容错 / 优雅退化, 非业务生吞)。
 */
public final class ChampionPromoter implements ChampionSpawnSeam.Promoter {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion");

    /** 冠军基础血量属性修饰固定 UUID (permanent, 随实体 NBT 存盘; 幂等按 UUID 覆盖)。 */
    private static final UUID HP_MODIFIER_UUID = UUID.fromString("a1e6f3c2-9b84-4d17-b0e5-3c9f2a6d8b41");

    /** 原版 generic.max_health 硬上限 (RangedAttribute maxValue=1024): 6★+ vanilla 血作影子血池渲染镜像的天花板。 */
    private static final double VANILLA_MAX_HEALTH = BloodPool.VANILLA_MAX_HEALTH_CLAMP;

    /** 该冠军是否需要影子血池 (spec 6.2): 6★+ 恒需, 或有效血破 vanilla 1024 上限亦需。升格建池与载入重建共用同一判据, 不许各写一份。 */
    public static boolean requiresBloodPool(StarRank rank, double effectiveHp) {
        return rank.usesCustomBloodPool() || effectiveHp > VANILLA_MAX_HEALTH;
    }

    @Override
    public void promote(Mob mob, Difficulty difficulty) {
        RandomSource rng = mob.level().getRandom();
        if (!ChampionSpawnPolicy.shouldPromote(difficulty, rng)) {
            return; // 本只不升格, 普通怪。
        }
        try {
            promoteToChampion(mob, difficulty, rng);
        } catch (RuntimeException promotionFailed) {
            LOGGER.warn("champion promotion failed for {} (difficulty {}), staying vanilla mob",
                    mob.getType(), difficulty, promotionFailed);
        }
    }

    private void promoteToChampion(Mob mob, Difficulty difficulty, RandomSource rng) {
        int star = ChampionSpawnPolicy.rollStar(difficulty, rng);
        StarRank rank = StarRank.ofStar(star);

        // 词条掷取 (纯逻辑) -> def→品质映射。带体型资格上下文: 非白名单异形碰撞箱实体剔除 SIZE 族, 点数改抽同池
        // (spec 9A.4), 避免蜘蛛/史莱姆等被巨大化/缩小化缩放出崩坏碰撞箱。
        List<AffixSelection> selections = AffixRoller.roll(rank, rng, sizeEligibleOf(mob));
        Map<AffixDef, AffixQuality> affixMap = new EnumMap<>(AffixDef.class);
        for (AffixSelection sel : selections) {
            affixMap.put(sel.affix(), sel.quality());
        }

        applyChampion(mob, star, affixMap);
    }

    /**
     * 盖章一只 Mob 为指定星级 + 词条的冠军 (自然升格 + 命令召唤共用唯一入口): 写 capability (唯一权威) + 接管基础
     * 血量。无冠军 capability (非 Mob) 静默返回。有效血经 {@link ChampionHpConversion} 点数换算 (批2): 星表基础血
     * × 生存池剩余点曲线 × 体型乘数 (巨大化 +血 / 缩小化 -血), 堵"带减伤词条 = 白送有效血"。
     *
     * @param mob     目标怪
     * @param star    星级 (1-10)
     * @param affixes 词条→品质映射 (拷入)
     */
    public static void applyChampion(Mob mob, int star, Map<AffixDef, AffixQuality> affixes) {
        MiningChampionData champ = MiningChampions.get(mob).orElse(null);
        if (champ == null) {
            return; // 无冠军 capability (非 Mob / 未挂载): 不盖章。
        }
        StarRank rank = StarRank.ofStar(star);
        double effectiveHp = ChampionHpConversion.convertedEffectiveHp(rank, affixes);
        champ.promote(star, affixes, effectiveHp);
        applyBaseHealth(mob, rank, effectiveHp);
        // 诊断 (真服首验): 每次盖章打一行 (低频不门控) —— 自然刷/命令召的冠军星级/词条/有效血/换算三因子一目了然。
        // sizeEligible 便于真服核对: 非白名单实体身上出现体型词条 = 命令强制附加 (自然刷不该出, 见 AffixRoller 剔除)。
        LOGGER.info("promoted {} star{} affixes={} effHp={} hpFrac={} sizeMult={} survSpent={} bloodPool={} sizeEligible={}",
                mob.getType().getDescriptionId(), star, affixes.keySet(),
                String.format("%.1f", effectiveHp),
                String.format("%.3f", ChampionHpConversion.hpFraction(rank, affixes)),
                String.format("%.2f", ChampionHpConversion.sizeMultiplier(affixes)),
                ChampionHpConversion.survivalSpent(affixes),
                requiresBloodPool(rank, effectiveHp),
                sizeEligibleOf(mob));

        // 体型词条落地触发 (批4 波3): 盖章毕刷新碰撞箱, 令 ChampionSizeHandler 的 EntityEvent.Size 按刚写入的
        // capability 体型系数缩放服务端 AABB + 首帧向 tracking 玩家广播尺寸 (客户端 capability 不同步, 靠 S2C 包)。
        // 无体型词条的冠军该事件读出系数 1.0 早退, 不改任何行为。
        mob.refreshDimensions();
    }

    /**
     * 取实体类型 id 判体型词条白名单资格 (spec 9A.4): 经 {@link ForgeRegistries#ENTITY_TYPES} 反查注册 id
     * (形如 minecraft:zombie), 转 String 喂纯逻辑 {@link SizeAffixEligibility}。反查不到注册 id (理论上不发生,
     * 实体已在世界内) 按不合格处理 (不给体型), 与 {@link SizeAffixEligibility#isEligible} 对 null 的兜底一致。
     */
    private static boolean sizeEligibleOf(Mob mob) {
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(mob.getType());
        return SizeAffixEligibility.isEligible(key == null ? null : key.toString());
    }

    /**
     * 接管冠军基础血量。vanilla MAX_HEALTH 一律修饰到有效血【真值】: 测试服装了 AttributeFix (max_health 上限抬到
     * 1e6), 属性真到位 -> Jade 等悬浮血条直显真血 (恢复 Champions 时代 112375/112375 的观感); 无 AttributeFix
     * (dev GameTest/纯原版) 属性自钳 1024, {@link ChampionBloodPoolHandler} 镜像按 getMaxHealth() 读回的实际上限
     * 等比映射, 自动降级不诈活。6★+ 或有效血破 1024 另建 {@link BloodPool} 影子血池 —— 无论属性钳不钳, 池都是
     * 唯一战斗权威 (spec 6.2), vanilla 恒为渲染镜像。
     */
    private static void applyBaseHealth(Mob mob, StarRank rank, double effectiveHp) {
        boolean useBloodPool = requiresBloodPool(rank, effectiveHp);
        double vanillaTarget = effectiveHp;

        AttributeInstance maxHp = mob.getAttribute(Attributes.MAX_HEALTH);
        if (maxHp != null) {
            maxHp.removeModifier(HP_MODIFIER_UUID); // 幂等: 覆盖旧修饰 (重复升格防叠加)。
            double delta = vanillaTarget - maxHp.getBaseValue();
            if (delta != 0.0D) {
                maxHp.addPermanentModifier(new AttributeModifier(
                        HP_MODIFIER_UUID, "champion_base_hp", delta, AttributeModifier.Operation.ADDITION));
            }
        }

        if (useBloodPool) {
            BloodPoolRegistry.install(mob.getUUID(), effectiveHp);
        }
        mob.setHealth(mob.getMaxHealth());
    }
}
