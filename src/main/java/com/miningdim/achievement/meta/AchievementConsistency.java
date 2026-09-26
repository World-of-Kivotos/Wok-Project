package com.miningdim.achievement.meta;

import com.miningdim.achievement.AchievementIds;
import com.miningdim.achievement.tier.AchievementTier;
import com.miningdim.title.TitleServices;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 运行时一致性校验 (Achievement_System_DesignSpec 4.1 第 2 条): 元数据加载后, 拿它和服务端实际加载的进度逐条对比。
 *
 * datagen 已经保证生成物一致; 这里防的是生成之后被手改的 JSON、第三方数据包覆盖、忘了重跑 runData 这类漂移。
 * 核对项:
 * <ul>
 *   <li>本模块的每个进度 ({@link AchievementIds#isAchievement}) 都有元数据; 缺的按"0 点、无称号"处理;</li>
 *   <li>有档位的成就: 框体、公告开关与档位一致 (隐藏成就一律公告), 隐藏标记与元数据一致, 发 Toast, 且挂在某个父进度下;</li>
 *   <li>页签根: 没有父进度, 不公告、不发 Toast、不隐藏;</li>
 *   <li>元数据引用的称号 id 在称号模块里有定义。</li>
 * </ul>
 * 本类只产出报告, 不改任何状态; 由调用方写日志或回显给管理员。
 */
public final class AchievementConsistency {

    private AchievementConsistency() {
    }

    /**
     * 核对一组进度与元数据。
     *
     * @param advancements 服务端加载的进度 (可以含配方进度与其他命名空间, 会被跳过)
     * @param metas        当前元数据
     * @param titleExists  称号 id 是否有定义
     */
    public static ConsistencyReport check(Collection<Advancement> advancements,
                                          Map<ResourceLocation, AchievementMeta> metas,
                                          Predicate<ResourceLocation> titleExists) {
        List<Advancement> ours = new ArrayList<>();
        for (Advancement advancement : advancements) {
            if (AchievementIds.isAchievement(advancement.getId())) {
                ours.add(advancement);
            }
        }
        ours.sort(Comparator.comparing(Advancement::getId));

        List<String> problems = new ArrayList<>();
        Set<ResourceLocation> loaded = new HashSet<>();
        for (Advancement advancement : ours) {
            loaded.add(advancement.getId());
            checkOne(advancement, metas.get(advancement.getId()), titleExists, problems);
        }

        List<ResourceLocation> orphans = new ArrayList<>();
        for (ResourceLocation id : metas.keySet()) {
            if (!loaded.contains(id)) {
                orphans.add(id);
            }
        }
        orphans.sort(Comparator.naturalOrder());
        return new ConsistencyReport(ours.size(), problems, orphans);
    }

    /** 称号模块当前是否有该称号的定义 (门面未注入时一律视为没有)。 */
    public static boolean titleDefined(ResourceLocation titleId) {
        return TitleServices.isRegistered() && TitleServices.titleService().definition(titleId).isPresent();
    }

    private static void checkOne(Advancement advancement, AchievementMeta meta,
                                 Predicate<ResourceLocation> titleExists, List<String> problems) {
        ResourceLocation id = advancement.getId();
        if (meta == null) {
            problems.add(id + ": no achievement meta; treated as 0 points and no title");
            return;
        }
        DisplayInfo display = advancement.getDisplay();
        if (display == null) {
            problems.add(id + ": has no display, so the tab screen, toast and announcement cannot show it");
            return;
        }
        if (meta.isRoot()) {
            if (advancement.getParent() != null) {
                problems.add(id + ": meta marks a tab root but the advancement has parent "
                        + advancement.getParent().getId());
            }
            if (display.shouldAnnounceChat() || display.shouldShowToast() || display.isHidden()) {
                problems.add(id + ": a tab root must not announce, show a toast or be hidden");
            }
            return;
        }
        AchievementTier tier = meta.tier();
        if (advancement.getParent() == null) {
            problems.add(id + ": tier " + tier.id() + " achievement has no parent");
        }
        if (display.getFrame() != tier.frame()) {
            problems.add(id + ": frame " + display.getFrame().getName() + " does not match tier " + tier.id()
                    + " (expected " + tier.frame().getName() + ")");
        }
        boolean expectedAnnounce = tier.announcesToChat(meta.hidden());
        if (display.shouldAnnounceChat() != expectedAnnounce) {
            problems.add(id + ": announce_to_chat is " + display.shouldAnnounceChat() + " but tier " + tier.id()
                    + (meta.hidden() ? " (hidden)" : "") + " requires " + expectedAnnounce);
        }
        if (display.isHidden() != meta.hidden()) {
            problems.add(id + ": hidden is " + display.isHidden() + " but the meta says " + meta.hidden());
        }
        if (!display.shouldShowToast()) {
            problems.add(id + ": show_toast is off");
        }
        if (meta.title() != null && !titleExists.test(meta.title())) {
            problems.add(id + ": meta grants unknown title " + meta.title());
        }
    }
}
