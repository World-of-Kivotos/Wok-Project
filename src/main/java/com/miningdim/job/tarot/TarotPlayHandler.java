package com.miningdim.job.tarot;

import com.miningdim.job.tarot.card.TarotCardData;
import com.miningdim.job.tarot.network.TarotCastVisualS2C;
import com.miningdim.job.tarot.network.TarotNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemHandlerHelper;

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

        // 裸牌闸门 (F075): op 用 /give miningdim:tarot_card 产出的牌没有任何 NBT, 下面 cardId/quality/upright
        // 三个 getter 一碰就抛 IllegalStateException, 把右键流程炸成服务端异常。hasReadableCardIdentity 是
        // 工程里现成的非抛探针 (TarotCardItem.java:85-94), 提前挡掉给玩家一个友好提示而不是让异常冒出来。
        if (!TarotCardItem.hasReadableCardIdentity(stack)) {
            player.displayClientMessage(Component.translatable("message.miningdim.tarot.invalid_card"), true);
            return false;
        }

        int cardId = TarotCardItem.cardId(stack);
        TarotQuality quality = TarotCardItem.quality(stack);
        boolean upright = TarotCardItem.upright(stack);
        boolean testMode = TarotConfig.TEST_MODE.get();

        // 闸门 1: ownerUUID 校验 (spec 第十章)。
        UUID owner = TarotCardItem.owner(stack);
        if (!testMode && (owner == null || !owner.equals(player.getUUID()))) {
            player.displayClientMessage(Component.translatable("message.miningdim.tarot.not_owner"), true);
            return false;
        }

        // 闸门 2: 等级门控 (spec 9.4)。
        if (!testMode && !TarotLeveling.canUseQuality(player, quality)) {
            player.displayClientMessage(
                    Component.translatable("message.miningdim.tarot.level_gated", quality.requiredLevel()), true);
            return false;
        }

        // 闸门 3: GCD + 每卡 CD (spec 9.5)。
        TarotCardData data = TarotCardItem.dataFor(stack);
        int cardCd = cooldownTicksFor(quality, data);
        int gcd = TarotConfig.GCD_TICKS.get();
        boolean shiny = quality == TarotQuality.SHINY;
        if (TarotRuntime.castManager().isCasting(player.getUUID())) {
            player.displayClientMessage(Component.translatable("message.miningdim.tarot.casting"), true);
            return false;
        }
        if (!testMode && !TarotRuntime.cooldown().tryUse(player, cardId, cardCd, gcd, shiny)) {
            player.displayClientMessage(Component.translatable("message.miningdim.tarot.on_cooldown"), true);
            return false;
        }

        // 全过: 先提交卡牌和冷却并播放演出, 到揭牌之后再由服务端结算效果与经验。
        boolean queued = TarotRuntime.castManager().begin(player, TarotCastTiming.EFFECT_RESOLVE_TICKS,
                resolvingPlayer -> {
                    TarotRuntime.effectEngine().applyCard(resolvingPlayer.serverLevel(), resolvingPlayer,
                            data, quality, upright);
                    if (!testMode) {
                        TarotLeveling.grantPlayXp(resolvingPlayer, quality);
                    }
                },
                discardedPlayer -> refundCast(discardedPlayer, cardId, quality, upright, shiny));
        if (!queued) {
            player.displayClientMessage(Component.translatable("message.miningdim.tarot.casting"), true);
            return false;
        }

        TarotNetwork.sendCastVisual(player, new TarotCastVisualS2C(player.getId(), cardId, quality, upright));
        if (!testMode) {
            stack.shrink(1);
            // 消耗品语义 (复核追加修正, F079 原实现漏了这条通道): 打出即烧掉, 净额账本必须同步释放, 否则
            // 玩家用掉手上唯一一张牌之后, 卡包会把这个 cardId+quality 永远判成"重复"只发碎片。演出被打断的
            // 补偿路径 (refundCast) 会补发同规格的牌并同步重新 markCollected, 与此处对称。
            com.miningdim.job.tarot.pack.TarotPackSavedData.get(player.getServer().overworld())
                    .releaseCollected(player.getUUID(), cardId, quality);
        }
        return true;
    }

    /**
     * 演出被打断 (死亡/换维度/登出) 时的补偿 (F074): 提交时已扣的这张牌和这张牌的 CD 一并退回。不退 GCD ——
     * GCD 是防连甩的秒级闸, 且演出期本来就被 {@link TarotCastManager#isCasting} 挡住, 无需退。
     *
     * <p>牌的消耗 (stack.shrink) 必须仍在 tryPlay 提交时发生, 不能挪到结算回调里: 演出期玩家可以把这张牌
     * 塞进箱子或丢在地上, 到点再去扣就会扣到别人手里的栈或者干脆扣不到 (等于免费放牌); 所以只能在提交时
     * 扣、丢弃时用 {@link TarotCardItem#create} 补发一张同规格的牌回补。
     */
    private static void refundCast(ServerPlayer player, int cardId, TarotQuality quality, boolean upright,
                                    boolean shiny) {
        if (TarotConfig.TEST_MODE.get()) {
            // 测试模式下 tryPlay 既没扣牌也没占 CD (闸门 1/3 全被 testMode 短路), 这里补偿就会凭空造牌。
            return;
        }
        TarotRuntime.cooldown().clearCard(player.getUUID(), cardId, shiny);
        ItemHandlerHelper.giveItemToPlayer(player,
                TarotCardItem.create(TarotRegistry.TAROT_CARD.get(), cardId, quality, upright, player.getUUID()));
        // 与 tryPlay 提交时的 releaseCollected 对称补回: 演出被打断等于这张牌其实没被真正打出去/烧掉,
        // 净额账本必须补记回来, 否则玩家会在"牌还在手上"的情况下被卡包判成"未持有"而重复发到一张真牌。
        com.miningdim.job.tarot.pack.TarotPackSavedData.get(player.getServer().overworld())
                .markCollected(player.getUUID(), cardId, quality);
        player.displayClientMessage(Component.translatable("message.miningdim.tarot.cast_interrupted"), true);
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
