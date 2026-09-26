package com.miningdim.achievement.datagen;

import com.miningdim.achievement.AchievementIds;
import com.miningdim.achievement.meta.AchievementMeta;
import com.miningdim.achievement.tier.AchievementTier;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条成就 (或页签根) 的 Java 声明。datagen 从同一条声明同时生成进度 JSON 与元数据 JSON (4.1), 框体、公告开关与
 * 标题颜色都从 {@link #tier} 推导, 不在声明里另写。
 *
 * @param tier        档位; 页签根为 null
 * @param parent      父进度; 页签根为 null
 * @param icon        图标物品 id (必须是已注册的物品)
 * @param background  页签背景贴图; 只有页签根有
 * @param criteria    条件, 全部满足才算获得 (原版默认的 AND)
 * @param title       附带称号 id; 没有为 null
 * @param requiredMod 依赖的可选模组; 非 null 时进度 JSON 带 forge:mod_loaded 加载条件
 */
record AchievementDeclaration(ResourceLocation id, @Nullable AchievementTier tier, boolean hidden,
                              @Nullable ResourceLocation parent, ResourceLocation icon,
                              @Nullable ResourceLocation background, Map<String, CriterionTriggerInstance> criteria,
                              @Nullable ResourceLocation title, @Nullable String requiredMod) {

    AchievementDeclaration {
        criteria = Collections.unmodifiableMap(new LinkedHashMap<>(criteria));
    }

    boolean isRoot() {
        return tier == null;
    }

    /** 与进度同一路径的元数据。点数一律取档位默认值 (第九章: 首批成就全部取默认)。 */
    AchievementMeta meta() {
        return tier == null ? AchievementMeta.root(id) : new AchievementMeta(id, tier, hidden, null, title);
    }

    String titleKey() {
        return AchievementIds.titleKey(id);
    }

    String descriptionKey() {
        return AchievementIds.descriptionKey(id);
    }
}
