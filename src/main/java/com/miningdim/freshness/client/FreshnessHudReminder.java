package com.miningdim.freshness.client;

import com.miningdim.freshness.FreshnessDisplay;
import com.miningdim.freshness.FreshnessReminder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.TickEvent;


/**
 * 快捷栏上方那一次提醒 (规格「客户端显示」: 每 20 tick 扫一遍自己背包, 有东西 1 小时内要烂就在快捷栏上方
 * 显示一次, **每小时最多一次**)。
 *
 * 判据全在 {@link FreshnessReminder} (纯函数, 有断言), 这里只做"扫背包 + 显示 + 记时间":
 * 每 tick 都提醒与每小时最多一次, 在玩家体感上完全是两个东西, 所以频率被单独钉住。
 *
 * 提醒只覆盖**背包** (规格原文"扫一遍自己背包"), 不看容器与末影箱 —— 那些地方的东西玩家没打算马上用,
 * 提醒只会变成噪音。
 */
public final class FreshnessHudReminder {

    private FreshnessHudReminder() {
    }

    private static long lastWarnMinute = Long.MIN_VALUE;

    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !FreshnessClientConfig.hotbarReminderEnabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            lastWarnMinute = Long.MIN_VALUE; // 换世界/退出: 冷却跟着重置, 免得把上一个服的提醒压掉
            return;
        }
        long gameTime = minecraft.level.getGameTime();
        if (!FreshnessReminder.isCheckTick(gameTime)) {
            return;
        }
        long nowMinute = FreshnessClientState.nowMinute();
        if (nowMinute == Long.MIN_VALUE) {
            return; // 还没同步: 宁可晚一次提醒, 也不编一个时间
        }
        if (!FreshnessReminder.cooldownElapsed(nowMinute, lastWarnMinute)) {
            return;
        }
        var reminder = FreshnessClientState.reminder(player.getInventory().items, nowMinute);
        if (reminder.count() == 0) {
            return;
        }
        lastWarnMinute = nowMinute;
        player.displayClientMessage(Component.translatable(FreshnessReminder.KEY_REMINDER,
                reminder.earliest().getHoverName(), FreshnessDisplay.label(reminder.remainingMinutes()),
                reminder.count()), true);
    }
}
