package com.miningdim.job.tarot;

import com.miningdim.job.tarot.card.TarotCardData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * 用牌服务端裁决 (TarotReader spec 9.4/9.5/十章 + 第八/十二章实现红线)。{@link TarotCardItem#use} 委派至此,
 * 三道闸门按序校验, 任一不过即拒绝 (不应用效果 + 不消耗 + 提示, spec 9.4):
 *  1. ownerUUID == 使用者 (spec 第十章: 倒卖来的牌打不出);
 *  2. 等级门控 (spec 9.4: L1/L3/L5/L8/L10 对应 R/SR/SSR/UR/闪耀);
 *  3. GCD + 每卡 CD (spec 9.5; 闪耀按牌表分钟级)。
 * 全过则应用效果 -> 消耗 1 张 -> 结算 "谁打谁得" 经验 (spec 9.1)。RNG 无 (用牌确定性); 服务端权威。
 */
public final class TarotPlayHandler {

    private TarotPlayHandler() {
    }

    /**
     * @return true 已打出 (效果应用 + 消耗 + 经验); false 被某道闸门拒绝 (无副作用)。
     */
    public static boolean tryPlay(ServerLevel level, ServerPlayer player, ItemStack stack, InteractionHand hand) {
        // 牌效表未加载完成 (重载前/失败): 拒绝并提示, 不抛 (玩家侧友好), 装配问题由 loader 重载边界报。
        if (!TarotRuntime.cardLoader().isLoaded()) {
            player.displayClientMessage(Component.translatable("message.miningdim.tarot.not_ready"), true);
            return false;
        }

        int cardId = TarotCardItem.cardId(stack);
        TarotQuality quality = TarotCardItem.quality(stack);
        boolean upright = TarotCardItem.upright(stack);

        // 闸门 1: ownerUUID 校验 (spec 第十章)。
        UUID owner = TarotCardItem.owner(stack);
        if (owner == null || !owner.equals(player.getUUID())) {
            player.displayClientMessage(Component.translatable("message.miningdim.tarot.not_owner"), true);
            return false;
        }

        // 闸门 2: 等级门控 (spec 9.4)。
        if (!TarotLeveling.canUseQuality(player, quality)) {
            player.displayClientMessage(
                    Component.translatable("message.miningdim.tarot.level_gated", quality.requiredLevel()), true);
            return false;
        }

        // 闸门 3: GCD + 每卡 CD (spec 9.5)。
        TarotCardData data = TarotCardItem.dataFor(stack);
        int cardCd = cooldownTicksFor(quality, data);
        int gcd = TarotConfig.GCD_TICKS.get();
        boolean shiny = quality == TarotQuality.SHINY;
        if (!TarotRuntime.cooldown().tryUse(player, cardId, cardCd, gcd, shiny)) {
            player.displayClientMessage(Component.translatable("message.miningdim.tarot.on_cooldown"), true);
            return false;
        }

        // 全过: 应用效果 -> 消耗 -> 经验 (谁打谁得)。
        TarotRuntime.effectEngine().applyCard(level, player, data, quality, upright);
        stack.shrink(1);
        TarotLeveling.grantPlayXp(player, quality);
        return true;
    }

    /** 该品质 + 该牌的每卡 CD (闪耀走牌表分钟级 ticks; 其余按 datapack 分档读 config; spec 9.5)。 */
    private static int cooldownTicksFor(TarotQuality quality, TarotCardData data) {
        if (quality == TarotQuality.SHINY) {
            return data.shinyCooldownTicks();
        }
        return switch (data.cooldownCategory()) {
            case UTILITY -> TarotConfig.CD_UTILITY_TICKS.get();
            case BUFF -> TarotConfig.CD_BUFF_TICKS.get();
            case COMBAT -> TarotConfig.CD_COMBAT_TICKS.get();
        };
    }
}
