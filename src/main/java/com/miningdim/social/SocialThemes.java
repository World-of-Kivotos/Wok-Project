package com.miningdim.social;

import com.miningdim.achievement.trigger.TarotCodexTrigger.Milestone;

import java.util.List;
import java.util.Optional;

/**
 * 平板主题目录。主题的视觉参数在页面 CSS 里，服务端只保存 id 与解锁状态。
 *
 * <p>{@code unlockKey} 是成就系统的绑定键：为空表示默认主题；非空表示需要对应成就解锁。
 * 服务端始终是解锁状态的权威：三档塔罗皮肤在本体对应成就完成后解锁，客户端没有任何写入入口。
 * {@code starlight} 保留原有 id 与主题资源，并默认解锁。</p>
 */
public final class SocialThemes {

    public static final String DEFAULT_THEME = "halolink";
    /** 塔罗图鉴三档皮肤 id（与 {@link com.miningdim.job.tarot.codex.TarotCodex} 里程碑一一对应）。 */
    public static final String STARDUST = "stardust";
    public static final String SHINING = "shining";
    public static final String STARMASTER = "starmaster";

    public record Theme(String id, String name, String subtitle, String unlockKey) {
        public boolean defaultUnlocked() {
            return unlockKey == null || unlockKey.isBlank();
        }

        /** 是否塔罗图鉴三档之一（顺序即档位：星屑 → 闪耀星辰 → 星辰之主）。 */
        public int tarotTier() {
            return switch (id) {
                case STARDUST -> 1;
                case SHINING -> 2;
                case STARMASTER -> 3;
                default -> 0;
            };
        }
    }

    public static final List<Theme> ALL = List.of(
            new Theme("halolink", "基沃托斯 · 青空", "默认主题 · 青空蓝白", ""),
            new Theme("abydos", "阿拜多斯 · 黄昏", "暖橙与沙丘的放学后", ""),
            new Theme("millennium", "千年 · 薄荷", "冷青与实验白", ""),
            new Theme("trinity", "圣三一 · 茶会", "奶油、玫瑰与金色边", ""),
            new Theme("starlight", "星光 · 夜航", "默认解锁 · 星光主题", ""),
            new Theme(STARDUST, "星屑", "静态星尘 · 烫金卡框", Milestone.STARDUST.advancementId().toString()),
            new Theme(SHINING, "闪耀星辰", "星点呼吸 · 扫光 · 全息卡边", Milestone.SHINING.advancementId().toString()),
            new Theme(STARMASTER, "星辰之主", "视差星图 · 翻牌 · 洗牌", Milestone.STARMASTER.advancementId().toString()));

    private SocialThemes() {
    }

    public static Optional<Theme> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return ALL.stream().filter(theme -> theme.id().equals(id)).findFirst();
    }

    public static Theme require(String id) {
        return byId(id).orElseThrow(() -> new SocialException("THEME_NOT_FOUND", "未知的平板主题"));
    }

    public static boolean unlocked(SocialData.Profile profile, Theme theme) {
        if (theme.defaultUnlocked()) {
            return true;
        }
        return profile.unlockedThemes != null && profile.unlockedThemes.contains(theme.id());
    }
}
