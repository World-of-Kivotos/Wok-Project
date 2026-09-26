package com.miningdim.achievement.reward;

import com.miningdim.achievement.command.AchievementCommands;
import com.miningdim.title.TitleServices;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.Advancement;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

/**
 * 奖励相关提示的文案拼装 (Achievement_System_DesignSpec 7.1、第十一章): 获得成就时的待领取提示、待领取列表的条目、
 * 领取结果都用这里的同一套写法, 聊天里的 [领取] 按钮与命令共用同一条 {@code /machievement claim} 命令串。
 *
 * <p>文案一律走 translate (键前缀 {@code achievement.miningdim.reward.}), 由客户端按自己的语言显示; 进度名取原版的
 * 聊天组件 (带方括号、档位颜色与悬停说明), 称号取称号模块渲染好的徽记。两者在数据包里找不到时退回 id 原文 ——
 * 数据包删掉某个成就或称号后, 已经产生的奖励记录仍要能列出来。
 */
public final class AchievementRewardText {

    private static final String PREFIX = "achievement.miningdim.reward.";

    private AchievementRewardText() {
    }

    /** 获得成就时发给玩家本人的提示: 成就名、奖励内容, 末尾一个可点击的 [领取]。 */
    public static Component earnedNotice(MinecraftServer server, AchievementReward reward) {
        return Component.translatable(PREFIX + "earned", advancementName(server, reward.advancementId()),
                        summary(reward.points(), reward.titleId()))
                .append(" ")
                .append(claimButton(reward.advancementId()));
    }

    /** 奖励内容: 只有成就点、只有称号、两者都有三种写法。 */
    public static Component summary(long points, @Nullable ResourceLocation titleId) {
        if (titleId == null) {
            return Component.translatable(PREFIX + "points", points);
        }
        if (points <= 0) {
            return Component.translatable(PREFIX + "title", titleBadge(titleId));
        }
        return Component.translatable(PREFIX + "points_and_title", points, titleBadge(titleId));
    }

    /** 进度在聊天里的名字 (原版写法: 方括号、框体颜色、悬停看说明); 服务端没有加载该进度时退回 id 原文。 */
    public static Component advancementName(MinecraftServer server, ResourceLocation advancementId) {
        Advancement advancement = server.getAdvancements().getAdvancement(advancementId);
        if (advancement == null || advancement.getDisplay() == null) {
            return Component.literal(advancementId.toString());
        }
        return advancement.getChatComponent();
    }

    /** 称号徽记 {@code [称号]}; 称号门面未注入或定义缺失时退回 id 原文。 */
    public static Component titleBadge(ResourceLocation titleId) {
        if (TitleServices.isRegistered()) {
            return TitleServices.titleService().badge(titleId).orElseGet(() -> Component.literal(titleId.toString()));
        }
        return Component.literal(titleId.toString());
    }

    /** 可点击的 [领取]: 点击即以玩家身份执行 {@code /machievement claim <进度id>}。 */
    public static MutableComponent claimButton(ResourceLocation advancementId) {
        return button(PREFIX + "claim_button", PREFIX + "claim_hover", AchievementCommands.claimCommand(
                advancementId.toString()));
    }

    /** 可点击的 [全部领取]: 点击即执行 {@code /machievement claim all}。 */
    public static MutableComponent claimAllButton() {
        return button(PREFIX + "claim_all_button", PREFIX + "claim_all_hover",
                AchievementCommands.claimCommand(AchievementCommands.ALL));
    }

    private static MutableComponent button(String textKey, String hoverKey, String command) {
        return Component.translatable(textKey).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(hoverKey))));
    }
}
