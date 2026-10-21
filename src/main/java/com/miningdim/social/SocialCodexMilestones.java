package com.miningdim.social;

import com.miningdim.job.tarot.TarotConfig;
import com.miningdim.job.tarot.codex.TarotCodex;
import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * 本体塔罗成就完成 → 平板主题解锁。图鉴仅提供展示进度，不直接授予皮肤。
 *
 * <p>图鉴由本体成就模块订阅；社交侧只监听原版成就完成事件并核对完成状态。
 * 登录与平板开机时也核对已完成的成就，处理成就授予时玩家尚未建立社交档案的情况。</p>
 *
 * <p>解锁判定（规格 v0.1 §2.2）：
 * 22 张 cardId 各点亮过至少一种形态 → {@code stardust}；点亮过任意闪耀形态 → {@code shining}；
 * 220 种形态全点亮 → {@code starmaster}。{@code tarot.codexUnlockThemes=false} 时只记录不解锁。</p>
 */
public final class SocialCodexMilestones {

    private SocialCodexMilestones() {
    }

    public static void register(IEventBus forgeBus) {
        forgeBus.addListener(SocialCodexMilestones::onAdvancementEarned);
    }

    private static void onAdvancementEarned(AdvancementEvent.AdvancementEarnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && SocialThemes.ALL.stream().anyMatch(theme ->
                theme.tarotTier() > 0 && theme.unlockKey().equals(event.getAdvancement().getId().toString()))) {
            apply(player);
        }
    }

    /**
     * 重算并解锁。幂等：{@link SocialStore#unlockTheme} 自带 contains 判重。
     * 玩家档案不存在时直接返回（档案由登录/开机建立，此处不代建）。
     */
    public static void apply(ServerPlayer player) {
        if (player == null || !TarotConfig.CODEX_UNLOCK_THEMES.get() || !SocialService.isLoaded()) {
            return;
        }
        SocialStore store = SocialService.store();
        if (store.profile(player.getUUID()) == null) {
            return;
        }
        for (SocialThemes.Theme theme : SocialThemes.ALL) {
            if (theme.tarotTier() == 0) {
                continue;
            }
            ResourceLocation id = new ResourceLocation(theme.unlockKey());
            Advancement advancement = player.server.getAdvancements().getAdvancement(id);
            if (advancement == null) {
                throw new IllegalStateException("Required tarot achievement is not loaded: " + id);
            }
            if (player.getAdvancements().getOrStartProgress(advancement).isDone()) {
                store.unlockTheme(player.getUUID(), theme.id());
            }
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

    /** @param now 当前进度 @param max 上限（&gt;0） @param label 直接展示的文本 */
    public record Progress(int now, int max, String label) {
        public int percent() {
            return Math.max(0, Math.min(100, Math.round(now * 100F / Math.max(1, max))));
        }
    }
}
