package com.miningdim.achievement.meta;

import com.miningdim.achievement.AchievementIds;
import com.miningdim.achievement.tier.AchievementTier;
import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.PlayerAdvancements;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 成就的查询快照: 某个进度的档位、成就点、附带称号, 以及 6.5 口径的成就数量候选列表。
 *
 * 每次数据包 (重新) 加载后由 {@code AchievementSystem} 用服务端实际加载的进度与当前元数据重建一次, 装进
 * {@code AchievementServices}; 查询只读这份快照, 不去扫描全部进度 (全部进度里大半是配方进度)。
 *
 * <p>缺少元数据的进度一律按"0 点、无称号"回答 (4.1: 宁可少发, 不可错发), 一致性校验会另外把它报出来。
 */
public final class AchievementCatalog {

    /** 开服前与停服后的空快照: 所有查询都回答"没有"。 */
    public static final AchievementCatalog EMPTY = new AchievementCatalog(Map.of(), List.of());

    private final Map<ResourceLocation, AchievementMeta> metas;
    private final List<Advancement> countable;
    private final Set<ResourceLocation> countableIds;

    private AchievementCatalog(Map<ResourceLocation, AchievementMeta> metas, List<Advancement> countable) {
        this.metas = metas;
        this.countable = countable;
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        for (Advancement advancement : countable) {
            ids.add(advancement.getId());
        }
        this.countableIds = Collections.unmodifiableSet(ids);
    }

    /**
     * 用一组已加载的进度与元数据建快照。成就数量候选 (6.5) 取本模块的进度, 不含各页签根与 meta 页签自己的成就,
     * 含隐藏成就; 加载条件不满足的进度本来就不在 advancements 里, 自然不计。
     */
    public static AchievementCatalog build(Collection<Advancement> advancements,
                                           Map<ResourceLocation, AchievementMeta> metas) {
        List<Advancement> countable = new ArrayList<>();
        for (Advancement advancement : advancements) {
            if (AchievementIds.isCountable(advancement.getId())) {
                countable.add(advancement);
            }
        }
        countable.sort(Comparator.comparing(Advancement::getId));
        return new AchievementCatalog(Map.copyOf(metas), Collections.unmodifiableList(countable));
    }

    /** 某进度的元数据; 不是本模块的进度或缺少元数据时为空。 */
    public Optional<AchievementMeta> meta(ResourceLocation advancementId) {
        return Optional.ofNullable(metas.get(advancementId));
    }

    /** 某进度的档位; 页签根、缺少元数据时为空。 */
    public Optional<AchievementTier> tier(ResourceLocation advancementId) {
        return meta(advancementId).map(AchievementMeta::tier);
    }

    /** 获得某进度可得的成就点; 页签根与缺少元数据的进度为 0。 */
    public int points(ResourceLocation advancementId) {
        return meta(advancementId).map(AchievementMeta::points).orElse(0);
    }

    /** 获得某进度附带的称号; 没有或缺少元数据时为空。 */
    public Optional<ResourceLocation> title(ResourceLocation advancementId) {
        return meta(advancementId).map(AchievementMeta::title);
    }

    /** 获得该进度是否会产生待领取奖励 (7.1: 本模块的进度, 且点数 > 0 或附带称号)。 */
    public boolean isRewarding(ResourceLocation advancementId) {
        return AchievementIds.isAchievement(advancementId)
                && (points(advancementId) > 0 || title(advancementId).isPresent());
    }

    /** 成就数量候选的 id (6.5), 按 id 排序。 */
    public Set<ResourceLocation> countableIds() {
        return countableIds;
    }

    /** 某玩家已获得的成就数量 (6.5 口径), 只遍历候选列表。 */
    public int countEarned(PlayerAdvancements progress) {
        int earned = 0;
        for (Advancement advancement : countable) {
            if (progress.getOrStartProgress(advancement).isDone()) {
                earned++;
            }
        }
        return earned;
    }

    /** 建快照时的全部元数据 (供一致性校验复查)。 */
    public Map<ResourceLocation, AchievementMeta> metas() {
        return metas;
    }
}
