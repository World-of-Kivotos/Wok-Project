package com.miningdim.social;

import com.miningdim.job.tarot.TarotConfig;
import com.miningdim.job.tarot.codex.TarotCodex;
import net.minecraft.server.level.ServerPlayer;

/**
 * 塔罗图鉴里程碑 → 平板主题解锁。社交侧唯一的接线点。
 *
 * <p>方向刻意是「社交 → 塔罗」：图鉴（{@code com.miningdim.job.tarot.codex}）不认识社交模块，
 * 只在点亮新形态时回调 {@link TarotCodex.MilestoneListener}；本类注册监听并在回调里判断三条里程碑。
 * 这样职业代码不反向依赖展示层，关掉社交也不影响图鉴记录。</p>
 *
 * <p>解锁判定（规格 v0.1 §2.2）：
 * 22 张 cardId 各点亮过至少一种形态 → {@code stardust}；点亮过任意闪耀形态 → {@code shining}；
 * 220 种形态全点亮 → {@code starmaster}。{@code tarot.codexUnlockThemes=false} 时只记录不解锁。</p>
 */
public final class SocialCodexMilestones {

    private SocialCodexMilestones() {
    }

    /** 服务端启动时调用一次（重复调用无害）。 */
    public static void register() {
        TarotCodex.addListener(SocialCodexMilestones::apply);
    }

    /**
     * 重算并解锁。幂等：{@link SocialStore#unlockTheme} 自带 contains 判重。
     * 玩家档案不存在时直接返回（档案由登录/开机建立，此处不代建）。
     */
    public static void apply(ServerPlayer player) {
        if (player == null || !unlockEnabled() || !SocialService.isLoaded()) {
            return;
        }
        SocialStore store = SocialService.store();
        if (store.profile(player.getUUID()) == null) {
            return;
        }
        TarotCodex.Summary summary = TarotCodex.summary(player);
        if (summary.arcana() >= summary.arcanaTotal()) {
            store.unlockTheme(player.getUUID(), SocialThemes.STARDUST);
        }
        if (summary.shiny()) {
            store.unlockTheme(player.getUUID(), SocialThemes.SHINING);
        }
        if (summary.count() >= summary.total()) {
            store.unlockTheme(player.getUUID(), SocialThemes.STARMASTER);
        }
    }

    /** 主题对应的图鉴进度；非塔罗皮肤返回 null（页面就不画进度条）。 */
    public static Progress progress(SocialThemes.Theme theme, TarotCodex.Summary summary) {
        if (theme == null || summary == null) {
            return null;
        }
        int total = Math.max(1, summary.total());
        return switch (theme.id()) {
            case SocialThemes.STARDUST -> new Progress(
                    Math.min(summary.arcana(), summary.arcanaTotal()), summary.arcanaTotal(),
                    summary.arcana() + " / " + summary.arcanaTotal() + " 张");
            case SocialThemes.SHINING -> summary.shiny()
                    ? new Progress(1, 1, "已获得闪耀")
                    : new Progress(0, 1, "尚未获得闪耀");
            case SocialThemes.STARMASTER -> new Progress(
                    Math.min(summary.count(), total), total, summary.count() + " / " + total);
            default -> null;
        };
    }

    /** 开关读取容错：配置尚未加载（如极端早期调用）时按默认开启处理。 */
    private static boolean unlockEnabled() {
        try {
            return TarotConfig.CODEX_UNLOCK_THEMES.get();
        } catch (RuntimeException notLoaded) {
            return true;
        }
    }

    /** @param now 当前进度 @param max 上限（&gt;0） @param label 直接展示的文本 */
    public record Progress(int now, int max, String label) {
        public int percent() {
            return Math.max(0, Math.min(100, Math.round(now * 100F / Math.max(1, max))));
        }
    }
}
