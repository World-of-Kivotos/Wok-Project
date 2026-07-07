package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.AffixRoller;
import com.miningdim.champion.AffixSelection;
import com.miningdim.champion.ChampionSpawnPolicy;
import com.miningdim.champion.ChampionSpawnSeam;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.StarRank;
import com.miningdim.champion.bloodpool.BloodPool;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.core.Difficulty;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
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
        MiningChampionData champ = MiningChampions.get(mob).orElse(null);
        if (champ == null) {
            return; // 无冠军 capability (非 Mob / 未挂载, 不应发生): 不升格。
        }

        int star = ChampionSpawnPolicy.rollStar(difficulty, rng);
        StarRank rank = StarRank.ofStar(star);

        // 词条掷取 (纯逻辑) -> def→品质映射。
        List<AffixSelection> selections = AffixRoller.roll(rank, rng);
        Map<AffixDef, AffixQuality> affixMap = new EnumMap<>(AffixDef.class);
        for (AffixSelection sel : selections) {
            affixMap.put(sel.affix(), sel.quality());
        }

        // 有效血 (贡献池分母 + 血量基数): 星表基础有效血 (巨大化 +血量 ×(1+pct) 批2 接入)。
        double effectiveHp = rank.baseEffectiveHp();

        // 盖章 capability (唯一权威: 效果 handler 全经 MiningChampions.get 读)。
        champ.promote(star, affixMap, effectiveHp);

        // 接管基础血量 (取代 Champions growthFactor)。
        applyBaseHealth(mob, rank, effectiveHp);
    }

    /**
     * 接管冠军基础血量。1-5★ (有效血 ≤765 &lt;1024): vanilla MAX_HEALTH 修饰到有效血, 满血。6★+ 或有效血破 1024:
     * 建 {@link BloodPool} 影子血池 (权威, 破千), vanilla MAX_HEALTH 钳到 1024 作渲染镜像天花板 ({@link
     * ChampionBloodPoolHandler} 每 tick 按 displayHealth 镜像), 满血。
     */
    private void applyBaseHealth(Mob mob, StarRank rank, double effectiveHp) {
        boolean useBloodPool = rank.usesCustomBloodPool() || effectiveHp > VANILLA_MAX_HEALTH;
        double vanillaTarget = useBloodPool ? VANILLA_MAX_HEALTH : effectiveHp;

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
