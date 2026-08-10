package com.miningdim.job.tarot.pack;

import com.miningdim.job.tarot.TarotArcana;
import com.miningdim.job.tarot.TarotCardItem;
import com.miningdim.job.tarot.TarotConfig;
import com.miningdim.job.tarot.TarotQuality;
import com.miningdim.job.tarot.TarotRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 服务端开包 RNG (TarotReader spec 第七章, 服务端权威)。
 *  - 普通包: 1 张 R + 随机 cardId + 随机正逆 (50/50, spec 13.4)。
 *  - 高级包: drawCount 张, 每张独立 SSR 概率 (其余 SR) + 派生包概率 (E<1 几何收敛, 派生只产高级包, 不升级)。
 *  - pity: 连续 N 个高级包未出 SSR 则下个高级包保底首张 SSR (spec 第七章; N 进 config)。
 *
 * 全部概率/张数从 {@link TarotConfig} 实时读 (C6 硬编码即缺陷)。开出的牌一律盖 ownerUUID (绑定; spec 第十章)。
 *
 * 重复牌转碎片 (spec 第七/十三章 6 反非酋承诺): 开包给牌前检测玩家是否已持同 cardId 的牌 (或本包已发过同 cardId),
 * 已持则改发 {@link TarotConfig#DUPLICATE_SHARD_REFUND} 张碎片而非重复牌。攒够碎片经 {@code /tarot exchange <id>}
 * (见 {@link com.miningdim.job.tarot.TarotSystem}) 确定性兑换指定牌 —— 给非洲玩家毕业线。
 *
 * pity 计数写入 {@link TarotPackSavedData}, 因而跨登出与服务端重启保留。
 * 派生包不产出物品 ItemStack 本身的 "包" —— 直接返回额外卡牌的 OpenResult.derivedPacks 计数, 由 use 层再开。
 */
public final class PackGachaService {

    /**
     * 一次开包结果: 给玩家的牌 + 重复牌转出的碎片总数 + 触发的派生高级包个数 (派生包就地再开, 并入产物)。
     * 重复牌不进 cards (改记 shardRefund), 由 use 层据 shardRefund 给等量碎片。
     */
    public record OpenResult(List<ItemStack> cards, int shardRefund, int derivedPacks) {
    }

    /**
     * 开一个普通包: 1 张 R, 随机 cardId + 随机正逆 (spec 第七章)。重复牌 (已持同 cardId) 改转碎片。
     */
    public OpenResult openCommon(ServerPlayer player, RandomSource rng) {
        List<ItemStack> out = new ArrayList<>(1);
        Set<Integer> grantedThisPack = new HashSet<>();
        int shards = grantOrRefund(player, randomCardId(rng), TarotQuality.R, rng.nextBoolean(), out, grantedThisPack);
        return new OpenResult(out, shards, 0);
    }

    /**
     * 开一个高级包 (从 {@link TarotConfig} 实时读旋钮): drawCount 张, 每张独立 SSR/SR; pity 命中则首张保底 SSR;
     * 计派生包个数 (几何收敛)。委派至显式参数重载 {@link #openAdvanced(ServerPlayer, RandomSource, int, double, double, int)}。
     */
    public OpenResult openAdvanced(ServerPlayer player, RandomSource rng) {
        return openAdvanced(player, rng,
                TarotConfig.ADVANCED_DRAW_COUNT.get(),
                TarotConfig.ADVANCED_SSR_CHANCE.get(),
                TarotConfig.ADVANCED_DERIVED_CHANCE.get(),
                TarotConfig.PITY_SSR_PACKS.get());
    }

    /**
     * 显式参数版高级包开包 (TDD 注入用: 可控 ssrChance/pityN 精确断言 pity 保底语义, 不依赖 config 加载)。
     * 派生期望硬约束 (spec 第七章): E = draws * derivedChance < 1 才几何收敛, 入口断言冒泡防印钞口。
     */
    public OpenResult openAdvanced(ServerPlayer player, RandomSource rng,
                                   int draws, double ssrChance, double derivedChance, int pityN) {
        if (draws * derivedChance >= 1.0D) {
            throw new IllegalStateException(
                    "advanced pack derived expectation must be < 1 (geometric convergence); got drawCount="
                            + draws + " * derivedChance=" + derivedChance + " = " + (draws * derivedChance));
        }
        TarotPackSavedData savedData = TarotPackSavedData.get(player.getServer().overworld());
        int streak = savedData.advancedNoSsrStreak(player.getUUID());

        List<ItemStack> out = new ArrayList<>(draws);
        Set<Integer> grantedThisPack = new HashSet<>();
        int shards = 0;
        boolean gotSsr = false;
        for (int i = 0; i < draws; i++) {
            // pity: 已连续 streak 个包未出 SSR 且本包首张, streak >= pityN 则首张保底 SSR。
            boolean pityForce = (i == 0 && streak >= pityN);
            boolean ssr = pityForce || rng.nextDouble() < ssrChance;
            TarotQuality q = ssr ? TarotQuality.SSR : TarotQuality.SR;
            if (ssr) {
                gotSsr = true;
            }
            // 重复牌转碎片 (spec 第七章): 已持同 cardId (或本包已发过) 改发碎片。品质保底 (pity/ssr) 仍按计数前判定,
            // 故 pity 不会因转碎片而失效 (pity 是 "本包必出 SSR 品质" 的承诺, 与具体 cardId 是否重复无关)。
            shards += grantOrRefund(player, randomCardId(rng), q, rng.nextBoolean(), out, grantedThisPack);
        }
        savedData.setAdvancedNoSsrStreak(player.getUUID(), gotSsr ? 0 : streak + 1);

        // 派生包: 每张产出独立判定一次派生 (期望 = draws * derivedChance, 已在入口断言 < 1 收敛)。
        int derived = 0;
        for (int i = 0; i < draws; i++) {
            if (rng.nextDouble() < derivedChance) {
                derived++;
            }
        }
        return new OpenResult(out, shards, derived);
    }

    /**
     * 开一个闪耀包: 不在此直接产牌 (spec 第七章: 开出后自选一张 SSR, 走 {@link ShinyPackSelectMenu})。
     * 本方法仅校验并构造一张玩家自选的 SSR 牌 (服务端校验合法性后给物)。
     *
     * @param cardId 玩家在自选 GUI 选的牌 (0-21, 任意大阿卡纳; spec 13.8 自选范围=任意 SSR)
     */
    public ItemStack grantShinySelection(ServerPlayer player, int cardId, RandomSource rng) {
        if (cardId < 0 || cardId >= TarotArcana.COUNT) {
            throw new IllegalArgumentException("shiny pack selection cardId out of range: " + cardId);
        }
        // 闪耀包是玩家显式自选 (青辉石高价), 不走重复转碎片 (自选即想要该牌, 转碎片反损玩家利益): 直接给 SSR。
        return makeCard(player, cardId, TarotQuality.SSR, rng.nextBoolean());
    }

    /**
     * 给牌或转碎片 (spec 第七章重复牌转碎片): 玩家已持同 cardId (背包/副手) 或本包已发过同 cardId 则不发重复牌,
     * 改记 {@link TarotConfig#DUPLICATE_SHARD_REFUND} 张碎片返还; 否则发牌并记入本包已发集合。
     *
     * @return 本次因重复转出的碎片数 (0 = 发了真牌)
     */
    private static int grantOrRefund(ServerPlayer player, int cardId, TarotQuality quality, boolean upright,
                                     List<ItemStack> out, Set<Integer> grantedThisPack) {
        if (playerOwnsCard(player, cardId) || grantedThisPack.contains(cardId)) {
            return TarotConfig.DUPLICATE_SHARD_REFUND.get();
        }
        out.add(makeCard(player, cardId, quality, upright));
        grantedThisPack.add(cardId);
        return 0;
    }

    /** 玩家背包 (含副手) 是否已持有某 cardId 的塔罗牌 (任意品质/朝向; 重复判定按身份不按品质)。 */
    private static boolean playerOwnsCard(ServerPlayer player, int cardId) {
        for (ItemStack s : player.getInventory().items) {
            if (isCard(s, cardId)) {
                return true;
            }
        }
        for (ItemStack s : player.getInventory().offhand) {
            if (isCard(s, cardId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCard(ItemStack stack, int cardId) {
        return !stack.isEmpty() && stack.getItem() instanceof TarotCardItem
                && TarotCardItem.cardId(stack) == cardId;
    }

    private static int randomCardId(RandomSource rng) {
        return rng.nextInt(TarotArcana.COUNT);
    }

    private static ItemStack makeCard(ServerPlayer owner, int cardId, TarotQuality quality, boolean upright) {
        return TarotCardItem.create(TarotRegistry.TAROT_CARD.get(), cardId, quality, upright, owner.getUUID());
    }
}
