package com.miningdim.champion;

import net.minecraft.nbt.CompoundTag;

/**
 * 冠军词条品质读取器 (ChampionStarAffix spec 第四章双层平衡 + 9A.7 品质显示 + 第十四章实现拆分 9 地基)。
 *
 * 地基职责: 升格盖章 (ChampionPromoter) 把每条词条的品质 ordinal 存进 IChampion.getServer().getData(DATA_KEY)
 * 的 {@value #NBT_AFFIX_QUALITY} 子表 (键 = AffixDef.name(), 值 = AffixQuality.ordinal())。效果应用层 (减伤/
 * 攻击/属性/技能 handler) 拿到冠军实体 + AffixDef 后, 经本读取器取回该词条的品质档, 再 def.valueFor(quality) /
 * secondaryValueFor 折算数值。
 *
 * compileOnly 隔离 (铁律): 本类属 champion 纯逻辑包, 不 import 任何 top.theillusivec4.champions.* / LivingEntity ——
 * 只吃已由 integration 层取出的 {@link CompoundTag} 子表 (integration handler 调
 * {@code champion.getServer().getData(DATA_KEY).getCompound(NBT_AFFIX_QUALITY)} 后传入)。如此默认品质推导写成
 * 可 GameTest 的纯函数 (dev 不加载 Champions), 真服 NBT 读取由 integration 薄壳转交。
 *
 * 命令召唤/无我方 NBT 的冠军兜底 (本任务要求): Champions 的 {@code /champions} 命令可直接 setRank + setAffixes
 * 盖章, 不经我方 promoter, 故无 {@value #NBT_AFFIX_QUALITY} 子表。此类冠军按 tier 兜底取该星允许的最高品质
 * ({@link StarRank#maxQuality()}), 再经 {@link AffixQuality#clampTo} 夹回 (给个合理默认, 让命令召的冠军效果也能跑),
 * 并抬到该词条最低可用档 ({@link AffixDef#minUsableQuality()}) 防取到前导 0 占位档。
 */
public final class ChampionAffixState {

    private ChampionAffixState() {
    }

    /**
     * 品质子表键 (在 DATA_KEY 主表下): {@code affix_quality: { COMPOSITE_ARMOR: 2, BURNING: 0, ... }}。
     * 与 {@link com.miningdim.champion.integration.ChampionPromoter#DATA_KEY} 同表内嵌, 故只需子表名常量。
     */
    public static final String NBT_AFFIX_QUALITY = "affix_quality";

    /**
     * 子表内每条词条的键 = 词条枚举名 (AffixDef.name(), 如 {@code COMPOSITE_ARMOR})。纯包稳定键, 不依赖
     * integration 的 registryName (那触 Champions); 二者均源于枚举名, 语义一致。值 = AffixQuality.ordinal()。
     *
     * @param def 词条
     * @return 子表键
     */
    public static String nbtKeyOf(AffixDef def) {
        if (def == null) {
            throw new IllegalArgumentException("def must not be null");
        }
        return def.name();
    }

    /**
     * 把一条已盖章的词条选择写进品质子表 (升格盖章用; 纯函数 mutate 传入的子表)。
     * 键 = 词条枚举名, 值 = 品质 ordinal。
     *
     * @param affixQualityTag DATA_KEY 主表下的 {@value #NBT_AFFIX_QUALITY} 子表 (可变)
     * @param selection       词条选择 (词条 + 实际品质)
     */
    public static void writeQuality(CompoundTag affixQualityTag, AffixSelection selection) {
        if (affixQualityTag == null) {
            throw new IllegalArgumentException("affixQualityTag must not be null");
        }
        if (selection == null) {
            throw new IllegalArgumentException("selection must not be null");
        }
        affixQualityTag.putInt(nbtKeyOf(selection.affix()), selection.quality().ordinal());
    }

    /**
     * 命令召唤/无我方 NBT 的冠军按 tier 兜底品质 (本任务要求): 取该星允许最高品质 {@link StarRank#maxQuality()},
     * 经 {@link AffixQuality#clampTo}(rank.maxQuality) 夹回 (给合理默认), 再抬到该词条最低可用档防取前导 0 占位。
     *
     * 纯函数 (不读 NBT, 仅星级 + 词条推导), GameTest 直接断言默认品质推导。
     *
     * @param def  词条
     * @param rank 冠军星级
     * @return 兜底品质 (∈ [def.minUsableQuality, rank.maxQuality])
     */
    public static AffixQuality defaultQualityFor(AffixDef def, StarRank rank) {
        if (def == null || rank == null) {
            throw new IllegalArgumentException("def and rank must not be null");
        }
        // 取该星最高品质再 clampTo 自身 (恒等, 与任务要求的兜底口径一致), 抬到词条最低可用档。
        AffixQuality byTier = rank.maxQuality().clampTo(rank.maxQuality());
        AffixQuality minUsable = def.minUsableQuality();
        if (byTier.ordinal() < minUsable.ordinal()) {
            return minUsable;
        }
        return byTier;
    }

    /**
     * 读某词条的品质档 (效果应用层取数入口)。优先读 NBT 子表里我方盖章的 ordinal; 无对应条目 (命令召唤/
     * 非我方盖章冠军) 则按 tier {@link #defaultQualityFor} 兜底。
     *
     * 读到的 NBT 品质仍经星级钳制 + 最低可用档抬升 (防脏 NBT/越档): 即使 NBT 存了超该星上限的 ordinal,
     * 也 clampTo(rank.maxQuality) 夹回, 与盖章期 {@link AffixSelection} 的合法区间一致 (不掩盖, 但不让脏值越红线)。
     *
     * @param affixQualityTag DATA_KEY 主表下的 {@value #NBT_AFFIX_QUALITY} 子表 (可为 null/空 = 走兜底)
     * @param def             词条
     * @param rank            冠军星级
     * @return 该词条实际品质
     */
    public static AffixQuality qualityOf(CompoundTag affixQualityTag, AffixDef def, StarRank rank) {
        if (def == null || rank == null) {
            throw new IllegalArgumentException("def and rank must not be null");
        }
        String key = nbtKeyOf(def);
        if (affixQualityTag == null || !affixQualityTag.contains(key)) {
            return defaultQualityFor(def, rank); // 命令召唤/非我方盖章: tier 兜底。
        }
        int ordinal = affixQualityTag.getInt(key);
        AffixQuality stored = qualityFromOrdinal(ordinal);
        // 星级钳制 + 最低可用档抬升 (与盖章期合法区间一致, 防脏 NBT 越档)。
        AffixQuality clamped = stored.clampTo(rank.maxQuality());
        AffixQuality minUsable = def.minUsableQuality();
        if (clamped.ordinal() < minUsable.ordinal()) {
            return minUsable;
        }
        return clamped;
    }

    /**
     * ordinal -> AffixQuality (越界 ordinal 抛 IllegalArgumentException, 不掩盖脏 NBT)。
     *
     * @param ordinal 品质 ordinal (0-4)
     * @return 对应品质
     */
    public static AffixQuality qualityFromOrdinal(int ordinal) {
        AffixQuality[] all = AffixQuality.values();
        if (ordinal < 0 || ordinal >= all.length) {
            throw new IllegalArgumentException("affix quality ordinal out of [0," + (all.length - 1) + "]: " + ordinal);
        }
        return all[ordinal];
    }
}
