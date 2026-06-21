package com.miningdim.champion.integration;

import com.miningdim.champion.AffixRoller;
import com.miningdim.champion.AffixSelection;
import com.miningdim.champion.ChampionAffixState;
import com.miningdim.champion.ChampionSpawnPolicy;
import com.miningdim.champion.ChampionSpawnSeam;
import com.miningdim.champion.StarRank;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.champion.integration.affix.MiningAffixTypes;
import com.miningdim.core.Difficulty;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.theillusivec4.champions.api.IAffix;
import top.theillusivec4.champions.api.IChampion;
import top.theillusivec4.champions.common.capability.ChampionCapability;
import top.theillusivec4.champions.common.rank.Rank;
import top.theillusivec4.champions.common.rank.RankManager;
import top.theillusivec4.champions.common.util.ChampionBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 精英怪升格实现 (Champions 集成层; ChampionStarAffix spec 第十二章生成接入 + 第二章星级控制)。实现纯逻辑层
 * {@link ChampionSpawnSeam.Promoter} 接口, 由 ChampionSystem 在 {@code ModList.isLoaded("champions")} 守卫下
 * {@code ChampionSpawnSeam.bind(this::promote)} 注入; 压力子系统 {@code MobPressureSystem.spawnMob} 成功落地
 * 一只怪后经 seam 回调本类把它升格。
 *
 * 升格链路 (全在 Champions 已加载的运行期, 故可直接触 Champions API):
 *  1. 按矿洞难度 {@link ChampionSpawnPolicy#shouldPromote} 掷是否升格 (杂兵海点缀精英); 不升格直接返回。
 *  2. {@link ChampionSpawnPolicy#rollStar} 按难度档掷星 (EASY[1,3]/MEDIUM[3,6]/HARD[5,10])。
 *  3. {@link AffixRoller#roll} 在该星四池点数预算内掷一组合法词条选择 (纯逻辑, PointBudget 终校验)。
 *  4. 把每条 {@link AffixSelection} 映射成已注册的真 {@link IAffix} ({@link MiningAffixTypes#affixOf})。
 *  5. {@code RankManager.getRank(star)} 取 1-10★ rank (champions-ranks.toml 数据驱动), {@code ChampionBuilder.spawnPreset}
 *     盖章星级 + 词条; 品质等四池无 Champions 字段的数据存进 IChampion.getServer().setData (NBT)。
 *  6. 6★+ 走自定义血池: 按星表基础有效血 {@link BloodPoolRegistry#install} 建影子血池 (破 1024)。
 *
 * 异常纪律: 升格失败 (无 capability / rank 缺失 / spawnPreset 抛) 在本类内 catch + 记日志吞掉, 不向压力
 * 子系统冒泡 —— 刷怪不因单只升格失败而中断 (普通怪照常存在)。这是 seam 回调的边界容错 (Gateway 语义),
 * 非业务层生吞: 升格是"附加增强", 失败降级为普通怪是可接受的优雅退化, 不是掩盖数据缺陷。
 */
public final class ChampionPromoter implements ChampionSpawnSeam.Promoter {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion");

    /** 自定义四池/品质数据在 IChampion.getServer().getData 的子标签键 (namespaced 防撞)。 */
    static final String DATA_KEY = "miningdim_champion";

    /** NBT 字段: 本工程盖章的星级 (IChampion rank tier 同值, 冗余便于 handler 直读不解 rank)。 */
    static final String NBT_STAR = "star";

    /** NBT 字段: 该冠军总有效血 (贡献池盖章门槛一分母; 6★+=血池 maxHp, 1-5★=星表基础有效血)。 */
    static final String NBT_EFFECTIVE_HP = "effective_hp";

    @Override
    public void promote(Mob mob, Difficulty difficulty) {
        RandomSource rng = mob.level().getRandom();
        if (!ChampionSpawnPolicy.shouldPromote(difficulty, rng)) {
            return; // 本只不升格, 普通怪。
        }

        try {
            promoteToChampion(mob, difficulty, rng);
        } catch (RuntimeException promotionFailed) {
            // seam 边界容错: 升格失败降级为普通怪, 不中断刷怪。记日志保留现场 (诊断升格失败原因)。
            LOGGER.warn("champion promotion failed for {} (difficulty {}), staying vanilla mob",
                    mob.getType(), difficulty, promotionFailed);
        }
    }

    private void promoteToChampion(Mob mob, Difficulty difficulty, RandomSource rng) {
        IChampion champion = ChampionCapability.getCapability(mob).orElse(null);
        if (champion == null || champion.getServer() == null) {
            return; // 该实体无冠军 capability (如非 LivingEntity 路径), 不升格。
        }

        int star = ChampionSpawnPolicy.rollStar(difficulty, rng);
        StarRank rank = StarRank.ofStar(star);

        // 词条掷取 (纯逻辑) -> 映射真 IAffix。
        List<AffixSelection> selections = AffixRoller.roll(rank, rng);
        List<IAffix> affixes = new ArrayList<>(selections.size());
        for (AffixSelection sel : selections) {
            affixes.add(MiningAffixTypes.affixOf(sel.affix()));
        }

        // 取 Champions rank (champions-ranks.toml 数据驱动); 缺该 tier 的 rank 则取最高可用 rank 兜底。
        Rank championRank = resolveRank(star);
        if (championRank == null) {
            LOGGER.warn("no Champions rank available for star {}, skipping promotion", star);
            return;
        }

        // 盖章星级 + 词条 (真 Champions API)。spawnPreset 内部 setRank + setAffixes + applyGrowth + sync。
        ChampionBuilder.spawnPreset(champion, championRank.getTier(), affixes);

        // 自定义四池/星级数据存 IChampion NBT (品质等无 Champions 字段, 须自存)。
        net.minecraft.nbt.CompoundTag data = champion.getServer().getData(DATA_KEY);
        data.putInt(NBT_STAR, star);
        data.putDouble(NBT_EFFECTIVE_HP, rank.baseEffectiveHp());

        // 每条词条的品质 ordinal 存进 affix_quality 子表 (键 = AffixDef.name(), 值 = ordinal): 效果应用层经
        // ChampionAffixState.qualityOf 取回品质再 def.valueFor 折算数值 (无品质子表的命令召冠军按 tier 兜底)。
        net.minecraft.nbt.CompoundTag affixQuality = new net.minecraft.nbt.CompoundTag();
        for (AffixSelection sel : selections) {
            ChampionAffixState.writeQuality(affixQuality, sel);
        }
        data.put(ChampionAffixState.NBT_AFFIX_QUALITY, affixQuality);

        champion.getServer().setData(DATA_KEY, data);

        // 6★+ 破 1024: 建自定义影子血池 (基础有效血按星表; b 阶段巨大化加成在词条数值层折算)。
        if (rank.usesCustomBloodPool()) {
            BloodPoolRegistry.install(mob.getUUID(), rank.baseEffectiveHp());
        }
    }

    /**
     * 取该星对应的 Champions rank。RankManager 是 champions-ranks.toml 数据驱动: {@code getRank(int)} 返回精确 tier;
     * 缺该 tier 时回退到 firstEntry, RANKS 全空时回退 emptyRank (tier 0)。tier 0 (空 rank) 视为"无可用 rank",
     * 返 null 让升格跳过 (1-10★ rank 须由 datapack/config 提供; 否则普通怪)。
     */
    private Rank resolveRank(int star) {
        Rank rank = RankManager.getRank(star);
        if (rank == null || rank.getTier() <= 0) {
            return null; // 空 rank: rank 配置缺失, 不升格。
        }
        return rank;
    }
}
